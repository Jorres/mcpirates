package com.mcpirates.nbtcheck;

import com.mcpirates.nbtcheck.StructureBundle.Jigsaw;
import com.mcpirates.nbtcheck.StructureBundle.ParsedPool;
import com.mcpirates.nbtcheck.StructureBundle.ParsedStructure;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Layer-1 static checks for mcpirates structure NBTs and worldgen JSONs.
 *
 * Pure-Java, no Minecraft runtime. Each check emits one DynamicTest per
 * structure / pool / jigsaw so a single broken artifact shows up alongside
 * all the other checks instead of short-circuiting the suite.
 *
 * Layer 2 (post-placement, per-kind anchor/lever/engine deltas) is a separate
 * gametest harness.
 */
class NbtStaticChecksTest {

    /** Minecraft block-state orientations a jigsaw can take. */
    private static final Set<String> VALID_ORIENTATIONS = Set.of(
            "down_east", "down_north", "down_south", "down_west",
            "up_east",   "up_north",   "up_south",   "up_west",
            "east_up",   "north_up",   "south_up",   "west_up"
    );

    private static final Set<String> VALID_JOINTS = Set.of("aligned", "rollable");

    private final StructureBundle bundle = StructureBundle.load();

    @TestFactory
    Stream<DynamicTest> poolElementsResolveToExistingNbts() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ParsedPool pool : bundle.pools.values()) {
            for (String loc : pool.elementLocations) {
                tests.add(DynamicTest.dynamicTest(
                        "pool " + pool.id + " -> " + loc,
                        () -> assertNotNull(
                                bundle.structures.get(loc),
                                "Pool '" + pool.id + "' references missing structure NBT '" + loc + "'"
                        )
                ));
            }
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> jigsawPoolRefsResolve() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ParsedStructure s : bundle.structures.values()) {
            for (Jigsaw j : s.jigsaws) {
                String pool = j.pool;
                if (pool == null || pool.isEmpty() || pool.equals("minecraft:empty")) continue;
                if (!pool.startsWith(StructureBundle.NS + ":")) continue; // vanilla pool — out of scope
                tests.add(DynamicTest.dynamicTest(
                        s.id + " jigsaw@" + posStr(j.pos) + " pool=" + pool,
                        () -> assertNotNull(
                                bundle.pools.get(pool),
                                s.id + " jigsaw at " + posStr(j.pos) + " references missing pool '" + pool + "'"
                        )
                ));
            }
        }
        return tests.stream();
    }

    /**
     * For every parent jigsaw with a non-empty pool, at least one element in that
     * pool must contain a jigsaw whose {@code name} matches the parent's {@code target}.
     * Without a match the pool will hard-fail to place at that slot at worldgen.
     */
    @TestFactory
    Stream<DynamicTest> parentTargetHasMatingChildJigsaw() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ParsedStructure parent : bundle.structures.values()) {
            for (Jigsaw j : parent.jigsaws) {
                String pool = j.pool;
                if (pool == null || pool.isEmpty() || pool.equals("minecraft:empty")) continue;
                if (!pool.startsWith(StructureBundle.NS + ":")) continue; // vanilla pool — out of scope
                ParsedPool p = bundle.pools.get(pool);
                if (p == null) continue; // covered by jigsawPoolRefsResolve

                String target = j.target;
                tests.add(DynamicTest.dynamicTest(
                        parent.id + " jigsaw@" + posStr(j.pos) + " target=" + target + " in pool " + pool,
                        () -> {
                            assertNotNull(target, parent.id + " jigsaw at " + posStr(j.pos) + " missing 'target'");
                            List<String> elementsChecked = new ArrayList<>();
                            for (String loc : p.elementLocations) {
                                ParsedStructure child = bundle.structures.get(loc);
                                if (child == null) continue;
                                elementsChecked.add(loc);
                                for (Jigsaw cj : child.jigsaws) {
                                    if (target.equals(cj.name)) return;
                                }
                            }
                            fail(parent.id + " jigsaw at " + posStr(j.pos) + " targets '" + target
                                    + "' but no child in pool " + pool + " has a jigsaw with that name. "
                                    + "Pool elements: " + elementsChecked);
                        }
                ));
            }
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> jigsawOrientationAndJointAreValid() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ParsedStructure s : bundle.structures.values()) {
            for (Jigsaw j : s.jigsaws) {
                tests.add(DynamicTest.dynamicTest(
                        s.id + " jigsaw@" + posStr(j.pos) + " orientation=" + j.orientation + " joint=" + j.joint,
                        () -> {
                            assertTrue(VALID_ORIENTATIONS.contains(j.orientation),
                                    s.id + " jigsaw at " + posStr(j.pos) + " has invalid orientation '" + j.orientation + "'");
                            assertTrue(VALID_JOINTS.contains(j.joint),
                                    s.id + " jigsaw at " + posStr(j.pos) + " has invalid joint '" + j.joint + "'");
                        }
                ));
            }
        }
        return tests.stream();
    }

    /**
     * A captured structure should not contain leftover authoring tools.
     * Structure blocks and command blocks in a baked NBT are almost always a mistake.
     */
    @TestFactory
    Stream<DynamicTest> noLeftoverAuthoringBlocksInPalette() {
        Set<String> banned = Set.of("minecraft:structure_block", "minecraft:command_block",
                "minecraft:chain_command_block", "minecraft:repeating_command_block");
        List<DynamicTest> tests = new ArrayList<>();
        for (ParsedStructure s : bundle.structures.values()) {
            tests.add(DynamicTest.dynamicTest(s.id + " palette has no authoring blocks", () -> {
                List<String> hits = new ArrayList<>();
                for (var entry : s.palette) {
                    Object name = entry.get("Name");
                    if (name instanceof String n && banned.contains(n)) hits.add(n);
                }
                if (!hits.isEmpty()) {
                    fail(s.id + " palette contains authoring blocks: " + hits);
                }
            }));
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> dataVersionIsPresent() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ParsedStructure s : bundle.structures.values()) {
            tests.add(DynamicTest.dynamicTest(s.id + " DataVersion=" + s.dataVersion, () -> {
                assertTrue(s.dataVersion > 0, s.id + " missing or invalid DataVersion: " + s.dataVersion);
            }));
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> worldgenStartPoolsResolve() {
        List<DynamicTest> tests = new ArrayList<>();
        for (var entry : bundle.worldgenStarts.entrySet()) {
            String id = entry.getKey();
            var obj = entry.getValue();
            tests.add(DynamicTest.dynamicTest(id + " start_pool", () -> {
                assertTrue(obj.has("start_pool"), id + " worldgen JSON missing start_pool");
                String startPool = obj.get("start_pool").getAsString();
                if (!startPool.startsWith(StructureBundle.NS + ":")) return; // vanilla pool — out of scope
                assertNotNull(bundle.pools.get(startPool),
                        id + " start_pool '" + startPool + "' does not resolve to a mcpirates pool");
            }));
        }
        return tests.stream();
    }

    /**
     * Structure size in the NBT root must be positive on every axis. Catches
     * cases where a structure was saved with a zero-extent (empty selection)
     * or with a corrupted size tag.
     */
    @TestFactory
    Stream<DynamicTest> structureSizeIsPositive() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ParsedStructure s : bundle.structures.values()) {
            tests.add(DynamicTest.dynamicTest(s.id + " size=[" + s.sx + "," + s.sy + "," + s.sz + "]", () -> {
                assertTrue(s.sx > 0 && s.sy > 0 && s.sz > 0,
                        s.id + " has non-positive size [" + s.sx + "," + s.sy + "," + s.sz + "]");
            }));
        }
        return tests.stream();
    }

    /**
     * Every jigsaw must sit inside the structure bounding box. A jigsaw at a
     * coordinate outside size is unreachable and silently dropped at worldgen.
     */
    @TestFactory
    Stream<DynamicTest> jigsawPositionsInsideBounds() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ParsedStructure s : bundle.structures.values()) {
            for (Jigsaw j : s.jigsaws) {
                tests.add(DynamicTest.dynamicTest(s.id + " jigsaw@" + posStr(j.pos) + " inside bounds", () -> {
                    int x = j.pos[0], y = j.pos[1], z = j.pos[2];
                    assertTrue(x >= 0 && x < s.sx && y >= 0 && y < s.sy && z >= 0 && z < s.sz,
                            s.id + " jigsaw at " + posStr(j.pos) + " outside size ["
                                    + s.sx + "," + s.sy + "," + s.sz + "]");
                }));
            }
        }
        return tests.stream();
    }

    private static String posStr(int[] p) {
        return "(" + p[0] + "," + p[1] + "," + p[2] + ")";
    }
}
