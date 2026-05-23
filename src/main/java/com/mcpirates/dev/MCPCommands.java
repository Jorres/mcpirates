package com.mcpirates.dev;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.AirshipBrain;
import com.mcpirates.airship.AirshipLiftoffTrigger;
import com.mcpirates.airship.anchor.MCPShipAnchorBlockEntity;
import com.mcpirates.airship.ships.galleon.GalleonSpawner;
import com.mcpirates.registry.MCPDataComponents;
import com.mcpirates.registry.MCPItems;
import com.mcpirates.registry.MCPStructureTags;
import com.mcpirates.util.FunnyNames;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.component.MapDecorations;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Dev/debug chat commands under {@code /mcpirates}. Op level 2 required. */
@EventBusSubscriber(modid = MCPirates.MOD_ID)
public final class MCPCommands {

    private static final ResourceKey<Structure> PILLAGER_OUTPOST_KEY =
            ResourceKey.create(Registries.STRUCTURE,
                    ResourceLocation.withDefaultNamespace("pillager_outpost"));

    private MCPCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("mcpirates")
                .requires(source -> source.hasPermission(2))

                .then(Commands.literal("fire")
                        .then(Commands.literal("on").executes(ctx -> setFire(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> setFire(ctx.getSource(), false))))

                .then(Commands.literal("lift")
                        .then(Commands.literal("on").executes(ctx -> setLift(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> setLift(ctx.getSource(), false))))

                .then(Commands.literal("outpost")
                        .then(Commands.literal("tp").executes(MCPCommands::tpNearestOutpost))
                        .then(Commands.literal("spawn")
                                .executes(ctx -> spawnOutpost(ctx, null))
                                .then(Commands.literal("airship_small")
                                        .executes(ctx -> spawnOutpost(ctx, "airship_small")))
                                .then(Commands.literal("crossbow_board")
                                        .executes(ctx -> spawnOutpost(ctx, "crossbow_board")))
                                .then(Commands.literal("ramship")
                                        .executes(ctx -> spawnOutpost(ctx, "ramship")))))

                .then(Commands.literal("sheriff")
                        .then(Commands.literal("spawn").executes(MCPCommands::spawnSheriff)))

                .then(Commands.literal("galleon")
                        .then(Commands.literal("spawn").executes(MCPCommands::spawnGalleon)))

                .then(Commands.literal("testunfurl")
                        .executes(ctx -> testUnfurl(ctx, false))
                        .then(Commands.literal("galleon")
                                .executes(ctx -> testUnfurl(ctx, true)))
                        .then(Commands.literal("give")
                                .executes(ctx -> testUnfurlGive(ctx, false))
                                .then(Commands.literal("galleon")
                                        .executes(ctx -> testUnfurlGive(ctx, true))))
                        .then(Commands.literal("verify")
                                .executes(ctx -> testUnfurlVerify(ctx, false))
                                .then(Commands.literal("galleon")
                                        .executes(ctx -> testUnfurlVerify(ctx, true))))
                        .then(Commands.literal("tp")
                                .executes(MCPCommands::testUnfurlTp)))

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

    // ────────────────────────────── /mcpirates lift ──────────────────────────────

    private static int setLift(CommandSourceStack source, boolean enabled) {
        AirshipLiftoffTrigger.setAutoLiftoffEnabled(enabled);
        Component msg = Component.literal(
                "mcpirates auto-liftoff is now " + (enabled ? "ON" : "OFF"));
        source.sendSuccess(() -> msg, /*broadcastToAdmins=*/true);
        return Command.SINGLE_SUCCESS;
    }

    // ────────────────────────────── /mcpirates outpost tp ──────────────────────────────

    private static int tpNearestOutpost(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        BlockPos origin = BlockPos.containing(src.getPosition());

        src.sendSystemMessage(Component.literal("Locating nearest pillager outpost..."));

        BlockPos found = findNearestOutpost(level, origin);
        if (found == null) {
            src.sendFailure(Component.literal("No pillager outpost found within search radius."));
            return 0;
        }

        try {
            ServerPlayer player = src.getPlayerOrException();
            Vec3 safePos = findSafeTeleportPos(level, found);
            player.teleportTo(level,
                    safePos.x, safePos.y, safePos.z,
                    Set.of(),
                    player.getYRot(), player.getXRot());
            BlockPos finalFound = found;
            src.sendSuccess(() -> Component.literal(
                    "Teleported to pillager outpost at " + finalFound.toShortString()), true);
        } catch (Exception e) {
            src.sendFailure(Component.literal("Command requires a player executor."));
            return 0;
        }
        return 1;
    }

    // ────────────────────────────── /mcpirates outpost spawn ──────────────────────────────

    /** {@code ship == null} → vanilla outpost with random ship; otherwise our
     *  {@code outpost_with_<ship>} variant pinning the ship choice. */
    private static int spawnOutpost(CommandContext<CommandSourceStack> ctx, String ship) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        BlockPos origin = BlockPos.containing(src.getPosition());

        // Place 48 blocks east of the player.
        int tx = origin.getX() + 48;
        int tz = origin.getZ();
        int ty = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, tx, tz);

        String structureId = (ship == null)
                ? "minecraft:pillager_outpost"
                : "mcpirates:outpost_with_" + ship;
        String cmd = String.format("place structure %s %d %d %d", structureId, tx, ty, tz);
        try {
            level.getServer().getCommands().performPrefixedCommand(src.withPermission(4), cmd);
            String label = (ship == null) ? "pillager outpost (random ship)"
                                          : "pillager outpost with " + ship;
            src.sendSuccess(() -> Component.literal(
                    "Placed " + label + " at " + new BlockPos(tx, ty, tz).toShortString()), true);
        } catch (Exception e) {
            MCPirates.LOGGER.error("[outpost spawn] failed", e);
            src.sendFailure(Component.literal("Failed to place outpost: " + e.getMessage()));
            return 0;
        }
        return 1;
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

    // ────────────────────────────── /mcpirates galleon spawn ──────────────────────────────

    private static int spawnGalleon(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        BlockPos origin = BlockPos.containing(src.getPosition());

        // 80 east — clears the player's chunk but stays a short flight away.
        int centerX = origin.getX() + 80;
        int centerZ = origin.getZ();

        BlockPos anchor = GalleonSpawner.spawnGalleonAt(level, centerX, centerZ);
        if (anchor == null) {
            src.sendFailure(Component.literal(
                    "galleon placement failed (check log) — NBT missing or template error"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(
                "Placed galleon at " + anchor.toShortString()
                        + " (fly close to trigger liftoff)"), true);
        return Command.SINGLE_SUCCESS;
    }

    // ────────────────────────────── /mcpirates testunfurl ──────────────────────────────

    // Stable identity for the integration-test FakePlayer; FakePlayerFactory caches by
    // (level, profile) so repeated calls reuse one instance — we clear its inventory on
    // each invocation instead.
    private static final GameProfile TEST_UNFURL_PROFILE = new GameProfile(
            UUID.fromString("00000000-0000-0000-c1a5-000000000001"),
            "TestUnfurlBot");

    // Integration-test entrypoint. Spawns/reuses a FakePlayer at the source position,
    // mints a furled scroll into its main hand, routes through ServerPlayerGameMode.useItem
    // (same path as a real right-click), then echoes the resulting map's id + target world
    // coords. No connected client required — RCON-callable directly.
    //
    // Output line (RCON-parseable):
    //   "testunfurl OK map_id=<int> target=<x>,<z>"
    // Failure modes echoed verbatim so the test harness can grep them.
    private static int testUnfurl(CommandContext<CommandSourceStack> ctx, boolean galleon) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Vec3 pos = src.getPosition();

        FakePlayer player = FakePlayerFactory.get(level, TEST_UNFURL_PROFILE);
        player.getInventory().clearContent();
        player.setPos(pos.x, pos.y, pos.z);

        ItemStack scroll = new ItemStack(MCPItems.FURLED_BOUNTY.get(), 1);
        if (galleon) {
            scroll.set(MCPDataComponents.IS_GALLEON_BOUNTY.get(), Unit.INSTANCE);
        }
        InteractionHand hand = InteractionHand.MAIN_HAND;
        player.setItemInHand(hand, scroll);
        player.gameMode.useItem(player, level, scroll, hand);

        ItemStack now = player.getItemInHand(hand);
        if (!(now.getItem() instanceof MapItem)) {
            src.sendFailure(Component.literal(
                    "testunfurl FAIL: hand has "
                            + BuiltInRegistries.ITEM.getKey(now.getItem()) + " x" + now.getCount()));
            return 0;
        }
        MapId mapId = now.get(DataComponents.MAP_ID);
        MapDecorations decos = now.getOrDefault(DataComponents.MAP_DECORATIONS, MapDecorations.EMPTY);
        if (mapId == null || decos.decorations().isEmpty()) {
            src.sendFailure(Component.literal(
                    "testunfurl FAIL: map missing id or decorations"));
            return 0;
        }
        MapDecorations.Entry target = decos.decorations().values().iterator().next();
        int tx = (int) target.x();
        int tz = (int) target.z();
        int id = mapId.id();

        // Worldgen can spit out a shipless outpost (pool fallback to empty, jigsaw clipping).
        // Force-loading 3 chunks around the structure target catches the ship anchor wherever
        // jigsaw rotation/offset put it inside the base_plate_with_<ship> NBT.
        MCPShipAnchorBlockEntity anchor = findShipAnchorNear(
                level, new BlockPos(tx, level.getMinBuildHeight(), tz), /*chunkRadius=*/3);
        if (anchor == null) {
            src.sendFailure(Component.literal(
                    "testunfurl FAIL: map points at (" + tx + "," + tz
                            + ") but no ship_anchor within 3 chunks "
                            + "— map_id=" + id + " (shipless outpost? pool-fallback hit?)"));
            return 0;
        }
        BlockPos aPos = anchor.getBlockPos();
        String kind = anchor.getKindName();
        src.sendSuccess(() -> Component.literal(
                "testunfurl OK map_id=" + id + " target=" + tx + "," + tz
                        + " ship=" + (kind.isEmpty() ? "<unknown>" : kind)
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

    // Manual GUI flow, stage 2: after the executor has right-clicked the scroll and is
    // holding the resulting map, verify its target decoration points at a real structure.
    // Same correctness check the headless harness does, but invoked from chat so the
    // player can sanity-check what they see on the client.
    private static int testUnfurlVerify(CommandContext<CommandSourceStack> ctx, boolean galleon) {
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
        var tag = galleon ? MCPStructureTags.PIRATE_GALLEONS : MCPStructureTags.PIRATE_OUTPOSTS;
        BlockPos found = level.findNearestMapStructure(
                tag, new BlockPos(tx, level.getMinBuildHeight(), tz),
                /*radiusChunks=*/2, /*skipExistingChunks=*/false);

        if (found == null) {
            src.sendFailure(Component.literal(
                    "testunfurl verify FAIL: no " + tag.location()
                            + " near map target (" + tx + "," + tz + ")"));
            return 0;
        }
        int dx = found.getX() - tx;
        int dz = found.getZ() - tz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > 16) {
            src.sendFailure(Component.literal(String.format(
                    "testunfurl verify FAIL: nearest %s is %.1f blocks from map target (>16)",
                    tag.location(), dist)));
            return 0;
        }
        MCPShipAnchorBlockEntity anchor = findShipAnchorNear(level, found, /*chunkRadius=*/3);
        if (anchor == null) {
            src.sendFailure(Component.literal(String.format(
                    "testunfurl verify FAIL: structure at (%d,%d) has no ship_anchor "
                            + "within 3 chunks (shipless outpost — pool-fallback?)",
                    found.getX(), found.getZ())));
            return 0;
        }
        BlockPos aPos = anchor.getBlockPos();
        String kind = anchor.getKindName().isEmpty() ? "<unknown>" : anchor.getKindName();
        src.sendSuccess(() -> Component.literal(String.format(
                "testunfurl verify OK: map (%d,%d) ↔ structure (%d,%d), %.1fb; "
                        + "ship=%s anchor=(%d,%d,%d)",
                tx, tz, found.getX(), found.getZ(), dist,
                kind, aPos.getX(), aPos.getY(), aPos.getZ())), true);
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

    // ────────────────────────────── helpers ──────────────────────────────

    private static BlockPos findNearestOutpost(ServerLevel level, BlockPos origin) {
        Optional<Holder.Reference<Structure>> structure =
                level.registryAccess()
                        .registryOrThrow(Registries.STRUCTURE)
                        .getHolder(PILLAGER_OUTPOST_KEY);

        if (structure.isEmpty()) {
            MCPirates.LOGGER.warn("[outpost] pillager_outpost not found in Structure registry");
            return null;
        }

        var result = level.getChunkSource()
                .getGenerator()
                .findNearestMapStructure(
                        level,
                        HolderSet.direct(structure.get()),
                        origin,
                        /*radiusInChunks=*/100,
                        /*skipExistingChunks=*/false);

        return result != null ? result.getFirst() : null;
    }

    /** Force-load chunks in a (2r+1)² square around {@code center} and return the first
     *  ship anchor BE found. Forces the load because outpost chunks generated by
     *  findNearestMapStructure may have been unloaded again by the time we check —
     *  AirshipLiftoffTrigger's getChunkNow pattern returns null in that case. */
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
