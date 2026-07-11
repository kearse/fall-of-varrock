/*
 * Fall of Varrock — war-supply dial (renderer).
 *
 * Circular gauge (TOP_RIGHT, movable) showing the realm's war-supply meter. Reads one packed varp:
 *   bits 0-11 meter | bits 12-23 max | bit 24 campaign affordable | bit 25 conquest affordable.
 * Hidden until the server publishes (packed 0). Ember arc while stocking; turns gold with a
 * pulsing glow once a campaign can march — the "go tell a Minister" signal.
 */
package net.runelite.client.plugins.lofsupplies;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.plugins.loftheme.LofTheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class LofSuppliesOverlay extends Overlay
{
	private static final int VARP_SUPPLY = 4609;

	private static final int DIAL = 56;         // dial diameter
	private static final float RING_W = 7f;     // arc stroke width
	private static final int LABEL_GAP = 4;

	private final Client client;
	private final LofSuppliesConfig config;

	@Inject
	private LofSuppliesOverlay(Client client, LofSuppliesConfig config)
	{
		this.client = client;
		this.config = config;
		// Bottom-right of the chat area (movable, so players can reposition it).
		setPosition(OverlayPosition.ABOVE_CHATBOX_RIGHT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setMovable(true);
		setSnappable(true);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.enabled())
		{
			return null;
		}

		final int packed = client.getVarpValue(VARP_SUPPLY);
		if (packed == 0)
		{
			return null; // server not publishing yet
		}

		final int meter = packed & 0xFFF;
		final int max = (packed >> 12) & 0xFFF;
		if (max <= 0)
		{
			return null;
		}
		final boolean campaignReady = (packed & (1 << 24)) != 0;
		final boolean conquestReady = (packed & (1 << 25)) != 0;
		final double frac = Math.min(1.0, meter / (double) max);

		final Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		g.setFont(FontManager.getRunescapeSmallFont());
		final FontMetrics fm = g.getFontMetrics();
		final String label = conquestReady ? "CONQUEST READY" : (campaignReady ? "CAMPAIGN READY" : "WAR SUPPLIES");
		final int w = Math.max(DIAL + 8, fm.stringWidth(label) + 4);
		final int dialX = (w - DIAL) / 2;
		final int h = DIAL + LABEL_GAP + fm.getHeight();

		// backing disc
		final int inset = (int) (RING_W / 2f) + 1;
		g.setColor(LofTheme.alpha(LofTheme.PANEL_OPAQUE, 205));
		g.fillOval(dialX, 0, DIAL, DIAL);

		// pulsing glow ring once a march is affordable (~1.5s cycle)
		final Color arcColor = campaignReady ? LofTheme.GOLD : LofTheme.EMBER;
		if (campaignReady)
		{
			final double phase = (System.currentTimeMillis() % 1500L) / 1500.0;
			final int pulse = (int) (40 + 90 * Math.abs(Math.sin(phase * Math.PI)));
			g.setColor(LofTheme.alpha(conquestReady ? LofTheme.LAVA : LofTheme.GOLD, pulse));
			g.setStroke(new BasicStroke(RING_W + 4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(new Arc2D.Double(dialX + inset, inset, DIAL - inset * 2, DIAL - inset * 2, 90, -360 * frac, Arc2D.OPEN));
		}

		// track ring + value arc (from 12 o'clock, clockwise)
		g.setStroke(new BasicStroke(RING_W, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(new Color(255, 255, 255, 28));
		g.draw(new Arc2D.Double(dialX + inset, inset, DIAL - inset * 2, DIAL - inset * 2, 0, 360, Arc2D.OPEN));
		if (frac > 0)
		{
			g.setColor(arcColor);
			g.draw(new Arc2D.Double(dialX + inset, inset, DIAL - inset * 2, DIAL - inset * 2, 90, -360 * frac, Arc2D.OPEN));
		}

		// centre value
		final String value = config.showNumbers() ? String.valueOf(meter) : (int) Math.round(frac * 100) + "%";
		final int vx = dialX + (DIAL - fm.stringWidth(value)) / 2;
		final int vy = (DIAL - fm.getHeight()) / 2 + fm.getAscent();
		LofTheme.shadowText(g, value, vx, vy, LofTheme.TEXT);

		// label under the dial
		final int lx = (w - fm.stringWidth(label)) / 2;
		final int ly = DIAL + LABEL_GAP + fm.getAscent();
		LofTheme.shadowText(g, label, lx, ly, campaignReady ? LofTheme.GOLD : LofTheme.TEXT_DIM);

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(w, h);
	}
}
