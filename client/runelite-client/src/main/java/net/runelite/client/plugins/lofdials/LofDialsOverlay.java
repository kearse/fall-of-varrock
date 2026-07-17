/*
 * Fall of Varrock — war dial row (renderer).
 *
 * A right-anchored row of circular gauges above the chatbox. War supplies is pinned to the far
 * right; the campaign/conquest/boss progress dial and the Slayer-task dial stack to its left and
 * only appear when they have data, so the row grows leftward as fights and contracts come and go:
 *
 *     [ slayer ] [ campaign ] [ supplies ]
 *
 * Each dial reads one packed varp published by a server HUD plugin (no custom packets):
 *   4616 slayer   — bits 0-11 killed | bits 12-23 total
 *   4601 progress — bits 0-1 kind (0 none / 1 boss / 2 campaign) | bits 2-8 pct | bits 9-10 tier
 *   4609 supplies — bits 0-11 meter | bits 12-23 max | bit 24 campaign ready | bit 25 conquest ready
 */
package net.runelite.client.plugins.lofdials;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.plugins.loftheme.LofTheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class LofDialsOverlay extends Overlay
{
	private static final int VARP_PROGRESS = 4601;
	private static final int VARP_SUPPLY = 4609;
	private static final int VARP_SLAYER = 4616;

	private static final int DIAL = 56;         // dial diameter
	private static final float RING_W = 7f;     // arc stroke width
	private static final int LABEL_GAP = 4;     // gap between dial and its label
	private static final int COL_GAP = 8;       // gap between adjacent dials

	private final Client client;
	private final LofDialsConfig config;

	@Inject
	private LofDialsOverlay(Client client, LofDialsConfig config)
	{
		this.client = client;
		this.config = config;
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

		// Build the row left-to-right: slayer, then campaign/conquest, then supplies (pinned right).
		final boolean showNumbers = config.showNumbers();
		final List<Dial> dials = new ArrayList<>();
		if (config.showSlayer())
		{
			addSlayer(dials, showNumbers);
		}
		if (config.showProgress())
		{
			addProgress(dials);
		}
		if (config.showSupplies())
		{
			addSupplies(dials, showNumbers);
		}
		if (dials.isEmpty())
		{
			return null;
		}

		final Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setFont(FontManager.getRunescapeSmallFont());
		final FontMetrics fm = g.getFontMetrics();
		final int h = DIAL + LABEL_GAP + fm.getHeight();

		int x = 0;
		for (int i = 0; i < dials.size(); i++)
		{
			final Dial d = dials.get(i);
			final int colW = Math.max(DIAL + 8, fm.stringWidth(d.label) + 4);
			drawDial(g, fm, x, colW, d);
			x += colW + COL_GAP;
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(x - COL_GAP, h);
	}

	private void drawDial(Graphics2D g, FontMetrics fm, int x, int colW, Dial d)
	{
		final int dialX = x + (colW - DIAL) / 2;
		final int inset = (int) (RING_W / 2f) + 1;

		// backing disc
		g.setColor(LofTheme.alpha(LofTheme.PANEL_OPAQUE, 205));
		g.fillOval(dialX, 0, DIAL, DIAL);

		// pulsing glow ring (used by the supply dial once a march is affordable, ~1.5s cycle)
		if (d.pulse)
		{
			final double phase = (System.currentTimeMillis() % 1500L) / 1500.0;
			final int pulse = (int) (40 + 90 * Math.abs(Math.sin(phase * Math.PI)));
			g.setColor(LofTheme.alpha(d.pulseColor, pulse));
			g.setStroke(new BasicStroke(RING_W + 4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(new Arc2D.Double(dialX + inset, inset, DIAL - inset * 2, DIAL - inset * 2, 90, -360 * d.frac, Arc2D.OPEN));
		}

		// track ring + value arc (from 12 o'clock, clockwise)
		g.setStroke(new BasicStroke(RING_W, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(new Color(255, 255, 255, 28));
		g.draw(new Arc2D.Double(dialX + inset, inset, DIAL - inset * 2, DIAL - inset * 2, 0, 360, Arc2D.OPEN));
		if (d.frac > 0)
		{
			g.setColor(d.arc);
			g.draw(new Arc2D.Double(dialX + inset, inset, DIAL - inset * 2, DIAL - inset * 2, 90, -360 * d.frac, Arc2D.OPEN));
		}

		// centre value
		final int vx = dialX + (DIAL - fm.stringWidth(d.center)) / 2;
		final int vy = (DIAL - fm.getHeight()) / 2 + fm.getAscent();
		LofTheme.shadowText(g, d.center, vx, vy, LofTheme.TEXT);

		// label under the dial
		final int lx = x + (colW - fm.stringWidth(d.label)) / 2;
		final int ly = DIAL + LABEL_GAP + fm.getAscent();
		LofTheme.shadowText(g, d.label, lx, ly, d.labelColor);
	}

	private void addSupplies(List<Dial> dials, boolean showNumbers)
	{
		final int packed = client.getVarpValue(VARP_SUPPLY);
		if (packed == 0)
		{
			return; // server not publishing yet
		}
		final int meter = packed & 0xFFF;
		final int max = (packed >> 12) & 0xFFF;
		if (max <= 0)
		{
			return;
		}
		final boolean campaignReady = (packed & (1 << 24)) != 0;
		final boolean conquestReady = (packed & (1 << 25)) != 0;
		final double frac = Math.min(1.0, meter / (double) max);

		final String label = conquestReady ? "CONQUEST READY" : (campaignReady ? "CAMPAIGN READY" : "WAR SUPPLIES");
		final Color arc = campaignReady ? LofTheme.GOLD : LofTheme.EMBER;
		final String center = showNumbers ? String.valueOf(meter) : (int) Math.round(frac * 100) + "%";
		final Color labelColor = campaignReady ? LofTheme.GOLD : LofTheme.TEXT_DIM;

		final Dial d = new Dial(frac, arc, center, label, labelColor);
		if (campaignReady)
		{
			d.pulse = true;
			d.pulseColor = conquestReady ? LofTheme.LAVA : LofTheme.GOLD;
		}
		dials.add(d);
	}

	private void addProgress(List<Dial> dials)
	{
		final int packed = client.getVarpValue(VARP_PROGRESS);
		final int kind = packed & 0x3;
		if (kind == 0)
		{
			return; // not in a campaign/boss fight
		}
		final int pct = Math.max(0, Math.min(100, (packed >> 2) & 0x7F));
		final int tier = (packed >> 9) & 0x3;

		final String label = kind == 1 ? "BOSS RAID" : (tier == 1 ? "CONQUEST" : "CAMPAIGN");
		final Color arc = kind == 1 ? LofTheme.EMBER : LofTheme.GOLD_DIM;
		dials.add(new Dial(pct / 100.0, arc, pct + "%", label, LofTheme.TEXT_DIM));
	}

	private void addSlayer(List<Dial> dials, boolean showNumbers)
	{
		final int packed = client.getVarpValue(VARP_SLAYER);
		final int total = (packed >> 12) & 0xFFF;
		if (packed == 0 || total <= 0)
		{
			return; // no active Slayer task
		}
		final int killed = Math.min(total, packed & 0xFFF);
		final double frac = killed / (double) total;
		final String center = showNumbers ? killed + "/" + total : (int) Math.round(frac * 100) + "%";
		dials.add(new Dial(frac, LofTheme.EMBER, center, "SLAYER", LofTheme.TEXT_DIM));
	}

	/** One gauge in the row: its fill fraction, arc colour, centre value, label + optional pulse. */
	private static final class Dial
	{
		private final double frac;
		private final Color arc;
		private final String center;
		private final String label;
		private final Color labelColor;
		private boolean pulse;
		private Color pulseColor;

		private Dial(double frac, Color arc, String center, String label, Color labelColor)
		{
			this.frac = frac;
			this.arc = arc;
			this.center = center;
			this.label = label;
			this.labelColor = labelColor;
		}
	}
}
