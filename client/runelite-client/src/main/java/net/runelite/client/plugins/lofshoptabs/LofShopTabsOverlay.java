/*
 * Fall of Varrock — storefront tab strip (renderer + hit-testing).
 *
 * Draws a Roat-style row of tabs along the bottom of the open shop interface (group 300) when
 * the current vendor owns several stores. Anchored to the live bounds of the shop's stock grid
 * (300.16 — the one child the server always drives), so it tracks the window in fixed and
 * resizable modes alike. Long tab sets (Jatix's 8 shops) wrap onto a second row.
 *
 * Only the strip itself is hit-tested — every other click passes through untouched, keeping the
 * native shop window (buy/sell/close) fully clickable. Null-guarded throughout: renders nothing
 * rather than throwing.
 */
package net.runelite.client.plugins.lofshoptabs;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.loftheme.LofTheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class LofShopTabsOverlay extends Overlay
{
	static final int OUTSIDE = -1;

	// Design-system row metrics (docs/overlay-design-system.md): 22px rows.
	private static final int TAB_H = 22;
	private static final int TAB_PAD_X = 10; // text padding inside a tab
	private static final int TAB_GAP = 4;
	private static final int ROW_GAP = 3;
	private static final int STRIP_PAD = 5;  // strip inner padding
	private static final int GAP_BELOW_SHOP = 2;

	private final Client client;
	private final LofShopTabsPlugin plugin;

	@Inject
	private LofShopTabsOverlay(Client client, LofShopTabsPlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
	}

	boolean isShowing()
	{
		return plugin.isEnabled()
			&& client.getGameState() == GameState.LOGGED_IN
			&& plugin.getTabs().size() > 1
			&& plugin.isShopOpen();
	}

	/** The shop stock grid's live canvas bounds — the anchor for the strip. Null if unusable. */
	private Rectangle anchor()
	{
		final Widget stock = client.getWidget(LofShopTabsPlugin.SHOP_GROUP, LofShopTabsPlugin.STOCK_CHILD);
		if (stock == null || stock.isHidden())
		{
			return null;
		}
		final Rectangle b = stock.getBounds();
		return b == null || b.width <= 0 ? null : b;
	}

	/** Lay the tabs out into rows under the shop window; indices parallel the tab list. */
	private List<Rectangle> layout(Rectangle anchor, List<String> tabs)
	{
		final java.awt.FontMetrics fm =
			client.getCanvas().getFontMetrics(FontManager.getRunescapeSmallFont());
		final int maxW = Math.max(anchor.width, 200) - STRIP_PAD * 2;
		final List<Rectangle> rects = new ArrayList<>(tabs.size());
		int x = 0;
		int y = 0;
		for (String label : tabs)
		{
			final int w = fm.stringWidth(label) + TAB_PAD_X * 2;
			if (x > 0 && x + w > maxW)
			{
				x = 0;
				y += TAB_H + ROW_GAP;
			}
			rects.add(new Rectangle(x, y, w, TAB_H));
			x += w + TAB_GAP;
		}
		// Translate to canvas space: strip sits just below the stock grid, clamped on-canvas.
		final int stripH = y + TAB_H + STRIP_PAD * 2;
		final int ox = anchor.x + STRIP_PAD;
		int oy = anchor.y + anchor.height + GAP_BELOW_SHOP + STRIP_PAD;
		final int overflow = oy + stripH - STRIP_PAD - client.getCanvasHeight();
		if (overflow > 0)
		{
			oy -= overflow;
		}
		for (Rectangle r : rects)
		{
			r.translate(ox, oy);
		}
		return rects;
	}

	/** Index of the tab under [p], or {@link #OUTSIDE}. Only tab rects consume clicks. */
	int hitTest(Point p)
	{
		if (!isShowing())
		{
			return OUTSIDE;
		}
		final Rectangle anchor = anchor();
		if (anchor == null)
		{
			return OUTSIDE;
		}
		final List<Rectangle> rects = layout(anchor, plugin.getTabs());
		for (int i = 0; i < rects.size(); i++)
		{
			if (rects.get(i).contains(p))
			{
				return i;
			}
		}
		return OUTSIDE;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!isShowing())
		{
			return null;
		}
		final Rectangle anchor = anchor();
		if (anchor == null)
		{
			return null;
		}

		final Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// Absolute canvas coordinates — undo any renderer translate (same trick as lofstake).
		final Rectangle selfBounds = getBounds();
		g.translate(-selfBounds.x, -selfBounds.y);

		final List<String> tabs = plugin.getTabs();
		final List<Rectangle> rects = layout(anchor, tabs);
		if (rects.isEmpty())
		{
			return null;
		}

		// Strip backing: one panel behind all tab rows.
		Rectangle strip = new Rectangle(rects.get(0));
		for (Rectangle r : rects)
		{
			strip = strip.union(r);
		}
		strip.grow(STRIP_PAD, STRIP_PAD);
		LofTheme.panel(g, strip.x, strip.y, strip.width, strip.height, 8);

		final Point mouse = mousePoint();
		g.setFont(FontManager.getRunescapeSmallFont());
		final int selected = plugin.getSelected();
		for (int i = 0; i < rects.size(); i++)
		{
			final Rectangle rc = rects.get(i);
			final boolean sel = i == selected;
			final boolean hov = rc.contains(mouse);

			g.setColor(sel ? LofTheme.alpha(LofTheme.EMBER, 60) : hov ? LofTheme.ROW_HOVER : LofTheme.ROW);
			g.fillRoundRect(rc.x, rc.y, rc.width, rc.height, 6, 6);
			g.setColor(LofTheme.alpha(sel ? LofTheme.EMBER : LofTheme.EMBER_DARK, sel ? 220 : hov ? 160 : 90));
			g.drawRoundRect(rc.x, rc.y, rc.width - 1, rc.height - 1, 6, 6);
			if (sel)
			{
				LofTheme.emberUnderline(g, rc.x + 2, rc.y + rc.height - 3, rc.width - 4);
			}

			final String label = tabs.get(i);
			final int tw = g.getFontMetrics().stringWidth(label);
			LofTheme.shadowText(g, label, rc.x + (rc.width - tw) / 2, rc.y + rc.height / 2 + 4,
				sel ? LofTheme.GOLD : hov ? LofTheme.TEXT : LofTheme.TEXT_DIM);
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(strip.width, strip.height);
	}

	private Point mousePoint()
	{
		final net.runelite.api.Point m = client.getMouseCanvasPosition();
		return m == null ? new Point(-1, -1) : new Point(m.getX(), m.getY());
	}
}
