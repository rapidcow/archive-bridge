package org.rapidcow.ai.archive.bridge.tophatch.concepts;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePackException;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.ArrayValue;
import org.msgpack.value.ExtensionValue;
import org.msgpack.value.Value;

/**
 * The {@code .concepts} flavor written by Concepts for Android and Windows:
 * {@code metadata.json} plus MessagePack {@code tree.pack}.
 *
 * <p>{@code tree.pack} is positional arrays throughout. The positions below
 * were checked against every stroke in 33 real archives (all
 * {@code tree.pack} version 1027, {@code formatVersion} 20250319), and the
 * decoder fails rather than guesses when a stroke does not match them:
 *
 * <pre>
 * tree   = [version, root]
 * root   = [.., .., .., .., layers, ..]            layers at 4
 * layer  = [kind, header, items, ..]
 * item   = [flags, [0, stroke]]                    other shapes are not strokes
 * stroke = [6, header, .., .., .., closed, .., .., .., .., .., .., .., .., .., .., .., points, ..]
 * header = [3, brush, id, .., .., .., time, transform, ..]
 * brush  = [.., [.., [.., [.., tool], color, opacity], .., width, ..]]
 * </pre>
 *
 * <p>{@code id} is ext 5 (16-byte big-endian UUID), {@code time} is Unix
 * seconds (kept to the microsecond), {@code transform} ext 7 and {@code color} ext 4 (little-endian
 * float32 matrix and RGBA). Each point is 16 bytes, starting with
 * little-endian float32 x and y.
 *
 * <p>Items whose {@code flags} vary (4, 6, 11, 4096 seen) all hold strokes;
 * the flags are not interpreted. Some tools (11 seen) leave {@code width} nil. Layers in {@code workspace.pack} are ignored.
 */
public final class MessagePackBackend implements ConceptsBackend {
	static final String TREE = "tree.pack";
	static final String METADATA = "metadata.json";

	private static final int LAYERS = 4;
	private static final int STROKE_KIND = 6;
	private static final int STROKE_HEADER = 1;
	private static final int STROKE_CLOSED = 5;
	private static final int STROKE_POINTS = 17;
	private static final int HEADER_BRUSH = 1;
	private static final int HEADER_ID = 2;
	private static final int HEADER_TIME = 6;
	private static final int HEADER_TRANSFORM = 7;
	private static final int POINT_BYTES = 16;
	private static final float AFFINE_TOLERANCE = 1e-5f;

	private static final byte EXT_COLOR = 4;
	private static final byte EXT_UUID = 5;
	private static final byte EXT_TRANSFORM = 7;

	@Override
	public String name() {
		return "MessagePack";
	}

	@Override
	public boolean recognizes(ConceptsContainer container) {
		return container.has(TREE);
	}

	@Override
	public ConceptsDrawing read(ConceptsContainer container) throws IOException {
		Value tree = unpack(container.read(TREE));
		var strokes = new ArrayList<ConceptsDrawing.Stroke>();
		int skipped = 0;
		ArrayValue top = array(tree, 2, "tree");
		ArrayValue root = array(top.get(1), LAYERS + 1, "root");
		for (Value layerValue : array(root.get(LAYERS), 0, "layers")) {
			ArrayValue layer = array(layerValue, 3, "layer");
			for (Value item : array(layer.get(2), 0, "layer items")) {
				Optional<ArrayValue> stroke = strokeOf(item);
				if (stroke.isPresent()) {
					strokes.add(stroke(stroke.get()));
				} else {
					skipped++;
				}
			}
		}
		return new ConceptsDrawing(manifest(container), strokes, skipped);
	}

	private static ConceptsDrawing.Manifest manifest(ConceptsContainer container) throws IOException {
		if (!container.has(METADATA)) {
			return new ConceptsDrawing.Manifest(container.entries(),
					Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
		}
		try {
			var json = new JSONObject(new String(container.read(METADATA), StandardCharsets.UTF_8));
			Optional<String> identifier = Optional.ofNullable(json.optString("identifier", null));
			Optional<String> created = Optional.ofNullable(json.optString("creationTime", null));
			Optional<String> modified = Optional.ofNullable(json.optString("modificationTime", null));
			Optional<Object> version = Optional.ofNullable(json.opt("formatVersion"));
			return new ConceptsDrawing.Manifest(
					container.entries(),
					identifier.map(UUID::fromString),
					created.map(Instant::parse),
					modified.map(Instant::parse),
					version.map(Object::toString));
		} catch (JSONException | DateTimeParseException | IllegalArgumentException exception) {
			throw new IOException("Invalid " + METADATA, exception);
		}
	}

	/** The stroke array of an item, if the item is a stroke. */
	private static Optional<ArrayValue> strokeOf(Value item) {
		if (!item.isArrayValue() || item.asArrayValue().size() < 2) {
			return Optional.empty();
		}
		Value content = item.asArrayValue().get(1);
		if (!content.isArrayValue() || content.asArrayValue().size() < 2
				|| !isInt(content.asArrayValue().get(0), 0)) {
			return Optional.empty();
		}
		Value body = content.asArrayValue().get(1);
		if (!body.isArrayValue() || body.asArrayValue().size() == 0
				|| !isInt(body.asArrayValue().get(0), STROKE_KIND)) {
			return Optional.empty();
		}
		return Optional.of(body.asArrayValue());
	}

	private static ConceptsDrawing.Stroke stroke(ArrayValue stroke) throws IOException {
		array(stroke, STROKE_POINTS + 1, "stroke");
		ArrayValue header = array(stroke.get(STROKE_HEADER), HEADER_TRANSFORM + 1, "stroke header");
		ArrayValue brush = array(array(header.get(HEADER_BRUSH), 2, "brush").get(1), 4, "brush settings");
		ArrayValue paint = array(brush.get(1), 4, "brush paint");
		ArrayValue tool = array(paint.get(1), 2, "brush tool");
		Value width = brush.get(3);
		return new ConceptsDrawing.Stroke(
				optionalUuid(header.get(HEADER_ID)),
				optionalTime(header.get(HEADER_TIME)),
				integer(tool.get(1), "tool"),
				color(ext(paint.get(2), EXT_COLOR, 16, "color")),
				real(paint.get(3), "opacity"),
				width.isNilValue() ? Optional.empty() : Optional.of(real(width, "width")),
				transform(ext(header.get(HEADER_TRANSFORM), EXT_TRANSFORM, 64, "transform")),
				bool(stroke.get(STROKE_CLOSED), "closed"),
				points(binary(stroke.get(STROKE_POINTS), "points")));
	}

	private static Optional<UUID> optionalUuid(Value value) throws IOException {
		if (value.isNilValue()) {
			return Optional.empty();
		}
		ByteBuffer bytes = ext(value, EXT_UUID, 16, "identifier");
		return Optional.of(new UUID(bytes.getLong(), bytes.getLong()));
	}

	private static Optional<Instant> optionalTime(Value value) throws IOException {
		if (value.isNilValue()) {
			return Optional.empty();
		}
		if (!value.isNumberValue()) {
			throw new IOException("Expected timestamp to be a number, got " + value.getValueType());
		}
		// A double cannot hold today's Unix time to the nanosecond, and the
		// app writes microseconds, so round to those.
		long micros = Math.round(value.asNumberValue().toDouble() * 1e6);
		return Optional.of(Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000), Math.floorMod(micros, 1_000_000) * 1000L));
	}

	private static ConceptsDrawing.Color color(ByteBuffer bytes) {
		bytes.order(ByteOrder.LITTLE_ENDIAN);
		return new ConceptsDrawing.Color(bytes.getFloat(), bytes.getFloat(), bytes.getFloat(), bytes.getFloat());
	}

	/**
	 * A 4x4 float32 matrix that must hold a 2D affine transform. The padding
	 * is not always exact (deviations around 3e-6 have been seen).
	 */
	private static ConceptsDrawing.Transform transform(ByteBuffer bytes) throws IOException {
		bytes.order(ByteOrder.LITTLE_ENDIAN);
		float[] m = new float[16];
		for (int i = 0; i < m.length; i++) {
			m[i] = bytes.getFloat();
		}
		for (int i : new int[] {2, 3, 6, 7, 8, 9, 11, 14}) {
			if (Math.abs(m[i]) > AFFINE_TOLERANCE) {
				throw new IOException("Transform is not 2D affine (element " + i + " is " + m[i] + ")");
			}
		}
		if (Math.abs(m[10] - 1) > AFFINE_TOLERANCE || Math.abs(m[15] - 1) > AFFINE_TOLERANCE) {
			throw new IOException("Transform is not 2D affine (diagonal is not 1)");
		}
		return new ConceptsDrawing.Transform(m[0], m[1], m[4], m[5], m[12], m[13]);
	}

	private static List<ConceptsDrawing.Point> points(byte[] raw) throws IOException {
		if (raw.length % POINT_BYTES != 0) {
			throw new IOException("Point buffer length " + raw.length + " is not a multiple of " + POINT_BYTES);
		}
		ByteBuffer bytes = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
		var points = new ArrayList<ConceptsDrawing.Point>(raw.length / POINT_BYTES);
		for (int at = 0; at < raw.length; at += POINT_BYTES) {
			points.add(new ConceptsDrawing.Point(bytes.getFloat(at), bytes.getFloat(at + 4)));
		}
		return points;
	}

	private static Value unpack(byte[] bytes) throws IOException {
		try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bytes)) {
			Value value = unpacker.unpackValue();
			if (unpacker.hasNext()) {
				throw new IOException(TREE + " has trailing data");
			}
			return value;
		} catch (MessagePackException exception) {
			throw new IOException("Invalid MessagePack in " + TREE, exception);
		}
	}

	private static ArrayValue array(Value value, int minSize, String what) throws IOException {
		if (!value.isArrayValue() || value.asArrayValue().size() < minSize) {
			throw new IOException("Expected " + what + " to be an array of at least " + minSize + ", got " + value);
		}
		return value.asArrayValue();
	}

	private static ByteBuffer ext(Value value, byte type, int length, String what) throws IOException {
		if (!value.isExtensionValue()) {
			throw new IOException("Expected " + what + " to be ext " + type + ", got " + value.getValueType());
		}
		ExtensionValue ext = value.asExtensionValue();
		if (ext.getType() != type || ext.getData().length != length) {
			throw new IOException("Expected " + what + " to be ext " + type + " of " + length
					+ " bytes, got ext " + ext.getType() + " of " + ext.getData().length);
		}
		return ByteBuffer.wrap(ext.getData());
	}

	private static int integer(Value value, String what) throws IOException {
		if (!value.isIntegerValue() || !value.asIntegerValue().isInIntRange()) {
			throw new IOException("Expected " + what + " to be an int, got " + value);
		}
		return value.asIntegerValue().asInt();
	}

	private static float real(Value value, String what) throws IOException {
		if (!value.isNumberValue()) {
			throw new IOException("Expected " + what + " to be a number, got " + value.getValueType());
		}
		return value.asNumberValue().toFloat();
	}

	private static boolean bool(Value value, String what) throws IOException {
		if (!value.isBooleanValue()) {
			throw new IOException("Expected " + what + " to be a boolean, got " + value.getValueType());
		}
		return value.asBooleanValue().getBoolean();
	}

	private static byte[] binary(Value value, String what) throws IOException {
		if (!value.isBinaryValue()) {
			throw new IOException("Expected " + what + " to be binary, got " + value.getValueType());
		}
		return value.asBinaryValue().asByteArray();
	}

	private static boolean isInt(Value value, int expected) {
		return value.isIntegerValue() && value.asIntegerValue().isInIntRange()
				&& value.asIntegerValue().asInt() == expected;
	}
}
