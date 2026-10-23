package org.rapidcow.ai.archive.bridge.cozic.joplin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;

/** A metadata snapshot of an extracted JEX archive. Note bodies are not read. */
public final class JoplinExportDirectory implements AutoCloseable {
    private final Path directory;
    private final Map<String, Entry> entries;
    private final Map<String, List<JoplinEntry>> childrenByParent;

    private JoplinExportDirectory(Path directory, Map<String, Entry> entries,
            Map<String, List<JoplinEntry>> childrenByParent) {
        this.directory = directory;
        this.entries = Collections.unmodifiableMap(entries);
        this.childrenByParent = Collections.unmodifiableMap(childrenByParent);
    }

    /**
     * Reads the first line and trailing properties of each top-level .md file.
     * Resource payloads, symbolic links, and subdirectories are not opened.
     * An optional UTF-8 byte-order mark is removed from the first line. Unknown properties
     * are retained as strings; this format is not parsed as YAML. Metadata uses
     * LF separators; literal CR in the trailer is rejected. The first line is
     * preserved verbatim, including any literal CR (apart from the optional BOM).
     * The title must be followed by an empty LF-delimited line.
     */
    public static JoplinExportDirectory open(Path directory) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("JEX directory does not exist or is not a directory");
        }
        var entries = new HashMap<String, Entry>();
        var childrenByParent = new HashMap<String, List<JoplinEntry>>();
        var archive = new JoplinExportDirectory(root, entries, childrenByParent);
        try (var paths = Files.newDirectoryStream(root)) {
            for (Path path : paths) {
                var attributes = Files.readAttributes(path, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attributes.isDirectory()) continue;
                if (!attributes.isRegularFile()) continue;
                if (!path.getFileName().toString().endsWith(".md")) continue;
                Entry entry = archive.readEntry(path);
                entries.put(entry.getId(), entry);
                String parentId = entry.getMetadata().get("parent_id");
                if (parentId != null && !parentId.isEmpty()) {
                    childrenByParent.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(entry);
                }
            }
        }
        childrenByParent.replaceAll((parentId, children) -> List.copyOf(children));
        return archive;
    }

    public Path directory() {
        return directory;
    }

    /** Items indexed by ID, with no iteration-order guarantee. */
    public Map<String, Entry> entries() {
        return entries;
    }

    /** No handles remain open after {@link #open(Path)} returns. */
    @Override
    public void close() throws IOException {
        // The snapshot remains usable after close.
    }

    /** Metadata values are raw strings, including empty values and escaped text. */
    public final class Entry implements JoplinEntry {
        private final Path path;
        private final String firstLine;
        private final Map<String, String> metadata;
        private final JoplinType type;
        private final long contentStart;
        private final long contentSize;

        private Entry(Path path, String firstLine, Map<String, String> metadata,
                JoplinType type, long contentStart, long contentEnd) {
            this.path = path;
            this.firstLine = firstLine;
            this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
            this.type = type;
            this.contentStart = contentStart;
            this.contentSize = type == JoplinType.NOTE ? contentEnd - contentStart : 0;
        }

        public Path getFilePath() { return path; }

        @Override
        public String getTitle() { return firstLine; }

        @Override
        public Map<String, String> getMetadata() { return metadata; }

        @Override
        public String getId() {
            String name = path.getFileName().toString();
            return name.substring(0, name.length() - 3);
        }

        @Override
        public JoplinType getType() { return type; }

        @Override
        public Optional<JoplinEntry> getParent() {
            return Optional.ofNullable(entries.get(metadata.get("parent_id")));
        }

        @Override
        public List<JoplinEntry> getChildren() {
            return childrenByParent.getOrDefault(getId(), Collections.emptyList());
        }

        @Override
        public String getContent() throws IOException {
            try (var stream = openContentStream()) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        @Override
        public long getContentSize() { return contentSize; }

        @Override
        public InputStream openContentStream() throws IOException {
            if (type != JoplinType.NOTE) return InputStream.nullInputStream();
            return new ContentInputStream(path, contentStart, contentSize);
        }

        @Override
        public InputStream openResourceStream() throws IOException {
            return Files.newInputStream(resourcePath());
        }

        @Override
        public long getResourceSize() throws IOException {
            return Files.size(resourcePath());
        }

        private Path resourcePath() throws IOException {
            if (type != JoplinType.RESOURCE) {
                throw new IllegalStateException("Entry is not a resource");
            }
            Path resources = directory.resolve("resources");
            String extension = metadata.getOrDefault("file_extension", "");
            if (!extension.isEmpty()) {
                if (extension.indexOf('/') >= 0 || extension.indexOf('\\') >= 0) {
                    throw new IOException("Invalid resource extension");
                }
                Path payload = resources.resolve(getId() + "." + extension);
                if (!Files.isRegularFile(payload, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Resource payload is not a regular file");
                }
                return payload;
            }
            // Older exports can infer the extension from MIME. Resolve the actual
            // exported filename instead of duplicating Joplin's MIME lookup table.
            Path found = null;
            try (var paths = Files.newDirectoryStream(resources)) {
                for (Path candidate : paths) {
                    String name = candidate.getFileName().toString();
                    if (!name.equals(getId()) && !name.startsWith(getId() + ".")) continue;
                    if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (found != null) throw new IOException("Ambiguous resource payload");
                    found = candidate;
                }
            }
            if (found == null) throw new IOException("Missing resource payload");
            return found;
        }

        // Avoid accidentally logging titles or metadata that may contain private text.
        @Override
        public String toString() {
            return "JoplinExportDirectory.Entry";
        }
    }

    private Entry readEntry(Path path) throws IOException {
        try (var file = new RandomAccessFile(path.toFile(), "r")) {
            var first = new ByteArrayOutputStream();
            int b;
            while ((b = file.read()) != -1 && b != '\n') first.write(b);
            String firstLine = new String(first.toByteArray(), StandardCharsets.UTF_8);
            if (firstLine.startsWith("\uFEFF")) firstLine = firstLine.substring(1);
            long firstLineEnd = file.getFilePointer();
            // Check only the required separator byte, never scan a second line.
            if (b != '\n' || file.read() != '\n') {
                throw new IOException("Missing blank line after JEX title");
            }
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
            JoplinType type;
            try {
                type = JoplinType.fromValue(Integer.parseInt(metadata.get("type_")));
            } catch (IllegalArgumentException error) {
                throw new IOException("Invalid Joplin item type");
            }
            long contentStart = firstLineEnd + 1;
            return new Entry(path, firstLine, metadata, type,
                    contentStart, Math.max(contentStart, cursor));
        }
    }

}
