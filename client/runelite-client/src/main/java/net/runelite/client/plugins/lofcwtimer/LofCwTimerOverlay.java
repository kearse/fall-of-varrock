/*
 * Fall of Varrock — Castle Wars timer overlay (renderer).
 *
 * Sits above the chatbox on the right-hand side (the classic Castle Wars spot). Reads four
 * server-published varps:
 *   4620 = seconds remaining, 4621 = mode (0 hidden, 1 waiting room, 2 in the war),
 *   4622 = Saradomin score, 4623 = Zamorak score.
 *
 * House style matches the PK stats box: RuneScape small font, shadowed text, no panel.
 */
package net.runelite.client.plugins.lofcwtimer;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.plugins.loftheme.LofTheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class LofCwTimerOverlay extends Overlay
{
	static final int VARP_SECONDS = 4620;
	static final int VARP_MODE = 4621; // 0 hidden, 1 waiting, 2 in-game
	static final int VARP_SARA_SCORE = 4622;
	static final int VARP_ZAM_SCORE = 4623;

	private static final Color YELLOW = new Color(255, 255, 0);
	private static final Color SARA_BLUE = new Color(90, 140, 255);
	private static final Color ZAM_RED = new Color(255, 80, 80);
	private static final Color WHITE = Color.WHITE;

	private final Client client;
	private final LofCwTimerConfig config;

	@Inject
	private LofCwTimerOverlay(Client client, LofCwTimerConfig config)
	{
		this.client = client;
		this.config = config;
		setPosition(OverlayPosition.ABOVE_CHATBOX_RIGHT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setMovable(true);
		setSnappable(true);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.enabled())
		{
			return null;
		}

		final int mode = client.getVarpValue(VARP_MODE);
		if (mode <= 0)
		{
			return null;
		}

		final int secs = Math.max(0, client.getVarpValue(VARP_SECONDS));
		final String time = String.format("%d:%02d", secs / 60, secs % 60);

		graphics.setFont(FontManager.getRunescapeSmallFont());
		final FontMetrics fm = graphics.getFontMetrics();
		final int lineH = fm.getHeight();

		final String line1 = mode == 1 ? "Next war: " + time : "War ends: " + time;

		int w = fm.stringWidth(line1);
		int h = lineH;
		int y = fm.getAscent();

		drawShadowed(graphics, line1, 0, y, YELLOW);

		if (mode == 2)
		{
			// Live score, team-coloured: "Saradomin 2 - 1 Zamorak"
			final String sara = "Saradomin " + client.getVarpValue(VARP_SARA_SCORE);
			final String dash = " - ";
			final String zam = client.getVarpValue(VARP_ZAM_SCORE) + " Zamorak";

			y += lineH;
			int x = 0;
			drawShadowed(graphics, sara, x, y, SARA_BLUE);
			x += fm.stringWidth(sara);
			drawShadowed(graphics, dash, x, y, WHITE);
			x += fm.stringWidth(dash);
			drawShadowed(graphics, zam, x, y, ZAM_RED);

			w = Math.max(w, fm.stringWidth(sara + dash + zam));
			h += lineH;
		}

		return new Dimension(w, h);
	}

	private void drawShadowed(Graphics2D graphics, String text, int x, int y, Color color)
	{
		LofTheme.shadowText(graphics, text, x, y, color);
	}
}
