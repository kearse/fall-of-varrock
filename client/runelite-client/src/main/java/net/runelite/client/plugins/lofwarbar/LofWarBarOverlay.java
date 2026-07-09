/*
 * Kingdom of Lumbridge — war progress bar (renderer).
 *
 * Reads one packed varp and draws a labelled progress bar (TOP_CENTER, movable).
 *   bits 0-1 kind (0 none / 1 boss / 2 campaign), bits 2-8 pct (0-100), bits 9-10 tier.
 */
package net.runelite.client.plugins.lofwarbar;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class LofWarBarOverlay extends Overlay
{
	private static final int VARP_PROGRESS = 4601;

	private static final int W = 200;
	private static final int H = 19;

	private static final Color TRACK = new Color(0, 0, 0, 205);
	private static final Color BOSS_FILL = new Color(196, 54, 40);     // red — boss HP depleted
	private static final Color CAMPAIGN_FILL = new Color(70, 132, 214); // blue — campaign cleared
	private static final Color BORDER = new Color(255, 255, 255, 55);

	private final Client client;
	private final LofWarBarConfig config;

	@Inject
	private LofWarBarOverlay(Client client, LofWarBarConfig config)
	{
		this.client = client;
		this.config = config;
		setPosition(OverlayPosition.TOP_CENTER);
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

		final int packed = client.getVarpValue(VARP_PROGRESS);
		final int kind = packed & 0x3;
		if (kind == 0)
		{
			return null; // not in a campaign/boss fight
		}

		final int pct = Math.max(0, Math.min(100, (packed >> 2) & 0x7F));
		final int tier = (packed >> 9) & 0x3;

		final String label = kind == 1 ? "Boss Raid" : (tier == 1 ? "Conquest" : "Campaign");
		final Color fill = kind == 1 ? BOSS_FILL : CAMPAIGN_FILL;
		final String text = label + " — " + pct + "%";

		// track + fill
		graphics.setColor(TRACK);
		graphics.fillRoundRect(0, 0, W, H, 8, 8);
		final int fw = (W - 2) * pct / 100;
		if (fw > 0)
		{
			graphics.setColor(fill);
			graphics.fillRoundRect(1, 1, fw, H - 2, 7, 7);
		}
		graphics.setColor(BORDER);
		graphics.drawRoundRect(0, 0, W - 1, H - 1, 8, 8);

		// centred label (shadow + white)
		graphics.setFont(FontManager.getRunescapeFont());
		final FontMetrics fm = graphics.getFontMetrics();
		final int tx = (W - fm.stringWidth(text)) / 2;
		final int ty = (H - fm.getHeight()) / 2 + fm.getAscent();
		graphics.setColor(Color.BLACK);
		graphics.drawString(text, tx + 1, ty + 1);
		graphics.setColor(Color.WHITE);
		graphics.drawString(text, tx, ty);

		return new Dimension(W, H);
	}
}
