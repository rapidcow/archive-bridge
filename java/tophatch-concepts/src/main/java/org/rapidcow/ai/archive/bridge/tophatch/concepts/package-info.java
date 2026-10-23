/**
 * Read-only access to Concepts (TopHatch) drawings: archive manifest and ink
 * strokes. {@link org.rapidcow.ai.archive.bridge.tophatch.concepts.ConceptsReader}
 * picks a {@link org.rapidcow.ai.archive.bridge.tophatch.concepts.ConceptsBackend}
 * by archive contents; only the Android/Windows MessagePack flavor
 * ({@code .concepts}) is implemented so far, while the iOS property-list
 * flavor ({@code .concept}) is meant to become a {@code PropertyListBackend}
 * producing the same {@link org.rapidcow.ai.archive.bridge.tophatch.concepts.ConceptsDrawing}.
 */
package org.rapidcow.ai.archive.bridge.tophatch.concepts;
