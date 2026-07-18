/*
 * Fall of Varrock — vote window (renderer + hit-testing).
 *
 * A centred modal listing every toplist as a clickable card: site name on the left, a
 * status pill on the right ("Vote now" in ember, or "ready in 3h 12m" dimmed while that
 * site's cooldown runs). Styled with the shared LofTheme to match the commands panel and
 * teleport portal. Clicking a card opens that site's vote page in the browser and the
 * window stays open; the X (or a click outside) closes it. Geometry lives here so the
 * mouse listener, wheel handler and renderer all agree.
 */
package net.runelite.client.plugins.lofvote;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.plugins.lofvote.LofVotePlugin.Site;
import net.runelite.client.plugins.loftheme.LofTheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class LofVoteOverlay extends Overlay
{
	// hit-test result codes
	static final int OUTSIDE = -1;
	static final int INSIDE = 0;
	static final int CLOSE = 1;
	static final int ROW_BASE = 100;

	// framed-modal standard (docs/overlay-design-system.md §6A), narrower than the
	// commands window — a single list, no tab rail.
	private static final int WIN_W = 400;
	private static final int WIN_ARC = 14;
	private static final int TITLE_H = 38;
	private static final int LIST_X = 12;
	private static final int LIST_W = WIN_W - LIST_X * 2;
	private static final int VP_TOP = TITLE_H + 14;
	private static final int CARD_H = 34;
	private static final int STEP = 40;
	private static final int FOOTER_H = 30;
	private static final int MAX_WIN_H = 440;
	private static final int SCROLLBAR_W = 5;

	private static final Color CLOSE_HOVER = LofTheme.EMBER;
	private static final Color READY_PILL = LofTheme.EMBER;

	private final Client client;
	private final LofVotePlugin plugin;

	private boolean visible;
	private int scroll; // px

	@Inject
	private LofVoteOverlay(Client client, LofVotePlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
	}

	boolean isVisible()
	{
		return visible;
	}

	void setVisible(boolean v)
	{
		visible = v;
	}

	void resetScroll()
	{
		scroll = 0;
	}

	private int rowCount()
	{
		return plugin.getSites().size();
	}

	private int winH()
	{
		return Math.min(MAX_WIN_H, VP_TOP + rowCount() * STEP + FOOTER_H);
	}

	private int vpH()
	{
		return winH() - VP_TOP - FOOTER_H;
	}

	private int maxScroll()
	{
		return Math.max(0, rowCount() * STEP - vpH());
	}

	private int originX()
	{
		// Centre within the game viewport, not the whole canvas, so the window clears the
		// inventory/tab column in fixed mode (§6A).
		final int canvasW = client.getCanvasWidth();
		final int viewportW = client.isResized() ? canvasW : Math.min(canvasW, 512);
		return Math.max(0, Math.min((canvasW - WIN_W) / 2, (viewportW - WIN_W) / 2));
	}

	private int originY()
	{
		return Math.max(0, (client.getCanvasHeight() - winH()) / 2);
	}

	/** Wheel scroll if the cursor is over the list; returns true if consumed. */
	boolean handleScroll(Point p, int rotation)
	{
		if (!visible)
		{
			return false;
		}
		final int ox = originX(), oy = originY();
		if (!new Rectangle(ox + LIST_X, oy + VP_TOP, LIST_W, vpH()).contains(p))
		{
			return false;
		}
		scroll = clamp(scroll + rotation * STEP, 0, maxScroll());
		return true;
	}

	int hitTest(Point p)
	{
		if (!visible)
		{
			return OUTSIDE;
		}
		final int ox = originX(), oy = originY();
		if (!new Rectangle(ox, oy, WIN_W, winH()).contains(p))
		{
			return OUTSIDE;
		}
		if (closeRect(ox, oy).contains(p))
		{
			return CLOSE;
		}
		if (new Rectangle(ox + LIST_X, oy + VP_TOP, LIST_W, vpH()).contains(p))
		{
			final int rel = p.y - (oy + VP_TOP) + scroll;
			final int i = rel / STEP;
			if (i >= 0 && i < rowCount() && (rel - i * STEP) <= CARD_H)
			{
				return ROW_BASE + i;
			}
		}
		return INSIDE;
	}

	/** The site of the row at {@code index}, or null if out of range. */
	Site siteAt(int index)
	{
		final List<Site> sites = plugin.getSites();
		return index >= 0 && index < sites.size() ? sites.get(index) : null;
	}

	private Rectangle closeRect(int ox, int oy)
	{
		return new Rectangle(ox + WIN_W - 30, oy + 9, 20, 20);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!visible || client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}

		final List<Site> sites = plugin.getSites();
		if (sites.isEmpty())
		{
			return null;
		}

		final Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// This overlay draws at ABSOLUTE canvas coordinates. Undo any translate the renderer
		// applied so absolute means absolute.
		final Rectangle selfBounds = getBounds();
		g.translate(-selfBounds.x, -selfBounds.y);

		final int ox = originX(), oy = originY();
		final int winH = winH();
		final Point mouse = mousePoint();

		LofTheme.panel(g, ox, oy, WIN_W, winH, WIN_ARC);

		// header bar with ember accent underline
		final Shape headerClip = g.getClip();
		g.setClip(ox, oy, WIN_W, TITLE_H);
		g.setColor(LofTheme.HEADER);
		g.fillRoundRect(ox, oy, WIN_W, TITLE_H + WIN_ARC, WIN_ARC, WIN_ARC);
		g.setClip(headerClip);
		LofTheme.emberUnderline(g, ox + 1, oy + TITLE_H - 2, WIN_W - 2);

		// shield logo + title
		final BufferedImage logo = LofTheme.logo();
		int titleX = ox + 14;
		if (logo != null)
		{
			g.drawImage(logo, ox + 12, oy + 5, 28, 28, null);
			titleX = ox + 46;
		}
		g.setFont(FontManager.getRunescapeBoldFont());
		LofTheme.shadowText(g, "Vote for Fall of Varrock", titleX, oy + 25, LofTheme.GOLD);

		// close button
		final Rectangle cr = closeRect(ox, oy);
		final boolean closeHov = cr.contains(mouse);
		g.setColor(closeHov ? CLOSE_HOVER : new Color(255, 255, 255, 18));
		g.fillRoundRect(cr.x, cr.y, cr.width, cr.height, 6, 6);
		g.setColor(closeHov ? LofTheme.TEXT : LofTheme.TEXT_DIM);
		final Stroke oldStroke = g.getStroke();
		g.setStroke(new BasicStroke(1.4f));
		g.drawLine(cr.x + 6, cr.y + 6, cr.x + cr.width - 7, cr.y + cr.height - 7);
		g.drawLine(cr.x + cr.width - 7, cr.y + 6, cr.x + 6, cr.y + cr.height - 7);
		g.setStroke(oldStroke);

		// site cards (clipped to viewport)
		final Shape oldClip = g.getClip();
		g.setClip(ox + LIST_X, oy + VP_TOP, LIST_W, vpH());
		g.setFont(FontManager.getRunescapeFont());
		final FontMetrics fm = g.getFontMetrics();
		final int cardW = LIST_W - (maxScroll() > 0 ? SCROLLBAR_W + 4 : 0);
		for (int i = 0; i < sites.size(); i++)
		{
			final int cy = oy + VP_TOP + i * STEP - scroll;
			if (cy + CARD_H < oy + VP_TOP || cy > oy + VP_TOP + vpH())
			{
				continue; // off-screen
			}
			final Site s = sites.get(i);
			final int cx = ox + LIST_X;
			final boolean onCooldown = s.cooldownMins > 0;
			final boolean hov = mouse.x >= cx && mouse.x <= cx + cardW
				&& mouse.y >= cy && mouse.y <= cy + CARD_H
				&& mouse.y >= oy + VP_TOP && mouse.y <= oy + VP_TOP + vpH();

			g.setColor(hov ? LofTheme.ROW_HOVER : LofTheme.ROW);
			g.fillRoundRect(cx, cy, cardW, CARD_H, 8, 8);
			if (hov)
			{
				g.setColor(LofTheme.alpha(LofTheme.EMBER, 150));
				g.drawRoundRect(cx, cy, cardW, CARD_H, 8, 8);
			}

			final int ty = cy + CARD_H / 2 + 4;
			LofTheme.shadowText(g, s.name, cx + 12, ty, onCooldown ? LofTheme.TEXT_DIM : LofTheme.GOLD);

			final String pill = onCooldown ? "ready in " + fmtCooldown(s.cooldownMins) : "Vote now";
			LofTheme.pill(g, fm, pill, cx + cardW - 10, ty,
				onCooldown ? LofTheme.TEXT_DIM : READY_PILL);
		}
		g.setClip(oldClip);

		// scrollbar
		final int ms = maxScroll();
		if (ms > 0)
		{
			final int sbX = ox + LIST_X + LIST_W - SCROLLBAR_W;
			g.setColor(new Color(255, 255, 255, 14));
			g.fillRoundRect(sbX, oy + VP_TOP, SCROLLBAR_W, vpH(), SCROLLBAR_W, SCROLLBAR_W);
			final int content = rowCount() * STEP;
			final int thumbH = Math.max(24, vpH() * vpH() / content);
			final int thumbY = oy + VP_TOP + (vpH() - thumbH) * scroll / ms;
			g.setColor(LofTheme.alpha(LofTheme.EMBER, 190));
			g.fillRoundRect(sbX, thumbY, SCROLLBAR_W, thumbH, SCROLLBAR_W, SCROLLBAR_W);
		}

		// footer hint
		g.setFont(FontManager.getRunescapeSmallFont());
		final String hint = "Each vote opens in your browser — rewards are delivered automatically.";
		final FontMetrics hintFm = g.getFontMetrics();
		LofTheme.shadowText(g, hint, ox + (WIN_W - hintFm.stringWidth(hint)) / 2, oy + winH - 11, LofTheme.TEXT_DIM);

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(WIN_W, winH);
	}

	/** 187 → "3h 7m", 45 → "45m". */
	private static String fmtCooldown(int mins)
	{
		final int h = mins / 60, m = mins % 60;
		return h > 0 ? h + "h " + m + "m" : m + "m";
	}

	private Point mousePoint()
	{
		final net.runelite.api.Point m = client.getMouseCanvasPosition();
		return m == null ? new Point(-1, -1) : new Point(m.getX(), m.getY());
	}

	private static int clamp(int v, int lo, int hi)
	{
		return Math.max(lo, Math.min(hi, v));
	}
}
