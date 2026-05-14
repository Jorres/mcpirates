package com.mcpirates.airship.anchor;

import com.mcpirates.registry.MCPBlockEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One-field block entity for {@link MCPShipAnchorBlock}. Stores the
 * {@code mcpirates:airship} kind name (e.g., {@code "airship_small"},
 * {@code "crossbow_board"}, {@code "galleon"}) baked into the ship's structure
 * NBT by {@code tools/build_ships.py}.
 *
 * <p>This BE exists solely so {@link com.mcpirates.airship.AirshipLiftoffTrigger}
 * can identify "what kind of ship this is" by scanning chunk BE lists for a
 * single, mod-namespaced BE class — no geometric heuristics, no per-kind
 * matches() predicates that have to discriminate between similar levers.
 *
 * <p>One anchor per ship NBT, at an arbitrary fixed position inside the hull
 * (see SHIPS config in build_ships.py for per-ship coordinates). The anchor
 * is invisible (no collision, no model, unbreakable), so it doesn't affect
 * gameplay — it's purely metadata.
 */
public final class MCPShipAnchorBlockEntity extends BlockEntity {

    private static final String KIND_TAG = "kind";

    /** AirshipKind name resolved at trigger time via
     *  {@link com.mcpirates.airship.kind.AirshipKinds#byName}. */
    private String kindName = "";

    public MCPShipAnchorBlockEntity(BlockPos pos, BlockState state) {
        super(MCPBlockEntityTypes.SHIP_ANCHOR.get(), pos, state);
    }

    public String getKindName() { return kindName; }

    public void setKindName(String name) {
        this.kindName = name == null ? "" : name;
        setChanged();
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.kindName = tag.getString(KIND_TAG);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString(KIND_TAG, this.kindName);
    }
}
