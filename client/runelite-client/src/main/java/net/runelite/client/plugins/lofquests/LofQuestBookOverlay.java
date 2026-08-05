/*
 * Fall of Varrock — Quest Journal window (renderer + hit-testing).
 *
 * A client-drawn progression window styled after the Feudal Ranks screen (lofranks): one quest in
 * focus — its name in the OSRS state colour, a progress bar, the "why" blurb, the step checklist
 * and its rewards, all scrolling together so a quest can be any length — with the whole chain as a
 * node track across the bottom (click a node to switch focus). A Track toggle drives the guidance
 * arrow, Close dismisses. All data comes from the shipped LofQuest registry + LofQuestVarps; the
 * frame/palette are the shared loftheme toolkit.
 */
package net.runelite.client.plugins.lofquests;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.plugins.loftheme.LofModal;
import net.runelite.client.plugins.loftheme.LofTheme;
import net.runelite.client.plugins.loftheme.LofWindows;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class LofQuestBookOverlay extends Overlay implements LofWindows.Window
{
	// hit-test result codes (nodes are NODE_BASE + chainIndex)
	static final int OUTSIDE = -1;
	static final int INSIDE = 0;
	static final int CLOSE = 1;
	static final int TRACK = 2;
	static final int NODE_BASE = 100;

	private static final int WIN_W = LofModal.W; // 480
	private static final int WIN_H = LofModal.H; // 324
	private static final int WIN_ARC = 14;
	private static final int TITLE_H = 38;
	private static final int PAD = 14;

	private static final int HERO_Y = 44;   // emblem/name block
	private static final int BAR_Y = 92;
	private static final int BAR_H = 8;
	private static final int SCROLL_TOP = 106;
	private static final int SCROLL_BOT = 208; // divider sits at 213
	private static final int TRACK_LABEL_Y = 224;
	private static final int NODE_CY = 244;
	private static final int NODE_D = 22;
	private static final int YOU_Y = 264;
	private static final int FOOT_BTN_Y = 284;
	private static final int FOOT_BTN_H = 28;

	// Rogue quests get a left-side progress dial; the story/steps/rewards shift right past it.
	private static final int DIAL_W = 130;
	private static final int HUNT_GOAL = 30;   // Rogue Hunting I — mirrors RogueProblem.HUNT_GOAL
	private static final int LADDER_SIZE = 14; // Rogue Knight ladder length (RogueKnights.LADDER)

	private final Client client;
	private final LofQuestsConfig config;

	private boolean visible;
	// focus/trackedOn/scroll are written from the mouse thread (node + track clicks, wheel) and read
	// on the client thread in render() — volatile for safe publication.
	private volatile LofQuest focus;
	private volatile boolean trackedOn; // whether the focused quest is the tracked one (drives the Track button)

	// Published by render() on the client thread; the mouse thread hit-tests via these caches only
	// (never re-derive off-thread — same reason as LofRanksOverlay's cached gate).
	private volatile LofModal.Placement placement;
	private volatile int contentH;   // measured content height, for maxScroll()
	private volatile boolean trackable; // focused quest can be tracked (not locked/future)

	private volatile int scroll; // px

	private Font nameFont;

	@Inject
	private LofQuestBookOverlay(Client client, LofQuestsConfig config)
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

	LofQuest getFocus()
	{
		return focus;
	}

	void setFocus(LofQuest q)
	{
		if (q != null && q != focus)
		{
			scroll = 0;
		}
		focus = q;
	}

	/** Told by the plugin (which owns tracking) whether the focused quest is currently tracked. */
	void setTrackedOn(boolean on)
	{
		trackedOn = on;
	}

	boolean isTrackable()
	{
		return trackable;
	}

	// --- placement helpers (single-sourced in LofModal) ---
	private Rectangle closeRect(int ox, int oy)
	{
		return new Rectangle(ox + WIN_W - 30, oy + 9, 20, 20);
	}

	private Rectangle trackRect(int ox, int oy)
	{
		return new Rectangle(ox + PAD, oy + FOOT_BTN_Y, 120, FOOT_BTN_H);
	}

	private Rectangle closeBtnRect(int ox, int oy)
	{
		final int w = 96;
		return new Rectangle(ox + WIN_W - PAD - w, oy + FOOT_BTN_Y, w, FOOT_BTN_H);
	}

	private Rectangle nodeRect(int ox, int oy, int i)
	{
		final int n = LofQuest.CHAIN.size();
		final int usable = WIN_W - 2 * PAD - NODE_D;
		final int cx = ox + PAD + (n <= 1 ? 0 : (usable * i) / (n - 1));
		return new Rectangle(cx, oy + NODE_CY - NODE_D / 2, NODE_D, NODE_D);
	}

	private int maxScroll()
	{
		return Math.max(0, contentH - (SCROLL_BOT - SCROLL_TOP));
	}

	/** Wheel-scroll the content region if the cursor is over it; returns true if consumed. */
	boolean handleScroll(Point canvas, int rotation)
	{
		if (!visible)
		{
			return false;
		}
		final LofModal.Placement place = placement;
		if (place == null)
		{
			return false;
		}
		final Point p = place.toLocal(canvas);
		final int ox = place.ox, oy = place.oy;
		if (!new Rectangle(ox + PAD, oy + SCROLL_TOP, WIN_W - 2 * PAD, SCROLL_BOT - SCROLL_TOP).contains(p))
		{
			return false;
		}
		scroll = clamp(scroll + rotation * 24, 0, maxScroll());
		return true;
	}

	int hitTest(Point canvas)
	{
		if (!visible)
		{
			return OUTSIDE;
		}
		final LofModal.Placement place = placement;
		if (place == null)
		{
			return OUTSIDE;
		}
		final Point p = place.toLocal(canvas);
		final int ox = place.ox, oy = place.oy;
		if (!new Rectangle(ox, oy, WIN_W, WIN_H).contains(p))
		{
			return OUTSIDE;
		}
		if (closeRect(ox, oy).contains(p) || closeBtnRect(ox, oy).contains(p))
		{
			return CLOSE;
		}
		if (trackable && trackRect(ox, oy).contains(p))
		{
			return TRACK;
		}
		for (int i = 0; i < LofQuest.CHAIN.size(); i++)
		{
			if (nodeRect(ox, oy, i).contains(p))
			{
				return NODE_BASE + i;
			}
		}
		return INSIDE;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!visible || !config.questWindow() || client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}
		if (focus == null)
		{
			focus = LofQuest.byChainIndex(0);
		}

		final Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		final Rectangle selfBounds = getBounds();
		g.translate(-selfBounds.x, -selfBounds.y);

		final LofModal.Placement place = LofModal.beginWindow(g, client, WIN_W, WIN_H);
		placement = place;
		if (nameFont == null)
		{
			nameFont = FontManager.getRunescapeBoldFont().deriveFont(19f);
		}

		final int ox = place.ox, oy = place.oy;
		final Point mouse = place.toLocal(mousePoint());
		final LofQuestState state = focus.state(client);
		trackable = state != LofQuestState.LOCKED && !focus.isFuture();

		final boolean rogue = focus == LofQuest.ROGUE_HUNTING_I || focus == LofQuest.ROGUE_HUNTING_II;
		final int leftX = rogue ? ox + PAD + DIAL_W : ox + PAD;
		final int viewW = rogue ? WIN_W - 2 * PAD - DIAL_W : WIN_W - 2 * PAD;

		LofTheme.panel(g, ox, oy, WIN_W, WIN_H, WIN_ARC);
		drawHeader(g, ox, oy, mouse);
		drawHero(g, ox, oy, state);
		if (rogue)
		{
			drawDial(g, ox, oy); // the left-side rogue gauge takes the progress bar's role
		}
		else
		{
			drawBar(g, ox, oy, state);
		}
		drawScrollContent(g, ox, oy, state, leftX, viewW);
		drawTrack(g, ox, oy, mouse);
		drawFooter(g, ox, oy, mouse);

		LofModal.endWindow(g, place);
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

		int titleX = ox + 14;
		if (LofTheme.logo() != null)
		{
			g.drawImage(LofTheme.logo(), ox + 12, oy + 5, 28, 28, null);
			titleX = ox + 46;
		}
		g.setFont(FontManager.getRunescapeBoldFont());
		LofTheme.shadowText(g, "Quest Journal", titleX, oy + 25, LofTheme.GOLD);

		g.setFont(FontManager.getRunescapeSmallFont());
		final String sub = focus.getQuestName();
		LofTheme.shadowText(g, sub, ox + WIN_W - 44 - g.getFontMetrics().stringWidth(sub), oy + 24, LofTheme.TEXT_DIM);

		final Rectangle cr = closeRect(ox, oy);
		final boolean hov = cr.contains(mouse);
		g.setColor(hov ? LofTheme.EMBER : new Color(255, 255, 255, 18));
		g.fillRoundRect(cr.x, cr.y, cr.width, cr.height, 6, 6);
		g.setColor(hov ? LofTheme.TEXT : LofTheme.TEXT_DIM);
		final Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(1.6f));
		g.drawLine(cr.x + 6, cr.y + 6, cr.x + cr.width - 7, cr.y + cr.height - 7);
		g.drawLine(cr.x + cr.width - 7, cr.y + 6, cr.x + 6, cr.y + cr.height - 7);
		g.setStroke(old);
	}

	private void drawHero(Graphics2D g, int ox, int oy, LofQuestState state)
	{
		final Color col = state.getColor();
		// emblem: a state ring with the quest's chain number
		final int ex = ox + PAD, ey = oy + HERO_Y + 2, ed = 34;
		g.setColor(new Color(22, 16, 14));
		g.fillOval(ex, ey, ed, ed);
		g.setColor(LofTheme.GOLD_DIM);
		final Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(1.6f));
		g.drawOval(ex, ey, ed, ed);
		g.setStroke(old);
		g.setFont(FontManager.getRunescapeBoldFont());
		final String num = Integer.toString(focus.chainIndex() + 1);
		final FontMetrics nfm = g.getFontMetrics();
		LofTheme.shadowText(g, num, ex + (ed - nfm.stringWidth(num)) / 2, ey + ed / 2 + 5, LofTheme.GOLD);

		g.setFont(nameFont);
		LofTheme.shadowText(g, focus.getQuestName(), ox + PAD + 44, oy + HERO_Y + 26, col);

		// status / progress pill, right-aligned
		final String pillText = pillText(state);
		final Color pillCol = state == LofQuestState.IN_PROGRESS ? LofTheme.GOLD : col;
		g.setFont(FontManager.getRunescapeSmallFont());
		LofTheme.pill(g, g.getFontMetrics(), pillText, ox + WIN_W - PAD, oy + HERO_Y + 20, pillCol);
	}

	/** The pill shows the current step's live counter (e.g. "6 / 14") when there is one, else the state label. */
	private String pillText(LofQuestState state)
	{
		if (state == LofQuestState.IN_PROGRESS)
		{
			final LofQuestStep cur = focus.currentStep(client);
			if (cur != null)
			{
				final String prog = focus.stepProgress(client, cur).trim(); // " (6/14)"
				if (!prog.isEmpty())
				{
					return prog.replace("(", "").replace(")", "").replace("/", " / ");
				}
			}
		}
		return state.getLabel().toUpperCase();
	}

	private void drawBar(Graphics2D g, int ox, int oy, LofQuestState state)
	{
		final int barW = WIN_W - 2 * PAD;
		g.setColor(new Color(12, 10, 9));
		g.fillRoundRect(ox + PAD, oy + BAR_Y, barW, BAR_H, BAR_H, BAR_H);
		g.setColor(LofTheme.EMBER_DARK);
		g.drawRoundRect(ox + PAD, oy + BAR_Y, barW, BAR_H, BAR_H, BAR_H);

		final double frac = progressFraction(state);
		final int fillW = (int) Math.round(barW * frac);
		if (fillW > 2)
		{
			g.setColor(state == LofQuestState.FINISHED ? LofTheme.GOLD_DIM : LofTheme.GOLD);
			g.fillRoundRect(ox + PAD, oy + BAR_Y, fillW, BAR_H, BAR_H, BAR_H);
		}
	}

	/**
	 * The rogue quests' left-side dial: a radial gauge of hunt kills (Rogue Hunting I) or camps
	 * cleared (Rogue Hunting II, with a knights-beaten sub-readout). Data from the varps the client
	 * already reads (ROGUE_PROBLEM kills, KNIGHTS camps/knights) — no server round-trip per frame.
	 */
	private void drawDial(Graphics2D g, int ox, int oy)
	{
		final int cx = ox + PAD + DIAL_W / 2;
		final int cy = oy + SCROLL_TOP + 42;
		final int r = 34;

		final int value, max;
		final String label;
		final String sub;
		if (focus == LofQuest.ROGUE_HUNTING_I)
		{
			value = Math.min(LofQuestVarps.rogueProblemKills(client), HUNT_GOAL);
			max = HUNT_GOAL;
			label = "CUTTHROATS";
			sub = null;
		}
		else
		{
			int total = LofQuestVarps.knightCampsTotal(client);
			if (total <= 0)
			{
				total = 5; // varp not populated yet — the ladder has 5 knight-hosting camps
			}
			value = Math.min(LofQuestVarps.knightCampsCleared(client), total);
			max = total;
			label = "CAMPS CLEARED";
			sub = "Knights " + Math.min(LofQuestVarps.knightsBeaten(client), LADDER_SIZE) + " / " + LADDER_SIZE;
		}
		final double frac = max <= 0 ? 0.0 : Math.min(1.0, (double) value / max);

		// background ring + progress arc (from the top, clockwise)
		final Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(new Color(255, 255, 255, 24));
		g.drawArc(cx - r, cy - r, 2 * r, 2 * r, 0, 360);
		if (frac > 0)
		{
			g.setColor(frac >= 1.0 ? LofTheme.GOLD : LofTheme.EMBER);
			g.drawArc(cx - r, cy - r, 2 * r, 2 * r, 90, -(int) Math.round(360 * frac));
		}
		g.setStroke(old);

		// centre value
		g.setFont(nameFont);
		final String big = value + "/" + max;
		final FontMetrics bfm = g.getFontMetrics();
		LofTheme.shadowText(g, big, cx - bfm.stringWidth(big) / 2, cy + 6, frac >= 1.0 ? LofTheme.GOLD : LofTheme.TEXT);

		// label + optional sub-readout under the dial
		g.setFont(FontManager.getRunescapeSmallFont());
		final FontMetrics sfm = g.getFontMetrics();
		LofTheme.shadowText(g, label, cx - sfm.stringWidth(label) / 2, cy + r + 14, LofTheme.GOLD_DIM);
		if (sub != null)
		{
			LofTheme.shadowText(g, sub, cx - sfm.stringWidth(sub) / 2, cy + r + 27, LofTheme.TEXT_DIM);
		}

		// thin divider between the dial column and the content
		g.setColor(new Color(255, 255, 255, 14));
		g.fillRect(ox + PAD + DIAL_W - 7, oy + SCROLL_TOP, 1, SCROLL_BOT - SCROLL_TOP);
	}

	private double progressFraction(LofQuestState state)
	{
		if (state == LofQuestState.FINISHED)
		{
			return 1.0;
		}
		if (state == LofQuestState.LOCKED || state == LofQuestState.NOT_STARTED || state == LofQuestState.FUTURE)
		{
			return 0.0;
		}
		final int total = focus.getSteps().size();
		return total == 0 ? 0.0 : Math.min(1.0, (double) focus.completedSteps(client) / total);
	}

	// --- the scrolling story / steps / rewards region (leftX/viewW let a rogue dial claim the left) ---
	private void drawScrollContent(Graphics2D g, int ox, int oy, LofQuestState state, int leftX, int viewW)
	{
		final int x = leftX;
		final int w = viewW - 6; // leave room for the scrollbar
		final int viewTop = oy + SCROLL_TOP;
		final int viewH = SCROLL_BOT - SCROLL_TOP;

		final Shape oldClip = g.getClip();
		g.setClip(x, viewTop, viewW, viewH);
		int y = viewTop - scroll; // content origin, scrolled

		final FontMetrics small = smallMetrics(g);

		// blurb (word-wrapped, italic-ish dim)
		g.setFont(FontManager.getRunescapeSmallFont());
		for (String line : wrap(small, focus.getWhy(), w))
		{
			y += 14;
			LofTheme.shadowText(g, line, x, y, LofTheme.TEXT_DIM);
		}

		// lock reason (locked quests): what to do first
		final String lock = focus.lockReason(client);
		if (lock != null)
		{
			y += 18;
			LofTheme.shadowText(g, "Locked — " + lock, x, y, LofQuestState.LOCKED.getColor());
		}

		// THE TASK
		y += 22;
		sectionHead(g, "The task", x, y, w);
		final int curOrd = focus.stepOrdinal(client);
		g.setFont(FontManager.getRunescapeSmallFont());
		for (LofQuestStep step : focus.getSteps())
		{
			y += 17;
			final boolean done = curOrd > step.getOrdinal();
			final boolean cur = curOrd == step.getOrdinal() && state == LofQuestState.IN_PROGRESS;
			// Drawn marker (the RS pixel font has no ✓/➤/○ glyphs — same reason the ranks window
			// draws its tick with lines): check = done, filled arrow = current, hollow dot = ahead.
			stepMark(g, x, y, done ? 'd' : cur ? 'c' : 't',
				done ? LofQuestState.FINISHED.getColor() : cur ? LofTheme.EMBER : LofTheme.TEXT_DIM);
			final Color tc = done ? LofTheme.GOLD_DIM : cur ? LofTheme.TEXT : LofTheme.TEXT_DIM;
			String label = step.getLabel();
			if (cur)
			{
				final String prog = focus.stepProgress(client, step).trim();
				if (!prog.isEmpty())
				{
					label = label + " " + prog;
				}
			}
			LofTheme.shadowText(g, label, x + 16, y, tc);
		}

		// REWARDS
		y += 24;
		sectionHead(g, "Rewards", x, y, w);
		g.setFont(FontManager.getRunescapeSmallFont());
		for (String reward : focus.getUnlocks())
		{
			y += 16;
			bullet(g, x, y, LofTheme.GOLD); // small gold diamond (no ✦ glyph in the RS font)
			final List<String> rl = wrap(small, reward, w - 16);
			for (int i = 0; i < rl.size(); i++)
			{
				if (i > 0)
				{
					y += 14;
				}
				LofTheme.shadowText(g, rl.get(i), x + 16, y, LofTheme.TEXT);
			}
		}

		contentH = (y + scroll) - viewTop + 6; // total content height for maxScroll()
		g.setClip(oldClip);

		// scrollbar at the right edge of the content column
		LofModal.scrollbar(g, x + viewW - 4, viewTop, viewH, contentH, scroll);

		// bottom divider under the scroll region (full width)
		g.setColor(new Color(255, 255, 255, 16));
		g.fillRect(ox + PAD, oy + SCROLL_BOT + 5, WIN_W - 2 * PAD, 1);
	}

	/** A step's status marker, drawn (not glyphed) at text baseline [y]: 'd' check, 'c' arrow, 't' dot. */
	private void stepMark(Graphics2D g, int x, int y, char kind, Color col)
	{
		final int mid = y - 4;
		g.setColor(col);
		final Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(1.7f));
		if (kind == 'd')
		{
			g.drawLine(x, mid, x + 3, mid + 3);
			g.drawLine(x + 3, mid + 3, x + 9, mid - 4);
		}
		else if (kind == 'c')
		{
			g.fillPolygon(new int[]{x, x, x + 6}, new int[]{mid - 4, mid + 4, mid}, 3);
		}
		else
		{
			g.drawOval(x, mid - 3, 6, 6);
		}
		g.setStroke(old);
	}

	/** A small filled gold diamond bullet, drawn at text baseline [y]. */
	private void bullet(Graphics2D g, int x, int y, Color col)
	{
		final int mid = y - 4;
		g.setColor(col);
		g.fillPolygon(new int[]{x, x + 3, x + 6, x + 3}, new int[]{mid, mid - 3, mid, mid + 3}, 4);
	}

	private void sectionHead(Graphics2D g, String label, int x, int y, int w)
	{
		g.setFont(FontManager.getRunescapeSmallFont());
		LofTheme.shadowText(g, label.toUpperCase(), x, y, LofTheme.GOLD);
		final int lx = x + g.getFontMetrics().stringWidth(label.toUpperCase()) + 8;
		LofTheme.emberUnderline(g, lx, y - 4, Math.max(0, x + w - lx));
	}

	private void drawTrack(Graphics2D g, int ox, int oy, Point mouse)
	{
		g.setFont(FontManager.getRunescapeSmallFont());
		final FontMetrics fm = g.getFontMetrics();
		final String lab = "The realm's path";
		LofTheme.shadowText(g, lab, ox + (WIN_W - fm.stringWidth(lab)) / 2, oy + TRACK_LABEL_Y, LofTheme.TEXT_DIM);

		final int n = LofQuest.CHAIN.size();
		final Rectangle first = nodeRect(ox, oy, 0);
		final Rectangle last = nodeRect(ox, oy, n - 1);
		final int lineY = oy + NODE_CY;
		// connector: faint the whole way, gold-dim up to the current quest
		g.setColor(new Color(158, 149, 140, 60));
		g.fillRect(first.x + NODE_D / 2, lineY - 1, last.x - first.x, 2);
		int curIdx = -1;
		for (int i = 0; i < n; i++)
		{
			if (LofQuest.CHAIN.get(i).state(client) == LofQuestState.IN_PROGRESS)
			{
				curIdx = i;
				break;
			}
		}
		if (curIdx > 0)
		{
			g.setColor(LofTheme.alpha(LofTheme.GOLD_DIM, 200));
			g.fillRect(first.x + NODE_D / 2, lineY - 1, nodeRect(ox, oy, curIdx).x - first.x, 2);
		}

		for (int i = 0; i < n; i++)
		{
			final Rectangle r = nodeRect(ox, oy, i);
			final LofQuest q = LofQuest.CHAIN.get(i);
			final LofQuestState st = q.state(client);
			final boolean done = st == LofQuestState.FINISHED;
			final boolean isCur = st == LofQuestState.IN_PROGRESS;

			g.setColor(new Color(12, 10, 9));
			g.fillOval(r.x, r.y, NODE_D, NODE_D);
			final Color ring = isCur ? LofTheme.EMBER
				: done ? LofTheme.GOLD_DIM
				: st == LofQuestState.NOT_STARTED ? LofTheme.GOLD
				: new Color(158, 149, 140, 90);
			g.setColor(ring);
			final Stroke old = g.getStroke();
			g.setStroke(new BasicStroke(isCur ? 2.2f : 1.4f));
			g.drawOval(r.x, r.y, NODE_D, NODE_D);
			g.setStroke(old);

			// node fill: a drawn check when done, a filled dot when current, else the chain number
			// (digits are safe in the RS font; ✓/● are not).
			g.setFont(FontManager.getRunescapeSmallFont());
			final FontMetrics gfm = g.getFontMetrics();
			final int mx = r.x + NODE_D / 2, my = r.y + NODE_D / 2;
			if (done)
			{
				g.setColor(LofQuestState.FINISHED.getColor());
				final Stroke s = g.getStroke();
				g.setStroke(new BasicStroke(1.8f));
				g.drawLine(mx - 4, my, mx - 1, my + 3);
				g.drawLine(mx - 1, my + 3, mx + 5, my - 4);
				g.setStroke(s);
			}
			else if (isCur)
			{
				g.setColor(LofTheme.TEXT);
				g.fillOval(mx - 3, my - 3, 6, 6);
			}
			else
			{
				final String num = Integer.toString(i + 1);
				final Color gc = st == LofQuestState.LOCKED ? new Color(158, 149, 140, 150) : LofTheme.TEXT_DIM;
				LofTheme.shadowText(g, num, mx - gfm.stringWidth(num) / 2, my + 5, gc);
			}

			// focus ring
			if (q == focus)
			{
				g.setColor(LofTheme.GOLD);
				g.setStroke(new BasicStroke(1.2f));
				g.drawOval(r.x - 3, r.y - 3, NODE_D + 6, NODE_D + 6);
				g.setStroke(old);
			}
			if (isCur)
			{
				final String you = "YOU";
				LofTheme.shadowText(g, you, r.x + (NODE_D - gfm.stringWidth(you)) / 2, oy + YOU_Y, LofTheme.EMBER);
			}
		}
	}

	private void drawFooter(Graphics2D g, int ox, int oy, Point mouse)
	{
		final Rectangle tr = trackRect(ox, oy);
		final boolean tHov = trackable && tr.contains(mouse);
		if (!trackable)
		{
			// disabled Track for locked/future quests
			LofModal.button(g, tr, "Track", LofTheme.GOLD_DIM, false, false);
		}
		else if (trackedOn)
		{
			LofModal.button(g, tr, "Tracking", LofTheme.GOLD, true, tHov);
		}
		else
		{
			LofModal.button(g, tr, "Track", LofTheme.GOLD_DIM, true, tHov);
		}

		final Rectangle cr = closeBtnRect(ox, oy);
		LofModal.button(g, cr, "Close", LofTheme.EMBER, true, cr.contains(mouse));
	}

	// --- helpers ---
	private FontMetrics smallMetrics(Graphics2D g)
	{
		g.setFont(FontManager.getRunescapeSmallFont());
		return g.getFontMetrics();
	}

	private static List<String> wrap(FontMetrics fm, String text, int maxW)
	{
		final List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" "))
		{
			final String test = line.length() == 0 ? word : line + " " + word;
			if (fm.stringWidth(test) > maxW && line.length() > 0)
			{
				lines.add(line.toString());
				line = new StringBuilder(word);
			}
			else
			{
				line = new StringBuilder(test);
			}
		}
		if (line.length() > 0)
		{
			lines.add(line.toString());
		}
		return lines;
	}

	private static int clamp(int v, int lo, int hi)
	{
		return Math.max(lo, Math.min(v, hi));
	}

	private Point mousePoint()
	{
		final net.runelite.api.Point m = client.getMouseCanvasPosition();
		return m == null ? new Point(-1, -1) : new Point(m.getX(), m.getY());
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
}
