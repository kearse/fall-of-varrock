/*
 * Fall of Varrock — Wilderness level indicator (client overlay), roak-style.
 *
 * Vanilla-OSRS look: a skull on top of "Level: N" and the attackable combat range "lo-hi",
 * in red RuneScape font on a transparent background, shown only while in the wilderness.
 * Drag it under the minimap (RuneLite persists the position). Zone geometry is mirrored from
 * the server's PvpZones.kt in WildernessZones — keep the two in sync.
 *
 * Skull art: if a `skull.png` resource is bundled next to this class it is used verbatim
 * (pixel-exact, e.g. the OSRS skull sprite); otherwise a red vector skull-and-crossbones is
 * drawn as a fallback.
 */
package net.runelite.client.plugins.wildernesslines;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ImageUtil;

class WildernessBannerOverlay extends Overlay
{
	private static final Color RED = new Color(204, 34, 34);
	private static final Color BONE = new Color(228, 70, 70);

	/** Native OSRS PvP "skull" overlay container (interface 90, child 44) — sits below the minimap
	 *  at the vanilla wilderness-indicator spot. We anchor to it and hide it (its level is wrong for
	 *  our custom zones), drawing our correct skull/level/range in its place. */
	private static final int PVP_WILDERNESS_SKULL_CONTAINER = 5898284;

	/** Server-authoritative wilderness level (0 = safe), published by WildernessOverlayPlugin.
	 *  Used instead of a client-side zone mirror so the skull never drifts from PvpZones. */
	private static final int VARP_WILD_LEVEL = 4606;

	private final Client client;
	private final WildernessBannerConfig config;

	/** The OSRS skull sprite if bundled (skull.png), else null → vector fallback. */
	private final BufferedImage skullImg;

	/** Last-known canvas bounds of the native PvP skull container (the OSRS-default spot). */
	private Rectangle nativeBounds;

	@Inject
	private WildernessBannerOverlay(Client client, WildernessBannerConfig config)
	{
		this.client = client;
		this.config = config;
		this.skullImg = loadSkull();
		// Auto-positioned onto the vanilla wilderness-indicator spot below the minimap.
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	private static BufferedImage loadSkull()
	{
		try
		{
			return ImageUtil.loadImageResource(WildernessBannerOverlay.class, "skull.png");
		}
		catch (Exception | Error e)
		{
			return null; // not bundled yet — use the vector fallback
		}
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		final Player local = client.getLocalPlayer();
		if (local == null)
		{
			return null;
		}

		// Server-authoritative: >0 only when actually in the wild (no client-side zone mirror to drift).
		final int wl = client.getVarpValue(VARP_WILD_LEVEL);
		if (wl <= 0)
		{
			return null;
		}

		// Anchor onto the vanilla wilderness-indicator spot (below the minimap), and hide the native
		// one — its level is wrong for our custom zones. Capture its bounds while visible; reuse cached.
		final Widget pvp = client.getWidget(PVP_WILDERNESS_SKULL_CONTAINER);
		if (pvp != null)
		{
			final Rectangle b = pvp.getBounds();
			if (b != null && b.width > 0 && b.height > 0 && b.x >= 0 && b.y >= 0)
			{
				nativeBounds = b;
			}
			pvp.setHidden(true);
		}
		if (nativeBounds == null)
		{
			return null; // wait until we know where OSRS puts it
		}

		final int cb = local.getCombatLevel();
		final int lo = Math.max(3, cb - wl);
		final int hi = Math.min(126, cb + wl);

		final String levelText = "Level: " + wl;
		final String rangeText = lo + "-" + hi;

		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setFont(FontManager.getRunescapeFont());
		final FontMetrics fm = graphics.getFontMetrics();
		final int lineH = fm.getHeight();

		final int skullSize = 25;
		final int skullW = skullImg != null ? skullImg.getWidth() : skullSize;
		final int skullH = skullImg != null ? skullImg.getHeight() : skullSize;
		final int gap = 1;

		final int levelW = fm.stringWidth(levelText);
		final int rangeW = fm.stringWidth(rangeText);
		final int w = Math.max(skullW, Math.max(levelW, rangeW));
		final int h = skullH + gap + lineH + lineH;

		// skull on top (centered)
		if (skullImg != null)
		{
			graphics.drawImage(skullImg, (w - skullW) / 2, 0, null);
		}
		else
		{
			drawPkSkull(graphics, (w - skullSize) / 2, 0, skullSize);
		}

		// "Level: N" then "lo-hi", centered, red with black shadow
		int y = skullH + gap + fm.getAscent();
		drawCentered(graphics, levelText, w, y, fm);
		y += lineH;
		drawCentered(graphics, rangeText, w, y, fm);

		// Centre our content horizontally on the native indicator's spot (applies next frame).
		setPreferredLocation(new Point(
			nativeBounds.x + (nativeBounds.width - w) / 2,
			nativeBounds.y));

		return new Dimension(w, h);
	}

	private static void drawCentered(Graphics2D g, String s, int w, int baselineY, FontMetrics fm)
	{
		final int x = (w - fm.stringWidth(s)) / 2;
		g.setColor(Color.BLACK);
		g.drawString(s, x + 1, baselineY + 1);
		g.setColor(RED);
		g.drawString(s, x, baselineY);
	}

	/** Fallback red skull-and-crossbones of side [s] px at (x,y). */
	private static void drawPkSkull(Graphics2D g, int x, int y, int s)
	{
		final Stroke old = g.getStroke();
		final float boneW = Math.max(2f, s * 0.16f);
		g.setStroke(new BasicStroke(boneW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(BONE.darker());
		final int m = Math.max(2, s / 6);
		g.drawLine(x + m, y + m, x + s - m, y + s - m);
		g.drawLine(x + s - m, y + m, x + m, y + s - m);
		g.setStroke(old);
		final int knob = Math.max(3, (int) (s * 0.24));
		g.setColor(BONE);
		fillKnob(g, x + m, y + m, knob);
		fillKnob(g, x + s - m, y + m, knob);
		fillKnob(g, x + m, y + s - m, knob);
		fillKnob(g, x + s - m, y + s - m, knob);

		final int hw = (int) (s * 0.60);
		final int hh = (int) (s * 0.52);
		final int hx = x + (s - hw) / 2;
		final int hy = y + (int) (s * 0.10);
		g.setColor(BONE);
		g.fill(new Ellipse2D.Double(hx, hy, hw, hh));
		final int jw = (int) (hw * 0.6);
		final int jh = (int) (s * 0.20);
		g.fillRoundRect(hx + (hw - jw) / 2, hy + hh - 2, jw, jh, 3, 3);

		g.setColor(Color.BLACK);
		final int eye = Math.max(2, (int) (s * 0.16));
		final int eyeY = hy + (int) (hh * 0.32);
		g.fill(new Ellipse2D.Double(hx + hw * 0.18, eyeY, eye, eye));
		g.fill(new Ellipse2D.Double(hx + hw * 0.62, eyeY, eye, eye));
		final int noseW = Math.max(1, s / 10);
		g.fillRect(hx + hw / 2 - noseW / 2, hy + (int) (hh * 0.60), noseW, Math.max(1, s / 8));
	}

	private static void fillKnob(Graphics2D g, int cx, int cy, int d)
	{
		g.fill(new Ellipse2D.Double(cx - d / 2.0, cy - d / 2.0, d, d));
	}
}
