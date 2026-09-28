package org.rapidcow.archive.bridge.apple.notes.drawing;

import org.apache.commons.numbers.fraction.Fraction;

/**
 * One decoded channel's value, permanently tagged with which channel it
 * came from. Each subtype exposes only the conversion that's valid for
 * its own channel - there's no shared "divide by N" entry point a raw
 * value from the wrong channel could be passed into. Formulas from
 * apple-notes-drawing-2.proto's channel table.
 */
public sealed interface ChannelReading {
	StrokeChannel channel();

	/** timestamp/radius/radius2 - already IEEE-exact float32, no scaling
	 *  formula exists for these, so there's nothing to gate. */
	record DirectFloat(StrokeChannel channel, float value) implements ChannelReading {}

	record AspectRatio(int raw) implements ChannelReading {
		public StrokeChannel channel() { return StrokeChannel.ASPECT_RATIO; }
		public Fraction value() { return Fraction.of(raw, 1000); }
	}

	record EdgeWidth(int raw) implements ChannelReading {
		public StrokeChannel channel() { return StrokeChannel.EDGE_WIDTH; }
		public Fraction value() { return Fraction.of(raw, 10); }
	}

	record Force(int raw) implements ChannelReading {
		public StrokeChannel channel() { return StrokeChannel.FORCE; }
		public Fraction value() { return Fraction.of(raw, 1000); }
	}

	record Azimuth(int raw) implements ChannelReading {
		public StrokeChannel channel() { return StrokeChannel.AZIMUTH; }
		/** turns of PI: 0=+x, 1/2=+y, +-1=-x, -1/2=-y (PencilKit's on-screen convention) */
		public Fraction turns() { return Fraction.of(2 * raw - 65535, 65535); }
		public double radians() { return turns().doubleValue() * Math.PI; }
	}

	record Altitude(int raw) implements ChannelReading {
		public StrokeChannel channel() { return StrokeChannel.ALTITUDE; }
		public Fraction turns() { return Fraction.of(65535 - raw, 131070); }
		public double radians() { return turns().doubleValue() * Math.PI; }
	}

	record Opacity(int raw) implements ChannelReading {
		public StrokeChannel channel() { return StrokeChannel.OPACITY; }
		public Fraction value() { return Fraction.of(2 * raw, 65535); }
	}

	record Threshold(int raw) implements ChannelReading {
		public StrokeChannel channel() { return StrokeChannel.THRESHOLD; }
		public Fraction value() { return Fraction.of(2 * raw, 65535); }
	}
}
