package org.rapidcow.ai.archive.script.cozic.joplin;

import java.awt.Color;
import java.awt.Component;
import javax.swing.Icon;
import javax.swing.JTree;
import javax.swing.UIManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import org.rapidcow.ai.archive.bridge.cozic.joplin.JoplinType;

/**
 * Draws the nodes of an {@link EntryTree}: folders with the look and
 * feel's folder icons, and notes with its leaf (document) icon,
 * whether or not they have children.
 *
 * <p>
 * An empty title is shown as "&lt;no title&gt;" in a muted color,
 * halfway between the text and its background.  So is a node that
 * only refers back to an ancestor, to close a cycle.
 */
final
class
	EntryCellRenderer
extends
	DefaultTreeCellRenderer
{
	@Override
	public Component
	getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
			boolean expanded, boolean leaf, int row, boolean hasFocus)
	{
		super.getTreeCellRendererComponent(
				tree, value, selected, expanded, leaf, row, hasFocus);
		if (!(((DefaultMutableTreeNode) value).getUserObject()
				instanceof EntryTree.Item item)) {
			return this;
		}

		var entry = item.entry();
		String title = entry.getTitle();
		boolean muted = title.isEmpty() || item.cycle();
		if (title.isEmpty()) {
			title = "<no title>";
		}
		setText(item.cycle() ? title + " (cycle)" : title);

		// Fetched each time, so they follow changes of theme.
		Icon icon;
		if (entry.getType() == JoplinType.FOLDER) {
			icon = UIManager.getIcon(expanded ? "Tree.openIcon" : "Tree.closedIcon");
		} else {
			icon = UIManager.getIcon("Tree.leafIcon");
		}
		setIcon(icon);

		if (muted) {
			Color background = selected
				? getBackgroundSelectionColor()
				: tree.getBackground();
			setForeground(blend(getForeground(), background));
		}
		return this;
	}

	private static Color
	blend(Color a, Color b)
	{
		return new Color(
				(a.getRed() + b.getRed()) / 2,
				(a.getGreen() + b.getGreen()) / 2,
				(a.getBlue() + b.getBlue()) / 2);
	}
}
