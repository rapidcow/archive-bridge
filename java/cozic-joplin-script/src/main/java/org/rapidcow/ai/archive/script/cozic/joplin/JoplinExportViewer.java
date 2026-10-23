package org.rapidcow.ai.archive.script.cozic.joplin;

import java.awt.BorderLayout;
import java.awt.Window;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.metal.DefaultMetalTheme;
import javax.swing.plaf.metal.MetalLookAndFeel;
import javax.swing.plaf.metal.MetalTheme;
import javax.swing.plaf.metal.OceanTheme;

/**
 * Viewer for Joplin export (JEX) archives.
 *
 * <p>
 * For now it is an empty window.  The "View" menu has a "Toggle Dark
 * Mode" item, which switches between a light and a dark palette (it
 * starts out dark).  On macOS the menu sits in the system menu bar at
 * the top of the screen; elsewhere it is at the top of the window.
 *
 * <p>
 * This uses Swing's cross-platform Metal look and feel: the native
 * ones (Aqua in particular) ignore most of the colors a theme sets.
 */
public final
class
	JoplinExportViewer
{
	private static final MetalTheme LIGHT = new OceanTheme();
	private static final MetalTheme DARK = new DarkTheme();

	private final JFrame frame = new JFrame("Joplin Export Viewer");
	private final JCheckBoxMenuItem dark = new JCheckBoxMenuItem("Dark Mode");

	/**
	 * Creates the window, with the dark mode item checked if
	 * {@code theme} is the dark one.
	 *
	 * @param theme  the UI theme to use
	 */
	private
	JoplinExportViewer(MetalTheme theme)
	{
		var view = new JMenu("View");
		view.add(dark);
		var menus = new JMenuBar();
		menus.add(view);

		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setJMenuBar(menus);
		frame.add(new JPanel(), BorderLayout.CENTER);
		frame.setSize(640, 480);
		frame.setLocationRelativeTo(null);

		dark.setSelected(theme == DARK);
		dark.addItemListener(e -> applyTheme(dark.isSelected() ? DARK : LIGHT));
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
	private static void
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
	private static void
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
		SwingUtilities.invokeLater(() -> {
			useScreenMenuBar();
			var theme = DARK;
			applyTheme(theme);
			new JoplinExportViewer(theme).frame.setVisible(true);
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
