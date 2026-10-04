package org.rapidcow.archive.bridge.apple.notes.store;

/**
 * Thrown when a {@code NoteStore.sqlite} cannot be opened or read, or does
 * not look like a Core Data store.
 *
 * <p>Unchecked so that it can escape from the lazy
 * {@link java.util.stream.Stream}s that {@link NoteStore} hands out.
 */
public class NoteStoreException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	/**
	 * Constructs an exception with the given detail message.
	 *
	 * @param message the detail message
	 */
	public NoteStoreException(String message) {
		super(message);
	}

	/**
	 * Constructs an exception with the given detail message and cause.
	 *
	 * @param message the detail message
	 * @param cause the underlying failure
	 */
	public NoteStoreException(String message, Throwable cause) {
		super(message, cause);
	}
}
