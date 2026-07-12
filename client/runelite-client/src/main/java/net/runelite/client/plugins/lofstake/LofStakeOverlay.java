/*
 * Fall of Varrock — Duel Arena stake overlay (renderer + hit-testing).
 *
 * A fully custom, themed stake window drawn client-side over the standard trade interface (group
 * 335) the server opens for a staked duel. It READS the live offers straight from that interface's
 * widgets — your offer (335.25), the opponent's (335.28), the title (335.31), your value (335.24) —
 * so no custom packets are needed. Actions route back through the existing SECURE TradeSession:
 *   - click one of YOUR staked items → "::stake rm <slot>" (un-stake)
 *   - Accept / Decline buttons       → "::stake a" / "::stake d"
 * Adding items stays native (click the real inventory on the right, which the overlay never covers).
 *
 * Every widget read is null-guarded: if the interface's shape differs at runtime the window simply
 * renders empty rather than throwing — it can never crash the client.
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

	private static final int WIN_W = 210;
	private static final int WIN_H = 372;
	private static final int WIN_ARC = 14;
	private static final int TITLE_H = 36;
	private static final int PAD = 12;
	private static final int COLS = 7;
	private static final int ROWS = 4;
	private static final int SLOT = 24;
	private static final int SLOT_GAP = 1;
	private static final int GRID_W = COLS * SLOT + (COLS - 1) * SLOT_GAP; // 175
	private static final int GRID_H = ROWS * SLOT + (ROWS - 1) * SLOT_GAP; // 99
	private static final int YOUR_GRID_Y = TITLE_H + 24;
	private static final int THEIR_GRID_Y = YOUR_GRID_Y + GRID_H + 40;
	private static final int BTN_H = 32;

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
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	/** Only while the server's stake trade screen is open (title reads "Staking with …"). */
	boolean isShowing()
	{
		if (!config.enabled() || client.getGameState() != GameState.LOGGED_IN)
		{
			return false;
		}
		final Widget title = client.getWidget(TRADE_GROUP, TITLE_CHILD);
		return title != null && !title.isHidden() && text(title).startsWith("Staking");
	}

	// Left-anchored so it never covers the inventory on the right (needed to add items natively).
	private int originX() { return 12; }
	private int originY() { return Math.max(0, (client.getCanvasHeight() - WIN_H) / 2); }

	private Rectangle slotRect(int ox, int oy, int gridY, int i)
	{
		final int col = i % COLS, row = i / COLS;
		return new Rectangle(ox + PAD + col * (SLOT + SLOT_GAP), oy + gridY + row * (SLOT + SLOT_GAP), SLOT, SLOT);
	}

	private Rectangle acceptRect(int ox, int oy) { return new Rectangle(ox + PAD, oy + WIN_H - PAD - BTN_H, (WIN_W - PAD * 2 - 6) / 2, BTN_H); }
	private Rectangle declineRect(int ox, int oy) { final Rectangle a = acceptRect(ox, oy); return new Rectangle(a.x + a.width + 6, a.y, a.width, BTN_H); }

	int hitTest(Point p)
	{
		if (!isShowing()) return OUTSIDE;
		final int ox = originX(), oy = originY();
		if (!new Rectangle(ox, oy, WIN_W, WIN_H).contains(p)) return OUTSIDE;
		if (acceptRect(ox, oy).contains(p)) return ACCEPT;
		if (declineRect(ox, oy).contains(p)) return DECLINE;
		for (int i = 0; i < COLS * ROWS; i++)
		{
			if (slotRect(ox, oy, YOUR_GRID_Y, i).contains(p)) return SLOT_BASE + i;
		}
		return INSIDE;
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
		int titleX = ox + 12;
		if (logo != null)
		{
			g.drawImage(logo, ox + 10, oy + 5, 26, 26, null);
			titleX = ox + 42;
		}
		g.setFont(FontManager.getRunescapeBoldFont());
		LofTheme.shadowText(g, "Duel Stake", titleX, oy + 24, LofTheme.GOLD);

		// your stake
		g.setFont(FontManager.getRunescapeSmallFont());
		final String yourVal = valueText(client.getWidget(TRADE_GROUP, YOUR_VALUE_CHILD));
		LofTheme.shadowText(g, "YOUR STAKE", ox + PAD, oy + YOUR_GRID_Y - 6, LofTheme.GOLD_DIM);
		if (yourVal != null)
		{
			LofTheme.shadowText(g, yourVal, ox + WIN_W - PAD - g.getFontMetrics().stringWidth(yourVal), oy + YOUR_GRID_Y - 6, GREEN);
		}
		drawGrid(g, ox, oy, YOUR_GRID_Y, client.getWidget(TRADE_GROUP, YOUR_OFFER_CHILD), mouse, true);

		// opponent stake
		final boolean theyAccepted = text(client.getWidget(TRADE_GROUP, ACCEPT_TEXT_CHILD)).contains("accepted");
		LofTheme.shadowText(g, "OPPONENT", ox + PAD, oy + THEIR_GRID_Y - 6, LofTheme.GOLD_DIM);
		if (theyAccepted)
		{
			final String tag = "ACCEPTED";
			LofTheme.shadowText(g, tag, ox + WIN_W - PAD - g.getFontMetrics().stringWidth(tag), oy + THEIR_GRID_Y - 6, GREEN);
		}
		drawGrid(g, ox, oy, THEIR_GRID_Y, client.getWidget(TRADE_GROUP, THEIR_OFFER_CHILD), mouse, false);

		// buttons
		g.setFont(FontManager.getRunescapeBoldFont());
		final Rectangle acc = acceptRect(ox, oy), dec = declineRect(ox, oy);
		button(g, acc, "Accept", LofTheme.GOLD, acc.contains(mouse));
		button(g, dec, "Decline", LofTheme.EMBER, dec.contains(mouse));

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(WIN_W, WIN_H);
	}

	/** Draw a 7x4 item grid from an offer widget's item children (null-safe). */
	private void drawGrid(Graphics2D g, int ox, int oy, int gridY, Widget offer, Point mouse, boolean yours)
	{
		final Widget[] items = offer == null ? null : offer.getDynamicChildren();
		for (int i = 0; i < COLS * ROWS; i++)
		{
			final Rectangle rc = slotRect(ox, oy, gridY, i);
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

	/** "Value: 3,200,000 coins" text stripped of tags, or null. */
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
