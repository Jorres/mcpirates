package com.mcpirates.pirates;

import com.mcpirates.airship.Airship;
import com.mcpirates.pirates.CaptainSpawner.AnchoredEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Pillager;

/**
 * Per-ship pirate driver. Walks each {@link AnchoredEntity} on the ship and calls
 * {@code role.tick(...)}, resolving the live pillager by UUID.
 *
 * <h2>Where this fits</h2>
 *
 * <p>{@link com.mcpirates.airship.AirshipBrain#tickShip} owns the flight control loop
 * (state machine, throttle, clutches, ship-level cannon combat). After it finishes its
 * own per-tick work it calls {@link #tickShip(Airship, ServerPlayer, long)} to give each
 * pirate a turn. Splitting the two means:
 *
 * <ul>
 *   <li>Ship physics and crew behaviour are decoupled — the ship can pursue / orbit
 *       without knowing what its crew is doing, and a crew member can decide independently
 *       (target selection, friendly-fire skip) without sniffing through the brain.</li>
 *   <li>Crewless ships have zero per-pirate overhead — the loop terminates immediately
 *       on an empty {@code anchoredEntities} list.</li>
 * </ul>
 *
 * <p>The target argument is the one the ship's brain already computed via
 * {@code findEnemyPlayerOnAirship}. Reusing it avoids each role re-running the player
 * scan and keeps targets consistent across the crew (otherwise different pirates could
 * end up shooting at different players, which feels wrong for a coordinated crew).
 */
public final class PirateBrain {

    private PirateBrain() {}

    /**
     * Tick every anchored pirate on {@code ship}.
     *
     * @param ship    the airship.
     * @param target  current target the ship's flight brain acquired this tick, or
     *                {@code null} when in LIFTOFF/RETURN/HOVER or no player in range.
     * @param now     monotonic per-server tick.
     */
    public static void tickShip(Airship ship, LivingEntity target, long now) {
        if (ship.anchoredEntities.isEmpty()) return;
        ServerLevel parentLevel = ship.parentLevel;
        for (AnchoredEntity ae : ship.anchoredEntities) {
            Entity entity = parentLevel.getEntity(ae.uuid());
            if (!(entity instanceof Pillager pillager) || entity.isRemoved() || !entity.isAlive()) {
                continue;
            }
            ae.role().tick(parentLevel, ship, ae, pillager, target, now);
        }
    }
}
