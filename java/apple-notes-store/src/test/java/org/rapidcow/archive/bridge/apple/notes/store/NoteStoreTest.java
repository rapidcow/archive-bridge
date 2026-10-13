package org.rapidcow.archive.bridge.apple.notes.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.rapidcow.archive.bridge.apple.notes.store.ai.NoteStore;
import org.rapidcow.archive.bridge.apple.notes.store.ai.NoteStoreRow;
import org.rapidcow.archive.bridge.apple.notes.store.ai.NoteStoreEntity;
import org.rapidcow.archive.bridge.apple.notes.store.ai.NoteStoreException;

/**
 * Tests against the empty store fixture: a freshly set-up Notes account
 * with no notes, whose database is a standalone file (no WAL).
 */
class NoteStoreTest {
	private static final String FIXTURE = "/fixtures/macOS/sequoia/fresh/NoteStore.sqlite";

	@TempDir
	Path dir;
	Path db;

	@BeforeEach
	void copyFixture() throws IOException {
		db = dir.resolve("NoteStore.sqlite");
		try (InputStream in = NoteStoreTest.class.getResourceAsStream(FIXTURE)) {
			assertNotNull(in, "missing test resource " + FIXTURE);
			Files.copy(in, db, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	@Test
	void listsDeclaredEntities() {
		try (NoteStore store = NoteStore.open(db)) {
			List<NoteStoreEntity> entities = store.entities();
			assertEquals(23, entities.size());
			NoteStoreEntity note = store.entity("ICNote").orElseThrow();
			assertEquals(11, note.id());
			assertEquals(store.entity("ICCloudSyncingObject").orElseThrow().id(), note.superId());
			assertEquals(0, store.entity("ICCloudSyncingObject").orElseThrow().superId());
			assertTrue(store.entity("NoSuchEntity").isEmpty());
		}
	}

	@Test
	void hasNoNotes() {
		try (NoteStore store = NoteStore.open(db)) {
			assertEquals(0, store.count("ICNote"));
			try (Stream<NoteStoreRow> rows = store.rows("ICNote")) {
				assertEquals(0, rows.count());
			}
		}
	}

	@Test
	void readsFoldersAndAccount() {
		try (NoteStore store = NoteStore.open(db)) {
			// Column suffixes (ZTITLE2, ZNAME) are Core Data's; the generic
			// layer deliberately takes them as they are in the database.
			try (Stream<NoteStoreRow> folders = store.rows("ICFolder")) {
				List<String> titles = folders.map(r -> r.getString("ZTITLE2")).collect(Collectors.toList());
				assertEquals(List.of("Notes", "Recently Deleted"), titles.stream().sorted().toList());
			}
			try (Stream<NoteStoreRow> accounts = store.rows("ICAccount")) {
				List<NoteStoreRow> list = accounts.toList();
				assertEquals(1, list.size());
				assertEquals("iCloud", list.get(0).getString("ZNAME"));
				assertEquals("ICAccount", list.get(0).entity().name());
			}
		}
	}

	@Test
	void rowsOfAnEntityIncludeItsSubentities() {
		try (NoteStore store = NoteStore.open(db)) {
			// ICNoteContainer is abstract here: ICAccount (1) + ICFolder (2).
			assertEquals(3, store.count("ICNoteContainer"));
			try (Stream<NoteStoreRow> rows = store.rows("ICNoteContainer")) {
				List<String> kinds = rows.map(r -> r.entity().name()).sorted().toList();
				assertEquals(List.of("ICAccount", "ICFolder", "ICFolder"), kinds);
			}
			// ...and a root entity covers its whole hierarchy.
			assertEquals(
					store.count("ICNoteContainer") + store.count("ICAccountData")
							+ store.count("ICDeviceMigrationState") + store.count("ICNote")
							+ store.count("ICAttachment") + store.count("ICAttachmentPreviewImage"),
					store.count("ICCloudSyncingObject"));
		}
	}

	@Test
	void entitiesInTheirOwnTablesAreReadable() {
		try (NoteStore store = NoteStore.open(db)) {
			// ICNoteData is its own root, in ZICNOTEDATA rather than ZICCLOUDSYNCINGOBJECT.
			assertEquals(0, store.count("ICNoteData"));
			assertEquals(4, store.count("ICServerChangeToken"));
		}
	}

	@Test
	void valuesAreNormalized() {
		try (NoteStore store = NoteStore.open(db)) {
			NoteStoreRow account = store.rows("ICAccount").findFirst().orElseThrow();
			assertTrue(account.get("Z_PK") instanceof Long);
			assertEquals(account.pk(), account.get("Z_PK"));
			assertEquals(13L, account.get("Z_ENT"));
			assertNull(account.get("ZTITLE2"));
			assertTrue(account.get("ZSERVERRECORDDATA") instanceof byte[]);
		}
	}

	@Test
	void rowsCanBeReadWhileTheStreamIsOpen() {
		try (NoteStore store = NoteStore.open(db); Stream<NoteStoreRow> rows = store.rows("ICCloudSyncingObject")) {
			// A second statement runs on the same connection mid-stream.
			assertTrue(rows.allMatch(r -> r.get("Z_ENT") != null));
		}
	}

	@Test
	void rejectsUnknownEntitiesAndColumns() {
		try (NoteStore store = NoteStore.open(db)) {
			assertThrows(IllegalArgumentException.class, () -> store.rows("NoSuchEntity"));
			assertThrows(IllegalArgumentException.class, () -> store.count("NoSuchEntity"));
			NoteStoreRow row = store.rows("ICAccount").findFirst().orElseThrow();
			assertThrows(IllegalArgumentException.class, () -> row.get("ZNOPE"));
			// Not a column: must be refused, not interpolated into SQL.
			assertThrows(IllegalArgumentException.class, () -> row.get("Z_PK\" FROM ZICNOTEDATA --"));
		}
	}

	@Test
	void refusesAMissingFileAndNonStores() throws IOException {
		assertThrows(NoteStoreException.class, () -> NoteStore.open(dir.resolve("absent.sqlite")));
		Path junk = Files.writeString(dir.resolve("junk.sqlite"), "this is not a database, not even close.");
		assertThrows(NoteStoreException.class, () -> NoteStore.open(junk));
		// A valid SQLite database that is not a Core Data store.
		Path plain = dir.resolve("plain.sqlite");
		try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + plain);
				var st = c.createStatement()) {
			st.execute("CREATE TABLE t(x)");
		} catch (java.sql.SQLException e) {
			throw new AssertionError(e);
		}
		NoteStoreException e = assertThrows(NoteStoreException.class, () -> NoteStore.open(plain));
		assertTrue(e.getMessage().contains("Z_PRIMARYKEY"), e.getMessage());
	}

	@Test
	void isUnusableAfterClose() {
		NoteStore store = NoteStore.open(db);
		store.close();
		store.close();   // idempotent
		assertThrows(IllegalStateException.class, store::entities);
		assertThrows(IllegalStateException.class, () -> store.rows("ICNote"));
	}

	@Test
	void leavesNoSidecarFilesAndNeverWritesTheStore() throws IOException {
		byte[] before = Files.readAllBytes(db);
		try (NoteStore store = NoteStore.open(db)) {
			store.entities();
			store.count("ICCloudSyncingObject");
			try (Stream<NoteStoreRow> rows = store.rows("ICFolder")) {
				rows.forEach(r -> r.get("ZTITLE2"));
			}
		}
		try (Stream<Path> files = Files.list(dir)) {
			assertEquals(List.of("NoteStore.sqlite"), files.map(p -> p.getFileName().toString()).toList());
		}
		assertFalse(Files.exists(dir.resolve("NoteStore.sqlite-wal")));
		assertEquals(before.length, Files.size(db));
		assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(db)), "store was modified");
	}
}
