/*
 * Kingdom of Lumbridge — PK / KD stats overlay (renderer).
 *
 * Roak-style: RuneScape font, green text on a transparent background (no panel), top-left,
 * shown ONLY while in the wilderness. Reads four server-published varps:
 *   4602 = kills, 4603 = deaths, 4604 = elo, 4605 = rank percentile in tenths of a percent.
 */
package net.runelite.client.plugins.lofpkstats;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class LofPkStatsOverlay extends Overlay
{
	static final int VARP_KILLS = 4602;
	static final int VARP_DEATHS = 4603;
	static final int VARP_ELO = 4604;
	static final int VARP_RANK = 4605; // tenths of a percent

	/** Server-authoritative wilderness level (0 = safe, published by WildernessOverlayPlugin) —
	 *  gate the box on this so it matches the skull overlay exactly. */
	private static final int VARP_WILD_LEVEL = 4606;

	/** In-game public-chat yellow. */
	private static final Color YELLOW = new Color(255, 255, 0);
	/** Nudge the whole box down ~¼ inch from the top-left corner. */
	private static final int TOP_OFFSET = 24;

	private final Client client;
	private final LofPkStatsConfig config;

	@Inject
	private LofPkStatsOverlay(Client client, LofPkStatsConfig config)
	{
		this.client = client;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
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

		// Only in the wilderness (server-authoritative, matches the skull overlay).
		if (client.getVarpValue(VARP_WILD_LEVEL) <= 0)
		{
			return null;
		}

		final int kills = client.getVarpValue(VARP_KILLS);
		final int deaths = client.getVarpValue(VARP_DEATHS);
		final int elo = client.getVarpValue(VARP_ELO);
		final int rankTenths = client.getVarpValue(VARP_RANK);

		final double kd = deaths == 0 ? kills : (double) kills / deaths;

		final List<String> lines = new ArrayList<>(4);
		lines.add("Kills: " + kills);
		lines.add("Deaths: " + deaths);
		lines.add("K/D Ratio: " + String.format("%.3f", kd));
		if (config.showRank() && rankTenths > 0)
		{
			lines.add("Elo: " + elo + " (TOP " + String.format("%.1f", rankTenths / 10.0) + "%)");
		}
		else
		{
			lines.add("Elo: " + elo);
		}

		graphics.setFont(FontManager.getRunescapeSmallFont());
		final FontMetrics fm = graphics.getFontMetrics();
		final int lineH = fm.getHeight();

		int w = 0;
		for (String line : lines)
		{
			w = Math.max(w, fm.stringWidth(line));
		}
		final int h = TOP_OFFSET + lines.size() * lineH;

		int y = TOP_OFFSET + fm.getAscent();
		for (String line : lines)
		{
			graphics.setColor(Color.BLACK);
			graphics.drawString(line, 1, y + 1);
			graphics.setColor(YELLOW);
			graphics.drawString(line, 0, y);
			y += lineH;
		}

		return new Dimension(w, h);
	}
}
