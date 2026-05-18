package com.mcpirates.structure;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the convention that every ship NBT's {@code mcpirates:ship_anchor} palette entry
 * carries <strong>no</strong> {@code Properties} compound. Without a Properties tag, the
 * anchor loads via {@code MCPShipAnchorBlock}'s {@code registerDefaultState(FACING=NORTH)},
 * which is what {@code AirshipKind.detectRotation} needs: every NBT starts at the same
 * canonical NBT-frame orientation, and structure-template placement rotates the FACING into
 * the correct world-frame value.
 *
 * <p>If someone re-saves a ship NBT via a Structure Block (or hand-edits one) and bakes in
 * {@code facing=south}, the placed anchor at rotation NONE comes out facing south, and
 * detection then computes the wrong rotation. This test fails immediately on that drift.
 *
 * <p>Pure stdlib — no Minecraft classes on the test classpath. The hand-rolled NBT reader
 * stops as soon as it finds the anchor palette entry; doesn't try to be a complete codec.
 */
class ShipAnchorPaletteTest {

    private static final Path STRUCTURE_DIR =
            Path.of("src/main/resources/data/mcpirates/structure");

    private static final String[] SHIP_KINDS =
            { "airship_small", "crossbow_board", "galleon", "ramship" };

    private static final String ANCHOR_BLOCK_ID = "mcpirates:ship_anchor";

    @Test
    void everyShipAnchorPaletteEntryHasNoProperties() throws IOException {
        for (String kind : SHIP_KINDS) {
            Path nbt = STRUCTURE_DIR.resolve(kind + ".nbt");
            assertTrue(Files.exists(nbt), "missing structure NBT: " + nbt);

            byte[] decompressed;
            try (GZIPInputStream gz = new GZIPInputStream(Files.newInputStream(nbt))) {
                decompressed = gz.readAllBytes();
            }
            Map<String, Object> root = new NbtReader(decompressed).readRoot();

            Object paletteObj = root.get("palette");
            assertNotNull(paletteObj, kind + ": missing 'palette' tag");
            assertInstanceOf(List.class, paletteObj, kind + ": 'palette' isn't a list");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> palette = (List<Map<String, Object>>) paletteObj;

            boolean found = false;
            for (Map<String, Object> entry : palette) {
                if (!ANCHOR_BLOCK_ID.equals(entry.get("Name"))) continue;
                found = true;
                Object props = entry.get("Properties");
                assertNull(props,
                        kind + ": ship_anchor palette entry has unexpected Properties — "
                                + "either the NBT was hand-edited or re-saved with an explicit "
                                + "FACING. Strip Properties so the block uses its default state. "
                                + "Got: " + props);
            }
            assertTrue(found, kind + ": no ship_anchor palette entry");
        }
    }

    // ─── Minimal NBT reader — handles only what we need to navigate the palette ────────

    /** All NBT tag types we care about reading. */
    private static final int TAG_END = 0, TAG_BYTE = 1, TAG_SHORT = 2, TAG_INT = 3,
            TAG_LONG = 4, TAG_FLOAT = 5, TAG_DOUBLE = 6, TAG_BYTE_ARRAY = 7, TAG_STRING = 8,
            TAG_LIST = 9, TAG_COMPOUND = 10, TAG_INT_ARRAY = 11, TAG_LONG_ARRAY = 12;

    private static final class NbtReader {
        private final DataInputStream in;
        NbtReader(byte[] data) { this.in = new DataInputStream(new ByteArrayInputStream(data)); }

        Map<String, Object> readRoot() throws IOException {
            int type = in.readByte();
            if (type != TAG_COMPOUND) throw new IOException("root not a compound: " + type);
            readString(); // root name (usually empty)
            return readCompoundBody();
        }

        private Map<String, Object> readCompoundBody() throws IOException {
            Map<String, Object> fields = new LinkedHashMap<>();
            while (true) {
                int t = in.readByte();
                if (t == TAG_END) return fields;
                String name = readString();
                fields.put(name, readPayload(t));
            }
        }

        private String readString() throws IOException {
            int len = in.readUnsignedShort();
            byte[] bytes = new byte[len];
            in.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        private Object readPayload(int type) throws IOException {
            switch (type) {
                case TAG_BYTE:   return in.readByte();
                case TAG_SHORT:  return in.readShort();
                case TAG_INT:    return in.readInt();
                case TAG_LONG:   return in.readLong();
                case TAG_FLOAT:  return in.readFloat();
                case TAG_DOUBLE: return in.readDouble();
                case TAG_BYTE_ARRAY: {
                    int n = in.readInt();
                    byte[] a = new byte[n];
                    in.readFully(a);
                    return a;
                }
                case TAG_STRING: return readString();
                case TAG_LIST: {
                    int elemType = in.readByte();
                    int n = in.readInt();
                    List<Object> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        if (elemType == TAG_END) continue;
                        list.add(readPayload(elemType));
                    }
                    return list;
                }
                case TAG_COMPOUND: return readCompoundBody();
                case TAG_INT_ARRAY: {
                    int n = in.readInt();
                    int[] a = new int[n];
                    for (int i = 0; i < n; i++) a[i] = in.readInt();
                    return a;
                }
                case TAG_LONG_ARRAY: {
                    int n = in.readInt();
                    long[] a = new long[n];
                    for (int i = 0; i < n; i++) a[i] = in.readLong();
                    return a;
                }
                default: throw new IOException("unknown tag type: " + type);
            }
        }
    }
}
