package com.mcpirates.nbtcheck;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Loads every mcpirates structure NBT and worldgen JSON into a single in-memory
 * view that static checks can query. Pure-Java; does not touch Minecraft classes.
 */
final class StructureBundle {

    static final String NS = "mcpirates";
    static final Path RES_ROOT = Path.of("src/main/resources/data");
    static final Path STRUCT_ROOT = RES_ROOT.resolve(NS).resolve("structure");
    static final Path POOL_ROOT = RES_ROOT.resolve(NS).resolve("worldgen/template_pool");
    static final Path WORLDGEN_STRUCT_ROOT = RES_ROOT.resolve(NS).resolve("worldgen/structure");

    private static final Gson GSON = new Gson();

    final Map<String, ParsedStructure> structures;   // id -> parsed NBT view
    final Map<String, ParsedPool> pools;             // id -> pool JSON
    final Map<String, JsonObject> worldgenStarts;    // id -> worldgen/structure JSON

    private StructureBundle(Map<String, ParsedStructure> s, Map<String, ParsedPool> p, Map<String, JsonObject> w) {
        this.structures = s;
        this.pools = p;
        this.worldgenStarts = w;
    }

    static StructureBundle load() {
        Map<String, ParsedStructure> structures = new LinkedHashMap<>();
        walk(STRUCT_ROOT, ".nbt", file -> {
            String id = NS + ":" + relIdNoExt(STRUCT_ROOT, file, ".nbt");
            try {
                structures.put(id, ParsedStructure.from(id, file, NbtReader.read(file)));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to parse " + file, e);
            }
        });

        Map<String, ParsedPool> pools = new LinkedHashMap<>();
        walk(POOL_ROOT, ".json", file -> {
            String id = NS + ":" + relIdNoExt(POOL_ROOT, file, ".json");
            try {
                JsonObject obj = GSON.fromJson(Files.readString(file), JsonObject.class);
                pools.put(id, ParsedPool.from(id, file, obj));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read " + file, e);
            }
        });

        Map<String, JsonObject> worldgenStarts = new LinkedHashMap<>();
        walk(WORLDGEN_STRUCT_ROOT, ".json", file -> {
            String id = NS + ":" + relIdNoExt(WORLDGEN_STRUCT_ROOT, file, ".json");
            try {
                worldgenStarts.put(id, GSON.fromJson(Files.readString(file), JsonObject.class));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read " + file, e);
            }
        });

        return new StructureBundle(structures, pools, worldgenStarts);
    }

    private static String relIdNoExt(Path root, Path file, String ext) {
        String rel = root.relativize(file).toString().replace('\\', '/');
        return rel.substring(0, rel.length() - ext.length());
    }

    private static void walk(Path root, String ext, java.util.function.Consumer<Path> sink) {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(p -> p.toString().endsWith(ext)).filter(Files::isRegularFile).forEach(sink);
        } catch (IOException e) {
            throw new UncheckedIOException("Walk failed: " + root, e);
        }
    }

    static final class ParsedStructure {
        final String id;
        final Path file;
        final int sx, sy, sz;
        final int dataVersion;
        final List<Map<String, Object>> palette;
        final List<Jigsaw> jigsaws;
        final List<BlockRef> blocks;

        ParsedStructure(String id, Path file, int sx, int sy, int sz, int dataVersion,
                        List<Map<String, Object>> palette, List<Jigsaw> jigsaws, List<BlockRef> blocks) {
            this.id = id;
            this.file = file;
            this.sx = sx; this.sy = sy; this.sz = sz;
            this.dataVersion = dataVersion;
            this.palette = palette;
            this.jigsaws = jigsaws;
            this.blocks = blocks;
        }

        @SuppressWarnings("unchecked")
        static ParsedStructure from(String id, Path file, Map<String, Object> root) {
            List<Object> size = (List<Object>) root.get("size");
            int sx = ((Number) size.get(0)).intValue();
            int sy = ((Number) size.get(1)).intValue();
            int sz = ((Number) size.get(2)).intValue();
            int dv = root.get("DataVersion") instanceof Number n ? n.intValue() : -1;
            List<Map<String, Object>> palette = (List<Map<String, Object>>) root.getOrDefault("palette", List.of());
            List<Map<String, Object>> blocks = (List<Map<String, Object>>) root.getOrDefault("blocks", List.of());

            List<Jigsaw> jigs = new ArrayList<>();
            List<BlockRef> bRefs = new ArrayList<>(blocks.size());
            for (Map<String, Object> b : blocks) {
                int stateIdx = ((Number) b.get("state")).intValue();
                List<Object> pos = (List<Object>) b.get("pos");
                int[] p = { ((Number) pos.get(0)).intValue(), ((Number) pos.get(1)).intValue(), ((Number) pos.get(2)).intValue() };
                Map<String, Object> nbt = (Map<String, Object>) b.get("nbt");
                bRefs.add(new BlockRef(stateIdx, p, nbt));
                if (nbt != null) {
                    Object bid = nbt.get("id");
                    if (bid instanceof String s && s.equals("minecraft:jigsaw")) {
                        Map<String, Object> stateEntry = palette.get(stateIdx);
                        Map<String, Object> props = (Map<String, Object>) stateEntry.getOrDefault("Properties", Map.of());
                        jigs.add(new Jigsaw(
                                p,
                                str(nbt, "name"),
                                str(nbt, "target"),
                                str(nbt, "pool"),
                                str(nbt, "final_state"),
                                str(nbt, "joint"),
                                props.get("orientation") == null ? null : props.get("orientation").toString()
                        ));
                    }
                }
            }
            return new ParsedStructure(id, file, sx, sy, sz, dv, palette, jigs, bRefs);
        }

        private static String str(Map<String, Object> m, String k) {
            Object v = m.get(k);
            return v == null ? null : v.toString();
        }
    }

    static final class Jigsaw {
        final int[] pos;
        final String name;
        final String target;
        final String pool;
        final String finalState;
        final String joint;
        final String orientation;

        Jigsaw(int[] pos, String name, String target, String pool, String finalState, String joint, String orientation) {
            this.pos = pos;
            this.name = name;
            this.target = target;
            this.pool = pool;
            this.finalState = finalState;
            this.joint = joint;
            this.orientation = orientation;
        }
    }

    static final class BlockRef {
        final int stateIdx;
        final int[] pos;
        final Map<String, Object> nbt;
        BlockRef(int stateIdx, int[] pos, Map<String, Object> nbt) {
            this.stateIdx = stateIdx;
            this.pos = pos;
            this.nbt = nbt;
        }
    }

    static final class ParsedPool {
        final String id;
        final Path file;
        final String fallback;
        final List<String> elementLocations; // children referenced via single_pool_element

        ParsedPool(String id, Path file, String fallback, List<String> elementLocations) {
            this.id = id;
            this.file = file;
            this.fallback = fallback;
            this.elementLocations = elementLocations;
        }

        static ParsedPool from(String id, Path file, JsonObject obj) {
            String fallback = obj.has("fallback") ? obj.get("fallback").getAsString() : null;
            List<String> locs = new ArrayList<>();
            if (obj.has("elements")) {
                for (var elJson : obj.getAsJsonArray("elements")) {
                    JsonObject element = elJson.getAsJsonObject().getAsJsonObject("element");
                    if (element == null) continue;
                    String type = element.has("element_type") ? element.get("element_type").getAsString() : "";
                    if (!type.equals("minecraft:single_pool_element")) continue;
                    if (element.has("location")) locs.add(element.get("location").getAsString());
                }
            }
            return new ParsedPool(id, file, fallback, locs);
        }
    }

}

