package org.rapidcow.archive.bridge.apple.notes.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.sqlite.SQLiteConfig;

/**
 * A {@code NoteStore.sqlite}, opened read-only.
 *
 * <p>This is deliberately a thin layer over the Core Data tables and not
 * a model of them: a store can run to hundreds of megabytes, so nothing
 * is loaded up front. {@link #rows(String)} streams {@link NoteStoreRow} handles
 * lazily and {@link NoteStoreRow#get(String)} reads one column at a time.
 *
 * <p>The file is opened exactly as given. In particular this class
 * never looks for, creates or depends on a {@code -wal} or {@code -shm}
 * file next to it: a store that still has its changes in a WAL has to be
 * vacuumed into a standalone file first, which is the application's
 * decision to make and not this class's.
 *
 * <p>Instances are not thread-safe.
 */
public final class NoteStore implements AutoCloseable {
	private final Connection connection;
	private final Map<String, NoteStoreEntity> entitiesByName;
	private final Map<Integer, NoteStoreEntity> entitiesById;
	private final Set<String> tables;
	private final Map<String, Set<String>> columnsByTable = new HashMap<>();
	private boolean closed;

	private NoteStore(Connection connection, Map<String, NoteStoreEntity> entitiesByName, Set<String> tables) {
		this.connection = connection;
		this.entitiesByName = entitiesByName;
		this.tables = tables;
		Map<Integer, NoteStoreEntity> byId = new HashMap<>();
		for (NoteStoreEntity e : entitiesByName.values()) {
			byId.put(e.id(), e);
		}
		this.entitiesById = byId;
	}

	/**
	 * Opens a store read-only.
	 *
	 * @param path the {@code NoteStore.sqlite} file
	 * @return the open store; close it when done
	 * @throws NoteStoreException if the file does not exist, is not a
	 *         SQLite database, or is not a Core Data store
	 */
	public static NoteStore open(Path path) {
		if (!Files.isRegularFile(path)) {
			throw new NoteStoreException("no such file: " + path);
		}
		SQLiteConfig config = new SQLiteConfig();
		config.setReadOnly(true);
		Connection connection = null;
		try {
			connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath(), config.toProperties());
			Set<String> tables = readTables(connection);
			if (!tables.contains("Z_PRIMARYKEY")) {
				throw new NoteStoreException("not a Core Data store (no Z_PRIMARYKEY): " + path);
			}
			return new NoteStore(connection, readEntities(connection), tables);
		} catch (SQLException e) {
			closeQuietly(connection);
			throw new NoteStoreException("cannot read " + path + ": " + e.getMessage(), e);
		} catch (RuntimeException e) {
			closeQuietly(connection);
			throw e;
		}
	}

	private static Set<String> readTables(Connection c) throws SQLException {
		Set<String> tables = new HashSet<>();
		try (Statement st = c.createStatement();
				ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
			while (rs.next()) {
				tables.add(rs.getString(1));
			}
		}
		return tables;
	}

	private static Map<String, NoteStoreEntity> readEntities(Connection c) throws SQLException {
		Map<String, NoteStoreEntity> byName = new LinkedHashMap<>();
		try (Statement st = c.createStatement();
				ResultSet rs = st.executeQuery("SELECT Z_ENT, Z_NAME, Z_SUPER FROM Z_PRIMARYKEY ORDER BY Z_ENT")) {
			while (rs.next()) {
				NoteStoreEntity e = new NoteStoreEntity(rs.getInt(1), rs.getString(2), rs.getInt(3));
				byName.put(e.name(), e);
			}
		}
		return Collections.unmodifiableMap(byName);
	}

	/**
	 * Lists the entities the store declares, ordered by entity number.
	 *
	 * @return an unmodifiable list
	 */
	public List<NoteStoreEntity> entities() {
		requireOpen();
		return List.copyOf(entitiesByName.values());
	}

	/**
	 * Looks an entity up by name.
	 *
	 * @param name the entity name, such as {@code "ICNote"}
	 * @return the entity, or empty if the store does not declare it
	 */
	public Optional<NoteStoreEntity> entity(String name) {
		requireOpen();
		return Optional.ofNullable(entitiesByName.get(name));
	}

	/**
	 * Counts the rows of an entity, including those of its subentities.
	 *
	 * @param entityName the entity name, such as {@code "ICNote"}
	 * @return the number of rows
	 * @throws IllegalArgumentException if there is no such entity or it
	 *         has no table of its own
	 */
	public long count(String entityName) {
		Source source = source(entityName);
		String sql = "SELECT count(*) FROM \"" + source.table + "\" WHERE Z_ENT IN " + source.placeholders();
		try (PreparedStatement st = connection.prepareStatement(sql)) {
			source.bind(st);
			try (ResultSet rs = st.executeQuery()) {
				rs.next();
				return rs.getLong(1);
			}
		} catch (SQLException e) {
			throw new NoteStoreException("count " + entityName + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Streams the rows of an entity, including those of its
	 * subentities, in primary key order.
	 *
	 * <p>The stream is lazy: rows are fetched as it is consumed. It
	 * holds a database cursor, so close it (try-with-resources) unless
	 * it is read to the end.
	 *
	 * @param entityName the entity name, such as {@code "ICNote"}
	 * @return the rows
	 * @throws IllegalArgumentException if there is no such entity or it
	 *         has no table of its own
	 */
	public Stream<NoteStoreRow> rows(String entityName) {
		Source source = source(entityName);
		String sql = "SELECT Z_PK, Z_ENT FROM \"" + source.table + "\" WHERE Z_ENT IN "
				+ source.placeholders() + " ORDER BY Z_PK";
		PreparedStatement st = null;
		try {
			st = connection.prepareStatement(sql);
			source.bind(st);
			ResultSet rs = st.executeQuery();
			StoreRowSpliterator spliterator = new StoreRowSpliterator(source.table, st, rs);
			return StreamSupport.stream(spliterator, false).onClose(spliterator::close);
		} catch (SQLException e) {
			closeQuietly(st);
			throw new NoteStoreException("rows " + entityName + ": " + e.getMessage(), e);
		}
	}

	private static void closeQuietly(AutoCloseable c) {
		if (c != null) {
			try {
				c.close();
			} catch (Exception ignored) {
				// Already failing, or already closed.
			}
		}
	}

	private final class StoreRowSpliterator extends Spliterators.AbstractSpliterator<NoteStoreRow> {
		private final String table;
		private final PreparedStatement statement;
		private final ResultSet results;
		private boolean done;

		StoreRowSpliterator(String table, PreparedStatement statement, ResultSet results) {
			super(Long.MAX_VALUE, ORDERED | NONNULL);
			this.table = table;
			this.statement = statement;
			this.results = results;
		}

		@Override
		public boolean tryAdvance(java.util.function.Consumer<? super NoteStoreRow> action) {
			if (done) {
				return false;
			}
			try {
				if (!results.next()) {
					close();
					return false;
				}
				NoteStoreEntity entity = entitiesById.get(results.getInt(2));
				if (entity == null) {
					throw new NoteStoreException("row " + results.getLong(1) + " of " + table
							+ " has undeclared Z_ENT " + results.getInt(2));
				}
				action.accept(new NoteStoreRow(NoteStore.this, table, entity, results.getLong(1)));
				return true;
			} catch (SQLException e) {
				close();
				throw new NoteStoreException("reading " + table + ": " + e.getMessage(), e);
			}
		}

		void close() {
			done = true;
			closeQuietly(results);
			closeQuietly(statement);
		}
	}

	/** Where an entity's rows live: a table, filtered to a set of Z_ENT values. */
	private record Source(String table, List<Integer> entityIds) {
		String placeholders() {
			return "(" + String.join(",", Collections.nCopies(entityIds.size(), "?")) + ")";
		}

		void bind(PreparedStatement st) throws SQLException {
			for (int i = 0; i < entityIds.size(); i++) {
				st.setInt(i + 1, entityIds.get(i));
			}
		}
	}

	/**
	 * Core Data stores a whole entity hierarchy in the table named for its
	 * root entity ({@code ICNote} lives in {@code ZICCLOUDSYNCINGOBJECT}
	 * because {@code ICCloudSyncingObject} is its root), told apart by
	 * {@code Z_ENT}. So an entity's rows are the rows of its root's table
	 * whose {@code Z_ENT} is the entity or any of its descendants.
	 */
	private Source source(String entityName) {
		requireOpen();
		NoteStoreEntity entity = entitiesByName.get(entityName);
		if (entity == null) {
			throw new IllegalArgumentException("no such entity: " + entityName);
		}
		NoteStoreEntity root = entity;
		while (root.superId() != 0) {
			root = entitiesById.get(root.superId());
		}
		String table = "Z" + root.name().toUpperCase(Locale.ROOT);
		if (!tables.contains(table)) {
			throw new IllegalArgumentException("entity " + entityName + " has no table (expected " + table + ")");
		}
		List<Integer> ids = new ArrayList<>();
		for (NoteStoreEntity e : entitiesByName.values()) {
			if (isSameOrDescendant(e, entity)) {
				ids.add(e.id());
			}
		}
		return new Source(table, ids);
	}

	private boolean isSameOrDescendant(NoteStoreEntity candidate, NoteStoreEntity ancestor) {
		for (NoteStoreEntity e = candidate; e != null; e = entitiesById.get(e.superId())) {
			if (e.id() == ancestor.id()) {
				return true;
			}
			if (e.superId() == 0) {
				break;
			}
		}
		return false;
	}

	/** Backs {@link NoteStoreRow#get(String)}. */
	Object value(String table, String column, long pk) {
		requireOpen();
		if (!columnsOf(table).contains(column)) {
			throw new IllegalArgumentException("no column " + column + " in " + table);
		}
		String sql = "SELECT \"" + column + "\" FROM \"" + table + "\" WHERE Z_PK = ?";
		try (PreparedStatement st = connection.prepareStatement(sql)) {
			st.setLong(1, pk);
			try (ResultSet rs = st.executeQuery()) {
				if (!rs.next()) {
					throw new NoteStoreException("no row " + pk + " in " + table);
				}
				Object v = rs.getObject(1);
				// The driver hands back Integer for small INTEGERs.
				return v instanceof Integer i ? Long.valueOf(i) : v;
			}
		} catch (SQLException e) {
			throw new NoteStoreException("reading " + table + "." + column + ": " + e.getMessage(), e);
		}
	}

	private Set<String> columnsOf(String table) {
		Set<String> cached = columnsByTable.get(table);
		if (cached != null) {
			return cached;
		}
		Set<String> columns = new HashSet<>();
		try (Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery("PRAGMA table_info(\"" + table + "\")")) {
			while (rs.next()) {
				columns.add(rs.getString("name"));
			}
		} catch (SQLException e) {
			throw new NoteStoreException("reading columns of " + table + ": " + e.getMessage(), e);
		}
		columnsByTable.put(table, columns);
		return columns;
	}

	private void requireOpen() {
		if (closed) {
			throw new IllegalStateException("NoteStore is closed");
		}
	}

	/**
	 * Closes the store.  This operation is idempotent.
	 */
	@Override
	public void close() {
		if (!closed) {
			closed = true;
			closeQuietly(connection);
		}
	}
}
