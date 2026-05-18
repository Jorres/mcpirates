package com.mcpirates.pirates;

import com.mcpirates.MCPDataKeys;
import com.mcpirates.MCPirates;
import com.mcpirates.airship.interfaces.AirshipKind;
import com.mcpirates.pirates.roles.CannoneerRole;
import com.mcpirates.pirates.roles.CrossbowmanRole;
import com.mcpirates.pirates.roles.PirateRole;
import com.mcpirates.pirates.roles.PirateRoleCodec;
import net.minecraft.nbt.CompoundTag;
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
 * Seat-driven pirate spawn — one pillager per Create seat in the glue volume:
 * <ul>
 *   <li><b>Black seat</b> → captain ({@link MCPDataKeys#CAPTAIN_TAG}, crossbow,
 *       {@link CrossbowmanRole}). Exactly one required, else no crew spawns at all.</li>
 *   <li><b>Gray/light-gray near cannon</b> (within {@link #CANNONEER_BIND_RANGE_SQ})
 *       → {@link CannoneerRole}, registered in {@code cannoneerByMount}.</li>
 *   <li><b>Gray/light-gray elsewhere</b> → crossbowman, staggered volleys.</li>
 * </ul>
 *
 * <p>Pillagers are {@code NoAi=true}/{@code NoGravity=true} and anchored via Sable's
 * {@code sable$setPlotPosition}; plot-pos isn't persisted, so the brain re-applies it
 * after chunk reload.
 */
public final class CaptainSpawner {

    /** Stable handle for a Sable-anchored pillager. {@code role} may be shared
     *  (stateless) or unique (carries per-pirate state). */
    public record AnchoredEntity(UUID uuid, Vec3 plotPos, PirateRole role) {
        public CompoundTag writeNbt() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("uuid", uuid);
            tag.putDouble("px", plotPos.x);
            tag.putDouble("py", plotPos.y);
            tag.putDouble("pz", plotPos.z);
            tag.put("role", PirateRoleCodec.write(role));
            return tag;
        }
        public static AnchoredEntity readNbt(CompoundTag tag) {
            return new AnchoredEntity(
                    tag.getUUID("uuid"),
                    new Vec3(tag.getDouble("px"), tag.getDouble("py"), tag.getDouble("pz")),
                    PirateRoleCodec.read(tag.getCompound("role")));
        }
    }

    public record CrewSpawnResult(List<AnchoredEntity> anchors,
                                  Map<BlockPos, UUID> cannoneerByMount) {

        /** Used for MOORED registrations that defer deck-crew spawn. */
        public static CrewSpawnResult empty() {
            return new CrewSpawnResult(List.of(), Map.of());
        }
    }

    /** 7 ticks → 4-crew volley spreads ~1s. */
    private static final int CREW_FIRE_STAGGER_TICKS = 7;

    /** 4-block² seat→cannon binding window; un-manned cannons never fire. */
    private static final double CANNONEER_BIND_RANGE_SQ = 16.0;


    private CaptainSpawner() {}

    /** Scan seats, bind cannons, spawn the crew. */
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

        // Cannons claim nearest unclaimed CREW seat; black seat is never a cannoneer.
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
                    CannoneerRole.INSTANCE);
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
                new CrossbowmanRole(now));
        if (captain != null) anchors.add(captain);

        // Crossbowmen on remaining crew seats; stagger reloads.
        int volleyStaggerIndex = 1;
        for (BlockPos seat : crewSeats) {
            if (reserved.contains(seat)) continue;
            PirateRole role = new CrossbowmanRole(now + (long) volleyStaggerIndex * CREW_FIRE_STAGGER_TICKS);
            volleyStaggerIndex++;
            AnchoredEntity ae = spawnAnchoredPillager(
                    inner, subLevel, seatToFeetPlot(seat), leverWorldPos,
                    /*tag=*/ null,
                    Component.literal("Pirate Crewmate"),
                    role);
            if (ae != null) anchors.add(ae);
        }

        MCPirates.LOGGER.info(
                "{} crew: spawned {} (1 captain + {}/{} cannoneers + remainder crossbowmen) from {} seats",
                kind.name(), anchors.size(),
                cannoneerByMount.size(), slCannonMounts.size(), totalSeats);
        return new CrewSpawnResult(anchors, cannoneerByMount);
    }

    /** Feet at (x+0.5, y+1, z+0.5) — same convention vanilla uses for seat-mounted mobs. */
    private static Vec3 seatToFeetPlot(BlockPos seat) {
        return new Vec3(seat.getX() + 0.5, seat.getY() + 1.0, seat.getZ() + 0.5);
    }

    private static AnchoredEntity spawnAnchoredPillager(
            Level inner, SubLevel subLevel,
            Vec3 plotPos,
            BlockPos airshipAnchorWorldPos,
            String tag,
            Component customName,
            PirateRole role) {
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
        // Aggressive→CROSSBOW_HOLD pose with arms raised. Skip for unarmed cannoneers.
        pillager.setAggressive(!mainHand.isEmpty());
        // Anchor stamp is harmless on non-captain crew; keeps promote-to-captain path open
        // and lets ground-combat adopt orphan captains on restart.
        pillager.getPersistentData().putLong(MCPDataKeys.CAPTAIN_ANCHOR_NBT_KEY, airshipAnchorWorldPos.asLong());

        boolean added = inner.addFreshEntity(pillager);
        // Bind AFTER addFreshEntity so Sable's @Unique mixin field is initialised.
        ((EntityStickExtension) pillager).sable$setPlotPosition(plotPos);

        MCPirates.LOGGER.info(
                "{} spawned: subLevel={} plotPos={} initialWorldPos={} added={}",
                role.name(), subLevel.getUniqueId(), plotPos, initialWorldPos, added);

        return added ? new AnchoredEntity(pillager.getUUID(), plotPos, role) : null;
    }

    /** Rotate glue corners into SubLevel space, sort, scan for colour-partitioned seats. */
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
