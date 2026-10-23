package org.rapidcow.ai.archive.bridge.cozic.joplin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A metadata snapshot of an extracted JEX archive. Note bodies are not read. */
public final class JoplinExportDirectory implements AutoCloseable {
    private final Path directory;
    private final List<Entry> entries;

    private JoplinExportDirectory(Path directory, List<Entry> entries) {
        this.directory = directory;
        this.entries = List.copyOf(entries);
    }

    /**
     * Reads the first line and trailing properties of each top-level .md file.
     * Resource payloads, symbolic links, and subdirectories are not opened.
     * An optional UTF-8 byte-order mark is removed from the first line. Unknown properties
     * are retained as strings; this format is not parsed as YAML. Metadata uses
     * LF separators; literal CR in the trailer is rejected. The first line is
     * preserved verbatim, including any literal CR (apart from the optional BOM).
     * This is the first physical line, not a reconstructed multiline title.
     */
    public static JoplinExportDirectory open(Path directory) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("JEX directory does not exist or is not a directory");
        }
        var entries = new ArrayList<Entry>();
        try (var paths = Files.list(root)) {
            for (Path path : paths.sorted().toList()) {
                var attributes = Files.readAttributes(path, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attributes.isDirectory()) continue;
                if (!attributes.isRegularFile()) continue;
                if (!path.getFileName().toString().endsWith(".md")) continue;
                entries.add(readEntry(path));
            }
        }
        return new JoplinExportDirectory(root, entries);
    }

    public Path directory() {
        return directory;
    }

    public List<Entry> entries() {
        return entries;
    }

    /** No handles remain open after {@link #open(Path)} returns. */
    @Override
    public void close() throws IOException {
        // The snapshot remains usable after close.
    }

    /** Metadata values are raw strings, including empty values and escaped text. */
    public record Entry(Path path, String firstLine, Map<String, String> metadata) {
        public Entry {
            metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        }

        // Avoid accidentally logging titles or metadata that may contain private text.
        @Override
        public String toString() {
            return "JoplinExportDirectory.Entry";
        }
    }

    private static Entry readEntry(Path path) throws IOException {
        try (var file = new RandomAccessFile(path.toFile(), "r")) {
            var first = new ByteArrayOutputStream();
            int b;
            while ((b = file.read()) != -1 && b != '\n') first.write(b);
            String firstLine = new String(first.toByteArray(), StandardCharsets.UTF_8);
            if (firstLine.startsWith("\uFEFF")) firstLine = firstLine.substring(1);
            long firstLineEnd = file.getFilePointer();
            long cursor = file.length();
            var reversed = new ArrayList<String>();
            boolean terminalLine = true;
            boolean separator = false;
            while (cursor > firstLineEnd) {
                var bytes = new ByteArrayOutputStream();
                while (cursor > firstLineEnd) {
                    file.seek(--cursor);
                    b = file.read();
                    if (b == '\n') break;
                    bytes.write(b);
                }
                if (cursor == firstLineEnd && b == '\n') separator = true;
                byte[] lineBytes = bytes.toByteArray();
                for (int i = 0, j = lineBytes.length - 1; i < j; i++, j--) {
                    byte swap = lineBytes[i];
                    lineBytes[i] = lineBytes[j];
                    lineBytes[j] = swap;
                }
                String line = new String(lineBytes, StandardCharsets.UTF_8);
                if (line.indexOf('\r') >= 0) {
                    throw new IOException("Literal CR is not allowed in JEX metadata");
                }
                if (terminalLine && line.isEmpty()) {
                    terminalLine = false;
                    continue;
                }
                terminalLine = false;
                if (line.isEmpty()) {
                    separator = true;
                    break;
                }
                if (!line.matches("[a-z][a-z0-9_]*:.*")) {
                    throw new IOException("Invalid JEX metadata trailer");
                }
                reversed.add(line);
            }
            if (!separator) throw new IOException("Missing JEX metadata separator");
            Collections.reverse(reversed);
            var metadata = new LinkedHashMap<String, String>();
            for (String line : reversed) {
                int colon = line.indexOf(':');
                String value = line.substring(colon + 1);
                if (value.startsWith(" ")) value = value.substring(1);
                if (metadata.putIfAbsent(line.substring(0, colon), value) != null) {
                    throw new IOException("Duplicate JEX metadata property");
                }
            }
            if (!metadata.containsKey("id") || !metadata.containsKey("type_")) {
                throw new IOException("Missing JEX item identity or type");
            }
            return new Entry(path, firstLine, metadata);
        }
    }

}
