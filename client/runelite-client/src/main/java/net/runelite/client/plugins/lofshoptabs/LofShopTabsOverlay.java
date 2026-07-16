/*
 * Fall of Varrock — custom shop window (renderer + hit-testing).
 *
 * Draws the whole shop client-side in the brand theme (LofTheme), anchored EXACTLY over the native
 * shop interface (group 300) — same position, shape and size — so it replaces the OSRS shop look
 * without the native frame peeking out. The native interface stays open underneath as the modal
 * barrier and for selling (right-click an inventory item); we just cover it with an opaque panel.
 * Disabling this plugin restores the untouched native shop.
 *
 * Layout is computed from the native window's live bounds: a header (shop name + balance), an
 * item-icon tab rail down the left for multi-store vendors, an 8-column item grid with prices, and
 * a 1/5/10/50 buy-quantity selector. Item sprites come from ItemManager; all null-guarded.
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
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
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
	static final int ITEM_BASE = 1000; // + grid index

	private static final int COLS = 8;
	private static final int CELL_GAP = 3;
	private static final int TITLE_H = 30;
	private static final int RAIL_W = 40;
	private static final int RAIL_GAP = 4;
	private static final int PAD = 6;

	// Fallback size if the native bounds can't be read (should be rare).
	private static final int FALLBACK_W = 480;
	private static final int FALLBACK_H = 300;

	private final Client client;
	private final LofShopTabsPlugin plugin;
	private final ItemManager itemManager;

	// Computed on the client thread during render() and read by the mouse thread. Reading widgets
	// off the client thread returns null/stale, which silently killed every click — so the click
	// path (isShowing/hitTest) uses ONLY these cached values, never touches client.getWidget.
	private volatile boolean showing;
	private volatile Rectangle winRect;

	@Inject
	private LofShopTabsOverlay(Client client, LofShopTabsPlugin plugin, ItemManager itemManager)
	{
		this.client = client;
		this.plugin = plugin;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
	}

	/** Cached — safe to call from the mouse thread (see the field note). */
	boolean isShowing()
	{
		return showing && winRect != null;
	}

	/** The live client-thread check (widget access) — only call from render(). */
	private boolean computeShowing()
	{
		return plugin.isEnabled()
			&& client.getGameState() == GameState.LOGGED_IN
			&& plugin.isShopOpen()
			&& !plugin.getItems().isEmpty();
	}

	private static final int TOP_MARGIN = 4;

	/** The window fills the game viewport above the chatbox: chatbox width, from the top of the
	 *  view down to the top of the chatbox (the area the player circled). Anchored to the chatbox
	 *  frame so it tracks fixed/resizable layouts. */
	private Rectangle windowRect()
	{
		final Widget chat = client.getWidget(ComponentID.CHATBOX_FRAME);
		if (chat != null && !chat.isHidden())
		{
			final Rectangle c = chat.getBounds();
			if (c != null && c.width > 0 && c.y > 120)
			{
				return new Rectangle(c.x, TOP_MARGIN, c.width, c.y - TOP_MARGIN);
			}
		}
		// Fallback: viewport-centre at a fixed size.
		final int cw = client.getCanvasWidth(), ch = client.getCanvasHeight();
		final int vw = client.isResized() ? cw : Math.min(cw, 512);
		return new Rectangle(Math.max(0, (vw - FALLBACK_W) / 2), Math.max(0, (ch - FALLBACK_H) / 2), FALLBACK_W, FALLBACK_H);
	}

	private boolean hasRail()
	{
		return plugin.getTabs().size() > 1;
	}

	private int gridX(Rectangle w)
	{
		return w.x + PAD + (hasRail() ? RAIL_W + PAD : 0);
	}

	private int gridTop(Rectangle w)
	{
		return w.y + TITLE_H + 8;
	}

	private int cellW(Rectangle w)
	{
		final int gridW = w.x + w.width - PAD - gridX(w);
		return (gridW - (COLS - 1) * CELL_GAP) / COLS;
	}

	private int cellH(Rectangle w)
	{
		final int rows = rowCount();
		final int availH = w.y + w.height - PAD - gridTop(w);
		final int fit = (availH - (rows - 1) * CELL_GAP) / rows;
		return Math.max(30, Math.min(fit, cellW(w) + 14));
	}

	private int rowCount()
	{
		final int n = plugin.getItems().size();
		return Math.max(1, (n + COLS - 1) / COLS);
	}

	private Rectangle closeRect(Rectangle w)
	{
		return new Rectangle(w.x + w.width - 24, w.y + 7, 16, 16);
	}

	private Rectangle tabRect(Rectangle w, int t)
	{
		final int railH = w.height - TITLE_H - PAD * 2;
		final int btnH = Math.max(28, Math.min(38, (railH - (plugin.getTabs().size() - 1) * RAIL_GAP) / Math.max(1, plugin.getTabs().size())));
		return new Rectangle(w.x + PAD, w.y + TITLE_H + PAD + t * (btnH + RAIL_GAP), RAIL_W, btnH);
	}

	private Rectangle cellRect(Rectangle w, int i)
	{
		final int col = i % COLS, row = i / COLS;
		final int cw = cellW(w), ch = cellH(w);
		return new Rectangle(gridX(w) + col * (cw + CELL_GAP), gridTop(w) + row * (ch + CELL_GAP), cw, ch);
	}

	int hitTest(Point p)
	{
		final Rectangle w = winRect;
		if (!showing || w == null || !w.contains(p))
		{
			return OUTSIDE;
		}
		if (closeRect(w).contains(p))
		{
			return CLOSE;
		}
		if (hasRail())
		{
			final List<LofShopTabsPlugin.Tab> tabs = plugin.getTabs();
			for (int t = 0; t < tabs.size(); t++)
			{
				if (tabRect(w, t).contains(p))
				{
					return TAB_BASE + t;
				}
			}
		}
		final int n = plugin.getItems().size();
		for (int i = 0; i < n; i++)
		{
			if (cellRect(w, i).contains(p))
			{
				return ITEM_BASE + i;
			}
		}
		return INSIDE;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!computeShowing())
		{
			showing = false;
			return null;
		}
		final Rectangle w = windowRect();
		if (w == null)
		{
			showing = false;
			return null;
		}
		// Publish for the mouse thread BEFORE drawing.
		winRect = w;
		showing = true;

		final Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// Absolute canvas coordinates — undo any renderer translate (same as lofstake/lofteleports).
		final Rectangle selfBounds = getBounds();
		g.translate(-selfBounds.x, -selfBounds.y);

		final Point mouse = mousePoint();

		LofTheme.panel(g, w.x, w.y, w.width, w.height, 12);

		// header
		final Shape clip = g.getClip();
		g.setClip(w.x, w.y, w.width, TITLE_H);
		g.setColor(LofTheme.HEADER);
		g.fillRoundRect(w.x, w.y, w.width, TITLE_H + 12, 12, 12);
		g.setClip(clip);
		LofTheme.emberUnderline(g, w.x + 1, w.y + TITLE_H - 2, w.width - 2);

		final BufferedImage logo = LofTheme.logo();
		int titleX = w.x + 10;
		if (logo != null)
		{
			g.drawImage(logo, w.x + 8, w.y + 4, 22, 22, null);
			titleX = w.x + 34;
		}
		g.setFont(FontManager.getRunescapeBoldFont());
		LofTheme.shadowText(g, plugin.getShopName(), titleX, w.y + 20, LofTheme.GOLD);

		// balance
		g.setFont(FontManager.getRunescapeSmallFont());
		final String bal = fmt(plugin.getBalance()) + " " + plugin.getCurrencyLabel();
		LofTheme.shadowText(g, bal, w.x + w.width - 30 - g.getFontMetrics().stringWidth(bal), w.y + 19, LofTheme.TEXT_DIM);

		// close
		final Rectangle cr = closeRect(w);
		final boolean closeHov = cr.contains(mouse);
		g.setColor(closeHov ? LofTheme.EMBER : new Color(255, 255, 255, 18));
		g.fillRoundRect(cr.x, cr.y, cr.width, cr.height, 5, 5);
		g.setColor(closeHov ? LofTheme.TEXT : LofTheme.TEXT_DIM);
		final Stroke oldStroke = g.getStroke();
		g.setStroke(new BasicStroke(1.5f));
		g.drawLine(cr.x + 5, cr.y + 5, cr.x + cr.width - 6, cr.y + cr.height - 6);
		g.drawLine(cr.x + cr.width - 6, cr.y + 5, cr.x + 5, cr.y + cr.height - 6);
		g.setStroke(oldStroke);

		// tab rail
		if (hasRail())
		{
			final List<LofShopTabsPlugin.Tab> tabs = plugin.getTabs();
			final int selectedTab = plugin.getSelectedTab();
			for (int t = 0; t < tabs.size(); t++)
			{
				final Rectangle tr = tabRect(w, t);
				final boolean sel = t == selectedTab;
				final boolean hov = tr.contains(mouse);
				g.setColor(sel ? LofTheme.alpha(LofTheme.EMBER, 52) : hov ? LofTheme.ROW_HOVER : LofTheme.ROW);
				g.fillRoundRect(tr.x, tr.y, tr.width, tr.height, 6, 6);
				g.setColor(LofTheme.alpha(sel ? LofTheme.GOLD : LofTheme.EMBER_DARK, sel ? 220 : hov ? 160 : 90));
				g.setStroke(new BasicStroke(sel ? 1.5f : 1.0f));
				g.drawRoundRect(tr.x, tr.y, tr.width - 1, tr.height - 1, 6, 6);
				g.setStroke(oldStroke);
				if (sel)
				{
					g.setColor(LofTheme.EMBER);
					g.fillRoundRect(tr.x + 2, tr.y + 5, 3, tr.height - 10, 2, 2);
				}
				final LofShopTabsPlugin.Tab tab = tabs.get(t);
				final BufferedImage icon = tab.itemId > 0 ? itemManager.getImage(tab.itemId) : null;
				if (icon != null)
				{
					g.drawImage(icon, tr.x + (tr.width - 30) / 2, tr.y + (tr.height - 26) / 2, 30, 26, null);
				}
				else
				{
					final String s = tab.label.isEmpty() ? "?" : tab.label.substring(0, 1);
					LofTheme.shadowText(g, s, tr.x + tr.width / 2 - 3, tr.y + tr.height / 2 + 4, sel ? LofTheme.GOLD : LofTheme.TEXT_DIM);
				}
			}
		}

		// item grid
		final List<LofShopTabsPlugin.Item> items = plugin.getItems();
		final int cw = cellW(w), ch = cellH(w);
		final int spriteW = Math.min(36, cw - 4);
		for (int i = 0; i < items.size(); i++)
		{
			final Rectangle rc = cellRect(w, i);
			final boolean hov = rc.contains(mouse);
			g.setColor(hov ? LofTheme.ROW_HOVER : LofTheme.ROW);
			g.fillRoundRect(rc.x, rc.y, rc.width, rc.height, 6, 6);
			g.setColor(LofTheme.alpha(hov ? LofTheme.EMBER : LofTheme.EMBER_DARK, hov ? 170 : 45));
			g.drawRoundRect(rc.x, rc.y, rc.width - 1, rc.height - 1, 6, 6);

			final LofShopTabsPlugin.Item it = items.get(i);
			final BufferedImage img = it.itemId > 0 ? itemManager.getImage(it.itemId, Math.max(1, it.qty), it.qty > 1) : null;
			if (img != null)
			{
				g.drawImage(img, rc.x + (rc.width - spriteW) / 2, rc.y + 2, spriteW, (int) (spriteW * 32.0 / 36.0), null);
			}
			g.setFont(FontManager.getRunescapeSmallFont());
			final String price = fmt(it.price);
			LofTheme.shadowText(g, price, rc.x + (rc.width - g.getFontMetrics().stringWidth(price)) / 2, rc.y + ch - 3, LofTheme.GOLD);
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(w.width, w.height);
	}

	/** Compact price: 1.2k / 3.4m / 1.1b, else the raw number. */
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
