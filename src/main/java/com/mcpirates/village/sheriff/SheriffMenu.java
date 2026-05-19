package com.mcpirates.village.sheriff;

import com.mcpirates.MCPConfig;
import com.mcpirates.MCPDataKeys;
import com.mcpirates.MCPirates;
import com.mcpirates.registry.MCPDataComponents;
import com.mcpirates.registry.MCPItems;
import com.mcpirates.registry.MCPMenuTypes;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
import vazkii.patchouli.api.PatchouliAPI;

import javax.annotation.Nullable;

/** Custom container menu replacing vanilla trade GUI on the sheriff villager. See
 *  {@code docs/decisions.md} (2026-05-19 entry) for the state machine, sync model,
 *  layout, and why we bypass {@code MerchantOffers} entirely. */
public final class SheriffMenu extends AbstractContainerMenu {

    public static final int ROW_LEN = 5;
    public static final int MAP_ROW_OFFSET = 0;
    public static final int SEAL_ROW_OFFSET = 5;
    public static final int REWARD_ROW_OFFSET = 10;
    public static final int STATEFUL_SLOTS = 15;
    public static final int BOOK_SLOT_INDEX = 15;
    public static final int BOARD_SIZE = 16;
    public static final int MAX_CYCLES = 5;
    public static final int REWARD_EMERALDS = 10;

    public static final ResourceLocation BOUNTY_HUNTER_GUIDE_ID =
            ResourceLocation.fromNamespaceAndPath(MCPirates.MOD_ID, "bounty_hunter_guide");

    private static final double STILL_VALID_DIST_SQ = 64.0D;

    private final Container board;
    @Nullable private final Villager sheriff;
    /** Menu owner — used to peek inventory for the "already has the book" check. */
    private final Player player;
    private final int cycleLength;

    // persistentData is server-only; DataSlots are the per-tick sync channel to client.
    private final DataSlot mapsCounter = DataSlot.standalone();
    private final DataSlot sealsCounter = DataSlot.standalone();
    private final DataSlot rewardsCounter = DataSlot.standalone();

    public SheriffMenu(int windowId, Inventory playerInv, @Nullable Villager sheriff) {
        this(windowId, playerInv, sheriff, MCPConfig.cycleLength());
    }

    /** Test entry-point: pins the cycle length explicitly instead of reading the config. */
    public SheriffMenu(int windowId, Inventory playerInv, @Nullable Villager sheriff, int explicitCycleLength) {
        super(MCPMenuTypes.SHERIFF.get(), windowId);
        this.sheriff = sheriff;
        this.player = playerInv.player;
        this.board = new SimpleContainer(BOARD_SIZE);
        this.cycleLength = Mth.clamp(explicitCycleLength, 1, MAX_CYCLES);

        for (int col = 0; col < ROW_LEN; col++) {
            addSlot(new MapSlot(board, MAP_ROW_OFFSET + col, 64 + col * 18, 18));
        }
        for (int col = 0; col < ROW_LEN; col++) {
            addSlot(new SealSlot(board, SEAL_ROW_OFFSET + col, 64 + col * 18, 40));
        }
        for (int col = 0; col < ROW_LEN; col++) {
            addSlot(new RewardSlot(board, REWARD_ROW_OFFSET + col, 64 + col * 18, 62));
        }
        addSlot(new BookSlot(board, BOOK_SLOT_INDEX, 160, 18));

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

    /** Client-side ctor invoked by {@code IMenuTypeExtension.create}. */
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

    private void setMapsClaimed(int v)   { writeCounter(mapsCounter,   MCPDataKeys.SHERIFF_MAPS_CLAIMED_NBT_KEY, v); }
    private void setSealsReturned(int v) { writeCounter(sealsCounter,  MCPDataKeys.SHERIFF_SEALS_RETURNED_NBT_KEY, v); }
    private void setRewardsClaimed(int v){ writeCounter(rewardsCounter,MCPDataKeys.SHERIFF_REWARDS_CLAIMED_NBT_KEY, v); }

    /** Write-through to DataSlot (syncs to client) AND villager NBT (persists across menu close). */
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

    private ItemStack mintBook() {
        return PatchouliAPI.get().getBookStack(BOUNTY_HUNTER_GUIDE_ID);
    }

    /** Sheriff plays the affirm sound + emits happy-villager particles when a seal lands. */
    private void playSealAcceptedFeedback() {
        if (sheriff == null || !sheriff.isAlive()) return;
        if (!(sheriff.level() instanceof ServerLevel sl)) return;
        sl.playSound(null, sheriff.blockPosition(),
                SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 1.0f, 1.0f);
        sl.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                sheriff.getX(), sheriff.getEyeY(), sheriff.getZ(),
                /*count=*/8, /*xSpread=*/0.4, /*ySpread=*/0.4, /*zSpread=*/0.4, /*speed=*/0.0);
    }

    private void refreshBoard() {
        int activeMap = activeMapIndex();
        int activeReward = activeRewardIndex();
        for (int i = 0; i < ROW_LEN; i++) {
            board.setItem(MAP_ROW_OFFSET + i,
                    i == activeMap ? mintFurledBounty(i) : ItemStack.EMPTY);
            board.setItem(REWARD_ROW_OFFSET + i,
                    i == activeReward ? mintReward() : ItemStack.EMPTY);
        }
        // Skip the dispenser if the player is already holding a guide (in inv or cursor).
        board.setItem(BOOK_SLOT_INDEX, playerHasGuideBook() ? ItemStack.EMPTY : mintBook());
    }

    /** True if the menu's owning player already carries a {@code bounty_hunter_guide}
     *  book either in their inventory or in the cursor (carried) slot. */
    private boolean playerHasGuideBook() {
        if (player == null) return false;
        ItemStack reference = mintBook();
        if (reference.isEmpty()) return false;
        if (ItemStack.isSameItemSameComponents(getCarried(), reference)) return true;
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (ItemStack.isSameItemSameComponents(inv.getItem(i), reference)) return true;
        }
        return false;
    }

    @Override
    public void broadcastChanges() {
        // Pin the sheriff while the menu is open; vanilla brain hooks aren't wired.
        if (sheriff != null && sheriff.isAlive()) {
            sheriff.getNavigation().stop();
            sheriff.setDeltaMovement(0.0, sheriff.getDeltaMovement().y, 0.0);
            sheriff.xxa = 0;
            sheriff.yya = 0;
            sheriff.zza = 0;
        }
        // Consume any captain_seal sitting in the active seal slot.
        int activeSeal = activeSealIndex();
        if (activeSeal != -1) {
            ItemStack here = board.getItem(SEAL_ROW_OFFSET + activeSeal);
            if (here.is(MCPItems.CAPTAIN_SEAL.get())) {
                here.shrink(1);
                board.setItem(SEAL_ROW_OFFSET + activeSeal, here.isEmpty() ? ItemStack.EMPTY : here);
                setSealsReturned(sealsReturned() + 1);
                playSealAcceptedFeedback();
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

    /** Return parked seals to the player; clear minted virtual stacks so they can't be cloned. */
    @Override
    public void removed(Player player) {
        super.removed(player);
        for (int i = 0; i < ROW_LEN; i++) {
            ItemStack seal = board.getItem(SEAL_ROW_OFFSET + i);
            if (!seal.isEmpty()) {
                if (!player.getInventory().add(seal)) {
                    player.drop(seal, false);
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

    /** Unlimited dispenser for the Patchouli guide; re-mints on every refresh. */
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
