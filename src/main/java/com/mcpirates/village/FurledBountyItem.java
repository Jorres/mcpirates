package com.mcpirates.village;

import com.mcpirates.MCPirates;
import com.mcpirates.pirates.DefeatedAirships;
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
 * The "furled bounty" scroll. Right-clicking it server-side picks the closest
 * non-{@link DefeatedAirships}-marked pillager outpost and swaps the scroll in
 * the player's hand for a filled bounty map pointing at that outpost.
 *
 * <h2>Why scroll → map at *unfurl* time, not at *trade* time</h2>
 *
 * <p>If the sheriff baked the target outpost into the map at trade-gen time
 * (the v0.1 design), every map a given sheriff issued in his lifetime targeted
 * the same outpost — the one closest to the villager's position at first
 * interaction. Deferring outpost selection until the player actually opens the
 * scroll means the search anchors at the player's location at that moment, so
 * the same scroll bought from the same sheriff resolves to different outposts
 * as the player wanders.
 *
 * <h2>Defeated-airship skipping</h2>
 *
 * <p>{@code findNearestMapStructure} doesn't accept a filter, so we use the
 * standard "search → check → retry from a perturbed origin" pattern: locate
 * the nearest outpost, ask {@link DefeatedAirships} whether it's been defeated,
 * and if so try again from an offset origin. {@link #MAX_RETRIES} bounds the
 * search so a heavily-defeated world doesn't lock the unfurl into a loop. If
 * we exhaust retries we still hand out the best candidate we found — better a
 * stale map than no map and lost emeralds.
 */
public final class FurledBountyItem extends Item {

    /** Map scale (1=1:2, 2=1:4 vanilla treasure, 3=1:8). 3 keeps the player on the
     *  painted square at typical sheriff→outpost distances. */
    private static final byte MAP_SCALE = 3;
    /** Translation key for the resulting filled_map's ITEM_NAME component. */
    private static final String MAP_DISPLAY_NAME_KEY = "pirate_bounty";
    /** {@code findNearestMapStructure}'s searchRadius is in chunks; 200ch = 3200 blocks. */
    private static final int SEARCH_RADIUS_CHUNKS = 200;
    /** How many times we re-try with a perturbed origin if the result is defeated. */
    private static final int MAX_RETRIES = 6;
    /** Perturbation distance (blocks) added per retry to nudge the search elsewhere. */
    private static final int RETRY_OFFSET_MIN_BLOCKS = 800;
    private static final int RETRY_OFFSET_MAX_BLOCKS = 2400;

    public FurledBountyItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            // Pass through on the client — we wait for the server-authoritative resolve.
            return InteractionResultHolder.pass(held);
        }

        BlockPos found = findUndefeatedOutpost(serverLevel, player);
        if (found == null) {
            // No outpost within the search ring. Refund the scroll (don't consume),
            // tell the player. Treating this as soft-fail so a player on an island
            // far from any village doesn't burn 16 emeralds worth of scroll.
            player.displayClientMessage(
                    Component.translatable("item.mcpirates.furled_bounty.no_target"), true);
            return InteractionResultHolder.fail(held);
        }

        ItemStack map = makeBountyMap(serverLevel, found);
        // Replace the scroll in hand with the filled map. consumeItem handles
        // creative mode (don't shrink) and 1-stack vs many-stack cases.
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
                "{} unfurled a bounty scroll → outpost at {} (defeated set has {})",
                player.getName().getString(), found,
                DefeatedAirships.get(serverLevel).defeatedCount());

        return InteractionResultHolder.consume(held);
    }

    /**
     * Iteratively probe {@code findNearestMapStructure}, perturbing the search origin
     * each time the result lands in a defeated chunk. Returns the first non-defeated
     * outpost we find, or the last-seen result if all retries collided with defeats.
     * Returns null only if vanilla itself returned null on every attempt.
     */
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
            best = candidate; // remember in case all subsequent attempts also collide
            if (!defeated.isDefeated(candidate)) {
                return candidate;
            }
            // Defeated — nudge search origin away from the player by a random vector.
            // Using a different angle/distance each attempt makes us walk around the
            // ring rather than re-landing on the same defeated outpost.
            double angle = random.nextDouble() * Math.PI * 2.0;
            int dist = RETRY_OFFSET_MIN_BLOCKS
                    + random.nextInt(RETRY_OFFSET_MAX_BLOCKS - RETRY_OFFSET_MIN_BLOCKS);
            int dx = (int) Math.round(Math.cos(angle) * dist);
            int dz = (int) Math.round(Math.sin(angle) * dist);
            searchOrigin = player.blockPosition().offset(dx, 0, dz);
        }
        return best; // null if vanilla never found one, else last-seen (possibly defeated)
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
