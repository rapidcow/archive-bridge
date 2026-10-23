package org.rapidcow.ai.archive.script.apple.notes;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import org.rapidcow.ai.archive.script.cozic.joplin.JoplinExportViewer;
import org.rapidcow.ai.archive.script.cozic.joplin.MetadataPane;

/**
 * Read-only browser for a standalone NoteStore.sqlite.
 * Usage: {@code AppleNotesViewer [PATH]}. Entities and their rows appear on
 * the left, raw non-null columns on the right. Binary values show their size;
 * double-click a com.apple.drawing.2 attachment to animate it through the
 * bundled Perl decoder. Note bodies are not decoded. Uses the Joplin viewer's theme
 * and metadata pane. The store must already have its WAL consolidated.
 */
public final class AppleNotesViewer {
    private final JFrame frame = new JFrame("Apple Notes Viewer");
    private final JTree tree = new JTree(new DefaultMutableTreeNode());
    private final MetadataPane metadata = new MetadataPane();
    private final JLabel status = new JLabel(" ");
    private final JMenuItem open = new JMenuItem("Open...");
    private final JMenuItem reveal = new JMenuItem("View in File System");
    private StoreTree store;
    private boolean byTitle = true;
    private int selectionVersion;
    private boolean drawingLoading;

    private AppleNotesViewer() {
        int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        open.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, shortcut));
        open.addActionListener(e -> chooseStore());
        var file = new JMenu("File");
        file.add(open);
        var deselect = new JMenuItem("Deselect");
        deselect.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, shortcut | InputEvent.SHIFT_DOWN_MASK));
        deselect.addActionListener(e -> tree.clearSelection());
        var select = new JMenu("Select");
        select.add(deselect);
        var sort = new JMenu("Sort By");
        var group = new ButtonGroup();
        for (String label : new String[] {"Title", "Primary Key"}) {
            var item = new JRadioButtonMenuItem(label, label.equals("Title"));
            item.addActionListener(e -> { byTitle = label.equals("Title"); rebuild(); });
            group.add(item);
            sort.add(item);
        }
        var dark = new JCheckBoxMenuItem("Dark Mode", true);
        dark.addItemListener(e -> JoplinExportViewer.applyTheme(
                dark.isSelected() ? JoplinExportViewer.DARK : JoplinExportViewer.LIGHT));
        reveal.setEnabled(false);
        reveal.addActionListener(e -> reveal());
        var view = new JMenu("View");
        view.add(sort);
        view.add(reveal);
        view.addSeparator();
        view.add(dark);
        var menus = new JMenuBar();
        menus.add(file);
        menus.add(select);
        menus.add(view);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.addTreeSelectionListener(e -> showSelection());
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    var path = tree.getPathForLocation(e.getX(), e.getY());
                    if (path != null && ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject()
                            instanceof StoreTree.Item item) playDrawing(item);
                }
            }
            @Override public void mousePressed(MouseEvent e) {
                int row = tree.getClosestRowForLocation(0, e.getY());
                var bounds = tree.getRowBounds(row);
                if (bounds == null || e.getY() < bounds.y || e.getY() >= bounds.y + bounds.height) {
                    tree.clearSelection();
                }
            }
        });
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(tree), new JScrollPane(metadata));
        split.setDividerLocation(280);
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        frame.setJMenuBar(menus);
        frame.add(split, BorderLayout.CENTER);
        frame.add(status, BorderLayout.SOUTH);
        frame.setSize(800, 600);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                selectionVersion++;
                if (store != null) store.close();
            }
        });
    }

    private void playDrawing(StoreTree.Item item) {
        if (drawingLoading) return;
        var source = store;
        drawingLoading = true;
        status.setText("Loading drawing " + item.row().pk() + "...");
        new SwingWorker<java.util.List<Stroke>, Void>() {
            @Override protected java.util.List<Stroke> doInBackground() throws Exception {
                if (!source.isDrawing(item)) return null;
                return DrawingDecoder.decode(source.path, item.row().pk());
            }
            @Override protected void done() {
                drawingLoading = false;
                updateStatus();
                try {
                    var strokes = get();
                    if (strokes == null || source != store || !frame.isDisplayable()) return;
                    if (strokes.stream().allMatch(s -> s.points().isEmpty())) {
                        throw new IllegalArgumentException("This drawing has no points to play");
                    }
                    var panel = new DrawingPanel(strokes);
                    panel.setPreferredSize(new java.awt.Dimension(900, 700));
                    var window = new JFrame("Apple Notes Drawing — " + item);
                    window.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                    window.setContentPane(panel);
                    window.pack();
                    window.setLocationRelativeTo(frame);
                    window.setVisible(true);
                    panel.animate(50);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (ExecutionException | RuntimeException e) {
                    if (source == store && frame.isDisplayable()) {
                        error("Cannot play drawing", e instanceof ExecutionException ? e.getCause() : e);
                    }
                }
            }
        }.execute();
    }

    private Object selected() {
        return tree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node
                ? node.getUserObject() : null;
    }

    private void showSelection() {
        int version = ++selectionVersion;
        metadata.showProperties(Map.of());
        if (!(selected() instanceof StoreTree.Item item)) return;
        var source = store;
        metadata.showProperties(Map.of("Status", "Loading..."));
        new SwingWorker<Map<String, String>, Void>() {
            @Override protected Map<String, String> doInBackground() { return source.details(item); }
            @Override protected void done() {
                if (version != selectionVersion) return;
                try { metadata.showProperties(get()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (ExecutionException e) { metadata.showProperties(Map.of("Error", e.getCause().toString())); }
            }
        }.execute();
    }

    private void show(StoreTree loaded) {
        selectionVersion++;
        if (store != null) store.close();
        store = loaded;
        tree.setModel(new DefaultTreeModel(store.root(byTitle)));
        metadata.showProperties(Map.of());
        reveal.setEnabled(true);
        frame.setTitle("Apple Notes Viewer — " + store.path);
        updateStatus();
    }

    private void rebuild() {
        if (store == null) return;
        Object selected = selected();
        var expanded = new HashSet<Object>();
        var root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        var paths = tree.getExpandedDescendants(new TreePath(root));
        while (paths != null && paths.hasMoreElements()) {
            expanded.add(((DefaultMutableTreeNode) paths.nextElement().getLastPathComponent()).getUserObject());
        }
        root = store.root(byTitle);
        tree.setModel(new DefaultTreeModel(root));
        var nodes = root.preorderEnumeration();
        while (nodes.hasMoreElements()) {
            var node = (DefaultMutableTreeNode) nodes.nextElement();
            var path = new TreePath(node.getPath());
            if (expanded.contains(node.getUserObject())) tree.expandPath(path);
            if (selected != null && selected.equals(node.getUserObject())) tree.setSelectionPath(path);
        }
    }

    private void updateStatus() {
        status.setText(store == null ? " " : store.count + " rows in " + store.entities.size() + " entities loaded");
    }

    private void chooseStore() {
        var chooser = new JFileChooser();
        if (store != null) chooser.setCurrentDirectory(store.path.getParent().toFile());
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath();
        open.setEnabled(false);
        status.setText("Loading " + path + "...");
        new SwingWorker<StoreTree, Void>() {
            @Override protected StoreTree doInBackground() { return StoreTree.open(path); }
            @Override protected void done() {
                open.setEnabled(true);
                try {
                    var loaded = get();
                    if (frame.isDisplayable()) show(loaded); else loaded.close();
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (ExecutionException e) {
                    updateStatus();
                    if (frame.isDisplayable()) error("Cannot open store", e.getCause());
                }
            }
        }.execute();
    }

    private void reveal() {
        try {
            var desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) desktop.browseFileDirectory(store.path.toFile());
            else desktop.open(store.path.getParent().toFile());
        } catch (Exception e) { error("Cannot view in file system", e); }
    }

    private void error(String title, Throwable e) {
        JOptionPane.showMessageDialog(frame, e.getMessage(), title, JOptionPane.ERROR_MESSAGE);
    }

    /** Opens the optional store before showing the window; startup failures are fatal. */
    public static void main(String[] args) {
        if (args.length > 1) {
            System.err.println("usage: AppleNotesViewer [PATH]");
            System.exit(1);
        }
        final StoreTree loaded;
        try { loaded = args.length == 0 ? null : StoreTree.open(Path.of(args[0])); }
        catch (RuntimeException e) {
            System.err.println("AppleNotesViewer: " + e.getMessage());
            System.exit(1);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            JoplinExportViewer.useScreenMenuBar();
            JoplinExportViewer.applyTheme(JoplinExportViewer.DARK);
            var viewer = new AppleNotesViewer();
            if (loaded != null) viewer.show(loaded);
            viewer.frame.setVisible(true);
        });
    }
}
