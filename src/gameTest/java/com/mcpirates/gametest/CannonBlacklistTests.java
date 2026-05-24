package com.mcpirates.gametest;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.hardware.CannonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import rbasamoyai.createbigcannons.block_armor_properties.BlockArmorPropertiesHandler;
import rbasamoyai.createbigcannons.block_armor_properties.BlockArmorPropertiesProvider;
import rbasamoyai.createbigcannons.block_armor_properties.SimpleBlockArmorProperties;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.config.CBCCfgMunitions;
import rbasamoyai.createbigcannons.config.CBCConfigs;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Block-impact policy tests. Each test loads a user-built template containing a real CBC
 * cannon aimed at a fragile target, fires it via {@link CannonOps#fireRawAt}, and asserts
 * post-shot block state.
 *
 * <ul>
 *   <li>{@code chest_indestructible_test} — a single chest sits under the cannon muzzle.
 *       After firing, the chest count must be unchanged.</li>
 *   <li>{@code envelope_protection_layer_test} — an iron-bar layer fronts a balloon
 *       envelope. After firing, at least one iron bar must have been broken (proving the
 *       shot landed and damage works) while every envelope block must survive (proving the
 *       one-block-cap mixin stopped the shot at the bar).</li>
 * </ul>
 */
@GameTestHolder(MCPirates.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CannonBlacklistTests {

    private static final int IMPACT_WAIT_TICKS = 80;

    private static final TagKey<Block> ENVELOPE_TAG =
            TagKey.create(Registries.BLOCK,
                    ResourceLocation.fromNamespaceAndPath("aeronautics", "envelope"));

    private CannonBlacklistTests() {}

    private static final int EXPECTED_EMERALDS = 10;

    @GameTest(template = "chest_indestructible_test", timeoutTicks = 200, batch = "cannon_chest")
    public static void chestSurvivesCannonShot(GameTestHelper helper) {
        forceAllDamage();
        Predicate<BlockState> chests = s -> s.getBlock() instanceof net.minecraft.world.level.block.ChestBlock;

        BlockPos chestPos = findFirstMatching(helper, chests);
        helper.assertTrue(chestPos != null, "template missing chest");
        int emeraldsPre = countItemsInContainer(helper, chestPos, Items.EMERALD);
        helper.assertTrue(emeraldsPre == EXPECTED_EMERALDS,
                "expected " + EXPECTED_EMERALDS + " emeralds in chest pre-shot, found " + emeraldsPre);

        BlockPos mountPos = findCannonMount(helper);
        helper.assertTrue(mountPos != null, "template missing CannonMountBlockEntity");
        assembleAndFire(helper, mountPos);

        helper.runAfterDelay(IMPACT_WAIT_TICKS, () -> {
            BlockPos chestPostPos = findFirstMatching(helper, chests);
            helper.assertTrue(chestPostPos != null, "chest gone — block_armor tag should have protected it");
            int emeraldsPost = countItemsInContainer(helper, chestPostPos, Items.EMERALD);
            helper.assertTrue(emeraldsPost == EXPECTED_EMERALDS,
                    "emerald count " + EXPECTED_EMERALDS + " → " + emeraldsPost
                            + " — chest contents disturbed");
            helper.succeed();
        });
    }

    /** Sanity check on the data pipeline: if the tagged armor JSON had low toughness, the
     *  chest would break. Replaces the chest's armor entry with {@code (0.1, 0.1)} via the
     *  CBC registry's BLOCK_MAP (higher priority than TAG_MAP), fires, asserts chest is gone,
     *  then restores. The restore runs in the delayed callback BEFORE assertions so a
     *  failing test doesn't poison subsequent tests in the same JVM. */
    @GameTest(template = "chest_indestructible_test", timeoutTicks = 200, batch = "cannon_chest_negative")
    public static void chestBreaksWhenToughnessLow(GameTestHelper helper) {
        forceAllDamage();
        Predicate<BlockState> chests = s -> s.getBlock() instanceof net.minecraft.world.level.block.ChestBlock;

        BlockPos chestPos = findFirstMatching(helper, chests);
        helper.assertTrue(chestPos != null, "template missing chest");
        Block chestBlock = helper.getLevel().getBlockState(chestPos).getBlock();

        Map<Block, BlockArmorPropertiesProvider> blockMap = cbcBlockArmorBlockMap();
        BlockArmorPropertiesProvider saved = blockMap.put(chestBlock,
                new SimpleBlockArmorProperties(0.1, 0.1));

        BlockPos mountPos = findCannonMount(helper);
        helper.assertTrue(mountPos != null, "template missing CannonMountBlockEntity");
        assembleAndFire(helper, mountPos);

        helper.runAfterDelay(IMPACT_WAIT_TICKS, () -> {
            // Restore first so a failing assertion doesn't leak state into other tests.
            if (saved == null) blockMap.remove(chestBlock);
            else blockMap.put(chestBlock, saved);

            BlockPos chestPostPos = findFirstMatching(helper, chests);
            helper.assertTrue(chestPostPos == null,
                    "chest should have broken with toughness=0.1 but is still at " + chestPostPos
                            + " — block_armor pipeline may be ignoring overrides");
            helper.succeed();
        });
    }

    @GameTest(template = "envelope_protection_layer_test", timeoutTicks = 200, batch = "cannon_envelope")
    public static void protectiveLayerBlocksBalloon(GameTestHelper helper) {
        forceAllDamage();
        Predicate<BlockState> bars = s -> s.getBlock() == net.minecraft.world.level.block.Blocks.IRON_BARS;
        Predicate<BlockState> envelopes = s -> s.is(ENVELOPE_TAG);

        int barsPre = count(helper, bars);
        int envelopesPre = count(helper, envelopes);
        helper.assertTrue(barsPre > 0, "template missing iron bars");
        helper.assertTrue(envelopesPre > 0, "template missing envelope block");

        BlockPos mountPos = findCannonMount(helper);
        helper.assertTrue(mountPos != null, "template missing CannonMountBlockEntity");
        assembleAndFire(helper, mountPos);

        helper.runAfterDelay(IMPACT_WAIT_TICKS, () -> {
            int barsPost = count(helper, bars);
            int envelopesPost = count(helper, envelopes);
            helper.assertTrue(barsPost < barsPre,
                    "iron bars unchanged (" + barsPre + " → " + barsPost
                            + ") — shot may have missed or damage path is broken");
            helper.assertTrue(envelopesPost == envelopesPre,
                    "envelope count " + envelopesPre + " → " + envelopesPost
                            + " — one-block cap should have stopped the shot at the bars");
            helper.succeed();
        });
    }

    private static BlockPos findCannonMount(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AABB bb = helper.getBounds();
        for (BlockPos p : BlockPos.betweenClosed(
                (int) Math.floor(bb.minX), (int) Math.floor(bb.minY), (int) Math.floor(bb.minZ),
                (int) Math.ceil(bb.maxX) - 1, (int) Math.ceil(bb.maxY) - 1, (int) Math.ceil(bb.maxZ) - 1)) {
            BlockEntity be = level.getBlockEntity(p);
            if (be instanceof CannonMountBlockEntity) return p.immutable();
        }
        return null;
    }

    /** Push the assembly redstone signal so CBC builds the contraption (creating the
     *  POCE that {@link CannonOps#fireRawAt} needs to find), then fire with 2 charges. */
    private static void assembleAndFire(GameTestHelper helper, BlockPos worldMountPos) {
        ServerLevel level = helper.getLevel();
        if (!(level.getBlockEntity(worldMountPos) instanceof CannonMountBlockEntity mount)) {
            helper.fail("mount disappeared at " + worldMountPos);
            return;
        }
        // Simulate assembly redstone pulse. firePower=0 so we don't auto-fire here.
        mount.onRedstoneUpdate(true, false, false, false, 0);
        // 1 powder + 1 shot = 2 cells, matching the test cannon's chamber count. Loading
        // 2 powders would consume both chambers and leave no slot for the projectile.
        boolean fired = CannonOps.fireRawAt(level, level, worldMountPos, 1);
        helper.assertTrue(fired, "fireRawAt returned false at " + worldMountPos);
    }

    private static BlockPos findFirstMatching(GameTestHelper helper, Predicate<BlockState> match) {
        ServerLevel level = helper.getLevel();
        AABB bb = helper.getBounds();
        for (BlockPos p : BlockPos.betweenClosed(
                (int) Math.floor(bb.minX), (int) Math.floor(bb.minY), (int) Math.floor(bb.minZ),
                (int) Math.ceil(bb.maxX) - 1, (int) Math.ceil(bb.maxY) - 1, (int) Math.ceil(bb.maxZ) - 1)) {
            if (match.test(level.getBlockState(p))) return p.immutable();
        }
        return null;
    }

    private static int countItemsInContainer(GameTestHelper helper, BlockPos pos, net.minecraft.world.item.Item item) {
        BlockEntity be = helper.getLevel().getBlockEntity(pos);
        if (!(be instanceof Container c)) return -1;
        int total = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (s.is(item)) total += s.getCount();
        }
        return total;
    }

    private static int count(GameTestHelper helper, Predicate<BlockState> match) {
        ServerLevel level = helper.getLevel();
        AABB bb = helper.getBounds();
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(
                (int) Math.floor(bb.minX), (int) Math.floor(bb.minY), (int) Math.floor(bb.minZ),
                (int) Math.ceil(bb.maxX) - 1, (int) Math.ceil(bb.maxY) - 1, (int) Math.ceil(bb.maxZ) - 1)) {
            if (match.test(level.getBlockState(p))) n++;
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private static Map<Block, BlockArmorPropertiesProvider> cbcBlockArmorBlockMap() {
        try {
            Field f = BlockArmorPropertiesHandler.class.getDeclaredField("BLOCK_MAP");
            f.setAccessible(true);
            return (Map<Block, BlockArmorPropertiesProvider>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("failed to access CBC BlockArmorPropertiesHandler.BLOCK_MAP", e);
        }
    }

    private static void forceAllDamage() {
        if (CBCConfigs.server().munitions.damageRestriction.get() != CBCCfgMunitions.GriefState.ALL_DAMAGE) {
            CBCConfigs.server().munitions.damageRestriction.set(CBCCfgMunitions.GriefState.ALL_DAMAGE);
        }
    }
}
