package org.rapidcow.ai.archive.bridge.cozic.joplin;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** An item in a Joplin export. */
public interface JoplinEntry {
    String getId();
    JoplinType getType();
    /**
     * The title is the first line of a Joplin entry.
     * It is followed by a blank line before the start
     * of the main content.
     */
    String getTitle();

    /** Raw properties in their original trailer order. */
    Map<String, String> getMetadata();

    /** Empty for root items and parents not included in this export. */
    Optional<JoplinEntry> getParent();

    /** Direct children, immutable and unordered; empty for items without children. */
    List<JoplinEntry> getChildren();

    /**
     * Reads the note body on demand, without its title or metadata.
     * Returns an empty string for an empty note or a non-note item.
     * No content is cached; the backing export must remain unchanged.
     *
     * @throws IOException if the backing file cannot be read
     */
    String getContent() throws IOException;
}
