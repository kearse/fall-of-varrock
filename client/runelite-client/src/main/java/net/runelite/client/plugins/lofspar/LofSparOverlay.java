/*
 * Fall of Varrock — Companion Sparring settings overlay (renderer + hit-testing).
 *
 * The Duel Arena Options screen's layout, single-player: the combat-rule checkboxes (left, with
 * Max Stats appended) and the SPARRING PARTNER column (right — fight style / difficulty / loadout
 * radios) where the duel screen has its paper-doll. No accept handshake: Start Bout replaces
 * Accept. The server (SparringClientMenu) publishes state in one packed varp 4680; this overlay
 * reads it each frame and sends clicks back as "::lofspar <action>". Bit layout, RULES and the
 * radio-group orders MUST match SparringClientMenu.
 */
package net.runelite.client.plugins.lofspar;

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

class LofSparOverlay extends Overlay
{
	/** Must match server SparringClientMenu.STATE_VARP. */
	static final int STATE_VARP = 4680;

	// hit-test result codes
	static final int OUTSIDE = -1;
	static final int INSIDE = 0;
	static final int DECLINE = 1;
	static final int START = 2;   // settings phase: "Continue" (on to the kit step)
	static final int LOAD = 3;
	static final int ACCEPT = 4;  // confirm phase: begin the bout
	static final int BACK = 5;    // confirm phase: return to settings
	static final int RULE_BASE = 200;  // + rule index (0..8)
	static final int STYLE_BASE = 100; // + fight-style index (0..3)
	static final int DIFF_BASE = 110;  // + difficulty index (0..2)
	static final int KIT_BASE = 120;   // + companion-loadout mode (0..2)

	/** MUST match SparringClientMenu.packRules order. */
	private static final String[] RULES = {
		"No Melee", "No Ranged", "No Magic", "No Prayer", "No Food", "No Drinks",
		"No Movement", "No Special Attacks", "Max Stats - all 99s",
	};

	/** MUST match the server enums' ordinal orders. */
	private static final String[] STYLES = { "Full NH - switches you", "Melee only", "Ranged only", "Magic only" };
	private static final String[] DIFFS = { "Easy - slow, no prayer", "Medium", "Hard - 1-tick switches" };
	private static final String[] KITS = { "Current gear", "Mirror my loadout", "Custom kit..." };

	// Shared window size (design-system standard modal). See docs/overlay-design-system.md.
	static final int WIN_W = LofModal.W;
	static final int WIN_H = LofModal.H;
	private static final int WIN_ARC = 14;
	private static final int TITLE_H = 38;
	private static final int PAD = 12;
	private static final int RULE_TOP = TITLE_H + 26;
	private static final int RULE_H = 16;
	private static final int RULE_W = 226;
	private static final int RIGHT_X = 288;
	private static final int RIGHT_W = 180;
	private static final int ROW_H = 15;
	private static final int STYLE_TOP = TITLE_H + 26;          // 4 rows
	private static final int DIFF_LABEL_Y = STYLE_TOP + 4 * ROW_H + 12;
	private static final int DIFF_TOP = DIFF_LABEL_Y + 6;       // 3 rows
	private static final int KIT_LABEL_Y = DIFF_TOP + 3 * ROW_H + 12;
	private static final int KIT_TOP = KIT_LABEL_Y + 6;         // 3 rows
	private static final int BTN_H = 32;

	private static final Color GREEN = new Color(110, 205, 110);

	private final Client client;
	private final LofSparConfig config;

	@Inject
	private LofSparOverlay(Client client, LofSparConfig config)
	{
		this.client = client;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
	}

	// Computed on the CLIENT thread during render() and read by the mouse thread — the click path
	// must read ONLY these cached values (see LofDuelOverlay's field note; same fix, same reason).
	private volatile boolean showingCached;
	private volatile boolean confirmCached; // varp bit 16: the read-only CONFIRM overview phase
	private volatile LofModal.Placement placement;

	/** Cached — safe to call from the mouse thread. */
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

	private Rectangle ruleRect(int ox, int oy, int i) { return new Rectangle(ox + PAD, oy + RULE_TOP + i * RULE_H, RULE_W, RULE_H - 2); }
	private Rectangle styleRect(int ox, int oy, int i) { return new Rectangle(ox + RIGHT_X, oy + STYLE_TOP + i * ROW_H, RIGHT_W, ROW_H - 2); }
	private Rectangle diffRect(int ox, int oy, int i) { return new Rectangle(ox + RIGHT_X, oy + DIFF_TOP + i * ROW_H, RIGHT_W, ROW_H - 2); }
	private Rectangle kitRect(int ox, int oy, int i) { return new Rectangle(ox + RIGHT_X, oy + KIT_TOP + i * ROW_H, RIGHT_W, ROW_H - 2); }
	private Rectangle loadRect(int ox, int oy) { return new Rectangle(ox + PAD, oy + WIN_H - PAD - BTN_H, 96, BTN_H); }
	private Rectangle startRect(int ox, int oy) { return new Rectangle(ox + WIN_W - PAD - 210, oy + WIN_H - PAD - BTN_H, 100, BTN_H); }
	private Rectangle declineRect(int ox, int oy) { return new Rectangle(ox + WIN_W - PAD - 100, oy + WIN_H - PAD - BTN_H, 100, BTN_H); }

	int hitTest(Point canvas)
	{
		if (!isShowing()) return OUTSIDE;
		final LofModal.Placement place = placement;
		if (place == null) return OUTSIDE;
		final Point p = place.toLocal(canvas);
		final int ox = place.ox, oy = place.oy;
		if (!new Rectangle(ox, oy, WIN_W, WIN_H).contains(p)) return OUTSIDE;
		if (confirmCached)
		{
			// The overview is read-only: just Back / Accept / Decline.
			if (loadRect(ox, oy).contains(p)) return BACK;
			if (startRect(ox, oy).contains(p)) return ACCEPT;
			if (declineRect(ox, oy).contains(p)) return DECLINE;
			return INSIDE;
		}
		if (loadRect(ox, oy).contains(p)) return LOAD;
		if (startRect(ox, oy).contains(p)) return START;
		if (declineRect(ox, oy).contains(p)) return DECLINE;
		for (int i = 0; i < RULES.length; i++) if (ruleRect(ox, oy, i).contains(p)) return RULE_BASE + i;
		for (int i = 0; i < STYLES.length; i++) if (styleRect(ox, oy, i).contains(p)) return STYLE_BASE + i;
		for (int i = 0; i < DIFFS.length; i++) if (diffRect(ox, oy, i).contains(p)) return DIFF_BASE + i;
		for (int i = 0; i < KITS.length; i++) if (kitRect(ox, oy, i).contains(p)) return KIT_BASE + i;
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

		// Absolute canvas coordinates — undo any renderer translate (see LofDuelOverlay).
		final java.awt.Rectangle selfBounds = getBounds();
		g.translate(-selfBounds.x, -selfBounds.y);

		final LofModal.Placement place = LofModal.beginWindow(g, client, WIN_W, WIN_H);
		placement = place;

		final int state = client.getVarpValue(STATE_VARP);
		final int style = (state >> 1) & 0x3;
		final int diff = (state >> 3) & 0x3;
		final int rules = (state >> 5) & 0x1FF;
		final int kitMode = (state >> 14) & 0x3;
		final boolean confirm = (state & (1 << 16)) != 0;
		final boolean loanerKit = (state & (1 << 17)) != 0;
		confirmCached = confirm;
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
		LofTheme.shadowText(g, confirm ? "Companion Sparring - Confirm" : "Companion Sparring", titleX, oy + 25, LofTheme.GOLD);

		// section labels
		g.setFont(FontManager.getRunescapeSmallFont());
		LofTheme.shadowText(g, "COMBAT RULES", ox + PAD + 2, oy + TITLE_H + 22, LofTheme.GOLD_DIM);
		if (confirm)
		{
			LofTheme.shadowText(g, "THE ARRANGEMENT", ox + RIGHT_X, oy + TITLE_H + 22, LofTheme.GOLD_DIM);
		}
		else
		{
			LofTheme.shadowText(g, "SPARRING PARTNER", ox + RIGHT_X, oy + TITLE_H + 22, LofTheme.GOLD_DIM);
			LofTheme.shadowText(g, "DIFFICULTY", ox + RIGHT_X, oy + DIFF_LABEL_Y, LofTheme.GOLD_DIM);
			LofTheme.shadowText(g, "THEIR LOADOUT", ox + RIGHT_X, oy + KIT_LABEL_Y, LofTheme.GOLD_DIM);
		}

		// rule checkboxes (green = a rule the bout enforces); read-only on the confirm overview
		g.setFont(FontManager.getRunescapeFont());
		for (int i = 0; i < RULES.length; i++)
		{
			final boolean on = (rules & (1 << i)) != 0;
			row(g, ruleRect(ox, oy, i), RULES[i], on, GREEN, !confirm && ruleRect(ox, oy, i).contains(mouse));
		}

		if (confirm)
		{
			// The classic duel-arena second screen: a read-back of everything agreed, then Accept.
			final String[] summary = {
				"Fight style:  " + STYLES[style],
				"Difficulty:  " + DIFFS[diff],
				"Their loadout:  " + KITS[kitMode],
				"Your loadout:  " + (loanerKit ? "Loaner kit (from the locker)" : "Your own gear"),
			};
			int sy = oy + STYLE_TOP + 8;
			for (final String line : summary)
			{
				LofTheme.shadowText(g, line, ox + RIGHT_X, sy, LofTheme.TEXT);
				sy += ROW_H + 8;
			}
			g.setFont(FontManager.getRunescapeSmallFont());
			LofTheme.shadowText(g, "Check the terms - Accept starts the countdown.", ox + RIGHT_X, sy + 6, LofTheme.TEXT_DIM);
		}
		else
		{
			// partner radios (gold = the selected choice)
			for (int i = 0; i < STYLES.length; i++)
			{
				row(g, styleRect(ox, oy, i), STYLES[i], i == style, LofTheme.GOLD, styleRect(ox, oy, i).contains(mouse));
			}
			for (int i = 0; i < DIFFS.length; i++)
			{
				row(g, diffRect(ox, oy, i), DIFFS[i], i == diff, LofTheme.GOLD, diffRect(ox, oy, i).contains(mouse));
			}
			for (int i = 0; i < KITS.length; i++)
			{
				row(g, kitRect(ox, oy, i), KITS[i], i == kitMode, LofTheme.GOLD, kitRect(ox, oy, i).contains(mouse));
			}
		}

		// status
		final boolean maxStats = (rules & (1 << 8)) != 0;
		final String status = maxStats
			? "All combat stats set to 99 for this bout - armoury fully unlocked."
			: "Deaths are safe. Loaner gear returns itself. You both keep the XP.";
		g.setFont(FontManager.getRunescapeSmallFont());
		LofTheme.shadowText(g, status, ox + PAD + 2, oy + WIN_H - PAD - BTN_H - 8, maxStats ? GREEN : LofTheme.TEXT_DIM);

		// buttons — settings: Load Last / Continue / Decline; confirm: Back / Accept / Decline
		g.setFont(FontManager.getRunescapeBoldFont());
		if (confirm)
		{
			button(g, loadRect(ox, oy), "Back", LofTheme.GOLD_DIM, loadRect(ox, oy).contains(mouse));
			button(g, startRect(ox, oy), "Accept", LofTheme.GOLD, startRect(ox, oy).contains(mouse));
		}
		else
		{
			button(g, loadRect(ox, oy), "Load Last", LofTheme.GOLD_DIM, loadRect(ox, oy).contains(mouse));
			button(g, startRect(ox, oy), "Continue", LofTheme.GOLD, startRect(ox, oy).contains(mouse));
		}
		button(g, declineRect(ox, oy), "Decline", LofTheme.EMBER, declineRect(ox, oy).contains(mouse));

		LofModal.endWindow(g, place);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(WIN_W, WIN_H);
	}

	/** A checkbox/radio row in the duel screen's visual language — [accent] tints the on-state. */
	private static void row(Graphics2D g, Rectangle rc, String label, boolean on, Color accent, boolean hov)
	{
		g.setColor(on ? LofTheme.alpha(accent, 30) : (hov ? LofTheme.ROW_HOVER : LofTheme.ROW));
		g.fillRoundRect(rc.x, rc.y, rc.width, rc.height, 6, 6);
		final int bx = rc.x + 6, by = rc.y + (rc.height - 11) / 2;
		g.setColor(on ? accent : new Color(0, 0, 0, 120));
		g.fillRoundRect(bx, by, 11, 11, 3, 3);
		if (on)
		{
			final Stroke old = g.getStroke();
			g.setColor(new Color(20, 20, 20));
			g.setStroke(new BasicStroke(2f));
			g.drawLine(bx + 2, by + 6, bx + 4, by + 8);
			g.drawLine(bx + 4, by + 8, bx + 9, by + 2);
			g.setStroke(old);
		}
		LofTheme.shadowText(g, label, bx + 17, rc.y + rc.height / 2 + 5, on ? LofTheme.TEXT : LofTheme.TEXT_DIM);
	}

	private static void button(Graphics2D g, Rectangle rc, String label, Color accent, boolean hov)
	{
		g.setColor(hov ? LofTheme.alpha(accent, 30) : new Color(255, 255, 255, 12));
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
