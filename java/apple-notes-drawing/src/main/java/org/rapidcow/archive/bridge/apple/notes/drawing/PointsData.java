package org.rapidcow.archive.bridge.apple.notes.drawing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Interpreted form of StrokeData's points_count/points_flags/points_data
 *  trio: the flat, variable-stride point table, unpacked. */
public record PointsData(List<StrokePoint> points, Set<StrokeChannel> presentChannels, int stride) {
	public static PointsData decode(StrokeData data) {
		int count = data.getPointsCount();
		if (count == 0 || !data.hasPointsData()) {
			return new PointsData(List.of(), Set.of(), 8);
		}

		int flags = data.getPointsFlags();
		EnumSet<StrokeChannel> present = EnumSet.noneOf(StrokeChannel.class);
		int stride = 8;
		for (StrokeChannel ch : StrokeChannel.values()) {
			if ((flags & (1 << ch.bitIndex)) != 0) {
				present.add(ch);
				stride += ch.byteSize;
			}
		}

		byte[] raw = data.getPointsData().toByteArray();
		if (raw.length != count * stride) {
			throw new StrokeDataFormatException(
				"points_data length " + raw.length + " != pointsCount " + count
					+ " * derived stride " + stride + " (pointsFlags=0x"
					+ Integer.toHexString(flags) + ")");
		}

		ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
		List<StrokePoint> points = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			float x = buf.getFloat();
			float y = buf.getFloat();
			EnumMap<StrokeChannel, ChannelReading> channels = new EnumMap<>(StrokeChannel.class);
			for (StrokeChannel ch : present) {
				channels.put(ch, ch.decode(buf));
			}
			points.add(new StrokePoint(x, y, channels));
		}
		return new PointsData(List.copyOf(points), Set.copyOf(present), stride);
	}
}
