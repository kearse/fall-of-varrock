/*
 * Kingdom of Lumbridge — server-driven on-screen alerts.
 */
package net.runelite.client.plugins.lofalerts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Stroke;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class LofAlertsOverlay extends Overlay
{
	private static final Font BANNER_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 18);

	private final LofAlertsPlugin plugin;

	@Inject
	private LofAlertsOverlay(LofAlertsPlugin plugin)
	{
		this.plugin = plugin;
		setPosition(OverlayPosition.TOP_CENTER);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		final String text = plugin.getActiveBannerText();
		if (text == null)
		{
			return null;
		}

		graphics.setFont(BANNER_FONT);
		final FontMetrics fm = graphics.getFontMetrics();

		final int padX = 16;
		final int padY = 9;
		final int w = fm.stringWidth(text) + padX * 2;
		final int h = fm.getHeight() + padY * 2;

		// pulsing border alpha for attention (~1s cycle)
		final double phase = (System.currentTimeMillis() % 1000L) / 1000.0;
		final int pulse = (int) (120 + 135 * Math.abs(Math.sin(phase * Math.PI)));

		// background
		graphics.setColor(new Color(0, 0, 0, 190));
		graphics.fillRoundRect(0, 0, w, h, 14, 14);

		// pulsing border
		final Stroke oldStroke = graphics.getStroke();
		graphics.setStroke(new BasicStroke(2f));
		graphics.setColor(new Color(210, 40, 40, pulse));
		graphics.drawRoundRect(1, 1, w - 3, h - 3, 14, 14);
		graphics.setStroke(oldStroke);

		// text (shadow + fill)
		final int tx = padX;
		final int ty = padY + fm.getAscent();
		graphics.setColor(Color.BLACK);
		graphics.drawString(text, tx + 1, ty + 1);
		graphics.setColor(new Color(255, 90, 90));
		graphics.drawString(text, tx, ty);

		return new Dimension(w, h);
	}
}
