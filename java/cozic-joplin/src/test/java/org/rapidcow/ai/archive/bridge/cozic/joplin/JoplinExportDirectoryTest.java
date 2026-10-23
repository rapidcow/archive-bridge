package org.rapidcow.ai.archive.bridge.cozic.joplin;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.abort;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JoplinExportDirectoryTest {
    @TempDir Path temporary;

    private static void
    writeUTF8String(Path name, String str)
    throws IOException
    {
        Files.writeString(name, str, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void readsOnlyHeaderAndTrailerAndIgnoresResources() throws IOException {
        writeUTF8String(temporary.resolve("b.md"), """
                Invented café 🌱

                An invented note.

                id: body-decoy

                id: example
                source_application: net.cozic.joplin-mobile
                author:\s
                future_field: a: b\\nnext
                type_: 1
                """);
        writeUTF8String(temporary.resolve("a.md"), """


                Invented body

                id: empty-title
                type_: 2\
                """);
        Files.createDirectory(temporary.resolve("resources"));
        Files.createDirectory(temporary.resolve("directory.md"));
        writeUTF8String(temporary.resolve("resources/ignored.md"), "not an item");
        writeUTF8String(temporary.resolve("ignored.bin"), "not an item");
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
        writeUTF8String(temporary.resolve("item.md"), "\uFEFF\n\nInvented body\n\nid: example\ntype_: 1\n");
        try (var archive = JoplinExportDirectory.open(temporary)) {
            assertEquals("", archive.entries().get("item").getTitle());
            assertEquals("example", archive.entries().get("item").getMetadata().get("id"));
        }
    }

    @Test
    void supportsLfAndEmptyBody() throws IOException {
        writeUTF8String(temporary.resolve("item.md"), """


                id: example
                type_: 2
                """);
        try (var archive = JoplinExportDirectory.open(temporary)) {
            assertEquals("2", archive.entries().get("item").getMetadata().get("type_"));
        }
    }

    @Test
    void rejectsCrLfMetadata() throws IOException {
        writeUTF8String(temporary.resolve("item.md"), "Title\n\nid: example\r\ntype_: 4\r\n");
        var error = assertThrows(IOException.class, () -> JoplinExportDirectory.open(temporary));
        assertEquals("Literal CR is not allowed in JEX metadata", error.getMessage());
    }

    @Test
    void preservesLiteralCrInFirstLine() throws IOException {
        writeUTF8String(temporary.resolve("item.md"), "Icon\r\n\nid: example\ntype_: 2");
        try (var archive = JoplinExportDirectory.open(temporary)) {
            assertEquals("Icon\r", archive.entries().get("item").getTitle());
        }
    }

    @Test
    void rejectsMissingBlankLineAfterTitle() throws IOException {
        writeUTF8String(temporary.resolve("item.md"), """
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
        writeUTF8String(temporary.resolve("item.md"), """
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
        writeUTF8String(item, """
                Title

                id: one
                id: two
                type_: 1
                """);
        assertThrows(IOException.class, () -> JoplinExportDirectory.open(temporary));
        writeUTF8String(item, """
                Title

                type_: 1
                """);
        assertThrows(IOException.class, () -> JoplinExportDirectory.open(temporary));
    }

    @Test
    void indexesEntriesResolvesParentsAndLoadsContentLazily() throws IOException {
        writeUTF8String(temporary.resolve("folder.md"), """
                Invented notebook

                id: folder
                type_: 2""");
        Path notePath = temporary.resolve("note.md");
        writeUTF8String(notePath, """
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
            writeUTF8String(notePath, Files.readString(notePath).replace("A café", "B café"));
            assertEquals("B café 🌱.\n\nid: body-decoy\n", note.getContent());
            // A second call reads the backing file rather than returning cached content.
            Files.delete(notePath);
            assertEquals("Invented title", note.getTitle());
            assertThrows(IOException.class, note::getContent);
        }
    }

    @Test
    void emptyNoteAndUnresolvedParentAreSupported() throws IOException {
        writeUTF8String(temporary.resolve("empty.md"), """
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
            writeUTF8String(temporary.resolve(id + ".md"), """
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
        writeUTF8String(temporary.resolve("item.md"), """
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
        writeUTF8String(file, "Title\n\n" + body + "\n\nid: stream\ntype_: 1");
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
            writeUTF8String(file, "");
            assertEquals(expected.length, entry.getContentSize());
            try (var stream = entry.openContentStream()) {
                assertThrows(java.io.EOFException.class, stream::read);
            }
        }
    }

    @Test
    void streamsBinaryResourcesAndLeavesMetadataUntouched() throws IOException {
        writeUTF8String(temporary.resolve("resource.md"), """
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
        writeUTF8String(temporary.resolve("resource.md"), """
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
            writeUTF8String(temporary.resolve("resource.md"), """
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
        writeUTF8String(temporary.resolve("resource.md"), """
                Invented attachment

                id: resource
                file_extension: bin
                type_: 4""");
        assertThrows(IllegalStateException.class, () -> JoplinExportDirectory.open(temporary));
    }

    /**
     * Lays out {@code payload} the way git-annex stores it: as a
     * read-only object under {@code .git/annex/objects} in
     * {@code repository}, with {@code link} a relative symbolic link
     * to it.  Skips the test where symbolic links cannot be created,
     * e.g. on Windows without Developer Mode or administrator rights.
     *
     * @return the annexed object
     */
    private static Path
    annex(Path repository, Path link, byte[] payload)
    throws IOException
    {
        String key = "SHA256E-s" + payload.length + "--invented"
                + link.getFileName().toString().replaceFirst("^[^.]*", "");
        Path object = repository.resolve(".git/annex/objects/Ab/Cd").resolve(key).resolve(key);
        Files.createDirectories(object.getParent());
        Files.write(object, payload);
        assertTrue(object.toFile().setReadOnly());
        try {
            Files.createSymbolicLink(link, link.getParent().relativize(object));
        } catch (UnsupportedOperationException | FileSystemException e) {
            abort("Cannot create symbolic links here: " + e.getMessage());
        }
        return object;
    }

    /**
     * An export checked into a git-annex repository, three directories
     * below its root, as in {@code backup/joplin/2024-09-22_iPhone}.
     */
    private Path
    annexedExport()
    throws IOException
    {
        Path export = temporary.resolve("backup/joplin/2024-09-22_iPhone");
        Files.createDirectories(export.resolve("resources"));
        return export;
    }

    @Test
    void followsAnnexedResourceWithExplicitExtension() throws IOException {
        Path export = annexedExport();
        writeUTF8String(export.resolve("resource.md"), """
                Invented attachment

                id: resource
                file_extension: png
                size: 6
                type_: 4""");
        byte[] payload = { 0, 1, (byte) 255, 10, 13, 0 };
        Path link = export.resolve("resources/resource.png");
        annex(temporary, link, payload);
        assertEquals(Path.of("../../../../.git/annex/objects/Ab/Cd/SHA256E-s6--invented.png/SHA256E-s6--invented.png"),
                Files.readSymbolicLink(link));
        try (var archive = JoplinExportDirectory.open(export)) {
            JoplinEntry entry = archive.entries().get("resource");
            assertEquals(payload.length, entry.getResourceSize());
            try (var stream = entry.openResourceStream()) {
                assertArrayEquals(payload, stream.readAllBytes());
            }
        }
    }

    @Test
    void followsAnnexedResourceWithoutExplicitExtension() throws IOException {
        Path export = annexedExport();
        writeUTF8String(export.resolve("resource.md"), """
                Invented attachment

                id: resource
                file_extension:
                mime: image/jpeg
                size: 1
                type_: 4""");
        annex(temporary, export.resolve("resources/resource.jpg"), new byte[] { 42 });
        try (var archive = JoplinExportDirectory.open(export)) {
            JoplinEntry entry = archive.entries().get("resource");
            assertEquals(1, entry.getResourceSize());
            try (var stream = entry.openResourceStream()) {
                assertEquals(42, stream.read());
                assertEquals(-1, stream.read());
            }
        }
    }

    @Test
    void rejectsDroppedAnnexedResources() throws IOException {
        // After `git annex drop', the link stays but its object is gone.
        Path export = annexedExport();
        for (String extension : java.util.List.of("png", "")) {
            writeUTF8String(export.resolve("resource.md"), """
                    Invented attachment

                    id: resource
                    file_extension: %s
                    mime: image/png
                    size: 1
                    type_: 4""".formatted(extension));
            Path link = export.resolve("resources/resource.png");
            Path object = annex(temporary, link, new byte[] { 42 });
            Files.delete(object);
            assertThrows(IOException.class, () -> JoplinExportDirectory.open(export));
            Files.delete(link);
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
