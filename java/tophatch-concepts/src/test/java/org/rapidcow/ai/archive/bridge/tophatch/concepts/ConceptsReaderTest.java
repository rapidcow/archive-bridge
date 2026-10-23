package org.rapidcow.ai.archive.bridge.tophatch.concepts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;

class ConceptsReaderTest {
	private static final String FIXTURE_ROOT = "org.rapidcow.archive.bridge.fixtureRoot";
	private static final UUID STROKE_ID = UUID.fromString("b0c7b677-3e14-4ebf-a604-e356cbedb738");

	@TempDir
	Path temporary;

	@Test
	void decodesSyntheticStrokeAndSkipsOtherItems() throws IOException {
		Path path = zip(Map.of(
				"metadata.json", ("{\"identifier\":\"a75d6467-8cf9-4201-a951-64e8115f79ad\","
						+ "\"creationTime\":\"2026-04-11T18:33:53.674Z\",\"formatVersion\":20250319}")
						.getBytes(StandardCharsets.UTF_8),
				"tree.pack", tree(affine(2, 0, 0, 2, 10, -5), new float[] {1, 2, 3.5f, -4})));
		ConceptsDrawing drawing = new ConceptsReader().read(path);

		var manifest = drawing.manifest();
		assertEquals(List.of("metadata.json", "tree.pack"),
				manifest.entries().stream().map(ConceptsDrawing.Entry::name).toList());
		assertEquals(UUID.fromString("a75d6467-8cf9-4201-a951-64e8115f79ad"), manifest.identifier().orElseThrow());
		assertEquals(Instant.parse("2026-04-11T18:33:53.674Z"), manifest.created().orElseThrow());
		assertTrue(manifest.modified().isEmpty());
		assertEquals("20250319", manifest.formatVersion().orElseThrow());

		assertEquals(1, drawing.skippedItems());
		assertEquals(1, drawing.strokes().size());
		var stroke = drawing.strokes().get(0);
		assertEquals(STROKE_ID, stroke.identifier().orElseThrow());
		assertEquals(Instant.ofEpochSecond(1776497347L, 500_000_000), stroke.timestamp().orElseThrow());
		assertEquals(14, stroke.tool());
		assertEquals(new ConceptsDrawing.Color(0.25f, 0.5f, 0.75f, 1), stroke.color());
		assertEquals(0.5f, stroke.opacity());
		assertEquals(3.5f, stroke.width().orElseThrow());
		assertEquals(new ConceptsDrawing.Transform(2, 0, 0, 2, 10, -5), stroke.transform());
		assertTrue(stroke.closed());
		assertEquals(List.of(new ConceptsDrawing.Point(1, 2), new ConceptsDrawing.Point(3.5f, -4)), stroke.points());
	}

	@Test
	void rejectsMalformedStrokes() throws IOException {
		byte[] skewed = affine(1, 0, 0, 1, 0, 0);
		ByteBuffer.wrap(skewed).order(ByteOrder.LITTLE_ENDIAN).putFloat(8, 1);
		Path nonAffine = zip(Map.of("tree.pack", tree(skewed, new float[] {0, 0})));
		assertThrows(IOException.class, () -> new ConceptsReader().read(nonAffine));
	}

	@Test
	void choosesBackendByContentsNotName() throws IOException {
		Path plist = zip(Map.of("Strokes.plist", new byte[] {'b', 'p'}));
		var none = assertThrows(IOException.class, () -> new ConceptsReader().read(plist));
		assertTrue(none.getMessage().contains("Strokes.plist"));

		Path pack = zip(Map.of("tree.pack", tree(affine(1, 0, 0, 1, 0, 0), new float[0])));
		var reader = new ConceptsReader(List.of(new MessagePackBackend(), new MessagePackBackend()),
				ConceptsContainer.Limits.DEFAULT);
		assertThrows(IOException.class, () -> reader.read(pack));
	}

	@Test
	void decodesOptionalPrivateFixture() throws IOException {
		String root = System.getProperty(FIXTURE_ROOT, "");
		assumeTrue(!root.isEmpty(), "No fixture root configured");
		Path fixture = Path.of(root, "concepts", "Stargazer.concepts");
		assumeTrue(Files.isRegularFile(fixture), "Optional private fixture unavailable");

		ConceptsDrawing drawing = new ConceptsReader().read(fixture);
		var manifest = drawing.manifest();
		assertEquals(UUID.fromString("a75d6467-8cf9-4201-a951-64e8115f79ad"), manifest.identifier().orElseThrow());
		assertEquals(Instant.parse("2026-04-18T04:32:35.598Z"), manifest.modified().orElseThrow());
		assertEquals("20250319", manifest.formatVersion().orElseThrow());
		assertEquals(5, manifest.entries().size());
		assertEquals(58, drawing.strokes().size());
		assertEquals(0, drawing.skippedItems());

		var first = drawing.strokes().get(0);
		assertEquals(12, first.points().size());
		assertEquals(2, first.tool());
		assertEquals(3.2f, first.width().orElseThrow());
		assertFalse(first.closed());
		assertEquals(Instant.ofEpochSecond(1776497347L, 114_815_000), first.timestamp().orElseThrow());
	}

	/** A tree.pack with one stroke followed by one non-stroke item. */
	private static byte[] tree(byte[] transform, float[] xy) throws IOException {
		byte[] points = new byte[xy.length / 2 * 16];
		var buffer = ByteBuffer.wrap(points).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < xy.length; i += 2) {
			buffer.putFloat(i * 8, xy[i]).putFloat(i * 8 + 4, xy[i + 1]);
		}
		byte[] color = new byte[16];
		ByteBuffer.wrap(color).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.25f).putFloat(0.5f).putFloat(0.75f).putFloat(1);
		byte[] id = ByteBuffer.allocate(16)
				.putLong(STROKE_ID.getMostSignificantBits()).putLong(STROKE_ID.getLeastSignificantBits()).array();

		try (MessageBufferPacker p = MessagePack.newDefaultBufferPacker()) {
			p.packArrayHeader(2).packInt(1027);
			p.packArrayHeader(7).packInt(0).packNil().packInt(0).packNil();
			p.packArrayHeader(1); // layers
			p.packArrayHeader(4).packInt(1).packNil();
			p.packArrayHeader(2); // items

			p.packArrayHeader(2).packInt(11).packArrayHeader(2).packInt(0);
			p.packArrayHeader(19).packInt(6);
			p.packArrayHeader(10).packInt(3);
			p.packArrayHeader(2).packInt(1);
			p.packArrayHeader(5).packInt(1);
			p.packArrayHeader(4).packInt(0);
			p.packArrayHeader(2).packInt(0).packInt(14);
			p.packExtensionTypeHeader((byte) 4, 16).addPayload(color);
			p.packFloat(0.5f);
			p.packNil().packFloat(3.5f).packFloat(0);
			p.packExtensionTypeHeader((byte) 5, 16).addPayload(id);
			p.packNil().packNil().packInt(0).packDouble(1776497347.5);
			p.packExtensionTypeHeader((byte) 7, 64).addPayload(transform);
			p.packBoolean(false).packInt(17037320);
			p.packInt(2).packFloat(1).packFloat(0.1f).packBoolean(true);
			for (int i = 6; i < 17; i++) {
				p.packNil();
			}
			p.packBinaryHeader(points.length).addPayload(points);
			p.packBinaryHeader(0);

			p.packArrayHeader(2).packInt(7).packArrayHeader(2).packInt(1).packArrayHeader(1).packInt(3);

			p.packInt(9);
			p.packNil().packNil();
			return p.toByteArray();
		}
	}

	private static byte[] affine(float a, float b, float c, float d, float tx, float ty) {
		float[] m = {a, b, 0, 0, c, d, 0, 0, 0, 0, 1, 0, tx, ty, 0, 1};
		var buffer = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
		for (float f : m) {
			buffer.putFloat(f);
		}
		return buffer.array();
	}

	private Path zip(Map<String, byte[]> entries) throws IOException {
		Path path = Files.createTempFile(temporary, "synthetic-", ".zip");
		try (var output = new ZipOutputStream(Files.newOutputStream(path))) {
			for (var entry : new TreeMap<>(entries).entrySet()) {
				output.putNextEntry(new ZipEntry(entry.getKey()));
				output.write(entry.getValue());
				output.closeEntry();
			}
		}
		return path;
	}
}
