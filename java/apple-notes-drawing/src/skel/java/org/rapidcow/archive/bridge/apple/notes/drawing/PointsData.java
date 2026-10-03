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
import java.util.EnumSet;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Decoder for StrokeData's {@code points_data}.
 *
 * <p>The data is stored column-wise: each channel is an array holding
 * one value per point.  The location channels ({@code x} and {@code y})
 * are always present; every other channel is optional and may be queried
 * with its corresponding {@code has*()} method before being read.
 * Instances are created with {@link #decode(StrokeData)} or through
 * a {@link Builder}.
 *
 * @see StrokeChannel
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
	 *
	 * <p>Points are added one at a time with {@link #addPoint(float, float)},
	 * and the optional channels of that point are filled in with the
	 * {@code set*()} methods <em>before</em> the next point is added.
	 */
	public static final class Builder
	{
		private final int channelLength;
		private int index = -1;
[% FOREACH chan IN StrokeChannel.fields -%]
		private [% chan.java_type %][] channel[% chan.name.ucfirst %];
[% END -%]

		/**
		 * Creates a builder for the given number of points.
		 *
		 * @param length the number of points expected to be added
		 * @param set  the optional channels to allocate; location
		 *    is always allocated and need not be included
		 */
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

		/**
		 * Builds the {@link PointsData}.
		 *
		 * @return the finished data
		 * @throws IllegalStateException if fewer points were added
		 *    than the total number of points expected to be added
		 */
		public PointsData build()
		{
			if (index < channelLength - 1) {
				throw new IllegalStateException("Insufficient points (added "
					+ index + " but expected " + channelLength + ")");
			}
			/* Note: index >= channelLength cannot happen. */
			return new PointsData(this);
		}

		/**
		 * Advances to the next point and sets its location.
		 *
		 * @param x the <var>x</var>-coordinate
		 * @param y the <var>y</var>-coordinate
		 * @return this builder
		 * @throws IllegalStateException if more points were added
		 *    than the total number of points expected to be added
		 */
		public Builder addPoint(float x, float y)
		{
			/* Keep this check side-effect free. */
			if (index >= channelLength - 1) {
				throw new IllegalStateException("Too many points");
			}
			++index;
			channelX[index] = x;
			channelY[index] = y;
			return this;
		}
[% FOREACH chan IN StrokeChannel.channels -%]

		/**
		 * Sets the {@code [% chan.name.snake %]} channel for the current point.
		 *
		 * @param value the raw value
		 * @return this builder
		 * @throws IllegalStateException if no point has been added yet,
		 *    or the channel was not requested in the constructor
		 */
		public Builder set[% chan.name.ucfirst %]([% chan.java_type %] value)
		{
			if (index < 0) {
				throw new IllegalStateException("No point added yet");
			}
			if (channel[% chan.name.ucfirst %] == null) {
				throw new IllegalStateException("Channel [% chan.name.snake %] absent");
			}
			channel[% chan.name.ucfirst %][index] = value;
			return this;
		}
[% END -%]
	}

	/**
	 * {@return the number of points}
	 */
	public int length()
	{
		return channelLength;
	}
[%#
  # This bit is a bit repetitive, but it's for Eclipse IDE, which
  # doesn't seem to handle markup within {@return ...} well. :<
  #-%]
[% FOREACH chan IN StrokeChannel.fields -%]
[%   IF chan.bit -%]

	/**
	 * Returns whether the {@code [% chan.name.snake %]} channel is present.
	 * True if and only if {@link #[% chan.name %](int)} may be called.
	 *
	 * @return whether the {@code [% chan.name.snake %]} channel is present
	 */
	public boolean has[% chan.name.ucfirst %]()
	{
		return channel[% chan.name.ucfirst %] != null;
	}
[%   END -%]

	/**
	 * Returns the {@code [% chan.name.snake %]} field of the point at {@code index}.
	 * This field is of type {@code [% chan.type %]} ([% chan.width %] byte[% "s" UNLESS chan.width == 1 %]
[%-  IF chan.rescale %], unscaled[% END %]).
	 *
	 * @param index the zero-based point index
	 * @return the {@code [% chan.name.snake %]} field
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
[%   IF chan.bit -%]
	 * @throws NoSuchElementException if the channel is absent
	 * @see #has[% chan.name.ucfirst %]()
[%   END -%]
	 */
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

	/**
	 * Decodes the points of a stroke.
	 *
	 * <p>The active channels are taken from {@code points_flags},
	 * and the stride from their widths.
	 *
	 * @param data the stroke
	 * @return the decoded points
	 * @throws StrokeDataFormatException if the length of {@code points_data}
	 *    does not match {@code points_count} times the stride
	 */
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
