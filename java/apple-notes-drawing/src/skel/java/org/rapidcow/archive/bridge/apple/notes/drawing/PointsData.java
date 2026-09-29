[% USE Snake -%]
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
[% FOREACH chan IN StrokeChannel.fields -%]
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
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Decoder for StrokeData's {@code pointsData}.
 */
public class PointsData
{
	private final int     channelLength;
[% FOREACH chan IN StrokeChannel.fields -%]
	private final [% chan.java_type %][] channel[% chan.name.ucfirst %];
[% END -%]

	private PointsData(Builder data)
	{
		this.channelLength      = data.channelLength;
[% FOREACH chan IN StrokeChannel.fields -%]
		this.channel[% chan.name.ucfirst | format("%-11s") %] = data.channel[% chan.name.ucfirst %];
[% END -%]
	}

	/**
	 * {@link PointsData} Builder.
	 */
	public static final class Builder
	{
		private final int channelLength;
		private int index = -1;
[% FOREACH chan IN StrokeChannel.fields -%]
		private [% chan.java_type %][] channel[% chan.name.ucfirst %];
[% END -%]

		public Builder(int length, Set<StrokeChannel> set)
		{
			channelLength = length;
[% FOREACH chan IN StrokeChannel.fields -%]
[%   IF chan.bit == 0 -%]
			channel[% chan.name.ucfirst %] = new [% chan.java_type %][length];
[%   ELSE -%]
			if (set.contains(StrokeChannel.[% chan.name.SNAKE %])) {
				channel[% chan.name.ucfirst %] = new [% chan.java_type %][length];
			}
[%   END -%]
[% END -%]
		}

		public PointsData build()
		{
			if (index < channelLength - 1) {
				throw new IllegalArgumentException("Insufficient points (added "
					+ index + " but expected " + channelLength + ")");
			}
			/* Note: index >= channelLength cannot happen. */
			return new PointsData(this);
		}

		public Builder addPoint(float x, float y)
		{
			++index;
			channelX[index] = x;
			channelY[index] = y;
			return this;
		}
[% FOREACH chan IN StrokeChannel.channels -%]

		public Builder set[% chan.name.ucfirst %]([% chan.java_type %] value)
		{
			if (channel[% chan.name.ucfirst %] == null) {
				throw new IllegalArgumentException("Channel [% chan.name.snake %] absent");
			}
			channel[% chan.name.ucfirst %][index] = value;
			return this;
		}
[% END -%]
	}

	public int length()
	{
		return channelLength;
	}
[% FOREACH chan IN StrokeChannel.fields -%]

[%   IF chan.type == "u16" -%]
	public int [% chan.name %](int index)
[%   ELSE -%]
	public [% chan.java_type %] [% chan.name %](int index)
[%   END -%]
	{
[%   IF chan.bit -%]
		if (channel[% chan.name.ucfirst %] == null) {
			throw new NoSuchElementException("Channel [% chan.name.snake %] absent");
		}
[%   END -%]
[%   IF chan.type == "u16" -%]
		return Short.toUnsignedInt(channel[% chan.name.ucfirst %][index]);
[%   ELSE -%]
		return channel[% chan.name.ucfirst %][index];
[%   END -%]
	}
[% END -%]

	public static PointsData decode(StrokeData data)
	{
		int count = data.getPointsCount();
		int flags = data.getPointsFlags();
		byte[] buf = data.getPointsData().toByteArray();

		/* location (float x and y) is a mandatory channel. */
		int stride = 8;
		EnumSet<StrokeChannel> set = EnumSet.noneOf(StrokeChannel.class);

		/* path strictly taken when there is absolutely nothing to parse. */
		if (count == 0 && !data.hasPointsData()) {
			return new PointsData.Builder(count, set).build();
		}

		/* widen stride based on optional channels... */
[% FOREACH chan IN StrokeChannel.channels -%]
		if ( 0 != (flags & ( 1 << [% chan.bit %] )) ) {
			set.add(StrokeChannel.[% chan.name.SNAKE %]);
			stride += [% chan.width %];
		}
[% END -%]

		if (buf.length != count * stride) {
			throw new StrokeDataFormatException(
				"Bad points_data length " + buf.length
				+ " (expecting " + count + " * stride "
				+ stride + " bytes)");
		}

		ByteBuffer reader = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
		PointsData.Builder out = new PointsData.Builder(count, set);
		for (int i = 0; i < count; ++i) {
			float x = reader.getFloat();
			float y = reader.getFloat();
			out.addPoint(x, y);
			/*
			 * EnumSet<...> iterates over the active channels
			 * in declaration order which MATTERS!!!  This is
			 * so that it matches the order on the wire too.
			 */
			for (StrokeChannel chan : set) {
				switch (chan) {
[% FOREACH chan IN StrokeChannel.channels -%]
				case [% chan.name.SNAKE %]:
					out.set[% chan.name.ucfirst %](reader.get[% chan.java_type.ucfirst %]());
					break;
[% END -%]
				}
			}
		}
		return out.build();
	}
}
[% IF 0 -%]

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
[% END -%]
