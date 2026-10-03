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
 *   00  00  04  f32 < | x
 *       04  04  f32 < | y
 *   01  08  04  f32 < | timestamp
 *   02  12  04  f32 < | radius
 *   03  16  02  u16 < | aspectRatio*
 *   04  18  02  u16 < | edgeWidth*
 *   05  20  02  u16 < | force*
 *   06  22  02  u16 < | azimuth*
 *   07  24  02  u16 < | altitude*
 *   08  26  02  u16 < | opacity*
 *   09  28  04  f32 < | radius2
 *   10  32  02  u16 < | threshold*
 */

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumSet;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Decoder for StrokeData's {@code pointsData}.
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
	private final float[] channelX;
	private final float[] channelY;
	private final float[] channelTimestamp;
	private final float[] channelRadius;
	private final short[] channelAspectRatio;
	private final short[] channelEdgeWidth;
	private final short[] channelForce;
	private final short[] channelAzimuth;
	private final short[] channelAltitude;
	private final short[] channelOpacity;
	private final float[] channelRadius2;
	private final short[] channelThreshold;

	private PointsData(Builder data)
	{
		this.channelLength      = data.channelLength;
		this.channelX           = data.channelX;
		this.channelY           = data.channelY;
		this.channelTimestamp   = data.channelTimestamp;
		this.channelRadius      = data.channelRadius;
		this.channelAspectRatio = data.channelAspectRatio;
		this.channelEdgeWidth   = data.channelEdgeWidth;
		this.channelForce       = data.channelForce;
		this.channelAzimuth     = data.channelAzimuth;
		this.channelAltitude    = data.channelAltitude;
		this.channelOpacity     = data.channelOpacity;
		this.channelRadius2     = data.channelRadius2;
		this.channelThreshold   = data.channelThreshold;
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
		private float[] channelX;
		private float[] channelY;
		private float[] channelTimestamp;
		private float[] channelRadius;
		private short[] channelAspectRatio;
		private short[] channelEdgeWidth;
		private short[] channelForce;
		private short[] channelAzimuth;
		private short[] channelAltitude;
		private short[] channelOpacity;
		private float[] channelRadius2;
		private short[] channelThreshold;

		/**
		 * Creates a builder for the given number of points.
		 *
		 * @param length the number of points to be added
		 * @param set    the optional channels to allocate; location
		 *               is always allocated and need not be included
		 */
		public Builder(int length, Set<StrokeChannel> set)
		{
			channelLength = length;
			channelX = new float[length];
			channelY = new float[length];
			if (set.contains(StrokeChannel.TIMESTAMP)) {
				channelTimestamp = new float[length];
			}
			if (set.contains(StrokeChannel.RADIUS)) {
				channelRadius = new float[length];
			}
			if (set.contains(StrokeChannel.ASPECT_RATIO)) {
				channelAspectRatio = new short[length];
			}
			if (set.contains(StrokeChannel.EDGE_WIDTH)) {
				channelEdgeWidth = new short[length];
			}
			if (set.contains(StrokeChannel.FORCE)) {
				channelForce = new short[length];
			}
			if (set.contains(StrokeChannel.AZIMUTH)) {
				channelAzimuth = new short[length];
			}
			if (set.contains(StrokeChannel.ALTITUDE)) {
				channelAltitude = new short[length];
			}
			if (set.contains(StrokeChannel.OPACITY)) {
				channelOpacity = new short[length];
			}
			if (set.contains(StrokeChannel.RADIUS2)) {
				channelRadius2 = new float[length];
			}
			if (set.contains(StrokeChannel.THRESHOLD)) {
				channelThreshold = new short[length];
			}
		}

		/**
		 * Builds the {@link PointsData}.
		 *
		 * @return the finished data
		 * @throws IllegalArgumentException if fewer points were added
		 *         than the length given to the constructor
		 */
		public PointsData build()
		{
			if (index < channelLength - 1) {
				throw new IllegalArgumentException("Insufficient points (added "
					+ index + " but expected " + channelLength + ")");
			}
			/* Note: index >= channelLength cannot happen. */
			return new PointsData(this);
		}

		/**
		 * Advances to the next point and sets its location.
		 *
		 * @param x the x coordinate
		 * @param y the y coordinate
		 * @return this builder
		 */
		public Builder addPoint(float x, float y)
		{
			++index;
			channelX[index] = x;
			channelY[index] = y;
			return this;
		}

		/**
		 * Sets the {@code timestamp} channel of the current point
		 * (4-byte {@code f32}).
		 *
		 * @param value the raw value
		 * @return this builder
		 * @throws IllegalArgumentException if the channel was not
		 *         requested in the constructor
		 */
		public Builder setTimestamp(float value)
		{
			if (channelTimestamp == null) {
				throw new IllegalArgumentException("Channel timestamp absent");
			}
			channelTimestamp[index] = value;
			return this;
		}

		/**
		 * Sets the {@code radius} channel of the current point
		 * (4-byte {@code f32}).
		 *
		 * @param value the raw value
		 * @return this builder
		 * @throws IllegalArgumentException if the channel was not
		 *         requested in the constructor
		 */
		public Builder setRadius(float value)
		{
			if (channelRadius == null) {
				throw new IllegalArgumentException("Channel radius absent");
			}
			channelRadius[index] = value;
			return this;
		}

		/**
		 * Sets the {@code aspectRatio} channel of the current point
		 * (2-byte {@code u16}, subject to re-scaling).
		 *
		 * @param value the raw value
		 * @return this builder
		 * @throws IllegalArgumentException if the channel was not
		 *         requested in the constructor
		 */
		public Builder setAspectRatio(short value)
		{
			if (channelAspectRatio == null) {
				throw new IllegalArgumentException("Channel aspect_ratio absent");
			}
			channelAspectRatio[index] = value;
			return this;
		}

		/**
		 * Sets the {@code edgeWidth} channel of the current point
		 * (2-byte {@code u16}, subject to re-scaling).
		 *
		 * @param value the raw value
		 * @return this builder
		 * @throws IllegalArgumentException if the channel was not
		 *         requested in the constructor
		 */
		public Builder setEdgeWidth(short value)
		{
			if (channelEdgeWidth == null) {
				throw new IllegalArgumentException("Channel edge_width absent");
			}
			channelEdgeWidth[index] = value;
			return this;
		}

		/**
		 * Sets the {@code force} channel of the current point
		 * (2-byte {@code u16}, subject to re-scaling).
		 *
		 * @param value the raw value
		 * @return this builder
		 * @throws IllegalArgumentException if the channel was not
		 *         requested in the constructor
		 */
		public Builder setForce(short value)
		{
			if (channelForce == null) {
				throw new IllegalArgumentException("Channel force absent");
			}
			channelForce[index] = value;
			return this;
		}

		/**
		 * Sets the {@code azimuth} channel of the current point
		 * (2-byte {@code u16}, subject to re-scaling).
		 *
		 * @param value the raw value
		 * @return this builder
		 * @throws IllegalArgumentException if the channel was not
		 *         requested in the constructor
		 */
		public Builder setAzimuth(short value)
		{
			if (channelAzimuth == null) {
				throw new IllegalArgumentException("Channel azimuth absent");
			}
			channelAzimuth[index] = value;
			return this;
		}

		/**
		 * Sets the {@code altitude} channel of the current point
		 * (2-byte {@code u16}, subject to re-scaling).
		 *
		 * @param value the raw value
		 * @return this builder
		 * @throws IllegalArgumentException if the channel was not
		 *         requested in the constructor
		 */
		public Builder setAltitude(short value)
		{
			if (channelAltitude == null) {
				throw new IllegalArgumentException("Channel altitude absent");
			}
			channelAltitude[index] = value;
			return this;
		}

		/**
		 * Sets the {@code opacity} channel of the current point
		 * (2-byte {@code u16}, subject to re-scaling).
		 *
		 * @param value the raw value
		 * @return this builder
		 * @throws IllegalArgumentException if the channel was not
		 *         requested in the constructor
		 */
		public Builder setOpacity(short value)
		{
			if (channelOpacity == null) {
				throw new IllegalArgumentException("Channel opacity absent");
			}
			channelOpacity[index] = value;
			return this;
		}

		/**
		 * Sets the {@code radius2} channel of the current point
		 * (4-byte {@code f32}).
		 *
		 * @param value the raw value
		 * @return this builder
		 * @throws IllegalArgumentException if the channel was not
		 *         requested in the constructor
		 */
		public Builder setRadius2(float value)
		{
			if (channelRadius2 == null) {
				throw new IllegalArgumentException("Channel radius2 absent");
			}
			channelRadius2[index] = value;
			return this;
		}

		/**
		 * Sets the {@code threshold} channel of the current point
		 * (2-byte {@code u16}, subject to re-scaling).
		 *
		 * @param value the raw value
		 * @return this builder
		 * @throws IllegalArgumentException if the channel was not
		 *         requested in the constructor
		 */
		public Builder setThreshold(short value)
		{
			if (channelThreshold == null) {
				throw new IllegalArgumentException("Channel threshold absent");
			}
			channelThreshold[index] = value;
			return this;
		}
	}

	/**
	 * Returns the number of points.
	 *
	 * @return the number of points
	 */
	public int length()
	{
		return channelLength;
	}

	/**
	 * Returns the {@code x} of the point at {@code index}
	 * (4-byte {@code f32}).
	 *
	 * @param index the zero-based point index
	 * @return the {@code x}
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of range
	 */
	public float x(int index)
	{
		return channelX[index];
	}

	/**
	 * Returns the {@code y} of the point at {@code index}
	 * (4-byte {@code f32}).
	 *
	 * @param index the zero-based point index
	 * @return the {@code y}
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of range
	 */
	public float y(int index)
	{
		return channelY[index];
	}

	/**
	 * Returns whether the {@code timestamp} channel is present.
	 *
	 * @return {@code true} if {@link #timestamp(int)} may be called
	 */
	public boolean hasTimestamp()
	{
		return channelTimestamp != null;
	}

	/**
	 * Returns the {@code timestamp} of the point at {@code index}
	 * (4-byte {@code f32}).
	 *
	 * @param index the zero-based point index
	 * @return the {@code timestamp}
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of range
	 * @throws NoSuchElementException if the channel is absent (see
	 *         {@link #hasTimestamp()})
	 */
	public float timestamp(int index)
	{
		if (channelTimestamp == null) {
			throw new NoSuchElementException("Channel timestamp absent");
		}
		return channelTimestamp[index];
	}

	/**
	 * Returns whether the {@code radius} channel is present.
	 *
	 * @return {@code true} if {@link #radius(int)} may be called
	 */
	public boolean hasRadius()
	{
		return channelRadius != null;
	}

	/**
	 * Returns the {@code radius} of the point at {@code index}
	 * (4-byte {@code f32}).
	 *
	 * @param index the zero-based point index
	 * @return the {@code radius}
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of range
	 * @throws NoSuchElementException if the channel is absent (see
	 *         {@link #hasRadius()})
	 */
	public float radius(int index)
	{
		if (channelRadius == null) {
			throw new NoSuchElementException("Channel radius absent");
		}
		return channelRadius[index];
	}

	/**
	 * Returns whether the {@code aspectRatio} channel is present.
	 *
	 * @return {@code true} if {@link #aspectRatio(int)} may be called
	 */
	public boolean hasAspectRatio()
	{
		return channelAspectRatio != null;
	}

	/**
	 * Returns the {@code aspectRatio} of the point at {@code index}
	 * (2-byte {@code u16}, subject to re-scaling).
	 * The value is returned as an unsigned integer.
	 *
	 * @param index the zero-based point index
	 * @return the {@code aspectRatio}
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of range
	 * @throws NoSuchElementException if the channel is absent (see
	 *         {@link #hasAspectRatio()})
	 */
	public int aspectRatio(int index)
	{
		if (channelAspectRatio == null) {
			throw new NoSuchElementException("Channel aspect_ratio absent");
		}
		return Short.toUnsignedInt(channelAspectRatio[index]);
	}

	/**
	 * Returns whether the {@code edgeWidth} channel is present.
	 *
	 * @return {@code true} if {@link #edgeWidth(int)} may be called
	 */
	public boolean hasEdgeWidth()
	{
		return channelEdgeWidth != null;
	}

	/**
	 * Returns the {@code edgeWidth} of the point at {@code index}
	 * (2-byte {@code u16}, subject to re-scaling).
	 * The value is returned as an unsigned integer.
	 *
	 * @param index the zero-based point index
	 * @return the {@code edgeWidth}
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of range
	 * @throws NoSuchElementException if the channel is absent (see
	 *         {@link #hasEdgeWidth()})
	 */
	public int edgeWidth(int index)
	{
		if (channelEdgeWidth == null) {
			throw new NoSuchElementException("Channel edge_width absent");
		}
		return Short.toUnsignedInt(channelEdgeWidth[index]);
	}

	/**
	 * Returns whether the {@code force} channel is present.
	 *
	 * @return {@code true} if {@link #force(int)} may be called
	 */
	public boolean hasForce()
	{
		return channelForce != null;
	}

	/**
	 * Returns the {@code force} of the point at {@code index}
	 * (2-byte {@code u16}, subject to re-scaling).
	 * The value is returned as an unsigned integer.
	 *
	 * @param index the zero-based point index
	 * @return the {@code force}
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of range
	 * @throws NoSuchElementException if the channel is absent (see
	 *         {@link #hasForce()})
	 */
	public int force(int index)
	{
		if (channelForce == null) {
			throw new NoSuchElementException("Channel force absent");
		}
		return Short.toUnsignedInt(channelForce[index]);
	}

	/**
	 * Returns whether the {@code azimuth} channel is present.
	 *
	 * @return {@code true} if {@link #azimuth(int)} may be called
	 */
	public boolean hasAzimuth()
	{
		return channelAzimuth != null;
	}

	/**
	 * Returns the {@code azimuth} of the point at {@code index}
	 * (2-byte {@code u16}, subject to re-scaling).
	 * The value is returned as an unsigned integer.
	 *
	 * @param index the zero-based point index
	 * @return the {@code azimuth}
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of range
	 * @throws NoSuchElementException if the channel is absent (see
	 *         {@link #hasAzimuth()})
	 */
	public int azimuth(int index)
	{
		if (channelAzimuth == null) {
			throw new NoSuchElementException("Channel azimuth absent");
		}
		return Short.toUnsignedInt(channelAzimuth[index]);
	}

	/**
	 * Returns whether the {@code altitude} channel is present.
	 *
	 * @return {@code true} if {@link #altitude(int)} may be called
	 */
	public boolean hasAltitude()
	{
		return channelAltitude != null;
	}

	/**
	 * Returns the {@code altitude} of the point at {@code index}
	 * (2-byte {@code u16}, subject to re-scaling).
	 * The value is returned as an unsigned integer.
	 *
	 * @param index the zero-based point index
	 * @return the {@code altitude}
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of range
	 * @throws NoSuchElementException if the channel is absent (see
	 *         {@link #hasAltitude()})
	 */
	public int altitude(int index)
	{
		if (channelAltitude == null) {
			throw new NoSuchElementException("Channel altitude absent");
		}
		return Short.toUnsignedInt(channelAltitude[index]);
	}

	/**
	 * Returns whether the {@code opacity} channel is present.
	 *
	 * @return {@code true} if {@link #opacity(int)} may be called
	 */
	public boolean hasOpacity()
	{
		return channelOpacity != null;
	}

	/**
	 * Returns the {@code opacity} of the point at {@code index}
	 * (2-byte {@code u16}, subject to re-scaling).
	 * The value is returned as an unsigned integer.
	 *
	 * @param index the zero-based point index
	 * @return the {@code opacity}
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of range
	 * @throws NoSuchElementException if the channel is absent (see
	 *         {@link #hasOpacity()})
	 */
	public int opacity(int index)
	{
		if (channelOpacity == null) {
			throw new NoSuchElementException("Channel opacity absent");
		}
		return Short.toUnsignedInt(channelOpacity[index]);
	}

	/**
	 * Returns whether the {@code radius2} channel is present.
	 *
	 * @return {@code true} if {@link #radius2(int)} may be called
	 */
	public boolean hasRadius2()
	{
		return channelRadius2 != null;
	}

	/**
	 * Returns the {@code radius2} of the point at {@code index}
	 * (4-byte {@code f32}).
	 *
	 * @param index the zero-based point index
	 * @return the {@code radius2}
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of range
	 * @throws NoSuchElementException if the channel is absent (see
	 *         {@link #hasRadius2()})
	 */
	public float radius2(int index)
	{
		if (channelRadius2 == null) {
			throw new NoSuchElementException("Channel radius2 absent");
		}
		return channelRadius2[index];
	}

	/**
	 * Returns whether the {@code threshold} channel is present.
	 *
	 * @return {@code true} if {@link #threshold(int)} may be called
	 */
	public boolean hasThreshold()
	{
		return channelThreshold != null;
	}

	/**
	 * Returns the {@code threshold} of the point at {@code index}
	 * (2-byte {@code u16}, subject to re-scaling).
	 * The value is returned as an unsigned integer.
	 *
	 * @param index the zero-based point index
	 * @return the {@code threshold}
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of range
	 * @throws NoSuchElementException if the channel is absent (see
	 *         {@link #hasThreshold()})
	 */
	public int threshold(int index)
	{
		if (channelThreshold == null) {
			throw new NoSuchElementException("Channel threshold absent");
		}
		return Short.toUnsignedInt(channelThreshold[index]);
	}

	/**
	 * Decodes the points of a stroke.
	 *
	 * <p>The active channels are taken from {@code points_flags}, and the
	 * stride from their widths.
	 *
	 * @param data the stroke
	 * @return the decoded points
	 * @throws StrokeDataFormatException if the length of {@code points_data}
	 *         does not match {@code points_count} times the stride
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
		if ( 0 != (flags & ( 1 << 1 )) ) {
			set.add(StrokeChannel.TIMESTAMP);
			stride += 4;
		}
		if ( 0 != (flags & ( 1 << 2 )) ) {
			set.add(StrokeChannel.RADIUS);
			stride += 4;
		}
		if ( 0 != (flags & ( 1 << 3 )) ) {
			set.add(StrokeChannel.ASPECT_RATIO);
			stride += 2;
		}
		if ( 0 != (flags & ( 1 << 4 )) ) {
			set.add(StrokeChannel.EDGE_WIDTH);
			stride += 2;
		}
		if ( 0 != (flags & ( 1 << 5 )) ) {
			set.add(StrokeChannel.FORCE);
			stride += 2;
		}
		if ( 0 != (flags & ( 1 << 6 )) ) {
			set.add(StrokeChannel.AZIMUTH);
			stride += 2;
		}
		if ( 0 != (flags & ( 1 << 7 )) ) {
			set.add(StrokeChannel.ALTITUDE);
			stride += 2;
		}
		if ( 0 != (flags & ( 1 << 8 )) ) {
			set.add(StrokeChannel.OPACITY);
			stride += 2;
		}
		if ( 0 != (flags & ( 1 << 9 )) ) {
			set.add(StrokeChannel.RADIUS2);
			stride += 4;
		}
		if ( 0 != (flags & ( 1 << 10 )) ) {
			set.add(StrokeChannel.THRESHOLD);
			stride += 2;
		}

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
				case TIMESTAMP:
					out.setTimestamp(reader.getFloat());
					break;
				case RADIUS:
					out.setRadius(reader.getFloat());
					break;
				case ASPECT_RATIO:
					out.setAspectRatio(reader.getShort());
					break;
				case EDGE_WIDTH:
					out.setEdgeWidth(reader.getShort());
					break;
				case FORCE:
					out.setForce(reader.getShort());
					break;
				case AZIMUTH:
					out.setAzimuth(reader.getShort());
					break;
				case ALTITUDE:
					out.setAltitude(reader.getShort());
					break;
				case OPACITY:
					out.setOpacity(reader.getShort());
					break;
				case RADIUS2:
					out.setRadius2(reader.getFloat());
					break;
				case THRESHOLD:
					out.setThreshold(reader.getShort());
					break;
				}
			}
		}
		return out.build();
	}
}
