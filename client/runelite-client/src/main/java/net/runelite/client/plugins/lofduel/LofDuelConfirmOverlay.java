/*
 * Fall of Varrock — Duel Arena CONFIRMATION screen (renderer + hit-testing).
 *
 * The third screen of the classic agreement flow, themed and drawn over the vanilla stake-confirm
 * interface (group 334): opponent name, combat level and INDIVIDUAL combat stats, the "Before the
 * duel starts:" consequence lines, one "During the duel:" line per agreed rule (cache-exact
 * wording), both stake grids with value + short K/M expansion, the opponent's worn gear when Show
 * Inventories is on, and Accept ("Wait…" during the change lockout) / Decline.
 *
 * Data channels (no custom packets):
 *  - varp 4685 (server DuelConfirmScreen): bit 0 open, rules in the DuelRulesClientMenu bit
 *    layout, bit 30 = Accept locked.
 *  - opponent stats: the "~LOFDUEL~stats|…" CONSOLE line, parsed by LofDuelPlugin.
 *  - item lists: confirm containers 90 (yours) / 90+32768 (theirs); Show-Inventories worn gear
 *    in container 4701. Values read from the 334:23/24 texts the server already sets.
 *  - Accept/Decline route through the SECURE TradeSession as "::lofstake a" / "::lofstake d".
 */
package net.runelite.client.plugins.lofduel;

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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.loftheme.LofModal;
import net.runelite.client.plugins.loftheme.LofTheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class LofDuelConfirmOverlay extends Overlay
{
	/** Must match server DuelConfirmScreen.STATE_VARP. */
	static final int STATE_VARP = 4685;

	/** Confirm containers the trade screen sends: yours, and theirs ('invother' = +32768). */
	private static final int STAKE_INV = 90;
	private static final int STAKE_INV_OTHER = 90 + 32768;

	/** Show-Inventories containers (server DuelConfirmScreen.OPP_*_KEY). */
	private static final int OPP_WORN_INV = 4701;

	// Vanilla confirm interface (group 334) — the server-set texts this overlay re-renders.
	private static final int CONFIRM_GROUP = 334;
	private static final int STATUS_CHILD = 4;
	private static final int YOUR_VALUE_CHILD = 23;
	private static final int THEIR_VALUE_CHILD = 24;

	// Packed-varp bit layout — MUST match DuelRulesClientMenu/DuelConfirmScreen.
	private static final int RULE_BITS = 16;
	private static final int SLOT_BIT_BASE = RULE_BITS + 3;
	private static final int SLOT_COUNT = 11;
	private static final int LOCK_BIT = 30;

	// Rule indices (DuelRulesClientMenu.RULES order) → cache-exact confirm lines (research §2.2),
	// rendered in the classic confirm-screen order: forfeit/movement/switch first, then styles,
	// then supplies, then the rest.
	private static final int[] LINE_ORDER = { 7, 6, 12, 14, 15, 1, 0, 2, 5, 4, 3, 10, 13, 8, 9, 11 };
	private static final String[] RULE_LINES = {
		"You cannot use Melee attacks.",              // 0
		"You cannot use Ranged attacks.",             // 1
		"You cannot use Magic attacks.",              // 2
		"You cannot use Prayer.",                     // 3
		"You cannot use food.",                       // 4
		"You cannot use potions or drinks.",          // 5
		"You cannot move.",                           // 6
		"You cannot forfeit the duel.",               // 7
		"Weapons restricted: whip only.",             // 8
		"Weapons restricted: dragon dagger only.",    // 9
		"You can only attack with 'fun' weapons.",    // 10
		"Companions may fight too (up to 4v4).",      // 11
		"You cannot switch weapons.",                 // 12
		"You cannot use special attacks.",            // 13
		"You can see your opponent's inventory.",     // 14
		"There will be obstacles in the arena.",      // 15
	};
	private static final int SHOW_INV_RULE = 14;

	/** Paper-doll slot names, DuelRulesClientMenu.SLOT_IDS order — the forbidden-gear line. */
	private static final String[] SLOT_NAMES = { "head", "cape", "neck", "weapon", "body", "shield", "legs", "hands", "feet", "ring", "ammo" };

	// hit-test result codes
	static final int OUTSIDE = -1;
	static final int INSIDE = 0;
	static final int DECLINE = 1;
	static final int ACCEPT = 2;

	// Design-system standard modal.
	static final int WIN_W = LofModal.W;
	static final int WIN_H = LofModal.H;
	private static final int WIN_ARC = 14;
	private static final int TITLE_H = 38;
	private static final int PAD = 12;
	private static final int BTN_H = 32;
	// Left column: opponent + rule lines. Right column: the two stake grids.
	private static final int GRID_X = 248;
	private static final int GRID_COLS = 4;
	private static final int GRID_ROWS = 7;
	private static final int SLOT = 24;
	private static final int SLOT_GAP = 1;
	private static final int GRID_W = GRID_COLS * SLOT + (GRID_COLS - 1) * SLOT_GAP; // 99
	private static final int GRID_TOP = TITLE_H + 36;
	private static final int LINE_H = 13;
	private static final int LEFT_MAX_Y = WIN_H - PAD - BTN_H - 20; // truncate rule lines here

	private static final Color GREEN = new Color(110, 205, 110);
	private static final Pattern VALUE_RX = Pattern.compile("([\\d,]+)\\s*coins");

	private final Client client;
	private final LofDuelConfig config;
	private final LofDuelPlugin plugin;
	private final ItemManager itemManager;

	@Inject
	private LofDuelConfirmOverlay(Client client, LofDuelConfig config, LofDuelPlugin plugin, ItemManager itemManager)
	{
		this.client = client;
		this.config = config;
		this.plugin = plugin;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
	}

	// Client-thread state published for the mouse thread (same pattern as the rules overlay).
	private volatile boolean showingCached;
	private volatile boolean lockedCached;
	private volatile LofModal.Placement placement;
	// Inventory panel bounds published by render(); hitTest passes clicks there through so the
	// real inventory stays inspectable/clickable next to the confirm screen (Show Inventories).
	private volatile Rectangle inventoryRect;

	/** Cached — safe to call from the mouse thread. */
	boolean isShowing()
	{
		return showingCached;
	}

	/** Cached — Accept clicks are swallowed while the server holds the change lockout. */
	boolean isAcceptLocked()
	{
		return lockedCached;
	}

	/** The live client-thread check — only call from render(). */
	private boolean computeShowing()
	{
		return config.enabled()
			&& client.getGameState() == GameState.LOGGED_IN
			&& (client.getVarpValue(STATE_VARP) & 0x1) != 0;
	}

	private Rectangle acceptRect(int ox, int oy) { return new Rectangle(ox + WIN_W - PAD - 210, oy + WIN_H - PAD - BTN_H, 100, BTN_H); }
	private Rectangle declineRect(int ox, int oy) { return new Rectangle(ox + WIN_W - PAD - 100, oy + WIN_H - PAD - BTN_H, 100, BTN_H); }

	int hitTest(Point canvas)
	{
		if (!isShowing()) return OUTSIDE;
		final LofModal.Placement place = placement;
		if (place == null) return OUTSIDE;
		final Point p = place.toLocal(canvas);
		final int ox = place.ox, oy = place.oy;
		if (!new Rectangle(ox, oy, WIN_W, WIN_H).contains(p)) return OUTSIDE;
		if (acceptRect(ox, oy).contains(p)) return ACCEPT;
		if (declineRect(ox, oy).contains(p)) return DECLINE;
		final Rectangle inv = inventoryRect;
		if (inv != null && inv.contains(canvas)) return OUTSIDE;
		return INSIDE;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		final boolean showing = computeShowing();
		showingCached = showing;
		if (!showing) return null;

		final Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		final java.awt.Rectangle selfBounds = getBounds();
		g.translate(-selfBounds.x, -selfBounds.y);

		// Beside-inventory placement: the confirm screen is exactly where a player wants to eyeball
		// their own (and the opponent's) real inventory before accepting.
		final LofModal.Placement place = LofModal.beginWindowBesideInventory(g, client, WIN_W, WIN_H);
		placement = place;
		inventoryRect = LofModal.inventoryBounds(client);

		final int state = client.getVarpValue(STATE_VARP);
		final boolean locked = (state & (1 << LOCK_BIT)) != 0;
		lockedCached = locked;
		final int ox = place.ox, oy = place.oy;
		final Point mouse = place.toLocal(mousePoint());

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
		LofTheme.shadowText(g, "Duel Arena - Confirm", titleX, oy + 25, LofTheme.GOLD);

		// ── left column: opponent, consequences, rules ──
		int y = oy + TITLE_H + 18;
		final int lx = ox + PAD + 2;
		g.setFont(FontManager.getRunescapeFont());
		final LofDuelPlugin.OpponentStats opp = plugin.opponentStats();
		LofTheme.shadowText(g, opp == null ? "Opponent" : opp.name + "  (level " + opp.combat + ")", lx, y, LofTheme.GOLD);
		y += LINE_H + 2;
		g.setFont(FontManager.getRunescapeSmallFont());
		if (opp != null && opp.stats.length >= 7)
		{
			LofTheme.shadowText(g, "Att " + opp.stats[0] + "   Str " + opp.stats[1] + "   Def " + opp.stats[2] + "   HP " + opp.stats[3], lx, y, LofTheme.TEXT);
			y += LINE_H;
			LofTheme.shadowText(g, "Rng " + opp.stats[4] + "   Mag " + opp.stats[5] + "   Pray " + opp.stats[6], lx, y, LofTheme.TEXT);
			y += LINE_H;
		}

		// Show Inventories: the opponent's worn gear, identities only.
		if ((state & (1 << (SHOW_INV_RULE + 1))) != 0)
		{
			y += 4;
			LofTheme.shadowText(g, "OPPONENT'S WORN GEAR", lx, y, LofTheme.GOLD_DIM);
			y += 4;
			y = drawGearStrip(g, lx, y, client.getItemContainer(OPP_WORN_INV));
		}

		y += 6;
		LofTheme.shadowText(g, "Before the duel starts:", lx, y, LofTheme.GOLD_DIM);
		y += LINE_H;
		for (String line : beforeLines(state))
		{
			LofTheme.shadowText(g, "- " + line, lx, y, LofTheme.TEXT_DIM);
			y += LINE_H;
		}
		y += 4;
		LofTheme.shadowText(g, "During the duel:", lx, y, LofTheme.GOLD_DIM);
		y += LINE_H;
		final List<String> during = duringLines(state);
		for (int i = 0; i < during.size(); i++)
		{
			if (y > oy + LEFT_MAX_Y && i < during.size() - 1)
			{
				LofTheme.shadowText(g, "+ " + (during.size() - i) + " more (see rules screen)", lx, y, LofTheme.TEXT_DIM);
				break;
			}
			LofTheme.shadowText(g, "- " + during.get(i), lx, y, LofTheme.TEXT);
			y += LINE_H;
		}

		// ── right column: the two locked stakes ──
		g.setFont(FontManager.getRunescapeSmallFont());
		LofTheme.shadowText(g, "YOUR STAKE", ox + GRID_X, oy + TITLE_H + 20, LofTheme.GOLD_DIM);
		LofTheme.shadowText(g, "OPPONENT", ox + GRID_X + GRID_W + 10, oy + TITLE_H + 20, LofTheme.GOLD_DIM);
		drawGrid(g, ox + GRID_X, oy + GRID_TOP, client.getItemContainer(STAKE_INV));
		drawGrid(g, ox + GRID_X + GRID_W + 10, oy + GRID_TOP, client.getItemContainer(STAKE_INV_OTHER));
		int valY = oy + GRID_TOP + GRID_ROWS * (SLOT + SLOT_GAP) + 12;
		final String yours = valueLine("You risk: ", client.getWidget(CONFIRM_GROUP, YOUR_VALUE_CHILD));
		final String theirs = valueLine("They risk: ", client.getWidget(CONFIRM_GROUP, THEIR_VALUE_CHILD));
		if (yours != null) { LofTheme.shadowText(g, yours, ox + GRID_X, valY, GREEN); valY += LINE_H; }
		if (theirs != null) { LofTheme.shadowText(g, theirs, ox + GRID_X, valY, GREEN); }

		// status (from the vanilla confirm screen's text) + buttons
		final String status = plainText(client.getWidget(CONFIRM_GROUP, STATUS_CHILD));
		LofTheme.shadowText(g, status, ox + PAD + 2, oy + WIN_H - PAD - BTN_H - 6, LofTheme.TEXT_DIM);
		g.setFont(FontManager.getRunescapeBoldFont());
		final Rectangle acc = acceptRect(ox, oy), dec = declineRect(ox, oy);
		button(g, acc, locked ? "Wait..." : "Accept", locked ? LofTheme.GOLD_DIM : LofTheme.GOLD, acc.contains(mouse) && !locked);
		button(g, dec, "Decline", LofTheme.EMBER, dec.contains(mouse));

		LofModal.endWindow(g, place);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(WIN_W, WIN_H);
	}

	/** "Before the duel starts:" consequence lines — what the normalization will do. */
	private static List<String> beforeLines(int state)
	{
		final List<String> lines = new ArrayList<>();
		lines.add("Boosted stats will be restored.");
		lines.add("Existing prayers will be stopped.");
		boolean gearOff = false;
		for (int i = 0; i < SLOT_COUNT; i++)
		{
			if ((state & (1 << (i + SLOT_BIT_BASE))) != 0)
			{
				gearOff = true;
				break;
			}
		}
		if (gearOff || (state & ((1 << 9) | (1 << 10) | (1 << 11))) != 0) // slots or whip/DDS/fun whitelists
		{
			lines.add("Restricted worn items will be taken off.");
		}
		return lines;
	}

	/** "During the duel:" — one cache-exact line per agreed rule + the forbidden-gear summary. */
	private static List<String> duringLines(int state)
	{
		final List<String> lines = new ArrayList<>();
		for (int idx : LINE_ORDER)
		{
			if ((state & (1 << (idx + 1))) != 0)
			{
				lines.add(RULE_LINES[idx]);
			}
		}
		final List<String> off = new ArrayList<>();
		for (int i = 0; i < SLOT_COUNT; i++)
		{
			if ((state & (1 << (i + SLOT_BIT_BASE))) != 0)
			{
				off.add(SLOT_NAMES[i]);
			}
		}
		if (off.size() == SLOT_COUNT)
		{
			lines.add("No gear may be worn at all.");
		}
		else if (!off.isEmpty())
		{
			lines.add("Forbidden gear: " + String.join(", ", off) + ".");
		}
		if (lines.isEmpty())
		{
			lines.add("No special rules - a straight fight.");
		}
		return lines;
	}

	/** A 4x7 stake grid from an item container (null-safe: empty grid when absent). */
	private void drawGrid(Graphics2D g, int x, int y, ItemContainer inv)
	{
		final Item[] items = inv == null ? null : inv.getItems();
		for (int i = 0; i < GRID_COLS * GRID_ROWS; i++)
		{
			final int cx = x + (i % GRID_COLS) * (SLOT + SLOT_GAP);
			final int cy = y + (i / GRID_COLS) * (SLOT + SLOT_GAP);
			g.setColor(LofTheme.ROW);
			g.fillRoundRect(cx, cy, SLOT, SLOT, 4, 4);
			g.setColor(LofTheme.alpha(LofTheme.EMBER, 30));
			g.drawRoundRect(cx, cy, SLOT - 1, SLOT - 1, 4, 4);
			if (items != null && i < items.length && items[i] != null)
			{
				final int id = items[i].getId();
				final int qty = items[i].getQuantity();
				if (id > 0 && qty > 0)
				{
					final BufferedImage img = itemManager.getImage(id, qty, qty > 1);
					if (img != null)
					{
						g.drawImage(img, cx + 1, cy + 1, SLOT - 2, SLOT - 2, null);
					}
				}
			}
		}
	}

	/** The opponent's worn gear as two icon rows (identities only — quantities are masked). */
	private int drawGearStrip(Graphics2D g, int x, int y, ItemContainer inv)
	{
		final int sz = 18, perRow = 7;
		final Item[] items = inv == null ? new Item[0] : inv.getItems();
		final List<Item> worn = new ArrayList<>();
		for (Item it : items)
		{
			if (it != null && it.getId() > 0)
			{
				worn.add(it);
			}
		}
		if (worn.isEmpty())
		{
			LofTheme.shadowText(g, "(nothing worn)", x, y + 10, LofTheme.TEXT_DIM);
			return y + 14;
		}
		for (int i = 0; i < worn.size(); i++)
		{
			final int cx = x + (i % perRow) * (sz + 2);
			final int cy = y + (i / perRow) * (sz + 2);
			g.setColor(LofTheme.ROW);
			g.fillRoundRect(cx, cy, sz, sz, 3, 3);
			final BufferedImage img = itemManager.getImage(worn.get(i).getId());
			if (img != null)
			{
				g.drawImage(img, cx + 1, cy + 1, sz - 2, sz - 2, null);
			}
		}
		return y + ((worn.size() + perRow - 1) / perRow) * (sz + 2) + 4;
	}

	/** Parse the server's value text and append a short K/M expansion — "You risk: 3,000,000 (3M)". */
	private static String valueLine(String prefix, Widget w)
	{
		final String t = plainText(w);
		final Matcher m = VALUE_RX.matcher(t);
		if (!m.find())
		{
			return null;
		}
		final String grouped = m.group(1);
		final long v = Long.parseLong(grouped.replace(",", ""));
		final String shortForm = v >= 10_000_000 ? (v / 1_000_000) + "M"
			: v >= 100_000 ? (v / 1_000) + "K"
			: null;
		return prefix + grouped + (shortForm == null ? "" : " (" + shortForm + ")");
	}

	private static String plainText(Widget w)
	{
		final String t = w == null || w.getText() == null ? "" : w.getText();
		return t.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
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

	private Point mousePoint()
	{
		final net.runelite.api.Point m = client.getMouseCanvasPosition();
		return m == null ? new Point(-1, -1) : new Point(m.getX(), m.getY());
	}
}
