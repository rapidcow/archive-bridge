package org.rapidcow.archive.bridge.apple.notes.store.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.dd.plist.BinaryPropertyListParser;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSString;

/**
 * Tests against the empty Sequoia store fixture. Expected values were
 * read independently with Python's sqlite3, plistlib and zlib.
 */
class NoteStoreModelTest {
	private static final String FIXTURE = "/fixtures/macOS/sequoia/fresh/NoteStore.sqlite";
	private static final String SEQUOIA_DIGEST =
			"eFiI+OaI3aU7Agl8qoeAA365gbOMQ0PCuVnwa+xpv49wCRyslSf3uFUP388CoHMKbg8fMbjKpr6ovHB7hAdkyg==";

	@TempDir
	Path dir;
	Path db;

	@BeforeEach
	void copyFixture() throws IOException {
		db = dir.resolve("NoteStore.sqlite");
		try (InputStream in = NoteStoreModelTest.class.getResourceAsStream(FIXTURE)) {
			assertNotNull(in, "missing test resource " + FIXTURE);
			Files.copy(in, db, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	@Test
	void readsTheMetadata() {
		try (NoteStore store = NoteStore.open(db)) {
			NoteStoreModel model = store.model();
			assertEquals("022E10B1-4CDA-4D27-A5AD-A3CDCEF191EE", model.storeUuid());
			assertEquals(SEQUOIA_DIGEST, model.digest());
			assertEquals(1448, model.frameworkVersion().orElseThrow());
			Map<String, byte[]> hashes = model.entityVersionHashes();
			assertEquals(20, hashes.size());
			assertEquals("a8c7bf9815e35bf1a8dca26ae21c1e7026cd4f82707730b21dfa6f28db244eef",
					HexFormat.of().formatHex(hashes.get("ICNote")));
			assertEquals(1617, model.metadataPlist().length);
		}
	}

	@Test
	void passesTheModelCacheThroughUnchanged() {
		try (NoteStore store = NoteStore.open(db)) {
			byte[] cache = store.model().modelCache().orElseThrow();
			assertEquals(35222, cache.length);
			assertTrue(sha256(cache).startsWith("e85414f60a8b2caa"));
		}
	}

	@Test
	void inflatesTheModelCacheToAKeyedArchive() throws Exception {
		try (NoteStore store = NoteStore.open(db)) {
			byte[] archive = store.model().modelArchive().orElseThrow();
			assertEquals(71287, archive.length);
			assertTrue(sha256(archive).startsWith("224dbed68f337f48"));
			assertEquals("bplist00", new String(archive, 0, 8, StandardCharsets.US_ASCII));
			NSDictionary root = (NSDictionary) BinaryPropertyListParser.parse(archive);
			assertEquals("NSKeyedArchiver", ((NSString) root.get("$archiver")).getContent());
		}
	}

	@Test
	void isReadOnceAndHandsOutCopies() {
		try (NoteStore store = NoteStore.open(db)) {
			NoteStoreModel model = store.model();
			assertSame(model, store.model());
			byte[] a = model.modelCache().orElseThrow();
			a[0] ^= 0xff;
			assertTrue(!Arrays.equals(a, model.modelCache().orElseThrow()));
			byte[] h = model.entityVersionHashes().get("ICNote");
			h[0] ^= 0xff;
			assertNotSame(h, model.entityVersionHashes().get("ICNote"));
			assertTrue(!Arrays.equals(h, model.entityVersionHashes().get("ICNote")));
			assertThrows(UnsupportedOperationException.class,
					() -> model.entityVersionHashes().put("x", new byte[0]));
		}
	}

	@Test
	void outlivesTheStore() {
		NoteStoreModel model;
		try (NoteStore store = NoteStore.open(db)) {
			model = store.model();
		}
		assertEquals(SEQUOIA_DIGEST, model.digest());
		assertTrue(model.modelArchive().isPresent());
	}

	@Test
	void aStoreWithoutAModelCacheStillHasMetadata() throws SQLException {
		execute(db, "DROP TABLE Z_MODELCACHE");
		try (NoteStore store = NoteStore.open(db)) {
			NoteStoreModel model = store.model();
			assertEquals(SEQUOIA_DIGEST, model.digest());
			assertTrue(model.modelCache().isEmpty());
			assertTrue(model.modelArchive().isEmpty());
		}
	}

	@Test
	void refusesMalformedMetadataAndCache() throws SQLException {
		Path junkMeta = copyOf("junk-meta.sqlite");
		execute(junkMeta, "UPDATE Z_METADATA SET Z_PLIST = x'00010203'");
		try (NoteStore store = NoteStore.open(junkMeta)) {
			assertThrows(NoteStoreException.class, store::model);
		}

		Path noDigest = copyOf("no-digest.sqlite");
		// A valid binary plist that is a dictionary without the digest:
		// plistlib.dumps({'a': 1}, fmt=FMT_BINARY).
		execute(noDigest, "UPDATE Z_METADATA SET Z_PLIST = x'62706c6973743030d1010251611001080b0d0000"
				+ "00000000010100000000000000030000000000000000000000000000000f'");
		try (NoteStore store = NoteStore.open(noDigest)) {
			NoteStoreException e = assertThrows(NoteStoreException.class, store::model);
			assertTrue(e.getMessage().contains("NSStoreModelVersionHashesDigest"), e.getMessage());
		}

		Path junkCache = copyOf("junk-cache.sqlite");
		execute(junkCache, "UPDATE Z_MODELCACHE SET Z_CONTENT = x'ffffffff'");
		try (NoteStore store = NoteStore.open(junkCache)) {
			NoteStoreModel model = store.model();
			assertEquals(4, model.modelCache().orElseThrow().length);
			assertThrows(NoteStoreException.class, model::modelArchive);
		}
	}

	private Path copyOf(String name) {
		try {
			return Files.copy(db, dir.resolve(name));
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static void execute(Path path, String sql) throws SQLException {
		try (var c = DriverManager.getConnection("jdbc:sqlite:" + path); var st = c.createStatement()) {
			st.execute(sql);
		}
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}
}
