/*
 * Fall of Varrock — storefront tab strip (renderer + hit-testing).
 *
 * A bare row of item-sprite buttons floating just ABOVE the open shop window (group 300) when
 * the current vendor owns several stores — real RuneScape item icons (a longsword for weapons,
 * a platebody for armour, ...), no backing panel, no shadow. The store name only appears as a
 * gold tooltip when hovering a button. Anchored to the live bounds of the shop's stock grid
 * (300.16 — the one child the server always drives), so it tracks the window in fixed and
 * resizable modes alike.
 *
 * Only the buttons themselves are hit-tested — every other click passes through untouched,
 * keeping the native shop window (buy/sell/close) fully clickable. Null-guarded throughout:
 * renders nothing rather than throwing.
 */
package net.runelite.client.plugins.lofshoptabs;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.loftheme.LofTheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class LofShopTabsOverlay extends Overlay
{
	static final int OUTSIDE = -1;

	// Item sprites are 36x32; the button hugs the sprite.
	private static final int BTN_W = 36;
	private static final int BTN_H = 32;
	private static final int GAP = 5;

	// The shop window's top edge sits roughly this far above the stock grid (title bar).
	private static final int TITLE_OFFSET = 32;
	private static final int GAP_ABOVE_WINDOW = 4;

	private final Client client;
	private final LofShopTabsPlugin plugin;
	private final ItemManager itemManager;

	@Inject
	private LofShopTabsOverlay(Client client, LofShopTabsPlugin plugin, ItemManager itemManager)
	{
		this.client = client;
		this.plugin = plugin;
		this.itemManager = itemManager;
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

	/** The shop stock grid's live canvas bounds — the anchor for the button row. */
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

	/** Button rectangles (canvas space), left-aligned in a single row above the window. */
	private List<Rectangle> layout(Rectangle anchor, int count)
	{
		final int windowTop = anchor.y - TITLE_OFFSET;
		final int y = Math.max(2, windowTop - GAP_ABOVE_WINDOW - BTN_H);
		final List<Rectangle> rects = new ArrayList<>(count);
		for (int i = 0; i < count; i++)
		{
			rects.add(new Rectangle(anchor.x + i * (BTN_W + GAP), y, BTN_W, BTN_H));
		}
		return rects;
	}

	/** Index of the button under [p], or {@link #OUTSIDE}. Only button rects consume clicks. */
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
		final List<Rectangle> rects = layout(anchor, plugin.getTabs().size());
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

		final List<LofShopTabsPlugin.Tab> tabs = plugin.getTabs();
		final List<Rectangle> rects = layout(anchor, tabs.size());
		final Point mouse = mousePoint();
		final int selected = plugin.getSelected();
		int hovered = -1;

		final Stroke oldStroke = g.getStroke();
		for (int i = 0; i < rects.size(); i++)
		{
			final Rectangle rc = rects.get(i);
			final boolean sel = i == selected;
			final boolean hov = rc.contains(mouse);
			if (hov)
			{
				hovered = i;
			}

			// Bare button: translucent dark fill, accent border. No panel, no shadow.
			g.setColor(sel ? LofTheme.alpha(LofTheme.EMBER, 52) : LofTheme.alpha(LofTheme.PANEL_OPAQUE, hov ? 190 : 140));
			g.fillRoundRect(rc.x, rc.y, rc.width, rc.height, 6, 6);
			g.setStroke(new BasicStroke(sel ? 1.6f : 1.0f));
			g.setColor(sel ? LofTheme.alpha(LofTheme.GOLD, 235)
				: LofTheme.alpha(hov ? LofTheme.EMBER : LofTheme.EMBER_DARK, hov ? 200 : 130));
			g.drawRoundRect(rc.x, rc.y, rc.width - 1, rc.height - 1, 6, 6);
			if (sel)
			{
				LofTheme.emberUnderline(g, rc.x + 2, rc.y + rc.height - 2, rc.width - 4);
			}

			final LofShopTabsPlugin.Tab tab = tabs.get(i);
			final BufferedImage img = tab.itemId > 0 ? itemManager.getImage(tab.itemId) : null;
			if (img != null)
			{
				// Item sprites are 36x32 with their own padding — draw 1:1, centred.
				g.drawImage(img, rc.x + (rc.width - img.getWidth()) / 2, rc.y + (rc.height - img.getHeight()) / 2, null);
			}
			else
			{
				// No icon resolved — fall back to the first letters of the label.
				g.setFont(FontManager.getRunescapeSmallFont());
				final String s = tab.label.length() > 4 ? tab.label.substring(0, 4) : tab.label;
				final int tw = g.getFontMetrics().stringWidth(s);
				LofTheme.shadowText(g, s, rc.x + (rc.width - tw) / 2, rc.y + rc.height / 2 + 4,
					sel ? LofTheme.GOLD : LofTheme.TEXT_DIM);
			}
		}
		g.setStroke(oldStroke);

		// Hover tooltip: the store name in gold, just below the hovered button.
		if (hovered >= 0)
		{
			final Rectangle rc = rects.get(hovered);
			g.setFont(FontManager.getRunescapeSmallFont());
			final String label = tabs.get(hovered).label;
			final int tw = g.getFontMetrics().stringWidth(label);
			final int tx = Math.max(2, Math.min(rc.x, client.getCanvasWidth() - tw - 4));
			LofTheme.shadowText(g, label, tx, rc.y + rc.height + 13, LofTheme.GOLD);
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		final Rectangle last = rects.get(rects.size() - 1);
		return new Dimension(last.x + last.width - rects.get(0).x, BTN_H);
	}

	private Point mousePoint()
	{
		final net.runelite.api.Point m = client.getMouseCanvasPosition();
		return m == null ? new Point(-1, -1) : new Point(m.getX(), m.getY());
	}
}
