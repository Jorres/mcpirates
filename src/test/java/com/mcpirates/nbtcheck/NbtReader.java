package com.mcpirates.nbtcheck;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

final class NbtReader {

    private NbtReader() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        InputStream in = (raw.length >= 2 && (raw[0] & 0xff) == 0x1f && (raw[1] & 0xff) == 0x8b)
                ? new GZIPInputStream(new ByteArrayInputStream(raw))
                : new ByteArrayInputStream(raw);
        try (DataInputStream d = new DataInputStream(in)) {
            int type = d.readUnsignedByte();
            if (type != 10) {
                throw new IOException("Expected root TAG_Compound in " + file + ", got tag " + type);
            }
            readString(d); // root name (usually empty)
            return (Map<String, Object>) readPayload(d, 10);
        }
    }

    private static String readString(DataInputStream d) throws IOException {
        int len = d.readUnsignedShort();
        byte[] buf = new byte[len];
        d.readFully(buf);
        return new String(buf, StandardCharsets.UTF_8);
    }

    private static Object readPayload(DataInputStream d, int type) throws IOException {
        switch (type) {
            case 1: return d.readByte();
            case 2: return d.readShort();
            case 3: return d.readInt();
            case 4: return d.readLong();
            case 5: return d.readFloat();
            case 6: return d.readDouble();
            case 7: {
                int n = d.readInt();
                byte[] buf = new byte[n];
                d.readFully(buf);
                return buf;
            }
            case 8: return readString(d);
            case 9: {
                int itemType = d.readUnsignedByte();
                int n = d.readInt();
                List<Object> list = new ArrayList<>(Math.max(n, 0));
                for (int i = 0; i < n; i++) list.add(readPayload(d, itemType));
                return list;
            }
            case 10: {
                Map<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    int t = d.readUnsignedByte();
                    if (t == 0) break;
                    String name = readString(d);
                    map.put(name, readPayload(d, t));
                }
                return map;
            }
            case 11: {
                int n = d.readInt();
                int[] arr = new int[n];
                for (int i = 0; i < n; i++) arr[i] = d.readInt();
                return arr;
            }
            case 12: {
                int n = d.readInt();
                long[] arr = new long[n];
                for (int i = 0; i < n; i++) arr[i] = d.readLong();
                return arr;
            }
            default: throw new IOException("Unknown NBT tag type " + type);
        }
    }
}
