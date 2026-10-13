package org.rapidcow.ai.archive.bridge.tophatch.concepts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Reads any Concepts archive by choosing the one backend that recognizes it.
 * The file name is never consulted (annexed copies are named {@code *.zip}).
 */
public final class ConceptsReader {
	/** Every backend implemented so far. */
	public static final List<ConceptsBackend> DEFAULT_BACKENDS = List.of(new MessagePackBackend());

	private final List<ConceptsBackend> backends;
	private final ConceptsContainer.Limits limits;

	/** A reader using {@link #DEFAULT_BACKENDS} and default limits. */
	public ConceptsReader() {
		this(DEFAULT_BACKENDS, ConceptsContainer.Limits.DEFAULT);
	}

	public ConceptsReader(List<ConceptsBackend> backends, ConceptsContainer.Limits limits) {
		this.backends = List.copyOf(backends);
		this.limits = Objects.requireNonNull(limits, "limits");
	}

	/** Decodes an archive, failing unless exactly one backend recognizes it. */
	public ConceptsDrawing read(Path path) throws IOException {
		try (var container = ConceptsContainer.open(path, limits)) {
			return backendFor(container).read(container);
		}
	}

	/** The single backend recognizing an open archive. */
	public ConceptsBackend backendFor(ConceptsContainer container) throws IOException {
		List<ConceptsBackend> matches = backends.stream()
				.filter(backend -> backend.recognizes(container))
				.toList();
		if (matches.size() == 1) {
			return matches.get(0);
		}
		if (matches.isEmpty()) {
			String names = container.entries().stream()
					.map(ConceptsDrawing.Entry::name)
					.collect(Collectors.joining(", "));
			throw new IOException("No Concepts backend recognizes an archive with entries: " + names);
		}
		throw new IOException("Several Concepts backends recognize the archive: "
				+ matches.stream().map(ConceptsBackend::name).collect(Collectors.joining(", ")));
	}
}
