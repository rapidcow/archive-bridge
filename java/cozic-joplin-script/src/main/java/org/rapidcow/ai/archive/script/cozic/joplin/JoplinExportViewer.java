package org.rapidcow.ai.archive.script.cozic.joplin;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.metal.DefaultMetalTheme;
import javax.swing.plaf.metal.MetalLookAndFeel;
import javax.swing.plaf.metal.MetalTheme;
import javax.swing.plaf.metal.OceanTheme;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import org.rapidcow.ai.archive.bridge.cozic.joplin.JoplinEntry;
import org.rapidcow.ai.archive.bridge.cozic.joplin.JoplinExportDirectory;

/**
 * Viewer for Joplin export (JEX) archives.
 *
 * <p>
 * Usage: {@code JoplinExportViewer [PATH]}, where {@code PATH} is an
 * extracted export directory.  If it is given, it is loaded before the
 * window opens, and any failure to load it is fatal.  (Unextracted JEX
 * files are not supported yet, and are fatal too.)
 *
 * <p>
 * The sidebar on the left shows the folders and notes as a tree (see
 * {@link EntryTree}).  Clicking the blank space below them clears the
 * selection, as does "Select &gt; Deselect" (Ctrl+Shift+A, or
 * Cmd+Shift+A on macOS).  "Open in File System", in "View" and on
 * right-clicking, opens the {@code .md} file of the selected one with
 * the application the system associates with it; "View in File
 * System" shows it in the file manager instead (selected there where
 * the platform supports that, as macOS does, or else just its
 * directory).  "View &gt; Sort By" picks how the tree is sorted
 * (see {@link EntryTree.Order}).  The panel on the
 * right lists the metadata of the selected one (see
 * {@link MetadataPane}).  The status line at the bottom tells how many
 * entries were loaded.
 * "File &gt; Open..." (Ctrl+O, or Cmd+O on
 * macOS) loads another directory; there, failures are only reported.
 * "View &gt; Dark Mode" switches between a light and a dark palette
 * (it starts out dark).  On macOS the menus sit in the system menu bar
 * at the top of the screen; elsewhere they are at the top of the
 * window.
 *
 * <p>
 * This uses Swing's cross-platform Metal look and feel: the native
 * ones (Aqua in particular) ignore most of the colors a theme sets.
 */
public final
class
	JoplinExportViewer
{
	public static final MetalTheme LIGHT = new OceanTheme();
	public static final MetalTheme DARK = new DarkTheme();

	private final JFrame frame = new JFrame("Joplin Export Viewer");
	private final JCheckBoxMenuItem dark = new JCheckBoxMenuItem("Dark Mode");
	private final JLabel status = new JLabel(" ");
	private final JTree tree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode()));
	private final MetadataPane metadata = new MetadataPane();

	/**
	 * Opens the selected folder or note's file with the default
	 * application; enabled only while one is selected.
	 */
	private final Action openInFileSystem = new AbstractAction("Open in File System") {
		@Override
		public void
		actionPerformed(ActionEvent e)
		{
			openFile(selectedFile());
		}
	};

	/**
	 * Shows the selected folder or note in the file manager; enabled
	 * only while one is selected.
	 */
	private final Action viewInFileSystem = new AbstractAction("View in File System") {
		@Override
		public void
		actionPerformed(ActionEvent e)
		{
			reveal(selectedFile());
		}
	};

	private EntryTree.Order order = EntryTree.Order.TITLE;

	/**
	 * The export on display, or {@code null} before one is loaded.
	 */
	private JoplinExportDirectory export;

	/**
	 * Creates the window, with the dark mode item checked if
	 * {@code theme} is the dark one.
	 *
	 * @param theme  the UI theme to use
	 */
	private
	JoplinExportViewer(MetalTheme theme)
	{
		int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
		var open = new JMenuItem("Open...");
		open.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, shortcut));
		open.addActionListener(e -> chooseExport());
		var file = new JMenu("File");
		file.add(open);
		var deselect = new JMenuItem("Deselect");
		deselect.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A,
				shortcut | InputEvent.SHIFT_DOWN_MASK));
		deselect.addActionListener(e -> tree.clearSelection());
		var select = new JMenu("Select");
		select.add(deselect);
		var sortBy = new JMenu("Sort By");
		var orders = new ButtonGroup();
		for (EntryTree.Order o : EntryTree.Order.values()) {
			var item = new JRadioButtonMenuItem(o.label(), o == order);
			item.addActionListener(e -> sort(o));
			orders.add(item);
			sortBy.add(item);
		}
		var view = new JMenu("View");
		view.add(sortBy);
		view.add(openInFileSystem);
		view.add(viewInFileSystem);
		view.addSeparator();
		view.add(dark);
		var menus = new JMenuBar();
		menus.add(file);
		menus.add(select);
		menus.add(view);

		status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		tree.setRootVisible(false);
		tree.setShowsRootHandles(true);
		tree.setCellRenderer(new EntryCellRenderer());
		openInFileSystem.setEnabled(false);
		viewInFileSystem.setEnabled(false);
		tree.addTreeSelectionListener(e -> {
			metadata.show(selectedEntry());
			openInFileSystem.setEnabled(selectedFile() != null);
			viewInFileSystem.setEnabled(selectedFile() != null);
		});

		var popup = new JPopupMenu();
		popup.add(openInFileSystem);
		popup.add(viewInFileSystem);
		// As the tree's own popup menu, it follows changes of theme.
		tree.setComponentPopupMenu(popup);
		tree.addMouseListener(new MouseAdapter() {
			@Override
			public void
			mousePressed(MouseEvent e)
			{
				// Clicking blank space clears the selection, and
				// right-clicking a row selects it, so the menu acts on it.
				int row = rowAt(e.getY());
				if (row < 0) {
					tree.clearSelection();
				} else if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e)) {
					tree.setSelectionRow(row);
				}
			}
		});

		var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
				new JScrollPane(tree), new JScrollPane(metadata));
		split.setDividerLocation(220);

		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setJMenuBar(menus);
		frame.add(split, BorderLayout.CENTER);
		frame.add(status, BorderLayout.SOUTH);
		frame.setSize(640, 480);
		frame.setLocationRelativeTo(null);

		dark.setSelected(theme == DARK);
		dark.addItemListener(e -> applyTheme(dark.isSelected() ? DARK : LIGHT));
	}

	/**
	 * Shows {@code export} in place of the current one, which is closed.
	 *
	 * @param export  the newly loaded export
	 */
	private void
	show(JoplinExportDirectory export)
	{
		if (this.export != null) {
			try {
				this.export.close();
			} catch (IOException e) {
				// The old export is going away either way.
			}
		}
		this.export = export;
		tree.setModel(new DefaultTreeModel(EntryTree.of(export.entries().values(), order)));
		updateStatus();
	}

	/**
	 * Sorts the tree in {@code order} from now on.  Which folders and
	 * notes are expanded, and which is selected, stays the same.
	 *
	 * @param order  the new order
	 */
	private void
	sort(EntryTree.Order order)
	{
		this.order = order;
		if (export == null) {
			return;
		}
		var root = (DefaultMutableTreeNode) tree.getModel().getRoot();
		Set<Object> expanded = new HashSet<>();
		var paths = tree.getExpandedDescendants(new TreePath(root));
		while (paths != null && paths.hasMoreElements()) {
			expanded.add(item(paths.nextElement()));
		}
		Object selected = item(tree.getSelectionPath());

		root = EntryTree.of(export.entries().values(), order);
		tree.setModel(new DefaultTreeModel(root));
		var nodes = root.preorderEnumeration();
		while (nodes.hasMoreElements()) {
			var node = (DefaultMutableTreeNode) nodes.nextElement();
			var item = node.getUserObject();
			if (item == null) {
				continue;
			}
			var path = new TreePath(node.getPath());
			if (expanded.contains(item)) {
				tree.expandPath(path);
			}
			if (item.equals(selected)) {
				tree.setSelectionPath(path);
				tree.scrollPathToVisible(path);
			}
		}
	}

	/**
	 * The {@link EntryTree.Item} at the end of {@code path}, or
	 * {@code null} if there is none.
	 */
	private static Object
	item(TreePath path)
	{
		if (path == null) {
			return null;
		}
		return ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
	}

	/**
	 * The row of the tree at height {@code y}, wherever it is across,
	 * or -1 if there is none.
	 */
	private int
	rowAt(int y)
	{
		int row = tree.getClosestRowForLocation(0, y);
		var bounds = tree.getRowBounds(row);
		if (bounds == null || y < bounds.y || y >= bounds.y + bounds.height) {
			return -1;
		}
		return row;
	}

	/**
	 * The folder or note selected in the tree, or {@code null}.
	 */
	private JoplinEntry
	selectedEntry()
	{
		var path = tree.getSelectionPath();
		if (path != null
				&& ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject()
					instanceof EntryTree.Item item) {
			return item.entry();
		}
		return null;
	}

	/**
	 * The {@code .md} file of the selected folder or note, or
	 * {@code null}.
	 */
	private Path
	selectedFile()
	{
		if (selectedEntry() instanceof JoplinExportDirectory.Entry entry) {
			return entry.getFilePath();
		}
		return null;
	}

	/**
	 * Opens {@code file} with the application the platform associates
	 * with it.
	 *
	 * @param file  the file to open
	 */
	private void
	openFile(Path file)
	{
		if (file == null) {
			return;
		}
		try {
			Desktop desktop = Desktop.isDesktopSupported() ? Desktop.getDesktop() : null;
			if (desktop == null || !desktop.isSupported(Desktop.Action.OPEN)) {
				throw new UnsupportedOperationException("No application to open with");
			}
			desktop.open(file.toFile());
		} catch (IOException | RuntimeException e) {
			JOptionPane.showMessageDialog(frame, file + ": " + e.getMessage(),
					"Cannot open in file system", JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * Shows {@code file} in the file manager: selected in its
	 * directory where the platform supports that (as macOS does), or
	 * else just its directory.
	 *
	 * @param file  the file to show
	 */
	private void
	reveal(Path file)
	{
		if (file == null) {
			return;
		}
		try {
			Desktop desktop = Desktop.isDesktopSupported() ? Desktop.getDesktop() : null;
			if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
				desktop.browseFileDirectory(file.toFile());
			} else if (desktop != null && desktop.isSupported(Desktop.Action.OPEN)) {
				desktop.open(file.getParent().toFile());
			} else {
				throw new UnsupportedOperationException("No file manager to open");
			}
		} catch (IOException | RuntimeException e) {
			JOptionPane.showMessageDialog(frame, file + ": " + e.getMessage(),
					"Cannot view in file system", JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * Tells how many entries the current export has, if there is one.
	 */
	private void
	updateStatus()
	{
		if (export == null) {
			status.setText(" ");
			return;
		}
		int n = export.entries().size();
		status.setText(n + (n == 1 ? " entry" : " entries") + " loaded");
	}

	/**
	 * Asks for an export directory and loads it in the background.
	 * A failure is reported in a dialog, and leaves the current export
	 * on display.
	 */
	private void
	chooseExport()
	{
		var chooser = new JFileChooser();
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		Path path = chooser.getSelectedFile().toPath();
		status.setText("Loading " + path + "...");
		new SwingWorker<JoplinExportDirectory, Void>() {
			@Override
			protected JoplinExportDirectory
			doInBackground()
			throws
				IOException
			{
				return JoplinExportDirectory.open(path);
			}

			@Override
			protected void
			done()
			{
				try {
					show(get());
				} catch (ExecutionException e) {
					updateStatus();
					JOptionPane.showMessageDialog(frame,
							path + ": " + e.getCause().getMessage(),
							"Cannot open export", JOptionPane.ERROR_MESSAGE);
				} catch (InterruptedException e) {
					throw new IllegalStateException(e);
				}
			}
		}.execute();
	}

	/**
	 * Loads the export at {@code path}, or exits with a message if
	 * that fails.
	 *
	 * @param path  an extracted export directory
	 * @return the loaded export
	 */
	private static JoplinExportDirectory
	loadOrDie(Path path)
	{
		if (Files.isRegularFile(path)) {
			die(path + ": opening JEX files is not implemented");
		}
		try {
			return JoplinExportDirectory.open(path);
		} catch (IOException | IllegalStateException e) {
			die(path + ": " + e.getMessage());
			return null;
		}
	}

	private static void
	die(String message)
	{
		System.err.println("JoplinExportViewer: " + message);
		System.exit(1);
	}

	/**
	 * Keeps menu bars in the macOS system menu bar under Metal.
	 *
	 * <p>
	 * {@code apple.laf.useScreenMenuBar} only takes effect through
	 * Aqua's menu bar delegate, which Metal replaces with its own.  So
	 * this borrows Aqua's before Metal is installed, and puts it in the
	 * developer defaults, which outlive every later change of look and
	 * feel.  Off macOS the default look and feel is not Aqua, and
	 * nothing changes.
	 */
	public static void
	useScreenMenuBar()
	{
		System.setProperty("apple.laf.useScreenMenuBar", "true");
		if ("Aqua".equals(UIManager.getLookAndFeel().getID())) {
			UIManager.put("MenuBarUI", UIManager.get("MenuBarUI"));
		}
	}

	/**
	 * Installs {@code theme} and repaints every open window with it.
	 *
	 * @param theme  my UI theme
	 */
	public static void
	applyTheme(MetalTheme theme)
	{
		MetalLookAndFeel.setCurrentTheme(theme);
		try {
			UIManager.setLookAndFeel(new MetalLookAndFeel());
		} catch (UnsupportedLookAndFeelException e) {
			throw new IllegalStateException(e);
		}
		for (Window w : Window.getWindows()) {
			SwingUtilities.updateComponentTreeUI(w);
		}
	}

	public static void
	main(String[] args)
	{
		int argc = args.length;
		if (argc > 1) {
			System.err.println("usage: JoplinExportViewer [PATH]");
			System.exit(1);
		}

		JoplinExportDirectory export = argc == 1 ? loadOrDie(Path.of(args[0])) : null;
		SwingUtilities.invokeLater(() -> {
			useScreenMenuBar();
			var theme = DARK;
			applyTheme(theme);
			var viewer = new JoplinExportViewer(theme);
			if (export != null) {
				viewer.show(export);
			}
			viewer.frame.setVisible(true);
		});
	}

	/**
	 * Dark palette.  Metal derives nearly everything (text, borders,
	 * selection, disabled shades) from these, so overriding them is
	 * enough.  Metal's "white" is the background of text and lists,
	 * and its "black" is the text itself, so both are flipped.
	 */
	private static final
	class
		DarkTheme
	extends
		DefaultMetalTheme
	{
		private static final ColorUIResource PRIMARY1 = new ColorUIResource(0x4a88c7);
		private static final ColorUIResource PRIMARY2 = new ColorUIResource(0x3d6fa8);
		private static final ColorUIResource PRIMARY3 = new ColorUIResource(0x2f5580);
		private static final ColorUIResource SECONDARY1 = new ColorUIResource(0x6b6e70);
		private static final ColorUIResource SECONDARY2 = new ColorUIResource(0x4e5254);
		private static final ColorUIResource SECONDARY3 = new ColorUIResource(0x3c3f41);
		private static final ColorUIResource BLACK = new ColorUIResource(0xd4d4d4);
		private static final ColorUIResource WHITE = new ColorUIResource(0x1e1f22);

		@Override
		public String
		getName()
		{
			return "Dark";
		}

		@Override protected ColorUIResource getPrimary1() { return PRIMARY1; }
		@Override protected ColorUIResource getPrimary2() { return PRIMARY2; }
		@Override protected ColorUIResource getPrimary3() { return PRIMARY3; }
		@Override protected ColorUIResource getSecondary1() { return SECONDARY1; }
		@Override protected ColorUIResource getSecondary2() { return SECONDARY2; }
		@Override protected ColorUIResource getSecondary3() { return SECONDARY3; }
		@Override protected ColorUIResource getBlack() { return BLACK; }
		@Override protected ColorUIResource getWhite() { return WHITE; }
	}
}
