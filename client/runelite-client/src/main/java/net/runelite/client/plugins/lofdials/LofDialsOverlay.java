/*
 * Fall of Varrock — war dial column (renderer).
 *
 * A left-anchored column of circular gauges. The Slayer-task dial sits on top, the
 * campaign/conquest/boss progress dial below it, and war supplies at the bottom; each only appears
 * when it has data, so the column grows and shrinks as fights and contracts come and go:
 *
 *     [ slayer   ]
 *     [ campaign ]
 *     [ supplies ]
 *
 * The dials carry no printed labels — hovering one shows a cursor tooltip naming it, the same way
 * hovering an NPC does.
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
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.client.plugins.loftheme.LofTheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;

class LofDialsOverlay extends Overlay
{
	private static final int VARP_PROGRESS = 4601;
	private static final int VARP_SUPPLY = 4609;
	private static final int VARP_SLAYER = 4616;

	private static final int DIAL = 40;         // dial diameter
	private static final float RING_W = 5f;     // arc stroke width
	private static final int ROW_GAP = 8;       // gap between stacked dials

	private final Client client;
	private final LofDialsConfig config;
	private final TooltipManager tooltipManager;

	@Inject
	private LofDialsOverlay(Client client, LofDialsConfig config, TooltipManager tooltipManager)
	{
		this.client = client;
		this.config = config;
		this.tooltipManager = tooltipManager;
		setPosition(OverlayPosition.TOP_LEFT);
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

		// Build the column top-to-bottom: slayer, then campaign/conquest, then supplies.
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

		// stack the dials top-to-bottom against the left edge, remembering each one's local box
		// so the hover test below can map it to canvas space.
		int y = 0;
		for (final Dial d : dials)
		{
			d.box = new Rectangle(0, y, DIAL, DIAL);
			drawDial(g, fm, y, d);
			y += DIAL + ROW_GAP;
		}

		addHoverTooltip(dials);

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(DIAL, y - ROW_GAP);
	}

	/** If the cursor is over a dial, show its name as a cursor tooltip — like hovering an NPC. */
	private void addHoverTooltip(List<Dial> dials)
	{
		if (client.isMenuOpen())
		{
			return;
		}
		final Point mouse = client.getMouseCanvasPosition();
		if (mouse == null)
		{
			return;
		}
		final Rectangle overlay = getBounds();
		for (final Dial d : dials)
		{
			if (d.box == null)
			{
				continue;
			}
			final Rectangle hit = new Rectangle(d.box);
			hit.translate(overlay.x, overlay.y);
			if (hit.contains(mouse.getX(), mouse.getY()))
			{
				tooltipManager.add(new Tooltip(d.label));
				break;
			}
		}
	}

	private void drawDial(Graphics2D g, FontMetrics fm, int y, Dial d)
	{
		final int inset = (int) (RING_W / 2f) + 1;

		// backing disc
		g.setColor(LofTheme.alpha(LofTheme.PANEL_OPAQUE, 205));
		g.fillOval(0, y, DIAL, DIAL);

		// pulsing glow ring (used by the supply dial once a march is affordable, ~1.5s cycle)
		if (d.pulse)
		{
			final double phase = (System.currentTimeMillis() % 1500L) / 1500.0;
			final int pulse = (int) (40 + 90 * Math.abs(Math.sin(phase * Math.PI)));
			g.setColor(LofTheme.alpha(d.pulseColor, pulse));
			g.setStroke(new BasicStroke(RING_W + 4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(new Arc2D.Double(inset, y + inset, DIAL - inset * 2, DIAL - inset * 2, 90, -360 * d.frac, Arc2D.OPEN));
		}

		// track ring + value arc (from 12 o'clock, clockwise)
		g.setStroke(new BasicStroke(RING_W, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(new Color(255, 255, 255, 28));
		g.draw(new Arc2D.Double(inset, y + inset, DIAL - inset * 2, DIAL - inset * 2, 0, 360, Arc2D.OPEN));
		if (d.frac > 0)
		{
			g.setColor(d.arc);
			g.draw(new Arc2D.Double(inset, y + inset, DIAL - inset * 2, DIAL - inset * 2, 90, -360 * d.frac, Arc2D.OPEN));
		}

		// centre value
		final int vx = (DIAL - fm.stringWidth(d.center)) / 2;
		final int vy = y + (DIAL - fm.getHeight()) / 2 + fm.getAscent();
		LofTheme.shadowText(g, d.center, vx, vy, LofTheme.TEXT);
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

		final Dial d = new Dial(frac, arc, center, label);
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
		dials.add(new Dial(pct / 100.0, arc, pct + "%", label));
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
		dials.add(new Dial(frac, LofTheme.EMBER, center, "SLAYER"));
	}

	/** One gauge in the column: its fill fraction, arc colour, centre value, hover name + optional pulse. */
	private static final class Dial
	{
		private final double frac;
		private final Color arc;
		private final String center;
		private final String label;   // shown as the hover tooltip, not printed under the dial
		private boolean pulse;
		private Color pulseColor;
		private Rectangle box;        // local bounds, filled in during layout for hover hit-testing

		private Dial(double frac, Color arc, String center, String label)
		{
			this.frac = frac;
			this.arc = arc;
			this.center = center;
			this.label = label;
		}
	}
}
