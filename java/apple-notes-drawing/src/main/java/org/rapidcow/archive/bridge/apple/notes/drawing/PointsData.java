/*
 * This is a file is generated!  You should edit
 * the source template in `src/skel/java/'. ;)
 *
 * Run `make apple-notes-drawing' to re-generate.
 */
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
	 * {@code setRaw*()} methods <em>before</em> the next point is added.
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
				int added = index + 1;
				throw new IllegalStateException("Insufficient points (added "
					+ added + " but expected " + channelLength + ")");
			}
			/* Note: index >= channelLength cannot happen. */
			return new PointsData(this);
		}

		/**
		 * Advances to the next point and sets its location.
		 *
		 * @param x the raw <var>x</var>-coordinate
		 * @param y the raw <var>y</var>-coordinate
		 * @return this builder
		 * @throws IllegalStateException if more points were added
		 *    than the total number of points expected to be added
		 * @see PointsData#x(int)
		 * @see PointsData#y(int)
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
		 * @param value the raw {@code timestamp} value
		 * @return this builder
		 * @throws IllegalStateException if no point has been added yet
		 * @throws IllegalArgumentException if the timestamp
		 *    channel was not expected; namely, if the enum
		 *    {@link StrokeChannel#TIMESTAMP} is
		 *    absent from the set passed to the constructor.
		 * @see PointsData#rawTimestamp(int)
		 * @see PointsData#timeOffset(int)
		 */
		public Builder setRawTimestamp(float value)
		{
			if (index < 0) {
				throw new IllegalStateException("No point");
			}
			if (channelTimestamp == null) {
				throw new IllegalArgumentException("Channel timestamp is absent");
			}
			channelTimestamp[index] = value;
			return this;
		}

		/**
		 * Sets the {@code radius} channel for the current point.
		 *
		 * @param value the raw {@code radius} value
		 * @return this builder
		 * @throws IllegalStateException if no point has been added yet
		 * @throws IllegalArgumentException if the radius
		 *    channel was not expected; namely, if the enum
		 *    {@link StrokeChannel#RADIUS} is
		 *    absent from the set passed to the constructor.
		 * @see PointsData#rawRadius(int)
		 * @see PointsData#width(int)
		 * @see PointsData#height(int)
		 */
		public Builder setRawRadius(float value)
		{
			if (index < 0) {
				throw new IllegalStateException("No point");
			}
			if (channelRadius == null) {
				throw new IllegalArgumentException("Channel radius is absent");
			}
			channelRadius[index] = value;
			return this;
		}

		/**
		 * Sets the {@code aspect_ratio} channel for the current point.
		 *
		 * @param value the raw {@code aspect_ratio} value
		 * @return this builder
		 * @throws IllegalStateException if no point has been added yet
		 * @throws IllegalArgumentException if the aspect_ratio
		 *    channel was not expected; namely, if the enum
		 *    {@link StrokeChannel#ASPECT_RATIO} is
		 *    absent from the set passed to the constructor.
		 * @see PointsData#rawAspectRatio(int)
		 * @see PointsData#width(int)
		 * @see PointsData#height(int)
		 */
		public Builder setRawAspectRatio(short value)
		{
			if (index < 0) {
				throw new IllegalStateException("No point");
			}
			if (channelAspectRatio == null) {
				throw new IllegalArgumentException("Channel aspect_ratio is absent");
			}
			channelAspectRatio[index] = value;
			return this;
		}

		/**
		 * Sets the {@code edge_width} channel for the current point.
		 *
		 * @param value the raw {@code edge_width} value
		 * @return this builder
		 * @throws IllegalStateException if no point has been added yet
		 * @throws IllegalArgumentException if the edge_width
		 *    channel was not expected; namely, if the enum
		 *    {@link StrokeChannel#EDGE_WIDTH} is
		 *    absent from the set passed to the constructor.
		 * @see PointsData#rawEdgeWidth(int)
		 * @see PointsData#edgeWidth(int)
		 * @see PointsData#lateralJitter(int)
		 */
		public Builder setRawEdgeWidth(short value)
		{
			if (index < 0) {
				throw new IllegalStateException("No point");
			}
			if (channelEdgeWidth == null) {
				throw new IllegalArgumentException("Channel edge_width is absent");
			}
			channelEdgeWidth[index] = value;
			return this;
		}

		/**
		 * Sets the {@code force} channel for the current point.
		 *
		 * @param value the raw {@code force} value
		 * @return this builder
		 * @throws IllegalStateException if no point has been added yet
		 * @throws IllegalArgumentException if the force
		 *    channel was not expected; namely, if the enum
		 *    {@link StrokeChannel#FORCE} is
		 *    absent from the set passed to the constructor.
		 * @see PointsData#rawForce(int)
		 * @see PointsData#force(int)
		 */
		public Builder setRawForce(short value)
		{
			if (index < 0) {
				throw new IllegalStateException("No point");
			}
			if (channelForce == null) {
				throw new IllegalArgumentException("Channel force is absent");
			}
			channelForce[index] = value;
			return this;
		}

		/**
		 * Sets the {@code azimuth} channel for the current point.
		 *
		 * @param value the raw {@code azimuth} value
		 * @return this builder
		 * @throws IllegalStateException if no point has been added yet
		 * @throws IllegalArgumentException if the azimuth
		 *    channel was not expected; namely, if the enum
		 *    {@link StrokeChannel#AZIMUTH} is
		 *    absent from the set passed to the constructor.
		 * @see PointsData#rawAzimuth(int)
		 * @see PointsData#azimuth(int)
		 */
		public Builder setRawAzimuth(short value)
		{
			if (index < 0) {
				throw new IllegalStateException("No point");
			}
			if (channelAzimuth == null) {
				throw new IllegalArgumentException("Channel azimuth is absent");
			}
			channelAzimuth[index] = value;
			return this;
		}

		/**
		 * Sets the {@code altitude} channel for the current point.
		 *
		 * @param value the raw {@code altitude} value
		 * @return this builder
		 * @throws IllegalStateException if no point has been added yet
		 * @throws IllegalArgumentException if the altitude
		 *    channel was not expected; namely, if the enum
		 *    {@link StrokeChannel#ALTITUDE} is
		 *    absent from the set passed to the constructor.
		 * @see PointsData#rawAltitude(int)
		 * @see PointsData#altitude(int)
		 */
		public Builder setRawAltitude(short value)
		{
			if (index < 0) {
				throw new IllegalStateException("No point");
			}
			if (channelAltitude == null) {
				throw new IllegalArgumentException("Channel altitude is absent");
			}
			channelAltitude[index] = value;
			return this;
		}

		/**
		 * Sets the {@code opacity} channel for the current point.
		 *
		 * @param value the raw {@code opacity} value
		 * @return this builder
		 * @throws IllegalStateException if no point has been added yet
		 * @throws IllegalArgumentException if the opacity
		 *    channel was not expected; namely, if the enum
		 *    {@link StrokeChannel#OPACITY} is
		 *    absent from the set passed to the constructor.
		 * @see PointsData#rawOpacity(int)
		 * @see PointsData#opacity(int)
		 */
		public Builder setRawOpacity(short value)
		{
			if (index < 0) {
				throw new IllegalStateException("No point");
			}
			if (channelOpacity == null) {
				throw new IllegalArgumentException("Channel opacity is absent");
			}
			channelOpacity[index] = value;
			return this;
		}

		/**
		 * Sets the {@code radius2} channel for the current point.
		 *
		 * @param value the raw {@code radius2} value
		 * @return this builder
		 * @throws IllegalStateException if no point has been added yet
		 * @throws IllegalArgumentException if the radius2
		 *    channel was not expected; namely, if the enum
		 *    {@link StrokeChannel#RADIUS2} is
		 *    absent from the set passed to the constructor.
		 * @see PointsData#rawRadius2(int)
		 * @see PointsData#secondaryScale(int)
		 */
		public Builder setRawRadius2(float value)
		{
			if (index < 0) {
				throw new IllegalStateException("No point");
			}
			if (channelRadius2 == null) {
				throw new IllegalArgumentException("Channel radius2 is absent");
			}
			channelRadius2[index] = value;
			return this;
		}

		/**
		 * Sets the {@code threshold} channel for the current point.
		 *
		 * @param value the raw {@code threshold} value
		 * @return this builder
		 * @throws IllegalStateException if no point has been added yet
		 * @throws IllegalArgumentException if the threshold
		 *    channel was not expected; namely, if the enum
		 *    {@link StrokeChannel#THRESHOLD} is
		 *    absent from the set passed to the constructor.
		 * @see PointsData#rawThreshold(int)
		 * @see PointsData#threshold(int)
		 */
		public Builder setRawThreshold(short value)
		{
			if (index < 0) {
				throw new IllegalStateException("No point");
			}
			if (channelThreshold == null) {
				throw new IllegalArgumentException("Channel threshold is absent");
			}
			channelThreshold[index] = value;
			return this;
		}

		/**
		 * Sets the {@code elevation} channel for the current point.
		 *
		 * @param value the raw {@code elevation} value
		 * @return this builder
		 * @throws IllegalStateException if no point has been added yet
		 * @throws IllegalArgumentException if the elevation
		 *    channel was not expected; namely, if the enum
		 *    {@link StrokeChannel#ELEVATION} is
		 *    absent from the set passed to the constructor.
		 * @see PointsData#rawElevation(int)
		 */
		public Builder setRawElevation(float value)
		{
			if (index < 0) {
				throw new IllegalStateException("No point");
			}
			if (channelElevation == null) {
				throw new IllegalArgumentException("Channel elevation is absent");
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
	 * Returns the raw {@code x} field of the point at {@code index}.
	 * This field is of type {@code f32 <} (4 bytes).
	 *
	 * @param index the zero-based point index
	 * @return the raw {@code x} field
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 */
	public float rawX(int index)
	{
		return channelX[index];
	}

	/**
	 * Returns the <var>x</var>-coordinate of the point at {@code index}.
	 * Derived from {@link #rawX(int)}.
	 *
	 * <p>This name is consistent with the eponymous {@code x}
	 * member of the {@code PKStrokePoint} class’s {@code CGPoint location}
	 * public instance property added in iOS 14.0, macOS 11.0.
	 *
	 * @param index the zero-based point index
	 * @return the <var>x</var>-coordinate
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @see <a href="https://developer.apple.com/documentation/pencilkit/pkstrokepointreference/location">-[PKStrokePoint location]</a>
	 * @see <a href="https://developer.apple.com/documentation/corefoundation/cgpoint/x">CGPoint.x</a>
	 */
	public double x(int index)
	{
		return rawX(index);
	}

	/**
	 * Returns the raw {@code y} field of the point at {@code index}.
	 * This field is of type {@code f32 <} (4 bytes).
	 *
	 * @param index the zero-based point index
	 * @return the raw {@code y} field
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 */
	public float rawY(int index)
	{
		return channelY[index];
	}

	/**
	 * Returns the <var>y</var>-coordinate of the point at {@code index}.
	 * Derived from {@link #rawY(int)}.
	 *
	 * <p>This name is consistent with the eponymous {@code y}
	 * member of the {@code PKStrokePoint} class’s {@code CGPoint location}
	 * public instance property added in iOS 14.0, macOS 11.0.
	 *
	 * @param index the zero-based point index
	 * @return the <var>y</var>-coordinate
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @see <a href="https://developer.apple.com/documentation/pencilkit/pkstrokepointreference/location">-[PKStrokePoint location]</a>
	 * @see <a href="https://developer.apple.com/documentation/corefoundation/cgpoint/y">CGPoint.y</a>
	 */
	public double y(int index)
	{
		return rawY(index);
	}

	/**
	 * Returns whether the {@code timestamp} channel is present.
	 * True if and only if {@link #rawTimestamp(int)} may be called.
	 *
	 * @return whether the {@code timestamp} channel is present
	 */
	public boolean hasTimestamp()
	{
		return channelTimestamp != null;
	}

	/**
	 * Returns the raw {@code timestamp} field of the point at {@code index}.
	 * This field is of type {@code f32 <} (4 bytes).
	 *
	 * @param index the zero-based point index
	 * @return the raw {@code timestamp} field
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code timestamp} channel is absent
	 * @see #hasTimestamp()
	 */
	public float rawTimestamp(int index)
	{
		if (channelTimestamp == null) {
			throw new NoSuchElementException("Channel timestamp is absent");
		}
		return channelTimestamp[index];
	}

	/**
	 * Returns the time offset of the point at {@code index}
	 * in seconds.  Derived from {@link #rawTimestamp(int)}.
	 *
	 * <p>This name is consistent with the {@code PKStrokePoint}
	 * class’s eponymous public instance property added in iOS 14.0,
	 * macOS 11.0.
	 *
	 * @param index the zero-based point index
	 * @return the time offset in seconds
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code timestamp} channel is absent
	 * @see <a href="https://developer.apple.com/documentation/pencilkit/pkstrokepointreference/timeoffset">-[PKStrokePoint timeOffset]</a>
	 */
	public double timeOffset(int index)
	{
		return rawTimestamp(index);
	}

	/**
	 * Returns whether the {@code radius} channel is present.
	 * True if and only if {@link #rawRadius(int)} may be called.
	 *
	 * @return whether the {@code radius} channel is present
	 */
	public boolean hasRadius()
	{
		return channelRadius != null;
	}

	/**
	 * Returns the raw {@code radius} field of the point at {@code index}.
	 * This field is of type {@code f32 <} (4 bytes).
	 *
	 * @param index the zero-based point index
	 * @return the raw {@code radius} field
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code radius} channel is absent
	 * @see #hasRadius()
	 */
	public float rawRadius(int index)
	{
		if (channelRadius == null) {
			throw new NoSuchElementException("Channel radius is absent");
		}
		return channelRadius[index];
	}

	/**
	 * Returns the width of the point’s size at {@code index}.
	 * Derived from {@link #rawRadius(int)}.
	 *
	 * <p>This name is consistent with the eponymous {@code width}
	 * member of the {@code PKStrokePoint} class’s {@code CGSize size}
	 * public instance property added in iOS 14.0, macOS 11.0.
	 *
	 * @param index the zero-based point index
	 * @return the point width
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code radius} channel is absent
	 * @see #height(int)
	 * @see <a href="https://developer.apple.com/documentation/pencilkit/pkstrokepointreference/size">-[PKStrokePoint size]</a>
	 * @see <a href="https://developer.apple.com/documentation/corefoundation/cgsize/width">CGSize.width</a>
	 */
	public double width(int index)
	{
		return rawRadius(index);
	}

	/**
	 * Returns the height of the point’s size at {@code index}.
	 * Derived from the product of {@link #width(int)} and
	 * {@link #aspectRatio(int)}.
	 *
	 * <p>This name is consistent with the eponymous {@code height}
	 * member of the {@code PKStrokePoint} class’s {@code CGSize size}
	 * public instance property added in iOS 14.0, macOS 11.0.
	 *
	 * @param index the zero-based point index
	 * @return the point height
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code radius}
	 *    or the {@code aspect_ratio} channel is absent
	 * @see <a href="https://developer.apple.com/documentation/pencilkit/pkstrokepointreference/size">-[PKStrokePoint size]</a>
	 * @see <a href="https://developer.apple.com/documentation/corefoundation/cgsize/height">CGSize.height</a>
	 */
	public double height(int index)
	{
		return width(index) * aspectRatio(index);
	}

	/**
	 * Returns whether the {@code aspect_ratio} channel is present.
	 * True if and only if {@link #rawAspectRatio(int)} may be called.
	 *
	 * @return whether the {@code aspect_ratio} channel is present
	 */
	public boolean hasAspectRatio()
	{
		return channelAspectRatio != null;
	}

	/**
	 * Returns the raw {@code aspect_ratio} field of the point at {@code index}.
	 * This field is of type {@code u16 <} (2 bytes, unscaled).
	 *
	 * @param index the zero-based point index
	 * @return the raw {@code aspect_ratio} field, widened to an {@code int}
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code aspect_ratio} channel is absent
	 * @see #hasAspectRatio()
	 */
	public int rawAspectRatio(int index)
	{
		if (channelAspectRatio == null) {
			throw new NoSuchElementException("Channel aspect_ratio is absent");
		}
		return Short.toUnsignedInt(channelAspectRatio[index]);
	}

	/**
	 * Returns the aspect ratio of the point’s size at {@code index}.
	 * Derived from {@link #rawAspectRatio(int)} divided by 1000.0.
	 *
	 * <p>It is noteworthy that the aspect ratio is defined as
	 * height over width, not the other way around.
	 *
	 * @param index the zero-based point index
	 * @return the aspect ratio
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code aspect_ratio} channel is absent
	 * @see #width(int)
	 * @see #height(int)
	 */
	public double aspectRatio(int index)
	{
		return (double) rawAspectRatio(index) / 1000.0;
	}

	/**
	 * Returns whether the {@code edge_width} channel is present.
	 * True if and only if {@link #rawEdgeWidth(int)} may be called.
	 *
	 * @return whether the {@code edge_width} channel is present
	 */
	public boolean hasEdgeWidth()
	{
		return channelEdgeWidth != null;
	}

	/**
	 * Returns the raw {@code edge_width} field of the point at {@code index}.
	 * This field is of type {@code u16 <} (2 bytes, unscaled).
	 *
	 * @param index the zero-based point index
	 * @return the raw {@code edge_width} field, widened to an {@code int}
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code edge_width} channel is absent
	 * @see #hasEdgeWidth()
	 */
	public int rawEdgeWidth(int index)
	{
		if (channelEdgeWidth == null) {
			throw new NoSuchElementException("Channel edge_width is absent");
		}
		return Short.toUnsignedInt(channelEdgeWidth[index]);
	}

	/**
	 * Returns the edge width of the point at {@code index}.
	 * Derived from {@link #rawEdgeWidth(int)} divided by 10.0.
	 *
	 * @param index the zero-based point index
	 * @return the edge width
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code edge_width} channel is absent
	 * @see #lateralJitter(int)
	 */
	public double edgeWidth(int index)
	{
		return (double) rawEdgeWidth(index) / 10.0;
	}

	/**
	 * Returns the lateral jitter of the point at {@code index}.
	 * Alias of {@link #edgeWidth(int)}.
	 *
	 * <p>This name is consistent with the {@code PKStrokePoint}
	 * class’s eponymous public instance property added in iOS 27.0,
	 * macOS 27.0.
	 *
	 * @param index the zero-based point index
	 * @return the lateral jitter
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code edge_width} channel is absent
	 * @see <a href="https://developer.apple.com/documentation/pencilkit/pkstrokepointreference/lateraljitter">-[PKStrokePoint lateralJitter]</a>
	 */
	public double lateralJitter(int index)
	{
		return edgeWidth(index);
	}

	/**
	 * Returns whether the {@code force} channel is present.
	 * True if and only if {@link #rawForce(int)} may be called.
	 *
	 * @return whether the {@code force} channel is present
	 */
	public boolean hasForce()
	{
		return channelForce != null;
	}

	/**
	 * Returns the raw {@code force} field of the point at {@code index}.
	 * This field is of type {@code u16 <} (2 bytes, unscaled).
	 *
	 * @param index the zero-based point index
	 * @return the raw {@code force} field, widened to an {@code int}
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code force} channel is absent
	 * @see #hasForce()
	 */
	public int rawForce(int index)
	{
		if (channelForce == null) {
			throw new NoSuchElementException("Channel force is absent");
		}
		return Short.toUnsignedInt(channelForce[index]);
	}

	/**
	 * Returns the force of the point at {@code index}.
	 * Derived from {@link #rawForce(int)} divided by 1000.0.
	 *
	 * <p>This name is consistent with the {@code PKStrokePoint}
	 * class’s eponymous public instance property added in iOS 14.0,
	 * macOS 11.0.
	 *
	 * @param index the zero-based point index
	 * @return the force
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code force} channel is absent
	 * @see <a href="https://developer.apple.com/documentation/pencilkit/pkstrokepointreference/force">-[PKStrokePoint force]</a>
	 */
	public double force(int index)
	{
		return (double) rawForce(index) / 1000.0;
	}

	/**
	 * Returns whether the {@code azimuth} channel is present.
	 * True if and only if {@link #rawAzimuth(int)} may be called.
	 *
	 * @return whether the {@code azimuth} channel is present
	 */
	public boolean hasAzimuth()
	{
		return channelAzimuth != null;
	}

	/**
	 * Returns the raw {@code azimuth} field of the point at {@code index}.
	 * This field is of type {@code u16 <} (2 bytes, unscaled).
	 *
	 * @param index the zero-based point index
	 * @return the raw {@code azimuth} field, widened to an {@code int}
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code azimuth} channel is absent
	 * @see #hasAzimuth()
	 */
	public int rawAzimuth(int index)
	{
		if (channelAzimuth == null) {
			throw new NoSuchElementException("Channel azimuth is absent");
		}
		return Short.toUnsignedInt(channelAzimuth[index]);
	}

	/**
	 * Returns the azimuth of the point at {@code index}.
	 * Derived from {@link #rawAzimuth(int)} scaled by 2π/65535,
	 * then offset by −π, giving a value in [−π, π].
	 *
	 * <p>An azimuth of 0 points along +<var>x</var> (right),
	 * and π/2 along +<var>y</var> (down).
	 *
	 * <p>This name is consistent with the {@code PKStrokePoint}
	 * class’s eponymous public instance property added in iOS 14.0,
	 * macOS 11.0.
	 *
	 * @param index the zero-based point index
	 * @return the azimuth in radians
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code azimuth} channel is absent
	 * @see <a href="https://developer.apple.com/documentation/pencilkit/pkstrokepointreference/azimuth">-[PKStrokePoint azimuth]</a>
	 */
	public double azimuth(int index)
	{
		return (double) rawAzimuth(index) * (2.0 * Math.PI / 65535.0) - Math.PI;
	}

	/**
	 * Returns whether the {@code altitude} channel is present.
	 * True if and only if {@link #rawAltitude(int)} may be called.
	 *
	 * @return whether the {@code altitude} channel is present
	 */
	public boolean hasAltitude()
	{
		return channelAltitude != null;
	}

	/**
	 * Returns the raw {@code altitude} field of the point at {@code index}.
	 * This field is of type {@code u16 <} (2 bytes, unscaled).
	 *
	 * @param index the zero-based point index
	 * @return the raw {@code altitude} field, widened to an {@code int}
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code altitude} channel is absent
	 * @see #hasAltitude()
	 */
	public int rawAltitude(int index)
	{
		if (channelAltitude == null) {
			throw new NoSuchElementException("Channel altitude is absent");
		}
		return Short.toUnsignedInt(channelAltitude[index]);
	}

	/**
	 * Returns the altitude of the point at {@code index}.
	 * Derived from π/2 minus {@link #rawAltitude(int)} scaled
	 * by (π/2)/65535, giving a value in [0, π/2].
	 *
	 * <p>An altitude of 0 means the pen lies flat, and π/2
	 * means it stands upright.
	 *
	 * <p>This name is consistent with the {@code PKStrokePoint}
	 * class’s eponymous public instance property added in iOS 14.0,
	 * macOS 11.0.
	 *
	 * @param index the zero-based point index
	 * @return the altitude in radians
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code altitude} channel is absent
	 * @see <a href="https://developer.apple.com/documentation/pencilkit/pkstrokepointreference/altitude">-[PKStrokePoint altitude]</a>
	 */
	public double altitude(int index)
	{
		return Math.PI / 2.0 - (double) rawAltitude(index) * (Math.PI / 2.0 / 65535.0);
	}

	/**
	 * Returns whether the {@code opacity} channel is present.
	 * True if and only if {@link #rawOpacity(int)} may be called.
	 *
	 * @return whether the {@code opacity} channel is present
	 */
	public boolean hasOpacity()
	{
		return channelOpacity != null;
	}

	/**
	 * Returns the raw {@code opacity} field of the point at {@code index}.
	 * This field is of type {@code u16 <} (2 bytes, unscaled).
	 *
	 * @param index the zero-based point index
	 * @return the raw {@code opacity} field, widened to an {@code int}
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code opacity} channel is absent
	 * @see #hasOpacity()
	 */
	public int rawOpacity(int index)
	{
		if (channelOpacity == null) {
			throw new NoSuchElementException("Channel opacity is absent");
		}
		return Short.toUnsignedInt(channelOpacity[index]);
	}

	/**
	 * Returns the opacity of the point at {@code index}.
	 * Derived from {@link #rawOpacity(int)} divided by
	 * 65535/2, giving a value in [0, 2].
	 *
	 * <p>This name is consistent with the {@code PKStrokePoint}
	 * class’s eponymous public instance property added in iOS 14.0,
	 * macOS 11.0.
	 *
	 * @param index the zero-based point index
	 * @return the opacity
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code opacity} channel is absent
	 * @see <a href="https://developer.apple.com/documentation/pencilkit/pkstrokepointreference/opacity">-[PKStrokePoint opacity]</a>
	 */
	public double opacity(int index)
	{
		return 2.0 * rawOpacity(index) / 65535.0;
	}

	/**
	 * Returns whether the {@code radius2} channel is present.
	 * True if and only if {@link #rawRadius2(int)} may be called.
	 *
	 * @return whether the {@code radius2} channel is present
	 */
	public boolean hasRadius2()
	{
		return channelRadius2 != null;
	}

	/**
	 * Returns the raw {@code radius2} field of the point at {@code index}.
	 * This field is of type {@code f32 <} (4 bytes).
	 *
	 * @param index the zero-based point index
	 * @return the raw {@code radius2} field
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code radius2} channel is absent
	 * @see #hasRadius2()
	 */
	public float rawRadius2(int index)
	{
		if (channelRadius2 == null) {
			throw new NoSuchElementException("Channel radius2 is absent");
		}
		return channelRadius2[index];
	}

	/**
	 * Returns the secondary scale of the point at {@code index}.
	 * Derived from the quotient of {@link #rawRadius2(int)} and
	 * {@link #rawRadius(int)}.
	 *
	 * <p>This name is consistent with the {@code PKStrokePoint}
	 * class’s eponymous public instance property added in iOS 17.0,
	 * macOS 14.0.
	 *
	 * @param index the zero-based point index
	 * @return the secondary scale
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code radius2}
	 *    or the {@code radius} channel is absent
	 * @see <a href="https://developer.apple.com/documentation/pencilkit/pkstrokepointreference/secondaryscale">-[PKStrokePoint secondaryScale]</a>
	 */
	public double secondaryScale(int index)
	{
		return (double) rawRadius2(index) / rawRadius(index);
	}

	/**
	 * Returns whether the {@code threshold} channel is present.
	 * True if and only if {@link #rawThreshold(int)} may be called.
	 *
	 * @return whether the {@code threshold} channel is present
	 */
	public boolean hasThreshold()
	{
		return channelThreshold != null;
	}

	/**
	 * Returns the raw {@code threshold} field of the point at {@code index}.
	 * This field is of type {@code u16 <} (2 bytes, unscaled).
	 *
	 * @param index the zero-based point index
	 * @return the raw {@code threshold} field, widened to an {@code int}
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code threshold} channel is absent
	 * @see #hasThreshold()
	 */
	public int rawThreshold(int index)
	{
		if (channelThreshold == null) {
			throw new NoSuchElementException("Channel threshold is absent");
		}
		return Short.toUnsignedInt(channelThreshold[index]);
	}

	/**
	 * Returns the threshold of the point at {@code index}.
	 * Derived from {@link #rawThreshold(int)} divided by
	 * 65535/2, giving a value in [0, 2].
	 *
	 * <p>This name is consistent with the {@code PKStrokePoint}
	 * class’s eponymous public instance property added in iOS 26.0,
	 * macOS 26.0.
	 *
	 * @param index the zero-based point index
	 * @return the threshold
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code threshold} channel is absent
	 * @see <a href="https://developer.apple.com/documentation/pencilkit/pkstrokepointreference/threshold">-[PKStrokePoint threshold]</a>
	 */
	public double threshold(int index)
	{
		return 2.0 * rawThreshold(index) / 65535.0;
	}

	/**
	 * Returns whether the {@code elevation} channel is present.
	 * True if and only if {@link #rawElevation(int)} may be called.
	 *
	 * @return whether the {@code elevation} channel is present
	 */
	public boolean hasElevation()
	{
		return channelElevation != null;
	}

	/**
	 * Returns the raw {@code elevation} field of the point at {@code index}.
	 * This field is of type {@code f32 <} (4 bytes).
	 *
	 * @param index the zero-based point index
	 * @return the raw {@code elevation} field
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code elevation} channel is absent
	 * @see #hasElevation()
	 */
	public float rawElevation(int index)
	{
		if (channelElevation == null) {
			throw new NoSuchElementException("Channel elevation is absent");
		}
		return channelElevation[index];
	}

	/**
	 * Decodes the points of a stroke.
	 *
	 * <p>The active channels are taken from {@code points_flags},
	 * and the computed stride from their widths.
	 *
	 * @param data the stroke
	 * @return the decoded points
	 * @throws StrokeDataFormatException if there exists at least
	 *    one point and {@code points_flags} contains unknown
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
		int unknown = flags & ~0xfff;
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
					out.setRawTimestamp(reader.getFloat());
					break;
				case RADIUS:
					out.setRawRadius(reader.getFloat());
					break;
				case ASPECT_RATIO:
					out.setRawAspectRatio(reader.getShort());
					break;
				case EDGE_WIDTH:
					out.setRawEdgeWidth(reader.getShort());
					break;
				case FORCE:
					out.setRawForce(reader.getShort());
					break;
				case AZIMUTH:
					out.setRawAzimuth(reader.getShort());
					break;
				case ALTITUDE:
					out.setRawAltitude(reader.getShort());
					break;
				case OPACITY:
					out.setRawOpacity(reader.getShort());
					break;
				case RADIUS2:
					out.setRawRadius2(reader.getFloat());
					break;
				case THRESHOLD:
					out.setRawThreshold(reader.getShort());
					break;
				case ELEVATION:
					out.setRawElevation(reader.getFloat());
					break;
				}
			}
		}
		return out.build();
	}
}
