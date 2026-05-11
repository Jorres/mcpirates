package com.mcpirates.pirates;

import com.mcpirates.MCPirates;
import dev.ryanhcode.sable.mixinterface.entity.entities_stick_sublevels.EntityStickExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;

/**
 * Spawn a pirate captain into the airship's SubLevel right after Aeronautics assembly
 * finishes. The captain rides the ship indefinitely as a stationary turret-style target;
 * killing it drops a {@code captain_seal} (see {@link CaptainDeath}).
 *
 * <h2>Anchoring to the moving SubLevel</h2>
 *
 * <p>An earlier attempt set NoAI/NoGravity and tagged {@code minecraft:pillager} as
 * {@code sable:retain_in_sub_level}, hoping the captain would follow the ship by sheer
 * virtue of being "inside" the SubLevel plot region. That tag only prevents Sable's
 * {@code kickEntity} call (which moves wandering entities out of plot space) — it does
 * NOT anchor the entity to the moving pose. Without anchoring, the ship's pose updates
 * each tick but the captain's stored world position stays fixed, so the ship flies past
 * and leaves the captain hovering in mid-air at the original spawn coords.
 *
 * <p>The correct mechanism is Sable's
 * {@code EntityStickExtension#sable$setPlotPosition(Vec3)}: every entity has a hidden
 * "plot position" field; if set, an {@code Entity.tick} mixin re-derives the entity's
 * world position each tick as {@code subLevel.logicalPose().transformPosition(plotPos)}.
 * Per-tick re-bind, not a one-shot transform — the entity smoothly follows ship motion.
 *
 * <p>NoAI + NoGravity stay in place: NoAI so the captain doesn't try to walk off-deck
 * (which would fight the per-tick rebind anyway), NoGravity so we don't waste cycles on
 * fall math that gets overwritten each tick.
 *
 * <h2>NBT-space layout</h2>
 *
 * <p>Deck floor on this airship is NBT y=3 (cobblestone at the captain's column).
 * Pillagers position via feet-Y, so the captain stands at NBT y=4 (feet on the deck,
 * head at ~y=5.95). Captain placed at NBT {@code (7, 4, 3)} — centred between the helm
 * levers {@code (5, 5, 2)} / {@code (9, 5, 2)}, two blocks aft of the cannon mount at
 * {@code (7, 3, 1)}. Delta from lever {@code (7, 6, 5)} is {@code (0, -2, -2)}.
 *
 * <p>A crewmate (regular pillager, no captain tag) spawns at NBT {@code (6, 4, 3)} — one
 * block west of the captain on the same deck row. Both rotate with the structure via
 * {@link BlockPos#rotate(Rotation)} on the delta, then we add the assembly offset.
 *
 * <p>An earlier draft put the captain at NBT y=6, floating two blocks above the deck;
 * visually the captain hovered like a ghost rather than standing at the helm. Lowered
 * to deck level after a screenshot review.
 *
 * <h2>Tag, not custom entity type</h2>
 *
 * <p>The captain is a vanilla {@link Pillager} with a single string tag,
 * {@link #CAPTAIN_TAG}. {@link CaptainDeath} listens on {@code LivingDeathEvent} and
 * checks for the tag to decide whether to drop a seal. No new entity type is registered —
 * the alternative would have been a full {@code EntityType} + model + texture pipeline,
 * a significant chunk of v0.2+ work.
 */
public final class CaptainSpawner {

    /** Marker tag on the pillager entity. {@link CaptainDeath} checks this to identify
     *  bounty-eligible kills. Format mirrors vanilla scoreboard-tag conventions. */
    public static final String CAPTAIN_TAG = "mcpirates.pirate_captain";

    /** Feet-Y delta from the lever's NBT pos (7, 6, 5). Captain at NBT (7, 4, 3) puts
     *  the pillager standing on the cobblestone deck (NBT y=3) between the helm levers. */
    private static final BlockPos CAPTAIN_DELTA = new BlockPos(0, -2, -2);

    /** Crewmate (a regular pillager without the captain tag, won't drop a seal). Spawns
     *  beside the captain at NBT (6, 4, 3) — same deck row, one block west of the
     *  captain. Anchored by the same {@code sable$setPlotPosition} mechanism, which is
     *  the gameplay-verification side benefit: any entity type can ride if bound. */
    private static final BlockPos CREW_DELTA = new BlockPos(-1, -2, -2);

    private CaptainSpawner() {}

    /**
     * Spawn a captain into the freshly-assembled SubLevel.
     *
     * @param subLevel  the SubLevel returned by {@code AirshipAssembler.assemble()}
     * @param leverWorldPos  the world position of the airship's analog lever BEFORE
     *                       assembly (i.e., where the lever was rooted in the parent
     *                       world). Used as the "logical origin" of the airship.
     * @param assemblyOffset  the offset returned by the assembler — adding it to a
     *                        world pos gives the SubLevel-local pos of the same block.
     * @param rotation        the jigsaw-applied rotation of this airship piece, used to
     *                        rotate the NBT-local captain delta.
     */
    public static void spawn(SubLevel subLevel, BlockPos leverWorldPos,
                             BlockPos assemblyOffset, Rotation rotation) {
        Level inner = subLevel.getLevel();
        if (inner == null) {
            MCPirates.LOGGER.warn("CaptainSpawner: SubLevel.getLevel() returned null — captain not spawned");
            return;
        }

        spawnAnchoredPillager(
                inner, subLevel, leverWorldPos, assemblyOffset, rotation,
                CAPTAIN_DELTA,
                /*tag=*/CAPTAIN_TAG,
                /*customName=*/Component.translatable("mcpirates.entity.pirate_captain"),
                /*role=*/"captain");

        spawnAnchoredPillager(
                inner, subLevel, leverWorldPos, assemblyOffset, rotation,
                CREW_DELTA,
                /*tag=*/null,
                /*customName=*/Component.literal("Pirate Crewmate"),
                /*role=*/"crewmate");
    }

    /**
     * Spawn a single anchored pillager onto the airship's deck.
     *
     * @param tag        scoreboard-style tag to add (or null for "no marker"). Captain
     *                   gets {@link #CAPTAIN_TAG} for {@link CaptainDeath} to find on
     *                   death; crewmate gets no tag so it drops nothing special.
     * @param customName name shown above the pillager.
     * @param role       short string used in the spawn log to tell captain/crew apart.
     */
    private static void spawnAnchoredPillager(
            Level inner, SubLevel subLevel,
            BlockPos leverWorldPos, BlockPos assemblyOffset, Rotation rotation,
            BlockPos delta,
            String tag,
            Component customName,
            String role) {
        BlockPos worldPos = leverWorldPos.offset(delta.rotate(rotation));
        BlockPos plotBlockPos = worldPos.offset(assemblyOffset);
        // Plot-local centre of the pillager's block (XZ centred, feet at integer Y) —
        // fed to Sable so each tick rebinds the entity's world position to
        // subLevel.logicalPose().transformPosition(plotPos).
        Vec3 plotPos = new Vec3(
                plotBlockPos.getX() + 0.5,
                plotBlockPos.getY() + 0.0,
                plotBlockPos.getZ() + 0.5);
        // Initial world position derived from the current pose. Without this the entity
        // would briefly appear at the raw plot coords (~20M block coordinate) for one
        // tick before Sable's mixin moved it; visible to clients in extreme cases.
        Vec3 initialWorldPos = subLevel.logicalPose().transformPosition(plotPos);

        Pillager pillager = EntityType.PILLAGER.create(inner);
        if (pillager == null) {
            MCPirates.LOGGER.warn("CaptainSpawner: EntityType.PILLAGER.create returned null for role={}", role);
            return;
        }
        pillager.moveTo(initialWorldPos.x, initialWorldPos.y, initialWorldPos.z, /*yaw=*/0.0f, /*pitch=*/0.0f);
        pillager.setNoAi(true);
        pillager.setNoGravity(true);
        pillager.setPersistenceRequired();
        if (tag != null) {
            pillager.addTag(tag);
        }
        pillager.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
        pillager.setCustomName(customName);
        pillager.setCustomNameVisible(true);

        boolean added = inner.addFreshEntity(pillager);
        // Bind to the SubLevel via the EntityStickExtension API. Must happen AFTER
        // addFreshEntity so the entity has its full state (and the mixin's $plotPosition
        // unique field has been initialised).
        ((EntityStickExtension) pillager).sable$setPlotPosition(plotPos);

        MCPirates.LOGGER.info(
                "{} spawned: subLevel={} plotPos={} initialWorldPos={} rotation={} added={}",
                role, subLevel.getUniqueId(), plotPos, initialWorldPos, rotation, added);
    }
}
