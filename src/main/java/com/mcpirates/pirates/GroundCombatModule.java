package com.mcpirates.pirates;

import com.mcpirates.MCPDataKeys;
import com.mcpirates.MCPirates;
import com.mcpirates.airship.interfaces.AirshipKind;
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
 * Ground defenders that spawn next to a dormant pirate airship when a player approaches
 * on foot. Air arrival triggers {@link com.mcpirates.airship.AirshipLiftoffTrigger} instead.
 *
 * <p>The "prize fight": kill the defenders and the airship is yours. The captain mob
 * carries {@link MCPDataKeys#CAPTAIN_TAG} + {@link MCPDataKeys#CAPTAIN_ANCHOR_NBT_KEY},
 * so {@code CaptainDeath} drops the seal and {@code DefeatedAirships} marks the ship cleared
 * regardless of where the fight happened.
 *
 * <p>Spawn deltas are lever-relative to share frame with the kind's hull bounds; Y is read
 * from the heightmap so defenders land on whatever surface the ship parked on. If the ship
 * later lifts off, the trigger calls {@link #despawn} so air and ground crews don't double up.
 */
public final class GroundCombatModule {

    public static final GroundCombatModule SHARED = new GroundCombatModule();

    public static final int LEASH_RADIUS = 30;

    /** Immutable; {@link #despawn} tolerates already-dead entries. */
    public record GroundEngagement(List<UUID> attackerUuids) {}

    /** Lever-relative deltas. |X| >= 4 keeps spawns clear of both small ship hulls
     *  (airship_small body X=-3..+1, crossbow_board body X=-2..+3). Y comes from the
     *  heightmap. */
    private static final BlockPos[] SPAWN_DELTAS = {
            new BlockPos(+4, 0,  0),  // vindicator: east flank, midship
            new BlockPos(+4, 0, -3),  // crossbow pillager 1: east flank, forward
            new BlockPos(-4, 0, -2),  // crossbow pillager 2: west flank, forward
            new BlockPos(+5, 0, +2),  // captain: east flank, aft
    };

    private GroundCombatModule() {}

    /** Spawn defenders in the parent ServerLevel (not a SubLevel). */
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

    /** Resolves Y via MOTION_BLOCKING_NO_LEAVES so leaves never pin a defender in a canopy. */
    private static BlockPos resolveGroundPos(ServerLevel level, BlockPos leverWorldPos,
                                             BlockPos delta, Rotation rotation) {
        BlockPos rotated = delta.rotate(rotation);
        int x = leverWorldPos.getX() + rotated.getX();
        int z = leverWorldPos.getZ() + rotated.getZ();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    /** Remove still-living attackers; returns the count actually removed. */
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

    /** True iff every attacker is dead or gone. */
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
        // finalizeSpawn re-rolls MAINHAND to the default iron axe; re-apply sword after.
        if (sword) {
            v.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            v.setDropChance(EquipmentSlot.MAINHAND, 0f);
        }
        if (captain) {
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
        p.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
        boolean added = level.addFreshEntity(p);
        if (!added) {
            MCPirates.LOGGER.warn("ground combat: addFreshEntity failed for pillager at {}", pos);
            return null;
        }
        return p;
    }

    /** Wire path-home + drop-target-when-outside-leash (neither is on vanilla by default). */
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
