package org.rapidcow.ai.archive.bridge.tophatch.concepts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A decoded Concepts drawing, in a representation shared by every backend.
 * Layers are flattened: strokes from all layers appear in file order.
 * Coordinates are canvas units with a bottom-left origin.
 *
 * @param manifest archive-level metadata
 * @param strokes ink strokes, in file order
 * @param skippedItems drawing items that are not strokes (images, text, ...)
 */
public record ConceptsDrawing(Manifest manifest, List<Stroke> strokes, int skippedItems) {
	public ConceptsDrawing {
		strokes = List.copyOf(strokes);
	}

	/**
	 * Archive contents and drawing-level metadata.
	 *
	 * @param entries every ZIP entry, in directory order
	 * @param identifier the drawing's own identifier
	 * @param created creation time
	 * @param modified last modification time
	 * @param formatVersion the app's declared format version, verbatim
	 */
	public record Manifest(
			List<Entry> entries,
			Optional<UUID> identifier,
			Optional<Instant> created,
			Optional<Instant> modified,
			Optional<String> formatVersion) {
		public Manifest {
			entries = List.copyOf(entries);
		}
	}

	/**
	 * A ZIP entry.
	 *
	 * @param name exact entry name
	 * @param size uncompressed size, or -1 if the directory does not say
	 */
	public record Entry(String name, long size) {
	}

	/**
	 * An ink stroke.
	 *
	 * @param identifier the stroke's identifier
	 * @param timestamp when the stroke was made
	 * @param tool the app's numeric tool identifier (uninterpreted)
	 * @param color brush color, before {@code opacity} is applied
	 * @param opacity separate brush alpha multiplier
	 * @param width brush width in canvas units, absent for some tools
	 * @param transform item transform applied to {@code points}
	 * @param closed whether the path is closed
	 * @param points untransformed point locations
	 */
	public record Stroke(
			Optional<UUID> identifier,
			Optional<Instant> timestamp,
			int tool,
			Color color,
			float opacity,
			Optional<Float> width,
			Transform transform,
			boolean closed,
			List<Point> points) {
		public Stroke {
			points = List.copyOf(points);
		}
	}

	/** A point location; pressure and angle channels are not decoded. */
	public record Point(float x, float y) {
	}

	/** RGBA components, nominally in 0..1. */
	public record Color(float red, float green, float blue, float alpha) {
	}

	/** A 2D affine transform: {@code x' = a*x + c*y + tx}, {@code y' = b*x + d*y + ty}. */
	public record Transform(float a, float b, float c, float d, float tx, float ty) {
		public static final Transform IDENTITY = new Transform(1, 0, 0, 1, 0, 0);

		public double x(double x, double y) {
			return a * x + c * y + tx;
		}

		public double y(double x, double y) {
			return b * x + d * y + ty;
		}
	}
}
