/*
 * Fall of Varrock — Duel Arena rules overlay (renderer + hit-testing).
 *
 * A faithful rebuild of the classic Duel Arena *Options* screen, themed and drawn client-side (cache
 * interfaces crash our client). Two controls, like the original: the combat-rule checkboxes (left)
 * and the equipment paper-doll (right — click a worn slot to forbid it), plus a "Load last duel"
 * preset. The server (DuelRulesClientMenu) publishes the shared state in one packed varp 4630; this
 * overlay reads it each frame and sends clicks back as "::duel <action>". RULES + SLOT order MUST
 * match DuelRulesClientMenu.
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
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.plugins.loftheme.LofModal;
import net.runelite.client.plugins.loftheme.LofTheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class LofDuelOverlay extends Overlay
{
	/** Must match server DuelRulesClientMenu.STATE_VARP. */
	static final int STATE_VARP = 4630;

	// hit-test result codes
	static final int OUTSIDE = -1;
	static final int INSIDE = 0;
	static final int DECLINE = 1;
	static final int ACCEPT = 2;
	static final int LOAD = 3;      // "Last" — the last-agreed rules
	static final int SAVE = 4;      // save personal preset
	static final int LOAD_SAVED = 5; // load personal preset
	static final int WHIP = 6;      // official Whip preset
	static final int BOXING = 7;    // official Boxing preset
	static final int RULE_BASE = 100; // + rule index (0..13)
	static final int SLOT_BASE = 200; // + doll slot index (0..10)

	/** MUST match DuelRulesClientMenu.RULES. */
	private static final String[] RULES = {
		"No Melee", "No Ranged", "No Magic", "No Prayer", "No Food", "No Drinks",
		"No Movement", "No Forfeit", "Whip only", "DDS only", "Fun weapons", "Allow companions",
		"No Weapon Switch", "No Special Attacks",
	};

	// Packed-varp bit layout — MUST match DuelRulesClientMenu: bit 0 open, rules at bit 1 with
	// 16 bits reserved, then the two accept bits, then the 11 doll-slot bits.
	private static final int RULE_BITS = 16;
	private static final int ACCEPT_MINE_BIT = RULE_BITS + 1;
	private static final int ACCEPT_THEIRS_BIT = RULE_BITS + 2;
	private static final int SLOT_BIT_BASE = RULE_BITS + 3;

	/** Paper-doll slot labels, in DuelRulesClientMenu.SLOT_IDS order. */
	private static final String[] SLOTS = { "Head", "Cape", "Neck", "Weap", "Body", "Shld", "Legs", "Hand", "Foot", "Ring", "Ammo" };
	// Grid position (col,row) of each doll slot — the classic worn-equipment layout (3 columns).
	private static final int[] DOLL_COL = { 1, 0, 1, 0, 1, 2, 1, 0, 1, 2, 2 };
	private static final int[] DOLL_ROW = { 0, 1, 1, 2, 2, 2, 3, 4, 4, 4, 1 };

	// Shared window size (design-system standard modal — matches the stake overlay). See
	// docs/overlay-design-system.md.
	static final int WIN_W = LofModal.W;
	static final int WIN_H = LofModal.H;
	private static final int WIN_ARC = 14;
	private static final int TITLE_H = 38;
	private static final int PAD = 12;
	// Rules column + paper-doll + preset column are tightened to fit the short standard window
	// (everything must show in full — rules can't be hidden and the doll isn't a list, so this
	// window crams rather than scrolls).
	private static final int RULE_TOP = TITLE_H + 24;
	private static final int RULE_H = 14;
	private static final int RULE_W = 226;
	private static final int DOLL_X = 288;
	private static final int DOLL_TOP = TITLE_H + 30;
	private static final int SLOT_SZ = 30;
	private static final int SLOT_GAP = 4;
	private static final int BTN_H = 32;
	// Preset button column, right of the paper-doll.
	private static final int PRESET_X = 394;
	private static final int PRESET_W = 74;
	private static final int PRESET_H = 22;
	private static final int PRESET_GAP = 5;

	private static final Color GREEN = new Color(110, 205, 110);

	private final Client client;
	private final LofDuelConfig config;

	@Inject
	private LofDuelOverlay(Client client, LofDuelConfig config)
	{
		this.client = client;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
	}

	// Computed on the CLIENT thread during render() and read by the mouse thread. Reading client
	// state off the client thread returns stale values, so isShowing() could answer false and
	// hitTest returned OUTSIDE — every button in the window went dead. The click path must read
	// ONLY this cached value. Same fix, same reason, as LofShopTabsOverlay's cached showing.
	private volatile boolean showingCached;

	// Scaled placement published by render() on the client thread; the mouse thread hit-tests via this cache only.
	private volatile LofModal.Placement placement;

	/** Cached — safe to call from the mouse thread (see the field note). */
	boolean isShowing()
	{
		return showingCached;
	}

	/** The live client-thread check — only call from render(). */
	private boolean computeShowing()
	{
		return config.enabled()
			&& client.getGameState() == GameState.LOGGED_IN
			&& (client.getVarpValue(STATE_VARP) & 0x1) != 0;
	}

	// Centred in the game viewport (left ~516 in fixed mode) so it sits clear of the inventory
	// column — the same anchor the stake window uses, so the two duel screens appear in one spot.
	// Placement single-sourced in LofModal (§6A) — same viewport-centred anchor as every modal.
	private int originX() { return LofModal.originX(client, WIN_W); }
	private int originY() { return LofModal.originY(client, WIN_H); }

	private Rectangle ruleRect(int ox, int oy, int i) { return new Rectangle(ox + PAD, oy + RULE_TOP + i * RULE_H, RULE_W, RULE_H - 2); }
	private Rectangle slotRect(int ox, int oy, int i)
	{
		final int x = ox + DOLL_X + DOLL_COL[i] * (SLOT_SZ + SLOT_GAP);
		final int y = oy + DOLL_TOP + DOLL_ROW[i] * (SLOT_SZ + SLOT_GAP);
		return new Rectangle(x, y, SLOT_SZ, SLOT_SZ);
	}
	/** Preset column rows, top to bottom: Whip, Boxing, Save, Load, Last. */
	private Rectangle presetRect(int ox, int oy, int row)
	{
		return new Rectangle(ox + PRESET_X, oy + DOLL_TOP + row * (PRESET_H + PRESET_GAP), PRESET_W, PRESET_H);
	}
	private Rectangle acceptRect(int ox, int oy) { return new Rectangle(ox + WIN_W - PAD - 210, oy + WIN_H - PAD - BTN_H, 100, BTN_H); }
	private Rectangle declineRect(int ox, int oy) { return new Rectangle(ox + WIN_W - PAD - 100, oy + WIN_H - PAD - BTN_H, 100, BTN_H); }

	/** Hit codes of the preset rows, in {@link #presetRect} row order. */
	private static final int[] PRESET_HITS = { WHIP, BOXING, SAVE, LOAD_SAVED, LOAD };

	int hitTest(Point canvas)
	{
		if (!isShowing()) return OUTSIDE;
		final LofModal.Placement place = placement;
		if (place == null) return OUTSIDE;
		final Point p = place.toLocal(canvas);
		final int ox = place.ox, oy = place.oy;
		if (!new Rectangle(ox, oy, WIN_W, WIN_H).contains(p)) return OUTSIDE;
		for (int i = 0; i < PRESET_HITS.length; i++) if (presetRect(ox, oy, i).contains(p)) return PRESET_HITS[i];
		if (acceptRect(ox, oy).contains(p)) return ACCEPT;
		if (declineRect(ox, oy).contains(p)) return DECLINE;
		for (int i = 0; i < RULES.length; i++) if (ruleRect(ox, oy, i).contains(p)) return RULE_BASE + i;
		for (int i = 0; i < SLOTS.length; i++) if (slotRect(ox, oy, i).contains(p)) return SLOT_BASE + i;
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

		final LofModal.Placement place = LofModal.beginWindow(g, client, WIN_W, WIN_H);
		placement = place;

		final int state = client.getVarpValue(STATE_VARP);
		final boolean myAccept = (state & (1 << ACCEPT_MINE_BIT)) != 0;
		final boolean theirAccept = (state & (1 << ACCEPT_THEIRS_BIT)) != 0;
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
		LofTheme.shadowText(g, "Duel Arena", titleX, oy + 25, LofTheme.GOLD);

		// section labels
		g.setFont(FontManager.getRunescapeSmallFont());
		LofTheme.shadowText(g, "COMBAT RULES", ox + PAD + 2, oy + TITLE_H + 20, LofTheme.GOLD_DIM);
		LofTheme.shadowText(g, "FORBIDDEN GEAR", ox + DOLL_X, oy + TITLE_H + 20, LofTheme.GOLD_DIM);
		LofTheme.shadowText(g, "PRESETS", ox + PRESET_X, oy + TITLE_H + 20, LofTheme.GOLD_DIM);

		// rule checkboxes
		g.setFont(FontManager.getRunescapeFont());
		for (int i = 0; i < RULES.length; i++)
		{
			final boolean on = (state & (1 << (i + 1))) != 0;
			checkbox(g, ruleRect(ox, oy, i), RULES[i], on, ruleRect(ox, oy, i).contains(mouse));
		}

		// equipment paper-doll
		for (int i = 0; i < SLOTS.length; i++)
		{
			final boolean forbidden = (state & (1 << (i + SLOT_BIT_BASE))) != 0;
			dollSlot(g, slotRect(ox, oy, i), SLOTS[i], forbidden, slotRect(ox, oy, i).contains(mouse));
		}

		// preset column: the two official rule sets, then the personal save/load pair, then last-duel
		g.setFont(FontManager.getRunescapeSmallFont());
		final String[] presetLabels = { "Whip", "Boxing", "Save", "Load", "Last" };
		for (int i = 0; i < presetLabels.length; i++)
		{
			button(g, presetRect(ox, oy, i), presetLabels[i], LofTheme.GOLD_DIM, false, presetRect(ox, oy, i).contains(mouse));
		}

		// status
		final String status = myAccept && !theirAccept ? "Waiting for the other player…"
			: !myAccept && theirAccept ? "Opponent has accepted — waiting on you."
			: "Either player can change the rules; both must accept.";
		g.setFont(FontManager.getRunescapeSmallFont());
		LofTheme.shadowText(g, status, ox + PAD + 2, oy + WIN_H - PAD - BTN_H - 8, (myAccept ^ theirAccept) ? GREEN : LofTheme.TEXT_DIM);

		// buttons
		g.setFont(FontManager.getRunescapeBoldFont());
		button(g, acceptRect(ox, oy), myAccept ? "Accepted" : "Accept", LofTheme.GOLD, myAccept, acceptRect(ox, oy).contains(mouse));
		button(g, declineRect(ox, oy), "Decline", LofTheme.EMBER, false, declineRect(ox, oy).contains(mouse));

		LofModal.endWindow(g, place);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(WIN_W, WIN_H);
	}

	private static void checkbox(Graphics2D g, Rectangle rc, String label, boolean on, boolean hov)
	{
		g.setColor(on ? LofTheme.alpha(GREEN, 30) : (hov ? LofTheme.ROW_HOVER : LofTheme.ROW));
		g.fillRoundRect(rc.x, rc.y, rc.width, rc.height, 6, 6);
		// 11px box so the tightened 14-rule rows still enclose it.
		final int bx = rc.x + 6, by = rc.y + (rc.height - 11) / 2;
		g.setColor(on ? GREEN : new Color(0, 0, 0, 120));
		g.fillRoundRect(bx, by, 11, 11, 3, 3);
		if (on)
		{
			final Stroke old = g.getStroke();
			g.setColor(new Color(20, 20, 20));
			g.setStroke(new BasicStroke(2f));
			g.drawLine(bx + 3, by + 6, bx + 4, by + 8);
			g.drawLine(bx + 4, by + 8, bx + 8, by + 3);
			g.setStroke(old);
		}
		LofTheme.shadowText(g, label, bx + 17, rc.y + rc.height / 2 + 5, on ? LofTheme.TEXT : LofTheme.TEXT_DIM);
	}

	/** A paper-doll equipment slot — red when the slot is forbidden. */
	private static void dollSlot(Graphics2D g, Rectangle rc, String label, boolean forbidden, boolean hov)
	{
		g.setColor(forbidden ? LofTheme.alpha(LofTheme.EMBER, 60) : (hov ? LofTheme.ROW_HOVER : LofTheme.ROW));
		g.fillRoundRect(rc.x, rc.y, rc.width, rc.height, 6, 6);
		g.setColor(forbidden ? LofTheme.EMBER : LofTheme.alpha(LofTheme.EMBER, hov ? 130 : 40));
		g.drawRoundRect(rc.x, rc.y, rc.width - 1, rc.height - 1, 6, 6);
		LofTheme.shadowText(g, label, rc.x + (rc.width - g.getFontMetrics().stringWidth(label)) / 2, rc.y + rc.height / 2 + 5,
			forbidden ? new Color(255, 150, 140) : LofTheme.TEXT_DIM);
		if (forbidden)
		{
			final Stroke old = g.getStroke();
			g.setColor(LofTheme.alpha(LofTheme.EMBER, 200));
			g.setStroke(new BasicStroke(2f));
			g.drawLine(rc.x + 6, rc.y + 6, rc.x + rc.width - 6, rc.y + rc.height - 6);
			g.drawLine(rc.x + rc.width - 6, rc.y + 6, rc.x + 6, rc.y + rc.height - 6);
			g.setStroke(old);
		}
	}

	private static void button(Graphics2D g, Rectangle rc, String label, Color accent, boolean active, boolean hov)
	{
		g.setColor(active ? LofTheme.alpha(accent, 46) : (hov ? LofTheme.alpha(accent, 30) : new Color(255, 255, 255, 12)));
		g.fillRoundRect(rc.x, rc.y, rc.width, rc.height, 8, 8);
		g.setColor(LofTheme.alpha(accent, hov || active ? 200 : 120));
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
