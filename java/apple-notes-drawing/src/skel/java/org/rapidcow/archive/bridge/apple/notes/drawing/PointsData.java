[% USE Snake -%]
[% USE StrokeChannel -%]
/*
 * Copyright 2026 rapidcow!
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This is a file is generated!  You should edit
 * the source template in `src/skel/java/'. ;)
[%#
 * If you can read this, you are looking at the source! :D
 * This means you can edit and expect changes to persist.
 -%]
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
import org.apache.commons.numbers.fraction.Fraction;
import org.rapidcow.archive.bridge.apple.notes.proto.StrokeData;

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
	 * {@link PointsData} builder.
	 *
	 * <p>Points are added one at a time with {@link #addPoint(float, float)},
	 * and the optional channels of that point are filled in with the
	 * {@code setRaw*()} methods <em>before</em> the next point is added.
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
		 * @param set  the optional channels expected to be added
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
[% FOREACH chan IN StrokeChannel.channels -%]

		/**
		 * Sets the {@code [% chan.name.snake %]} channel for the current point.
		 *
		 * @param value the raw {@code [% chan.name.snake %]} value
		 * @return this builder
		 * @throws IllegalStateException if no point has been added yet
		 * @throws IllegalArgumentException if the [% chan.name.snake %]
		 *    channel was not expected; namely, if the enum
		 *    {@link StrokeChannel#[% chan.name.SNAKE %]} is
		 *    absent from the set passed to the constructor.
		 * @see PointsData#raw[% chan.name.ucfirst %](int)
[%   IF chan.name == "timestamp" -%]
		 * @see PointsData#timeOffset(int)
[%   ELSIF chan.name == "radius" || chan.name == "aspectRatio" -%]
		 * @see PointsData#width(int)
		 * @see PointsData#height(int)
[%   ELSIF chan.name == "radius2" -%]
		 * @see PointsData#secondaryScale(int)
[%   ELSIF chan.name != "elevation" -%]
		 * @see PointsData#[% chan.name %](int)
[%     IF chan.name == "edgeWidth" -%]
		 * @see PointsData#lateralJitter(int)
[%     END -%]
[%   END -%]
		 */
		public Builder setRaw[% chan.name.ucfirst %]([% chan.java_type %] value)
		{
			if (index < 0) {
				throw new IllegalStateException("No point");
			}
			if (channel[% chan.name.ucfirst %] == null) {
				throw new IllegalArgumentException("Channel [% chan.name.snake %] is absent");
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
	 * True if and only if {@link #raw[% chan.name.ucfirst %](int)} may be called.
	 *
	 * @return whether the {@code [% chan.name.snake %]} channel is present
	 */
	public boolean has[% chan.name.ucfirst %]()
	{
		return channel[% chan.name.ucfirst %] != null;
	}
[%   END -%]

	/**
	 * Returns the raw {@code [% chan.name.snake %]} field of the point at {@code index}.
	 * This field is of type {@code [% chan.type %] [% chan.endian %]} ([% chan.width %] byte[% "s" UNLESS chan.width == 1 %]
[%-  IF chan.rescale %], unscaled[% END %]).
	 *
	 * @param index the zero-based point index
	 * @return the raw {@code [% chan.name.snake %]} field
[%-  IF chan.type == "u16" %], widened to an {@code int}[% END %]
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
[%   IF chan.bit -%]
	 * @throws NoSuchElementException if the {@code [% chan.name.snake %]} channel is absent
	 * @see #has[% chan.name.ucfirst %]()
[%   END -%]
	 */
[%   IF chan.type == "u16" -%]
	public int raw[% chan.name.ucfirst %](int index)
[%   ELSE -%]
	public [% chan.java_type %] raw[% chan.name.ucfirst %](int index)
[%   END -%]
	{
[%   IF chan.bit -%]
		if (channel[% chan.name.ucfirst %] == null) {
			throw new NoSuchElementException("Channel [% chan.name.snake %] is absent");
		}
[%   END -%]
[%   IF chan.type == "u16" -%]
		return Short.toUnsignedInt(channel[% chan.name.ucfirst %][index]);
[%   ELSE -%]
		return channel[% chan.name.ucfirst %][index];
[%   END -%]
	}
[%# Accessor methods with correct scaling.
  # They mimic PencilKit on 64-bit systems, floats
  # are widened, and floating-point math is done...
  # (unless they are "exact" (which I will add in
  # a bit.))
  -%]
[%   IF chan.name == "x" || chan.name == "y" -%]

	/**
	 * Returns the <var>[% chan.name %]</var>-coordinate of the point at {@code index}.
	 * Derived from {@link #raw[% chan.name.ucfirst %](int)}.
	 *
	 * <p>This name is consistent with the eponymous {@code [% chan.name %]}
	 * member of the {@code PKStrokePoint} class’s {@code CGPoint location}
	 * public instance property added in iOS 14.0, macOS 11.0.
	 *
	 * @param index the zero-based point index
	 * @return the <var>[% chan.name %]</var>-coordinate
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @see <a href="https://developer.apple.com/documentation/pencilkit/pkstrokepointreference/location">-[PKStrokePoint location]</a>
	 * @see <a href="https://developer.apple.com/documentation/corefoundation/cgpoint/[% chan.name | xml %]">CGPoint.[% chan.name %]</a>
	 */
	public double [% chan.name %](int index)
	{
		return raw[% chan.name.ucfirst %](index);
	}
[%   ELSIF chan.name == "timestamp" -%]

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
[%   ELSIF chan.name == "radius" -%]

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
[%   ELSIF chan.name == "aspectRatio" -%]

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
	 * Returns the aspect ratio of the point at {@code index}
	 * as an exact fraction.  The value is in [0, 65.535].
	 *
	 * @param index the zero-based point index
	 * @return the aspect ratio
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code aspect_ratio} channel is absent
	 * @see #rawAspectRatio(int)
	 */
	public Fraction aspectRatioExact(int index)
	{
		return Fraction.of(rawAspectRatio(index), 1000);
	}
[%   ELSIF chan.name == "edgeWidth" -%]

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
	 * Returns the edge width of the point at {@code index}
	 * as an exact fraction.  The value is in [0, 6553.5].
	 *
	 * @param index the zero-based point index
	 * @return the edge width
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code edge_width} channel is absent
	 * @see #rawEdgeWidth(int)
	 */
	public Fraction edgeWidthExact(int index)
	{
		return Fraction.of(rawEdgeWidth(index), 10);
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
[%   ELSIF chan.name == "force" -%]

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
	 * Returns the force of the point at {@code index}
	 * as an exact fraction.  The value is in [0, 65.535].
	 *
	 * @param index the zero-based point index
	 * @return the force
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code force} channel is absent
	 * @see #rawForce(int)
	 */
	public Fraction forceExact(int index)
	{
		return Fraction.of(rawForce(index), 1000);
	}
[%   ELSIF chan.name == "azimuth" -%]

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
	 * Returns the azimuth of the point at {@code index}
	 * as a fraction of π.  The value is in [−1, 1].
	 *
	 * @param index the zero-based point index
	 * @return the azimuth in π radians
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code azimuth} channel is absent
	 * @see #rawAzimuth(int)
	 */
	public Fraction azimuthExact(int index)
	{
		return Fraction.of(2 * rawAzimuth(index) - 65535, 65535);
	}
[%   ELSIF chan.name == "altitude" -%]

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
	 * Returns the altitude of the point at {@code index}
	 * as a fraction of π/2.  The value is in [0, 1].
	 *
	 * @param index the zero-based point index
	 * @return the altitude in π/2 radians
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code altitude} channel is absent
	 * @see #rawAltitude(int)
	 */
	public Fraction altitudeExact(int index)
	{
		return Fraction.of(65535 - rawAltitude(index), 65535);
	}
[%   ELSIF chan.name == "opacity" || chan.name == "threshold" -%]

	/**
	 * Returns the [% chan.name %] of the point at {@code index}.
	 * Derived from {@link #raw[% chan.name.ucfirst %](int)} divided by
	 * 65535/2, giving a value in [0, 2].
	 *
	 * <p>This name is consistent with the {@code PKStrokePoint}
	 * class’s eponymous public instance property added in
[%-    IF chan.name == "threshold" %] iOS 26.0,
	 * macOS 26.0.
[%-    ELSE %] iOS 14.0,
	 * macOS 11.0.
[%-    END %]
	 *
	 * @param index the zero-based point index
	 * @return the [% chan.name %]
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code [% chan.name.snake %]} channel is absent
	 * @see <a href="https://developer.apple.com/documentation/pencilkit/pkstrokepointreference/[% chan.name %]">-[PKStrokePoint [% chan.name %]]</a>
	 */
	public double [% chan.name %](int index)
	{
		return 2.0 * raw[% chan.name.ucfirst %](index) / 65535.0;
	}

	/**
	 * Returns the [% chan.name %] of the point at {@code index}
	 * as an exact fraction.  The value is in [0, 2].
	 *
	 * @param index the zero-based point index
	 * @return the [% chan.name %]
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
	 * @throws NoSuchElementException if the {@code [% chan.name.snake %]} channel is absent
	 * @see #raw[% chan.name.ucfirst %](int)
	 */
	public Fraction [% chan.name %]Exact(int index)
	{
		return Fraction.of(2 * raw[% chan.name.ucfirst %](index), 65535);
	}
[%   ELSIF chan.name == "radius2" -%]

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
[%   ELSIF chan.name == "elevation" -%]
[%#    No public PencilKit API for this, so leave it raw only. -%]
[%   ELSIF not chan.rescale -%]

[%-    SET value = chan.name.snake.replace("_", " ") -%]

	/**
	 * Returns the [% value %] of the point at {@code index}.
	 * Derived from {@link #raw[% chan.name.ucfirst %](int)}.
	 *
	 * @param index the zero-based point index
	 * @return the [% value %]
	 * @throws ArrayIndexOutOfBoundsException if {@code index} is out of bounds
[%     IF chan.bit -%]
	 * @throws NoSuchElementException if the {@code [% chan.name.snake %]} channel is absent
[%     END -%]
	 */
[%     IF chan.type == "u16" -%]
	public int [% chan.name %](int index)
[%     ELSIF chan.type.match("^f") -%]
	public double [% chan.name %](int index)
[%     ELSE -%]
	public [% chan.java_type %] [% chan.name %](int index)
[%     END -%]
	{
		return raw[% chan.name.ucfirst %](index);
	}
[%   END -%]
[% END -%]

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
		int unknown = flags & ~[% StrokeChannel.mask | format("%#x") %];
		if (unknown != 0) {
			throw new StrokeDataFormatException(
				"Unknown points_flags bits 0x"
				+ Integer.toHexString(unknown)
				+ " (points_flags 0x"
				+ Integer.toHexString(flags) + ")");
		}

		/* widen stride based on optional channels... */
[% FOREACH chan IN StrokeChannel.channels -%]
		if ( 0 != (flags & ( 1 << [% chan.bit %] )) ) {
			set.add(StrokeChannel.[% chan.name.SNAKE %]);
			stride += [% chan.width %];
		}
[% END -%]

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
[% FOREACH chan IN StrokeChannel.channels -%]
				case [% chan.name.SNAKE %]:
					out.setRaw[% chan.name.ucfirst %](reader.get[% chan.java_type.ucfirst %]());
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
