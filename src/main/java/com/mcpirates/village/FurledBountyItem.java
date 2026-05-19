package com.mcpirates.village;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.ships.galleon.GalleonUnlockState;
import com.mcpirates.pirates.DefeatedAirships;
import com.mcpirates.registry.MCPDataComponents;
import com.mcpirates.registry.MCPStructureTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/**
 * "Furled bounty" scroll: on right-click, pick the nearest non-defeated outpost and
 * hand back a filled map. Outpost is chosen at unfurl time (anchored at the player)
 * so the same scroll resolves differently as the player wanders.
 *
 * <p>Defeated outposts are skipped by perturbing the search origin and retrying up to
 * {@link #MAX_RETRIES} times; we fall back to the best candidate found rather than null.
 */
public final class FurledBountyItem extends Item {

    private static final byte MAP_SCALE = 3;
    private static final String MAP_DISPLAY_NAME_KEY = "pirate_bounty";
    private static final int SEARCH_RADIUS_CHUNKS = 200;
    /** Wider than outpost search — galleon structure_set spacing is ~64 chunks, so a
     *  smaller radius can miss every spawn cell in sparse regions. */
    private static final int GALLEON_SEARCH_RADIUS_CHUNKS = 400;
    private static final int MAX_RETRIES = 6;
    private static final int RETRY_OFFSET_MIN_BLOCKS = 800;
    private static final int RETRY_OFFSET_MAX_BLOCKS = 2400;

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
            // Pass through on the client — we wait for the server-authoritative resolve.
            return InteractionResultHolder.pass(held);
        }

        // Telemetry counter only; galleon mode is decided by the IS_GALLEON_BOUNTY
        // component stamped on the 5th map minted by SheriffMenu.
        DefeatedAirships airships = DefeatedAirships.get(serverLevel);
        int unfurlIndex = airships.incrementScrollsUnfurled();
        boolean isGalleon = held.has(MCPDataComponents.IS_GALLEON_BOUNTY.get());

        BlockPos found;
        if (isGalleon) {
            // Unlock the worldgen galleon structure_set, then point at the nearest yet-to-generate
            // chunk so the player is steered into unexplored territory.
            GalleonUnlockState.get(serverLevel).unlock();
            found = serverLevel.findNearestMapStructure(
                    MCPStructureTags.PIRATE_GALLEONS,
                    player.blockPosition(),
                    GALLEON_SEARCH_RADIUS_CHUNKS,
                    /*skipExistingChunks=*/true);
            if (found == null) {
                // Biome whitelist may have excluded everything in range — refund.
                player.displayClientMessage(
                        Component.translatable("item.mcpirates.furled_bounty.no_target"), true);
                return InteractionResultHolder.fail(held);
            }
        } else {
            found = findUndefeatedOutpost(serverLevel, player);
            if (found == null) {
                // Soft-fail: refund so an isolated player doesn't burn 16 emeralds.
                player.displayClientMessage(
                        Component.translatable("item.mcpirates.furled_bounty.no_target"), true);
                return InteractionResultHolder.fail(held);
            }
        }

        ItemStack map = makeBountyMap(serverLevel, found);
        if (!player.getAbilities().instabuild) {
            held.shrink(1);
        }
        if (held.isEmpty()) {
            player.setItemInHand(hand, map);
        } else {
            if (!player.getInventory().add(map)) {
                player.drop(map, /*includeThrowerName=*/false);
            }
        }

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1.0F, 1.0F);

        MCPirates.LOGGER.info(
                "{} unfurled bounty scroll #{} ({}) → {} (defeated set has {})",
                player.getName().getString(), unfurlIndex,
                isGalleon ? "BOSS galleon" : "regular outpost",
                found, airships.defeatedCount());

        return InteractionResultHolder.consume(held);
    }

    /** Probe; on a defeated hit, perturb the origin. Returns first non-defeated outpost,
     *  or last-seen if all collided, or null if vanilla never found one. */
    private static BlockPos findUndefeatedOutpost(ServerLevel level, Player player) {
        DefeatedAirships defeated = DefeatedAirships.get(level);
        RandomSource random = level.getRandom();
        BlockPos searchOrigin = player.blockPosition();
        BlockPos best = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            BlockPos candidate = level.findNearestMapStructure(
                    MCPStructureTags.PIRATE_OUTPOSTS, searchOrigin,
                    SEARCH_RADIUS_CHUNKS, /*skipExistingChunks=*/true);
            if (candidate == null) {
                continue; // try again with a different origin
            }
            best = candidate;
            if (!defeated.isDefeated(candidate)) {
                return candidate;
            }
            // Walk around the ring with a random angle/distance per attempt.
            double angle = random.nextDouble() * Math.PI * 2.0;
            int dist = RETRY_OFFSET_MIN_BLOCKS
                    + random.nextInt(RETRY_OFFSET_MAX_BLOCKS - RETRY_OFFSET_MIN_BLOCKS);
            int dx = (int) Math.round(Math.cos(angle) * dist);
            int dz = (int) Math.round(Math.sin(angle) * dist);
            searchOrigin = player.blockPosition().offset(dx, 0, dz);
        }
        return best;
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
