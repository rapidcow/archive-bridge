package org.rapidcow.archive.bridge.apple.notes.drawing;

import java.nio.ByteBuffer;

/**
 * One of the ten optional per-point channels gated by {@code
 * StrokeData.points_flags} bits 1-10 (bit 0 is always set on real samples
 * but names no channel - see {@link PointsData}). Byte layout, bit
 * assignment and decode formulas are all from PencilKit's own
 * PKStrokePathPointsShared/Private accessors - see
 * apple-notes-drawing-2.proto's header comment. decode() returns the
 * value AS STORED ON THE WIRE, un-normalized - see {@link ChannelReading}.
 */
public enum StrokeChannel {
    TIMESTAMP(1, 4)    { ChannelReading decode(ByteBuffer b) { return new ChannelReading.DirectFloat(this, b.getFloat()); } },
    RADIUS(2, 4)       { ChannelReading decode(ByteBuffer b) { return new ChannelReading.DirectFloat(this, b.getFloat()); } },
    ASPECT_RATIO(3, 2) { ChannelReading decode(ByteBuffer b) { return new ChannelReading.AspectRatio(u16(b)); } },
    EDGE_WIDTH(4, 2)   { ChannelReading decode(ByteBuffer b) { return new ChannelReading.EdgeWidth(u16(b)); } },
    FORCE(5, 2)        { ChannelReading decode(ByteBuffer b) { return new ChannelReading.Force(u16(b)); } },
    AZIMUTH(6, 2)      { ChannelReading decode(ByteBuffer b) { return new ChannelReading.Azimuth(u16(b)); } },
    ALTITUDE(7, 2)     { ChannelReading decode(ByteBuffer b) { return new ChannelReading.Altitude(u16(b)); } },
    OPACITY(8, 2)      { ChannelReading decode(ByteBuffer b) { return new ChannelReading.Opacity(u16(b)); } },
    RADIUS2(9, 4)      { ChannelReading decode(ByteBuffer b) { return new ChannelReading.DirectFloat(this, b.getFloat()); } },
    THRESHOLD(10, 2)   { ChannelReading decode(ByteBuffer b) { return new ChannelReading.Threshold(u16(b)); } };

    public final int bitIndex;
    public final int byteSize;

    StrokeChannel(int bitIndex, int byteSize) {
        this.bitIndex = bitIndex;
        this.byteSize = byteSize;
    }

    abstract ChannelReading decode(ByteBuffer littleEndian);

    private static int u16(ByteBuffer b) {
        return b.getShort() & 0xFFFF;
    }
}
