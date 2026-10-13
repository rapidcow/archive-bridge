package org.rapidcow.archive.bridge.apple.notes.store.ai;

/**
 * Reads {@code com.apple.drawing.2} ink markup attachments out of a
 * pre-Coherence Apple Notes {@code NoteStore.sqlite}: rows with {@code
 * ZTYPEUTI = 'com.apple.drawing.2'} in {@code ZICCLOUDSYNCINGOBJECT}, whose
 * {@code ZMERGEABLEDATA1} column holds the envelope + {@link
 * org.rapidcow.archive.bridge.apple.notes.proto.StrokeData}-bearing blob
 * described in {@code ai/tools/apple-notes-drawing-2.proto}.
 *
 * <p>Stub: connection/query plumbing not implemented yet. Reference
 * behavior to port is {@code ai/tools/apple-notes-drawing-decode.pl}'s
 * {@code connect_db}/{@code fetch_blob}/{@code list_attachments}
 * (including its read-only {@code immutable=1} URI mode).
 */
public final class NoteStoreDrawings {
    private NoteStoreDrawings() {}
}
