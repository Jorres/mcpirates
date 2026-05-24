package com.mcpirates.village;

import com.mcpirates.MCPirates;
import com.mcpirates.pirates.DefeatedAirships;
import com.mcpirates.registry.MCPDataComponents;
import com.mcpirates.worldgen.OutpostPermits;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.List;

/**
 * "Furled bounty" scroll. On right-click (server-side), picks a ship kind, asks
 * {@link OutpostPermits} for the nearest unclaimed structure-set candidate cell, stamps
 * a permit there, and hands back a filled map pointing at the cell. The structure
 * actually places when the player visits the cell — {@link com.mcpirates.worldgen.PermittedShipOutpostStructure}
 * consults the permit set during chunk generation.
 *
 * <p>Galleon scrolls (component {@code IS_GALLEON_BOUNTY}) target {@link #GALLEON_KIND};
 * regular scrolls roll uniformly from {@link #NORMAL_BOUNTY_KINDS}.
 */
public final class FurledBountyItem extends Item {

    private static final byte MAP_SCALE = 3;
    private static final String MAP_DISPLAY_NAME_KEY = "pirate_bounty";
    private static final int SEARCH_RADIUS_CHUNKS = 200;
    /** Galleon structure_set has wider spacing (64 chunks); needs a deeper search to
     *  guarantee at least one virgin cell in sparse regions. */
    private static final int GALLEON_SEARCH_RADIUS_CHUNKS = 400;

    public static final ResourceLocation GALLEON_KIND = MCPirates.id("galleon_outpost");

    public static final List<ResourceLocation> NORMAL_BOUNTY_KINDS = List.of(
            MCPirates.id("airship_small_outpost"),
            MCPirates.id("crossbow_board_outpost"),
            MCPirates.id("ramship_outpost"));

    public FurledBountyItem(Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        if (stack.has(MCPDataComponents.IS_GALLEON_BOUNTY.get())) {
            return Component.translatable("item.mcpirates.furled_bounty.galleon");
        }
        return super.getName(stack);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResultHolder.pass(held);
        }

        DefeatedAirships airships = DefeatedAirships.get(serverLevel);
        int unfurlIndex = airships.incrementScrollsUnfurled();
        boolean isGalleon = held.has(MCPDataComponents.IS_GALLEON_BOUNTY.get());

        ResourceLocation kind;
        int searchRadius;
        if (isGalleon) {
            kind = GALLEON_KIND;
            searchRadius = GALLEON_SEARCH_RADIUS_CHUNKS;
        } else {
            RandomSource rng = serverLevel.getRandom();
            kind = NORMAL_BOUNTY_KINDS.get(rng.nextInt(NORMAL_BOUNTY_KINDS.size()));
            searchRadius = SEARCH_RADIUS_CHUNKS;
        }

        ChunkPos origin = new ChunkPos(player.blockPosition());
        ChunkPos cell = OutpostPermits.findUnclaimedCandidate(serverLevel, kind, origin, searchRadius);
        if (cell == null) {
            MCPirates.LOGGER.info("bounty unfurl by {} found no virgin {} cell within {} chunks",
                    player.getName().getString(), kind, searchRadius);
            player.displayClientMessage(
                    Component.translatable("item.mcpirates.furled_bounty.no_target"), true);
            return InteractionResultHolder.fail(held);
        }
        OutpostPermits.get(serverLevel).permit(kind, cell);

        BlockPos target = surfaceTargetForCell(serverLevel, cell);
        ItemStack map = makeBountyMap(serverLevel, target);
        if (!player.getAbilities().instabuild) {
            held.shrink(1);
        }

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1.0F, 1.0F);

        MCPirates.LOGGER.info("{} unfurled bounty scroll #{} ({}) -> permit {} at {} target {}",
                player.getName().getString(), unfurlIndex,
                isGalleon ? "GALLEON" : "regular",
                kind, cell, target);

        if (held.isEmpty()) {
            return InteractionResultHolder.consume(map);
        }
        if (!player.getInventory().add(map)) {
            player.drop(map, /*includeThrowerName=*/false);
        }
        return InteractionResultHolder.consume(held);
    }

    /** Map's center decoration uses the structure's nominal center-block + surface-y at that
     *  column. The exact surface Y matters for {@code /locate}-style cross-checks and for
     *  the {@code testunfurl tp} subcommand. */
    private static BlockPos surfaceTargetForCell(ServerLevel level, ChunkPos cell) {
        int x = cell.getMiddleBlockX();
        int z = cell.getMiddleBlockZ();
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
        return new BlockPos(x, y, z);
    }

    private static ItemStack makeBountyMap(ServerLevel serverLevel, BlockPos targetPos) {
        ItemStack map = MapItem.create(
                serverLevel, targetPos.getX(), targetPos.getZ(),
                MAP_SCALE, /*trackingPosition=*/true, /*unlimitedTracking=*/true);
        MapItem.renderBiomePreviewMap(serverLevel, map);
        MapItemSavedData.addTargetDecoration(map, targetPos, "+", MapDecorationTypes.RED_X);
        map.set(DataComponents.ITEM_NAME, Component.translatable(MAP_DISPLAY_NAME_KEY));
        return map;
    }
}
