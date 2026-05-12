package com.mcpirates.pirates.roles;

import com.mcpirates.airship.Airship;
import com.mcpirates.pirates.CaptainSpawner.AnchoredEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Per-pirate behaviour model. Each anchored pillager on an airship carries one
 * {@code PirateRole} instance; {@link com.mcpirates.pirates.PirateBrain} drives them
 * every server tick. Compared to the older "external combat module fires every pirate
 * uniformly" design, role-per-pirate lets each crewmate make its own decisions
 * (target lock, friendly-fire skip, future deck movement) without a centralised
 * orchestrator.
 *
 * <h2>Why not vanilla {@code Goal}s</h2>
 *
 * <p>Vanilla AI goals lean heavily on {@code Mob#getNavigation().moveTo(...)}, which
 * fights Sable's per-tick {@code sable$setPlotPosition} rebind (the captain/crewmate
 * are anchored to plot coords, not world coords — vanilla pathfinding would compute
 * routes in plot space against the SubLevel's tiny captive level). Pillagers stay
 * {@code NoAi=true}; the role drives them directly via {@code yRot}/{@code xRot}
 * writes and manual projectile spawning. Movement, when we add it, will mutate
 * the entity's plot position via the same {@code sable$setPlotPosition} channel —
 * still bypassing navigation.
 *
 * <h2>State</h2>
 *
 * <p>Each role instance is per-pirate (not a singleton like {@code CombatBehavior}),
 * so it may hold mutable state (next fire tick, current sub-goal, etc.). Instances
 * live in {@link com.mcpirates.pirates.CaptainSpawner.AnchoredEntity} and survive
 * chunk reloads only because the {@link Airship} record holds the list — they are
 * NOT serialised; on full server restart everything resets.
 */
public interface PirateRole {

    /**
     * Per-server-tick driver.
     *
     * @param parentLevel  the world the pillager actually lives in (Sable stores SubLevel
     *                     entities in the parent {@code ServerLevel} at plot-local coords,
     *                     so projectiles spawned here travel through real world space).
     * @param ship         the airship the pirate is anchored to. Holds current state
     *                     ({@code ship.state}), peer roster ({@code ship.anchoredEntities}),
     *                     and the live SubLevel pose for world↔plot coord conversion.
     * @param self         the anchor record (uuid + stable plot-local position). Roles
     *                     resolve world-rendered position by transforming {@code self.plotPos()}
     *                     through the SubLevel's logical pose.
     * @param pillager     the live entity, resolved by uuid. Roles that need to mutate
     *                     rotation, items, etc. poke this directly.
     * @param target       current target the ship's brain has acquired, or {@code null}.
     *                     The role decides whether to act on it.
     * @param now          {@code event.getServer().getTickCount()} — monotonic per-server tick.
     */
    void tick(ServerLevel parentLevel, Airship ship, AnchoredEntity self, Pillager pillager,
              ServerPlayer target, long now);

    /** Short label used in spawn / re-anchor log lines. */
    String name();

    /** What the pillager holds in its main hand at spawn. Defaults to a crossbow (most
     *  roles either fight with one or carry one for the silhouette). Override with
     *  {@link ItemStack#EMPTY} for unarmed roles (e.g. cannoneers — see
     *  {@link CannoneerRole}). The spawner also drops the "aggressive" arms-raised pose
     *  for unarmed pirates so they look like crew rather than threats. */
    default ItemStack mainHandItem() { return new ItemStack(Items.CROSSBOW); }
}
