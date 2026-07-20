/*
 * Fall of Varrock — Duel Arena stake overlay (renderer + hit-testing).
 *
 * The classic Duel Arena *Stakes* screen, themed and drawn client-side over the server's standard
 * trade interface (group 335) during a staked duel. Design-system standard modal (480x400 — see
 * docs/overlay-design-system.md), matched to the rules window. It READS the live offers straight
 * from interface 335 — your offer (335.25), the opponent's (335.28), title (335.31), your value
 * (335.24) — so no custom packets are needed. Actions route through the existing SECURE TradeSession:
 *   - click one of YOUR staked items → "::stake rm <slot>" (un-stake)
 *   - Accept / Decline               → "::stake a" / "::stake d"
 * Adding items stays native (the window is viewport-anchored, leaving the inventory column clickable).
 *
 * Every widget read is null-guarded: it renders empty rather than throwing, so it can't crash.
 */
package net.runelite.client.plugins.lofstake;

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

class LofStakeOverlay extends Overlay
{
	// Trade interface (standard OSRS group 335) component ids the server drives (see TradeSession).
	private static final int TRADE_GROUP = 335;
	private static final int TITLE_CHILD = 31;
	private static final int YOUR_OFFER_CHILD = 25;
	private static final int THEIR_OFFER_CHILD = 28;
	private static final int YOUR_VALUE_CHILD = 24;
	private static final int ACCEPT_TEXT_CHILD = 30;

	// hit-test result codes
	static final int OUTSIDE = -1;
	static final int INSIDE = 0;
	static final int ACCEPT = 1;
	static final int DECLINE = 2;
	static final int SLOT_BASE = 100; // + your-stake slot index

	// Design-system standard modal (matches the rules window).
	static final int WIN_W = 480;
	static final int WIN_H = 400;
	private static final int WIN_ARC = 14;
	private static final int TITLE_H = 38;
	private static final int PAD = 12;
	private static final int COLS = 4;
	private static final int ROWS = 7;
	private static final int SLOT = 26;
	private static final int SLOT_GAP = 2;
	private static final int GRID_W = COLS * SLOT + (COLS - 1) * SLOT_GAP; // 110
	private static final int GRID_TOP = TITLE_H + 30;
	private static final int BTN_H = 32;

	// column x-origins: your stake (left), opponent (right), grids centred within each half.
	private static final int HALF = (WIN_W - PAD * 2) / 2;                       // 228
	private static final int YOUR_X = PAD + (HALF - GRID_W) / 2;                 // grid left edge
	private static final int THEIR_X = PAD + HALF + (HALF - GRID_W) / 2;

	private static final Color GREEN = new Color(110, 205, 110);

	private final Client client;
	private final LofStakeConfig config;
	private final ItemManager itemManager;

	@Inject
	private LofStakeOverlay(Client client, LofStakeConfig config, ItemManager itemManager)
	{
		this.client = client;
		this.config = config;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
	}

	// Computed on the CLIENT thread during render() and read by the mouse thread. getWidget() off
	// the client thread returns null, so isShowing() answered false and hitTest returned OUTSIDE —
	// every button in the window was dead. The click path must read ONLY these cached values.
	// Same fix, same reason, as LofShopTabsOverlay's cached showing/winRect.
	private volatile boolean showingCached;

	/** Cached — safe to call from the mouse thread (see the field note). */
	boolean isShowing()
	{
		return showingCached;
	}

	/** The live client-thread check (widget access) — only call from render(). */
	private boolean computeShowing()
	{
		if (!config.enabled() || client.getGameState() != GameState.LOGGED_IN)
		{
			return false;
		}
		final Widget title = client.getWidget(TRADE_GROUP, TITLE_CHILD);
		return title != null && !title.isHidden() && text(title).startsWith("Staking");
	}

	// Centred in the game viewport (left ~516 in fixed mode) so the inventory column on the right
	// stays clickable for adding items — same anchor as the rules window.
	private int originX() { return Math.max(12, (Math.min(client.getCanvasWidth(), 516) - WIN_W) / 2); }
	private int originY() { return Math.max(0, (client.getCanvasHeight() - WIN_H) / 2); }

	private Rectangle slotRect(int ox, int oy, int gridX, int i)
	{
		final int col = i % COLS, row = i / COLS;
		return new Rectangle(ox + gridX + col * (SLOT + SLOT_GAP), oy + GRID_TOP + row * (SLOT + SLOT_GAP), SLOT, SLOT);
	}

	private Rectangle acceptRect(int ox, int oy) { return new Rectangle(ox + WIN_W - PAD - 210, oy + WIN_H - PAD - BTN_H, 100, BTN_H); }
	private Rectangle declineRect(int ox, int oy) { return new Rectangle(ox + WIN_W - PAD - 100, oy + WIN_H - PAD - BTN_H, 100, BTN_H); }

	int hitTest(Point p)
	{
		if (!isShowing()) return OUTSIDE;
		final int ox = originX(), oy = originY();
		if (!new Rectangle(ox, oy, WIN_W, WIN_H).contains(p)) return OUTSIDE;
		if (acceptRect(ox, oy).contains(p)) return ACCEPT;
		if (declineRect(ox, oy).contains(p)) return DECLINE;
		for (int i = 0; i < COLS * ROWS; i++)
		{
			if (slotRect(ox, oy, YOUR_X, i).contains(p)) return SLOT_BASE + i;
		}
		return INSIDE;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		// Publish the gate for the mouse thread in the same pass that draws the window.
		final boolean showing = computeShowing();
		showingCached = showing;
		if (!showing) return null;

		final Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// This overlay draws at ABSOLUTE canvas coordinates. Undo any translate the renderer
		// applied (e.g. a saved drag offset) so absolute means absolute.
		final java.awt.Rectangle selfBounds = getBounds();
		g.translate(-selfBounds.x, -selfBounds.y);


		final int ox = originX(), oy = originY();
		final Point mouse = mousePoint();

		LofTheme.panel(g, ox, oy, WIN_W, WIN_H, WIN_ARC);

		// header
		final Shape clip = g.getClip();
		g.setClip(ox, oy, WIN_W, TITLE_H);
		g.setColor(LofTheme.HEADER);
		g.fillRoundRect(ox, oy, WIN_W, TITLE_H + WIN_ARC, WIN_ARC, WIN_ARC);
		g.setClip(clip);
		LofTheme.emberUnderline(g, ox + 1, oy + TITLE_H - 2, WIN_W - 2);
		final BufferedImage logo = LofTheme.logo();
		int titleX = ox + 14;
		if (logo != null) { g.drawImage(logo, ox + 12, oy + 5, 28, 28, null); titleX = ox + 46; }
		g.setFont(FontManager.getRunescapeBoldFont());
		LofTheme.shadowText(g, "Duel Arena", titleX, oy + 25, LofTheme.GOLD);

		// column headers
		g.setFont(FontManager.getRunescapeSmallFont());
		LofTheme.shadowText(g, "YOUR STAKE", ox + PAD, oy + TITLE_H + 22, LofTheme.GOLD_DIM);
		final boolean theyAccepted = text(client.getWidget(TRADE_GROUP, ACCEPT_TEXT_CHILD)).contains("accepted");
		LofTheme.shadowText(g, "OPPONENT", ox + PAD + HALF, oy + TITLE_H + 22, LofTheme.GOLD_DIM);
		if (theyAccepted)
		{
			final String tag = "ACCEPTED";
			LofTheme.shadowText(g, tag, ox + WIN_W - PAD - g.getFontMetrics().stringWidth(tag), oy + TITLE_H + 22, GREEN);
		}

		// grids
		drawGrid(g, ox, oy, YOUR_X, client.getWidget(TRADE_GROUP, YOUR_OFFER_CHILD), mouse, true);
		drawGrid(g, ox, oy, THEIR_X, client.getWidget(TRADE_GROUP, THEIR_OFFER_CHILD), mouse, false);

		// centre divider
		g.setColor(LofTheme.alpha(LofTheme.EMBER_DARK, 150));
		g.fillRect(ox + WIN_W / 2 - 1, oy + GRID_TOP - 4, 2, ROWS * (SLOT + SLOT_GAP));

		// your value under your grid
		final int valY = oy + GRID_TOP + ROWS * (SLOT + SLOT_GAP) + 14;
		final String yourVal = valueText(client.getWidget(TRADE_GROUP, YOUR_VALUE_CHILD));
		if (yourVal != null)
		{
			LofTheme.shadowText(g, yourVal, ox + PAD, valY, GREEN);
		}

		// buttons
		g.setFont(FontManager.getRunescapeBoldFont());
		final Rectangle acc = acceptRect(ox, oy), dec = declineRect(ox, oy);
		button(g, acc, "Accept", LofTheme.GOLD, acc.contains(mouse));
		button(g, dec, "Decline", LofTheme.EMBER, dec.contains(mouse));

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(WIN_W, WIN_H);
	}

	/** Draw a 4x7 item grid from an offer widget's item children (null-safe). */
	private void drawGrid(Graphics2D g, int ox, int oy, int gridX, Widget offer, Point mouse, boolean yours)
	{
		final Widget[] items = offer == null ? null : offer.getDynamicChildren();
		for (int i = 0; i < COLS * ROWS; i++)
		{
			final Rectangle rc = slotRect(ox, oy, gridX, i);
			final boolean hov = yours && rc.contains(mouse);
			g.setColor(hov ? LofTheme.ROW_HOVER : LofTheme.ROW);
			g.fillRoundRect(rc.x, rc.y, rc.width, rc.height, 4, 4);
			g.setColor(LofTheme.alpha(LofTheme.EMBER, hov ? 130 : 30));
			g.drawRoundRect(rc.x, rc.y, rc.width - 1, rc.height - 1, 4, 4);

			if (items != null && i < items.length && items[i] != null)
			{
				final int id = items[i].getItemId();
				final int qty = items[i].getItemQuantity();
				if (id > 0 && qty > 0)
				{
					final BufferedImage img = itemManager.getImage(id, qty, qty > 1);
					if (img != null)
					{
						g.drawImage(img, rc.x + 2, rc.y + 2, SLOT - 4, SLOT - 4, null);
					}
				}
			}
		}
	}

	private static void button(Graphics2D g, Rectangle rc, String label, Color accent, boolean hov)
	{
		g.setColor(hov ? LofTheme.alpha(accent, 34) : new Color(255, 255, 255, 12));
		g.fillRoundRect(rc.x, rc.y, rc.width, rc.height, 8, 8);
		g.setColor(LofTheme.alpha(accent, hov ? 200 : 120));
		final Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(1.4f));
		g.drawRoundRect(rc.x, rc.y, rc.width - 1, rc.height - 1, 8, 8);
		g.setStroke(old);
		final int tw = g.getFontMetrics().stringWidth(label);
		LofTheme.shadowText(g, label, rc.x + (rc.width - tw) / 2, rc.y + rc.height / 2 + 6, accent);
	}

	private static String valueText(Widget w)
	{
		final String t = text(w);
		return t.isEmpty() ? null : t.replaceAll("<[^>]*>", "").trim();
	}

	private static String text(Widget w)
	{
		return w == null || w.getText() == null ? "" : w.getText();
	}

	private Point mousePoint()
	{
		final net.runelite.api.Point m = client.getMouseCanvasPosition();
		return m == null ? new Point(-1, -1) : new Point(m.getX(), m.getY());
	}
}
