package org.rapidcow.ai.archive.bridge.tophatch.concepts;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A read-only view of a Concepts ZIP, handed to each {@link ConceptsBackend}.
 * Nothing is extracted to the filesystem. This class is not thread-safe.
 */
public final class ConceptsContainer implements AutoCloseable {
	/** Allocation limits, measured after decompression. */
	public record Limits(int maxEntryBytes, int maxEntries) {
		public static final Limits DEFAULT = new Limits(256 * 1024 * 1024, 100_000);

		public Limits {
			if (maxEntryBytes < 1 || maxEntryBytes == Integer.MAX_VALUE || maxEntries < 1) {
				throw new IllegalArgumentException("Limits must be positive and below Integer.MAX_VALUE");
			}
		}
	}

	private final ZipFile zip;
	private final Limits limits;
	private final List<ConceptsDrawing.Entry> entries;

	private ConceptsContainer(ZipFile zip, Limits limits) throws IOException {
		this.zip = zip;
		this.limits = limits;
		var found = new ArrayList<ConceptsDrawing.Entry>();
		var unique = new HashSet<String>();
		var iterator = zip.entries();
		while (iterator.hasMoreElements()) {
			ZipEntry entry = iterator.nextElement();
			if (found.size() == limits.maxEntries()) {
				throw new IOException("Concepts ZIP exceeds entry limit");
			}
			if (!unique.add(entry.getName())) {
				throw new IOException("Concepts ZIP has duplicate entry " + entry.getName());
			}
			found.add(new ConceptsDrawing.Entry(entry.getName(), entry.getSize()));
		}
		entries = List.copyOf(found);
	}

	/** Opens a ZIP and reads its directory. */
	public static ConceptsContainer open(Path path, Limits limits) throws IOException {
		Objects.requireNonNull(limits, "limits");
		ZipFile zip = new ZipFile(path.toFile());
		try {
			return new ConceptsContainer(zip, limits);
		} catch (IOException | RuntimeException | Error exception) {
			try {
				zip.close();
			} catch (IOException closeException) {
				exception.addSuppressed(closeException);
			}
			throw exception;
		}
	}

	/** Every entry, in directory order. */
	public List<ConceptsDrawing.Entry> entries() {
		return entries;
	}

	/**
	 * Finds an entry by name. Some older archives prefix every name with
	 * {@code /}, so that spelling is accepted too.
	 */
	public Optional<String> find(String name) {
		for (String candidate : List.of(name, "/" + name)) {
			if (zip.getEntry(candidate) != null) {
				return Optional.of(candidate);
			}
		}
		return Optional.empty();
	}

	/** Whether {@link #find} succeeds. */
	public boolean has(String name) {
		return find(name).isPresent();
	}

	/** Reads an entry found by {@link #find}, failing if it is absent or too large. */
	public byte[] read(String name) throws IOException {
		String actual = find(name).orElseThrow(() -> new IOException("Concepts ZIP has no " + name));
		try (InputStream input = zip.getInputStream(zip.getEntry(actual))) {
			byte[] bytes = input.readNBytes(limits.maxEntryBytes() + 1);
			if (bytes.length > limits.maxEntryBytes()) {
				throw new IOException(name + " exceeds entry size limit");
			}
			return bytes;
		}
	}

	@Override
	public void close() throws IOException {
		zip.close();
	}
}
