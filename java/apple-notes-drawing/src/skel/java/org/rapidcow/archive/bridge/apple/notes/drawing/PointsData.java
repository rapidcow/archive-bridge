[% USE StrokeChannel -%]
package org.rapidcow.archive.bridge.apple.notes.drawing;

/*
 * Methodology:
 * The field names originate from PencilKit.framework,
 * and their byte offsets from sFieldOffsets (Os) and
 * sFieldSizes (Wd).  The types (T) are then inferred
 * from the field size and the disassembled code that
 * loads the from these regions.
 *
 * Additional details written by Anthropic Claude can
 * be found in `ai/notes/apple/com.apple.drawing.2/'
 * at the Git repository root.
 *
 * Legend: `f' = float, `i' = signed int,
 * `u' = unsigned int. `<' = little endian.
 * Asterisk means value is subject to re-scaling.
 *
 *       Os  Wd  T     | channel
 *      ---------------+-------------
[% FOREACH chan IN StrokeChannel.channels -%]
 *   [% IF chan.name == "x" %]00
[%- ELSIF chan.name == "y" %]  [% ELSE -%]
[% chan.bit | format("%02d") %][% END -%]
  [% chan.offset | format("%02d") -%]
  [% chan.width | format("%02d") -%]
  [% chan.type %] [% chan.endian -%]
 | [% chan.name %][% IF chan.rescale %]*[% END %]
[% END -%]
 */

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

[%- IF 0 %]
/**
 * Decoder for StrokeData's {@code pointsData}.
 */
public class PointsData
{
	private final int     channelLength;
[% FOREACH chan IN StrokeChannel.channels -%]
	private final [% chan.java_type %][] channel[% chan.name.ucfirst %];
[% END -%]
}
[% END -%]

/** Interpreted form of StrokeData's points_count/points_flags/points_data
 *  trio: the flat, variable-stride point table, unpacked. */
public record PointsData(List<StrokePoint> points, Set<StrokeChannel> presentChannels, int stride) {
	public static PointsData decode(StrokeData data) {
		int count = data.getPointsCount();
		if (count == 0 || !data.hasPointsData()) {
			return new PointsData(List.of(), Set.of(), 8);
		}

		int flags = data.getPointsFlags();
		// EnumSet's iteration order is StrokeChannel's declaration/ordinal order,
		// which StrokeChannel's own static initializer guarantees matches ascending
		// bitIndex order - that's what makes iterating `present` below (rather than
		// StrokeChannel.values()) reproduce points_data's actual per-point byte
		// packing order. See StrokeChannel's class doc for why order is load-bearing.
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
