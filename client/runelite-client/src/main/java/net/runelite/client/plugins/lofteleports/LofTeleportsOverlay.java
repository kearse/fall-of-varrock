/*
 * Kingdom of Lumbridge — teleport portal overlay (renderer + hit-testing).
 *
 * A Roak/OSRS-style tabbed window drawn client-side (cache interfaces don't render on our client).
 * Opened by the server pulsing varp 4607 (portal click); rows send "::tp <cat> <row>" back.
 * Each destination is a "card" (icon + name + price + danger); the list scrolls. Geometry lives
 * here so the mouse listener + wheel + renderer all agree.
 */
package net.runelite.client.plugins.lofteleports;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.game.ItemManager;
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

	private static final int WIN_W = 520;
	private static final int WIN_H = 336;
	private static final int TITLE_H = 24;
	private static final int TAB_X = 8;
	private static final int TAB_W = 104;
	private static final int TAB_Y0 = 34;
	private static final int TAB_H = 30;
	private static final int TAB_GAP = 32;
	private static final int LIST_X = 120;
	private static final int LIST_W = WIN_W - LIST_X - 8;     // 392
	private static final int SCROLLBAR_W = 6;
	private static final int VP_TOP = 46;                      // from window top
	private static final int VP_BOTTOM = WIN_H - 8;
	private static final int VP_H = VP_BOTTOM - VP_TOP;        // 282
	private static final int CARD_X = LIST_X + 2;
	private static final int CARD_W = LIST_W - SCROLLBAR_W - 4;
	private static final int CARD_H = 22;
	private static final int STEP = 25;

	// OSRS stone palette
	private static final Color STONE = new Color(62, 54, 42);
	private static final Color STONE_DARK = new Color(34, 29, 20);
	private static final Color STONE_LIGHT = new Color(111, 97, 73);
	private static final Color TITLE_BG = new Color(42, 36, 25);
	private static final Color CARD_BG = new Color(58, 51, 38);
	private static final Color CARD_HOVER = new Color(92, 80, 55);
	private static final Color CARD_BORDER = new Color(26, 22, 15);
	private static final Color TAB_SEL = new Color(28, 24, 16);
	private static final Color TAB_HOVER = new Color(86, 75, 52);
	private static final Color ACCENT = new Color(255, 152, 31);
	private static final Color GOLD = new Color(255, 152, 31);
	private static final Color GREY = new Color(170, 165, 150);
	private static final Color WHITE = new Color(235, 230, 220);
	private static final Color CLOSE_RED = new Color(124, 38, 32);
	private static final Color FREE_GREEN = new Color(120, 200, 90);

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
		setLayer(OverlayLayer.ABOVE_WIDGETS);
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

	private int originX() { return Math.max(0, (client.getCanvasWidth() - WIN_W) / 2); }

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

	private Rectangle closeRect(int ox, int oy) { return new Rectangle(ox + WIN_W - 21, oy + 5, 16, 15); }
	private Rectangle tabRect(int ox, int oy, int t) { return new Rectangle(ox + TAB_X, oy + TAB_Y0 + t * TAB_GAP, TAB_W, TAB_H); }

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!visible || !config.enabled() || client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}

		final int ox = originX(), oy = originY();
		final Point mouse = mousePoint();

		bevel(g, ox, oy, WIN_W, WIN_H, STONE, STONE_LIGHT, STONE_DARK);

		// title
		g.setColor(TITLE_BG);
		g.fillRect(ox + 2, oy + 2, WIN_W - 4, TITLE_H - 2);
		g.setColor(STONE_DARK);
		g.drawLine(ox + 2, oy + TITLE_H, ox + WIN_W - 3, oy + TITLE_H);
		g.setFont(FontManager.getRunescapeBoldFont());
		centered(g, "Teleports", ox, oy + 17, WIN_W, GOLD);

		// close
		final Rectangle cr = closeRect(ox, oy);
		bevel(g, cr.x, cr.y, cr.width, cr.height, CLOSE_RED, CLOSE_RED.brighter(), STONE_DARK);
		g.setFont(FontManager.getRunescapeFont());
		centered(g, "X", cr.x, cr.y + 12, cr.width, WHITE);

		// tab column
		g.setColor(STONE_DARK);
		g.fillRect(ox + TAB_X - 3, oy + TITLE_H + 4, TAB_W + 6, WIN_H - TITLE_H - 10);
		g.setFont(FontManager.getRunescapeFont());
		final List<LofTeleportsData.Category> cats = LofTeleportsData.CATEGORIES;
		for (int t = 0; t < cats.size(); t++)
		{
			final Rectangle tr = tabRect(ox, oy, t);
			final boolean sel = t == activeTab;
			final boolean hov = tr.contains(mouse);
			if (sel)
			{
				g.setColor(TAB_SEL);
				g.fillRect(tr.x, tr.y, tr.width, tr.height);
				g.setColor(ACCENT);
				g.fillRect(tr.x, tr.y, 3, tr.height);
				g.setColor(STONE_DARK);
				g.drawRect(tr.x, tr.y, tr.width - 1, tr.height - 1);
			}
			else
			{
				bevel(g, tr.x, tr.y, tr.width, tr.height, hov ? TAB_HOVER : STONE, STONE_LIGHT, STONE_DARK);
			}
			shadow(g, cats.get(t).name, tr.x + 9, tr.y + 19, sel ? GOLD : (hov ? WHITE : GREY));
		}

		// list backing
		g.setColor(STONE_DARK);
		g.fillRect(ox + LIST_X, oy + TITLE_H + 4, LIST_W, WIN_H - TITLE_H - 10);

		// headers
		g.setFont(FontManager.getRunescapeBoldFont());
		shadow(g, "Location", ox + CARD_X + 26, oy + 40, GOLD);
		shadow(g, "Price", ox + CARD_X + CARD_W - 150, oy + 40, GOLD);
		shadow(g, "Danger", ox + CARD_X + CARD_W - 12 - g.getFontMetrics().stringWidth("Danger"), oy + 40, GOLD);

		// cards (clipped to viewport)
		final Shape oldClip = g.getClip();
		g.setClip(ox + LIST_X, oy + VP_TOP, LIST_W, VP_H);
		g.setFont(FontManager.getRunescapeSmallFont());
		final FontMetrics fm = g.getFontMetrics();
		final List<LofTeleportsData.Dest> dests = cats.get(activeTab).dests;
		for (int i = 0; i < dests.size(); i++)
		{
			final int cy = oy + VP_TOP + i * STEP - scroll;
			if (cy + CARD_H < oy + VP_TOP || cy > oy + VP_BOTTOM) continue; // off-screen
			final LofTeleportsData.Dest d = dests.get(i);
			final int cx = ox + CARD_X;
			final boolean hov = mouse.x >= cx && mouse.x <= cx + CARD_W && mouse.y >= cy && mouse.y <= cy + CARD_H
				&& mouse.y >= oy + VP_TOP && mouse.y <= oy + VP_BOTTOM;

			// card body
			g.setColor(hov ? CARD_HOVER : CARD_BG);
			g.fillRoundRect(cx, cy, CARD_W, CARD_H, 6, 6);
			g.setColor(CARD_BORDER);
			g.drawRoundRect(cx, cy, CARD_W, CARD_H, 6, 6);

			// real item icon (greyscale-ish fallback to a gem if the id has no image)
			drawIcon(g, cx + 4, cy + 2, d.icon, d.built ? d.colour : GREY);

			final int ty = cy + CARD_H / 2 + 4;
			shadow(g, d.name, cx + 26, ty, d.built ? WHITE : GREY);
			if (d.built)
			{
				shadow(g, "FREE", cx + CARD_W - 150, ty, FREE_GREEN);
			}
			shadow(g, d.danger, cx + CARD_W - 10 - fm.stringWidth(d.danger), ty, d.built ? d.colour : GREY);
		}
		g.setClip(oldClip);

		// scrollbar
		final int ms = maxScroll();
		if (ms > 0)
		{
			final int sbX = ox + LIST_X + LIST_W - SCROLLBAR_W;
			g.setColor(STONE_DARK);
			g.fillRect(sbX, oy + VP_TOP, SCROLLBAR_W, VP_H);
			final int content = rowCount() * STEP;
			final int thumbH = Math.max(20, VP_H * VP_H / content);
			final int thumbY = oy + VP_TOP + (VP_H - thumbH) * scroll / ms;
			bevel(g, sbX, thumbY, SCROLLBAR_W, thumbH, STONE_LIGHT, STONE_LIGHT.brighter(), STONE_DARK);
		}

		return new Dimension(WIN_W, WIN_H);
	}

	private void drawIcon(Graphics2D g, int x, int y, int itemId, Color fallback)
	{
		if (itemId > 0)
		{
			final BufferedImage img = itemManager.getImage(itemId);
			if (img != null)
			{
				g.drawImage(img, x, y, 20, 18, null);
				return;
			}
		}
		gem(g, x + 2, y, fallback);
	}

	private static void gem(Graphics2D g, int cx, int cy, Color col)
	{
		final Polygon p = new Polygon();
		p.addPoint(cx + 7, cy);
		p.addPoint(cx + 14, cy + 6);
		p.addPoint(cx + 7, cy + 12);
		p.addPoint(cx, cy + 6);
		g.setColor(col);
		g.fillPolygon(p);
		g.setColor(col.brighter());
		g.drawLine(cx + 7, cy, cx, cy + 6); // top-left facet highlight
		g.setColor(Color.BLACK);
		g.drawPolygon(p);
	}

	private Point mousePoint()
	{
		final net.runelite.api.Point m = client.getMouseCanvasPosition();
		return m == null ? new Point(-1, -1) : new Point(m.getX(), m.getY());
	}

	private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

	private static void bevel(Graphics2D g, int x, int y, int w, int h, Color base, Color light, Color dark)
	{
		g.setColor(base);
		g.fillRect(x, y, w, h);
		g.setColor(light);
		g.drawLine(x, y, x + w - 1, y);
		g.drawLine(x, y, x, y + h - 1);
		g.setColor(dark);
		g.drawLine(x, y + h - 1, x + w - 1, y + h - 1);
		g.drawLine(x + w - 1, y, x + w - 1, y + h - 1);
	}

	private static void centered(Graphics2D g, String text, int x, int baselineY, int w, Color col)
	{
		final int tx = x + (w - g.getFontMetrics().stringWidth(text)) / 2;
		shadow(g, text, tx, baselineY, col);
	}

	private static void shadow(Graphics2D g, String text, int x, int y, Color col)
	{
		g.setColor(Color.BLACK);
		g.drawString(text, x + 1, y + 1);
		g.setColor(col);
		g.drawString(text, x, y);
	}
}
