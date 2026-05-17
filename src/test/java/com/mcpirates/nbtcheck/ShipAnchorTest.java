package com.mcpirates.nbtcheck;

import com.mcpirates.airship.kind.AnchorNbtPositions;
import com.mcpirates.nbtcheck.StructureBundle.BlockRef;
import com.mcpirates.nbtcheck.StructureBundle.ParsedStructure;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Each ship NBT listed in {@link AnchorNbtPositions} must contain exactly one
 * {@code mcpirates:ship_anchor} block at the coordinates the game code names, with a
 * matching {@code kind} block-entity tag. Failing here means the source NBT drifted from
 * the kind's anchor-to-lever delta math (or vice versa).
 */
class ShipAnchorTest {

    private static final String ANCHOR_BLOCK = "mcpirates:ship_anchor";
    private static final String NS = "mcpirates";

    private final StructureBundle bundle = StructureBundle.load();

    @TestFactory
    Stream<DynamicTest> eachShipNbtHasAnchorAtSpecifiedPosition() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : AnchorNbtPositions.BY_NAME.entrySet()) {
            String shipName = entry.getKey();
            int[] expected = entry.getValue();
            String shipId = NS + ":" + shipName;
            tests.add(DynamicTest.dynamicTest(
                    shipId + " anchor @ (" + expected[0] + "," + expected[1] + "," + expected[2] + ")",
                    () -> assertAnchor(shipId, expected)
            ));
        }
        return tests.stream();
    }

    private void assertAnchor(String shipId, int[] expected) {
        ParsedStructure s = bundle.structures.get(shipId);
        assertNotNull(s, shipId + " missing structure NBT");

        int anchorPaletteIdx = -1;
        for (int i = 0; i < s.palette.size(); i++) {
            Object name = s.palette.get(i).get("Name");
            if (name instanceof String n && ANCHOR_BLOCK.equals(n)) {
                anchorPaletteIdx = i;
                break;
            }
        }
        assertTrue(anchorPaletteIdx >= 0,
                shipId + " palette has no '" + ANCHOR_BLOCK + "' — ship NBT must bake the anchor in directly");

        List<BlockRef> hits = new ArrayList<>();
        for (BlockRef b : s.blocks) {
            if (b.stateIdx == anchorPaletteIdx) hits.add(b);
        }
        assertEquals(1, hits.size(),
                shipId + " expected exactly 1 anchor block, found " + hits.size());

        BlockRef anchor = hits.get(0);
        assertArrayEquals(expected, anchor.pos,
                shipId + " anchor block at NBT (" + anchor.pos[0] + "," + anchor.pos[1] + "," + anchor.pos[2]
                        + ") but AnchorNbtPositions says (" + expected[0] + "," + expected[1] + "," + expected[2] + ")");

        Object kind = anchor.nbt == null ? null : anchor.nbt.get("kind");
        if (!(kind instanceof String ks) || !shipId.endsWith(":" + ks)) {
            fail(shipId + " anchor BE NBT 'kind' field is " + kind
                    + ", expected '" + shipId.substring(NS.length() + 1) + "'");
        }
    }
}
