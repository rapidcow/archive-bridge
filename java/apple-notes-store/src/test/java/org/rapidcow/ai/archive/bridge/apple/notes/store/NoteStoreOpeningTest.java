package org.rapidcow.ai.archive.bridge.apple.notes.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NoteStoreOpeningTest {
    @TempDir Path directory;

    @Test void opensStandaloneWalModeStoreWithoutSidecars() throws Exception {
        Path path = directory.resolve("Notes #1.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                var sql = connection.createStatement()) {
            sql.execute("PRAGMA journal_mode=WAL");
            sql.execute("CREATE TABLE Z_PRIMARYKEY (Z_ENT INTEGER, Z_NAME TEXT, Z_SUPER INTEGER)");
            sql.execute("INSERT INTO Z_PRIMARYKEY VALUES (1, 'ICNote', 0)");
            sql.execute("CREATE TABLE ZICNOTE (Z_PK INTEGER, Z_ENT INTEGER)");
            sql.execute("INSERT INTO ZICNOTE VALUES (1,1)");
        }
        byte[] before = Files.readAllBytes(path);
        try (var store = NoteStore.open(path)) {
            assertEquals(1, store.count("ICNote"));
            assertFalse(Files.exists(Path.of(path + "-wal")));
            assertFalse(Files.exists(Path.of(path + "-shm")));
        }
        assertArrayEquals(before, Files.readAllBytes(path));
    }
}
