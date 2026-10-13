package org.rapidcow.ai.archive.bridge.microsoft.windowsink;

import static org.rapidcow.ai.archive.bridge.microsoft.windowsink.InkDocument.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * Bounded ISF v1 packet decoder, including fortified GIF comment containers. Drawing attributes,
 * metrics, custom properties and button bits are retained without interpretation.
 */
public final class IsfDecoder {
    private static final int[][] HUFF = {
        {0, 1, 2, 4, 6, 8, 12, 16, 24, 32}, {0, 1, 1, 2, 4, 8, 12, 16, 24, 32},
        {0, 1, 1, 1, 2, 4, 8, 14, 22, 32}, {0, 2, 2, 3, 5, 8, 12, 16, 24, 32},
        {0, 3, 4, 5, 8, 12, 16, 24, 32}, {0, 4, 6, 8, 12, 16, 24, 32},
        {0, 6, 8, 12, 16, 24, 32}, {0, 7, 8, 12, 16, 24, 32}
    };
    private final int maxValues;

    public IsfDecoder() {
        this(2_000_000);
    }

    /** Limit total decoded scalar packet values across the document. */
    public IsfDecoder(int maxValues) {
        if (maxValues < 1) throw new IllegalArgumentException("maxValues must be positive");
        this.maxValues = maxValues;
    }

    public InkDocument decode(byte[] input) throws IsfFormatException {
        Objects.requireNonNull(input, "input");
        if (input.length >= 6 && input[0] == 'G' && input[1] == 'I' && input[2] == 'F')
            input = extractGifInk(input);
        Cursor c = new Cursor(input);
        if (c.uint() != 0) throw c.error("Unsupported ISF version");
        Cursor body = c.block();
        if (c.remaining() != 0) throw c.error("Trailing bytes after ISF object");
        List<Stroke> strokes = new ArrayList<>();
        List<Block> metadata = new ArrayList<>();
        List<Descriptor> descriptors = new ArrayList<>();
        List<byte[]> attrs = new ArrayList<>(), metrics = new ArrayList<>();
        List<Transform> transforms = new ArrayList<>();
        int di = 0, ai = 0, mi = 0, ti = 0, total = 0;
        while (body.remaining() > 0) {
            int tag = body.uint();
            switch (tag) {
                case 9 -> ai = body.uint();
                case 13 -> di = body.uint();
                case 23 -> ti = body.uint();
                case 26 -> mi = body.uint();
                case 0 -> {
                    for (int i = 0; i < 4; i++) body.signed();
                }
                case 16, 17, 18, 19, 20, 21 -> {
                    transforms.clear();
                    transforms.add(transform(body, tag, false));
                }
                case 10 -> {
                    Cursor s = body.block();
                    int n = s.uint();
                    Descriptor d =
                            select(descriptors, di, new Descriptor(List.of(50, 51), 0), body);
                    long values = (long) n * d.channels.size();
                    if (n > maxValues || values > maxValues - total)
                        throw s.error("Packet value limit exceeded");
                    total += (int) values;
                    Map<Integer, List<Integer>> channels = new LinkedHashMap<>();
                    for (int property : d.channels) channels.put(property, packets(s, n));
                    long buttonSize = ((long) n * d.buttons + 7) / 8;
                    if (buttonSize > Integer.MAX_VALUE) throw s.error("Button data too large");
                    byte[] buttons = s.bytes((int) buttonSize);
                    strokes.add(
                            new Stroke(
                                    n,
                                    channels,
                                    select(transforms, ti, Transform.IDENTITY, body),
                                    select(attrs, ai, new byte[0], body),
                                    select(metrics, mi, new byte[0], body),
                                    buttons,
                                    s.bytes(s.remaining())));
                }
                case 1, 2, 3, 4, 5, 14, 15, 24, 25, 28, 29, 30, 31 -> {
                    Cursor b = body.block();
                    metadata.add(new Block(tag, b.copy()));
                    switch (tag) {
                        case 2 -> {
                            attrs.clear();
                            while (b.remaining() > 0) attrs.add(b.block().copy());
                        }
                        case 3 -> {
                            attrs.clear();
                            attrs.add(b.copy());
                        }
                        case 4 -> {
                            descriptors.clear();
                            while (b.remaining() > 0) descriptors.add(descriptor(b.block()));
                        }
                        case 5 -> {
                            descriptors.clear();
                            descriptors.add(descriptor(b));
                        }
                        case 24 -> {
                            metrics.clear();
                            while (b.remaining() > 0) metrics.add(b.block().copy());
                        }
                        case 25 -> {
                            metrics.clear();
                            metrics.add(b.copy());
                        }
                        case 15, 31 -> {
                            transforms.clear();
                            while (b.remaining() > 0)
                                transforms.add(transform(b, b.uint(), tag == 31));
                        }
                        case 14 -> throw b.error("Custom compression tables are unsupported");
                        default -> {}
                    }
                }
                default -> {
                    if (tag < 100) throw body.error("Unsupported ISF tag " + tag);
                    int size = body.uint();
                    if (size == Integer.MAX_VALUE) throw body.error("Property size overflow");
                    metadata.add(new Block(tag, body.bytes(size + 1)));
                }
            }
        }
        return new InkDocument(strokes, metadata);
    }

    private record Descriptor(List<Integer> channels, int buttons) {}

    private static Descriptor descriptor(Cursor c) throws IsfFormatException {
        List<Integer> channels = new ArrayList<>(List.of(50, 51));
        int buttons = 0;
        while (c.remaining() > 0) {
            int t = c.uint();
            if (t == 7) channels.remove(Integer.valueOf(50));
            else if (t == 8) channels.remove(Integer.valueOf(51));
            else if (t == 6) {
                buttons = c.uint();
                if (buttons > c.remaining()) throw c.error("Truncated button descriptor");
                for (int i = 0; i < buttons; i++) c.uint();
            } else if (t == 11) {
                while (c.remaining() > 0) c.uint();
            } else {
                if (t < 50 || channels.contains(t)) throw c.error("Invalid packet descriptor");
                channels.add(t);
            }
        }
        return new Descriptor(List.copyOf(channels), buttons);
    }

    private static <T> T select(List<T> list, int index, T fallback, Cursor c)
            throws IsfFormatException {
        if (list.isEmpty() && index == 0) return fallback;
        if (index >= list.size()) throw c.error("Table index out of bounds");
        return list.get(index);
    }

    private static Transform transform(Cursor c, int tag, boolean doubles)
            throws IsfFormatException {
        if (tag == 19) {
            double a = Math.toRadians(c.uint() / 100.0);
            return new Transform(Math.cos(a), Math.sin(a), -Math.sin(a), Math.cos(a), 0, 0);
        }
        int n =
                switch (tag) {
                    case 16 -> 6;
                    case 17 -> 1;
                    case 18, 20 -> 2;
                    case 21 -> 4;
                    default -> throw c.error("Unsupported transform tag " + tag);
                };
        double[] v = new double[n];
        for (int i = 0; i < n; i++) v[i] = c.real(doubles);
        return switch (tag) {
            case 16 -> new Transform(v[0], v[1], v[2], v[3], v[4], v[5]);
            case 17 -> new Transform(v[0], 0, 0, v[0], 0, 0);
            case 18 -> new Transform(v[0], 0, 0, v[1], 0, 0);
            case 20 -> new Transform(1, 0, 0, 1, v[0], v[1]);
            default -> new Transform(v[0], 0, 0, v[1], v[2], v[3]);
        };
    }

    private static List<Integer> packets(Cursor c, int count) throws IsfFormatException {
        if (count == 0) return List.of();
        int algorithm = c.byteValue();
        boolean huff = (algorithm & 0xC0) == 0x80;
        if (!huff && (algorithm & 0xC0) != 0) throw c.error("Unsupported packet compression");
        if (huff && ((algorithm & 0x20) != 0 || (algorithm & 31) >= HUFF.length))
            throw c.error("Unsupported Huffman table");
        boolean delta = huff || (algorithm & 32) != 0;
        int width = algorithm & 31;
        if (width == 0) width = 32;
        long prev = 0, prev2 = 0;
        List<Integer> result = new ArrayList<>(count);
        int initial = !huff && delta ? 2 : 0;
        if (initial > count) throw c.error("Delta packet requires at least two points");
        for (int i = 0; i < initial; i++) {
            long v = c.signed() + 2 * prev - prev2;
            if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE)
                throw c.error("Packet coordinate overflow");
            result.add((int) v);
            prev2 = prev;
            prev = v;
        }
        Bits bits = new Bits(c);
        for (int i = initial; i < count; i++) {
            long value;
            if (huff) value = huffman(bits, HUFF[algorithm & 31], 0);
            else {
                long raw = bits.read(width);
                value = (int) (raw << (32 - width)) >> (32 - width);
            }
            if (delta) {
                value = value + 2 * prev - prev2;
                prev2 = prev;
                prev = value;
            }
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
                throw c.error("Packet coordinate overflow");
            result.add((int) value);
        }
        return result;
    }

    private static long huffman(Bits b, int[] table, int depth) throws IsfFormatException {
        if (depth > 1) throw b.c.error("Nested Huffman escape");
        int prefix = 0;
        while (b.read(1) != 0) {
            if (++prefix > table.length) throw b.c.error("Invalid Huffman prefix");
        }
        if (prefix == 0) return 0;
        if (prefix == table.length) {
            long extra = huffman(b, table, depth + 1), low = huffman(b, table, depth + 1);
            if (extra < 0 || extra > 7) throw b.c.error("Invalid Huffman overflow bits");
            long magnitude = ((extra >> 1) << 32) | (low & 0xffffffffL);
            return (extra & 1) != 0 ? -magnitude : magnitude;
        }
        long min = 1;
        for (int i = 1; i < prefix; i++) min += 1L << (table[i] - 1);
        long raw = b.read(table[prefix]);
        long value = (raw >>> 1) + min;
        return (raw & 1) != 0 ? -value : value;
    }

    private static final class Bits {
        final Cursor c;
        int current, remaining;

        Bits(Cursor c) {
            this.c = c;
        }

        long read(int n) throws IsfFormatException {
            long v = 0;
            for (int i = 0; i < n; i++) {
                if (remaining == 0) {
                    current = c.byteValue();
                    remaining = 8;
                }
                v = (v << 1) | ((current >> --remaining) & 1);
            }
            return v;
        }
    }

    private static final class Cursor {
        final byte[] data;
        final int end;
        int pos;

        Cursor(byte[] data) {
            this(data, 0, data.length);
        }

        Cursor(byte[] data, int start, int end) {
            this.data = data;
            pos = start;
            this.end = end;
        }

        int remaining() {
            return end - pos;
        }

        IsfFormatException error(String message) {
            return new IsfFormatException(message + " at byte " + pos);
        }

        int byteValue() throws IsfFormatException {
            if (pos == end) throw error("Truncated data");
            return data[pos++] & 255;
        }

        long varint() throws IsfFormatException {
            long value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int b = byteValue();
                value |= (long) (b & 127) << shift;
                if ((b & 128) == 0) return value;
            }
            throw error("Oversized variable integer");
        }

        int uint() throws IsfFormatException {
            long v = varint();
            if (v > Integer.MAX_VALUE) throw error("Integer exceeds supported range");
            return (int) v;
        }

        long signed() throws IsfFormatException {
            long v = varint();
            if (v > 0x100000001L || v == 0x100000000L) throw error("Signed integer overflow");
            return (v & 1) != 0 ? -(v >>> 1) : v >>> 1;
        }

        byte[] bytes(int n) throws IsfFormatException {
            if (n < 0 || n > remaining()) throw error("Truncated block");
            byte[] v = Arrays.copyOfRange(data, pos, pos + n);
            pos += n;
            return v;
        }

        Cursor block() throws IsfFormatException {
            int n = uint();
            if (n > remaining()) throw error("Block exceeds enclosing size");
            Cursor c = new Cursor(data, pos, pos + n);
            pos += n;
            return c;
        }

        byte[] copy() {
            return Arrays.copyOfRange(data, pos, end);
        }

        double real(boolean wide) throws IsfFormatException {
            ByteBuffer b = ByteBuffer.wrap(bytes(wide ? 8 : 4)).order(ByteOrder.LITTLE_ENDIAN);
            double v = wide ? b.getDouble() : b.getFloat();
            if (!Double.isFinite(v)) throw error("Nonfinite transform");
            return v;
        }
    }

    /** Extract ISF from a GIF comment extension without decoding its raster preview. */
    public static byte[] extractGifInk(byte[] input) throws IsfFormatException {
        Cursor c = new Cursor(input);
        String header = new String(c.bytes(6), java.nio.charset.StandardCharsets.US_ASCII);
        if (!header.equals("GIF89a") && !header.equals("GIF87a"))
            throw c.error("Invalid GIF header");
        byte[] screen = c.bytes(7);
        int flags = screen[4] & 255;
        if ((flags & 128) != 0) c.bytes(3 * (1 << ((flags & 7) + 1)));
        byte[] ink = null;
        while (c.remaining() > 0) {
            int type = c.byteValue();
            if (type == 59) {
                if (ink == null) throw c.error("GIF has no ISF comment");
                return ink;
            }
            int label = -1;
            if (type == 44) {
                byte[] image = c.bytes(9);
                flags = image[8] & 255;
                if ((flags & 128) != 0) c.bytes(3 * (1 << ((flags & 7) + 1)));
                c.byteValue();
            } else if (type == 33) label = c.byteValue();
            else throw c.error("Invalid GIF block");
            ByteArrayOutputStream comment = label == 254 ? new ByteArrayOutputStream() : null;
            for (int n = c.byteValue(); n != 0; n = c.byteValue()) {
                byte[] b = c.bytes(n);
                if (comment != null) comment.writeBytes(b);
            }
            if (comment != null) {
                byte[] b = comment.toByteArray();
                if (b.length > 0 && b[0] == 0) {
                    if (ink != null) throw c.error("Multiple ISF comments");
                    ink = b;
                }
            }
        }
        throw c.error("Missing GIF trailer");
    }
}
