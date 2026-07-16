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

	// Our own right-click menu (drawn on top of the window so it can't hide behind it). Item-buy
	// rows carry the item name; Buy X prompts for an amount. Indices map to LofShopTabsPlugin.menuAction.
	static final String[] MENU_OPTS = {"Value", "Buy 1", "Buy 10", "Buy 100", "Buy X", "Examine", "Cancel"};
	private static final int MENU_W = 240;
	private static final int MENU_HEADER_H = 16;
	private static final int MENU_ROW_H = 15;

	private volatile boolean menuOpen;
	private volatile int menuGridIndex = -1;
	private volatile int menuX, menuY;

	private static final int COLS = 8;
	private static final int CELL_GAP = 3;
	private static final int CELL_H = 50;       // fixed — never scales with item count
	private static final int TITLE_H = 30;
	private static final int RAIL_W = 40;
	private static final int RAIL_GAP = 4;
	private static final int PAD = 6;
	private static final int SCROLLBAR_W = 5;
	private static final int SCROLL_STEP = CELL_H + CELL_GAP;

	private volatile int scroll;

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
		return (gridW - SCROLLBAR_W - 2 - (COLS - 1) * CELL_GAP) / COLS;
	}

	private int rowCount()
	{
		final int n = plugin.getItems().size();
		return Math.max(1, (n + COLS - 1) / COLS);
	}

	/** The scrolling item area (below the header, right of the rail). */
	private Rectangle gridViewport(Rectangle w)
	{
		final int x = gridX(w);
		final int y = gridTop(w);
		final int gw = w.x + w.width - PAD - x;
		final int gh = w.y + w.height - PAD - y;
		return new Rectangle(x, y, gw, gh);
	}

	private int maxScroll(Rectangle w)
	{
		final int contentH = rowCount() * (CELL_H + CELL_GAP);
		return Math.max(0, contentH - gridViewport(w).height);
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
		final int cw = cellW(w);
		final int x = gridX(w) + col * (cw + CELL_GAP);
		final int y = gridTop(w) + row * (CELL_H + CELL_GAP) - scroll;
		return new Rectangle(x, y, cw, CELL_H);
	}

	void resetScroll()
	{
		scroll = 0;
	}

	/** Mouse-wheel scroll when the cursor is over the item area. */
	boolean handleScroll(Point p, int rotation)
	{
		final Rectangle w = winRect;
		if (!showing || w == null || !gridViewport(w).contains(p))
		{
			return false;
		}
		scroll = Math.max(0, Math.min(scroll + rotation * SCROLL_STEP, maxScroll(w)));
		return true;
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
		final Rectangle vp = gridViewport(w);
		if (!vp.contains(p))
		{
			return INSIDE; // header/rail gap etc. — swallow but no action
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

	// -------- our own right-click menu --------

	boolean isMenuOpen()
	{
		return menuOpen;
	}

	int menuGridIndex()
	{
		return menuGridIndex;
	}

	/** Open the menu for grid cell [gridIndex] at the cursor, clamped on-canvas. */
	void openMenu(int gridIndex, int atX, int atY)
	{
		menuGridIndex = gridIndex;
		final int h = MENU_HEADER_H + MENU_OPTS.length * MENU_ROW_H;
		menuX = Math.max(2, Math.min(atX, client.getCanvasWidth() - MENU_W - 2));
		menuY = Math.max(2, Math.min(atY, client.getCanvasHeight() - h - 2));
		menuOpen = true;
	}

	void closeMenu()
	{
		menuOpen = false;
		menuGridIndex = -1;
	}

	private Rectangle menuBox()
	{
		return new Rectangle(menuX, menuY, MENU_W, MENU_HEADER_H + MENU_OPTS.length * MENU_ROW_H);
	}

	/** Option index under [p] (0..n-1), or -1 for anywhere else (which dismisses the menu). */
	int menuHit(Point p)
	{
		if (!menuOpen || !menuBox().contains(p))
		{
			return -1;
		}
		final int rel = p.y - (menuY + MENU_HEADER_H);
		if (rel < 0)
		{
			return -1;
		}
		final int idx = rel / MENU_ROW_H;
		return (idx >= 0 && idx < MENU_OPTS.length) ? idx : -1;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!computeShowing())
		{
			showing = false;
			menuOpen = false;
			return null;
		}
		final Rectangle w = windowRect();
		if (w == null)
		{
			showing = false;
			menuOpen = false;
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

		// item grid — fixed cell size; scrolls when it overflows, clipped to the viewport
		final List<LofShopTabsPlugin.Item> items = plugin.getItems();
		final Rectangle vp = gridViewport(w);
		scroll = Math.max(0, Math.min(scroll, maxScroll(w)));
		final int cw = cellW(w);
		final int spriteW = Math.min(36, cw - 4);
		final Shape gridClip = g.getClip();
		g.setClip(vp.x, vp.y, vp.width, vp.height);
		g.setFont(FontManager.getRunescapeSmallFont());
		for (int i = 0; i < items.size(); i++)
		{
			final Rectangle rc = cellRect(w, i);
			if (rc.y + rc.height < vp.y || rc.y > vp.y + vp.height)
			{
				continue; // off-screen
			}
			final boolean hov = rc.contains(mouse) && mouse.y >= vp.y && mouse.y <= vp.y + vp.height;
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
			final String price = fmt(it.price);
			LofTheme.shadowText(g, price, rc.x + (rc.width - g.getFontMetrics().stringWidth(price)) / 2, rc.y + CELL_H - 3, LofTheme.GOLD);
		}
		g.setClip(gridClip);

		// scrollbar
		final int ms = maxScroll(w);
		if (ms > 0)
		{
			final int sbX = vp.x + vp.width - SCROLLBAR_W;
			g.setColor(new Color(255, 255, 255, 14));
			g.fillRoundRect(sbX, vp.y, SCROLLBAR_W, vp.height, SCROLLBAR_W, SCROLLBAR_W);
			final int contentH = rowCount() * (CELL_H + CELL_GAP);
			final int thumbH = Math.max(24, vp.height * vp.height / contentH);
			final int thumbY = vp.y + (vp.height - thumbH) * scroll / ms;
			g.setColor(LofTheme.alpha(LofTheme.EMBER, 190));
			g.fillRoundRect(sbX, thumbY, SCROLLBAR_W, thumbH, SCROLLBAR_W, SCROLLBAR_W);
		}

		// our own right-click menu — drawn LAST so it sits on top of the window
		if (menuOpen)
		{
			drawMenu(g, mouse);
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(w.width, w.height);
	}

	/** The OSRS-style "Choose Option" menu: Value / Buy 1 / 10 / 100 / X / Examine / Cancel, each
	 *  buy row tagged with the item name. */
	private void drawMenu(Graphics2D g, Point mouse)
	{
		final Rectangle box = menuBox();
		g.setColor(new Color(0, 0, 0, 235));
		g.fillRect(box.x, box.y, box.width, box.height);
		g.setColor(LofTheme.EMBER_DARK);
		g.drawRect(box.x, box.y, box.width - 1, box.height - 1);

		// header
		g.setColor(LofTheme.HEADER);
		g.fillRect(box.x, box.y, box.width, MENU_HEADER_H);
		g.setFont(FontManager.getRunescapeSmallFont());
		LofTheme.shadowText(g, "Choose Option", box.x + 4, box.y + 12, LofTheme.GOLD);

		final String name = plugin.itemName(menuGridIndex);
		for (int i = 0; i < MENU_OPTS.length; i++)
		{
			final int ry = box.y + MENU_HEADER_H + i * MENU_ROW_H;
			final boolean hov = mouse.y >= ry && mouse.y < ry + MENU_ROW_H && mouse.x >= box.x && mouse.x < box.x + box.width;
			if (hov)
			{
				g.setColor(LofTheme.alpha(LofTheme.EMBER, 70));
				g.fillRect(box.x + 1, ry, box.width - 2, MENU_ROW_H);
			}
			final String opt = MENU_OPTS[i];
			LofTheme.shadowText(g, opt, box.x + 4, ry + 12, LofTheme.TEXT);
			// item name in orange after buy/value/examine rows (not Cancel)
			if (i < MENU_OPTS.length - 1 && !name.isEmpty())
			{
				final int ox = box.x + 4 + g.getFontMetrics().stringWidth(opt) + 5;
				LofTheme.shadowText(g, name, ox, ry + 12, LofTheme.LAVA);
			}
		}
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
