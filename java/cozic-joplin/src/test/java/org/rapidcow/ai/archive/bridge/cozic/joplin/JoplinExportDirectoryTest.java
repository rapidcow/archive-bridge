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
        Files.writeString(temporary.resolve("b.md"),
                "Invented café 🌱\n\nAn invented note.\n\nid: body-decoy\n\n"
                + "id: example\nsource_application: net.cozic.joplin-mobile\n"
                + "author: \nfuture_field: a: b\\nnext\ntype_: 1\n");
        Files.writeString(temporary.resolve("a.md"), "\n\nInvented body\n\nid: empty-title\ntype_: 2");
        Files.createDirectory(temporary.resolve("resources"));
        Files.createDirectory(temporary.resolve("directory.md"));
        Files.writeString(temporary.resolve("resources/ignored.md"), "not an item");
        Files.writeString(temporary.resolve("ignored.bin"), "not an item");
        try (var archive = JoplinExportDirectory.open(temporary)) {
            assertEquals(2, archive.entries().size());
            assertEquals("", archive.entries().get(0).firstLine());
            var note = archive.entries().get(1);
            assertEquals("Invented café 🌱", note.firstLine());
            assertEquals("example", note.metadata().get("id"));
            assertEquals("", note.metadata().get("author"));
            assertEquals("a: b\\nnext", note.metadata().get("future_field"));
            assertThrows(UnsupportedOperationException.class, () -> note.metadata().put("id", "other"));
            assertThrows(UnsupportedOperationException.class, () -> archive.entries().clear());
        }
    }

    @Test
    void supportsUtf8BomWithEmptyFirstLine() throws IOException {
        Files.writeString(temporary.resolve("item.md"), "\uFEFF\n\nInvented body\n\nid: example\ntype_: 1\n");
        try (var archive = JoplinExportDirectory.open(temporary)) {
            assertEquals("", archive.entries().get(0).firstLine());
            assertEquals("example", archive.entries().get(0).metadata().get("id"));
        }
    }

    @Test
    void supportsLfAndEmptyBody() throws IOException {
        Files.writeString(temporary.resolve("item.md"), "\n\nid: example\ntype_: 2\n");
        try (var archive = JoplinExportDirectory.open(temporary)) {
            assertEquals("2", archive.entries().get(0).metadata().get("type_"));
        }
    }

    @Test
    void rejectsCrLfMetadata() throws IOException {
        Files.writeString(temporary.resolve("item.md"), "Title\r\n\r\nid: example\r\ntype_: 4\r\n");
        var error = assertThrows(IOException.class, () -> JoplinExportDirectory.open(temporary));
        assertEquals("Literal CR is not allowed in JEX metadata", error.getMessage());
    }

    @Test
    void preservesLiteralCrInFirstLine() throws IOException {
        Files.writeString(temporary.resolve("item.md"), "Icon\r\n\nid: example\ntype_: 2");
        try (var archive = JoplinExportDirectory.open(temporary)) {
            assertEquals("Icon\r", archive.entries().get(0).firstLine());
        }
    }

    @Test
    void exposesOnlyFirstPhysicalLineOfMultilineTitle() throws IOException {
        Files.writeString(temporary.resolve("item.md"), "First\nSecond\n\nid: example\ntype_: 2");
        try (var archive = JoplinExportDirectory.open(temporary)) {
            assertEquals("First", archive.entries().get(0).firstLine());
            assertEquals("example", archive.entries().get(0).metadata().get("id"));
        }
    }

    @Test
    void rejectsMalformedTrailerWithoutEchoingText() throws IOException {
        Files.writeString(temporary.resolve("item.md"), "Title\n\nInvented body\n\nprivate malformed trailer\n");
        var error = assertThrows(IOException.class, () -> JoplinExportDirectory.open(temporary));
        assertEquals("Invalid JEX metadata trailer", error.getMessage());
    }

    @Test
    void rejectsDuplicateMetadataAndMissingIdentity() throws IOException {
        Path item = temporary.resolve("item.md");
        Files.writeString(item, "Title\n\nid: one\nid: two\ntype_: 1\n");
        assertThrows(IOException.class, () -> JoplinExportDirectory.open(temporary));
        Files.writeString(item, "Title\n\ntype_: 1\n");
        assertThrows(IOException.class, () -> JoplinExportDirectory.open(temporary));
    }

    @Test
    void requiresDirectoryAndAllowsEmptyDirectory() throws IOException {
        assertThrows(IOException.class, () -> JoplinExportDirectory.open(temporary.resolve("missing")));
        try (var archive = JoplinExportDirectory.open(temporary)) {
            assertTrue(archive.entries().isEmpty());
        }
    }
}
