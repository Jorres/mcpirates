package com.mcpirates.nbtcheck;

import com.mcpirates.nbtcheck.StructureBundle.Jigsaw;
import com.mcpirates.nbtcheck.StructureBundle.ParsedPool;
import com.mcpirates.nbtcheck.StructureBundle.ParsedStructure;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Asserts ship↔pad jigsaw mating: each ship's {@code airship_keel} points down,
 * each pad's {@code landing_pad_top} points up, both joints are {@code aligned}
 * (not {@code rollable}), and the jigsaw secondary axis on each piece coincides
 * with that piece's longest footprint dimension — otherwise a placed ship lands
 * sideways across its pad.
 */
class JigsawAirshipPadMatchTest {

    private static final String SHIP_JIGSAW = "mcpirates:airship_keel";
    private static final String PAD_JIGSAW = "mcpirates:landing_pad_top";

    private final StructureBundle bundle = StructureBundle.load();

    @TestFactory
    Stream<DynamicTest> shipKeelJigsawIsDownwardAligned() {
        return everyJigsawNamed(SHIP_JIGSAW).map(jr -> DynamicTest.dynamicTest(
                jr.structure.id + " keel@" + posStr(jr.jigsaw.pos),
                () -> assertConnector(jr, "down", "aligned")
        ));
    }

    @TestFactory
    Stream<DynamicTest> padTopJigsawIsUpwardAligned() {
        return everyJigsawNamed(PAD_JIGSAW).map(jr -> DynamicTest.dynamicTest(
                jr.structure.id + " top@" + posStr(jr.jigsaw.pos),
                () -> assertConnector(jr, "up", "aligned")
        ));
    }

    /**
     * For every pad/ship pair linked through the pool graph: each piece's jigsaw
     * secondary axis must run along that piece's longest footprint dim (X vs Z,
     * Y excluded). Equivalent to: when worldgen rotates the ship to mate aligned
     * with the pad, the long axes coincide instead of crossing perpendicular.
     */
    @TestFactory
    Stream<DynamicTest> shipAndPadLongAxesAlign() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ParsedStructure pad : bundle.structures.values()) {
            for (Jigsaw padJig : pad.jigsaws) {
                if (!PAD_JIGSAW.equals(padJig.name)) continue;
                ParsedPool shipPool = bundle.pools.get(padJig.pool);
                if (shipPool == null) continue; // pool resolution covered elsewhere
                for (String shipLoc : shipPool.elementLocations) {
                    ParsedStructure ship = bundle.structures.get(shipLoc);
                    if (ship == null) continue;
                    Jigsaw shipJig = findJigsawByName(ship, SHIP_JIGSAW);
                    tests.add(DynamicTest.dynamicTest(
                            pad.id + " ↔ " + ship.id,
                            () -> assertPairLongAxesAlign(pad, padJig, ship, shipJig)
                    ));
                }
            }
        }
        return tests.stream();
    }

    private static void assertConnector(JigsawRef jr, String expectedFace, String expectedJoint) {
        String o = jr.jigsaw.orientation;
        assertTrue(o != null && o.startsWith(expectedFace + "_"),
                jr.structure.id + " jigsaw '" + jr.jigsaw.name + "' at " + posStr(jr.jigsaw.pos)
                        + " expected face=" + expectedFace + ", orientation=" + o);
        assertEquals(expectedJoint, jr.jigsaw.joint,
                jr.structure.id + " jigsaw '" + jr.jigsaw.name + "' at " + posStr(jr.jigsaw.pos)
                        + " must be joint='" + expectedJoint + "', got '" + jr.jigsaw.joint + "'");
    }

    private static void assertPairLongAxesAlign(ParsedStructure pad, Jigsaw padJig,
                                                ParsedStructure ship, Jigsaw shipJig) {
        if (shipJig == null) {
            fail(ship.id + " has no '" + SHIP_JIGSAW + "' jigsaw to mate with pad " + pad.id);
        }
        Axis padLong = longestFootprintAxis(pad);
        Axis shipLong = longestFootprintAxis(ship);
        Axis padSecondary = secondaryAxis(padJig.orientation);
        Axis shipSecondary = secondaryAxis(shipJig.orientation);
        assertEquals(padLong, padSecondary,
                pad.id + " size=[" + pad.sx + "," + pad.sy + "," + pad.sz
                        + "] longest footprint=" + padLong + " but jigsaw orientation '"
                        + padJig.orientation + "' has secondary axis=" + padSecondary
                        + " → pad would mate perpendicular to its long side");
        assertEquals(shipLong, shipSecondary,
                ship.id + " size=[" + ship.sx + "," + ship.sy + "," + ship.sz
                        + "] longest footprint=" + shipLong + " but jigsaw orientation '"
                        + shipJig.orientation + "' has secondary axis=" + shipSecondary
                        + " → ship would mate perpendicular to its long side");
    }

    private Stream<JigsawRef> everyJigsawNamed(String name) {
        List<JigsawRef> out = new ArrayList<>();
        for (ParsedStructure s : bundle.structures.values()) {
            for (Jigsaw j : s.jigsaws) {
                if (name.equals(j.name)) out.add(new JigsawRef(s, j));
            }
        }
        return out.stream();
    }

    private static Jigsaw findJigsawByName(ParsedStructure s, String name) {
        for (Jigsaw j : s.jigsaws) {
            if (name.equals(j.name)) return j;
        }
        return null;
    }

    private enum Axis { X, Y, Z }

    private static Axis longestFootprintAxis(ParsedStructure s) {
        return s.sx >= s.sz ? Axis.X : Axis.Z;
    }

    /** Orientation is {@code face_secondary} (e.g. {@code down_south}); returns
     *  the axis of the {@code secondary} component. */
    private static Axis secondaryAxis(String orientation) {
        if (orientation == null) return null;
        int underscore = orientation.indexOf('_');
        if (underscore < 0) return null;
        return directionAxis(orientation.substring(underscore + 1));
    }

    private static Axis directionAxis(String dir) {
        return switch (dir) {
            case "north", "south" -> Axis.Z;
            case "east", "west" -> Axis.X;
            case "up", "down" -> Axis.Y;
            default -> null;
        };
    }

    private static String posStr(int[] p) {
        return "(" + p[0] + "," + p[1] + "," + p[2] + ")";
    }

    private record JigsawRef(ParsedStructure structure, Jigsaw jigsaw) {}
}
