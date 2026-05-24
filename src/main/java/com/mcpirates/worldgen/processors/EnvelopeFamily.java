package com.mcpirates.worldgen.processors;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;

import java.util.Comparator;
import java.util.Optional;

/**
 * Family for {@code aeronautics:<color>_envelope} blocks. Variant key = the dye color name
 * ({@code "red"}, {@code "light_gray"}, ...). Canonical ordering = {@link DyeColor#getId()}.
 */
public final class EnvelopeFamily implements BlockFamily {

    public static final EnvelopeFamily INSTANCE = new EnvelopeFamily();

    private static final String NAMESPACE = "aeronautics";
    private static final String SUFFIX = "_envelope";

    private EnvelopeFamily() {}

    @Override
    public String key() {
        return "envelope";
    }

    @Override
    public Optional<String> extractVariant(ResourceLocation blockId) {
        if (!NAMESPACE.equals(blockId.getNamespace())) return Optional.empty();
        String path = blockId.getPath();
        if (!path.endsWith(SUFFIX)) return Optional.empty();
        String variant = path.substring(0, path.length() - SUFFIX.length());
        if (variant.isEmpty()) return Optional.empty();
        return Optional.of(variant);
    }

    @Override
    public ResourceLocation rebuild(ResourceLocation original, String variant) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, variant + SUFFIX);
    }

    @Override
    public Comparator<String> canonicalOrder() {
        return Comparator.comparingInt(EnvelopeFamily::dyeId);
    }

    @Override
    public void validateVariant(String variant) {
        DyeColor color = DyeColor.byName(variant, null);
        if (color == null) {
            throw new IllegalArgumentException("envelope: unknown color '" + variant
                    + "' (expected a vanilla DyeColor name like 'red', 'light_gray')");
        }
    }

    private static int dyeId(String variant) {
        DyeColor color = DyeColor.byName(variant, null);
        return color == null ? Integer.MAX_VALUE : color.getId();
    }
}
