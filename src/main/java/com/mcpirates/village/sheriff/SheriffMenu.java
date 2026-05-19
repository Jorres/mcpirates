package com.mcpirates.village.sheriff;

import com.mcpirates.MCPConfig;
import com.mcpirates.MCPDataKeys;
import com.mcpirates.MCPirates;
import com.mcpirates.registry.MCPDataComponents;
import net.minecraft.nbt.CompoundTag;
import com.mcpirates.registry.MCPItems;
import com.mcpirates.registry.MCPMenuTypes;
import vazkii.patchouli.api.PatchouliAPI;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.util.Unit;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;

/**
 * State-driven board menu replacing vanilla trade GUI on the sheriff villager.
 *
 * <p>Three logical rows of 5 slots each backed by one {@link SimpleContainer}:
 * <pre>
 *   row 0 (slots  0..4)  — maps    : 1 furled_bounty appears at slot {@code mapsClaimed} when active
 *   row 1 (slots  5..9)  — seals   : empty receptacle at slot {@code sealsReturned} accepts a captain_seal
 *   row 2 (slots 10..14) — rewards : 10× emerald at slot {@code rewardsClaimed} when active
 * </pre>
 *
 * <p>State lives on the villager's persistent NBT under three counters in
 * {@link MCPDataKeys}. Invariant: {@code seals <= maps <= seals + 1} and {@code rewards <= seals},
 * all in {@code [0, 5]}. Sheriff is "retired" when {@code sealsReturned == 5} —
 * every slot becomes inactive forever.
 *
 * <p>Seal consumption is processed in {@link #broadcastChanges()}: any captain_seal sitting
 * in the active seal slot is shrunk by one and {@code sealsReturned} bumped, then the board
 * is refreshed from state. Map / reward pickups go through {@code onTake} on the slot.
 *
 * <p>The last map in the cycle (index {@code cycleLength - 1}) is minted with the
 * {@link MCPDataComponents#IS_GALLEON_BOUNTY} data component; {@link com.mcpirates.village.FurledBountyItem}
 * reads the flag and forces the galleon path on unfurl. Cycle length is read from
 * {@link MCPConfig#cycleLength()} at menu-construction time — config changes take
 * effect the next time the GUI is opened.
 */
public final class SheriffMenu extends AbstractContainerMenu {

    public static final int ROW_LEN = 5;
    public static final int MAP_ROW_OFFSET = 0;
    public static final int SEAL_ROW_OFFSET = 5;
    public static final int REWARD_ROW_OFFSET = 10;
    /** Stateful slots: 3 rows × 5 = 15 slots driven by the cycle counters. */
    public static final int STATEFUL_SLOTS = 15;
    /** Index of the always-available "Guide for the Bounty Hunters" book slot. */
    public static final int BOOK_SLOT_INDEX = 15;
    /** Total board slots = stateful + 1 book slot. */
    public static final int BOARD_SIZE = 16;
    /** Hard ceiling for the cycle-length config. Slots beyond this are never used. */
    public static final int MAX_CYCLES = 5;
    public static final int REWARD_EMERALDS = 10;

    /** Patchouli book identifier — matches the path {@code data/mcpirates/patchouli_books/bounty_hunter_guide/}. */
    public static final net.minecraft.resources.ResourceLocation BOUNTY_HUNTER_GUIDE_ID =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MCPirates.MOD_ID, "bounty_hunter_guide");

    /** Square-distance ceiling enforced in {@link #stillValid}. Matches villager
     *  trade-screen behavior — vanilla uses 64. */
    private static final double STILL_VALID_DIST_SQ = 64.0D;

    private final Container board;
    @Nullable private final Villager sheriff;
    /** Snapshot of {@link MCPConfig#cycleLength()} at construction so config changes
     *  mid-session don't desync between server and client menu instances. */
    private final int cycleLength;

    /** Vanilla {@link DataSlot}s are the only built-in server→client int-sync mechanism
     *  on {@link AbstractContainerMenu} — {@code persistentData} is server-only. Each
     *  counter lives in its own slot, written through to the villager's NBT on the
     *  server, and pulled from the slot on either side when rendering. */
    private final DataSlot mapsCounter = DataSlot.standalone();
    private final DataSlot sealsCounter = DataSlot.standalone();
    private final DataSlot rewardsCounter = DataSlot.standalone();

    public SheriffMenu(int windowId, Inventory playerInv, @Nullable Villager sheriff) {
        this(windowId, playerInv, sheriff, MCPConfig.cycleLength());
    }

    /** Test-friendly ctor that pins the cycle length explicitly instead of reading
     *  the config. Production code paths always go through the 3-arg ctor. */
    public SheriffMenu(int windowId, Inventory playerInv, @Nullable Villager sheriff, int explicitCycleLength) {
        super(MCPMenuTypes.SHERIFF.get(), windowId);
        this.sheriff = sheriff;
        this.board = new SimpleContainer(BOARD_SIZE);
        this.cycleLength = Mth.clamp(explicitCycleLength, 1, MAX_CYCLES);

        // Board slots are shifted right by 20px vs hopper-style layout to leave room
        // for the row labels ("Maps" / "Seals" / "Rewards") in the GUI's left margin.
        for (int col = 0; col < ROW_LEN; col++) {
            addSlot(new MapSlot(board, MAP_ROW_OFFSET + col, 64 + col * 18, 18));
        }
        for (int col = 0; col < ROW_LEN; col++) {
            addSlot(new SealSlot(board, SEAL_ROW_OFFSET + col, 64 + col * 18, 40));
        }
        for (int col = 0; col < ROW_LEN; col++) {
            addSlot(new RewardSlot(board, REWARD_ROW_OFFSET + col, 64 + col * 18, 62));
        }

        // Book slot — top-right corner, always-available, non-craftable Patchouli book.
        addSlot(new BookSlot(board, BOOK_SLOT_INDEX, 160, 18));

        // Player inv centered inside the 196-px-wide GUI: (196 - 162) / 2 = 17.
        // Reward row ends at y=78; main inv starts at y=98 for a 20-px gap.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, 17 + col * 18, 98 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, 17 + col * 18, 158));
        }

        addDataSlot(mapsCounter);
        addDataSlot(sealsCounter);
        addDataSlot(rewardsCounter);
        if (sheriff != null) {
            CompoundTag data = sheriff.getPersistentData();
            mapsCounter.set(data.getInt(MCPDataKeys.SHERIFF_MAPS_CLAIMED_NBT_KEY));
            sealsCounter.set(data.getInt(MCPDataKeys.SHERIFF_SEALS_RETURNED_NBT_KEY));
            rewardsCounter.set(data.getInt(MCPDataKeys.SHERIFF_REWARDS_CLAIMED_NBT_KEY));
        }

        refreshBoard();
    }

    /** Client-side reconstructor invoked by {@code IMenuTypeExtension.create}. Resolves
     *  the sheriff via entity id; if the entity isn't on the client yet, the menu still
     *  opens but every slot stays inactive (sheriff == null). */
    public static SheriffMenu fromNetwork(int windowId, Inventory playerInv, RegistryFriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        Entity entity = playerInv.player.level().getEntity(entityId);
        Villager sheriff = (entity instanceof Villager v) ? v : null;
        return new SheriffMenu(windowId, playerInv, sheriff);
    }

    public int mapsClaimed() { return Mth.clamp(mapsCounter.get(), 0, cycleLength); }
    public int sealsReturned() { return Mth.clamp(sealsCounter.get(), 0, cycleLength); }
    public int rewardsClaimed() { return Mth.clamp(rewardsCounter.get(), 0, cycleLength); }
    public int cycleLength() { return cycleLength; }

    private void setMapsClaimed(int value) { writeCounter(mapsCounter, MCPDataKeys.SHERIFF_MAPS_CLAIMED_NBT_KEY, value); }
    private void setSealsReturned(int value) { writeCounter(sealsCounter, MCPDataKeys.SHERIFF_SEALS_RETURNED_NBT_KEY, value); }
    private void setRewardsClaimed(int value) { writeCounter(rewardsCounter, MCPDataKeys.SHERIFF_REWARDS_CLAIMED_NBT_KEY, value); }

    /** Write-through: update the DataSlot (auto-syncs to client) AND the villager NBT
     *  (survives menu close + entity save). Both writes are server-side only — calling
     *  this on the client is a no-op via the null check, since client menus don't have
     *  the live entity reference's persistentData populated. */
    private void writeCounter(DataSlot slot, String key, int value) {
        int clamped = Mth.clamp(value, 0, cycleLength);
        slot.set(clamped);
        if (sheriff != null) {
            sheriff.getPersistentData().putInt(key, clamped);
        }
    }

    public int activeMapIndex() {
        int m = mapsClaimed();
        return (m < cycleLength && m == sealsReturned()) ? m : -1;
    }

    public int activeSealIndex() {
        int s = sealsReturned();
        return (s < cycleLength && mapsClaimed() > s) ? s : -1;
    }

    public int activeRewardIndex() {
        int r = rewardsClaimed();
        return (r < sealsReturned()) ? r : -1;
    }

    public boolean isRetired() {
        return sealsReturned() >= cycleLength;
    }

    private ItemStack mintFurledBounty(int mapIndex) {
        ItemStack stack = new ItemStack(MCPItems.FURLED_BOUNTY.get(), 1);
        if (mapIndex == cycleLength - 1) {
            stack.set(MCPDataComponents.IS_GALLEON_BOUNTY.get(), Unit.INSTANCE);
        }
        return stack;
    }

    private ItemStack mintReward() {
        return new ItemStack(Items.EMERALD, REWARD_EMERALDS);
    }

    /** Fresh "Guide for the Bounty Hunters" Patchouli book. Resolved via the API; if
     *  Patchouli isn't loaded the API returns a stub which yields an empty stack. */
    private ItemStack mintBook() {
        return PatchouliAPI.get().getBookStack(BOUNTY_HUNTER_GUIDE_ID);
    }

    /** Sync the board container to current state. Idempotent; safe to call repeatedly.
     *  Inactive slots are forced empty so leftover virtual items can't be re-picked. */
    private void refreshBoard() {
        int activeMap = activeMapIndex();
        int activeReward = activeRewardIndex();
        for (int i = 0; i < ROW_LEN; i++) {
            board.setItem(MAP_ROW_OFFSET + i,
                    i == activeMap ? mintFurledBounty(i) : ItemStack.EMPTY);
            board.setItem(REWARD_ROW_OFFSET + i,
                    i == activeReward ? mintReward() : ItemStack.EMPTY);
            // Seal slots stay as whatever the player placed; only the active one
            // accepts new placements (via SealSlot.mayPlace).
        }
        // Book slot is always available — re-mint each tick so taking one immediately
        // replaces it with another (unlimited dispenser).
        board.setItem(BOOK_SLOT_INDEX, mintBook());
    }

    /** Server-tick path: pull any captain_seal sitting in the active seal slot,
     *  bump the counter, then re-sync. Vanilla calls this once per server tick
     *  per open menu. Visible to tests too — call directly to force progression. */
    @Override
    public void broadcastChanges() {
        // Pin the sheriff while the menu is open — vanilla trade GUI gates wandering via
        // brain hooks we don't have wired, so we clear navigation + horizontal momentum
        // every server tick instead. Y-component of delta preserved so gravity still works.
        if (sheriff != null && sheriff.isAlive()) {
            sheriff.getNavigation().stop();
            sheriff.setDeltaMovement(0.0, sheriff.getDeltaMovement().y, 0.0);
            sheriff.xxa = 0;
            sheriff.yya = 0;
            sheriff.zza = 0;
        }

        int activeSeal = activeSealIndex();
        if (activeSeal != -1) {
            ItemStack here = board.getItem(SEAL_ROW_OFFSET + activeSeal);
            if (here.is(MCPItems.CAPTAIN_SEAL.get())) {
                here.shrink(1);
                board.setItem(SEAL_ROW_OFFSET + activeSeal, here.isEmpty() ? ItemStack.EMPTY : here);
                setSealsReturned(sealsReturned() + 1);
            }
        }
        refreshBoard();
        super.broadcastChanges();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        ItemStack source = slot.getItem();
        ItemStack original = source.copy();

        if (index < BOARD_SIZE) {
            if (!slot.mayPickup(player)) return ItemStack.EMPTY;
            if (!moveItemStackTo(source, BOARD_SIZE, BOARD_SIZE + 36, true)) return ItemStack.EMPTY;
            slot.onTake(player, original);
        } else {
            int activeSeal = activeSealIndex();
            if (activeSeal == -1 || !source.is(MCPItems.CAPTAIN_SEAL.get())) return ItemStack.EMPTY;
            int dst = SEAL_ROW_OFFSET + activeSeal;
            if (!moveItemStackTo(source, dst, dst + 1, false)) return ItemStack.EMPTY;
        }

        if (source.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        if (sheriff == null) return false;
        if (!sheriff.isAlive()) return false;
        return sheriff.distanceToSqr(player) <= STILL_VALID_DIST_SQ;
    }

    /** Return any seal items the player parked in the seal row; clear minted (virtual)
     *  map / reward / book stacks so they can't be cloned by closing the GUI mid-action. */
    @Override
    public void removed(Player player) {
        super.removed(player);
        for (int i = 0; i < ROW_LEN; i++) {
            ItemStack seal = board.getItem(SEAL_ROW_OFFSET + i);
            if (!seal.isEmpty()) {
                if (!player.getInventory().add(seal)) {
                    player.drop(seal, /*includeThrowerName=*/false);
                }
                board.setItem(SEAL_ROW_OFFSET + i, ItemStack.EMPTY);
            }
            board.setItem(MAP_ROW_OFFSET + i, ItemStack.EMPTY);
            board.setItem(REWARD_ROW_OFFSET + i, ItemStack.EMPTY);
        }
        board.setItem(BOOK_SLOT_INDEX, ItemStack.EMPTY);
    }

    @Nullable
    public Villager sheriff() { return sheriff; }

    private final class MapSlot extends Slot {
        MapSlot(Container container, int index, int x, int y) { super(container, index, x, y); }
        int rowIndex() { return getContainerSlot() - MAP_ROW_OFFSET; }

        @Override public boolean mayPickup(Player p) { return rowIndex() == activeMapIndex(); }
        @Override public boolean mayPlace(ItemStack s) { return false; }

        @Override
        public void onTake(Player p, ItemStack taken) {
            if (rowIndex() == activeMapIndex()) {
                setMapsClaimed(mapsClaimed() + 1);
            }
            super.onTake(p, taken);
            refreshBoard();
        }
    }

    private final class SealSlot extends Slot {
        SealSlot(Container container, int index, int x, int y) { super(container, index, x, y); }
        int rowIndex() { return getContainerSlot() - SEAL_ROW_OFFSET; }

        @Override
        public boolean mayPlace(ItemStack s) {
            return s.is(MCPItems.CAPTAIN_SEAL.get()) && rowIndex() == activeSealIndex();
        }
        @Override public int getMaxStackSize() { return 1; }
        @Override public int getMaxStackSize(ItemStack stack) { return 1; }
    }

    private final class RewardSlot extends Slot {
        RewardSlot(Container container, int index, int x, int y) { super(container, index, x, y); }
        int rowIndex() { return getContainerSlot() - REWARD_ROW_OFFSET; }

        @Override public boolean mayPickup(Player p) { return rowIndex() == activeRewardIndex(); }
        @Override public boolean mayPlace(ItemStack s) { return false; }

        @Override
        public void onTake(Player p, ItemStack taken) {
            if (rowIndex() == activeRewardIndex()) {
                setRewardsClaimed(rewardsClaimed() + 1);
            }
            super.onTake(p, taken);
            refreshBoard();
        }
    }

    /** Always-available "Guide for the Bounty Hunters" Patchouli book. Pickup is
     *  unrestricted; the slot re-mints on the next refresh, so it works as an unlimited
     *  dispenser (player can grab a fresh copy any time they need one). */
    private final class BookSlot extends Slot {
        BookSlot(Container container, int index, int x, int y) { super(container, index, x, y); }

        @Override public boolean mayPickup(Player p) { return true; }
        @Override public boolean mayPlace(ItemStack s) { return false; }

        @Override
        public void onTake(Player p, ItemStack taken) {
            super.onTake(p, taken);
            refreshBoard();
        }
    }
}
