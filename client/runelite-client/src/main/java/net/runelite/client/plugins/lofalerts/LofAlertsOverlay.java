/*
 * Fall of Varrock — server-driven on-screen alerts.
 */
package net.runelite.client.plugins.lofalerts;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.client.plugins.loftheme.LofTheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class LofAlertsOverlay extends Overlay
{
	/** RuneScape bold, scaled up — §3 forbids Font.SANS_SERIF in-window and this banner was the
	 *  last place still using it, so the alert read as a different product to everything else. */
	private static final Font BANNER_FONT = FontManager.getRunescapeBoldFont().deriveFont(18f);

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

		final int padX = 14;
		final int padY = 9;
		final int h = fm.getHeight() + padY * 2;
		final BufferedImage logo = LofTheme.logo();
		final int logoSize = logo != null ? h - 8 : 0;
		final int textX = padX + (logo != null ? logoSize + 8 : 0);
		final int w = textX + fm.stringWidth(text) + padX;

		// pulsing border alpha for attention (~1s cycle)
		final double phase = (System.currentTimeMillis() % 1000L) / 1000.0;
		final int pulse = (int) (120 + 135 * Math.abs(Math.sin(phase * Math.PI)));

		// background
		graphics.setColor(LofTheme.alpha(LofTheme.PANEL_OPAQUE, 205));
		graphics.fillRoundRect(0, 0, w, h, 14, 14);

		// pulsing ember border
		final Stroke oldStroke = graphics.getStroke();
		graphics.setStroke(new BasicStroke(2f));
		graphics.setColor(LofTheme.alpha(LofTheme.EMBER, pulse));
		graphics.drawRoundRect(1, 1, w - 3, h - 3, 14, 14);
		graphics.setStroke(oldStroke);

		// shield logo + text (shadow + fill)
		if (logo != null)
		{
			graphics.drawImage(logo, padX, 4, logoSize, logoSize, null);
		}
		LofTheme.shadowText(graphics, text, textX, padY + fm.getAscent(), LofTheme.LAVA);

		return new Dimension(w, h);
	}
}
