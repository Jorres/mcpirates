package com.mcpirates.commands;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.AirshipBrain;
import com.mcpirates.airship.AirshipLiftoffTrigger;
import com.mcpirates.airship.GalleonSpawner;
import com.mcpirates.util.FunnyNames;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Optional;
import java.util.Set;

/**
 * All mcpirates dev/debug chat commands hang off the {@code /mcpirates} root.
 *
 * <h2>Subcommands</h2>
 *
 * <ul>
 *     <li>{@code /mcpirates fire on|off} — toggles cannon firing globally
 *         ({@link AirshipBrain#CANNON_FIRE_ENABLED}). Aiming is always on so
 *         the cannon visually tracks the player; this just gates whether
 *         projectiles actually spawn. Defaults OFF so test sessions don't get
 *         interrupted by cannonballs.</li>
 *     <li>{@code /mcpirates outpost tp} — teleports the issuing player to a
 *         safe surface position near the nearest {@code minecraft:pillager_outpost}
 *         (searches a 100-chunk radius). Useful when iterating on outpost
 *         worldgen / airship lift-off.</li>
 *     <li>{@code /mcpirates outpost spawn [airship_small|crossbow_board]} —
 *         places a pillager outpost 48 blocks east of the issuing player.
 *         With no argument, places {@code minecraft:pillager_outpost} and lets
 *         the worldgen airships pool roll randomly between the two ships. With
 *         an argument, places one of our variant structures
 *         ({@code mcpirates:outpost_with_<ship>}) that uses a single-ship pool,
 *         guaranteeing the requested ship. Bypasses worldgen so a new outpost
 *         can be built without travelling.</li>
 *     <li>{@code /mcpirates sheriff spawn} — spawns a named sheriff villager
 *         two blocks NE of the issuing player. Bypasses the village's natural
 *         POI-claim flow for trade testing.</li>
 *     <li>{@code /mcpirates galleon spawn} — places a galleon 80 blocks east of
 *         the issuing player at heightmap+altitude. Same placement primitive
 *         {@link com.mcpirates.airship.GalleonSpawner} uses for boss-bounty
 *         spawns, just at deterministic close range so liftoff can be tested
 *         without flying 1500 blocks to find the random-far spawn.</li>
 * </ul>
 *
 * <p>All subcommands require op level 2 (vanilla cheats). In dev {@code Dev}
 * is op by default.
 */
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
                                        .executes(ctx -> spawnOutpost(ctx, "crossbow_board")))))

                .then(Commands.literal("sheriff")
                        .then(Commands.literal("spawn").executes(MCPCommands::spawnSheriff)))

                .then(Commands.literal("galleon")
                        .then(Commands.literal("spawn").executes(MCPCommands::spawnGalleon)));

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

    /** Toggle the auto-liftoff trigger. Off = placed ships stay dormant so flat-world
     *  iteration on the NBT layout doesn't fire activations every time you /place. */
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

    /** @param ship null = vanilla {@code pillager_outpost} (random ship via the
     *              50/50 airships pool); otherwise {@code "airship_small"} or
     *              {@code "crossbow_board"} → one of our variant structures whose
     *              start_pool references a single-ship airship pool. */
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
            // Build the /summon command via string concat. Funny names come from a
            // hand-curated list (FunnyNames.SHERIFF_*) so a stray quote is impossible;
            // if that list ever grows from a config we should switch to a structured
            // EntityType.PILLAGER.create + setCustomName path.
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

    // ────────────────────────────── /mcpirates galleon spawn ──────────────────────────────

    private static int spawnGalleon(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        BlockPos origin = BlockPos.containing(src.getPosition());

        // 80 blocks east of the player. Galleon footprint is 12×28; 80 keeps it clear
        // of the player's chunk while still close enough to fly to in a few seconds.
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

    /**
     * Find a surface block adjacent to {@code origin} that has solid ground, two
     * blocks of free vertical space, and open sky — i.e. a place the player won't
     * spawn inside the watchtower or in a cave. Spirals outward up to 48 blocks.
     */
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
        // If the heightmap value is below the outpost anchor (we may be in a cave or
        // the outpost is on a mesa pillar), start the downward search 40 blocks above
        // the anchor so we don't shoot straight into the cave ceiling.
        int searchY = Math.max(startY + 40, surfaceY);

        for (int y = searchY; y >= level.getMinBuildHeight(); y--) {
            if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                return y;
            }
        }
        return Math.max(surfaceY, level.getSeaLevel());
    }
}
