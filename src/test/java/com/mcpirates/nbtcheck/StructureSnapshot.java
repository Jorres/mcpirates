package com.mcpirates.nbtcheck;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Palette-order-independent snapshot of an NBT structure file. Two snapshots
 * are equal iff they place the same block (by resolved palette entry + BE NBT)
 * at every position, regardless of how the palette indices happen to be ordered.
 *
 * Used by {@link DevSaveSyncTest} to compare a dev-save under {@code runs/}
 * against its declared source-of-truth file.
 */
final class StructureSnapshot {

    final int sx, sy, sz;
    final int dataVersion;
    final List<String> canonicalBlocks; // sorted; "pos|Name|Properties|BE"
    final List<String> canonicalEntities;

    private StructureSnapshot(int sx, int sy, int sz, int dataVersion,
                              List<String> blocks, List<String> entities) {
        this.sx = sx; this.sy = sy; this.sz = sz;
        this.dataVersion = dataVersion;
        this.canonicalBlocks = blocks;
        this.canonicalEntities = entities;
    }

    @SuppressWarnings("unchecked")
    static StructureSnapshot of(Path file) throws IOException {
        Map<String, Object> root = NbtReader.read(file);
        List<Object> size = (List<Object>) root.get("size");
        int sx = ((Number) size.get(0)).intValue();
        int sy = ((Number) size.get(1)).intValue();
        int sz = ((Number) size.get(2)).intValue();
        int dv = root.get("DataVersion") instanceof Number n ? n.intValue() : -1;

        List<Map<String, Object>> palette = (List<Map<String, Object>>) root.getOrDefault("palette", List.of());
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) root.getOrDefault("blocks", List.of());
        List<Map<String, Object>> entities = (List<Map<String, Object>>) root.getOrDefault("entities", List.of());

        List<String> canonicalBlocks = new ArrayList<>(blocks.size());
        for (Map<String, Object> b : blocks) {
            int stateIdx = ((Number) b.get("state")).intValue();
            List<Object> pos = (List<Object>) b.get("pos");
            String posKey = String.format("(%d,%d,%d)",
                    ((Number) pos.get(0)).intValue(),
                    ((Number) pos.get(1)).intValue(),
                    ((Number) pos.get(2)).intValue());
            Map<String, Object> paletteEntry = palette.get(stateIdx);
            String resolvedState = canonical(paletteEntry);
            Object be = b.get("nbt");
            String beKey = be == null ? "" : canonical(be);
            canonicalBlocks.add(posKey + "|" + resolvedState + "|" + beKey);
        }
        Collections.sort(canonicalBlocks);

        List<String> canonicalEntities = new ArrayList<>(entities.size());
        for (Map<String, Object> e : entities) {
            canonicalEntities.add(canonical(e));
        }
        Collections.sort(canonicalEntities);

        return new StructureSnapshot(sx, sy, sz, dv, canonicalBlocks, canonicalEntities);
    }

    /**
     * Canonical serialization: compounds sorted by key, lists in source order
     * (lists are order-significant in NBT). Primitives are toString'd.
     */
    @SuppressWarnings("unchecked")
    private static String canonical(Object o) {
        if (o == null) return "null";
        if (o instanceof Map<?, ?> m) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (var e : ((Map<String, Object>) m).entrySet()) sorted.put(e.getKey(), e.getValue());
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var e : sorted.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(e.getKey()).append('=').append(canonical(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (o instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object v : l) {
                if (!first) sb.append(',');
                first = false;
                sb.append(canonical(v));
            }
            return sb.append(']').toString();
        }
        if (o instanceof byte[] ba) return java.util.Arrays.toString(ba);
        if (o instanceof int[] ia) return java.util.Arrays.toString(ia);
        if (o instanceof long[] la) return java.util.Arrays.toString(la);
        return o.toString();
    }

    /** Returns null if equal, else a short diff summary suitable for an assertion message. */
    String diff(StructureSnapshot other) {
        if (sx != other.sx || sy != other.sy || sz != other.sz) {
            return String.format("size differs: [%d,%d,%d] vs [%d,%d,%d]",
                    sx, sy, sz, other.sx, other.sy, other.sz);
        }
        if (dataVersion != other.dataVersion) {
            return String.format("DataVersion differs: %d vs %d", dataVersion, other.dataVersion);
        }
        if (!canonicalBlocks.equals(other.canonicalBlocks)) {
            return blockDiff(other);
        }
        if (!canonicalEntities.equals(other.canonicalEntities)) {
            return String.format("entities differ: %d vs %d (first uniq: %s ↔ %s)",
                    canonicalEntities.size(), other.canonicalEntities.size(),
                    firstUnique(canonicalEntities, other.canonicalEntities),
                    firstUnique(other.canonicalEntities, canonicalEntities));
        }
        return null;
    }

    private String blockDiff(StructureSnapshot other) {
        String onlyHere = firstUnique(canonicalBlocks, other.canonicalBlocks);
        String onlyThere = firstUnique(other.canonicalBlocks, canonicalBlocks);
        return String.format("blocks differ (this has %d, other has %d). First only-here: %s. First only-there: %s",
                canonicalBlocks.size(), other.canonicalBlocks.size(),
                truncate(onlyHere, 240), truncate(onlyThere, 240));
    }

    private static String firstUnique(List<String> a, List<String> b) {
        var bSet = new java.util.HashSet<>(b);
        for (String s : a) if (!bSet.contains(s)) return s;
        return "(none)";
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
