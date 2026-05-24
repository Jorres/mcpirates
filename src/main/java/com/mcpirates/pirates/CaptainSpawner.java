package com.mcpirates.pirates;

import com.mcpirates.MCPDataKeys;
import com.mcpirates.MCPirates;
import com.mcpirates.airship.interfaces.AirshipKind;
import com.mcpirates.airship.interfaces.Layout;
import com.mcpirates.pirates.roles.CannoneerRole;
import com.mcpirates.pirates.roles.CrossbowmanRole;
import com.mcpirates.pirates.roles.PirateRole;
import com.mcpirates.pirates.roles.PirateRoleCodec;
import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import net.minecraft.nbt.CompoundTag;
import com.mcpirates.util.FunnyNames;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;

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
 * <p>Each pillager is mounted onto its Create seat via {@link SeatBlock#sitDown}; the
 * resulting passenger-of-SeatEntity binding makes them ride the SubLevel naturally with
 * no need for plot-pinning, NoAi, or NoGravity. Vanilla AI runs unchanged — the seat is
 * what holds them in place.
 */
public final class CaptainSpawner {

    /** Stable handle for a seated pillager. {@code role} may be shared (stateless) or
     *  unique (carries per-pirate state). */
    public record AnchoredEntity(UUID uuid, PirateRole role) {
        public CompoundTag writeNbt() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("uuid", uuid);
            tag.put("role", PirateRoleCodec.write(role));
            return tag;
        }
        public static AnchoredEntity readNbt(CompoundTag tag) {
            return new AnchoredEntity(
                    tag.getUUID("uuid"),
                    PirateRoleCodec.read(tag.getCompound("role")));
        }
    }

    public record CrewSpawnResult(List<AnchoredEntity> anchors,
                                  Map<BlockPos, UUID> cannoneerByMount) {}

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
            AnchoredEntity ae = spawnSeatedPillager(
                    inner, subLevel, seat, leverWorldPos,
                    /*tag=*/ null,
                    /*name=*/ Component.literal("Pirate Gunner"),
                    CannoneerRole.INSTANCE);
            if (ae != null) {
                anchors.add(ae);
                cannoneerByMount.put(mount, ae.uuid());
            }
        }

        // Captain on the (single) black seat.
        AnchoredEntity captain = spawnSeatedPillager(
                inner, subLevel, captainSeat, leverWorldPos,
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
            AnchoredEntity ae = spawnSeatedPillager(
                    inner, subLevel, seat, leverWorldPos,
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

    private static AnchoredEntity spawnSeatedPillager(
            Level inner, SubLevel subLevel,
            BlockPos seatPlotPos,
            BlockPos airshipAnchorWorldPos,
            String tag,
            Component customName,
            PirateRole role) {
        Pillager pillager = EntityType.PILLAGER.create(inner);
        if (pillager == null) {
            MCPirates.LOGGER.warn("CaptainSpawner: EntityType.PILLAGER.create returned null for role={}",
                    role.name());
            return null;
        }
        // Spawn at seat block top; SeatBlock.sitDown immediately overrides position via
        // startRiding. Vanilla AI runs unchanged — the seat is what holds the pillager.
        pillager.moveTo(seatPlotPos.getX() + 0.5, seatPlotPos.getY() + 1.0, seatPlotPos.getZ() + 0.5,
                /*yaw=*/0.0f, /*pitch=*/0.0f);
        pillager.setPersistenceRequired();
        if (tag != null) pillager.addTag(tag);
        ItemStack mainHand = role.mainHandItem();
        // Always write the slot, including EMPTY. Some spawn paths (Mob ctor / equipment
        // init triggered by addFreshEntity) can leave a default crossbow on Pillagers;
        // an explicit clear is the only reliable way to keep cannoneers unarmed.
        pillager.setItemSlot(EquipmentSlot.MAINHAND, mainHand);
        pillager.setCustomName(customName);
        pillager.setCustomNameVisible(true);
        // Aggressive→CROSSBOW_HOLD pose with arms raised. Skip for unarmed cannoneers.
        pillager.setAggressive(!mainHand.isEmpty());
        // Anchor stamp powers the bounty path on captain death.
        pillager.getPersistentData().putLong(MCPDataKeys.CAPTAIN_ANCHOR_NBT_KEY, airshipAnchorWorldPos.asLong());

        boolean added = inner.addFreshEntity(pillager);
        if (!added) {
            MCPirates.LOGGER.warn("{} spawn rejected by level (subLevel={})",
                    role.name(), subLevel.getUniqueId());
            return null;
        }

        // Suppress vanilla AI now that addFreshEntity has run registerGoals(). We keep
        // NoAi=false so aiStep continues to fire (Sable's rideTick kick depends on it),
        // but with both selectors emptied no goals run — vanilla won't pick its own
        // target, drive LookControl, or fire crossbow on its schedule. Our PirateBrain →
        // CrossbowmanRole.tick is the sole combat driver.
        pillager.goalSelector.removeAllGoals(g -> true);
        pillager.targetSelector.removeAllGoals(g -> true);

        SeatBlock.sitDown(inner, seatPlotPos, pillager);
        if (pillager.getVehicle() == null) {
            MCPirates.LOGGER.warn(
                    "{} failed to mount seat at {} (subLevel={}) — entity not riding after sitDown",
                    role.name(), seatPlotPos, subLevel.getUniqueId());
        }

        MCPirates.LOGGER.info(
                "{} spawned + seated at plot {} (subLevel={}, riding={})",
                role.name(), seatPlotPos, subLevel.getUniqueId(),
                pillager.getVehicle() != null);

        return new AnchoredEntity(pillager.getUUID(), role);
    }

    /** Resolve glue corners in SubLevel space, sort, scan for colour-partitioned seats. */
    private static Seats.SeatScan scanSeatsInGlueBox(SubLevel subLevel, BlockPos leverWorldPos,
                                                     BlockPos assemblyOffset, Rotation rotation,
                                                     AirshipKind kind) {
        Layout sl = kind.layoutAt(rotation, leverWorldPos.offset(assemblyOffset));
        BlockPos a = sl.glueMin();
        BlockPos b = sl.glueMax();
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
