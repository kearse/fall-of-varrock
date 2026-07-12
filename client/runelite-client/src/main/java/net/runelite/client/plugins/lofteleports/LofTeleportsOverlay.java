/*
 * Fall of Varrock — teleport portal overlay (renderer + hit-testing).
 *
 * A tabbed teleport window drawn client-side (cache interfaces don't render on our client).
 * Opened by the server pulsing varp 4607 (portal click); rows send "::tp <cat> <row>" back.
 * Each destination is a "card" (icon + name + price + danger); the list scrolls. Geometry lives
 * here so the mouse listener + wheel + renderer all agree.
 *
 * Styled with the Fall of Varrock brand theme (LofTheme): shield logo in the header, ember
 * accents, antique-gold headings on a warm near-black panel — matching the login screen.
 */
package net.runelite.client.plugins.lofteleports;

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
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.loftheme.LofTheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class LofTeleportsOverlay extends Overlay
{
	// hit-test result codes
	static final int OUTSIDE = -1;
	static final int INSIDE = 0;
	static final int CLOSE = 1;
	static final int TAB_BASE = 100;
	static final int ROW_BASE = 1000;

	// Design-system standard modal (480x400 — docs/overlay-design-system.md); viewport-anchored so
	// the window clears the inventory/tab column in fixed mode (it used to be 560 wide, canvas-
	// centred, which draped over the side panel once modals moved to ALWAYS_ON_TOP).
	private static final int WIN_W = 480;
	private static final int WIN_H = 400;
	private static final int WIN_ARC = 14;
	private static final int TITLE_H = 38;
	private static final int TAB_X = 10;
	private static final int TAB_W = 122;
	private static final int TAB_Y0 = TITLE_H + 14;
	private static final int TAB_H = 30;
	private static final int TAB_GAP = 34;
	private static final int LIST_X = 144;
	private static final int LIST_W = WIN_W - LIST_X - 10;     // 326
	private static final int SCROLLBAR_W = 5;
	private static final int VP_TOP = TITLE_H + 32;            // 70, below the column headers
	private static final int VP_BOTTOM = WIN_H - 12;
	private static final int VP_H = VP_BOTTOM - VP_TOP;        // 318
	private static final int CARD_X = LIST_X + 2;
	private static final int CARD_W = LIST_W - SCROLLBAR_W - 8;
	private static final int CARD_H = 26;
	private static final int STEP = 30;

	private static final Color CLOSE_HOVER = LofTheme.EMBER;
	private static final Color FREE_GREEN = new Color(110, 205, 110);

	private final Client client;
	private final LofTeleportsConfig config;
	private final ItemManager itemManager;

	private boolean visible;
	private int activeTab;
	private int scroll; // px

	@Inject
	private LofTeleportsOverlay(Client client, LofTeleportsConfig config, ItemManager itemManager)
	{
		this.client = client;
		this.config = config;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
	}

	boolean isVisible() { return visible; }

	void setVisible(boolean v) { visible = v; }

	int getActiveTab() { return activeTab; }

	void setActiveTab(int t)
	{
		if (t >= 0 && t < LofTeleportsData.CATEGORIES.size())
		{
			activeTab = t;
			scroll = 0;
		}
	}

	private int rowCount() { return LofTeleportsData.CATEGORIES.get(activeTab).dests.size(); }

	private int maxScroll() { return Math.max(0, rowCount() * STEP - VP_H); }

	private int originX()
	{
		// Centre within the game viewport, not the whole canvas (§6A): in fixed mode the world
		// view is the left ~512px, so this keeps the window off the inventory/tab column.
		final int canvasW = client.getCanvasWidth();
		final int viewportW = client.isResized() ? canvasW : Math.min(canvasW, 512);
		return Math.max(0, Math.min((canvasW - WIN_W) / 2, (viewportW - WIN_W) / 2));
	}

	private int originY() { return Math.max(0, (client.getCanvasHeight() - WIN_H) / 2); }

	/** Wheel scroll if the cursor is over the list; returns true if consumed. */
	boolean handleScroll(Point p, int rotation)
	{
		if (!visible) return false;
		final int ox = originX(), oy = originY();
		if (!new Rectangle(ox + LIST_X, oy + VP_TOP, LIST_W, VP_H).contains(p)) return false;
		scroll = clamp(scroll + rotation * STEP, 0, maxScroll());
		return true;
	}

	int hitTest(Point p)
	{
		if (!visible) return OUTSIDE;
		final int ox = originX(), oy = originY();
		if (!new Rectangle(ox, oy, WIN_W, WIN_H).contains(p)) return OUTSIDE;
		if (closeRect(ox, oy).contains(p)) return CLOSE;

		for (int t = 0; t < LofTeleportsData.CATEGORIES.size(); t++)
		{
			if (tabRect(ox, oy, t).contains(p)) return TAB_BASE + t;
		}

		if (new Rectangle(ox + LIST_X, oy + VP_TOP, LIST_W, VP_H).contains(p))
		{
			final int rel = p.y - (oy + VP_TOP) + scroll;
			final int i = rel / STEP;
			if (i >= 0 && i < rowCount() && (rel - i * STEP) <= CARD_H) return ROW_BASE + i;
		}
		return INSIDE;
	}

	private Rectangle closeRect(int ox, int oy) { return new Rectangle(ox + WIN_W - 30, oy + 9, 20, 20); }
	private Rectangle tabRect(int ox, int oy, int t) { return new Rectangle(ox + TAB_X, oy + TAB_Y0 + t * TAB_GAP, TAB_W, TAB_H); }

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!visible || !config.enabled() || client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}

		final Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// This overlay draws at ABSOLUTE canvas coordinates. Undo any translate the renderer
		// applied (e.g. a saved drag offset) so absolute means absolute.
		final java.awt.Rectangle selfBounds = getBounds();
		g.translate(-selfBounds.x, -selfBounds.y);


		final int ox = originX(), oy = originY();
		final Point mouse = mousePoint();

		LofTheme.panel(g, ox, oy, WIN_W, WIN_H, WIN_ARC);

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
		LofTheme.shadowText(g, "Teleport Portal", titleX, oy + 25, LofTheme.GOLD);
		g.setFont(FontManager.getRunescapeSmallFont());
		final List<LofTeleportsData.Category> cats = LofTeleportsData.CATEGORIES;
		final String sub = cats.get(activeTab).name + "  •  " + rowCount() + " destinations";
		LofTheme.shadowText(g, sub, ox + WIN_W - 44 - g.getFontMetrics().stringWidth(sub), oy + 24, LofTheme.TEXT_DIM);

		// close button
		final Rectangle cr = closeRect(ox, oy);
		final boolean closeHov = cr.contains(mouse);
		g.setColor(closeHov ? CLOSE_HOVER : new Color(255, 255, 255, 18));
		g.fillRoundRect(cr.x, cr.y, cr.width, cr.height, 6, 6);
		g.setColor(closeHov ? LofTheme.TEXT : LofTheme.TEXT_DIM);
		final Stroke oldStroke = g.getStroke();
		g.setStroke(new BasicStroke(1.6f));
		g.drawLine(cr.x + 6, cr.y + 6, cr.x + cr.width - 7, cr.y + cr.height - 7);
		g.drawLine(cr.x + cr.width - 7, cr.y + 6, cr.x + 6, cr.y + cr.height - 7);
		g.setStroke(oldStroke);

		// tab rail
		g.setColor(LofTheme.ROW);
		g.fillRoundRect(ox + TAB_X - 4, oy + TITLE_H + 8, TAB_W + 8, WIN_H - TITLE_H - 20, 10, 10);
		g.setFont(FontManager.getRunescapeFont());
		for (int t = 0; t < cats.size(); t++)
		{
			final Rectangle tr = tabRect(ox, oy, t);
			final boolean sel = t == activeTab;
			final boolean hov = tr.contains(mouse);
			if (sel)
			{
				g.setColor(LofTheme.alpha(LofTheme.EMBER, 44));
				g.fillRoundRect(tr.x, tr.y, tr.width, tr.height, 8, 8);
				g.setColor(LofTheme.EMBER);
				g.fillRoundRect(tr.x, tr.y + 6, 3, tr.height - 12, 2, 2);
			}
			else if (hov)
			{
				g.setColor(LofTheme.ROW_HOVER);
				g.fillRoundRect(tr.x, tr.y, tr.width, tr.height, 8, 8);
			}
			LofTheme.shadowText(g, cats.get(t).name, tr.x + 12, tr.y + 20, sel ? LofTheme.GOLD : (hov ? LofTheme.TEXT : LofTheme.TEXT_DIM));
		}

		// column headers
		g.setFont(FontManager.getRunescapeSmallFont());
		LofTheme.shadowText(g, "DESTINATION", ox + CARD_X + 30, oy + TITLE_H + 22, LofTheme.GOLD_DIM);
		LofTheme.shadowText(g, "DANGER", ox + CARD_X + CARD_W - 12 - g.getFontMetrics().stringWidth("DANGER"), oy + TITLE_H + 22, LofTheme.GOLD_DIM);

		// cards (clipped to viewport)
		final Shape oldClip = g.getClip();
		g.setClip(ox + LIST_X, oy + VP_TOP, LIST_W, VP_H);
		final FontMetrics fm = g.getFontMetrics();
		final List<LofTeleportsData.Dest> dests = cats.get(activeTab).dests;
		for (int i = 0; i < dests.size(); i++)
		{
			final int cy = oy + VP_TOP + i * STEP - scroll;
			if (cy + CARD_H < oy + VP_TOP || cy > oy + VP_BOTTOM) continue; // off-screen
			final LofTeleportsData.Dest d = dests.get(i);
			final int cx = ox + CARD_X;
			final boolean hov = d.built
				&& mouse.x >= cx && mouse.x <= cx + CARD_W && mouse.y >= cy && mouse.y <= cy + CARD_H
				&& mouse.y >= oy + VP_TOP && mouse.y <= oy + VP_BOTTOM;

			// card body
			g.setColor(hov ? LofTheme.ROW_HOVER : LofTheme.ROW);
			g.fillRoundRect(cx, cy, CARD_W, CARD_H, 8, 8);
			if (hov)
			{
				g.setColor(LofTheme.alpha(LofTheme.EMBER, 150));
				g.drawRoundRect(cx, cy, CARD_W, CARD_H, 8, 8);
			}

			// real item icon (falls back to nothing; the pill still identifies the row)
			drawIcon(g, cx + 6, cy + 4, d.icon);

			final int ty = cy + CARD_H / 2 + 4;
			LofTheme.shadowText(g, d.name, cx + 32, ty, d.built ? LofTheme.TEXT : LofTheme.TEXT_DIM);
			if (d.built)
			{
				// FREE sits between the longest names and the danger pill (narrower 480 frame).
				LofTheme.shadowText(g, "FREE", cx + CARD_W - 120, ty, FREE_GREEN);
			}
			LofTheme.pill(g, fm, d.danger, cx + CARD_W - 10, ty, d.built ? d.colour : LofTheme.TEXT_DIM);
		}
		g.setClip(oldClip);

		// scrollbar
		final int ms = maxScroll();
		if (ms > 0)
		{
			final int sbX = ox + LIST_X + LIST_W - SCROLLBAR_W;
			g.setColor(new Color(255, 255, 255, 14));
			g.fillRoundRect(sbX, oy + VP_TOP, SCROLLBAR_W, VP_H, SCROLLBAR_W, SCROLLBAR_W);
			final int content = rowCount() * STEP;
			final int thumbH = Math.max(24, VP_H * VP_H / content);
			final int thumbY = oy + VP_TOP + (VP_H - thumbH) * scroll / ms;
			g.setColor(LofTheme.alpha(LofTheme.EMBER, 190));
			g.fillRoundRect(sbX, thumbY, SCROLLBAR_W, thumbH, SCROLLBAR_W, SCROLLBAR_W);
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(WIN_W, WIN_H);
	}

	private void drawIcon(Graphics2D g, int x, int y, int itemId)
	{
		if (itemId > 0)
		{
			final BufferedImage img = itemManager.getImage(itemId);
			if (img != null)
			{
				g.drawImage(img, x, y, 20, 18, null);
			}
		}
	}

	private Point mousePoint()
	{
		final net.runelite.api.Point m = client.getMouseCanvasPosition();
		return m == null ? new Point(-1, -1) : new Point(m.getX(), m.getY());
	}

	private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
