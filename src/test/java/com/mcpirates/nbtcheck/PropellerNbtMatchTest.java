package com.mcpirates.nbtcheck;

import com.mcpirates.airship.ships.ShipNbtSpec;
import com.mcpirates.nbtcheck.StructureBundle.BlockRef;
import com.mcpirates.nbtcheck.StructureBundle.ParsedStructure;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Each kind's {@link ShipNbtSpec} declares propeller positions (lever-relative) and
 * the NBT-baked REVERSED default per side. Validate against the on-disk structure NBT:
 * <ul>
 *   <li>Every declared lever-relative propeller delta must resolve to an actual
 *       Aeronautics propeller block in the NBT (not air, not some other block).</li>
 *   <li>The kind's {@code nbtReversedL/R} (and {@code nbtReversedF} for ramship) must
 *       match the NBT palette entry's {@code reversed} property.</li>
 * </ul>
 *
 * <p>If either check fails: either the NBT has drifted (someone re-saved the structure
 * with different prop placements), or the spec is out of date. Both are bugs.
 */
class PropellerNbtMatchTest {

    private static final String NS = "mcpirates";

    private final StructureBundle bundle = StructureBundle.load();

    @TestFactory
    Stream<DynamicTest> propellerPositionsAndReversedMatchNbt() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ShipNbtSpec spec : ShipNbtSpecDiscovery.all()) {
            String shipId = NS + ":" + spec.shipId();
            tests.add(DynamicTest.dynamicTest(
                    shipId + " propellers match NbtSpec",
                    () -> assertProps(shipId, spec)));
        }
        return tests.stream();
    }

    private void assertProps(String shipId, ShipNbtSpec spec) {
        ParsedStructure s = bundle.structures.get(shipId);
        assertNotNull(s, shipId + " missing structure NBT");

        int[] lever = leverNbtPos(spec);

        // Set of declared world positions (lever-relative → NBT-absolute), one per side.
        Set<String> leftExpected = expectedPositions(spec.leftPropellersLeverRel(), lever);
        Set<String> rightExpected = expectedPositions(spec.rightPropellersLeverRel(), lever);
        Set<String> forwardExpected = spec.forwardPropellerLeverRel() == null
                ? Set.of()
                : expectedPositions(new int[][]{spec.forwardPropellerLeverRel()}, lever);

        // Walk every block in the NBT. If it's at a declared position, the palette entry
        // must be an aeronautics propeller AND its reversed property must match.
        Set<String> leftSeen = new HashSet<>();
        Set<String> rightSeen = new HashSet<>();
        Set<String> forwardSeen = new HashSet<>();
        for (BlockRef b : s.blocks) {
            String key = key(b.pos);
            boolean isLeft = leftExpected.contains(key);
            boolean isRight = rightExpected.contains(key);
            boolean isForward = forwardExpected.contains(key);
            if (!isLeft && !isRight && !isForward) continue;

            Map<String, Object> palette = s.palette.get(b.stateIdx);
            Object name = palette.get("Name");
            if (!(name instanceof String n) || !n.contains("propeller")) {
                fail(shipId + " expected propeller at NBT " + key + " but found '" + name + "'");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) palette.getOrDefault("Properties", Map.of());
            Object reversedRaw = props.get("reversed");
            boolean actualReversed = "true".equals(String.valueOf(reversedRaw));

            if (isLeft) {
                leftSeen.add(key);
                assertEquals(spec.nbtReversedL(), actualReversed,
                        shipId + " LEFT prop at " + key + " has reversed=" + actualReversed
                                + " but spec says nbtReversedL=" + spec.nbtReversedL());
            }
            if (isRight) {
                rightSeen.add(key);
                assertEquals(spec.nbtReversedR(), actualReversed,
                        shipId + " RIGHT prop at " + key + " has reversed=" + actualReversed
                                + " but spec says nbtReversedR=" + spec.nbtReversedR());
            }
            if (isForward) {
                forwardSeen.add(key);
                Boolean expected = spec.nbtReversedF();
                assertNotNull(expected, shipId + " forward prop declared but nbtReversedF is null");
                assertEquals(expected, actualReversed,
                        shipId + " FORWARD prop at " + key + " has reversed=" + actualReversed
                                + " but spec says nbtReversedF=" + expected);
            }
        }

        assertEquals(leftExpected, leftSeen,
                shipId + " LEFT propellers declared in spec but missing from NBT: "
                        + minus(leftExpected, leftSeen));
        assertEquals(rightExpected, rightSeen,
                shipId + " RIGHT propellers declared in spec but missing from NBT: "
                        + minus(rightExpected, rightSeen));
        assertEquals(forwardExpected, forwardSeen,
                shipId + " FORWARD propeller declared in spec but missing from NBT: "
                        + minus(forwardExpected, forwardSeen));
    }

    private static int[] leverNbtPos(ShipNbtSpec spec) {
        int[] a = spec.anchorNbtPos();
        int[] d = spec.anchorToLever();
        return new int[]{a[0] + d[0], a[1] + d[1], a[2] + d[2]};
    }

    private static Set<String> expectedPositions(int[][] leverRelative, int[] lever) {
        Set<String> out = new HashSet<>();
        for (int[] d : leverRelative) {
            out.add(key(new int[]{lever[0] + d[0], lever[1] + d[1], lever[2] + d[2]}));
        }
        return out;
    }

    private static String key(int[] pos) {
        return pos[0] + "," + pos[1] + "," + pos[2];
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.removeAll(b);
        return out;
    }
}
