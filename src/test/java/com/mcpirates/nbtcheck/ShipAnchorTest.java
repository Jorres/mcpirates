package com.mcpirates.nbtcheck;

import com.mcpirates.airship.ships.ShipNbtSpec;
import com.mcpirates.nbtcheck.StructureBundle.BlockRef;
import com.mcpirates.nbtcheck.StructureBundle.ParsedStructure;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Each ship NBT must contain exactly one {@code mcpirates:ship_anchor} block at the
 * coordinates the kind's {@link com.mcpirates.airship.ships.ShipNbtSpec#anchorNbtPos()}
 * names, with a matching {@code kind} block-entity tag, and <strong>no</strong>
 * {@code Properties} on its palette entry (the block must load via its default
 * {@code FACING=NORTH} state so structure-template placement rotates the property into
 * the correct world-frame value; see {@code MCPShipAnchorBlock.NBT_FACING}). Failing
 * here means the source NBT drifted from the kind's NbtSpec, or the NBT was re-saved
 * with an explicit FACING.
 */
class ShipAnchorTest {

    private static final String ANCHOR_BLOCK = "mcpirates:ship_anchor";
    private static final String NS = "mcpirates";

    private final StructureBundle bundle = StructureBundle.load();

    @TestFactory
    Stream<DynamicTest> eachShipNbtHasAnchorAtSpecifiedPosition() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ShipNbtSpec spec : ShipNbtSpecDiscovery.all()) {
            int[] expected = spec.anchorNbtPos();
            String shipId = NS + ":" + spec.shipId();
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

        // Anchor palette entry must load at the default state (FACING=NORTH): either no
        // Properties at all, or Properties that pin facing=north (in-game SAVE bakes the
        // default state into the palette explicitly — that's fine). Anything else means
        // someone rotated the anchor before saving, and AirshipKind.detectRotation will
        // misread the rotation post-placement. See MCPShipAnchorBlock.NBT_FACING.
        Object props = s.palette.get(anchorPaletteIdx).get("Properties");
        if (props != null) {
            assertTrue(props instanceof java.util.Map,
                    shipId + " ship_anchor Properties is not a Map: " + props);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> propsMap = (java.util.Map<String, Object>) props;
            // Strict allowlist: every key must be one we expect to see baked at default.
            // Only `facing=north` is permitted; anything else (or any other key) fails.
            for (var e : propsMap.entrySet()) {
                assertEquals("facing", e.getKey(),
                        shipId + " ship_anchor palette has unexpected property '" + e.getKey()
                                + "'=" + e.getValue() + " — strip non-default properties.");
                assertEquals("north", e.getValue(),
                        shipId + " ship_anchor palette has facing=" + e.getValue()
                                + ", expected north (default). AirshipKind.detectRotation will misread.");
            }
        }

        List<BlockRef> hits = new ArrayList<>();
        for (BlockRef b : s.blocks) {
            if (b.stateIdx == anchorPaletteIdx) hits.add(b);
        }
        assertEquals(1, hits.size(),
                shipId + " expected exactly 1 anchor block, found " + hits.size());

        BlockRef anchor = hits.get(0);
        assertArrayEquals(expected, anchor.pos,
                shipId + " anchor block at NBT (" + anchor.pos[0] + "," + anchor.pos[1] + "," + anchor.pos[2]
                        + ") but kind's NbtSpec says (" + expected[0] + "," + expected[1] + "," + expected[2] + ")");

        Object kind = anchor.nbt == null ? null : anchor.nbt.get("kind");
        if (!(kind instanceof String ks) || !shipId.endsWith(":" + ks)) {
            fail(shipId + " anchor BE NBT 'kind' field is " + kind
                    + ", expected '" + shipId.substring(NS.length() + 1) + "'");
        }
    }
}
