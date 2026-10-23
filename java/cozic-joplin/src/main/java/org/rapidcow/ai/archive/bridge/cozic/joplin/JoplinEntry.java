package org.rapidcow.ai.archive.bridge.cozic.joplin;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/** An item in a Joplin export. */
public interface JoplinEntry {
    String getId();
    JoplinType getType();
    String firstLine();

    /** Raw properties in their original trailer order. */
    Map<String, String> metadata();

    /** Empty for root items and parents not included in this export. */
    Optional<JoplinEntry> getParent();

    /**
     * Reads the note body on demand, without its title or metadata.
     * Returns an empty string for an empty note or a non-note item.
     * No content is cached; the backing export must remain unchanged.
     * @throws IOException if the backing file cannot be read
     */
    String getContent() throws IOException;
}
