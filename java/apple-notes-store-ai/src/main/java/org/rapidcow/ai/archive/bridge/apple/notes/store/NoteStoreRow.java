package org.rapidcow.ai.archive.bridge.apple.notes.store;

/**
 * A handle to one row of a {@link NoteStore}.
 *
 * <p>A {@code NoteStoreRow} remembers only where it lives, never what it holds:
 * every {@link #get(String)} is a query for that one column, so
 * holding a million of these costs a million small objects and no
 * blobs. It is only valid while its {@code NoteStore} is open.
 */
public final class NoteStoreRow {
	private final NoteStore store;
	private final String table;
	private final NoteStoreEntity entity;
	private final long pk;

	NoteStoreRow(NoteStore store, String table, NoteStoreEntity entity, long pk) {
		this.store = store;
		this.table = table;
		this.entity = entity;
		this.pk = pk;
	}

	/**
	 * Returns the row's primary key ({@code Z_PK}), which is only
	 * unique within its table.
	 *
	 * @return the primary key
	 */
	public long pk() {
		return pk;
	}

	/**
	 * Returns the row's own entity, which may be a subentity of the
	 * one it was looked up under.
	 *
	 * @return the entity named by this row's {@code Z_ENT}
	 */
	public NoteStoreEntity entity() {
		return entity;
	}

	/**
	 * Reads one column of this row.
	 *
	 * @param column the column name exactly as in the database,
	 *        suffix and all (e.g. {@code "ZTITLE2"})
	 * @return a {@link Long}, {@link Double}, {@link String} or
	 *         {@code byte[]}, or {@code null} for SQL NULL
	 * @throws IllegalArgumentException if the table has no such column
	 */
	public Object get(String column) {
		return store.value(table, column, pk);
	}

	/**
	 * Reads one column of this row as text.
	 *
	 * @param column the column name, as in {@link #get(String)}
	 * @return the value, or {@code null} for SQL NULL
	 * @throws IllegalArgumentException if the table has no such column
	 * @throws ClassCastException if the value is not text
	 */
	public String getString(String column) {
		return (String) get(column);
	}

	@Override
	public String toString() {
		return entity.name() + "#" + pk;
	}
}
