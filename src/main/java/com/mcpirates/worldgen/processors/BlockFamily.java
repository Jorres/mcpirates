package com.mcpirates.worldgen.processors;

import net.minecraft.resources.ResourceLocation;

import java.util.Comparator;
import java.util.Optional;

/**
 * A pluggable "set of block variants we know how to recolor/reskin." Each family
 * answers three questions for {@link VariantSwapProcessor}:
 *
 * <ul>
 *   <li>"Is this block part of my family, and if so what is its variant key?"
 *       ({@link #extractVariant}) — e.g. {@code aeronautics:red_envelope} → {@code "red"}.</li>
 *   <li>"Given a block that is in my family, swap it to a different variant."
 *       ({@link #rebuild}) — e.g. {@code (aeronautics:red_envelope, "gray")} → {@code aeronautics:gray_envelope}.</li>
 *   <li>"In what order should detected variants and pool entries be sorted so positional
 *       mapping is stable?" ({@link #canonicalOrder}) — used to break ties when two
 *       variants have the same block count in the NBT.</li>
 * </ul>
 *
 * <p>Families also declare a {@link #key() name} used as the family identifier in the
 * processor's JSON config.
 */
public interface BlockFamily {

    String key();

    Optional<String> extractVariant(ResourceLocation blockId);

    ResourceLocation rebuild(ResourceLocation original, String variant);

    Comparator<String> canonicalOrder();

    /** Optional validation hook: throw IllegalArgumentException if {@code variant} is unknown
     *  to this family. Called once per pool-entry-variant at codec decode. */
    void validateVariant(String variant);
}
