/*
 * Fall of Varrock — the Gambler's Table (renderer + hit-testing).
 *
 * The big ember die with the last roll burned in, stake chips (10K / 100K / 1M / 10M / x2 /
 * MAX / CLEAR), the odds printed on the felt, a win/lose banner and the last-rolls rail.
 * Coins are read from the inventory; the ROLL button lights only for a payable stake.
 */
package net.runelite.client.plugins.lofdice;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;
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
import net.runelite.client.util.ImageUtil;

class LofDiceOverlay extends Overlay implements LofWindows.Window
{
	// hit-test result codes
	static final int OUTSIDE = -1;
	static final int INSIDE = 0;
	static final int CLOSE = 1;
	static final int ROLL = 2;
	static final int CHIP_BASE = 50; // 10K, 100K, 1M, 10M, x2, MAX, CLEAR

	static final long[] CHIP_ADD = {10_000, 100_000, 1_000_000, 10_000_000};
	private static final String[] CHIP_LABELS = {"10K", "100K", "1M", "10M", "x2", "MAX", "CLEAR"};
	private static final long MAX_BET = 100_000_000L; // mirror of server GamblingPlugin.MAX_BET
	private static final double RAKE = 0.05;          // mirror of server GamblingPlugin.RAKE

	private static final int DIE_Y = LofModal.TITLE_H + 16;
	private static final int DIE_SIZE = 84;
	private static final int CHIPS_Y = DIE_Y + DIE_SIZE + 18;
	private static final int STAKE_Y = CHIPS_Y + 34;
	private static final int HIST_Y = STAKE_Y + 44;

	private final Client client;

	private boolean visible;
	private long stake;
	private long stakeAtRoll; // snapshot when ROLL is sent — chips clicked mid-flight must not skew the banner
	private int lastRoll = -1;
	private boolean lastWin;
	private long lastPayout;
	private final Deque<int[]> history = new ArrayDeque<>(); // {roll, win}

	private BufferedImage tankards;
	private boolean iconLoaded;

	@Inject
	private LofDiceOverlay(Client client)
	{
		this.client = client;
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

	long getStake()
	{
		return stake;
	}

	void applyChip(int chip)
	{
		if (chip < CHIP_ADD.length)
		{
			stake += CHIP_ADD[chip];
		}
		else if (chip == 4)
		{
			stake *= 2;
		}
		else if (chip == 5)
		{
			// ALL IN — cached count; applyChip runs on the mouse thread.
			stake = Math.min(coinsCached, MAX_BET);
		}
		else
		{
			stake = 0;
		}
		stake = Math.max(0, Math.min(stake, MAX_BET));
	}

	/** Called by the mouse listener the moment a roll is sent, before the round trip. */
	void onRollSent()
	{
		stakeAtRoll = stake;
	}

	void onResult(int roll, boolean win)
	{
		lastRoll = roll;
		lastWin = win;
		lastPayout = win ? (long) (stakeAtRoll * 2L * (1.0 - RAKE)) : 0;
		history.addFirst(new int[]{roll, win ? 1 : 0});
		while (history.size() > 8)
		{
			history.removeLast();
		}
	}

	// Coins carried, sampled on the CLIENT thread by render(). Reading the inventory container off
	// the client thread returns null (→ 0 coins), which made canRoll() false, so hitTest answered
	// INSIDE and the ROLL click was swallowed while the button was drawn lit. The click path must
	// read ONLY this cached value. Same fix, same reason, as LofShopTabsOverlay's cached showing.
	private volatile long coinsCached;

	private boolean canRoll()
	{
		return stake > 0 && stake <= coinsCached;
	}

	private Rectangle chipRect(int ox, int oy, int i)
	{
		return new Rectangle(ox + LofModal.PAD + i * 64, oy + CHIPS_Y, 58, 26);
	}

	private Rectangle rollRect(int ox, int oy)
	{
		return new Rectangle(ox + LofModal.W - LofModal.PAD - 240, oy + LofModal.H - 12 - 32, 240, 32);
	}

	int hitTest(Point p)
	{
		if (!visible)
		{
			return OUTSIDE;
		}
		final int ox = LofModal.originX(client), oy = LofModal.originY(client);
		if (!new Rectangle(ox, oy, LofModal.W, LofModal.H).contains(p))
		{
			return OUTSIDE;
		}
		if (LofModal.closeRect(ox, oy).contains(p))
		{
			return CLOSE;
		}
		for (int i = 0; i < CHIP_LABELS.length; i++)
		{
			if (chipRect(ox, oy, i).contains(p))
			{
				return CHIP_BASE + i;
			}
		}
		if (canRoll() && rollRect(ox, oy).contains(p))
		{
			return ROLL;
		}
		return INSIDE;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!visible || client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}
		final Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		final Rectangle selfBounds = getBounds();
		g.translate(-selfBounds.x, -selfBounds.y);
		if (!iconLoaded)
		{
			iconLoaded = true;
			try
			{
				tankards = ImageUtil.loadImageResource(LofDiceOverlay.class, "table_tankards.png");
			}
			catch (Exception e)
			{
				tankards = null;
			}
		}

		final int ox = LofModal.originX(client), oy = LofModal.originY(client);
		final Point mouse = mousePoint();
		LofModal.frame(g, ox, oy, "The Gambler's Table", "roll 51+ to win", mouse);

		// the die
		final int dx = ox + LofModal.PAD + 4, dy = oy + DIE_Y;
		g.setColor(new Color(18, 13, 11));
		g.fillRoundRect(dx, dy, DIE_SIZE, DIE_SIZE, 16, 16);
		final Stroke oldStroke = g.getStroke();
		g.setStroke(new BasicStroke(2f));
		g.setColor(LofTheme.EMBER);
		g.drawRoundRect(dx, dy, DIE_SIZE, DIE_SIZE, 16, 16);
		g.setStroke(oldStroke);
		final Font dieFont = FontManager.getRunescapeBoldFont().deriveFont(34f);
		g.setFont(dieFont);
		final String face = lastRoll >= 0 ? String.valueOf(lastRoll) : "?";
		final FontMetrics dfm = g.getFontMetrics();
		LofTheme.shadowText(g, face, dx + (DIE_SIZE - dfm.stringWidth(face)) / 2, dy + DIE_SIZE / 2 + 12, LofTheme.GOLD);

		if (tankards != null)
		{
			g.drawImage(tankards, dx + DIE_SIZE + 14, dy + 2, 44, 44, null);
		}

		// result banner + odds
		g.setFont(FontManager.getRunescapeFont());
		final int tx = dx + DIE_SIZE + 70;
		if (lastRoll >= 0)
		{
			if (lastWin)
			{
				LofTheme.shadowText(g, "WINNER — the house pays " + LofModal.fmt(lastPayout) + " coins", tx, dy + 18, new Color(110, 205, 110));
			}
			else
			{
				LofTheme.shadowText(g, "The house wins this one.", tx, dy + 18, new Color(255, 138, 117));
			}
		}
		else
		{
			LofTheme.shadowText(g, "Care to test your luck?", tx, dy + 18, LofTheme.TEXT);
		}
		g.setFont(FontManager.getRunescapeSmallFont());
		LofTheme.shadowText(g, "Percentile die: 51-100 wins, pays 2x stake", tx, dy + 40, LofTheme.TEXT_DIM);
		LofTheme.shadowText(g, "minus the 5% house cut. Limit " + LofModal.fmt(MAX_BET) + " a roll.", tx, dy + 54, LofTheme.TEXT_DIM);
		LofTheme.shadowText(g, "Every roll is recorded.", tx, dy + 68, LofTheme.TEXT_DIM);

		// stake chips
		for (int i = 0; i < CHIP_LABELS.length; i++)
		{
			final Rectangle cr = chipRect(ox, oy, i);
			final boolean mod = i >= CHIP_ADD.length;
			LofModal.button(g, cr, CHIP_LABELS[i], mod ? LofTheme.GOLD_DIM : LofTheme.GOLD, true, cr.contains(mouse));
		}

		// stake line
		final Rectangle sr = new Rectangle(ox + LofModal.PAD, oy + STAKE_Y, LofModal.W - 2 * LofModal.PAD, 32);
		g.setColor(new Color(0, 0, 0, 90));
		g.fillRoundRect(sr.x, sr.y, sr.width, sr.height, 8, 8);
		g.setColor(LofTheme.alpha(LofTheme.GOLD, 80));
		g.drawRoundRect(sr.x, sr.y, sr.width, sr.height, 8, 8);
		g.setFont(FontManager.getRunescapeFont());
		LofTheme.shadowText(g, "Your stake:  " + LofModal.fmt(stake), sr.x + 12, sr.y + 21, LofTheme.GOLD);
		final String winPays = stake > 0 ? "a win pays " + LofModal.fmt((long) (stake * 2L * (1.0 - RAKE))) : "";
		g.setFont(FontManager.getRunescapeSmallFont());
		final FontMetrics fm = g.getFontMetrics();
		LofTheme.shadowText(g, winPays, sr.x + sr.width - 12 - fm.stringWidth(winPays), sr.y + 20, LofTheme.TEXT_DIM);

		// history rail
		LofTheme.shadowText(g, "LAST ROLLS", ox + LofModal.PAD, oy + HIST_Y + 12, LofTheme.GOLD_DIM);
		int hx = ox + LofModal.PAD + 76;
		for (int[] h : history)
		{
			final boolean win = h[1] == 1;
			g.setColor(win ? new Color(110, 205, 110) : new Color(184, 84, 63));
			g.fillOval(hx, oy + HIST_Y, 20, 20);
			final String v = String.valueOf(h[0]);
			g.setColor(Color.BLACK);
			g.drawString(v, hx + 10 - fm.stringWidth(v) / 2, oy + HIST_Y + 14);
			hx += 26;
		}

		// footer
		final long coins = LofModal.carried(client, LofModal.COINS_ID);
		coinsCached = coins; // publish for the mouse thread before canRoll() is consulted
		LofTheme.shadowText(g, "Coins carried: " + LofModal.fmt(coins), ox + LofModal.PAD, oy + LofModal.H - 22, LofTheme.TEXT_DIM);
		LofModal.button(g, rollRect(ox, oy), stake > 0 ? "ROLL THE DIE — " + LofModal.fmt(stake) : "ROLL THE DIE",
			LofTheme.EMBER, canRoll(), rollRect(ox, oy).contains(mouse));

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(LofModal.W, LofModal.H);
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
}
