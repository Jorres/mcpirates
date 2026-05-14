package com.mcpirates.pirates;

import com.mcpirates.MCPDataKeys;
import com.mcpirates.MCPirates;
import com.mcpirates.airship.kind.AirshipKind;
import com.mcpirates.pirates.roles.CannoneerRole;
import com.mcpirates.pirates.roles.CrossbowmanRole;
import com.mcpirates.pirates.roles.PirateRole;
import com.mcpirates.util.FunnyNames;
import dev.ryanhcode.sable.mixinterface.entity.entities_stick_sublevels.EntityStickExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Seat-driven pirate spawn. Every airship gets one pillager per Create seat inside its
 * glue volume, with role determined by seat colour and proximity to cannons:
 *
 * <ul>
 *   <li><strong>Black seat → captain.</strong> Tagged {@link MCPDataKeys#CAPTAIN_TAG} so
 *       {@link CaptainDeath} drops a seal + marks the airship defeated. Wields a crossbow
 *       and runs {@link CrossbowmanRole}. Each ship NBT must have <em>exactly one</em>
 *       black seat — zero or more than one is treated as a broken blueprint and no crew
 *       spawns at all (loud error, no salvage path).</li>
 *   <li><strong>Gray / light-gray seat near a cannon → cannoneer.</strong> Each cannon
 *       mount claims its nearest unclaimed crew seat within {@link #CANNONEER_BIND_RANGE_SQ}
 *       blocks. The pillager there is unarmed, runs {@link CannoneerRole}, and is
 *       registered in {@link com.mcpirates.airship.Airship#cannoneerByMount}. Killing
 *       the cannoneer permanently silences that cannon (per-mount, not all of them).</li>
 *   <li><strong>Gray / light-gray seat elsewhere → crossbowman.</strong> Untagged,
 *       crossbow-wielding, fires in staggered volleys.</li>
 * </ul>
 *
 * <h2>Why colour + proximity (and not just colour, or just proximity)</h2>
 *
 * <p>Colour cleanly answers "which seat is the captain?" — explicit, rotation-independent,
 * doesn't depend on the NBT designer remembering to put the helm at the highest Y. Cannon
 * binding still uses proximity because there's no natural colour-mapping for "this seat
 * belongs to that cannon" — and the geometric pairing is intuitive (sit next to the gun
 * you're operating).
 *
 * <h2>Anchoring + per-pirate behaviour</h2>
 *
 * <p>Pillagers stay {@code NoAi=true} + {@code NoGravity=true} and are anchored via
 * Sable's {@code sable$setPlotPosition(Vec3)}. Sable's {@code Entity.tick} mixin re-derives
 * world position each tick from plot pos. The plot-position field isn't persisted to NBT
 * so {@link com.mcpirates.airship.AirshipBrain#tickShip} re-applies it from
 * {@link AnchoredEntity#plotPos} when observed null. See {@link PirateRole} for why we
 * don't reuse vanilla {@code Goal}s.
 */
public final class CaptainSpawner {

    /**
     * Stable handle to a pillager anchored on a moving SubLevel.
     *
     * <p>{@link #role} is the per-pirate behaviour model. Each instance may be unique
     * per-pirate ({@link CrossbowmanRole} carries reload state) or a shared singleton
     * ({@link CannoneerRole} is stateless).
     */
    public record AnchoredEntity(UUID uuid, Vec3 plotPos, PirateRole role) {}

    /**
     * Bundle returned from {@link #spawn} — the anchored entities for the brain to drive,
     * plus the cannon→cannoneer binding for {@link com.mcpirates.airship.Airship}'s
     * {@code isMountManned} queries.
     */
    public record CrewSpawnResult(List<AnchoredEntity> anchors,
                                  Map<BlockPos, UUID> cannoneerByMount) {}

    /** Stagger between crossbowman firing starts (ticks). 7 ticks = 0.35 s, so a 4-crew
     *  volley spreads ~1 s from first shot to last — overlapping reloads. */
    private static final int CREW_FIRE_STAGGER_TICKS = 7;

    /** Max squared-distance from a cannon mount to bind a seat as cannoneer station.
     *  16 = 4 blocks Euclidean — generous enough to absorb deck thickness and 1–2 blocks
     *  of horizontal offset from the mount. A seat farther than that is treated as a
     *  crossbowman / captain station, and the cannon goes un-manned (no fire). */
    private static final double CANNONEER_BIND_RANGE_SQ = 16.0;


    private CaptainSpawner() {}

    /**
     * Scan seats, bind cannons, spawn the crew.
     *
     * @param subLevel        SubLevel returned by the assembler.
     * @param leverWorldPos   pre-assembly world pos of the airship's primary lever —
     *                        airship identity stamped onto each pirate's persistentData.
     * @param assemblyOffset  offset returned by the assembler.
     * @param rotation        jigsaw rotation of the structure.
     * @param kind            supplies the glue AABB used to bound the seat scan.
     * @param slCannonMounts  cannon mount positions in SubLevel-local frame (already
     *                        assembled). Each gets its nearest seat as cannoneer station.
     */
    public static CrewSpawnResult spawn(SubLevel subLevel, BlockPos leverWorldPos,
                                        BlockPos assemblyOffset, Rotation rotation,
                                        AirshipKind kind, List<BlockPos> slCannonMounts) {
        Level inner = subLevel.getLevel();
        if (inner == null) {
            MCPirates.LOGGER.error("crew spawn: SubLevel.getLevel() returned null for {}", kind.name());
            return new CrewSpawnResult(List.of(), Map.of());
        }
        Seats.SeatScan scan = scanSeatsInGlueBox(subLevel, leverWorldPos, assemblyOffset, rotation, kind);
        if (scan.captainSeats().size() != 1) {
            MCPirates.LOGGER.error(
                    "ship {} has {} BLACK seats — exactly one is required. No crew spawned. "
                    + "Place one BLACK seat for the captain, plus GRAY/LIGHT_GRAY seats: "
                    + "one near each cannon mount (cannoneer), the rest anywhere for crossbowmen.",
                    kind.name(), scan.captainSeats().size());
            return new CrewSpawnResult(List.of(), Map.of());
        }
        BlockPos captainSeat = scan.captainSeats().get(0);
        List<BlockPos> crewSeats = new ArrayList<>(scan.crewSeats());

        // Bind cannons → nearest unclaimed CREW seat (captain seat is reserved). The black
        // seat is never a cannoneer station even if it happens to sit next to a cannon —
        // colour is the authoritative role marker.
        Set<BlockPos> reserved = new HashSet<>();
        Map<BlockPos, BlockPos> cannonToSeat = new HashMap<>();
        for (BlockPos mount : slCannonMounts) {
            BlockPos best = null;
            double bestDistSq = Double.MAX_VALUE;
            for (BlockPos seat : crewSeats) {
                if (reserved.contains(seat)) continue;
                double d = seat.distSqr(mount);
                if (d < bestDistSq && d <= CANNONEER_BIND_RANGE_SQ) {
                    bestDistSq = d;
                    best = seat;
                }
            }
            if (best != null) {
                cannonToSeat.put(mount, best);
                reserved.add(best);
            } else {
                MCPirates.LOGGER.warn(
                        "{}: cannon mount {} has no GRAY/LIGHT_GRAY seat within {} blocks — that cannon will not fire",
                        kind.name(), mount, Math.sqrt(CANNONEER_BIND_RANGE_SQ));
            }
        }

        long now = inner.getGameTime();
        int totalSeats = scan.captainSeats().size() + scan.crewSeats().size();
        List<AnchoredEntity> anchors = new ArrayList<>(totalSeats);
        Map<BlockPos, UUID> cannoneerByMount = new HashMap<>(cannonToSeat.size());

        // Spawn cannoneers.
        for (Map.Entry<BlockPos, BlockPos> e : cannonToSeat.entrySet()) {
            BlockPos mount = e.getKey();
            BlockPos seat = e.getValue();
            AnchoredEntity ae = spawnAnchoredPillager(
                    inner, subLevel, seatToFeetPlot(seat), leverWorldPos,
                    /*tag=*/ null,
                    /*name=*/ Component.literal("Pirate Gunner"),
                    CannoneerRole.INSTANCE,
                    /*roleStamp=*/ "cannoneer",
                    /*cannonMountSlPos=*/ mount);
            if (ae != null) {
                anchors.add(ae);
                cannoneerByMount.put(mount, ae.uuid());
            }
        }

        // Captain on the (single) black seat.
        AnchoredEntity captain = spawnAnchoredPillager(
                inner, subLevel, seatToFeetPlot(captainSeat), leverWorldPos,
                MCPDataKeys.CAPTAIN_TAG,
                Component.literal(FunnyNames.nextPirateCaptainName(inner.getRandom())),
                new CrossbowmanRole(now),
                /*roleStamp=*/ "captain",
                /*cannonMountSlPos=*/ null);
        if (captain != null) anchors.add(captain);

        // Crossbowmen on remaining (unclaimed) crew seats. Stagger reloads so volleys
        // arrive spread across ~1 s rather than as one big simultaneous flight.
        int volleyStaggerIndex = 1;
        for (BlockPos seat : crewSeats) {
            if (reserved.contains(seat)) continue;
            PirateRole role = new CrossbowmanRole(now + (long) volleyStaggerIndex * CREW_FIRE_STAGGER_TICKS);
            volleyStaggerIndex++;
            AnchoredEntity ae = spawnAnchoredPillager(
                    inner, subLevel, seatToFeetPlot(seat), leverWorldPos,
                    /*tag=*/ null,
                    Component.literal("Pirate Crewmate"),
                    role,
                    /*roleStamp=*/ "crewmate",
                    /*cannonMountSlPos=*/ null);
            if (ae != null) anchors.add(ae);
        }

        MCPirates.LOGGER.info(
                "{} crew: spawned {} (1 captain + {}/{} cannoneers + remainder crossbowmen) from {} seats",
                kind.name(), anchors.size(),
                cannoneerByMount.size(), slCannonMounts.size(), totalSeats);
        return new CrewSpawnResult(anchors, cannoneerByMount);
    }

    /** Pillager stands ON the seat block: feet at (x+0.5, y+1, z+0.5) — same convention
     *  vanilla uses when a mob mounts a seat block. */
    private static Vec3 seatToFeetPlot(BlockPos seat) {
        return new Vec3(seat.getX() + 0.5, seat.getY() + 1.0, seat.getZ() + 0.5);
    }

    /**
     * Spawn one pillager at the given plot-local position, anchor it via Sable, equip
     * per-role, and stamp the airship anchor for the defeat-tracking pipeline.
     */
    private static AnchoredEntity spawnAnchoredPillager(
            Level inner, SubLevel subLevel,
            Vec3 plotPos,
            BlockPos airshipAnchorWorldPos,
            String tag,
            Component customName,
            PirateRole role,
            String roleStamp,
            BlockPos cannonMountSlPos) {
        Vec3 initialWorldPos = subLevel.logicalPose().transformPosition(plotPos);

        Pillager pillager = EntityType.PILLAGER.create(inner);
        if (pillager == null) {
            MCPirates.LOGGER.warn("CaptainSpawner: EntityType.PILLAGER.create returned null for role={}",
                    role.name());
            return null;
        }
        pillager.moveTo(initialWorldPos.x, initialWorldPos.y, initialWorldPos.z, /*yaw=*/0.0f, /*pitch=*/0.0f);
        pillager.setNoAi(true);
        pillager.setNoGravity(true);
        pillager.setPersistenceRequired();
        if (tag != null) pillager.addTag(tag);
        ItemStack mainHand = role.mainHandItem();
        if (!mainHand.isEmpty()) {
            pillager.setItemSlot(EquipmentSlot.MAINHAND, mainHand);
        }
        pillager.setCustomName(customName);
        pillager.setCustomNameVisible(true);
        // Aggressive flag → vanilla Pillager.getArmPose() returns CROSSBOW_HOLD when also
        // holding a crossbow (arms raised). Cannoneers have no weapon and don't want the
        // attacking pose — they're crew, not shooters. So only set aggressive for armed
        // roles. NoAi=true keeps the flag from being ticked back off.
        pillager.setAggressive(!mainHand.isEmpty());
        // Stamp the airship anchor for CaptainDeath / DefeatedAirships. Harmless for crew
        // (their death isn't watched) — lets us later promote a crewmate to captain
        // without touching the spawn path.
        pillager.getPersistentData().putLong(MCPDataKeys.CAPTAIN_ANCHOR_NBT_KEY, airshipAnchorWorldPos.asLong());
        pillager.getPersistentData().putString(MCPDataKeys.CREW_ROLE_NBT_KEY, roleStamp);
        if (cannonMountSlPos != null) {
            pillager.getPersistentData().putLong(MCPDataKeys.CREW_CANNON_MOUNT_NBT_KEY, cannonMountSlPos.asLong());
        }

        boolean added = inner.addFreshEntity(pillager);
        // Bind to the SubLevel AFTER addFreshEntity so the entity's full state is in place
        // and Sable's @Unique mixin field has been initialised.
        ((EntityStickExtension) pillager).sable$setPlotPosition(plotPos);

        MCPirates.LOGGER.info(
                "{} spawned: subLevel={} plotPos={} initialWorldPos={} added={}",
                role.name(), subLevel.getUniqueId(), plotPos, initialWorldPos, added);

        return added ? new AnchoredEntity(pillager.getUUID(), plotPos, role) : null;
    }

    /** Rotate the kind's NBT-frame glue corners into SubLevel space, sort componentwise,
     *  then scan that AABB for Create seats partitioned by colour. */
    private static Seats.SeatScan scanSeatsInGlueBox(SubLevel subLevel, BlockPos leverWorldPos,
                                                     BlockPos assemblyOffset, Rotation rotation,
                                                     AirshipKind kind) {
        BlockPos a = leverWorldPos.offset(kind.glueMin().rotate(rotation)).offset(assemblyOffset);
        BlockPos b = leverWorldPos.offset(kind.glueMax().rotate(rotation)).offset(assemblyOffset);
        BlockPos slMin = new BlockPos(
                Math.min(a.getX(), b.getX()),
                Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ()));
        BlockPos slMax = new BlockPos(
                Math.max(a.getX(), b.getX()),
                Math.max(a.getY(), b.getY()),
                Math.max(a.getZ(), b.getZ()));
        return Seats.scan(subLevel, slMin, slMax);
    }
}
