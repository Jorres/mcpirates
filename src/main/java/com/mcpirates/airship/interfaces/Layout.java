package com.mcpirates.airship.interfaces;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Resolved positions for an assembled airship, relative to a primary-lever reference. The
 * reference can be in either world frame (pre-assembly, returned by the kind given the
 * world lever position) or SubLevel frame (post-assembly, given the SL-plot primary
 * anchor) — the layout is frame-agnostic because every delta is just an offset from the
 * reference.
 *
 * <p>{@code glueMin}/{@code glueMax} are inclusive BlockPos corners; the trigger inflates
 * the {@code max} side by +1 when building an {@link net.minecraft.world.phys.AABB} so the
 * box covers inclusive block coords.
 */
public record Layout(
        List<BlockPos> engines,
        List<BlockPos> throttleLevers,
        BlockPos leftClutch,
        BlockPos rightClutch,
        List<BlockPos> cannonMounts,
        BlockPos glueMin,
        BlockPos glueMax
) {}
