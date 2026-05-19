package com.mcpirates.client.sheriff;

import com.mcpirates.MCPirates;
import com.mcpirates.registry.MCPItems;
import com.mcpirates.village.sheriff.SheriffMenu;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Visual for {@link SheriffMenu}. Background image lives at
 * {@code textures/gui/sheriff.png} (176×168 within a 256×256 canvas).
 * Inactive board slots get a 16×16 translucent dark overlay drawn in
 * {@link #renderBg} — vanilla slot rendering already happens via the parent.
 */
public final class SheriffScreen extends AbstractContainerScreen<SheriffMenu> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MCPirates.MOD_ID, "textures/gui/sheriff.png");

    /** ARGB overlay for slots not yet reachable (locked-future state). */
    private static final int LOCKED_OVERLAY_COLOR = 0x80303030;
    /** ARGB overlay for slots already consumed (TAKEN state) — same dim level plus the X. */
    private static final int TAKEN_OVERLAY_COLOR = 0x80303030;
    /** ARGB colour for the crossed-out X drawn across TAKEN slots. */
    private static final int TAKEN_CROSS_COLOR = 0xFFC04030;
    /** ARGB colour for the 1-px frame around the active slot in each row. */
    private static final int ACTIVE_FRAME_COLOR = 0xFFFFDA48;

    private static final int RETIRED_BANNER_COLOR = 0xFFAA1010;
    /** Vanilla "Inventory" label colour. */
    private static final int LABEL_COLOR = 0xFF404040;
    /** Dim grey for the "?" placeholder on locked reward slots. */
    private static final int LOCKED_HINT_COLOR = 0xFF707070;
    /** Alpha multiplier for the ghost captain-seal hint in seal slots. */
    private static final float GHOST_ALPHA = 0.35f;

    private enum SlotState { ACTIVE, TAKEN, LOCKED }

    public SheriffScreen(SheriffMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 196;
        this.imageHeight = 182;
        // Player inv labels follow the player-inv slot grid (now at x=17).
        this.inventoryLabelX = 17;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        g.blit(TEXTURE, x, y, 0, 0, this.imageWidth, this.imageHeight);

        int maps = menu.mapsClaimed();
        int seals = menu.sealsReturned();
        int rewards = menu.rewardsClaimed();
        int activeMap = menu.activeMapIndex();
        int activeSeal = menu.activeSealIndex();
        int activeReward = menu.activeRewardIndex();

        for (int i = 0; i < SheriffMenu.ROW_LEN; i++) {
            SlotState mapState = classify(i, maps, activeMap);
            SlotState sealState = classify(i, seals, activeSeal);
            SlotState rewardState = classify(i, rewards, activeReward);

            paintSlot(g, x, y, SheriffMenu.MAP_ROW_OFFSET + i, mapState);
            paintSlot(g, x, y, SheriffMenu.SEAL_ROW_OFFSET + i, sealState);
            paintSlot(g, x, y, SheriffMenu.REWARD_ROW_OFFSET + i, rewardState);

            // Non-taken seal slots show a faded captain-seal hint of what goes in.
            if (sealState != SlotState.TAKEN) {
                paintSealGhost(g, x, y, SheriffMenu.SEAL_ROW_OFFSET + i);
            }
            // Locked future rewards show "?" so the player knows there'll be a payout.
            if (rewardState == SlotState.LOCKED) {
                paintRewardQuestionMark(g, x, y, SheriffMenu.REWARD_ROW_OFFSET + i);
            }
        }
    }

    private void paintSealGhost(GuiGraphics g, int leftPos, int topPos, int slotIndex) {
        var slot = menu.slots.get(slotIndex);
        int sx = leftPos + slot.x;
        int sy = topPos + slot.y;
        RenderSystem.enableBlend();
        g.setColor(1.0f, 1.0f, 1.0f, GHOST_ALPHA);
        g.renderFakeItem(new ItemStack(MCPItems.CAPTAIN_SEAL.get()), sx, sy);
        g.setColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
    }

    private void paintRewardQuestionMark(GuiGraphics g, int leftPos, int topPos, int slotIndex) {
        var slot = menu.slots.get(slotIndex);
        // 16x16 slot — "?" is ~4 px wide, 8 tall. Center it.
        int cx = leftPos + slot.x + (16 - this.font.width("?")) / 2;
        int cy = topPos + slot.y + 4;
        g.drawString(this.font, "?", cx, cy, LOCKED_HINT_COLOR, false);
    }

    private static SlotState classify(int rowIndex, int consumed, int active) {
        if (rowIndex == active) return SlotState.ACTIVE;
        if (rowIndex < consumed) return SlotState.TAKEN;
        return SlotState.LOCKED;
    }

    private void paintSlot(GuiGraphics g, int leftPos, int topPos, int slotIndex, SlotState state) {
        var slot = menu.slots.get(slotIndex);
        int x0 = leftPos + slot.x;
        int y0 = topPos + slot.y;
        int x1 = x0 + 16;
        int y1 = y0 + 16;
        switch (state) {
            case ACTIVE -> {
                // 1-px gold frame on top of the slot well, inside the 16x16 area.
                g.fill(x0,     y0,     x1,     y0 + 1, ACTIVE_FRAME_COLOR);
                g.fill(x0,     y1 - 1, x1,     y1,     ACTIVE_FRAME_COLOR);
                g.fill(x0,     y0,     x0 + 1, y1,     ACTIVE_FRAME_COLOR);
                g.fill(x1 - 1, y0,     x1,     y1,     ACTIVE_FRAME_COLOR);
            }
            case TAKEN -> {
                g.fill(x0, y0, x1, y1, TAKEN_OVERLAY_COLOR);
                drawCross(g, x0, y0);
            }
            case LOCKED -> g.fill(x0, y0, x1, y1, LOCKED_OVERLAY_COLOR);
        }
    }

    /** Two diagonal lines across a 16x16 cell anchored at (x0, y0). Drawn as a chain of
     *  1-px filled rectangles so each diagonal renders as a stair-step line. */
    private void drawCross(GuiGraphics g, int x0, int y0) {
        for (int i = 0; i < 16; i++) {
            g.fill(x0 + i,     y0 + i,     x0 + i + 1,     y0 + i + 1,     TAKEN_CROSS_COLOR);
            g.fill(x0 + 15 - i, y0 + i,     x0 + 16 - i,    y0 + i + 1,     TAKEN_CROSS_COLOR);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (menu.isRetired()) {
            int x = (this.width - this.imageWidth) / 2;
            int y = (this.height - this.imageHeight) / 2;
            String label = Component.translatable("gui.mcpirates.sheriff.retired").getString();
            int w = this.font.width(label);
            g.drawString(this.font, label,
                    x + (this.imageWidth - w) / 2, y + 4, RETIRED_BANNER_COLOR, false);
        }
        this.renderTooltip(g, mouseX, mouseY);
    }

    /** Renders the title + inventory labels (vanilla behavior) plus our row labels.
     *  These are drawn in the menu's local coordinate space (origin = leftPos/topPos),
     *  so coordinates are within the 176×{@link #imageHeight} GUI area. */
    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY);
        drawRowLabel(g, "gui.mcpirates.sheriff.row.maps", 22);
        drawRowLabel(g, "gui.mcpirates.sheriff.row.seals", 44);
        drawRowLabel(g, "gui.mcpirates.sheriff.row.rewards", 66);
        // "Guide" caption above the always-available book slot at (160, 18).
        String guide = Component.translatable("gui.mcpirates.sheriff.row.guide").getString();
        int guideWidth = this.font.width(guide);
        g.drawString(this.font, guide, 168 - guideWidth / 2, 7, LABEL_COLOR, false);
    }

    private void drawRowLabel(GuiGraphics g, String translationKey, int y) {
        String text = Component.translatable(translationKey).getString();
        // Right-align so labels of different widths share a clean column edge just
        // before the first slot (slot 0 inner edge starts at GUI-local x=64).
        int rightEdge = 62;
        int x = rightEdge - this.font.width(text);
        g.drawString(this.font, text, x, y, LABEL_COLOR, false);
    }
}
