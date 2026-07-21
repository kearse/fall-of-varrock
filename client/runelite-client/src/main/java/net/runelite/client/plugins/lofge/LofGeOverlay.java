/*
 * Fall of Varrock — Grand Exchange offer window (renderer + hit-testing).
 *
 * Draws the 8-slot offer board (2×4), each slot showing its offer's item, buy/sell tag, progress and
 * price, plus a collection strip and a "Collect all" button. Styled with the shared LofTheme/LofModal
 * so it matches the shop, stake and spoils windows. Geometry lives here so the mouse handler agrees.
 * Like the other lof windows it tracks a plain {@code visible} flag (never reads widgets), so there's
 * no client-thread hazard in hit-testing.
 */
package net.runelite.client.plugins.lofge;

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
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.loftheme.LofModal;
import net.runelite.client.plugins.loftheme.LofTheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class LofGeOverlay extends Overlay
{
	// hit-test result codes
	static final int OUTSIDE = -1;
	static final int INSIDE = 0;
	static final int CLOSE = 1;
	static final int COLLECT_ALL = 2;
	static final int SLOT_BASE = 100; // + box  → click slot body (empty = new offer, else collect)
	static final int ABORT_BASE = 200; // + box → abort an active offer

	private static final int PAD = LofModal.PAD;        // 14
	private static final int GRID_TOP = 46;             // below the 38px title + 8
	private static final int COL_GAP = 8;
	private static final int ROW_GAP = 8;
	private static final int COLS = 4;
	private static final int ROWS = 2;
	private static final int COL_W = (LofModal.W - PAD * 2 - COL_GAP * (COLS - 1)) / COLS; // 107
	private static final int ROW_H = 94;
	private static final int COLL_Y = GRID_TOP + ROWS * ROW_H + ROWS * ROW_GAP;            // 250
	private static final int BTN_W = 150;
	private static final int BTN_H = 28;

	private final Client client;
	private final LofGePlugin plugin;
	private final ItemManager itemManager;

	private boolean visible;

	@Inject
	private LofGeOverlay(Client client, LofGePlugin plugin, ItemManager itemManager)
	{
		this.client = client;
		this.plugin = plugin;
		this.itemManager = itemManager;
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

	private int originX()
	{
		return LofModal.originX(client);
	}

	private int originY()
	{
		return LofModal.originY(client);
	}

	private Rectangle slotRect(int ox, int oy, int i)
	{
		final int col = i % COLS, row = i / COLS;
		final int x = ox + PAD + col * (COL_W + COL_GAP);
		final int y = oy + GRID_TOP + row * (ROW_H + ROW_GAP);
		return new Rectangle(x, y, COL_W, ROW_H);
	}

	private Rectangle abortRect(int ox, int oy, int i)
	{
		final Rectangle s = slotRect(ox, oy, i);
		return new Rectangle(s.x + s.width - 16, s.y + 3, 13, 13);
	}

	private Rectangle collectAllRect(int ox, int oy)
	{
		return new Rectangle(ox + LofModal.W - PAD - BTN_W, oy + LofModal.H - PAD - BTN_H, BTN_W, BTN_H);
	}

	int hitTest(Point p)
	{
		if (!visible)
		{
			return OUTSIDE;
		}
		final int ox = originX(), oy = originY();
		if (!new Rectangle(ox, oy, LofModal.W, LofModal.H).contains(p))
		{
			return OUTSIDE;
		}
		if (LofModal.closeRect(ox, oy).contains(p))
		{
			return CLOSE;
		}
		if (anyCollectable() && collectAllRect(ox, oy).contains(p))
		{
			return COLLECT_ALL;
		}
		final LofGePlugin.Slot[] slots = plugin.getSlots();
		for (int i = 0; i < LofGePlugin.SLOTS; i++)
		{
			final LofGePlugin.Slot s = slots[i];
			if (s != null && !s.isEmpty() && isAbortable(s) && abortRect(ox, oy, i).contains(p))
			{
				return ABORT_BASE + i;
			}
			if (slotRect(ox, oy, i).contains(p))
			{
				return SLOT_BASE + i;
			}
		}
		return INSIDE;
	}

	private boolean isAbortable(LofGePlugin.Slot s)
	{
		return s.state == 3 || s.state == 5; // BUYING / SELLING (in progress)
	}

	private boolean anyCollectable()
	{
		for (LofGePlugin.Slot s : plugin.getSlots())
		{
			if (s != null && (s.collectCoins > 0 || s.collectItems > 0))
			{
				return true;
			}
		}
		return false;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!plugin.isEnabled() || client.getGameState() != GameState.LOGGED_IN || !visible)
		{
			return null;
		}

		final Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// Absolute canvas coordinates — undo any renderer translate (same trick as the other windows).
		final Rectangle selfBounds = getBounds();
		g.translate(-selfBounds.x, -selfBounds.y);

		final int ox = originX(), oy = originY();
		final Point mouse = mousePoint();

		LofModal.frame(g, ox, oy, "Grand Exchange", LofModal.fmt(plugin.getCoins()) + " gp", mouse);

		final LofGePlugin.Slot[] slots = plugin.getSlots();
		for (int i = 0; i < LofGePlugin.SLOTS; i++)
		{
			drawSlot(g, ox, oy, i, slots[i], mouse);
		}

		drawCollection(g, ox, oy, mouse);

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(LofModal.W, LofModal.H);
	}

	private void drawSlot(Graphics2D g, int ox, int oy, int i, LofGePlugin.Slot s, Point mouse)
	{
		final Rectangle r = slotRect(ox, oy, i);
		final boolean hov = r.contains(mouse);

		g.setColor(hov ? LofTheme.ROW_HOVER : LofTheme.ROW);
		g.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8);
		g.setColor(LofTheme.alpha(hov ? LofTheme.EMBER : Color.BLACK, hov ? 150 : 160));
		g.drawRoundRect(r.x, r.y, r.width - 1, r.height - 1, 8, 8);

		if (s == null || s.isEmpty())
		{
			// empty slot: a "+" and "Make offer"
			g.setFont(FontManager.getRunescapeBoldFont());
			final String plus = "+";
			final FontMetrics fmp = g.getFontMetrics();
			LofTheme.shadowText(g, plus, r.x + (r.width - fmp.stringWidth(plus)) / 2, r.y + r.height / 2 - 2,
				hov ? LofTheme.GOLD : LofTheme.GOLD_DIM);
			g.setFont(FontManager.getRunescapeSmallFont());
			final String lbl = "Make offer";
			LofTheme.shadowText(g, lbl, r.x + (r.width - g.getFontMetrics().stringWidth(lbl)) / 2, r.y + r.height / 2 + 18,
				LofTheme.TEXT_DIM);
			return;
		}

		// tag
		g.setFont(FontManager.getRunescapeSmallFont());
		final String tag = s.buy ? "BUY" : "SELL";
		LofTheme.shadowText(g, tag, r.x + 6, r.y + 14, s.buy ? LofTheme.LAVA : LofTheme.GOLD);

		// abort ✕ for in-progress offers
		if (isAbortable(s))
		{
			final Rectangle ar = abortRect(ox, oy, i);
			final boolean ah = ar.contains(mouse);
			g.setColor(ah ? LofTheme.EMBER : LofTheme.alpha(Color.WHITE, 20));
			g.fillRoundRect(ar.x, ar.y, ar.width, ar.height, 4, 4);
			final Stroke old = g.getStroke();
			g.setStroke(new BasicStroke(1.3f));
			g.setColor(ah ? LofTheme.TEXT : LofTheme.TEXT_DIM);
			g.drawLine(ar.x + 4, ar.y + 4, ar.x + ar.width - 5, ar.y + ar.height - 5);
			g.drawLine(ar.x + ar.width - 5, ar.y + 4, ar.x + 4, ar.y + ar.height - 5);
			g.setStroke(old);
		}

		// item sprite (centred in the upper area)
		final BufferedImage img = s.itemId > 0 ? itemManager.getImage(s.itemId) : null;
		if (img != null)
		{
			g.drawImage(img, r.x + (r.width - img.getWidth()) / 2, r.y + 18, null);
		}

		// name
		g.setFont(FontManager.getRunescapeSmallFont());
		final FontMetrics fm = g.getFontMetrics();
		final String name = fit(fm, itemName(s.itemId), r.width - 10);
		LofTheme.shadowText(g, name, r.x + (r.width - fm.stringWidth(name)) / 2, r.y + 58, LofTheme.TEXT);

		// progress bar
		final int barY = r.y + 64;
		final int barW = r.width - 12;
		g.setColor(new Color(14, 10, 8));
		g.fillRoundRect(r.x + 6, barY, barW, 7, 3, 3);
		final int frac = s.qty > 0 ? Math.min(barW, (int) ((long) barW * s.filled / s.qty)) : barW;
		g.setColor(s.buy ? LofTheme.EMBER : LofTheme.GOLD_DIM);
		g.fillRoundRect(r.x + 6, barY, frac, 7, 3, 3);

		// status line: filled/qty @ price
		final String done = (s.state == 4 || s.state == 6) ? "done" : s.filled + "/" + s.qty;
		final String line = done + " · " + LofModal.fmt(s.price) + " gp";
		LofTheme.shadowText(g, fit(fm, line, r.width - 8), r.x + 6, r.y + 84, LofTheme.TEXT_DIM);
	}

	private void drawCollection(Graphics2D g, int ox, int oy, Point mouse)
	{
		g.setFont(FontManager.getRunescapeSmallFont());
		LofTheme.shadowText(g, "COLLECTION BOX", ox + PAD, oy + COLL_Y + 2, LofTheme.GOLD_DIM);

		// summarise collectable proceeds
		int stacks = 0;
		long coins = 0;
		for (LofGePlugin.Slot s : plugin.getSlots())
		{
			if (s == null)
			{
				continue;
			}
			if (s.collectItems > 0)
			{
				stacks++;
			}
			coins += s.collectCoins;
		}
		final boolean any = stacks > 0 || coins > 0;
		final String summary = any
			? (stacks + (stacks == 1 ? " stack" : " stacks") + (coins > 0 ? " · " + LofModal.fmt(coins) + " gp" : "") + " ready")
			: "Nothing to collect";
		g.setFont(FontManager.getRunescapeFont());
		LofTheme.shadowText(g, summary, ox + PAD, oy + COLL_Y + 22, any ? LofTheme.TEXT : LofTheme.TEXT_DIM);

		// collect-all button (bottom-right)
		final Rectangle br = collectAllRect(ox, oy);
		LofModal.button(g, br, "Collect all", LofTheme.EMBER, any, br.contains(mouse));
	}

	private String itemName(int id)
	{
		try
		{
			final String n = itemManager.getItemComposition(id).getName();
			return n == null || n.isEmpty() ? "Item " + id : n;
		}
		catch (Exception e)
		{
			return "Item " + id;
		}
	}

	/** Truncate text with an ellipsis to fit maxW pixels. */
	private String fit(FontMetrics fm, String text, int maxW)
	{
		if (fm.stringWidth(text) <= maxW)
		{
			return text;
		}
		final String ell = "…";
		final int ew = fm.stringWidth(ell);
		final StringBuilder sb = new StringBuilder();
		int w = 0;
		for (int i = 0; i < text.length(); i++)
		{
			final int cw = fm.charWidth(text.charAt(i));
			if (w + cw + ew > maxW)
			{
				break;
			}
			sb.append(text.charAt(i));
			w += cw;
		}
		return sb.append(ell).toString();
	}

	private Point mousePoint()
	{
		final net.runelite.api.Point m = client.getMouseCanvasPosition();
		return m == null ? new Point(-1, -1) : new Point(m.getX(), m.getY());
	}
}
