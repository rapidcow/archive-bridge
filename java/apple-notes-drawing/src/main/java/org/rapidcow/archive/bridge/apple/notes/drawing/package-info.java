/**
 * Types and decode logic for Apple Notes ink markup drawings that are
 * common to both {@link org.rapidcow.archive.bridge.apple.notes.drawing.v2}
 * and {@link org.rapidcow.archive.bridge.apple.notes.drawing.paper} -
 * today, just {@code StrokeData} (protoc-generated from {@code
 * StrokeData.proto}) and the hand-written point/channel decoder built on
 * top of it ({@link StrokeChannel}, {@link ChannelReading}, {@link
 * StrokePoint}, {@link PointsData}).
 *
 * <p>Generated and hand-written classes deliberately sit in the same
 * package here, mirroring how Apple's own {@code drawing::} C++ namespace
 * itself makes no such distinction (see
 * {@code ai/notes/apple/com.apple.paper/proto-schema.md} for how
 * Coherence's unrelated, genuinely-generated {@code Proto_*} Swift types
 * differ from this). Gradle's protobuf plugin keeps the two on separate
 * source roots ({@code src/main/java} vs. {@code
 * build/generated/sources/proto}), so regeneration never touches
 * hand-written files despite the shared package.
 */
package org.rapidcow.archive.bridge.apple.notes.drawing;
