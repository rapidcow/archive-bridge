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
            assertEquals(0, note.getContentSize());
            try (var stream = note.openContentStream()) {
                assertEquals(-1, stream.read());
            }
            assertThrows(IllegalStateException.class, note::openResourceStream);
            assertThrows(IllegalStateException.class, note::getResourceSize);
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
    void contentStreamsAreBoundedIndependentAndCloseable() throws IOException {
        String body = "Invented café 🌱\n\nAnother line.\n";
        byte[] expected = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path file = temporary.resolve("stream.md");
        Files.writeString(file, "Title\n\n" + body + "\n\nid: stream\ntype_: 1");
        try (var archive = JoplinExportDirectory.open(temporary)) {
            JoplinEntry entry = archive.entries().get("stream");
            assertEquals(expected.length, entry.getContentSize());
            assertFalse(entry.getMetadata().containsKey("size"));
            assertEquals(body, entry.getContent());
            try (var first = entry.openContentStream(); var second = entry.openContentStream()) {
                assertEquals(expected[0], first.read());
                assertArrayEquals(expected, second.readAllBytes());
                assertEquals(-1, second.read());
                assertEquals(0, second.read(new byte[0]));
                assertEquals(0, second.skip(100));
                assertEquals(0, first.skip(-1));
                assertEquals(expected.length - 1, first.skip(Long.MAX_VALUE));
                assertEquals(-1, first.read());
                first.close();
                assertThrows(IOException.class, first::read);
                assertEquals(-1, second.read());
            }
            // Size is stored from the original byte offsets, without rereading content.
            Files.writeString(file, "");
            assertEquals(expected.length, entry.getContentSize());
            try (var stream = entry.openContentStream()) {
                assertThrows(java.io.EOFException.class, stream::read);
            }
        }
    }

    @Test
    void streamsBinaryResourcesAndLeavesMetadataUntouched() throws IOException {
        Files.writeString(temporary.resolve("resource.md"), """
                Invented attachment

                id: resource
                file_extension: bin
                size: 6
                type_: 4""");
        Files.createDirectory(temporary.resolve("resources"));
        byte[] payload = { 0, 1, (byte) 255, 10, 13, 0 };
        Path resource = temporary.resolve("resources/resource.bin");
        Files.write(resource, payload);
        try (var archive = JoplinExportDirectory.open(temporary)) {
            JoplinEntry entry = archive.entries().get("resource");
            assertEquals(payload.length, entry.getResourceSize());
            assertEquals("6", entry.getMetadata().get("size"));
            assertEquals(0, entry.getContentSize());
            assertEquals("", entry.getContent());
            try (var content = entry.openContentStream()) {
                assertEquals(-1, content.read());
            }
            try (var first = entry.openResourceStream(); var second = entry.openResourceStream()) {
                assertEquals(0, first.read());
                first.close();
                assertThrows(IOException.class, first::read);
                assertArrayEquals(payload, second.readAllBytes());
            }
            Files.delete(resource);
            assertEquals(payload.length, entry.getResourceSize());
            assertEquals(0, entry.getContentSize());
            assertThrows(IOException.class, entry::openResourceStream);
        }
    }

    @Test
    void resolvesResourceWithoutExplicitExtensionAndRejectsAmbiguity() throws IOException {
        Files.writeString(temporary.resolve("resource.md"), """
                Invented attachment

                id: resource
                file_extension:
                mime: image/png
                size: 1
                type_: 4""");
        Files.createDirectory(temporary.resolve("resources"));
        assertThrows(IOException.class, () -> JoplinExportDirectory.open(temporary));
        Files.write(temporary.resolve("resources/resource.png"), new byte[] { 42 });
        try (var archive = JoplinExportDirectory.open(temporary)) {
            JoplinEntry entry = archive.entries().get("resource");
            assertEquals(1, entry.getResourceSize());
            try (var stream = entry.openResourceStream()) {
                assertEquals(42, stream.read());
                assertEquals(-1, stream.read());
            }
        }
        Files.write(temporary.resolve("resources/resource"), new byte[] { 43 });
        assertThrows(IOException.class, () -> JoplinExportDirectory.open(temporary));
    }

    @Test
    void rejectsMismatchedAndInvalidResourceSizes() throws IOException {
        Files.createDirectory(temporary.resolve("resources"));
        Files.write(temporary.resolve("resources/resource.bin"), new byte[] { 1, 2, 3 });
        for (String size : java.util.List.of("2", "4", "2147483648", "-1", "", "invalid",
                "9223372036854775808")) {
            Files.writeString(temporary.resolve("resource.md"), """
                    Invented attachment

                    id: resource
                    file_extension: bin
                    size: %s
                    type_: 4""".formatted(size));
            var error = assertThrows(IllegalStateException.class,
                    () -> JoplinExportDirectory.open(temporary));
            if (size.equals("2147483648")) {
                assertEquals("Resource size mismatch: expected 2147483648 bytes, found 3",
                        error.getMessage());
            }
        }
        Files.writeString(temporary.resolve("resource.md"), """
                Invented attachment

                id: resource
                file_extension: bin
                type_: 4""");
        assertThrows(IllegalStateException.class, () -> JoplinExportDirectory.open(temporary));
    }

    @Test
    void requiresDirectoryAndAllowsEmptyDirectory() throws IOException {
        assertThrows(IOException.class, () -> JoplinExportDirectory.open(temporary.resolve("missing")));
        try (var archive = JoplinExportDirectory.open(temporary)) {
            assertTrue(archive.entries().isEmpty());
        }
    }
}
