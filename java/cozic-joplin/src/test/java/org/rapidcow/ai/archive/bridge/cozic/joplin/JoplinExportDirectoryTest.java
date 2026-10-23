package org.rapidcow.ai.archive.bridge.cozic.joplin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JoplinExportDirectoryTest {
    @TempDir Path temporary;

    @Test
    void readsOnlyHeaderAndTrailerAndIgnoresResources() throws IOException {
        Files.writeString(temporary.resolve("b.md"), """
                Invented café 🌱

                An invented note.

                id: body-decoy

                id: example
                source_application: net.cozic.joplin-mobile
                author:\s
                future_field: a: b\\nnext
                type_: 1
                """);
        Files.writeString(temporary.resolve("a.md"), """


                Invented body

                id: empty-title
                type_: 2\
                """);
        Files.createDirectory(temporary.resolve("resources"));
        Files.createDirectory(temporary.resolve("directory.md"));
        Files.writeString(temporary.resolve("resources/ignored.md"), "not an item");
        Files.writeString(temporary.resolve("ignored.bin"), "not an item");
        try (var archive = JoplinExportDirectory.open(temporary)) {
            assertEquals(2, archive.entries().size());
            assertEquals("", archive.entries().get("a").getTitle());
            var note = archive.entries().get("b");
            assertEquals("Invented café 🌱", note.getTitle());
            assertEquals("example", note.getMetadata().get("id"));
            assertEquals(java.util.List.of("id", "source_application", "author", "future_field", "type_"),
                    java.util.List.copyOf(note.getMetadata().keySet()));
            assertEquals("", note.getMetadata().get("author"));
            assertEquals("a: b\\nnext", note.getMetadata().get("future_field"));
            assertThrows(UnsupportedOperationException.class, () -> note.getMetadata().put("id", "other"));
            assertThrows(UnsupportedOperationException.class, () -> archive.entries().clear());
        }
    }

    @Test
    void supportsUtf8BomWithEmptyFirstLine() throws IOException {
        Files.writeString(temporary.resolve("item.md"), "\uFEFF\n\nInvented body\n\nid: example\ntype_: 1\n");
        try (var archive = JoplinExportDirectory.open(temporary)) {
            assertEquals("", archive.entries().get("item").getTitle());
            assertEquals("example", archive.entries().get("item").getMetadata().get("id"));
        }
    }

    @Test
    void supportsLfAndEmptyBody() throws IOException {
        Files.writeString(temporary.resolve("item.md"), """


                id: example
                type_: 2
                """);
        try (var archive = JoplinExportDirectory.open(temporary)) {
            assertEquals("2", archive.entries().get("item").getMetadata().get("type_"));
        }
    }

    @Test
    void rejectsCrLfMetadata() throws IOException {
        Files.writeString(temporary.resolve("item.md"), "Title\n\nid: example\r\ntype_: 4\r\n");
        var error = assertThrows(IOException.class, () -> JoplinExportDirectory.open(temporary));
        assertEquals("Literal CR is not allowed in JEX metadata", error.getMessage());
    }

    @Test
    void preservesLiteralCrInFirstLine() throws IOException {
        Files.writeString(temporary.resolve("item.md"), "Icon\r\n\nid: example\ntype_: 2");
        try (var archive = JoplinExportDirectory.open(temporary)) {
            assertEquals("Icon\r", archive.entries().get("item").getTitle());
        }
    }

    @Test
    void rejectsMissingBlankLineAfterTitle() throws IOException {
        Files.writeString(temporary.resolve("item.md"), """
                First
                Second

                id: example
                type_: 2
                """);
        var error = assertThrows(IOException.class, () -> JoplinExportDirectory.open(temporary));
        assertEquals("Missing blank line after JEX title", error.getMessage());
    }

    @Test
    void rejectsMalformedTrailerWithoutEchoingText() throws IOException {
        Files.writeString(temporary.resolve("item.md"), """
                Title

                Invented body

                private malformed trailer
                """);
        var error = assertThrows(IOException.class, () -> JoplinExportDirectory.open(temporary));
        assertEquals("Invalid JEX metadata trailer", error.getMessage());
    }

    @Test
    void rejectsDuplicateMetadataAndMissingIdentity() throws IOException {
        Path item = temporary.resolve("item.md");
        Files.writeString(item, """
                Title

                id: one
                id: two
                type_: 1
                """);
        assertThrows(IOException.class, () -> JoplinExportDirectory.open(temporary));
        Files.writeString(item, """
                Title

                type_: 1
                """);
        assertThrows(IOException.class, () -> JoplinExportDirectory.open(temporary));
    }

    @Test
    void indexesEntriesResolvesParentsAndLoadsContentLazily() throws IOException {
        Files.writeString(temporary.resolve("folder.md"), """
                Invented notebook

                id: folder
                type_: 2""");
        Path notePath = temporary.resolve("note.md");
        Files.writeString(notePath, """
                Invented title

                A café 🌱.

                id: body-decoy


                id: note
                parent_id: folder
                type_: 1""");
        try (var archive = JoplinExportDirectory.open(temporary)) {
            JoplinEntry note = archive.entries().get("note");
            JoplinEntry folder = archive.entries().get("folder");
            assertEquals("note", note.getId());
            assertEquals(JoplinType.NOTE, note.getType());
            assertEquals(JoplinType.FOLDER, folder.getType());
            assertSame(folder, note.getParent().orElseThrow());
            assertTrue(folder.getParent().isEmpty());
            assertEquals("", folder.getContent());
            // Same byte length: proves open() did not eagerly read or cache the body.
            Files.writeString(notePath, Files.readString(notePath).replace("A café", "B café"));
            assertEquals("B café 🌱.\n\nid: body-decoy\n", note.getContent());
            // A second call reads the backing file rather than returning cached content.
            Files.delete(notePath);
            assertEquals("Invented title", note.getTitle());
            assertThrows(IOException.class, note::getContent);
        }
    }

    @Test
    void emptyNoteAndUnresolvedParentAreSupported() throws IOException {
        Files.writeString(temporary.resolve("empty.md"), """
                Empty note

                id: empty
                parent_id: absent
                type_: 1""");
        try (var archive = JoplinExportDirectory.open(temporary)) {
            JoplinEntry note = archive.entries().get("empty");
            assertTrue(note.getParent().isEmpty());
            assertEquals("", note.getContent());
        }
    }

    @Test
    void indexesOnlyDirectChildrenAndReturnsImmutableLists() throws IOException {
        for (String id : java.util.List.of("root", "nested", "note", "grandchild", "orphan")) {
            String parent = switch (id) {
                case "nested", "note" -> "root";
                case "grandchild" -> "nested";
                case "orphan" -> "absent";
                default -> "";
            };
            Files.writeString(temporary.resolve(id + ".md"), """
                    Invented title

                    id: %s
                    parent_id: %s
                    type_: 2""".formatted(id, parent));
        }
        try (var archive = JoplinExportDirectory.open(temporary)) {
            JoplinEntry root = archive.entries().get("root");
            JoplinEntry nested = archive.entries().get("nested");
            JoplinEntry note = archive.entries().get("note");
            JoplinEntry grandchild = archive.entries().get("grandchild");
            assertEquals(java.util.Set.of(nested, note), java.util.Set.copyOf(root.getChildren()));
            assertEquals(java.util.List.of(grandchild), nested.getChildren());
            assertTrue(note.getChildren().isEmpty());
            assertTrue(grandchild.getChildren().isEmpty());
            assertTrue(archive.entries().get("orphan").getChildren().isEmpty());
            for (JoplinEntry child : root.getChildren()) {
                assertSame(root, child.getParent().orElseThrow());
            }
            assertThrows(UnsupportedOperationException.class, () -> root.getChildren().clear());
            assertThrows(UnsupportedOperationException.class, () -> note.getChildren().add(root));
        }
    }

    @Test
    void rejectsUnknownTypeDuringOpen() throws IOException {
        Files.writeString(temporary.resolve("item.md"), """
                Invented title

                id: item
                type_: 999""");
        assertThrows(IOException.class, () -> JoplinExportDirectory.open(temporary));
        assertEquals(JoplinType.NOTE, JoplinType.fromValue(1));
        assertEquals(JoplinType.REVISION, JoplinType.fromValue(13));
        for (var type : JoplinType.values()) {
            assertSame(type, JoplinType.fromValue(type.getValue()));
        }
    }

    @Test
    void requiresDirectoryAndAllowsEmptyDirectory() throws IOException {
        assertThrows(IOException.class, () -> JoplinExportDirectory.open(temporary.resolve("missing")));
        try (var archive = JoplinExportDirectory.open(temporary)) {
            assertTrue(archive.entries().isEmpty());
        }
    }
}
