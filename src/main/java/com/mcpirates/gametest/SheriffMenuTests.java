package com.mcpirates.gametest;

import com.mcpirates.MCPDataKeys;
import com.mcpirates.MCPirates;
import com.mcpirates.registry.MCPDataComponents;
import com.mcpirates.registry.MCPItems;
import com.mcpirates.registry.MCPVillagerProfessions;
import com.mcpirates.village.sheriff.SheriffMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;

import java.util.Locale;

/**
 * Drives the server-side state machine of {@link SheriffMenu} without rendering.
 * Visual / screen tests live in the manual {@code runClientQuick} loop — gametest
 * server is headless.
 *
 * <p>Each test spawns a sheriff villager + a mock {@link GameTestPlayer} on the
 * 7x1x7 stone arena template ({@code sheriff_test_arena}), opens the menu directly
 * (skipping the {@code EntityInteract} event so the unit-under-test is the menu),
 * then drives slot operations via {@link SheriffMenu#clicked} and asserts on the
 * three villager NBT counters.
 *
 * <p>The "open via interact" path is exercised separately in {@link #opensCustomMenuOnInteract}
 * — that's the only test that touches the event listener.
 */
@GameTestHolder(MCPirates.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SheriffMenuTests {

    private static final String TEMPLATE = "sheriff_test_arena";
    /** Arena-local sheriff spawn. The 7x1x7 floor is centered at (3.5, 1, 3.5). */
    private static final BlockPos SHERIFF_SPAWN = new BlockPos(3, 1, 3);
    private static final BlockPos PLAYER_SPAWN = new BlockPos(2, 1, 3);

    private SheriffMenuTests() {}

    @GameTest(template = TEMPLATE, timeoutTicks = 100, setupTicks = 2, batch = "sheriff_menu_open")
    public static void sheriffMenuOpensOnInteract(GameTestHelper helper) {
        Villager sheriff = spawnSheriff(helper);
        GameTestPlayer player = spawnPlayer(helper);

        helper.startSequence()
                .thenExecuteAfter(2, () -> player.interactOn(sheriff, InteractionHand.MAIN_HAND))
                .thenIdle(2)
                .thenExecute(() -> {
                    if (!(player.containerMenu instanceof SheriffMenu)) {
                        helper.fail("expected SheriffMenu after interact, got "
                                + player.containerMenu.getClass().getSimpleName());
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100, setupTicks = 2, batch = "sheriff_menu_map_pickup")
    public static void sheriffMenuMapPickupBumpsCounter(GameTestHelper helper) {
        Villager sheriff = spawnSheriff(helper);
        GameTestPlayer player = spawnPlayer(helper);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    SheriffMenu menu = openMenu(player, sheriff);
                    menu.clicked(SheriffMenu.MAP_ROW_OFFSET + 0, 0, ClickType.PICKUP, player);
                    assertCounter(helper, sheriff, MCPDataKeys.SHERIFF_MAPS_CLAIMED_NBT_KEY, 1);
                    if (!menu.getCarried().is(MCPItems.FURLED_BOUNTY.get())) {
                        helper.fail("expected furled_bounty in carried slot, got " + menu.getCarried());
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100, setupTicks = 2, batch = "sheriff_menu_map_out_of_order")
    public static void sheriffMenuMapOutOfOrderRejected(GameTestHelper helper) {
        Villager sheriff = spawnSheriff(helper);
        GameTestPlayer player = spawnPlayer(helper);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    SheriffMenu menu = openMenu(player, sheriff);
                    menu.clicked(SheriffMenu.MAP_ROW_OFFSET + 2, 0, ClickType.PICKUP, player);
                    assertCounter(helper, sheriff, MCPDataKeys.SHERIFF_MAPS_CLAIMED_NBT_KEY, 0);
                    if (!menu.getCarried().isEmpty()) {
                        helper.fail("carried slot should be empty after out-of-order click");
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100, setupTicks = 2, batch = "sheriff_menu_map_locked_pending_seal")
    public static void sheriffMenuMapLockedPendingSeal(GameTestHelper helper) {
        Villager sheriff = spawnSheriff(helper);
        GameTestPlayer player = spawnPlayer(helper);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    setCounter(sheriff, MCPDataKeys.SHERIFF_MAPS_CLAIMED_NBT_KEY, 1);
                    setCounter(sheriff, MCPDataKeys.SHERIFF_SEALS_RETURNED_NBT_KEY, 0);
                    SheriffMenu menu = openMenu(player, sheriff);
                    menu.clicked(SheriffMenu.MAP_ROW_OFFSET + 1, 0, ClickType.PICKUP, player);
                    assertCounter(helper, sheriff, MCPDataKeys.SHERIFF_MAPS_CLAIMED_NBT_KEY, 1);
                    if (!menu.getCarried().isEmpty()) {
                        helper.fail("map slot 1 should be locked while seal 0 outstanding");
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100, setupTicks = 2, batch = "sheriff_menu_seal_wrong_slot")
    public static void sheriffMenuSealWrongSlotRejected(GameTestHelper helper) {
        Villager sheriff = spawnSheriff(helper);
        GameTestPlayer player = spawnPlayer(helper);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    setCounter(sheriff, MCPDataKeys.SHERIFF_MAPS_CLAIMED_NBT_KEY, 1);
                    setCounter(sheriff, MCPDataKeys.SHERIFF_SEALS_RETURNED_NBT_KEY, 0);
                    SheriffMenu menu = openMenu(player, sheriff);
                    menu.setCarried(new ItemStack(MCPItems.CAPTAIN_SEAL.get()));

                    menu.clicked(SheriffMenu.SEAL_ROW_OFFSET + 1, 0, ClickType.PICKUP, player);
                    if (menu.getCarried().isEmpty()) {
                        helper.fail("seal at wrong slot should bounce; carried got drained");
                    }
                    assertCounter(helper, sheriff, MCPDataKeys.SHERIFF_SEALS_RETURNED_NBT_KEY, 0);
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100, setupTicks = 2, batch = "sheriff_menu_seal_submit")
    public static void sheriffMenuSealSubmitBumpsCounter(GameTestHelper helper) {
        Villager sheriff = spawnSheriff(helper);
        GameTestPlayer player = spawnPlayer(helper);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    setCounter(sheriff, MCPDataKeys.SHERIFF_MAPS_CLAIMED_NBT_KEY, 1);
                    SheriffMenu menu = openMenu(player, sheriff);
                    menu.setCarried(new ItemStack(MCPItems.CAPTAIN_SEAL.get()));

                    menu.clicked(SheriffMenu.SEAL_ROW_OFFSET + 0, 0, ClickType.PICKUP, player);
                    menu.broadcastChanges();
                    assertCounter(helper, sheriff, MCPDataKeys.SHERIFF_SEALS_RETURNED_NBT_KEY, 1);
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100, setupTicks = 2, batch = "sheriff_menu_reward_locked")
    public static void sheriffMenuRewardInactiveBeforeSubmit(GameTestHelper helper) {
        Villager sheriff = spawnSheriff(helper);
        GameTestPlayer player = spawnPlayer(helper);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    SheriffMenu menu = openMenu(player, sheriff);
                    menu.clicked(SheriffMenu.REWARD_ROW_OFFSET + 0, 0, ClickType.PICKUP, player);
                    if (!menu.getCarried().isEmpty()) {
                        helper.fail("reward slot 0 should be locked before any seal submitted");
                    }
                    assertCounter(helper, sheriff, MCPDataKeys.SHERIFF_REWARDS_CLAIMED_NBT_KEY, 0);
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100, setupTicks = 2, batch = "sheriff_menu_reward_pickup")
    public static void sheriffMenuRewardPickupGivesEmeralds(GameTestHelper helper) {
        Villager sheriff = spawnSheriff(helper);
        GameTestPlayer player = spawnPlayer(helper);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    setCounter(sheriff, MCPDataKeys.SHERIFF_MAPS_CLAIMED_NBT_KEY, 1);
                    setCounter(sheriff, MCPDataKeys.SHERIFF_SEALS_RETURNED_NBT_KEY, 1);
                    SheriffMenu menu = openMenu(player, sheriff);
                    menu.clicked(SheriffMenu.REWARD_ROW_OFFSET + 0, 0, ClickType.PICKUP, player);
                    ItemStack carried = menu.getCarried();
                    if (!carried.is(Items.EMERALD)) {
                        helper.fail("expected emerald in carried slot, got " + carried);
                    }
                    if (carried.getCount() != SheriffMenu.REWARD_EMERALDS) {
                        helper.fail("expected " + SheriffMenu.REWARD_EMERALDS + " emeralds, got "
                                + carried.getCount());
                    }
                    assertCounter(helper, sheriff, MCPDataKeys.SHERIFF_REWARDS_CLAIMED_NBT_KEY, 1);
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100, setupTicks = 2, batch = "sheriff_menu_fifth_galleon")
    public static void sheriffMenuFifthMapHasGalleonComponent(GameTestHelper helper) {
        Villager sheriff = spawnSheriff(helper);
        GameTestPlayer player = spawnPlayer(helper);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    setCounter(sheriff, MCPDataKeys.SHERIFF_MAPS_CLAIMED_NBT_KEY, 4);
                    setCounter(sheriff, MCPDataKeys.SHERIFF_SEALS_RETURNED_NBT_KEY, 4);
                    SheriffMenu menu = openMenu(player, sheriff);
                    menu.clicked(SheriffMenu.MAP_ROW_OFFSET + 4, 0, ClickType.PICKUP, player);
                    ItemStack carried = menu.getCarried();
                    if (!carried.is(MCPItems.FURLED_BOUNTY.get())) {
                        helper.fail("expected furled_bounty, got " + carried);
                        return;
                    }
                    if (!carried.has(MCPDataComponents.IS_GALLEON_BOUNTY.get())) {
                        helper.fail("5th furled_bounty missing IS_GALLEON_BOUNTY data component");
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100, setupTicks = 2, batch = "sheriff_menu_retired")
    public static void sheriffMenuRetiredAllLocked(GameTestHelper helper) {
        Villager sheriff = spawnSheriff(helper);
        GameTestPlayer player = spawnPlayer(helper);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    setCounter(sheriff, MCPDataKeys.SHERIFF_MAPS_CLAIMED_NBT_KEY, 5);
                    setCounter(sheriff, MCPDataKeys.SHERIFF_SEALS_RETURNED_NBT_KEY, 5);
                    setCounter(sheriff, MCPDataKeys.SHERIFF_REWARDS_CLAIMED_NBT_KEY, 5);
                    SheriffMenu menu = openMenu(player, sheriff);
                    if (!menu.isRetired()) helper.fail("isRetired should be true at seals==5");
                    if (menu.activeMapIndex() != -1) helper.fail("activeMapIndex should be -1");
                    if (menu.activeSealIndex() != -1) helper.fail("activeSealIndex should be -1");
                    if (menu.activeRewardIndex() != -1) helper.fail("activeRewardIndex should be -1");

                    // Probe each board slot: every click should be a no-op.
                    menu.setCarried(new ItemStack(MCPItems.CAPTAIN_SEAL.get()));
                    // Only stateful slots should be locked — the book slot (index 15)
                    // is intentionally always available, even on a retired sheriff.
                    for (int i = 0; i < SheriffMenu.STATEFUL_SLOTS; i++) {
                        ItemStack before = menu.getCarried().copy();
                        menu.clicked(i, 0, ClickType.PICKUP, player);
                        if (!ItemStack.matches(menu.getCarried(), before)) {
                            helper.fail("retired sheriff allowed click on slot " + i
                                    + " — carried changed from " + before + " to " + menu.getCarried());
                            return;
                        }
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100, setupTicks = 2,
              batch = "sheriff_menu_cycle_one_first_is_galleon")
    public static void sheriffMenuCycleLengthOneFirstMapIsGalleon(GameTestHelper helper) {
        Villager sheriff = spawnSheriff(helper);
        GameTestPlayer player = spawnPlayer(helper);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    SheriffMenu menu = openMenuWithLength(player, sheriff, 1);
                    menu.clicked(SheriffMenu.MAP_ROW_OFFSET + 0, 0, ClickType.PICKUP, player);
                    ItemStack carried = menu.getCarried();
                    if (!carried.is(MCPItems.FURLED_BOUNTY.get())) {
                        helper.fail("expected furled_bounty, got " + carried);
                        return;
                    }
                    if (!carried.has(MCPDataComponents.IS_GALLEON_BOUNTY.get())) {
                        helper.fail("cycleLength=1: first map must be galleon-stamped");
                    }
                    if (menu.activeMapIndex() != -1) {
                        helper.fail("after taking the only map, no map slot should be active");
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100, setupTicks = 2,
              batch = "sheriff_menu_cycle_one_retires_after_one")
    public static void sheriffMenuCycleLengthOneRetiresAfterOneCycle(GameTestHelper helper) {
        Villager sheriff = spawnSheriff(helper);
        GameTestPlayer player = spawnPlayer(helper);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    SheriffMenu menu = openMenuWithLength(player, sheriff, 1);
                    menu.clicked(SheriffMenu.MAP_ROW_OFFSET + 0, 0, ClickType.PICKUP, player);
                    menu.setCarried(new ItemStack(MCPItems.CAPTAIN_SEAL.get()));
                    menu.clicked(SheriffMenu.SEAL_ROW_OFFSET + 0, 0, ClickType.PICKUP, player);
                    menu.broadcastChanges();
                    if (!menu.isRetired()) {
                        helper.fail("cycleLength=1: sheriff should retire after first seal submitted");
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100, setupTicks = 2,
              batch = "sheriff_menu_cycle_three_galleon_at_two")
    public static void sheriffMenuCycleLengthThreeGalleonAtIndexTwo(GameTestHelper helper) {
        Villager sheriff = spawnSheriff(helper);
        GameTestPlayer player = spawnPlayer(helper);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    setCounter(sheriff, MCPDataKeys.SHERIFF_MAPS_CLAIMED_NBT_KEY, 2);
                    setCounter(sheriff, MCPDataKeys.SHERIFF_SEALS_RETURNED_NBT_KEY, 2);
                    SheriffMenu menu = openMenuWithLength(player, sheriff, 3);
                    menu.clicked(SheriffMenu.MAP_ROW_OFFSET + 2, 0, ClickType.PICKUP, player);
                    ItemStack carried = menu.getCarried();
                    if (!carried.has(MCPDataComponents.IS_GALLEON_BOUNTY.get())) {
                        helper.fail("cycleLength=3: map at index 2 must be galleon-stamped, got " + carried);
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100, setupTicks = 2,
              batch = "sheriff_menu_cycle_three_trailing_locked")
    public static void sheriffMenuCycleLengthThreeTrailingSlotsLocked(GameTestHelper helper) {
        Villager sheriff = spawnSheriff(helper);
        GameTestPlayer player = spawnPlayer(helper);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    SheriffMenu menu = openMenuWithLength(player, sheriff, 3);
                    // Walk through all three valid steps; trailing slots (3, 4) must never go active.
                    for (int step = 0; step < 3; step++) {
                        menu.clicked(SheriffMenu.MAP_ROW_OFFSET + step, 0, ClickType.PICKUP, player);
                        // Drop the map (don't care about it for this assertion).
                        menu.setCarried(new ItemStack(MCPItems.CAPTAIN_SEAL.get()));
                        menu.clicked(SheriffMenu.SEAL_ROW_OFFSET + step, 0, ClickType.PICKUP, player);
                        menu.broadcastChanges();
                        // Drop the reward.
                        menu.clicked(SheriffMenu.REWARD_ROW_OFFSET + step, 0, ClickType.PICKUP, player);
                        menu.setCarried(ItemStack.EMPTY);

                        if (menu.activeMapIndex() >= 3) {
                            helper.fail("step " + step + ": activeMapIndex slid into trailing slot "
                                    + menu.activeMapIndex());
                            return;
                        }
                    }
                    if (!menu.isRetired()) {
                        helper.fail("cycleLength=3: should retire after 3 cycles");
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100, setupTicks = 2,
              batch = "sheriff_menu_book_slot_always_available")
    public static void sheriffMenuBookSlotAlwaysGivesBook(GameTestHelper helper) {
        Villager sheriff = spawnSheriff(helper);
        GameTestPlayer player = spawnPlayer(helper);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    // Retire the sheriff so we also prove the book bypasses retirement.
                    setCounter(sheriff, MCPDataKeys.SHERIFF_MAPS_CLAIMED_NBT_KEY, 5);
                    setCounter(sheriff, MCPDataKeys.SHERIFF_SEALS_RETURNED_NBT_KEY, 5);
                    setCounter(sheriff, MCPDataKeys.SHERIFF_REWARDS_CLAIMED_NBT_KEY, 5);
                    SheriffMenu menu = openMenu(player, sheriff);
                    menu.clicked(SheriffMenu.BOOK_SLOT_INDEX, 0, ClickType.PICKUP, player);
                    ItemStack carried = menu.getCarried();
                    if (carried.isEmpty()) {
                        helper.fail("book slot should give a book even on a retired sheriff");
                        return;
                    }
                    var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(carried.getItem());
                    if (!"patchouli".equals(key.getNamespace())) {
                        helper.fail("expected a Patchouli book item, got " + key);
                    }
                })
                .thenSucceed();
    }

    // ----- helpers -----

    private static Villager spawnSheriff(GameTestHelper helper) {
        TestSetup.reset(helper);
        ServerLevel level = helper.getLevel();
        Villager v = new Villager(EntityType.VILLAGER, level);
        v.setVillagerData(new VillagerData(
                v.getVillagerData().getType(),
                MCPVillagerProfessions.SHERIFF.get(),
                2));
        v.setPersistenceRequired();
        BlockPos abs = helper.absolutePos(SHERIFF_SPAWN);
        v.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0f, 0f);
        level.addFreshEntity(v);
        return v;
    }

    private static GameTestPlayer spawnPlayer(GameTestHelper helper) {
        ExtendedGameTestHelper ext = TestSetup.extend(helper);
        BlockPos abs = helper.absolutePos(PLAYER_SPAWN);
        GameTestPlayer player = ext.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
        player.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
        player.setInvulnerable(true);
        return player;
    }

    private static SheriffMenu openMenu(GameTestPlayer player, Villager sheriff) {
        SimpleMenuProvider provider = new SimpleMenuProvider(
                (id, inv, p) -> new SheriffMenu(id, inv, sheriff),
                sheriff.getDisplayName());
        player.openMenu(provider, buf -> buf.writeVarInt(sheriff.getId()));
        return (SheriffMenu) player.containerMenu;
    }

    private static SheriffMenu openMenuWithLength(GameTestPlayer player, Villager sheriff, int cycleLength) {
        SimpleMenuProvider provider = new SimpleMenuProvider(
                (id, inv, p) -> new SheriffMenu(id, inv, sheriff, cycleLength),
                sheriff.getDisplayName());
        player.openMenu(provider, buf -> buf.writeVarInt(sheriff.getId()));
        return (SheriffMenu) player.containerMenu;
    }

    private static void setCounter(Villager sheriff, String key, int value) {
        sheriff.getPersistentData().putInt(key, value);
    }

    private static void assertCounter(GameTestHelper helper, Villager sheriff, String key, int expected) {
        int actual = sheriff.getPersistentData().getInt(key);
        if (actual != expected) {
            helper.fail(String.format(Locale.ROOT,
                    "%s: expected %d, got %d", key, expected, actual));
        }
    }
}
