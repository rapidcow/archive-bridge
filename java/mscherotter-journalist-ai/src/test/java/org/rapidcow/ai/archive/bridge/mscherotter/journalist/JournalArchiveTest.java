package org.rapidcow.ai.archive.bridge.mscherotter.journalist;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalArchiveTest {
    @TempDir
    Path temporary;

    @Test
    void ordersActualItemsAndPreservesUnknownMetadata() throws IOException {
        String contents = """
                <PrototypePage><Items><Sketch/></Items></PrototypePage>
                <Pages>
                  <JournalPage Id="first"><TypicalSketch/><Items>
                    <Text><Text>synthetic text</Text></Text><FutureItem Custom="kept"/>
                  </Items></JournalPage>
                  <JournalPage Id="second"><Items><Sketch><InkBase64> A QI=\n</InkBase64></Sketch></Items></JournalPage>
                </Pages>
                """;
        try (var archive = JournalArchive.open(archive(contents, Map.of()))) {
            assertEquals(2, archive.pages().size());
            assertEquals("first", archive.pages().get(0).xml().getAttribute("Id"));
            assertEquals("Text", archive.pages().get(0).items().get(0).type());
            assertEquals("FutureItem", archive.pages().get(0).items().get(1).type());
            assertEquals("kept", archive.pages().get(0).items().get(1).xml().getAttribute("Custom"));
            assertArrayEquals(new byte[]{1, 2}, archive.inkBytes(archive.pages().get(1).items().get(0)).orElseThrow());
            assertTrue(archive.inkBytes(archive.pages().get(0).items().get(0)).isEmpty());
        }
    }

    @Test
    void entryReferenceTakesPrecedenceAndMissingReferenceFails() throws IOException {
        String contents = """
                <Pages><JournalPage><Items>
                  <Sketch Ink="ink.bin"><InkBase64>AQI=</InkBase64></Sketch>
                  <Sketch Ink="missing.bin"><InkBase64>AQI=</InkBase64></Sketch>
                  <Sketch><InkBase64>!!!</InkBase64></Sketch>
                </Items></JournalPage></Pages>
                """;
        try (var archive = JournalArchive.open(archive(contents, Map.of("ink.bin", new byte[]{3, 4})))) {
            var items = archive.pages().get(0).items();
            assertArrayEquals(new byte[]{3, 4}, archive.inkBytes(items.get(0)).orElseThrow());
            assertThrows(IOException.class, () -> archive.inkBytes(items.get(1)));
            assertThrows(IOException.class, () -> archive.inkBytes(items.get(2)));
        }
    }

    @Test
    void rejectsDtdsAndHonorsReadLimits() throws IOException {
        String malicious = "<!DOCTYPE Journal [<!ENTITY secret SYSTEM 'file:///nonexistent'>]>"
                + "<Journal xmlns='" + JournalArchive.NAMESPACE + "'>&secret;</Journal>";
        Path bad = zip(malicious, Map.of());
        assertThrows(IOException.class, () -> JournalArchive.open(bad));
        Path valid = archive("<Pages/>", Map.of("large.bin", new byte[40]));
        assertThrows(IOException.class, () -> JournalArchive.open(valid, new JournalArchive.Limits(8, 8, 10)));
        assertThrows(IOException.class, () -> JournalArchive.open(valid, new JournalArchive.Limits(1024, 8, 1)));
        try (var archive = JournalArchive.open(valid, new JournalArchive.Limits(1024, 8, 10))) {
            assertThrows(IOException.class, () -> archive.readAttachment("large.bin"));
        }
    }

    @Test
    void decodesOptionalPrivateInkInSyntheticArchive() throws IOException {
        String configured = System.getProperty("bridge.journalist.inkFixture", "");
        assumeTrue(!configured.isEmpty(), "No optional private ink fixture configured");
        Path fixture = Path.of(configured);
        assumeTrue(Files.isRegularFile(fixture), "Optional private ink fixture unavailable");
        byte[] ink = Files.readAllBytes(fixture);
        String contents = "<Pages><JournalPage><Items><Sketch Ink='ink.gif'/><Sketch><InkBase64>"
                + Base64.getEncoder().encodeToString(ink)
                + "</InkBase64></Sketch></Items></JournalPage></Pages>";
        try (var archive = JournalArchive.open(archive(contents, Map.of("ink.gif", ink)))) {
            var items = archive.pages().get(0).items();
            assertArrayEquals(archive.inkBytes(items.get(0)).orElseThrow(), archive.inkBytes(items.get(1)).orElseThrow());
            assertTrue(!archive.decodeInk(items.get(0)).orElseThrow().strokes().isEmpty());
            assertEquals(archive.decodeInk(items.get(0)).orElseThrow().strokes().size(),
                    archive.decodeInk(items.get(1)).orElseThrow().strokes().size());
        }
    }

    private Path archive(String contents, Map<String, byte[]> entries) throws IOException {
        return zip("<Journal xmlns='" + JournalArchive.NAMESPACE + "'>" + contents + "</Journal>", entries);
    }

    private Path zip(String xml, Map<String, byte[]> entries) throws IOException {
        Path path = Files.createTempFile(temporary, "synthetic-", ".journalist");
        try (var output = new ZipOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new ZipEntry("journal.xml"));
            output.write(xml.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return path;
    }
}
