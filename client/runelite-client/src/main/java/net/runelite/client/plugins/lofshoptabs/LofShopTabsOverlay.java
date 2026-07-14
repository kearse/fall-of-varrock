/*
 * Fall of Varrock — custom shop window (renderer + hit-testing).
 *
 * Draws the whole shop client-side in the brand theme (LofTheme): a header with the shop name +
 * the player's balance, an item-icon tab rail down the left for multi-store vendors, an 8-column
 * item grid with prices, and a 1/5/10/50 buy-quantity selector. Viewport-anchored so it clears the
 * inventory column (selling stays native: right-click an inventory item). Item sprites come from
 * ItemManager; everything is null-guarded so it renders empty rather than throwing.
 *
 * Hit-test codes tell the mouse listener what was clicked: close, a tab, a qty button, or an item.
 */
package net.runelite.client.plugins.lofshoptabs;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
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

class LofShopTabsOverlay extends Overlay
{
	// hit-test result codes
	static final int OUTSIDE = -1;
	static final int INSIDE = 0;
	static final int CLOSE = 1;
	static final int TAB_BASE = 100;   // + tab index
	static final int QTY_BASE = 500;   // + qty-button index (0..3)
	static final int ITEM_BASE = 1000; // + grid index

	private static final int[] QTY_OPTS = {1, 5, 10, 50};

	// Window geometry.
	private static final int WIN_W = 470;
	private static final int WIN_ARC = 14;
	private static final int TITLE_H = 34;
	private static final int RAIL_X = 8;
	private static final int RAIL_W = 44;
	private static final int RAIL_BTN_H = 34;
	private static final int RAIL_GAP = 4;
	private static final int GRID_X = RAIL_X + RAIL_W + 8;   // 60
	private static final int COLS = 8;
	private static final int CELL_GAP = 4;
	private static final int CELL_W = (WIN_W - GRID_X - 10 - (COLS - 1) * CELL_GAP) / COLS; // ~46
	private static final int CELL_H = 50;
	private static final int GRID_TOP = TITLE_H + 24;        // below the sub/qty row

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
			&& plugin.isShopOpen()
			&& !plugin.getItems().isEmpty();
	}

	private int rowCount()
	{
		final int n = plugin.getItems().size();
		return Math.max(5, (n + COLS - 1) / COLS);
	}

	private int winH()
	{
		return GRID_TOP + rowCount() * (CELL_H + CELL_GAP) + 10;
	}

	private int originX()
	{
		final int canvasW = client.getCanvasWidth();
		final int viewportW = client.isResized() ? canvasW : Math.min(canvasW, 512);
		return Math.max(0, Math.min((canvasW - WIN_W) / 2, (viewportW - WIN_W) / 2));
	}

	private int originY()
	{
		return Math.max(0, (client.getCanvasHeight() - winH()) / 2);
	}

	private Rectangle closeRect(int ox, int oy)
	{
		return new Rectangle(ox + WIN_W - 28, oy + 8, 18, 18);
	}

	private Rectangle tabRect(int ox, int oy, int t)
	{
		return new Rectangle(ox + RAIL_X, oy + TITLE_H + 8 + t * (RAIL_BTN_H + RAIL_GAP), RAIL_W, RAIL_BTN_H);
	}

	private Rectangle qtyRect(int ox, int oy, int i)
	{
		final int w = 22;
		final int gap = 4;
		final int right = ox + WIN_W - 10;
		final int x = right - (QTY_OPTS.length - i) * (w + gap) + gap;
		return new Rectangle(x, oy + TITLE_H + 3, w, 16);
	}

	private Rectangle cellRect(int ox, int oy, int i)
	{
		final int col = i % COLS;
		final int row = i / COLS;
		return new Rectangle(ox + GRID_X + col * (CELL_W + CELL_GAP), oy + GRID_TOP + row * (CELL_H + CELL_GAP), CELL_W, CELL_H);
	}

	int hitTest(Point p)
	{
		if (!isShowing())
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
		for (int i = 0; i < QTY_OPTS.length; i++)
		{
			if (qtyRect(ox, oy, i).contains(p))
			{
				return QTY_BASE + i;
			}
		}
		final List<LofShopTabsPlugin.Tab> tabs = plugin.getTabs();
		for (int t = 0; t < tabs.size(); t++)
		{
			if (tabRect(ox, oy, t).contains(p))
			{
				return TAB_BASE + t;
			}
		}
		final int n = plugin.getItems().size();
		for (int i = 0; i < n; i++)
		{
			if (cellRect(ox, oy, i).contains(p))
			{
				return ITEM_BASE + i;
			}
		}
		return INSIDE;
	}

	int qtyValue(int i)
	{
		return QTY_OPTS[i];
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!isShowing())
		{
			return null;
		}

		final Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// Absolute canvas coordinates — undo any renderer translate (same as lofstake/lofteleports).
		final Rectangle selfBounds = getBounds();
		g.translate(-selfBounds.x, -selfBounds.y);

		final int ox = originX(), oy = originY();
		final int h = winH();
		final Point mouse = mousePoint();

		LofTheme.panel(g, ox, oy, WIN_W, h, WIN_ARC);

		// header
		final Shape clip = g.getClip();
		g.setClip(ox, oy, WIN_W, TITLE_H);
		g.setColor(LofTheme.HEADER);
		g.fillRoundRect(ox, oy, WIN_W, TITLE_H + WIN_ARC, WIN_ARC, WIN_ARC);
		g.setClip(clip);
		LofTheme.emberUnderline(g, ox + 1, oy + TITLE_H - 2, WIN_W - 2);

		final BufferedImage logo = LofTheme.logo();
		int titleX = ox + 12;
		if (logo != null)
		{
			g.drawImage(logo, ox + 10, oy + 5, 24, 24, null);
			titleX = ox + 40;
		}
		g.setFont(FontManager.getRunescapeBoldFont());
		LofTheme.shadowText(g, plugin.getShopName(), titleX, oy + 22, LofTheme.GOLD);

		// balance (right of header)
		g.setFont(FontManager.getRunescapeSmallFont());
		final String bal = fmt(plugin.getBalance()) + " " + plugin.getCurrencyLabel();
		final int balW = g.getFontMetrics().stringWidth(bal);
		LofTheme.shadowText(g, bal, ox + WIN_W - 34 - balW, oy + 21, LofTheme.TEXT_DIM);

		// close button
		final Rectangle cr = closeRect(ox, oy);
		final boolean closeHov = cr.contains(mouse);
		g.setColor(closeHov ? LofTheme.EMBER : new Color(255, 255, 255, 18));
		g.fillRoundRect(cr.x, cr.y, cr.width, cr.height, 5, 5);
		g.setColor(closeHov ? LofTheme.TEXT : LofTheme.TEXT_DIM);
		final Stroke oldStroke = g.getStroke();
		g.setStroke(new BasicStroke(1.5f));
		g.drawLine(cr.x + 5, cr.y + 5, cr.x + cr.width - 6, cr.y + cr.height - 6);
		g.drawLine(cr.x + cr.width - 6, cr.y + 5, cr.x + 5, cr.y + cr.height - 6);
		g.setStroke(oldStroke);

		// qty selector
		g.setFont(FontManager.getRunescapeSmallFont());
		final int amount = plugin.getBuyAmount();
		final String buyLbl = "Buy";
		LofTheme.shadowText(g, buyLbl, qtyRect(ox, oy, 0).x - 4 - g.getFontMetrics().stringWidth(buyLbl), oy + TITLE_H + 15, LofTheme.TEXT_DIM);
		for (int i = 0; i < QTY_OPTS.length; i++)
		{
			final Rectangle qr = qtyRect(ox, oy, i);
			final boolean sel = QTY_OPTS[i] == amount;
			final boolean hov = qr.contains(mouse);
			g.setColor(sel ? LofTheme.alpha(LofTheme.EMBER, 60) : hov ? LofTheme.ROW_HOVER : LofTheme.ROW);
			g.fillRoundRect(qr.x, qr.y, qr.width, qr.height, 5, 5);
			g.setColor(LofTheme.alpha(sel ? LofTheme.GOLD : LofTheme.EMBER_DARK, sel ? 220 : 120));
			g.drawRoundRect(qr.x, qr.y, qr.width - 1, qr.height - 1, 5, 5);
			final String s = String.valueOf(QTY_OPTS[i]);
			final int tw = g.getFontMetrics().stringWidth(s);
			LofTheme.shadowText(g, s, qr.x + (qr.width - tw) / 2, qr.y + 12, sel ? LofTheme.GOLD : LofTheme.TEXT_DIM);
		}

		// tab rail
		final List<LofShopTabsPlugin.Tab> tabs = plugin.getTabs();
		final int selectedTab = plugin.getSelectedTab();
		for (int t = 0; t < tabs.size(); t++)
		{
			final Rectangle tr = tabRect(ox, oy, t);
			final boolean sel = t == selectedTab;
			final boolean hov = tr.contains(mouse);
			g.setColor(sel ? LofTheme.alpha(LofTheme.EMBER, 52) : hov ? LofTheme.ROW_HOVER : LofTheme.ROW);
			g.fillRoundRect(tr.x, tr.y, tr.width, tr.height, 7, 7);
			g.setColor(LofTheme.alpha(sel ? LofTheme.GOLD : LofTheme.EMBER_DARK, sel ? 220 : hov ? 160 : 90));
			g.setStroke(new BasicStroke(sel ? 1.5f : 1.0f));
			g.drawRoundRect(tr.x, tr.y, tr.width - 1, tr.height - 1, 7, 7);
			g.setStroke(oldStroke);
			if (sel)
			{
				g.setColor(LofTheme.EMBER);
				g.fillRoundRect(tr.x + 2, tr.y + 6, 3, tr.height - 12, 2, 2);
			}
			final LofShopTabsPlugin.Tab tab = tabs.get(t);
			final BufferedImage icon = tab.itemId > 0 ? itemManager.getImage(tab.itemId) : null;
			if (icon != null)
			{
				g.drawImage(icon, tr.x + (tr.width - 32) / 2, tr.y + (tr.height - 28) / 2 - 2, 32, 28, null);
			}
			else
			{
				final String s = tab.label.isEmpty() ? "?" : tab.label.substring(0, 1);
				LofTheme.shadowText(g, s, tr.x + tr.width / 2 - 3, tr.y + tr.height / 2 + 4, sel ? LofTheme.GOLD : LofTheme.TEXT_DIM);
			}
		}

		// item grid
		final List<LofShopTabsPlugin.Item> items = plugin.getItems();
		for (int i = 0; i < items.size(); i++)
		{
			final Rectangle rc = cellRect(ox, oy, i);
			final boolean hov = rc.contains(mouse);
			g.setColor(hov ? LofTheme.ROW_HOVER : LofTheme.ROW);
			g.fillRoundRect(rc.x, rc.y, rc.width, rc.height, 7, 7);
			g.setColor(LofTheme.alpha(hov ? LofTheme.EMBER : LofTheme.EMBER_DARK, hov ? 170 : 45));
			g.drawRoundRect(rc.x, rc.y, rc.width - 1, rc.height - 1, 7, 7);

			final LofShopTabsPlugin.Item it = items.get(i);
			final BufferedImage img = it.itemId > 0 ? itemManager.getImage(it.itemId, Math.max(1, it.qty), it.qty > 1) : null;
			if (img != null)
			{
				g.drawImage(img, rc.x + (rc.width - 36) / 2, rc.y + 2, 36, 32, null);
			}
			// price under the sprite
			g.setFont(FontManager.getRunescapeSmallFont());
			final String price = fmt(it.price);
			final int pw = g.getFontMetrics().stringWidth(price);
			LofTheme.shadowText(g, price, rc.x + (rc.width - pw) / 2, rc.y + CELL_H - 4, LofTheme.GOLD);
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(WIN_W, h);
	}

	/** Compact price: 1.2k / 3.4m / 1.1b, else the raw number with separators. */
	private static String fmt(int v)
	{
		if (v >= 10_000_000)
		{
			return (v / 1_000_000) + "m";
		}
		if (v >= 1_000_000)
		{
			return String.format("%.1fm", v / 1_000_000.0);
		}
		if (v >= 100_000)
		{
			return (v / 1000) + "k";
		}
		if (v >= 1000)
		{
			return String.format("%.1fk", v / 1000.0);
		}
		return String.valueOf(v);
	}

	private Point mousePoint()
	{
		final net.runelite.api.Point m = client.getMouseCanvasPosition();
		return m == null ? new Point(-1, -1) : new Point(m.getX(), m.getY());
	}
}
