/*
 * Fall of Varrock — Mire Dispenser window (renderer + hit-testing).
 *
 * Two states:
 *  - Not attuned: the attunement pitch (one-time 150,000-coin fee) over a dimmed loot preview,
 *    with an ATTUNE button that lights gold when the carried coins cover the fee.
 *  - Attuned: stat strip (lap streak · loot multiplier with its ×1→×5 progress bar · banked
 *    laps), the live loot table (item icons via ItemManager, quantities pre-scaled by the current
 *    multiplier, drop-chance pills), and a CLAIM button that lights when laps are banked.
 *
 * The loot table mirrors the server's (content/skills/agility AgilityPlugin.lootTable) — keep the
 * two in step. Coins are read from the inventory container like lofranks.
 */
package net.runelite.client.plugins.lofmire;

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
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.loftheme.LofModal;
import net.runelite.client.plugins.loftheme.LofTheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class LofMireOverlay extends Overlay
{
	// hit-test result codes
	static final int OUTSIDE = -1;
	static final int INSIDE = 0;
	static final int CLOSE = 1;
	static final int ACTION = 2; // the footer button — Attune when unattuned, else Claim

	/** One supply row of the dispenser table — mirror of the server lootTable. */
	private static final class Loot
	{
		final int itemId;
		final String name;
		final int min;
		final int max;
		final int weight;

		Loot(int itemId, String name, int min, int max, int weight)
		{
			this.itemId = itemId;
			this.name = name;
			this.min = min;
			this.max = max;
			this.weight = weight;
		}
	}

	private static final Loot[] LOOT = {
		new Loot(ItemID.ANGLERFISH, "Anglerfish", 3, 5, 30),
		new Loot(ItemID.COOKED_KARAMBWAN, "Cooked karambwan", 3, 5, 25),
		new Loot(ItemID.SUPER_RESTORE4, "Super restore(4)", 1, 2, 20),
		new Loot(ItemID.MANTA_RAY, "Manta ray", 2, 4, 15),
		new Loot(ItemID.BLIGHTED_TELEPORT_SPELL_SACK, "Teleport spell sack", 1, 3, 7),
		new Loot(ItemID.MARK_OF_GRACE, "Mark of grace", 1, 1, 3),
	};

	// Mirror of server MireDispenser constants.
	private static final int ATTUNE_FEE = 150_000;
	private static final int MAX_BANK = 10;
	private static final int STREAK_CAP = 60;
	private static final int COINS_MIN = 2_000;
	private static final int COINS_MAX = 5_000;
	private static final int COINS_ID = 995;

	// Design-system standard modal (480x400 — docs/overlay-design-system.md §6A).
	private static final int WIN_W = LofModal.W;
	private static final int WIN_H = LofModal.H;
	private static final int WIN_ARC = 14;
	private static final int TITLE_H = 38;
	private static final int PAD = 14;

	private static final int STATS_Y = TITLE_H + 12;   // 50
	private static final int STATS_H = 56;
	private static final int LOOT_LABEL_Y = STATS_Y + STATS_H + 20; // baseline of the section label
	private static final int LOOT_Y = LOOT_LABEL_Y + 8;
	private static final int ROW_H = 25;
	private static final int BTN_W = 130;
	private static final int BTN_H = 32;

	private final Client client;
	private final ItemManager itemManager;
	private final LofMireConfig config;

	private boolean visible;
	private boolean attuned;
	private int bank;
	private int streak;

	@Inject
	private LofMireOverlay(Client client, ItemManager itemManager, LofMireConfig config)
	{
		this.client = client;
		this.itemManager = itemManager;
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

	void setState(boolean attuned, int bank, int streak)
	{
		this.attuned = attuned;
		this.bank = bank;
		this.streak = streak;
	}

	boolean isAttuned()
	{
		return attuned;
	}

	// Coins carried, sampled on the CLIENT thread by render(). Reading the inventory container off
	// the client thread returns null (→ 0 coins), so actionable() was false and hitTest answered
	// INSIDE — the ATTUNE click was swallowed while the button was drawn lit. The click path must
	// read ONLY this cached value. Same fix, same reason, as LofShopTabsOverlay's cached showing.
	private volatile long coinsCached;

	/** The footer button only takes clicks when its action could actually go through. */
	boolean actionable()
	{
		return attuned ? bank > 0 : coinsCached >= ATTUNE_FEE;
	}

	/** Mirror of server MireDispenser.multiplier — ×1 → ×5 linearly, capped at STREAK_CAP laps. */
	private double multiplier()
	{
		return 1.0 + (Math.max(Math.min(streak, STREAK_CAP), 1) - 1) * 4.0 / (STREAK_CAP - 1);
	}

	// Placement single-sourced in LofModal (§6A) — same anchor as every modal.
	private int originX() { return LofModal.originX(client, WIN_W); }

	private int originY() { return LofModal.originY(client, WIN_H); }

	private Rectangle closeRect(int ox, int oy)
	{
		return new Rectangle(ox + WIN_W - 30, oy + 9, 20, 20);
	}

	private Rectangle actionRect(int ox, int oy)
	{
		return new Rectangle(ox + WIN_W - PAD - BTN_W, oy + WIN_H - PAD - BTN_H, BTN_W, BTN_H);
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
		if (actionable() && actionRect(ox, oy).contains(p))
		{
			return ACTION;
		}
		return INSIDE;
	}

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

		coinsCached = coinsCarried(); // publish for the mouse thread (actionable())

		final Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		final Rectangle selfBounds = getBounds();
		g.translate(-selfBounds.x, -selfBounds.y);

		final int ox = originX(), oy = originY();
		final Point mouse = mousePoint();

		LofTheme.panel(g, ox, oy, WIN_W, WIN_H, WIN_ARC);
		drawHeader(g, ox, oy, mouse);

		if (attuned)
		{
			drawStatStrip(g, ox, oy);
		}
		else
		{
			drawAttunePitch(g, ox, oy);
		}

		drawLootTable(g, ox, oy, attuned);
		drawFooter(g, ox, oy, mouse);

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
		LofTheme.shadowText(g, "Mire Dispenser", titleX, oy + 25, LofTheme.GOLD);
		g.setFont(FontManager.getRunescapeSmallFont());
		final String sub = attuned
			? "Mire Run  ·  streak ×" + fmtMult(multiplier())
			: "Mire Run  ·  not attuned";
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

	/** Attuned top strip: three stat tiles — lap streak, loot multiplier (+bar), banked laps. */
	private void drawStatStrip(Graphics2D g, int ox, int oy)
	{
		final int w = (WIN_W - 2 * PAD - 2 * 10) / 3;
		final String[] labels = {"LAP STREAK", "LOOT MULTIPLIER", "BANKED LAPS"};
		final String[] values = {
			Integer.toString(streak),
			"×" + fmtMult(multiplier()),
			bank + " / " + MAX_BANK,
		};
		final Color[] valueCols = {
			LofTheme.TEXT,
			streak >= STREAK_CAP ? LofTheme.LAVA : LofTheme.GOLD,
			bank > 0 ? LofTheme.GOLD : LofTheme.TEXT_DIM,
		};
		for (int i = 0; i < 3; i++)
		{
			final int x = ox + PAD + i * (w + 10);
			final int y = oy + STATS_Y;
			g.setColor(LofTheme.ROW);
			g.fillRoundRect(x, y, w, STATS_H, 8, 8);
			g.setColor(new Color(255, 255, 255, 15));
			g.drawRoundRect(x, y, w, STATS_H, 8, 8);

			g.setFont(FontManager.getRunescapeSmallFont());
			FontMetrics fm = g.getFontMetrics();
			LofTheme.shadowText(g, labels[i], x + (w - fm.stringWidth(labels[i])) / 2, y + 16, LofTheme.GOLD_DIM);

			g.setFont(FontManager.getRunescapeBoldFont());
			fm = g.getFontMetrics();
			LofTheme.shadowText(g, values[i], x + (w - fm.stringWidth(values[i])) / 2, y + 36, valueCols[i]);

			if (i == 1)
			{
				// the ×1→×5 progress bar under the multiplier value
				final int bx = x + 12, bw = w - 24, by = y + STATS_H - 12;
				g.setColor(LofTheme.PANEL_OPAQUE);
				g.fillRoundRect(bx, by, bw, 5, 5, 5);
				final int fill = (int) (bw * Math.min(streak, STREAK_CAP) / (double) STREAK_CAP);
				g.setColor(streak >= STREAK_CAP ? LofTheme.LAVA : LofTheme.EMBER);
				g.fillRoundRect(bx, by, Math.max(fill, streak > 0 ? 4 : 0), 5, 5, 5);
				g.setColor(LofTheme.EMBER_DARK);
				g.drawRoundRect(bx, by, bw, 5, 5, 5);
			}
		}
	}

	/** Unattuned top strip: the one-time-fee pitch in place of the stat tiles. */
	private void drawAttunePitch(Graphics2D g, int ox, int oy)
	{
		final int x = ox + PAD, w = WIN_W - 2 * PAD, y = oy + STATS_Y;
		g.setColor(LofTheme.ROW);
		g.fillRoundRect(x, y, w, STATS_H, 8, 8);
		g.setColor(LofTheme.alpha(LofTheme.GOLD, 60));
		g.drawRoundRect(x, y, w, STATS_H, 8, 8);

		g.setFont(FontManager.getRunescapeFont());
		drawCentred(g, ox, y + 22, "Attune to the dispenser to earn lap rewards.", LofTheme.TEXT);
		g.setFont(FontManager.getRunescapeSmallFont());
		final long coins = coinsCarried();
		drawCentred(g, ox, y + 42,
			"One-time fee: " + fmt(ATTUNE_FEE) + " coins  ·  you carry " + fmt(coins),
			coins >= ATTUNE_FEE ? new Color(110, 205, 110) : new Color(255, 138, 117));
	}

	/** The loot table — coins row (every lap) + the weighted supply rows, one of which drops per lap. */
	private void drawLootTable(Graphics2D g, int ox, int oy, boolean active)
	{
		g.setFont(FontManager.getRunescapeSmallFont());
		LofTheme.shadowText(g, "DISPENSER LOOT — PER BANKED LAP", ox + PAD, oy + LOOT_LABEL_Y, LofTheme.GOLD_DIM);

		final java.awt.Composite oldComposite = g.getComposite();
		if (!active)
		{
			g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.45f));
		}

		final double mult = active ? multiplier() : 1.0;
		final int x = ox + PAD, w = WIN_W - 2 * PAD;
		int y = oy + LOOT_Y;

		drawLootRow(g, x, y, w, COINS_ID, "Coins", scaled(COINS_MIN, mult), scaled(COINS_MAX, mult), "always", LofTheme.GOLD);
		y += ROW_H;

		int totalWeight = 0;
		for (Loot l : LOOT)
		{
			totalWeight += l.weight;
		}
		for (Loot l : LOOT)
		{
			final String chance = (100 * l.weight / totalWeight) + "%";
			drawLootRow(g, x, y, w, l.itemId, l.name, scaled(l.min, mult), scaled(l.max, mult), chance, LofTheme.TEXT_DIM);
			y += ROW_H;
		}

		g.setComposite(oldComposite);
	}

	private void drawLootRow(Graphics2D g, int x, int y, int w, int itemId, String name, int min, int max, String tag, Color tagCol)
	{
		g.setColor(LofTheme.ROW);
		g.fillRoundRect(x, y, w, ROW_H - 3, 6, 6);

		final BufferedImage icon = itemManager.getImage(itemId, Math.max(min, 1), min > 1 || itemId == COINS_ID);
		if (icon != null)
		{
			// item sprites are 36x32 — draw at half size, vertically centred in the row
			g.drawImage(icon, x + 6, y + (ROW_H - 3 - 16) / 2, 18, 16, null);
		}

		g.setFont(FontManager.getRunescapeFont());
		final int baseline = y + (ROW_H - 3) / 2 + 5;
		LofTheme.shadowText(g, name, x + 32, baseline, LofTheme.TEXT);

		final String qty = min == max ? fmt(min) : fmt(min) + "–" + fmt(max);
		g.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics fm = g.getFontMetrics();
		LofTheme.shadowText(g, qty, x + w - 110 - fm.stringWidth(qty), baseline, LofTheme.GOLD);
		LofTheme.pill(g, fm, tag, x + w - 8, baseline, tagCol);
	}

	private void drawFooter(Graphics2D g, int ox, int oy, Point mouse)
	{
		g.setFont(FontManager.getRunescapeSmallFont());
		final String status;
		if (!attuned)
		{
			status = "Run laps of the Mire Run — the dispenser banks each one until you claim.";
		}
		else if (bank > 0)
		{
			status = "Claim pays out every banked lap at your current streak multiplier.";
		}
		else
		{
			status = "Complete a lap to bank loot. The streak breaks after 5 idle minutes or logout.";
		}
		LofTheme.shadowText(g, status, ox + PAD, oy + WIN_H - PAD - BTN_H / 2 + 4, LofTheme.TEXT_DIM);

		final Rectangle br = actionRect(ox, oy);
		final boolean active = actionable();
		final boolean hov = active && br.contains(mouse);
		final Color accent = active ? LofTheme.GOLD : LofTheme.GOLD_DIM;
		g.setColor(hov ? LofTheme.alpha(LofTheme.GOLD, 60) : LofTheme.alpha(accent, active ? 34 : 16));
		g.fillRoundRect(br.x, br.y, br.width, br.height, 8, 8);
		final Stroke oldStroke = g.getStroke();
		g.setStroke(new BasicStroke(1.4f));
		g.setColor(LofTheme.alpha(accent, active ? 200 : 90));
		g.drawRoundRect(br.x, br.y, br.width, br.height, 8, 8);
		g.setStroke(oldStroke);

		g.setFont(FontManager.getRunescapeFont());
		final String label = attuned
			? (bank > 0 ? "CLAIM " + bank + " LAP" + (bank > 1 ? "S" : "") : "NOTHING BANKED")
			: "ATTUNE — " + fmt(ATTUNE_FEE);
		final FontMetrics fm = g.getFontMetrics();
		LofTheme.shadowText(g, label, br.x + (br.width - fm.stringWidth(label)) / 2, br.y + br.height / 2 + 5,
			active ? LofTheme.GOLD : LofTheme.TEXT_DIM);
	}

	private static int scaled(int base, double mult)
	{
		return Math.max((int) (base * mult), 1);
	}

	private void drawCentred(Graphics2D g, int ox, int y, String text, Color col)
	{
		final FontMetrics fm = g.getFontMetrics();
		LofTheme.shadowText(g, text, ox + (WIN_W - fm.stringWidth(text)) / 2, y, col);
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

	private static String fmtMult(double m)
	{
		return String.format("%.1f", m);
	}
}
