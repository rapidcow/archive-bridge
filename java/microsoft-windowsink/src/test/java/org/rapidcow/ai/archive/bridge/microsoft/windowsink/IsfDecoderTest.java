package org.rapidcow.ai.archive.bridge.microsoft.windowsink;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class IsfDecoderTest {
    @Test
    void emptyObject() throws Exception {
        assertTrue(new IsfDecoder().decode(new byte[] {0, 0}).strokes().isEmpty());
    }

    @Test
    void signedBitPackedPackets() throws Exception {
        // Two signed 4-bit X values (-1, 2), and Y values (3, -4).
        var d = new IsfDecoder().decode(new byte[] {0, 7, 10, 5, 2, 4, (byte) 0xf2, 4, 0x3c});
        assertEquals(List.of(-1, 2), d.strokes().get(0).packetChannels().get(50));
        assertEquals(List.of(3, -4), d.strokes().get(0).packetChannels().get(51));
    }

    @Test
    void defaultHuffmanUsesSecondDifferences() throws Exception {
        // Huffman table 0: +1 = 100, zero = 0. Second differences [1,0,0] -> [1,2,3].
        var d =
                new IsfDecoder()
                        .decode(
                                new byte[] {
                                    0, 7, 10, 5, 3, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0
                                });
        assertEquals(List.of(1, 2, 3), d.strokes().get(0).packetChannels().get(50));
        assertEquals(List.of(0, 0, 0), d.strokes().get(0).packetChannels().get(51));
    }

    @Test
    void deltaBitPackSeedsUseSignedVarints() throws Exception {
        // Seed second differences [10,-5], then [-1,0] -> coordinates [10,15,19,23].
        var d =
                new IsfDecoder()
                        .decode(new byte[] {0, 9, 10, 7, 4, 0x22, 20, 11, (byte) 0xc0, 2, 0});
        assertEquals(List.of(10, 15, 19, 23), d.strokes().get(0).packetChannels().get(50));
    }

    @Test
    void descriptorAddsPressureAndTransformRemainsExplicit() throws Exception {
        // Descriptor contains pressure tag 56; isotropic float transform is 2.0.
        byte[] bytes = {0, 17, 5, 1, 56, 17, 0, 0, 0, 64, 10, 7, 1, 4, 0x10, 4, 0x20, 4, 0x30};
        var stroke = new IsfDecoder().decode(bytes).strokes().get(0);
        assertEquals(List.of(3), stroke.packetChannels().get(56));
        assertEquals(List.of(1), stroke.packetChannels().get(50));
        assertEquals(2.0, stroke.transform().x(1, 2));
        assertEquals(4.0, stroke.transform().y(1, 2));
    }

    @Test
    void truncatedPacketAndUnsupportedTableFail() {
        assertThrows(
                IsfFormatException.class,
                () -> new IsfDecoder().decode(new byte[] {0, 4, 10, 2, 3, 0}));
        assertThrows(
                IsfFormatException.class,
                () -> new IsfDecoder().decode(new byte[] {0, 5, 10, 3, 1, (byte) 0x88, 0}));
    }

    @Test
    void rejectsOutOfRangeDeltaSeeds() {
        // First seed +2^30 is valid; zero second difference reconstructs +2^31.
        byte[] overflow = {
            0, 12, 10, 10, 2, 0x21, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 8, 0, 1, 0
        };
        assertTrue(
                assertThrows(IsfFormatException.class, () -> new IsfDecoder().decode(overflow))
                        .getMessage()
                        .startsWith("Packet coordinate overflow"));
        // Signed varint representation of +2^31 is itself outside the int domain.
        byte[] invalidSigned = {
            0, 12, 10, 10, 2, 0x21, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 16, 0, 1, 0
        };
        assertTrue(
                assertThrows(IsfFormatException.class, () -> new IsfDecoder().decode(invalidSigned))
                        .getMessage()
                        .startsWith("Signed integer overflow"));
    }

    @Test
    void enforcesEnclosingLengthsAndValueBudget() {
        assertThrows(IsfFormatException.class, () -> new IsfDecoder().decode(new byte[] {0, 1}));
        assertThrows(IsfFormatException.class, () -> new IsfDecoder().decode(new byte[] {0, 0, 0}));
        assertThrows(
                IsfFormatException.class,
                () -> new IsfDecoder().decode(new byte[] {0, 3, 10, 2, 5}));
        assertThrows(
                IsfFormatException.class,
                () -> new IsfDecoder(1).decode(new byte[] {0, 7, 10, 5, 2, 4, 0, 4, 0}));
    }

    @Test
    void optionalPrivateFortifiedGif() throws Exception {
        String value = System.getProperty("bridge.journalist.inkFixture");
        assumeTrue(value != null, "Private annex fixture not configured");
        Path file = Path.of(value);
        assumeTrue(Files.isRegularFile(file), "Private annex content unavailable");
        var d = new IsfDecoder().decode(Files.readAllBytes(file));
        assertFalse(d.strokes().isEmpty());
        for (var s : d.strokes()) {
            assertEquals(s.pointCount(), s.packetChannels().get(50).size());
            assertEquals(s.pointCount(), s.packetChannels().get(51).size());
        }
    }
}
