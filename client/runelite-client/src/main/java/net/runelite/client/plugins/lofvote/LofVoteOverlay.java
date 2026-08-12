/*
 * Fall of Varrock — vote window (renderer + hit-testing).
 *
 * A centred modal showing the toplists as a 3-column card grid: each card carries the
 * site's own vote-badge image (streamed logo URL, fetched by LofVoteLogoCache; a
 * lettered tile until it loads or when a site has none), the site name, and a green
 * Vote button. While a site's cooldown runs the card dims and the button becomes a
 * quiet countdown; once a vote is confirmed (or just cast from this window) the button
 * turns into a gold Claim that collects the reward with a click. Styled with the shared
 * LofTheme to match the commands panel and teleport portal. Clicking a card opens that
 * site's vote page in the browser and the window stays open; the X (or a click outside)
 * closes it. Geometry lives here so the mouse listener, wheel handler and renderer all
 * agree.
 *
 * Sized to fit the fixed-mode viewport (~512x334): 492 wide, and the grid viewport
 * scrolls if more sites arrive than two rows can hold.
 */
package net.runelite.client.plugins.lofvote;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
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
import net.runelite.client.plugins.loftheme.LofModal;
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

	// framed-modal standard (docs/overlay-design-system.md §6A), sized for fixed mode.
	private static final int WIN_W = 492;
	private static final int WIN_ARC = 14;
	private static final int TITLE_H = 38;
	private static final int PAD = 12;
	private static final int GAP = 10;
	private static final int COLS = 3;
	private static final int CARD_W = (WIN_W - 2 * PAD - (COLS - 1) * GAP) / COLS; // 149
	private static final int CARD_H = 104;
	private static final int ROW_STEP = CARD_H + GAP;
	private static final int LOGO_W = 88;
	private static final int LOGO_H = 40;
	private static final int VP_TOP = TITLE_H + PAD;
	private static final int FOOTER_H = 24;
	private static final int MAX_GRID_ROWS_VISIBLE = 2; // fixed-mode height budget
	private static final int SCROLLBAR_W = 5;

	private static final Color CLOSE_HOVER = LofTheme.EMBER;
	private static final Color GREEN = new Color(74, 164, 88);
	private static final Color GREEN_HI = new Color(94, 190, 108);
	private static final Color GOLD_BTN = new Color(186, 140, 44);
	private static final Color GOLD_BTN_HI = new Color(216, 168, 62);
	private static final Color GOLD_BTN_LO = new Color(142, 104, 32);

	private final Client client;
	private final LofVotePlugin plugin;

	private boolean visible;
	private int scroll; // px

	// Scaled placement published by render() on the client thread; the mouse thread hit-tests via this cache only.
	private volatile LofModal.Placement placement;

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

	private int siteCount()
	{
		return plugin.getSites().size();
	}

	private int gridRows()
	{
		return (siteCount() + COLS - 1) / COLS;
	}

	private int vpH()
	{
		return Math.min(gridRows(), MAX_GRID_ROWS_VISIBLE) * ROW_STEP - GAP;
	}

	private int winH()
	{
		return VP_TOP + vpH() + PAD + FOOTER_H;
	}

	private int maxScroll()
	{
		return Math.max(0, gridRows() * ROW_STEP - GAP - vpH());
	}

	// Placement single-sourced in LofModal (§6A) — this window has a content-driven height (winH()).
	private int originX() { return LofModal.originX(client, WIN_W); }

	private int originY() { return LofModal.originY(client, winH()); }

	/** Wheel scroll if the cursor is over the grid; returns true if consumed. */
	boolean handleScroll(Point canvas, int rotation)
	{
		if (!visible)
		{
			return false;
		}
		final LofModal.Placement place = placement;
		if (place == null)
		{
			return false;
		}
		final Point p = place.toLocal(canvas);
		final int ox = place.ox, oy = place.oy;
		if (!new Rectangle(ox + PAD, oy + VP_TOP, WIN_W - 2 * PAD, vpH()).contains(p))
		{
			return false;
		}
		scroll = clamp(scroll + rotation * ROW_STEP, 0, maxScroll());
		return true;
	}

	int hitTest(Point canvas)
	{
		if (!visible)
		{
			return OUTSIDE;
		}
		final LofModal.Placement place = placement;
		if (place == null)
		{
			return OUTSIDE;
		}
		final Point p = place.toLocal(canvas);
		final int ox = place.ox, oy = place.oy;
		if (!new Rectangle(ox, oy, WIN_W, winH()).contains(p))
		{
			return OUTSIDE;
		}
		if (closeRect(ox, oy).contains(p))
		{
			return CLOSE;
		}
		if (new Rectangle(ox + PAD, oy + VP_TOP, WIN_W - 2 * PAD, vpH()).contains(p))
		{
			final int relY = p.y - (oy + VP_TOP) + scroll;
			final int row = relY / ROW_STEP;
			if (relY - row * ROW_STEP <= CARD_H)
			{
				final int relX = p.x - (ox + PAD);
				final int col = relX / (CARD_W + GAP);
				if (col < COLS && relX - col * (CARD_W + GAP) <= CARD_W)
				{
					final int i = row * COLS + col;
					if (i >= 0 && i < siteCount())
					{
						return ROW_BASE + i;
					}
				}
			}
		}
		return INSIDE;
	}

	/** The site of the card at {@code index}, or null if out of range. */
	Site siteAt(int index)
	{
		final List<Site> sites = plugin.getSites();
		return index >= 0 && index < sites.size() ? sites.get(index) : null;
	}

	private Rectangle closeRect(int ox, int oy)
	{
		return new Rectangle(ox + WIN_W - 30, oy + 9, 20, 20);
	}

	private Rectangle cardRect(int ox, int oy, int i)
	{
		final int row = i / COLS, col = i % COLS;
		return new Rectangle(
			ox + PAD + col * (CARD_W + GAP),
			oy + VP_TOP + row * ROW_STEP - scroll,
			CARD_W, CARD_H);
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
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

		// This overlay draws at ABSOLUTE canvas coordinates. Undo any translate the renderer
		// applied so absolute means absolute.
		final Rectangle selfBounds = getBounds();
		g.translate(-selfBounds.x, -selfBounds.y);

		final int winH = winH();
		final LofModal.Placement place = LofModal.beginWindow(g, client, WIN_W, winH);
		placement = place;
		final int ox = place.ox, oy = place.oy;
		final Point mouse = place.toLocal(mousePoint());

		LofTheme.panel(g, ox, oy, WIN_W, winH, WIN_ARC);

		// header bar with ember accent underline
		final Shape headerClip = g.getClip();
		g.setClip(ox, oy, WIN_W, TITLE_H);
		g.setColor(LofTheme.HEADER);
		g.fillRoundRect(ox, oy, WIN_W, TITLE_H + WIN_ARC, WIN_ARC, WIN_ARC);
		g.setClip(headerClip);
		LofTheme.emberUnderline(g, ox + 1, oy + TITLE_H - 2, WIN_W - 2);

		// shield logo + title
		final BufferedImage shield = LofTheme.logo();
		int titleX = ox + 14;
		if (shield != null)
		{
			g.drawImage(shield, ox + 12, oy + 5, 28, 28, null);
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

		// site cards (clipped to the grid viewport)
		final Shape oldClip = g.getClip();
		g.setClip(ox + PAD, oy + VP_TOP, WIN_W - 2 * PAD, vpH());
		for (int i = 0; i < sites.size(); i++)
		{
			final Rectangle card = cardRect(ox, oy, i);
			if (card.y + CARD_H < oy + VP_TOP || card.y > oy + VP_TOP + vpH())
			{
				continue; // off-screen
			}
			drawCard(g, sites.get(i), card, card.contains(mouse)
				&& mouse.y >= oy + VP_TOP && mouse.y <= oy + VP_TOP + vpH());
		}
		g.setClip(oldClip);

		// scrollbar
		final int ms = maxScroll();
		if (ms > 0)
		{
			final int sbX = ox + WIN_W - PAD + 3;
			g.setColor(new Color(255, 255, 255, 14));
			g.fillRoundRect(sbX, oy + VP_TOP, SCROLLBAR_W, vpH(), SCROLLBAR_W, SCROLLBAR_W);
			final int content = gridRows() * ROW_STEP - GAP;
			final int thumbH = Math.max(24, vpH() * vpH() / content);
			final int thumbY = oy + VP_TOP + (vpH() - thumbH) * scroll / ms;
			g.setColor(LofTheme.alpha(LofTheme.EMBER, 190));
			g.fillRoundRect(sbX, thumbY, SCROLLBAR_W, thumbH, SCROLLBAR_W, SCROLLBAR_W);
		}

		// footer hint
		g.setFont(FontManager.getRunescapeSmallFont());
		final String hint = "Vote in your browser, then press the gold Claim button for your tickets.";
		final FontMetrics hintFm = g.getFontMetrics();
		LofTheme.shadowText(g, hint, ox + (WIN_W - hintFm.stringWidth(hint)) / 2, oy + winH - 9, LofTheme.TEXT_DIM);

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		LofModal.endWindow(g, place);
		return new Dimension(WIN_W, winH);
	}

	private void drawCard(Graphics2D g, Site s, Rectangle card, boolean hov)
	{
		final boolean claimable = s.claimable();
		final boolean onCooldown = !claimable && s.cooldownMins > 0;

		g.setColor(hov ? LofTheme.ROW_HOVER : LofTheme.ROW);
		g.fillRoundRect(card.x, card.y, card.width, card.height, 10, 10);
		if (hov)
		{
			g.setColor(LofTheme.alpha(LofTheme.EMBER, 150));
			g.drawRoundRect(card.x, card.y, card.width, card.height, 10, 10);
		}

		// badge image, letterboxed into LOGO_WxLOGO_H; lettered tile until it loads
		final int lx = card.x + (card.width - LOGO_W) / 2;
		final int ly = card.y + 10;
		final BufferedImage logo = plugin.logoFor(s);
		final java.awt.Composite oldComposite = g.getComposite();
		if (onCooldown)
		{
			g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.45f));
		}
		if (logo != null)
		{
			final double scale = Math.min((double) LOGO_W / logo.getWidth(), (double) LOGO_H / logo.getHeight());
			final int dw = Math.max(1, (int) Math.round(logo.getWidth() * scale));
			final int dh = Math.max(1, (int) Math.round(logo.getHeight() * scale));
			g.drawImage(logo, lx + (LOGO_W - dw) / 2, ly + (LOGO_H - dh) / 2, dw, dh, null);
		}
		else
		{
			g.setColor(new Color(255, 255, 255, 16));
			g.fillRoundRect(lx, ly, LOGO_W, LOGO_H, 6, 6);
			g.setColor(LofTheme.alpha(LofTheme.GOLD_DIM, 120));
			g.drawRoundRect(lx, ly, LOGO_W, LOGO_H, 6, 6);
			g.setFont(FontManager.getRunescapeSmallFont());
			final String tile = s.name.length() > 10 ? s.name.substring(0, 10) : s.name;
			final FontMetrics tfm = g.getFontMetrics();
			LofTheme.shadowText(g, tile, lx + (LOGO_W - tfm.stringWidth(tile)) / 2, ly + LOGO_H / 2 + 4,
				LofTheme.GOLD_DIM);
		}
		g.setComposite(oldComposite);

		// site name
		g.setFont(FontManager.getRunescapeFont());
		final FontMetrics fm = g.getFontMetrics();
		LofTheme.shadowText(g, ellipsise(fm, s.name, card.width - 16),
			card.x + (card.width - Math.min(fm.stringWidth(s.name), card.width - 16)) / 2,
			card.y + 68, onCooldown ? LofTheme.TEXT_DIM : LofTheme.TEXT);

		// button: gold "Claim" when a confirmed vote's reward waits, green "Vote" when
		// ready, quiet countdown while on cooldown
		final Rectangle btn = new Rectangle(card.x + 10, card.y + 76, card.width - 20, 20);
		if (claimable)
		{
			final java.awt.Paint oldPaint = g.getPaint();
			g.setPaint(new GradientPaint(btn.x, btn.y, hov ? GOLD_BTN_HI : GOLD_BTN, btn.x, btn.y + btn.height,
				hov ? GOLD_BTN : GOLD_BTN_LO));
			g.fillRoundRect(btn.x, btn.y, btn.width, btn.height, 7, 7);
			g.setPaint(oldPaint);
			g.setColor(new Color(255, 255, 255, 60));
			g.drawLine(btn.x + 4, btn.y + 1, btn.x + btn.width - 5, btn.y + 1);
			final String label = "Claim";
			LofTheme.shadowText(g, label, btn.x + (btn.width - fm.stringWidth(label)) / 2, btn.y + 15,
				Color.WHITE);
		}
		else if (onCooldown)
		{
			g.setColor(new Color(255, 255, 255, 14));
			g.fillRoundRect(btn.x, btn.y, btn.width, btn.height, 7, 7);
			g.setColor(new Color(255, 255, 255, 20));
			g.drawRoundRect(btn.x, btn.y, btn.width, btn.height, 7, 7);
			final String label = fmtCooldown(s.cooldownMins);
			LofTheme.shadowText(g, label, btn.x + (btn.width - fm.stringWidth(label)) / 2, btn.y + 15,
				LofTheme.TEXT_DIM);
		}
		else
		{
			final java.awt.Paint oldPaint = g.getPaint();
			g.setPaint(new GradientPaint(btn.x, btn.y, hov ? GREEN_HI : GREEN, btn.x, btn.y + btn.height,
				hov ? GREEN : new Color(56, 128, 68)));
			g.fillRoundRect(btn.x, btn.y, btn.width, btn.height, 7, 7);
			g.setPaint(oldPaint);
			g.setColor(new Color(255, 255, 255, 60));
			g.drawLine(btn.x + 4, btn.y + 1, btn.x + btn.width - 5, btn.y + 1);
			final String label = "Vote";
			LofTheme.shadowText(g, label, btn.x + (btn.width - fm.stringWidth(label)) / 2, btn.y + 15,
				Color.WHITE);
		}
	}

	/** 187 → "3h 7m", 45 → "45m". */
	private static String fmtCooldown(int mins)
	{
		final int h = mins / 60, m = mins % 60;
		return h > 0 ? h + "h " + m + "m" : m + "m";
	}

	/** Truncate text with a trailing "…" so it fits within maxW pixels. */
	private static String ellipsise(FontMetrics fm, String text, int maxW)
	{
		if (maxW <= 0)
		{
			return "";
		}
		if (fm.stringWidth(text) <= maxW)
		{
			return text;
		}
		final int ellW = fm.stringWidth("…");
		int end = text.length();
		while (end > 0 && fm.stringWidth(text.substring(0, end)) + ellW > maxW)
		{
			end--;
		}
		return text.substring(0, end).stripTrailing() + "…";
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
