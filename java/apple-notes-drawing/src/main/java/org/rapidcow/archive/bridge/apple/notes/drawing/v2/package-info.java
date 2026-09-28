/**
 * Decoder for {@code com.apple.drawing.2} ink markup blobs.
 *
 * <p>These are the drawing attachments stored in a pre-Coherence Apple
 * Notes {@code NoteStore.sqlite}, as {@code
 * ZICCLOUDSYNCINGOBJECT.ZMERGEABLEDATA1} on rows with {@code ZTYPEUTI =
 * 'com.apple.drawing.2'}. Apple OSes no longer produce this UTI - it's
 * superseded by the newer "Coherence" format decoded by {@link
 * org.rapidcow.archive.bridge.apple.notes.drawing.paper} - so this
 * package is purely about reading old data, not interoperating with a
 * live system.
 *
 * <p>The blob itself is an optional envelope followed by a {@code
 * proto2}-encoded {@code Drawing} message, built on the shared wire
 * types in {@link org.rapidcow.archive.bridge.apple.notes.proto}. See
 * {@code ai/notes/apple/com.apple.drawing.2/} in the repository root
 * for the reverse-engineering notes this decoder is built from.
 */
package org.rapidcow.archive.bridge.apple.notes.drawing.v2;
