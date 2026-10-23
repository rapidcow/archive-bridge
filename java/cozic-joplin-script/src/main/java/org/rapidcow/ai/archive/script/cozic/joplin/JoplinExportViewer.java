package org.rapidcow.ai.archive.script.cozic.joplin;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
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
 * For now it is an empty window with a "Dark" toggle in the
 * bottom-right corner, which switches between a light and a dark
 * palette.
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
	private final JToggleButton dark = new JToggleButton("Dark");

	private
	JoplinExportViewer()
	{
		var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
		buttons.add(dark);

		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.add(new JPanel(), BorderLayout.CENTER);
		frame.add(buttons, BorderLayout.SOUTH);
		frame.setSize(640, 480);
		frame.setLocationRelativeTo(null);

		dark.addItemListener(e -> applyTheme(dark.isSelected() ? DARK : LIGHT));
	}

	/**
	 * Installs {@code theme} and repaints every open window with it.
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
			applyTheme(LIGHT);
			new JoplinExportViewer().frame.setVisible(true);
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
