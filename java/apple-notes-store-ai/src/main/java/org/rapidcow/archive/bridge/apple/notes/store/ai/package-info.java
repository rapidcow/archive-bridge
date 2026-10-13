/**
 * NoteStore.sqlite reader (LLM-generated). {@link
 * org.rapidcow.archive.bridge.apple.notes.store.ai.NoteStore} opens a store
 * read-only and streams rows of its Core Data entities; {@link
 * org.rapidcow.archive.bridge.apple.notes.store.ai.NoteStoreModel} hands
 * out the model the store records about itself ({@code Z_METADATA},
 * {@code Z_MODELCACHE}) without interpreting it. Drawing attachments are
 * to be read via {@link
 * org.rapidcow.archive.bridge.apple.notes.store.ai.NoteStoreDrawings}
 * (a stub so far) and decoded by {@link
 * org.rapidcow.archive.bridge.apple.notes.drawing}.
 */
package org.rapidcow.archive.bridge.apple.notes.store.ai;
