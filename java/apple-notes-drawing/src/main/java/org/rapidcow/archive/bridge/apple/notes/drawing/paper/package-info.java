/**
 * Decoder for {@code com.apple.paper} ("Coherence") ink markup bundles.
 *
 * <p>This is the newer Apple Notes drawing storage that superseded
 * {@code com.apple.drawing.2} (decoded by {@link
 * org.rapidcow.archive.bridge.apple.notes.drawing.v2}) for drawings
 * created or edited on a device running the newer "Coherence" Notes
 * storage engine - found as {@code Database/data.sqlite3} inside each
 * {@code *.bundle} directory under a {@code Paper/Bundles/} directory.
 * {@code com.apple.paper} is a placeholder name here, not a confirmed
 * UTI string.
 *
 * <p>The bundle's rows parse as {@code proto2}-encoded messages built
 * on the shared wire types in {@link
 * org.rapidcow.archive.bridge.apple.notes.proto}. See {@code
 * ai/notes/apple/com.apple.paper/} in the repository root for the
 * reverse-engineering notes this decoder is built from.
 */
package org.rapidcow.archive.bridge.apple.notes.drawing.paper;
