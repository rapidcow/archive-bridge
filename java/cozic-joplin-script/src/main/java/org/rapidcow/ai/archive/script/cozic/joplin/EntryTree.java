package org.rapidcow.ai.archive.script.cozic.joplin;

import java.text.Collator;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.swing.tree.DefaultMutableTreeNode;
import org.rapidcow.ai.archive.bridge.cozic.joplin.JoplinEntry;
import org.rapidcow.ai.archive.bridge.cozic.joplin.JoplinType;

/**
 * Arranges the folders and notes of an export into a tree, by their
 * parent links.
 *
 * <p>
 * Any folder or note may have children, not just folders.  Other
 * entries (resources, tags, ...) are left out; a folder or note whose
 * parent is one of them, or is missing from the export, is a root.
 * Siblings are sorted in one of the {@link Order}s.
 *
 * <p>
 * Parent links may form cycles, in which no entry is a root.  Each
 * cycle is broken at its first member in sort order, which becomes a
 * root; where the cycle comes back around to it, it appears once more
 * as a leaf marked {@link Item#cycle()}.  So every folder and note
 * appears exactly once, apart from those leaves.
 */
final
class
	EntryTree
{
	/**
	 * What a tree node stands for.
	 *
	 * @param entry  the folder or note
	 * @param cycle  whether this only refers back to {@code entry},
	 *               which is an ancestor of this node
	 */
	record Item(JoplinEntry entry, boolean cycle) {}

	private static final Comparator<JoplinEntry> BY_TITLE
		= Comparator.comparing(JoplinEntry::getTitle, Collator.getInstance())
			.thenComparing(JoplinEntry::getId);

	/**
	 * How siblings are sorted.  Times sort newest first, as in
	 * Joplin's own note list; entries without a valid time come last,
	 * and ties go by title.
	 */
	enum Order {
		/** By title, then by ID. */
		TITLE("Title", BY_TITLE),
		/** By {@code updated_time}, when the entry last changed at all. */
		MODIFICATION_TIME("Modification Time", byTime("updated_time")),
		/** By {@code user_updated_time}, when its user last edited it. */
		USER_MODIFICATION_TIME("User Modification Time", byTime("user_updated_time"));

		private final String label;
		private final Comparator<JoplinEntry> comparator;

		Order(String label, Comparator<JoplinEntry> comparator)
		{
			this.label = label;
			this.comparator = comparator;
		}

		/** The name to show in a menu. */
		String label() { return label; }
	}

	private static Comparator<JoplinEntry>
	byTime(String key)
	{
		return Comparator.comparing((JoplinEntry e) -> time(e, key),
				Comparator.nullsLast(Comparator.reverseOrder()))
			.thenComparing(BY_TITLE);
	}

	/**
	 * The time in {@code key} of the metadata of {@code entry}, or
	 * {@code null} if it is missing or not a valid ISO 8601 instant.
	 */
	private static Instant
	time(JoplinEntry entry, String key)
	{
		String value = entry.getMetadata().get(key);
		if (value == null) {
			return null;
		}
		try {
			return Instant.parse(value);
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	private final Comparator<JoplinEntry> order;
	private final Set<JoplinEntry> placed = new HashSet<>();

	private
	EntryTree(Comparator<JoplinEntry> order)
	{
		this.order = order;
	}

	/**
	 * Builds the tree of {@code entries}.
	 *
	 * @param entries  every entry in an export
	 * @param order    how to sort siblings
	 * @return a root, standing for the export itself, with no
	 *         {@link Item} of its own
	 */
	static DefaultMutableTreeNode
	of(Collection<? extends JoplinEntry> entries, Order order)
	{
		List<JoplinEntry> shown = new ArrayList<>();
		for (JoplinEntry e : entries) {
			if (isShown(e)) {
				shown.add(e);
			}
		}
		shown.sort(order.comparator);

		var tree = new EntryTree(order.comparator);
		var root = new DefaultMutableTreeNode();
		for (JoplinEntry e : shown) {
			if (parent(e).isEmpty()) {
				tree.place(root, e);
			}
		}
		// What is left hangs off a cycle.
		for (JoplinEntry e : shown) {
			if (!tree.placed.contains(e)) {
				tree.place(root, tree.cycleRoot(e));
			}
		}
		return root;
	}

	private static boolean
	isShown(JoplinEntry entry)
	{
		var type = entry.getType();
		return type == JoplinType.FOLDER || type == JoplinType.NOTE;
	}

	private static Optional<JoplinEntry>
	parent(JoplinEntry entry)
	{
		return entry.getParent().filter(EntryTree::isShown);
	}

	/**
	 * Finds the cycle above {@code entry}, which must have no root
	 * among its ancestors, and returns its first member in sort order.
	 */
	private JoplinEntry
	cycleRoot(JoplinEntry entry)
	{
		var path = new LinkedHashSet<JoplinEntry>();
		JoplinEntry e = entry;
		while (path.add(e)) {
			e = parent(e).orElseThrow();
		}
		// e is the first entry met twice, so the cycle is the rest of
		// the path from e on.
		JoplinEntry first = e;
		boolean inCycle = false;
		for (JoplinEntry p : path) {
			inCycle |= p == e;
			if (inCycle && order.compare(p, first) < 0) {
				first = p;
			}
		}
		return first;
	}

	private void
	place(DefaultMutableTreeNode parent, JoplinEntry entry)
	{
		placed.add(entry);
		var node = new DefaultMutableTreeNode(new Item(entry, false));
		parent.add(node);

		List<JoplinEntry> children = new ArrayList<>();
		for (JoplinEntry c : entry.getChildren()) {
			if (isShown(c)) {
				children.add(c);
			}
		}
		children.sort(order);
		for (JoplinEntry c : children) {
			if (placed.contains(c)) {
				// Each entry has one parent, so c is only placed
				// already if it is the root of the cycle we are in.
				node.add(new DefaultMutableTreeNode(new Item(c, true), false));
			} else {
				place(node, c);
			}
		}
	}
}
