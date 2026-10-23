package org.rapidcow.ai.archive.bridge.tophatch.concepts;

import java.io.IOException;

/**
 * Decodes one platform's flavor of Concepts archive. Both flavors are ZIPs,
 * told apart only by their entries, so {@link ConceptsReader} asks every
 * backend whether it recognizes an archive before choosing one.
 */
public interface ConceptsBackend {
	/** A short name for error messages. */
	String name();

	/**
	 * Whether the archive looks like this backend's flavor. This should only
	 * look at entry names, and must not claim archives another backend owns.
	 */
	boolean recognizes(ConceptsContainer container);

	/** Decodes a recognized archive. */
	ConceptsDrawing read(ConceptsContainer container) throws IOException;
}
