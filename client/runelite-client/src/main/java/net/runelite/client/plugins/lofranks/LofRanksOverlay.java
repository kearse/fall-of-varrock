/*
 * Fall of Varrock — Feudal Ranks window (renderer + hit-testing).
 *
 * The CoD-style progression screen: current rank emblem + name, a coin bar that fills toward the
 * next rank's price (gold + glowing button when affordable), a next-rank showcase card with its
 * unlock chips, and the whole ladder as an emblem track (hover any rank for its unlocks).
 *
 * Emblem art: rank_<name>.png resources, currently re-using the website's ember-obsidian icon
 * family (bundles/general/support/war/elo/membership/official/overall) until the bespoke rank
 * emblems are generated — same filenames, so the new art drops straight in. Styled with LofTheme.
 */
package net.runelite.client.plugins.lofranks;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
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
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.plugins.loftheme.LofTheme;
import net.runelite.client.plugins.loftheme.LofWindows;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ImageUtil;

class LofRanksOverlay extends Overlay implements LofWindows.Window
{
	// hit-test result codes
	static final int OUTSIDE = -1;
	static final int INSIDE = 0;
	static final int CLOSE = 1;
	static final int BUY = 2;

	// Design-system standard modal (480x400 — docs/overlay-design-system.md §6A).
	private static final int WIN_W = 480;
	private static final int WIN_H = 400;
	private static final int WIN_ARC = 14;
	private static final int TITLE_H = 38;
	private static final int PAD = 14;

	private static final int HERO_Y = TITLE_H + 8;      // 46
	private static final int BAR_LABEL_Y = 118;
	private static final int BAR_Y = 124;
	private static final int BAR_H = 14;
	private static final int CARD_Y = 150;
	private static final int CARD_H = 88;
	private static final int TRACK_LINE_Y = 278;        // connector line through the node centres
	private static final int NODE_D = 44;               // node circle diameter
	private static final int FOOT_BTN_H = 32;

	private static final int COINS_ID = 995;            // item.coins_995

	private static final Color READY_FILL_A = new Color(0x8f6a1f);
	private static final Color READY_FILL_B = new Color(0xfff0c0);

	private final Client client;
	private final LofRanksConfig config;

	private boolean visible;
	private int currentRank; // Title ordinal, 0..7

	// Computed on the CLIENT thread during render() and read by the mouse thread. Reading the
	// inventory container off the client thread returns null/stale, so the affordability gate
	// failed and hitTest answered INSIDE — the BUY click was swallowed while the button was drawn
	// lit. Same fix, same reason, as LofShopTabsOverlay's cached showing/winRect.
	private volatile boolean canBuyCached;

	/** rank_<name>.png once the generated art lands; null slots fall back to vector emblems. */
	private final BufferedImage[] emblems = new BufferedImage[LofRanksData.RANKS.length];
	private boolean emblemsLoaded;

	private Font heroFont;
	private Font nextFont;

	@Inject
	private LofRanksOverlay(Client client, LofRanksConfig config)
	{
		this.client = client;
		this.config = config;
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

	void setCurrentRank(int rank)
	{
		currentRank = rank;
	}

	int getCurrentRank()
	{
		return currentRank;
	}

	/** The next rung's ordinal, or -1 when the player is King. */
	int nextRank()
	{
		return currentRank + 1 < LofRanksData.RANKS.length ? currentRank + 1 : -1;
	}

	private int originX()
	{
		// Centre within the game viewport, not the whole canvas (§6A).
		final int canvasW = client.getCanvasWidth();
		final int viewportW = client.isResized() ? canvasW : Math.min(canvasW, 512);
		return Math.max(0, Math.min((canvasW - WIN_W) / 2, (viewportW - WIN_W) / 2));
	}

	private int originY()
	{
		return Math.max(0, (client.getCanvasHeight() - WIN_H) / 2);
	}

	private Rectangle closeRect(int ox, int oy)
	{
		return new Rectangle(ox + WIN_W - 30, oy + 9, 20, 20);
	}

	private Rectangle buyRect(int ox, int oy)
	{
		final int w = 250;
		return new Rectangle(ox + WIN_W - PAD - w, oy + WIN_H - 12 - FOOT_BTN_H, w, FOOT_BTN_H);
	}

	private Rectangle nodeRect(int ox, int oy, int i)
	{
		// 8 nodes spread evenly between the side paddings.
		final int usable = WIN_W - 2 * PAD - NODE_D;
		final int cx = ox + PAD + (usable * i) / (LofRanksData.RANKS.length - 1);
		return new Rectangle(cx, oy + TRACK_LINE_Y - NODE_D / 2, NODE_D, NODE_D);
	}

	int hitTest(Point p)
	{
		if (!visible)
		{
			return OUTSIDE;
		}
		final int ox = originX(), oy = originY();
		if (!new Rectangle(ox, oy, WIN_W, WIN_H).contains(p))
		{
			return OUTSIDE;
		}
		if (closeRect(ox, oy).contains(p))
		{
			return CLOSE;
		}
		if (buyRect(ox, oy).contains(p))
		{
			// Cached gate (client thread) — never re-read the inventory here.
			return canBuyCached ? BUY : INSIDE;
		}
		return INSIDE;
	}

	/** Coins carried — read straight from the inventory container (server checks the same). */
	private long coinsCarried()
	{
		final ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
		if (inv == null)
		{
			return 0;
		}
		long total = 0;
		for (Item item : inv.getItems())
		{
			if (item != null && item.getId() == COINS_ID)
			{
				total += item.getQuantity();
			}
		}
		return total;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!visible || !config.enabled() || client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}

		final Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// Absolute-coordinate overlay: undo any renderer translate (saved drag offsets — §8).
		final Rectangle selfBounds = getBounds();
		g.translate(-selfBounds.x, -selfBounds.y);

		ensureEmblems();
		if (heroFont == null)
		{
			heroFont = FontManager.getRunescapeBoldFont().deriveFont(22f);
			nextFont = FontManager.getRunescapeBoldFont().deriveFont(18f);
		}

		final int ox = originX(), oy = originY();
		final Point mouse = mousePoint();
		final LofRanksData.Rank cur = LofRanksData.RANKS[currentRank];
		final int nextIdx = nextRank();
		final LofRanksData.Rank next = nextIdx >= 0 ? LofRanksData.RANKS[nextIdx] : null;
		final long coins = coinsCarried();
		final boolean canBuy = next != null && coins >= next.cost;
		// Publish the gate for the mouse thread in the same pass that draws it.
		canBuyCached = canBuy;

		LofTheme.panel(g, ox, oy, WIN_W, WIN_H, WIN_ARC);
		drawHeader(g, ox, oy, mouse);
		drawHero(g, ox, oy, cur, next, coins, canBuy);
		drawCoinBar(g, ox, oy, next, coins);
		drawNextCard(g, ox, oy, nextIdx);
		drawTrack(g, ox, oy, mouse);
		drawFooter(g, ox, oy, next, coins, canBuy, mouse);

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(WIN_W, WIN_H);
	}

	private void drawHeader(Graphics2D g, int ox, int oy, Point mouse)
	{
		final Shape headerClip = g.getClip();
		g.setClip(ox, oy, WIN_W, TITLE_H);
		g.setColor(LofTheme.HEADER);
		g.fillRoundRect(ox, oy, WIN_W, TITLE_H + WIN_ARC, WIN_ARC, WIN_ARC);
		g.setClip(headerClip);
		LofTheme.emberUnderline(g, ox + 1, oy + TITLE_H - 2, WIN_W - 2);

		final BufferedImage logo = LofTheme.logo();
		int titleX = ox + 14;
		if (logo != null)
		{
			g.drawImage(logo, ox + 12, oy + 5, 28, 28, null);
			titleX = ox + 46;
		}
		g.setFont(FontManager.getRunescapeBoldFont());
		LofTheme.shadowText(g, "Feudal Ranks", titleX, oy + 25, LofTheme.GOLD);
		g.setFont(FontManager.getRunescapeSmallFont());
		final String sub = "Duke Horacio";
		LofTheme.shadowText(g, sub, ox + WIN_W - 44 - g.getFontMetrics().stringWidth(sub), oy + 24, LofTheme.TEXT_DIM);

		final Rectangle cr = closeRect(ox, oy);
		final boolean hov = cr.contains(mouse);
		g.setColor(hov ? LofTheme.EMBER : new Color(255, 255, 255, 18));
		g.fillRoundRect(cr.x, cr.y, cr.width, cr.height, 6, 6);
		g.setColor(hov ? LofTheme.TEXT : LofTheme.TEXT_DIM);
		final Stroke oldStroke = g.getStroke();
		g.setStroke(new BasicStroke(1.6f));
		g.drawLine(cr.x + 6, cr.y + 6, cr.x + cr.width - 7, cr.y + cr.height - 7);
		g.drawLine(cr.x + cr.width - 7, cr.y + 6, cr.x + 6, cr.y + cr.height - 7);
		g.setStroke(oldStroke);
	}

	private void drawHero(Graphics2D g, int ox, int oy, LofRanksData.Rank cur, LofRanksData.Rank next, long coins, boolean canBuy)
	{
		drawEmblemAt(g, ox + PAD, oy + HERO_Y + 2, 54, currentRank, false);

		final Color nameCol = cur.color != null ? cur.color : LofTheme.TEXT;
		g.setFont(heroFont);
		LofTheme.shadowText(g, cur.name, ox + PAD + 64, oy + HERO_Y + 30, nameCol);
		g.setFont(FontManager.getRunescapeSmallFont());
		LofTheme.shadowText(g, "RANK " + (currentRank + 1) + " OF " + LofRanksData.RANKS.length + "  ·  " + cur.perks,
			ox + PAD + 64, oy + HERO_Y + 46, LofTheme.TEXT_DIM);

		// status pill, right-aligned
		final String pill;
		final Color pillCol;
		if (next == null)
		{
			pill = "THE REALM IS YOURS";
			pillCol = LofTheme.GOLD;
		}
		else if (canBuy)
		{
			pill = "READY TO RANK UP";
			pillCol = LofTheme.GOLD;
		}
		else
		{
			pill = fmt(next.cost - coins) + " TO GO";
			pillCol = LofTheme.TEXT_DIM;
		}
		final FontMetrics fm = g.getFontMetrics();
		LofTheme.pill(g, fm, pill, ox + WIN_W - PAD, oy + HERO_Y + 18, pillCol);
	}

	private void drawCoinBar(Graphics2D g, int ox, int oy, LofRanksData.Rank next, long coins)
	{
		final int barW = WIN_W - 2 * PAD;
		g.setFont(FontManager.getRunescapeSmallFont());
		final String label = next != null ? "COIN TOWARD " + next.name : "THE LADDER IS CLIMBED";
		LofTheme.shadowText(g, label, ox + PAD, oy + BAR_LABEL_Y, LofTheme.GOLD_DIM);
		final String value = next != null ? fmt(coins) + " / " + fmt(next.cost) : fmt(coins) + " coins carried";
		LofTheme.shadowText(g, value, ox + WIN_W - PAD - g.getFontMetrics().stringWidth(value), oy + BAR_LABEL_Y, LofTheme.GOLD);

		// track
		g.setColor(new Color(12, 10, 9));
		g.fillRoundRect(ox + PAD, oy + BAR_Y, barW, BAR_H, BAR_H / 2, BAR_H / 2);
		g.setColor(LofTheme.EMBER_DARK);
		g.drawRoundRect(ox + PAD, oy + BAR_Y, barW, BAR_H, BAR_H / 2, BAR_H / 2);

		final double frac = next == null ? 1.0 : Math.min(1.0, (double) coins / next.cost);
		final int fillW = (int) Math.round(barW * frac);
		if (fillW > 2)
		{
			if (frac >= 1.0)
			{
				g.setPaint(new GradientPaint(ox + PAD, 0, READY_FILL_A, ox + PAD + barW, 0, READY_FILL_B));
			}
			else
			{
				g.setPaint(new GradientPaint(ox + PAD, 0, LofTheme.EMBER_DARK, ox + PAD + barW, 0, LofTheme.EMBER));
			}
			g.fillRoundRect(ox + PAD, oy + BAR_Y, fillW, BAR_H, BAR_H / 2, BAR_H / 2);
		}
	}

	private void drawNextCard(Graphics2D g, int ox, int oy, int nextIdx)
	{
		final int x = ox + PAD, w = WIN_W - 2 * PAD;
		if (nextIdx < 0)
		{
			// King: the showcase becomes the victory lap.
			g.setColor(LofTheme.ROW);
			g.fillRoundRect(x, oy + CARD_Y, w, CARD_H, 10, 10);
			g.setFont(nextFont);
			final String s = "You rule the realm.";
			LofTheme.shadowText(g, s, ox + (WIN_W - g.getFontMetrics().stringWidth(s)) / 2, oy + CARD_Y + CARD_H / 2 + 6, LofTheme.GOLD);
			return;
		}
		final LofRanksData.Rank next = LofRanksData.RANKS[nextIdx];

		g.setPaint(new GradientPaint(0, oy + CARD_Y, LofTheme.alpha(LofTheme.EMBER, 30), 0, oy + CARD_Y + CARD_H, LofTheme.alpha(LofTheme.EMBER, 8)));
		g.fillRoundRect(x, oy + CARD_Y, w, CARD_H, 10, 10);
		g.setColor(LofTheme.alpha(LofTheme.EMBER, 140));
		g.drawRoundRect(x, oy + CARD_Y, w, CARD_H, 10, 10);

		drawEmblemAt(g, x + 12, oy + CARD_Y + (CARD_H - 60) / 2, 60, nextIdx, false);

		final int tx = x + 86;
		g.setFont(FontManager.getRunescapeSmallFont());
		LofTheme.shadowText(g, "NEXT RANK", tx, oy + CARD_Y + 18, LofTheme.EMBER);
		g.setFont(nextFont);
		LofTheme.shadowText(g, next.name, tx, oy + CARD_Y + 38, next.color != null ? next.color : LofTheme.TEXT);

		// unlock chips
		g.setFont(FontManager.getRunescapeSmallFont());
		final FontMetrics fm = g.getFontMetrics();
		int cx = tx, cy = oy + CARD_Y + 50;
		for (String unlock : next.unlocks)
		{
			final int cw = fm.stringWidth(unlock) + 16;
			if (cx + cw > x + w - 8)
			{
				cx = tx;
				cy += 19;
			}
			if (cy + 16 > oy + CARD_Y + CARD_H)
			{
				break; // never draw outside the card
			}
			g.setColor(new Color(0, 0, 0, 90));
			g.fillRoundRect(cx, cy, cw, 16, 16, 16);
			g.setColor(LofTheme.alpha(LofTheme.GOLD, 80));
			g.drawRoundRect(cx, cy, cw, 16, 16, 16);
			LofTheme.shadowText(g, unlock, cx + 8, cy + 12, LofTheme.TEXT);
			cx += cw + 6;
		}
	}

	private void drawTrack(Graphics2D g, int ox, int oy, Point mouse)
	{
		// connector line: gold-dim through the climbed rungs, faint beyond
		final Rectangle first = nodeRect(ox, oy, 0);
		final Rectangle last = nodeRect(ox, oy, LofRanksData.RANKS.length - 1);
		final Rectangle curNode = nodeRect(ox, oy, currentRank);
		final int lineY = oy + TRACK_LINE_Y;
		g.setColor(new Color(158, 149, 140, 60));
		g.fillRect(first.x + NODE_D / 2, lineY - 1, last.x - first.x, 2);
		g.setColor(LofTheme.alpha(LofTheme.GOLD_DIM, 200));
		g.fillRect(first.x + NODE_D / 2, lineY - 1, curNode.x - first.x, 2);

		int hoveredNode = -1;
		g.setFont(FontManager.getRunescapeSmallFont());
		final FontMetrics fm = g.getFontMetrics();
		for (int i = 0; i < LofRanksData.RANKS.length; i++)
		{
			final Rectangle r = nodeRect(ox, oy, i);
			final boolean held = i <= currentRank;
			final boolean isCur = i == currentRank;
			final boolean isNext = i == currentRank + 1;
			if (r.contains(mouse))
			{
				hoveredNode = i;
			}

			// node disc
			g.setColor(new Color(12, 10, 9));
			g.fillOval(r.x, r.y, NODE_D, NODE_D);
			final Color ring = isCur ? LofTheme.EMBER : isNext ? LofTheme.GOLD : held ? LofTheme.GOLD_DIM : new Color(158, 149, 140, 80);
			g.setColor(ring);
			final Stroke oldStroke = g.getStroke();
			g.setStroke(new BasicStroke(isCur || isNext ? 2.2f : 1.4f));
			g.drawOval(r.x, r.y, NODE_D, NODE_D);
			g.setStroke(oldStroke);

			drawEmblemAt(g, r.x + (NODE_D - 30) / 2, r.y + (NODE_D - 30) / 2, 30, i, !held && !isNext);

			// held tick
			if (held && !isCur)
			{
				g.setColor(LofTheme.GOLD);
				g.drawLine(r.x + NODE_D - 10, r.y + 4, r.x + NODE_D - 7, r.y + 7);
				g.drawLine(r.x + NODE_D - 7, r.y + 7, r.x + NODE_D - 2, r.y - 1);
			}
			if (isCur)
			{
				final String you = "YOU";
				LofTheme.shadowText(g, you, r.x + (NODE_D - fm.stringWidth(you)) / 2, r.y - 6, LofTheme.EMBER);
			}

			final String cost = LofRanksData.RANKS[i].costShort;
			final Color costCol = isCur || isNext ? LofTheme.GOLD : LofTheme.TEXT_DIM;
			LofTheme.shadowText(g, cost, r.x + (NODE_D - fm.stringWidth(cost)) / 2, r.y + NODE_D + 13, costCol);
		}

		if (hoveredNode >= 0 && hoveredNode != currentRank)
		{
			drawNodeTooltip(g, ox, oy, hoveredNode);
		}
	}

	private void drawNodeTooltip(Graphics2D g, int ox, int oy, int i)
	{
		final LofRanksData.Rank rank = LofRanksData.RANKS[i];
		g.setFont(FontManager.getRunescapeSmallFont());
		final FontMetrics fm = g.getFontMetrics();

		int w = fm.stringWidth(rank.name + " — " + rank.costShort) + 20;
		for (String u : rank.unlocks)
		{
			w = Math.max(w, fm.stringWidth("· " + u) + 20);
		}
		final int h = 22 + rank.unlocks.length * 14;
		final Rectangle node = nodeRect(ox, oy, i);
		int tx = node.x + NODE_D / 2 - w / 2;
		tx = Math.max(ox + 6, Math.min(tx, ox + WIN_W - w - 6));
		final int ty = node.y - h - 10;

		g.setColor(new Color(12, 10, 9, 245));
		g.fillRoundRect(tx, ty, w, h, 8, 8);
		g.setColor(LofTheme.GOLD_DIM);
		g.drawRoundRect(tx, ty, w, h, 8, 8);
		LofTheme.shadowText(g, rank.name + " — " + rank.costShort, tx + 10, ty + 15, rank.color != null ? rank.color : LofTheme.TEXT);
		int ly = ty + 29;
		for (String u : rank.unlocks)
		{
			LofTheme.shadowText(g, "· " + u, tx + 10, ly, LofTheme.TEXT_DIM);
			ly += 14;
		}
	}

	private void drawFooter(Graphics2D g, int ox, int oy, LofRanksData.Rank next, long coins, boolean canBuy, Point mouse)
	{
		g.setFont(FontManager.getRunescapeSmallFont());
		LofTheme.shadowText(g, "Coins carried: " + fmt(coins), ox + PAD, oy + WIN_H - 22, LofTheme.TEXT_DIM);

		if (next == null)
		{
			return; // King has nothing left to buy
		}
		final Rectangle b = buyRect(ox, oy);
		final boolean hov = canBuy && b.contains(mouse);

		if (canBuy)
		{
			// soft glow behind the lit button
			g.setColor(LofTheme.alpha(LofTheme.GOLD, hov ? 60 : 38));
			g.fillRoundRect(b.x - 3, b.y - 3, b.width + 6, b.height + 6, 12, 12);
		}
		g.setColor(canBuy ? LofTheme.alpha(LofTheme.GOLD, hov ? 60 : 30) : new Color(255, 255, 255, 8));
		g.fillRoundRect(b.x, b.y, b.width, b.height, 8, 8);
		final Stroke oldStroke = g.getStroke();
		g.setStroke(new BasicStroke(1.4f));
		g.setColor(canBuy ? LofTheme.GOLD : LofTheme.alpha(LofTheme.TEXT_DIM, 110));
		g.drawRoundRect(b.x, b.y, b.width, b.height, 8, 8);
		g.setStroke(oldStroke);

		g.setFont(FontManager.getRunescapeFont());
		final String label = "RANK UP — BECOME A " + next.name;
		final FontMetrics fm = g.getFontMetrics();
		LofTheme.shadowText(g, label, b.x + (b.width - fm.stringWidth(label)) / 2, b.y + b.height / 2 + 5,
			canBuy ? LofTheme.GOLD : LofTheme.TEXT_DIM);
	}

	// ---------------------------------- emblems ----------------------------------

	private void ensureEmblems()
	{
		if (emblemsLoaded)
		{
			return;
		}
		emblemsLoaded = true;
		final String[] names = {"peasant", "commoner", "squire", "soldier", "knight", "lord", "minister", "king"};
		for (int i = 0; i < names.length; i++)
		{
			try
			{
				emblems[i] = ImageUtil.loadImageResource(LofRanksOverlay.class, "rank_" + names[i] + ".png");
			}
			catch (Exception e)
			{
				emblems[i] = null; // missing resource — the slot simply draws empty
			}
		}
	}

	/**
	 * Draw rank [i]'s emblem PNG. Every slot ships with real art from the website's icon family
	 * (re-used deliberately until the bespoke rank emblems are generated — same filenames, so the
	 * new art drops straight in). A missing resource draws nothing rather than a placeholder.
	 */
	private void drawEmblemAt(Graphics2D g, int x, int y, int size, int i, boolean dim)
	{
		final BufferedImage img = emblems[i];
		if (img == null)
		{
			return;
		}
		final java.awt.Composite oldComposite = g.getComposite();
		if (dim)
		{
			g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.35f));
		}
		g.drawImage(img, x, y, size, size, null);
		g.setComposite(oldComposite);
	}


	@Override
	public boolean isWindowVisible()
	{
		return visible;
	}

	@Override
	public void hideWindow()
	{
		visible = false;
	}

	private Point mousePoint()
	{
		final net.runelite.api.Point m = client.getMouseCanvasPosition();
		return m == null ? new Point(-1, -1) : new Point(m.getX(), m.getY());
	}

	private static String fmt(long n)
	{
		return String.format("%,d", n);
	}
}
