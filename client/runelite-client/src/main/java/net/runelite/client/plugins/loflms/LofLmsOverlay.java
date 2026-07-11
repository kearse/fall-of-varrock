/*
 * Fall of Varrock — Last Man Standing HUD (renderer).
 *
 * Reads one packed varp and draws the OSRS-style LMS panel (TOP_RIGHT, movable):
 *   bit 0 in-game | bits 1-4 players remaining | bits 5-8 kills | bits 9-15 fog countdown seconds.
 * Hidden entirely when not in a game (bit 0 clear).
 */
package net.runelite.client.plugins.loflms;

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

class LofLmsOverlay extends Overlay
{
	private static final int VARP_HUD = 4608;

	private static final int W = 148;
	private static final int PAD = 6;
	private static final int LINE_GAP = 2;

	private static final Color PANEL = LofTheme.alpha(LofTheme.PANEL_OPAQUE, 205);
	private static final Color BORDER = LofTheme.alpha(LofTheme.EMBER_DARK, 210);
	private static final Color TITLE = LofTheme.GOLD;
	private static final Color TEXT = LofTheme.TEXT;
	private static final Color VALUE = LofTheme.GOLD;
	private static final Color FOG_ALERT = LofTheme.LAVA; // when the fog is (about to be) moving

	private final Client client;
	private final LofLmsConfig config;

	@Inject
	private LofLmsOverlay(Client client, LofLmsConfig config)
	{
		this.client = client;
		this.config = config;
		setPosition(OverlayPosition.TOP_RIGHT);
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

		final int packed = client.getVarpValue(VARP_HUD);
		if ((packed & 0x1) == 0)
		{
			return null; // not in a game
		}

		final int remaining = (packed >> 1) & 0xF;
		final int kills = (packed >> 5) & 0xF;
		final int fogSecs = (packed >> 9) & 0x7F;

		graphics.setFont(FontManager.getRunescapeSmallFont());
		final FontMetrics fm = graphics.getFontMetrics();
		final int lineH = fm.getHeight() + LINE_GAP;
		final int h = PAD * 2 + lineH * 4 - LINE_GAP;

		// panel
		graphics.setColor(PANEL);
		graphics.fillRoundRect(0, 0, W, h, 8, 8);
		graphics.setColor(BORDER);
		graphics.drawRoundRect(0, 0, W - 1, h - 1, 8, 8);

		int y = PAD + fm.getAscent();
		drawCentered(graphics, fm, "Last Man Standing", y, TITLE);
		y += lineH;
		drawPair(graphics, fm, "Players remaining:", String.valueOf(remaining), y, VALUE);
		y += lineH;
		drawPair(graphics, fm, "Kills:", String.valueOf(kills), y, VALUE);
		y += lineH;
		if (fogSecs > 0)
		{
			drawPair(graphics, fm, "Fog advances in:", fogSecs + "s", y, fogSecs <= 5 ? FOG_ALERT : VALUE);
		}
		else
		{
			drawPair(graphics, fm, "Fog:", "CLOSED IN", y, FOG_ALERT);
		}

		return new Dimension(W, h);
	}

	private void drawCentered(Graphics2D g, FontMetrics fm, String text, int y, Color color)
	{
		final int x = (W - fm.stringWidth(text)) / 2;
		g.setColor(Color.BLACK);
		g.drawString(text, x + 1, y + 1);
		g.setColor(color);
		g.drawString(text, x, y);
	}

	private void drawPair(Graphics2D g, FontMetrics fm, String label, String value, int y, Color valueColor)
	{
		g.setColor(Color.BLACK);
		g.drawString(label, PAD + 1, y + 1);
		g.setColor(TEXT);
		g.drawString(label, PAD, y);
		final int vx = W - PAD - fm.stringWidth(value);
		g.setColor(Color.BLACK);
		g.drawString(value, vx + 1, y + 1);
		g.setColor(valueColor);
		g.drawString(value, vx, y);
	}
}
