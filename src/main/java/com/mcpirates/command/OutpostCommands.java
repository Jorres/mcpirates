package com.mcpirates.command;

import com.mcpirates.MCPirates;
import com.mcpirates.util.FunnyNames;
import com.mojang.brigadier.CommandDispatcher;
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
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Optional;
import java.util.Set;

public class OutpostCommands {

    private static final ResourceKey<Structure> PILLAGER_OUTPOST_KEY =
            ResourceKey.create(Registries.STRUCTURE,
                    ResourceLocation.withDefaultNamespace("pillager_outpost"));

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("pirate")
                .requires(src -> src.hasPermission(2))

                .then(Commands.literal("outpost")
                        .then(Commands.literal("tp")
                                .executes(OutpostCommands::tpNearest))
                        .then(Commands.literal("spawn")
                                .executes(OutpostCommands::spawnOutpost)))

                .then(Commands.literal("sheriff")
                        .then(Commands.literal("spawn")
                                .executes(OutpostCommands::spawnSheriff)))
        );
    }

    // ── 1. /outpost tp ─────────────────────────────────────────────────────────

    private static int tpNearest(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        BlockPos origin = BlockPos.containing(src.getPosition());

        src.sendSystemMessage(Component.literal("Ищу ближайший Pillager Outpost..."));

        BlockPos found = findNearestOutpost(level, origin);
        if (found == null) {
            src.sendFailure(Component.literal("Pillager Outpost не найден в радиусе поиска."));
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
                    "Телепортирован к Pillager Outpost: " + finalFound.toShortString()), true);
        } catch (Exception e) {
            src.sendFailure(Component.literal("Команда требует игрока."));
            return 0;
        }
        return 1;
    }

    // ── 2. /outpost spawn ──────────────────────────────────────────────────────

    private static int spawnOutpost(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        BlockPos origin = BlockPos.containing(src.getPosition());

        // Размещаем структуру в 48 блоках к востоку от игрока
        int tx = origin.getX() + 48;
        int tz = origin.getZ();
        int ty = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, tx, tz);

        String cmd = String.format("place structure minecraft:pillager_outpost %d %d %d", tx, ty, tz);
        try {
            level.getServer().getCommands().performPrefixedCommand(src.withPermission(4), cmd);
            src.sendSuccess(() -> Component.literal(
                    "Pillager Outpost размещён у " + new BlockPos(tx, ty, tz).toShortString()), true);
        } catch (Exception e) {
            MCPirates.LOGGER.error("[outpost spawn] failed", e);
            src.sendFailure(Component.literal("Не удалось разместить outpost: " + e.getMessage()));
            return 0;
        }
        return 1;
    }

    private static int spawnSheriff(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        BlockPos origin = BlockPos.containing(src.getPosition());

        int x = origin.getX() + 2;
        int z = origin.getZ() + 2;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1;
        String sheriffName = FunnyNames.nextSheriffName(level.getRandom());

        try {
            String cmd = "summon minecraft:villager " + x + " " + y + " " + z +
                    " {VillagerData:{profession:\"mcpirates:sheriff\",level:2},PersistenceRequired:1b,CustomName:'\""
                    + sheriffName +
                    "\"',CustomNameVisible:1b}";
            level.getServer().getCommands().performPrefixedCommand(src.withPermission(4), cmd);
            src.sendSuccess(() -> Component.literal(
                    "Шериф " + sheriffName + " заспавнен у " + new BlockPos(x, y, z).toShortString()), true);
        } catch (Exception e) {
            MCPirates.LOGGER.error("[sheriff spawn] failed", e);
            src.sendFailure(Component.literal("Не удалось заспавнить шерифа: " + e.getMessage()));
            return 0;
        }

        return 1;
    }


    // ── Поиск безопасной высоты ────────────────────────────────────────────────

    private static Vec3 findSafeTeleportPos(ServerLevel level, BlockPos origin) {
        // Ищем безопасную точку вокруг якоря outpost в радиусе 48 блоков.
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
        // Используем Heightmap для быстрого поиска поверхности
        int surfaceY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        // Но если это ниже outpost (может быть пещера), ищем с startY+30 вниз
        int searchY = Math.max(startY + 40, surfaceY);

        // Идём вниз от searchY пока не найдём твёрдый блок
        for (int y = searchY; y >= level.getMinBuildHeight(); y--) {
            if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                // Нашли твёрдый блок, возвращаем Y выше него
                return y;
            }
        }
        // Fallback на поверхность если ничего не нашли
        return Math.max(surfaceY, level.getSeaLevel());
    }

    // ── Поиск структуры ────────────────────────────────────────────────────────

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
                        100,   // радиус поиска в чанках
                        false  // не пропускать незагруженные чанки
                );

        return result != null ? result.getFirst() : null;
    }
}

