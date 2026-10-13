package org.rapidcow.archive.bridge.apple.notes.store.ai;

/**
 * A Core Data entity, as declared in the {@code Z_PRIMARYKEY} table of a
 * {@code NoteStore.sqlite}: {@code ICNote}, {@code ICFolder}, and so on.
 * Obtained from a {@link NoteStore}; never constructed directly.
 *
 * <p>Entity numbers ({@link #id()}) are assigned by the model and are
 * only meaningful within one store, so look entities up by
 * {@linkplain #name() name}.
 */
public final class NoteStoreEntity {
	private final int id;
	private final String name;
	private final int superId;

	NoteStoreEntity(int id, String name, int superId) {
		this.id = id;
		this.name = name;
		this.superId = superId;
	}

	/**
	 * Returns the {@code Z_ENT} value that marks this entity's rows.
	 *
	 * @return the entity number
	 */
	public int id() {
		return id;
	}

	/**
	 * Returns the entity name, such as {@code "ICNote"}.
	 *
	 * @return the name
	 */
	public String name() {
		return name;
	}

	/**
	 * Returns the {@linkplain #id() number} of the parent entity.
	 *
	 * @return the parent's entity number, or 0 if this entity has none
	 */
	public int superId() {
		return superId;
	}

	@Override
	public String toString() {
		return name + " (id=" + id + ")";
	}
}
