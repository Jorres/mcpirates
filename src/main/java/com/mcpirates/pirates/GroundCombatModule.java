package com.mcpirates.pirates;

import com.mcpirates.MCPDataKeys;
import com.mcpirates.MCPirates;
import com.mcpirates.airship.kind.AirshipKind;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Ground-side defenders that spawn next to a dormant pirate airship the first time a
 * player approaches it <em>on foot</em>. A player arriving in their own airship triggers
 * the existing in-air liftoff path instead — see {@link com.mcpirates.airship.AirshipLiftoffTrigger}.
 *
 * <h2>Gameplay role</h2>
 *
 * Without this module a freshly-arrived on-foot player would have no way to engage the
 * pirate airship (they can't board it, and the liftoff trigger is gated on the player
 * being on a SubLevel). The ground module is the "prize fight": kill the defenders and
 * the airship is yours — undefended, no deck pillagers spawn. The bounty captain on
 * the ground is fully equivalent to his deck counterpart: same {@link MCPDataKeys#CAPTAIN_TAG},
 * same {@link MCPDataKeys#CAPTAIN_ANCHOR_NBT_KEY persistent anchor key}, so killing him
 * drops the seal and marks the ship defeated for {@code DefeatedAirships}. The
 * sheriff-bounty loop works whether the player engages on foot or in the air.
 *
 * <h2>Loadout (v0.1, shared between airship_small and crossbow_board)</h2>
 *
 * <ul>
 *   <li>1× {@link Vindicator} — default iron axe, no armour. Standard melee threat.</li>
 *   <li>2× {@link Pillager} — default crossbow. Ranged pressure; spread spawn so they
 *       don't share the same firing arc.</li>
 *   <li>1× {@link Vindicator} "captain" — iron sword + iron helm + iron chestplate,
 *       custom-named, captain-tagged. Tankier, last-man-standing, drops the seal.</li>
 * </ul>
 *
 * <p>Total raw threat: ~13 dmg from each vindicator melee, ~9 dmg per crossbow bolt,
 * captain has ~44% damage reduction from iron armour. Designed to bruise an early
 * leather-armoured player but be killable with a shield + iron sword.
 *
 * <h2>Spawn frame — lever-relative, not anchor-relative</h2>
 *
 * <p>{@link #SPAWN_DELTAS} are lever-relative because the kind's {@link AirshipKind#glueMin
 * glueMin}/{@link AirshipKind#glueMax glueMax} hull bounds are also lever-relative —
 * sharing the frame lets us read off "outside the hull" trivially. Y is resolved via
 * the world heightmap so defenders land on whatever surface the ship is parked on (its
 * stone-brick pad in worldgen, or natural terrain in a test setup), avoiding the
 * anchor-vs-lever-vs-pad Y-offset minefield that bit the first iteration.
 *
 * <h2>Despawn on liftoff</h2>
 *
 * If the airship eventually lifts off (another player flies up in their own ship), the
 * in-air branch first calls {@link #despawn} on the engagement so the ground defenders
 * don't double up with the air crew.
 */
public final class GroundCombatModule {

    /** Shared instance — the first two ships (airship_small, crossbow_board) point at
     *  this. If a later ship wants a different loadout, instantiate a separate one
     *  rather than mutating shared state. */
    public static final GroundCombatModule SHARED = new GroundCombatModule();

    /** Defenders' leash radius around their spawn point. Wide enough that vanilla
     *  vindicator/pillager combat AI can chase a player who steps back to ~kite range,
     *  tight enough that a sprinting retreat outpaces them and they wander home
     *  instead of disappearing into the world. */
    public static final int LEASH_RADIUS = 30;

    /**
     * Returned from {@link #spawn} so the trigger can despawn living attackers later.
     * The list is immutable; individual mobs may die in the meantime — {@link #despawn}
     * just skips removed/dead entries.
     */
    public record GroundEngagement(List<UUID> attackerUuids) {}

    /** Lever-relative (NBT-frame) spawn deltas. X chosen clearly outside both kinds'
     *  actual <em>body</em> X span (which is tighter than {@link AirshipKind#glueMin
     *  glueMin}/{@link AirshipKind#glueMax glueMax}!): airship_small body lever-rel
     *  X=-3..+1, crossbow_board body lever-rel X=-2..+3. So safe exterior X is
     *  X &lt;= -4 (west of both bodies) or X &gt;= +4 (east of both). A delta of -3
     *  lands on airship_small's western hull column — the iteration-1 bug that put a
     *  pillager on top of the keel. Z spreads the line along the ship's flank. Y is
     *  unused (the spawn path pulls it from the world heightmap at the X/Z column). */
    private static final BlockPos[] SPAWN_DELTAS = {
            new BlockPos(+4, 0,  0),  // vindicator: east flank, midship
            new BlockPos(+4, 0, -3),  // crossbow pillager 1: east flank, forward
            new BlockPos(-4, 0, -2),  // crossbow pillager 2: west flank, forward
            new BlockPos(+5, 0, +2),  // captain: east flank, aft, one block further out
    };

    private GroundCombatModule() {}

    /**
     * Spawn the full ground-combat loadout near the dormant ship. Mobs use vanilla AI
     * ({@code NoAi=false}) so they path toward and engage the player on their own.
     * Persistence is forced so a player retreating ~30 blocks doesn't despawn them.
     *
     * @param level         parent ServerLevel (NOT a SubLevel — these defenders live in
     *                      the normal world).
     * @param leverWorldPos world-space position of the ship's primary lever. Same frame
     *                      the kind's hull bounds use, so {@link #SPAWN_DELTAS} land
     *                      outside the hull by construction.
     * @param rotation      jigsaw rotation of the parent structure, applied to the
     *                      X/Z deltas.
     * @param kind          supplies a name string for diagnostic logs.
     */
    public GroundEngagement spawn(ServerLevel level, BlockPos leverWorldPos,
                                  Rotation rotation, AirshipKind kind) {
        List<UUID> uuids = new ArrayList<>(SPAWN_DELTAS.length);

        Vindicator vindicator = makeVindicator(level,
                resolveGroundPos(level, leverWorldPos, SPAWN_DELTAS[0], rotation),
                /*armoured=*/false, /*sword=*/false, /*name=*/null,
                /*captain=*/false, leverWorldPos);
        if (vindicator != null) uuids.add(vindicator.getUUID());

        for (int i = 1; i <= 2; i++) {
            Pillager pillager = makeCrossbowPillager(level,
                    resolveGroundPos(level, leverWorldPos, SPAWN_DELTAS[i], rotation));
            if (pillager != null) uuids.add(pillager.getUUID());
        }

        Vindicator captain = makeVindicator(level,
                resolveGroundPos(level, leverWorldPos, SPAWN_DELTAS[3], rotation),
                /*armoured=*/true, /*sword=*/true,
                /*name=*/Component.literal("Pirate Captain"),
                /*captain=*/true, leverWorldPos);
        if (captain != null) uuids.add(captain.getUUID());

        MCPirates.LOGGER.info(
                "ground combat: spawned {} defenders for {} lever {} (rotation={})",
                uuids.size(), kind.name(), leverWorldPos, rotation);
        return new GroundEngagement(Collections.unmodifiableList(uuids));
    }

    /** Apply rotation to the X/Z delta, then query the world heightmap so the mob
     *  stands on whatever surface is there — pad block, grass, sand — rather than
     *  hardcoding a Y offset that only works for one kind's pad layout. Uses
     *  MOTION_BLOCKING_NO_LEAVES (the heightmap mobs themselves consult for landing)
     *  rather than WORLD_SURFACE — leaves shouldn't pin a vindicator in the canopy
     *  if a ship ever parks above a forest. */
    private static BlockPos resolveGroundPos(ServerLevel level, BlockPos leverWorldPos,
                                             BlockPos delta, Rotation rotation) {
        BlockPos rotated = delta.rotate(rotation);
        int x = leverWorldPos.getX() + rotated.getX();
        int z = leverWorldPos.getZ() + rotated.getZ();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    /**
     * Remove any still-living attackers from {@code engagement}. Already-dead or
     * already-removed entries are silently skipped. Returns the count actually
     * removed for log readability.
     */
    public int despawn(ServerLevel level, GroundEngagement engagement) {
        int removed = 0;
        for (UUID id : engagement.attackerUuids()) {
            Entity e = level.getEntity(id);
            if (e == null || e.isRemoved()) continue;
            e.discard();
            removed++;
        }
        return removed;
    }

    /** True iff every attacker in the engagement is dead or gone. The trigger uses
     *  this to decide whether to re-spawn the module (it doesn't — once cleared, the
     *  ground is the player's reward) and for log clarity. */
    public boolean isCleared(ServerLevel level, GroundEngagement engagement) {
        for (UUID id : engagement.attackerUuids()) {
            Entity e = level.getEntity(id);
            if (e instanceof LivingEntity le && !le.isRemoved() && le.isAlive()) {
                return false;
            }
        }
        return true;
    }

    private Vindicator makeVindicator(ServerLevel level, BlockPos pos,
                                      boolean armoured, boolean sword, Component name,
                                      boolean captain, BlockPos shipLeverWorldPos) {
        Vindicator v = EntityType.VINDICATOR.create(level);
        if (v == null) {
            MCPirates.LOGGER.warn("ground combat: EntityType.VINDICATOR.create returned null at {}", pos);
            return null;
        }
        v.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0f, 0f);
        v.restrictTo(pos, LEASH_RADIUS);
        attachLeashBehavior(v);
        if (sword) {
            v.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        }
        if (armoured) {
            v.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            v.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        }
        if (name != null) {
            v.setCustomName(name);
            v.setCustomNameVisible(true);
        }
        // finalizeSpawn equips the vindicator's vanilla iron-axe default + sets attack
        // attributes. Passing null reason because this isn't a natural spawn.
        v.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
        // finalizeSpawn may overwrite our mainhand with the default iron axe — re-apply
        // the sword AFTER if requested. (We deliberately don't re-apply armour because
        // finalizeSpawn doesn't touch HEAD/CHEST when no spawn-armour table fires for
        // EVENT, but the sword goes in MAINHAND which is always re-rolled.)
        if (sword) {
            v.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            // Lock the weapon so vindicator's pickup-equipment AI doesn't swap it back.
            v.setDropChance(EquipmentSlot.MAINHAND, 0f);
        }
        if (captain) {
            // Tag + anchor stamp mirror CaptainSpawner's deck-captain setup so CaptainDeath
            // drops the seal and DefeatedAirships marks the ship as cleared regardless of
            // whether the player engaged on foot or in the air.
            v.addTag(MCPDataKeys.CAPTAIN_TAG);
            v.getPersistentData().putLong(MCPDataKeys.CAPTAIN_ANCHOR_NBT_KEY, shipLeverWorldPos.asLong());
        }
        boolean added = level.addFreshEntity(v);
        if (!added) {
            MCPirates.LOGGER.warn("ground combat: addFreshEntity failed for vindicator at {}", pos);
            return null;
        }
        return v;
    }

    private Pillager makeCrossbowPillager(ServerLevel level, BlockPos pos) {
        Pillager p = EntityType.PILLAGER.create(level);
        if (p == null) {
            MCPirates.LOGGER.warn("ground combat: EntityType.PILLAGER.create returned null at {}", pos);
            return null;
        }
        p.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0f, 0f);
        p.restrictTo(pos, LEASH_RADIUS);
        attachLeashBehavior(p);
        // finalizeSpawn equips the crossbow + the pillager's default cape & arm offsets.
        p.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
        boolean added = level.addFreshEntity(p);
        if (!added) {
            MCPirates.LOGGER.warn("ground combat: addFreshEntity failed for pillager at {}", pos);
            return null;
        }
        return p;
    }

    /** Wire restriction-based path-home + drop-target-when-outside-leash. Vanilla
     *  Vindicator/Pillager omit both. */
    private static void attachLeashBehavior(Mob mob) {
        mob.goalSelector.addGoal(5, new MoveTowardsRestrictionGoal(
                (net.minecraft.world.entity.PathfinderMob) mob, 1.0));
        mob.targetSelector.addGoal(0, new LeashedTargetGoal(mob, LEASH_RADIUS));
    }

    private static final class LeashedTargetGoal extends Goal {
        private final Mob mob;
        private final double leashRadiusSq;

        LeashedTargetGoal(Mob mob, int leashRadius) {
            this.mob = mob;
            this.leashRadiusSq = (double) leashRadius * leashRadius;
            this.setFlags(EnumSet.noneOf(Goal.Flag.class));
        }

        @Override
        public boolean canUse() {
            if (!mob.hasRestriction() || mob.getTarget() == null) return false;
            BlockPos home = mob.getRestrictCenter();
            double dx = mob.getX() - (home.getX() + 0.5);
            double dz = mob.getZ() - (home.getZ() + 0.5);
            return dx * dx + dz * dz > leashRadiusSq;
        }

        @Override
        public boolean canContinueToUse() { return false; }

        @Override
        public void start() {
            mob.setTarget(null);
        }
    }
}
