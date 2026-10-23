package org.rapidcow.archive.script.apple.notes;

import java.nio.file.Path;
import java.sql.DriverManager;
import javax.swing.tree.DefaultMutableTreeNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rapidcow.ai.archive.script.apple.notes.StoreTree;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StoreTreeTest {
    @TempDir Path directory;

    @Test void groupsConcreteEntitiesOnceAndReadsDetailsOnDemand() throws Exception {
        Path path = directory.resolve("NoteStore.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE Z_PRIMARYKEY (Z_ENT INTEGER, Z_NAME TEXT, Z_SUPER INTEGER)");
            sql.execute("INSERT INTO Z_PRIMARYKEY VALUES (1,'ICCloudSyncingObject',0),(2,'ICNote',1),(3,'CHANGE',0)");
            sql.execute("CREATE TABLE ZICCLOUDSYNCINGOBJECT (Z_PK INTEGER, Z_ENT INTEGER, ZTITLE1 TEXT, ZDATA BLOB, ZNULL TEXT)");
            sql.execute("INSERT INTO ZICCLOUDSYNCINGOBJECT VALUES (1,2,'Zebra',x'010203',NULL),(2,2,'Apple',NULL,NULL)");
        }
        try (var store = StoreTree.open(path)) {
            assertEquals(2, store.count);
            assertEquals(0, store.entities.get("ICCloudSyncingObject").size());
            assertTrue(store.unavailable.contains("CHANGE"));
            var notes = (DefaultMutableTreeNode) store.root(true).getChildAt(1);
            var first = (StoreTree.Item) ((DefaultMutableTreeNode) notes.getChildAt(0)).getUserObject();
            assertEquals("Apple", first.title());
            var details = store.details(store.entities.get("ICNote").get(0));
            assertEquals("<3 bytes>", details.get("ZDATA"));
            assertEquals("1", details.get("Z_PK"));
            assertFalse(details.containsKey("ZNULL"));
            assertThrows(UnsupportedOperationException.class, () -> first.row().columns().clear());
        }
    }

    @Test void opensOptionalRealStore() {
        String fixture = System.getProperty("notesViewerFixture");
        assumeTrue(fixture != null, "Pass -PnotesViewerFixture=/path/to/NoteStore.sqlite");
        try (var store = StoreTree.open(Path.of(fixture))) {
            assertTrue(store.count > 0);
            assertTrue(store.entities.containsKey("ICNote"));
            store.entities.values().stream().flatMap(java.util.Collection::stream)
                    .filter(item -> item.row().entity().name().equals("ICNote"))
                    .findFirst().ifPresent(item -> assertFalse(store.details(item).isEmpty()));
            assertEquals(store.entities.size(), store.root(true).getChildCount());
        }
    }
}
