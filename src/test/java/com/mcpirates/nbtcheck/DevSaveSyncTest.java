package com.mcpirates.nbtcheck;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Catches drift between NBTs the user saves during a dev session
 * (runs/&lt;client|server&gt;/saves/.../generated/mcpirates/structures/&lt;name&gt;.nbt)
 * and the corresponding checked-in resource NBT.
 *
 * <p>Only the resource path is treated as source-of-truth — both {@code runs/} and
 * {@code tools/sources/} are gitignored, so anything not under
 * {@code src/main/resources/data/mcpirates/structure/} can't be authoritative.
 *
 * <p>Pre-migration caveat: ship hulls and pads (airship_small, crossbow_board,
 * galleon, ramship and their {@code _pad} variants) have post-processing applied by
 * {@code tools/build_ships.py} (anchor block stamp, air buffer, pad lift), so a fresh
 * dev save will diverge from the resource until the script is re-run. Drift reports
 * for those names should be read as "rerun build_ships.py to refresh the resource,"
 * or — better — finish the Plan A migration so resource == dev save by construction.
 *
 * <p>If no dev-save tree exists under {@code runs/}, the suite emits a single
 * pass-through test so CI stays green.
 */
class DevSaveSyncTest {

    private static final Path RUNS_ROOT = Path.of("runs");
    private static final Path RESOURCE_STRUCT = Path.of("src/main/resources/data/mcpirates/structure");

    @TestFactory
    Stream<DynamicTest> devSavesMatchResourceNbt() {
        List<Path> devSaves;
        try {
            devSaves = findDevSaves();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        if (devSaves.isEmpty()) {
            return Stream.of(DynamicTest.dynamicTest(
                    "no dev saves under runs/ — skipping sync check", () -> {}));
        }

        List<DynamicTest> tests = new ArrayList<>();
        for (Path devSave : devSaves) {
            String name = stripExtension(devSave.getFileName().toString());
            String label = name + " <- " + RUNS_ROOT.relativize(devSave);
            tests.add(DynamicTest.dynamicTest(label, () -> checkOne(devSave, name)));
        }
        return tests.stream();
    }

    private static void checkOne(Path devSave, String name) throws IOException {
        if (name.startsWith("base_plate_with_")) {
            fail("'" + name + "' was saved under runs/ but base_plate_with_* NBTs are built "
                    + "from the vanilla pillager_outpost base_plate by tools/build_ships.py. "
                    + "Authoring one in-game can't roundtrip — delete the dev save.");
        }

        Path expected = RESOURCE_STRUCT.resolve(name + ".nbt");
        String label = "src/main/resources/data/mcpirates/structure/" + name + ".nbt";

        if (!Files.exists(expected)) {
            fail("'" + name + "' was saved under " + RUNS_ROOT + " but no resource NBT "
                    + "exists at " + label + ". Either copy the dev save into place "
                    + "(and register it in the relevant pool JSON), or delete the dev save "
                    + "if it was experimental.");
        }

        StructureSnapshot dev = StructureSnapshot.of(devSave);
        StructureSnapshot src = StructureSnapshot.of(expected);
        String diff = dev.diff(src);
        assertNull(diff, "Dev save " + devSave + " is out of sync with " + label + ": " + diff
                + "\n(If '" + name + "' is a ship hull or pad, this is expected until the "
                + "Plan A migration: rerun tools/build_ships.py to refresh the resource.)");
    }

    private static List<Path> findDevSaves() throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(RUNS_ROOT)) return out;
        try (Stream<Path> s = Files.walk(RUNS_ROOT)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".nbt"))
                    .filter(DevSaveSyncTest::isMcpiratesGeneratedStructure)
                    .forEach(out::add);
        }
        return out;
    }

    /** Path looks like {@code runs/.../generated/mcpirates/structures/<name>.nbt}. */
    private static boolean isMcpiratesGeneratedStructure(Path p) {
        String s = p.toString().replace('\\', '/');
        return s.contains("/generated/mcpirates/structures/")
                || s.contains("/generated/mcpirates/structure/");
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }
}
