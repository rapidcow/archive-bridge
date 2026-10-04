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
 *   11  36  04  f32 < | elevation
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
	private final float[] channelElevation;

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
		this.channelElevation   = data.channelElevation;
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
		private float[] channelElevation;

		/**
		 * Creates a builder for the given number of points.
		 *
		 * @param length the number of points expected to be added
		 * @param set  the optional channels expected to be added
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
			if (set.contains(StrokeChannel.ELEVATION)) {
				channelElevation = new float[length];
			}
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

		/**
		 * Sets the {@code timestamp} channel for the current point.
		 *
		 * @param value the raw value
		 * @return this builder
		 * @throws IllegalStateException if no point has been added yet
		 * @throws IllegalArgumentException if the timestamp
		 *    channel was not expected; namely, if the enum
		 *    {@link StrokeChannel#TIMESTAMP} is
		 *    absent from the set passed to the constructor.
		 */
		public Builder setTimestamp(float value)
		{
			if (index < 0) {
				throw new IllegalStateException("No point added yet");
			}
			if (channelTimestamp == null) {
				throw new IllegalArgumentException("Channel timestamp absent");
			}
			channelTimestamp[index] = value;
			return this;
		}

		/**
		 * Sets the {@code radius} channel for the current point.
		 *
		 * @param value the raw value
		 * @return this builder
		 * @throws IllegalStateException if no point has been added yet
		 * @throws IllegalArgumentException if the radius
		 *    channel was not expected; namely, if the enum
		 *    {@link StrokeChannel#RADIUS} is
		 *    absent from the set passed to the constructor.
		 */
		public Builder setRadius(float value)
		{
			if (index < 0) {
				throw new IllegalStateException("No point added yet");
			}
			if (channelRadius == null) {
				throw new IllegalArgumentException("Channel radius absent");
			}
			channelRadius[index] = value;
			return this;
		}

		/**
		 * Sets the {@code aspect_ratio} channel for the current point.
		 *
		 * @param value the raw value
		 * @return this builder
		 * @throws IllegalStateException if no point has been added yet
		 * @throws IllegalArgumentException if the aspect_ratio
		 *    channel was not expected; namely, if the enum
		 *    {@link StrokeChannel#ASPECT_RATIO} is
		 *    absent from the set passed to the constructor.
		 */
		public Builder setAspectRatio(short value)
		{
			if (index < 0) {
				throw new IllegalStateException("No point added yet");
			}
			if (channelAspectRatio == null) {
				throw new IllegalArgumentException("Channel aspect_ratio absent");
			}
			channelAspectRatio[index] = value;
			return this;
		}

		/**
		 * Sets the {@code edge_width} channel for the current point.
		 *
		 * @param value the raw value
		 * @return this builder
		 * @throws IllegalStateException if no point has been added yet
		 * @throws IllegalArgumentException if the edge_width
		 *    channel was not expected; namely, if the enum
		 *    {@link StrokeChannel#EDGE_WIDTH} is
		 *    absent from the set passed to the constructor.
		 */
		public Builder setEdgeWidth(short value)
		{
			if (index < 0) {
				throw new IllegalStateException("No point added yet");
			}
			if (channelEdgeWidth == null) {
				throw new IllegalArgumentException("Channel edge_width absent");
			}
			channelEdgeWidth[index] = value;
			return this;
		}

		/**
		 * Sets the {@code force} channel for the current point.
		 *
		 * @param value the raw value
		 * @return this builder
		 * @throws IllegalStateException if no point has been added yet
		 * @throws IllegalArgumentException if the force
		 *    channel was not expected; namely, if the enum
		 *    {@link StrokeChannel#FORCE} is
		 *    absent from the set passed to the constructor.
		 */
		public Builder setForce(short value)
		{
			if (index < 0) {
				throw new IllegalStateException("No point added yet");
			}
			if (channelForce == null) {
				throw new IllegalArgumentException("Channel force absent");
			}
			channelForce[index] = value;
			return this;
		}

		/**
		 * Sets the {@code azimuth} channel for the current point.
		 *
		 * @param value the raw value
		 * @return this builder
		 * @throws IllegalStateException if no point has been added yet
		 * @throws IllegalArgumentException if the azimuth
		 *    channel was not expected; namely, if the enum
		 *    {@link StrokeChannel#AZIMUTH} is
		 *    absent from the set passed to the constructor.
		 */
		public Builder setAzimuth(short value)
		{
			if (index < 0) {
				throw new IllegalStateException("No point added yet");
			}
			if (channelAzimuth == null) {
				throw new IllegalArgumentException("Channel azimuth absent");
			}
			channelAzimuth[index] = value;
			return this;
		}

		/**
		 * Sets the {@code altitude} channel for the current point.
		 *
		 * @param value the raw value
		 * @return this builder
		 * @throws IllegalStateException if no point has been added yet
		 * @throws IllegalArgumentException if the altitude
		 *    channel was not expected; namely, if the enum
		 *    {@link StrokeChannel#ALTITUDE} is
		 *    absent from the set passed to the constructor.
		 */
		public Builder setAltitude(short value)
		{
			if (index < 0) {
				throw new IllegalStateException("No point added yet");
			}
			if (channelAltitude == null) {
				throw new IllegalArgumentException("Channel altitude absent");
			}
			channelAltitude[index] = value;
			return this;
		}

		/**
		 * Sets the {@code opacity} channel for the current point.
		 *
		 * @param value the raw value
		 * @return this builder
		 * @throws IllegalStateException if no point has been added yet
		 * @throws IllegalArgumentException if the opacity
		 *    channel was not expected; namely, if the enum
		 *    {@link StrokeChannel#OPACITY} is
		 *    absent from the set passed to the constructor.
		 */
		public Builder setOpacity(short value)
		{
			if (index < 0) {
				throw new IllegalStateException("No point added yet");
			}
			if (channelOpacity == null) {
				throw new IllegalArgumentException("Channel opacity absent");
			}
			channelOpacity[index] = value;
			return this;
		}

		/**
		 * Sets the {@code radius2} channel for the current point.
		 *
		 * @param value the raw value
		 * @return this builder
		 * @throws IllegalStateException if no point has been added yet
		 * @throws IllegalArgumentException if the radius2
		 *    channel was not expected; namely, if the enum
		 *    {@link StrokeChannel#RADIUS2} is
		 *    absent from the set passed to the constructor.
		 */
		public Builder setRadius2(float value)
		{
			if (index < 0) {
				throw new IllegalStateException("No point added yet");
			}
			if (channelRadius2 == null) {
				throw new IllegalArgumentException("Channel radius2 absent");
			}
			channelRadius2[index] = value;
			return this;
		}

		/**
		 * Sets the {@code threshold} channel for the current point.
		 *
		 * @param value the raw value
		 * @return this builder
		 * @throws IllegalStateException if no point has been added yet
		 * @throws IllegalArgumentException if the threshold
		 *    channel was not expected; namely, if the enum
		 *    {@link StrokeChannel#THRESHOLD} is
		 *    absent from the set passed to the constructor.
		 */
		public Builder setThreshold(short value)
		{
			if (index < 0) {
				throw new IllegalStateException("No point added yet");
			}
			if (channelThreshold == null) {
				throw new IllegalArgumentException("Channel threshold absent");
			}
			channelThreshold[index] = value;
			return this;
		}

		/**
		 * Sets the {@code elevation} channel for the current point.
		 *
		 * @param value the raw value
		 * @return this builder
		 * @throws IllegalStateException if no point has been added yet
		 * @throws IllegalArgumentException if the elevation
		 *    channel was not expected; namely, if the enum
		 *    {@link StrokeChannel#ELEVATION} is
		 *    absent from the set passed to the constructor.
		 */
		public Builder setElevation(float value)
		{
			if (index < 0) {
				throw new IllegalStateException("No point added yet");
			}
			if (channelElevation == null) {
				throw new IllegalArgumentException("Channel elevation absent");
			}
			channelElevation[index] = value;
			return this;
		}
	}

	/**
	 * {@return the number of points}
	 */
	public int length()
	{
		return channelLength;
	}

	/**
	 * Returns the {@code x} field of the point at {@code index}.
	 * This field is of type {@code f32 <} (4 bytes).
	 *
	 * @param index the zero-based point index
	 * @return the {@code x} field
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 */
	public float x(int index)
	{
		return channelX[index];
	}

	/**
	 * Returns the {@code y} field of the point at {@code index}.
	 * This field is of type {@code f32 <} (4 bytes).
	 *
	 * @param index the zero-based point index
	 * @return the {@code y} field
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 */
	public float y(int index)
	{
		return channelY[index];
	}

	/**
	 * Returns whether the {@code timestamp} channel is present.
	 * True if and only if {@link #timestamp(int)} may be called.
	 *
	 * @return whether the {@code timestamp} channel is present
	 */
	public boolean hasTimestamp()
	{
		return channelTimestamp != null;
	}

	/**
	 * Returns the {@code timestamp} field of the point at {@code index}.
	 * This field is of type {@code f32 <} (4 bytes).
	 *
	 * @param index the zero-based point index
	 * @return the {@code timestamp} field
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code timestamp} channel is absent
	 * @see #hasTimestamp()
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
	 * True if and only if {@link #radius(int)} may be called.
	 *
	 * @return whether the {@code radius} channel is present
	 */
	public boolean hasRadius()
	{
		return channelRadius != null;
	}

	/**
	 * Returns the {@code radius} field of the point at {@code index}.
	 * This field is of type {@code f32 <} (4 bytes).
	 *
	 * @param index the zero-based point index
	 * @return the {@code radius} field
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code radius} channel is absent
	 * @see #hasRadius()
	 */
	public float radius(int index)
	{
		if (channelRadius == null) {
			throw new NoSuchElementException("Channel radius absent");
		}
		return channelRadius[index];
	}

	/**
	 * Returns whether the {@code aspect_ratio} channel is present.
	 * True if and only if {@link #aspectRatio(int)} may be called.
	 *
	 * @return whether the {@code aspect_ratio} channel is present
	 */
	public boolean hasAspectRatio()
	{
		return channelAspectRatio != null;
	}

	/**
	 * Returns the {@code aspect_ratio} field of the point at {@code index}.
	 * This field is of type {@code u16 <} (2 bytes, unscaled).
	 *
	 * @param index the zero-based point index
	 * @return the {@code aspect_ratio} field
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code aspect_ratio} channel is absent
	 * @see #hasAspectRatio()
	 */
	public int aspectRatio(int index)
	{
		if (channelAspectRatio == null) {
			throw new NoSuchElementException("Channel aspect_ratio absent");
		}
		return Short.toUnsignedInt(channelAspectRatio[index]);
	}

	/**
	 * Returns whether the {@code edge_width} channel is present.
	 * True if and only if {@link #edgeWidth(int)} may be called.
	 *
	 * @return whether the {@code edge_width} channel is present
	 */
	public boolean hasEdgeWidth()
	{
		return channelEdgeWidth != null;
	}

	/**
	 * Returns the {@code edge_width} field of the point at {@code index}.
	 * This field is of type {@code u16 <} (2 bytes, unscaled).
	 *
	 * @param index the zero-based point index
	 * @return the {@code edge_width} field
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code edge_width} channel is absent
	 * @see #hasEdgeWidth()
	 */
	public int edgeWidth(int index)
	{
		if (channelEdgeWidth == null) {
			throw new NoSuchElementException("Channel edge_width absent");
		}
		return Short.toUnsignedInt(channelEdgeWidth[index]);
	}

	/**
	 * Returns the lateral jitter of the point at {@code index}.
	 * Derived from {@link #edgeWidth(int)} by dividing by {@code 10.0}.
	 *
	 * <p>This name is consistent with the {@code PKStrokePoint}
	 * class’s eponymous public attribute added in iOS 27.0,
	 * MacOS 27.0.
	 *
	 * @param index the zero-based point index
	 * @return the lateral jitter
	 * @throws NoSuchElementException if the {@code edge_width} channel is absent
	 * @see <a href="https://developer.apple.com/documentation/pencilkit/pkstrokepointreference/lateraljitter">-[PKStrokePoint lateralJitter]</a>
	 */
	public double lateralJitter(int index)
	{
		return (double) edgeWidth(index) / 10.0;
	}

	/**
	 * Returns whether the {@code force} channel is present.
	 * True if and only if {@link #force(int)} may be called.
	 *
	 * @return whether the {@code force} channel is present
	 */
	public boolean hasForce()
	{
		return channelForce != null;
	}

	/**
	 * Returns the {@code force} field of the point at {@code index}.
	 * This field is of type {@code u16 <} (2 bytes, unscaled).
	 *
	 * @param index the zero-based point index
	 * @return the {@code force} field
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code force} channel is absent
	 * @see #hasForce()
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
	 * True if and only if {@link #azimuth(int)} may be called.
	 *
	 * @return whether the {@code azimuth} channel is present
	 */
	public boolean hasAzimuth()
	{
		return channelAzimuth != null;
	}

	/**
	 * Returns the {@code azimuth} field of the point at {@code index}.
	 * This field is of type {@code u16 <} (2 bytes, unscaled).
	 *
	 * @param index the zero-based point index
	 * @return the {@code azimuth} field
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code azimuth} channel is absent
	 * @see #hasAzimuth()
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
	 * True if and only if {@link #altitude(int)} may be called.
	 *
	 * @return whether the {@code altitude} channel is present
	 */
	public boolean hasAltitude()
	{
		return channelAltitude != null;
	}

	/**
	 * Returns the {@code altitude} field of the point at {@code index}.
	 * This field is of type {@code u16 <} (2 bytes, unscaled).
	 *
	 * @param index the zero-based point index
	 * @return the {@code altitude} field
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code altitude} channel is absent
	 * @see #hasAltitude()
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
	 * True if and only if {@link #opacity(int)} may be called.
	 *
	 * @return whether the {@code opacity} channel is present
	 */
	public boolean hasOpacity()
	{
		return channelOpacity != null;
	}

	/**
	 * Returns the {@code opacity} field of the point at {@code index}.
	 * This field is of type {@code u16 <} (2 bytes, unscaled).
	 *
	 * @param index the zero-based point index
	 * @return the {@code opacity} field
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code opacity} channel is absent
	 * @see #hasOpacity()
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
	 * True if and only if {@link #radius2(int)} may be called.
	 *
	 * @return whether the {@code radius2} channel is present
	 */
	public boolean hasRadius2()
	{
		return channelRadius2 != null;
	}

	/**
	 * Returns the {@code radius2} field of the point at {@code index}.
	 * This field is of type {@code f32 <} (4 bytes).
	 *
	 * @param index the zero-based point index
	 * @return the {@code radius2} field
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code radius2} channel is absent
	 * @see #hasRadius2()
	 */
	public float radius2(int index)
	{
		if (channelRadius2 == null) {
			throw new NoSuchElementException("Channel radius2 absent");
		}
		return channelRadius2[index];
	}

	/**
	 * Returns the secondary scale of the point at {@code index}.
	 * Derived from the quotient of {@link #radius2(int)} and
	 * {@link #radius(int)}.
	 *
	 * <p>This name is consistent with the {@code PKStrokePoint}
	 * class’s eponymous public attribute added in iOS 17.0,
	 * MacOS 14.0.
	 *
	 * @param index the zero-based point index
	 * @return the secondary scale
	 * @throws NoSuchElementException if the {@code radius2}
	 *    or the {@code radius} channel is absent
	 * @see <a href="https://developer.apple.com/documentation/pencilkit/pkstrokepointreference/secondaryscale">-[PKStrokePoint secondaryScale]</a>
	 */
	public double secondaryScale(int index)
	{
		return (double) radius2(index) / (double) radius(index);
	}

	/**
	 * Returns whether the {@code threshold} channel is present.
	 * True if and only if {@link #threshold(int)} may be called.
	 *
	 * @return whether the {@code threshold} channel is present
	 */
	public boolean hasThreshold()
	{
		return channelThreshold != null;
	}

	/**
	 * Returns the {@code threshold} field of the point at {@code index}.
	 * This field is of type {@code u16 <} (2 bytes, unscaled).
	 *
	 * @param index the zero-based point index
	 * @return the {@code threshold} field
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code threshold} channel is absent
	 * @see #hasThreshold()
	 */
	public int threshold(int index)
	{
		if (channelThreshold == null) {
			throw new NoSuchElementException("Channel threshold absent");
		}
		return Short.toUnsignedInt(channelThreshold[index]);
	}

	/**
	 * Returns whether the {@code elevation} channel is present.
	 * True if and only if {@link #elevation(int)} may be called.
	 *
	 * @return whether the {@code elevation} channel is present
	 */
	public boolean hasElevation()
	{
		return channelElevation != null;
	}

	/**
	 * Returns the {@code elevation} field of the point at {@code index}.
	 * This field is of type {@code f32 <} (4 bytes).
	 *
	 * @param index the zero-based point index
	 * @return the {@code elevation} field
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code elevation} channel is absent
	 * @see #hasElevation()
	 */
	public float elevation(int index)
	{
		if (channelElevation == null) {
			throw new NoSuchElementException("Channel elevation absent");
		}
		return channelElevation[index];
	}

	/** Every {@code points_flags} bit this decoder understands. */
	private static final int KNOWN_POINTS_FLAGS = 0xfff;

	/**
	 * Decodes the points of a stroke.
	 *
	 * <p>The active channels are taken from {@code points_flags},
	 * and the computed stride from their widths.
	 *
	 * @param data the stroke
	 * @return the decoded points
	 * @throws StrokeDataFormatException if there exists at least
	 *    one point and {@code points_flags} contains unknonwn
	 *    high bits, or if the length of {@code points_data} does
	 *    not match {@code points_count} times the computed stride
	 */
	public static PointsData decode(StrokeData data)
	{
		int count = data.getPointsCount();
		int flags = data.getPointsFlags();
		byte[] buf;  /* points_data (read later) */

		/* location (float x and y) is a mandatory channel. */
		int stride = 8;
		EnumSet<StrokeChannel> set = EnumSet.noneOf(StrokeChannel.class);

		/* path strictly taken when there is absolutely nothing to parse. */
		if (count == 0 && !data.hasPointsData()) {
			return new PointsData.Builder(count, set).build();
		}

		/* Even if we by some miracle get around the stride * count
		 * == length check, it'd still be sketchy overlooking some
		 * fields.  Error shouldn't pass silently (PEP 20).
		 *
		 * It doesn't hurt if the points are empty though, so we
		 * gave that a generous exception above!
		 */
		int unknown = flags & ~KNOWN_POINTS_FLAGS;
		if (unknown != 0) {
			throw new StrokeDataFormatException(
				"Unknown points_flags bits 0x"
				+ Integer.toHexString(unknown)
				+ " (points_flags 0x"
				+ Integer.toHexString(flags) + ")");
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
		if ( 0 != (flags & ( 1 << 11 )) ) {
			set.add(StrokeChannel.ELEVATION);
			stride += 4;
		}

		buf = data.getPointsData().toByteArray();
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
				case ELEVATION:
					out.setElevation(reader.getFloat());
					break;
				}
			}
		}
		return out.build();
	}
}
