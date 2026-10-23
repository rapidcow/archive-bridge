package org.rapidcow.ai.archive.script.apple.notes;

import java.util.List;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import javax.swing.AbstractAction;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.Timer;

class DrawingPanel extends JPanel
{
	private static final long serialVersionUID = 9207469320884566458L;
	private List<Stroke> art;
	private int revealed = 0;
	private Timer timer;

	DrawingPanel(List<Stroke> strokes)
	{
		art = List.copyOf(strokes);
		setBackground(Color.WHITE);
		setToolTipText("Space: pause, resume, or replay");
	}

	/** Reveals one stroke every {@code millisPerStroke}, starting immediately. */
	void animate(int millisPerStroke)
	{
		if (timer != null) timer.stop();
		revealed = 0;
		timer = new Timer(millisPerStroke, null);
		timer.setInitialDelay(0);
		timer.addActionListener(e -> {
			revealed++;
			repaint();
			if (revealed >= art.size()) {
				timer.stop();
			}
		});
		timer.start();

		getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggle");
		getActionMap().put("toggle", new AbstractAction() {
			@Override public void actionPerformed(ActionEvent e)
			{
				if (timer.isRunning()) {
					timer.stop();
				} else {
					if (revealed >= art.size()) revealed = 0;
					timer.start();
				}
			}
		});
	}

    @Override public void removeNotify() {
        if (timer != null) timer.stop();
        super.removeNotify();
    }

	@Override protected void paintComponent(Graphics graphics)
	{
		super.paintComponent(graphics);

		Graphics2D canvas = (Graphics2D) graphics.create();
		try {
			paintOnCanvas(canvas);
		}
		finally {
			canvas.dispose();
		}
	}

	private void paintOnCanvas(Graphics2D canvas)
	{
		canvas.setRenderingHint(
			RenderingHints.KEY_ANTIALIASING,
			RenderingHints.VALUE_ANTIALIAS_ON
		);
		canvas.setStroke(new BasicStroke(
			2.5f,  /* Full width */
			BasicStroke.CAP_ROUND,
			BasicStroke.JOIN_ROUND
		));

		AffineTransform screen = canvas.getTransform();
		String uuid = null;
		for (int i = 0; i < revealed && i < art.size(); i++) {
			Stroke stroke = art.get(i);
			uuid = stroke.uuid();
			canvas.setColor(stroke.brush().color());
			canvas.setTransform(screen);
			canvas.transform(stroke.affineMap());
			Path2D.Double path = new Path2D.Double();
			boolean lifted = true;

			for (Point point : stroke.points()) {
				if (lifted) {
					path.moveTo(point.x(), point.y());
					lifted = false;
				}
				else {
					path.lineTo(point.x(), point.y());
				}
			}

			canvas.draw(path);
		}

		if (uuid != null) {
			canvas.setTransform(screen);  /* back to screen space */
			canvas.setColor(Color.BLACK);
			canvas.drawString(uuid, 8, 16);
		}
	}
}
