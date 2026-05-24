package com.mcpirates.dev;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.AirshipBrain;
import com.mcpirates.airship.AirshipLiftoffTrigger;
import com.mcpirates.airship.anchor.MCPShipAnchorBlockEntity;
import com.mcpirates.pirates.roles.CrossbowmanRole;
import com.mcpirates.registry.MCPDataComponents;
import com.mcpirates.registry.MCPItems;
import com.mcpirates.util.FunnyNames;
import com.mcpirates.village.FurledBountyItem;
import com.mcpirates.worldgen.OutpostPermits;
import com.mcpirates.worldgen.processors.VariantSwapProcessor;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.component.MapDecorations;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Optional;
import java.util.Set;

/** Dev/debug chat commands under {@code /mcpirates}. Op level 2 required. */
@EventBusSubscriber(modid = MCPirates.MOD_ID)
public final class MCPCommands {

    private MCPCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("mcpirates")
                .requires(source -> source.hasPermission(2))

                .then(Commands.literal("fire")
                        .then(Commands.literal("on").executes(ctx -> setFire(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> setFire(ctx.getSource(), false))))

                .then(Commands.literal("crew")
                        .then(Commands.literal("hullcheck")
                                .then(Commands.literal("on").executes(ctx -> setHullCheck(ctx.getSource(), true)))
                                .then(Commands.literal("off").executes(ctx -> setHullCheck(ctx.getSource(), false)))))

                .then(Commands.literal("lift")
                        .then(Commands.literal("on").executes(ctx -> setLift(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> setLift(ctx.getSource(), false))))

                .then(Commands.literal("spawn")
                        .then(Commands.literal("airship_small")
                                .executes(ctx -> spawnShipOutpost(ctx, "airship_small")))
                        .then(Commands.literal("crossbow_board")
                                .executes(ctx -> spawnShipOutpost(ctx, "crossbow_board")))
                        .then(Commands.literal("ramship")
                                .executes(ctx -> spawnShipOutpost(ctx, "ramship")))
                        .then(Commands.literal("galleon")
                                .executes(ctx -> spawnShipOutpost(ctx, "galleon"))))

                .then(Commands.literal("sheriff")
                        .then(Commands.literal("spawn").executes(MCPCommands::spawnSheriff)))

                .then(Commands.literal("test")
                        .then(Commands.literal("unfurl")
                                .executes(ctx -> testUnfurl(ctx, false))
                                .then(Commands.literal("galleon")
                                        .executes(ctx -> testUnfurl(ctx, true)))
                                .then(Commands.literal("give")
                                        .executes(ctx -> testUnfurlGive(ctx, false))
                                        .then(Commands.literal("galleon")
                                                .executes(ctx -> testUnfurlGive(ctx, true))))
                                .then(Commands.literal("verify")
                                        .executes(MCPCommands::testUnfurlVerify))
                                .then(Commands.literal("tp")
                                        .executes(MCPCommands::testUnfurlTp))
                                .then(Commands.literal("preview")
                                        .executes(ctx -> testUnfurlPreview(ctx, false))
                                        .then(Commands.literal("galleon")
                                                .executes(ctx -> testUnfurlPreview(ctx, true)))))
                        .then(Commands.literal("scan_candidates")
                                .then(Commands.argument("halfRadius",
                                                com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 10))
                                        .executes(MCPCommands::testPermitsScanCandidates))))

                .then(Commands.literal("place_variant")
                        .then(Commands.argument("kind", StringArgumentType.word())
                                .then(Commands.argument("index", IntegerArgumentType.integer(0))
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(MCPCommands::placeVariant)))))

                .then(Commands.literal("debug")
                        .then(Commands.literal("activate")
                                .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                        .executes(MCPCommands::debugActivate)))
                        .then(Commands.literal("physics")
                                .executes(MCPCommands::debugPhysics))
                        .then(Commands.literal("getblock")
                                .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                        .executes(MCPCommands::debugGetBlock)))
                        .then(Commands.literal("sublevels")
                                .executes(MCPCommands::debugSubLevels)));

        event.getDispatcher().register(root);
    }

    // ────────────────────────────── /mcpirates fire ──────────────────────────────

    private static int setFire(CommandSourceStack source, boolean enabled) {
        AirshipBrain.setFireEnabled(enabled);
        Component msg = Component.literal(
                "mcpirates cannon fire is now " + (enabled ? "ON" : "OFF"));
        source.sendSuccess(() -> msg, /*broadcastToAdmins=*/true);
        return Command.SINGLE_SUCCESS;
    }

    private static int setHullCheck(CommandSourceStack source, boolean enabled) {
        CrossbowmanRole.HULL_CHECK_ENABLED = enabled;
        Component msg = Component.literal(
                "crossbowman own-ship hull check is now " + (enabled ? "ON" : "OFF"));
        source.sendSuccess(() -> msg, /*broadcastToAdmins=*/true);
        return Command.SINGLE_SUCCESS;
    }

    // ────────────────────────────── /mcpirates lift ──────────────────────────────

    private static int setLift(CommandSourceStack source, boolean enabled) {
        AirshipLiftoffTrigger.setAutoLiftoffEnabled(enabled);
        Component msg = Component.literal(
                "mcpirates auto-liftoff is now " + (enabled ? "ON" : "OFF"));
        source.sendSuccess(() -> msg, /*broadcastToAdmins=*/true);
        return Command.SINGLE_SUCCESS;
    }

    // ────────────────────────────── /mcpirates spawn <kind> ──────────────────────────────

    /** Stamps a permit at a chunk east of the executor and runs {@code /place structure
     *  mcpirates:<kind>_outpost} on it — same pipeline as a real bounty scroll unfurl. */
    private static int spawnShipOutpost(CommandContext<CommandSourceStack> ctx, String kind) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        BlockPos origin = BlockPos.containing(src.getPosition());

        // Place 80 blocks east — clears the player's view even for the larger galleon hull.
        int tx = origin.getX() + 80;
        int tz = origin.getZ();
        int ty = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, tx, tz);

        ResourceLocation key = MCPirates.id(kind + "_outpost");
        OutpostPermits.get(level).permit(key, new ChunkPos(new BlockPos(tx, ty, tz)));
        String cmd = String.format("place structure mcpirates:%s_outpost %d %d %d", kind, tx, ty, tz);
        try {
            level.getServer().getCommands().performPrefixedCommand(src.withPermission(4), cmd);
            src.sendSuccess(() -> Component.literal(
                    "Placed " + kind + "_outpost at " + new BlockPos(tx, ty, tz).toShortString()), true);
        } catch (Exception e) {
            MCPirates.LOGGER.error("[spawn {}] failed", kind, e);
            src.sendFailure(Component.literal("Failed to place " + kind + "_outpost: " + e.getMessage()));
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    // ────────────────────────────── /mcpirates place_variant ──────────────────────────────

    /** Place a ship NBT at {@code pos} with one specific palette pick forced for every
     *  family in {@code mcpirates:ship_variants}. {@code index} indexes the FIRST family's
     *  palette list; other families default to palette 0. Used by the place-structures
     *  skill to lay every variant out side-by-side. */
    private static int placeVariant(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        String kind = StringArgumentType.getString(ctx, "kind");
        int idx = IntegerArgumentType.getInteger(ctx, "index");
        BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");

        ResourceLocation templateId = MCPirates.id(kind);
        Optional<StructureTemplate> tplOpt = level.getStructureManager().get(templateId);
        if (tplOpt.isEmpty()) {
            src.sendFailure(Component.literal("Unknown ship NBT: " + templateId));
            return 0;
        }

        // Convention: each ship's variant pool lives at `mcpirates:<kind>_variants`.
        // Mirrors how the ship pools wire `processors: "mcpirates:<kind>_variants"`.
        ResourceLocation listId = MCPirates.id(kind + "_variants");
        Registry<StructureProcessorList> registry = level.registryAccess()
                .registryOrThrow(Registries.PROCESSOR_LIST);
        StructureProcessorList procList = registry.get(listId);
        if (procList == null) {
            src.sendFailure(Component.literal("Processor list not found: " + listId));
            return 0;
        }

        VariantSwapProcessor source = procList.list().stream()
                .filter(VariantSwapProcessor.class::isInstance)
                .map(VariantSwapProcessor.class::cast)
                .findFirst().orElse(null);
        if (source == null) {
            src.sendFailure(Component.literal("No variant_swap processor in " + listId));
            return 0;
        }

        int families = source.familyPools().size();
        int[] picks = new int[families];
        picks[0] = idx;  // first family varies; others stay at 0

        VariantSwapProcessor forced;
        try {
            forced = source.withForcedPicks(picks);
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Bad index: " + e.getMessage()));
            return 0;
        }

        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setIgnoreEntities(false)
                .addProcessor(forced);

        boolean ok = tplOpt.get().placeInWorld(level, pos, pos, settings, level.getRandom(), Block.UPDATE_ALL);
        if (!ok) {
            src.sendFailure(Component.literal("placeInWorld returned false"));
            return 0;
        }

        String label = forced.familyPools().stream()
                .map(fp -> fp.family().key() + "=" + String.join("+", fp.palettes().get(0).variants()))
                .reduce((a, b) -> a + ", " + b).orElse("(no families)");
        src.sendSuccess(() -> Component.literal(String.format(
                "Placed %s [%s] at %s", kind, label, pos.toShortString())), true);
        return Command.SINGLE_SUCCESS;
    }

    // ────────────────────────────── /mcpirates sheriff spawn ──────────────────────────────

    private static int spawnSheriff(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        BlockPos origin = BlockPos.containing(src.getPosition());

        int x = origin.getX() + 2;
        int z = origin.getZ() + 2;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1;
        String sheriffName = FunnyNames.nextSheriffName(level.getRandom());

        try {
            // Funny names are curated literals, no quote injection risk.
            String cmd = "summon minecraft:villager " + x + " " + y + " " + z +
                    " {VillagerData:{profession:\"mcpirates:sheriff\",level:2},PersistenceRequired:1b,CustomName:'\""
                    + sheriffName +
                    "\"',CustomNameVisible:1b}";
            level.getServer().getCommands().performPrefixedCommand(src.withPermission(4), cmd);
            src.sendSuccess(() -> Component.literal(
                    "Spawned sheriff " + sheriffName + " at " + new BlockPos(x, y, z).toShortString()), true);
        } catch (Exception e) {
            MCPirates.LOGGER.error("[sheriff spawn] failed", e);
            src.sendFailure(Component.literal("Failed to spawn sheriff: " + e.getMessage()));
            return 0;
        }
        return 1;
    }

    // ────────────────────────────── /mcpirates debug activate ──────────────────────────────

    /** Drive {@link AirshipLiftoffTrigger#activateAnchor} without needing a SubLevel player. */
    private static int debugActivate(CommandContext<CommandSourceStack> ctx) {
        net.minecraft.core.BlockPos pos =
                net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "pos");
        boolean ok = AirshipLiftoffTrigger.activateAnchor(ctx.getSource().getLevel(), pos);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "activateAnchor at " + pos.toShortString() + " → " + ok), true);
        return ok ? Command.SINGLE_SUCCESS : 0;
    }

    // ────────────────────────────── /mcpirates debug getblock ──────────────────────────────

    /** Single-line BlockState dump (vanilla {@code /data get block} only handles BEs). */
    private static int debugGetBlock(CommandContext<CommandSourceStack> ctx) {
        BlockPos pos =
                net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "pos");
        BlockState state = ctx.getSource().getLevel().getBlockState(pos);
        ctx.getSource().sendSuccess(
                () -> Component.literal(pos.toShortString() + " " + state.toString()),
                false);
        return Command.SINGLE_SUCCESS;
    }

    // ────────────────────────────── /mcpirates debug physics ──────────────────────────────

    /** Per-SubLevel pose + rapier velocity + attached-balloon state. */
    private static int debugPhysics(CommandContext<CommandSourceStack> ctx) {
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container =
                (dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer)
                        dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (container == null) {
            ctx.getSource().sendFailure(Component.literal("No SubLevelContainer in this level"));
            return 0;
        }
        var ps = container.physicsSystem();
        for (var sl : container.getAllSubLevels()) {
            if (sl.isRemoved()) continue;
            var handle = ps.getPhysicsHandle(sl);
            var pos = sl.logicalPose().position();
            var v = handle == null ? null : handle.getLinearVelocity(new org.joml.Vector3d());
            var av = handle == null ? null : handle.getAngularVelocity(new org.joml.Vector3d());
            var mt = sl.getMassTracker();
            // SI units; per-tick figures live in ShipTelemetry.snapshot.
            String msg = String.format(
                    "[debug-physics] subLevel=%s pos=(%.2f,%.2f,%.2f) v_mPerSec=%s av_radPerSec=%s mass=%.2fkg invalidMass=%s",
                    sl.getUniqueId(),
                    pos.x, pos.y, pos.z,
                    v == null ? "null" : String.format("(%.3f,%.3f,%.3f)", v.x, v.y, v.z),
                    av == null ? "null" : String.format("(%.3f,%.3f,%.3f)", av.x, av.y, av.z),
                    mt == null ? Double.NaN : mt.getMass(),
                    mt == null ? "n/a" : String.valueOf(mt.isInvalid()));
            MCPirates.LOGGER.info(msg);
            ctx.getSource().sendSuccess(() -> Component.literal(msg), false);

            // scan for burners in this plot, dump their balloon state
            var pb = sl.getPlot().getBoundingBox();
            for (BlockPos bp : BlockPos.betweenClosed(
                    pb.minX(), pb.minY(), pb.minZ(),
                    pb.maxX(), pb.maxY(), pb.maxZ())) {
                var be = sl.getLevel().getBlockEntity(bp);
                if (be instanceof dev.eriksonn.aeronautics.content.blocks.hot_air.hot_air_burner.HotAirBurnerBlockEntity burner) {
                    String bmsg = "  burner@" + bp.toShortString() + ": "
                            + com.mcpirates.airship.ShipTelemetry.describe(burner);
                    MCPirates.LOGGER.info(bmsg);
                    final String bmsgF = bmsg;
                    ctx.getSource().sendSuccess(() -> Component.literal(bmsgF), false);
                }
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    // ────────────────────────────── /mcpirates debug sublevels ──────────────────────────────

    /**
     * One-line-per-SubLevel census: pose, mass, plot AABB, and a block-type histogram
     * (top by count). Useful for identifying "what is this SubLevel" when a swivel
     * bearing or torsion spring spawns more than expected.
     */
    private static int debugSubLevels(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (container == null) {
            ctx.getSource().sendFailure(Component.literal("No SubLevelContainer in this level"));
            return 0;
        }

        int total = 0;
        for (var sl : container.getAllSubLevels()) {
            if (sl.isRemoved()) continue;
            total++;
            var pos = sl.logicalPose().position();
            var mt = sl.getMassTracker();
            var pb = sl.getPlot().getBoundingBox();

            // Census all non-air blocks. Also collect block-entity ids — these usually
            // identify the SubLevel's purpose better than raw block names (e.g. a
            // SwivelBearingLinkBlock vs a TorsionSpring vs random structural blocks).
            java.util.Map<String, Integer> blockCounts = new java.util.HashMap<>();
            java.util.Map<String, Integer> beCounts = new java.util.HashMap<>();
            int nonAir = 0;
            for (BlockPos bp : BlockPos.betweenClosed(
                    pb.minX(), pb.minY(), pb.minZ(),
                    pb.maxX(), pb.maxY(), pb.maxZ())) {
                BlockState s = sl.getLevel().getBlockState(bp);
                if (s.isAir()) continue;
                nonAir++;
                String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
                blockCounts.merge(id, 1, Integer::sum);
                var be = sl.getLevel().getBlockEntity(bp);
                if (be != null) {
                    String beId = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE
                            .getKey(be.getType()).toString();
                    beCounts.merge(beId, 1, Integer::sum);
                }
            }

            String topBlocks = blockCounts.entrySet().stream()
                    .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(5)
                    .map(e -> e.getKey() + "×" + e.getValue())
                    .reduce((a, b) -> a + ", " + b).orElse("(empty)");
            String beList = beCounts.entrySet().stream()
                    .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                    .map(e -> e.getKey() + "×" + e.getValue())
                    .reduce((a, b) -> a + ", " + b).orElse("(none)");

            final String header = String.format(
                    "[sublevel %s] pos=(%.2f,%.2f,%.2f) mass=%skg plot=[%d..%d, %d..%d, %d..%d] nonAir=%d",
                    sl.getUniqueId().toString().substring(0, 8),
                    pos.x, pos.y, pos.z,
                    mt == null ? "?" : String.format("%.2f", mt.getMass()),
                    pb.minX(), pb.maxX(), pb.minY(), pb.maxY(), pb.minZ(), pb.maxZ(),
                    nonAir);
            final String blockLine = "  blocks: " + topBlocks;
            final String beLine    = "  BEs:    " + beList;
            MCPirates.LOGGER.info(header);
            MCPirates.LOGGER.info(blockLine);
            MCPirates.LOGGER.info(beLine);
            ctx.getSource().sendSuccess(() -> Component.literal(header),    false);
            ctx.getSource().sendSuccess(() -> Component.literal(blockLine), false);
            ctx.getSource().sendSuccess(() -> Component.literal(beLine),    false);
        }

        final int totalF = total;
        ctx.getSource().sendSuccess(
                () -> Component.literal("[sublevels] total=" + totalF), true);
        return Command.SINGLE_SUCCESS;
    }

    // ────────────────────────────── /mcpirates test unfurl ──────────────────────────────

    // Integration-test entrypoint. Deterministic by design: non-galleon always picks
    // NORMAL_BOUNTY_KINDS[0], galleon always picks GALLEON_KIND. Bypasses use()/FakePlayer
    // and goes straight to the permit-and-scan loop — the use() path's random kind roll
    // is non-deterministic across server restarts (world RNG state drifts with background
    // activity) and would make two-phase same-cell assertions impossible. The use() path
    // is exercised by the manual GUI flow (testunfurl give → right-click → verify).
    //
    // Output (RCON-parseable):
    //   "testunfurl OK kind=<rl> cell=<cx>,<cz> ship=<kind> anchor=<x>,<y>,<z>"
    private static int testUnfurl(CommandContext<CommandSourceStack> ctx, boolean galleon) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        ChunkPos origin = new ChunkPos(BlockPos.containing(src.getPosition()));

        ResourceLocation kind = galleon ? FurledBountyItem.GALLEON_KIND
                                        : FurledBountyItem.NORMAL_BOUNTY_KINDS.get(0);
        int searchRadius = galleon ? 400 : 200;

        ChunkPos cell = OutpostPermits.findUnclaimedCandidate(level, kind, origin, searchRadius);
        if (cell == null) {
            src.sendFailure(Component.literal(
                    "testunfurl FAIL: no virgin " + kind + " cell within "
                            + searchRadius + " chunks of " + origin));
            return 0;
        }
        OutpostPermits.get(level).permit(kind, cell);

        // Force-load the permitted chunk's neighbourhood so worldgen runs and the ship
        // anchor BE gets created. Radius 1 is enough — the anchor is always within the
        // pad's own chunk (pad origin at chunk's min-corner, anchor at small offset).
        BlockPos targetCenter = new BlockPos(cell.getMiddleBlockX(),
                level.getMinBuildHeight(), cell.getMiddleBlockZ());
        MCPShipAnchorBlockEntity anchor = findShipAnchorNear(level, targetCenter, /*chunkRadius=*/1);
        if (anchor == null) {
            src.sendFailure(Component.literal(
                    "testunfurl FAIL: permitted " + kind + " at " + cell
                            + " but no ship_anchor placed (worldgen rejected?)"));
            return 0;
        }
        BlockPos aPos = anchor.getBlockPos();
        String shipKind = anchor.getKindName();
        ChunkPos cellCopy = cell;
        src.sendSuccess(() -> Component.literal(
                "testunfurl OK kind=" + kind + " cell=" + cellCopy.x + "," + cellCopy.z
                        + " ship=" + (shipKind.isEmpty() ? "<unknown>" : shipKind)
                        + " anchor=" + aPos.getX() + "," + aPos.getY() + "," + aPos.getZ()), true);
        return Command.SINGLE_SUCCESS;
    }

    // ────────────────────────────── /mcpirates testunfurl give ──────────────────────────────

    // Manual GUI flow, stage 1: drop a furled scroll into the executor's inventory so they
    // can right-click it themselves and observe the real client-side unfurl behaviour.
    // Pairs with `verify` below to catch false negatives where the FakePlayer-based
    // headless test passes but a real client would see a different outcome (e.g. the
    // setItemInHand-clobber bug that motivated this whole test).
    private static int testUnfurlGive(CommandContext<CommandSourceStack> ctx, boolean galleon) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (Exception e) {
            src.sendFailure(Component.literal("testunfurl give FAIL: requires a player executor"));
            return 0;
        }
        ItemStack scroll = new ItemStack(MCPItems.FURLED_BOUNTY.get(), 1);
        if (galleon) {
            scroll.set(MCPDataComponents.IS_GALLEON_BOUNTY.get(), Unit.INSTANCE);
        }
        if (!player.getInventory().add(scroll)) {
            player.drop(scroll, false);
        }
        src.sendSuccess(() -> Component.literal(
                "gave " + (galleon ? "galleon " : "") + "furled bounty to "
                        + player.getName().getString()), true);
        return Command.SINGLE_SUCCESS;
    }

    // ────────────────────────────── /mcpirates testunfurl verify ──────────────────────────────

    // Manual GUI flow, stage 2: confirm the scroll's map points at a real generated
    // structure. Reads the map decoration, scans the chunks around the target for a
    // ship_anchor block entity, reports the anchor's kind. End-to-end check — kind comes
    // from the anchor itself, no tag lookup needed.
    private static int testUnfurlVerify(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (Exception e) {
            src.sendFailure(Component.literal("testunfurl verify FAIL: requires a player executor"));
            return 0;
        }
        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!(held.getItem() instanceof MapItem)) {
            src.sendFailure(Component.literal(
                    "testunfurl verify FAIL: main hand has "
                            + BuiltInRegistries.ITEM.getKey(held.getItem())
                            + " (expected a filled map — right-click your scroll first)"));
            return 0;
        }
        MapDecorations decos = held.getOrDefault(DataComponents.MAP_DECORATIONS, MapDecorations.EMPTY);
        if (decos.decorations().isEmpty()) {
            src.sendFailure(Component.literal(
                    "testunfurl verify FAIL: map has no target decoration"));
            return 0;
        }
        MapDecorations.Entry entry = decos.decorations().values().iterator().next();
        int tx = (int) entry.x();
        int tz = (int) entry.z();

        ServerLevel level = src.getLevel();
        BlockPos targetCenter = new BlockPos(tx, level.getMinBuildHeight(), tz);
        MCPShipAnchorBlockEntity anchor = findShipAnchorNear(level, targetCenter, /*chunkRadius=*/1);
        if (anchor == null) {
            src.sendFailure(Component.literal(String.format(
                    "testunfurl verify FAIL: no ship_anchor within 1 chunk of map target (%d,%d) "
                            + "— structure not yet placed? try walking closer to load chunks",
                    tx, tz)));
            return 0;
        }
        BlockPos aPos = anchor.getBlockPos();
        String kind = anchor.getKindName().isEmpty() ? "<unknown>" : anchor.getKindName();
        src.sendSuccess(() -> Component.literal(String.format(
                "testunfurl verify OK: map (%d,%d); ship=%s anchor=(%d,%d,%d)",
                tx, tz, kind, aPos.getX(), aPos.getY(), aPos.getZ())), true);
        return Command.SINGLE_SUCCESS;
    }

    // ────────────────────────────── /mcpirates testunfurl tp ──────────────────────────────

    // Reads the executor's main-hand map target decoration and teleports them to a safe
    // surface position there. Reuses findSafeTeleportPos so we land on solid ground with
    // sky access, not inside a tree or buried in a hill.
    private static int testUnfurlTp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (Exception e) {
            src.sendFailure(Component.literal("testunfurl tp FAIL: requires a player executor"));
            return 0;
        }
        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!(held.getItem() instanceof MapItem)) {
            src.sendFailure(Component.literal(
                    "testunfurl tp FAIL: main hand has "
                            + BuiltInRegistries.ITEM.getKey(held.getItem())
                            + " (expected a filled map)"));
            return 0;
        }
        MapDecorations decos = held.getOrDefault(DataComponents.MAP_DECORATIONS, MapDecorations.EMPTY);
        if (decos.decorations().isEmpty()) {
            src.sendFailure(Component.literal("testunfurl tp FAIL: map has no target decoration"));
            return 0;
        }
        MapDecorations.Entry entry = decos.decorations().values().iterator().next();
        BlockPos target = new BlockPos((int) entry.x(), 0, (int) entry.z());

        ServerLevel level = src.getLevel();
        Vec3 safe = findSafeTeleportPos(level, target);
        player.teleportTo(level, safe.x, safe.y, safe.z, Set.of(),
                player.getYRot(), player.getXRot());
        src.sendSuccess(() -> Component.literal(
                "teleported to map target " + BlockPos.containing(safe).toShortString()), true);
        return Command.SINGLE_SUCCESS;
    }

    // ────────────────────────────── /mcpirates testunfurl preview ──────────────────────────────

    // Dry-run: calls OutpostPermits.findUnclaimedCandidate without stamping a permit, so
    // the integration test can learn the chunk a real unfurl would target and assert it's
    // empty before then re-running with an actual unfurl. Galleon flag picks GALLEON_KIND;
    // non-galleon uses NORMAL_BOUNTY_KINDS[0] (deterministic so tests don't depend on the
    // random roll). Output (RCON-parseable):
    //   "preview OK kind=<rl> cell=<cx>,<cz>"
    private static int testUnfurlPreview(CommandContext<CommandSourceStack> ctx, boolean galleon) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Vec3 pos = src.getPosition();
        ChunkPos origin = new ChunkPos(BlockPos.containing(pos));

        ResourceLocation kind;
        int searchRadius;
        if (galleon) {
            kind = FurledBountyItem.GALLEON_KIND;
            searchRadius = 400;
        } else {
            kind = FurledBountyItem.NORMAL_BOUNTY_KINDS.get(0);
            searchRadius = 200;
        }
        ChunkPos cell = OutpostPermits.findUnclaimedCandidate(level, kind, origin, searchRadius);
        if (cell == null) {
            src.sendFailure(Component.literal(
                    "preview FAIL: no virgin " + kind + " cell within " + searchRadius + " chunks"));
            return 0;
        }
        ChunkPos c = cell;
        src.sendSuccess(() -> Component.literal(
                "preview OK kind=" + kind + " cell=" + c.x + "," + c.z), true);
        return Command.SINGLE_SUCCESS;
    }

    // ────────────────────────────── /mcpirates testpermits scan_candidates ──────────────────────────────

    // Invariant probe: for every registered PermittedShipOutpostStructure, force-load every
    // placement-formula candidate in (-halfRadius..halfRadius)² regions around the source
    // position, and count ship_anchor BlockEntities. Used by the orchestrator's
    // no_natural_spawn scenario to prove no ships spawn on a fresh world without a scroll
    // unfurl. Scope: the source's dimension only — nether/end coverage was a one-time
    // empirical check, now folded into the biome-whitelist design (overworld-only biomes,
    // see decisions.md and the airship_small_outpost.json biome tag). Output:
    //   "scan_candidates OK kinds=<n> regions_per_kind=<n> chunks=<n> anchors=0"
    // On any anchor hit: FAIL with sample positions.
    private static int testPermitsScanCandidates(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        int halfRadius = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "halfRadius");
        ChunkPos origin = new ChunkPos(BlockPos.containing(src.getPosition()));

        var structureRegistry = level.registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
        var setRegistry = level.registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE_SET);

        int kinds = 0;
        int chunksScanned = 0;
        int regionsPerKind = (2 * halfRadius + 1) * (2 * halfRadius + 1);
        java.util.List<String> hits = new java.util.ArrayList<>();
        long seed = level.getSeed();

        for (var entry : structureRegistry.entrySet()) {
            if (!(entry.getValue() instanceof com.mcpirates.worldgen.PermittedShipOutpostStructure)) {
                continue;
            }
            ResourceLocation key = entry.getKey().location();
            var setOpt = setRegistry.getOptional(
                    net.minecraft.resources.ResourceKey.create(
                            net.minecraft.core.registries.Registries.STRUCTURE_SET, key));
            if (setOpt.isEmpty()) continue;
            var placement = setOpt.get().placement();
            if (!(placement instanceof net.minecraft.world.level.levelgen.structure.placement
                    .RandomSpreadStructurePlacement spread)) {
                continue;
            }
            kinds++;
            int spacing = spread.spacing();
            int originRegionX = Math.floorDiv(origin.x, spacing);
            int originRegionZ = Math.floorDiv(origin.z, spacing);

            for (int dx = -halfRadius; dx <= halfRadius; dx++) {
                for (int dz = -halfRadius; dz <= halfRadius; dz++) {
                    ChunkPos candidate = spread.getPotentialStructureChunk(
                            seed, originRegionX + dx, originRegionZ + dz);
                    LevelChunk chunk = level.getChunk(candidate.x, candidate.z);
                    chunksScanned++;
                    for (BlockEntity be : chunk.getBlockEntities().values()) {
                        if (be instanceof MCPShipAnchorBlockEntity anchor) {
                            BlockPos pos = anchor.getBlockPos();
                            if (hits.size() < 5) {
                                hits.add(key + "@" + pos.toShortString());
                            }
                        }
                    }
                }
            }
        }

        if (!hits.isEmpty()) {
            src.sendFailure(Component.literal(String.format(
                    "scan_candidates FAIL anchors>=%d (first %d): %s",
                    hits.size(), hits.size(), String.join(", ", hits))));
            return 0;
        }
        final int kindsF = kinds;
        final int chunksF = chunksScanned;
        src.sendSuccess(() -> Component.literal(String.format(
                "scan_candidates OK kinds=%d regions_per_kind=%d chunks=%d anchors=0",
                kindsF, regionsPerKind, chunksF)), true);
        return Command.SINGLE_SUCCESS;
    }

    // ────────────────────────────── helpers ──────────────────────────────

    /** Force-load chunks in a (2r+1)² square around {@code center} and return the first
     *  ship anchor BE found. Forces the load because outpost chunks generated by
     *  PermittedShipOutpostStructure-generated chunks may have been unloaded again by
     *  the time we check; force-load so chunk.getBlockEntities() includes the anchor BE. */
    private static MCPShipAnchorBlockEntity findShipAnchorNear(
            ServerLevel level, BlockPos center, int chunkRadius) {
        int cx = center.getX() >> 4;
        int cz = center.getZ() >> 4;
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                LevelChunk chunk = level.getChunk(cx + dx, cz + dz);
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof MCPShipAnchorBlockEntity anchor) {
                        return anchor;
                    }
                }
            }
        }
        return null;
    }

    /** Spiral outward (≤48) for ground + 2-tall clearance + open sky. */
    private static Vec3 findSafeTeleportPos(ServerLevel level, BlockPos origin) {
        for (int r = 0; r <= 48; r++) {
            for (int dx = -r; dx <= r; dx++) {
                int x1 = origin.getX() + dx;
                int z1 = origin.getZ() - r;
                Vec3 p1 = trySurfacePos(level, x1, z1, origin.getY());
                if (p1 != null) return p1;

                int z2 = origin.getZ() + r;
                Vec3 p2 = trySurfacePos(level, x1, z2, origin.getY());
                if (p2 != null) return p2;
            }
            for (int dz = -r + 1; dz <= r - 1; dz++) {
                int z1 = origin.getZ() + dz;
                int x1 = origin.getX() - r;
                Vec3 p1 = trySurfacePos(level, x1, z1, origin.getY());
                if (p1 != null) return p1;

                int x2 = origin.getX() + r;
                Vec3 p2 = trySurfacePos(level, x2, z1, origin.getY());
                if (p2 != null) return p2;
            }
        }

        int fallbackY = findSafeHeight(level, origin.getX(), origin.getZ(), origin.getY());
        return new Vec3(origin.getX() + 0.5, fallbackY + 1.0, origin.getZ() + 0.5);
    }

    private static Vec3 trySurfacePos(ServerLevel level, int x, int z, int anchorY) {
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        int startY = Math.max(surfaceY, anchorY + 1);

        for (int y = startY; y >= Math.max(level.getMinBuildHeight() + 1, startY - 24); y--) {
            BlockPos belowPos = new BlockPos(x, y - 1, z);
            BlockPos feetPos = new BlockPos(x, y, z);
            BlockPos headPos = new BlockPos(x, y + 1, z);

            BlockState below = level.getBlockState(belowPos);
            BlockState feet = level.getBlockState(feetPos);
            BlockState head = level.getBlockState(headPos);

            boolean hasGround = !below.isAir() && below.getFluidState().isEmpty();
            boolean feetFree = feet.isAir() || feet.getCollisionShape(level, feetPos).isEmpty();
            boolean headFree = head.isAir() || head.getCollisionShape(level, headPos).isEmpty();
            boolean hasSky = level.canSeeSky(feetPos);

            if (hasGround && feetFree && headFree && hasSky)
                return new Vec3(x + 0.5, y, z + 0.5);
        }
        return null;
    }

    private static int findSafeHeight(ServerLevel level, int x, int z, int startY) {
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        // Mesa-pillar / cave fallback: start 40 above anchor when heightmap is lower.
        int searchY = Math.max(startY + 40, surfaceY);

        for (int y = searchY; y >= level.getMinBuildHeight(); y--) {
            if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                return y;
            }
        }
        return Math.max(surfaceY, level.getSeaLevel());
    }
}
