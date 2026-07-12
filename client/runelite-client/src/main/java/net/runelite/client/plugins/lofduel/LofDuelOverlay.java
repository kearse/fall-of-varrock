/*
 * Fall of Varrock — Duel Arena rules overlay (renderer + hit-testing).
 *
 * A themed rules-grid window drawn client-side (cache interfaces crash our client). The server
 * (DuelRulesClientMenu) publishes the shared state in one packed varp 4630; this overlay reads it
 * every frame and draws the 12 rule toggles + accept/decline. Clicks send "::duel <action>" back
 * (t<i> toggle, a accept, d decline), which the server intercepts. Label order MUST match the
 * server's DuelRulesClientMenu.LABELS.
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
	static final int CHIP_BASE = 100;

	/** MUST match DuelRulesClientMenu.LABELS order. */
	private static final String[] LABELS = {
		"No Melee", "No Ranged", "No Magic", "No Prayer", "No Food", "No Drinks",
		"No Movement", "Boxing (no gear)", "Whip only", "DDS only", "Fun weapons", "Allow companions",
	};

	private static final int WIN_W = 470;
	private static final int WIN_H = 372;
	private static final int WIN_ARC = 14;
	private static final int TITLE_H = 38;
	private static final int PAD = 12;
	private static final int GRID_TOP = TITLE_H + 34;
	private static final int COLS = 2;
	private static final int ROWS = 6;
	private static final int CHIP_GAP = 8;
	private static final int CHIP_H = 30;
	private static final int CHIP_W = (WIN_W - PAD * 2 - CHIP_GAP) / COLS;   // 219
	private static final int BTN_H = 34;

	private static final Color GREEN = new Color(110, 205, 110);

	private final Client client;
	private final LofDuelConfig config;

	@Inject
	private LofDuelOverlay(Client client, LofDuelConfig config)
	{
		this.client = client;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	boolean isShowing()
	{
		return config.enabled()
			&& client.getGameState() == GameState.LOGGED_IN
			&& (client.getVarpValue(STATE_VARP) & 0x1) != 0;
	}

	private int originX() { return Math.max(0, (client.getCanvasWidth() - WIN_W) / 2); }
	private int originY() { return Math.max(0, (client.getCanvasHeight() - WIN_H) / 2); }

	private Rectangle chipRect(int ox, int oy, int i)
	{
		final int col = i / ROWS;                 // col-major: 0..5 left, 6..11 right
		final int row = i % ROWS;
		final int x = ox + PAD + col * (CHIP_W + CHIP_GAP);
		final int y = oy + GRID_TOP + row * (CHIP_H + CHIP_GAP);
		return new Rectangle(x, y, CHIP_W, CHIP_H);
	}

	private Rectangle acceptRect(int ox, int oy) { return new Rectangle(ox + PAD, oy + WIN_H - PAD - BTN_H, (WIN_W - PAD * 2 - CHIP_GAP) / 2, BTN_H); }
	private Rectangle declineRect(int ox, int oy) { final Rectangle a = acceptRect(ox, oy); return new Rectangle(a.x + a.width + CHIP_GAP, a.y, a.width, BTN_H); }

	int hitTest(Point p)
	{
		if (!isShowing()) return OUTSIDE;
		final int ox = originX(), oy = originY();
		if (!new Rectangle(ox, oy, WIN_W, WIN_H).contains(p)) return OUTSIDE;
		if (acceptRect(ox, oy).contains(p)) return ACCEPT;
		if (declineRect(ox, oy).contains(p)) return DECLINE;
		for (int i = 0; i < LABELS.length; i++)
		{
			if (chipRect(ox, oy, i).contains(p)) return CHIP_BASE + i;
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

		final int state = client.getVarpValue(STATE_VARP);
		final boolean myAccept = (state & (1 << 13)) != 0;
		final boolean theirAccept = (state & (1 << 14)) != 0;
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
		if (logo != null)
		{
			g.drawImage(logo, ox + 12, oy + 5, 28, 28, null);
			titleX = ox + 46;
		}
		g.setFont(FontManager.getRunescapeBoldFont());
		LofTheme.shadowText(g, "Duel Arena", titleX, oy + 25, LofTheme.GOLD);
		g.setFont(FontManager.getRunescapeSmallFont());
		LofTheme.shadowText(g, "COMBAT RULES", ox + PAD + 2, oy + TITLE_H + 22, LofTheme.GOLD_DIM);

		// rule chips
		g.setFont(FontManager.getRunescapeFont());
		for (int i = 0; i < LABELS.length; i++)
		{
			final boolean on = (state & (1 << (i + 1))) != 0;
			final Rectangle rc = chipRect(ox, oy, i);
			final boolean hov = rc.contains(mouse);
			chip(g, rc, LABELS[i], on, hov);
		}

		// status line
		final String status;
		if (myAccept && !theirAccept)
		{
			status = "Waiting for the other player…";
		}
		else if (!myAccept && theirAccept)
		{
			status = "Opponent has accepted — waiting on you.";
		}
		else
		{
			status = "Either player can change a rule; both must accept.";
		}
		g.setFont(FontManager.getRunescapeSmallFont());
		LofTheme.shadowText(g, status, ox + PAD + 2, oy + WIN_H - PAD - BTN_H - 8,
			(myAccept ^ theirAccept) ? GREEN : LofTheme.TEXT_DIM);

		// buttons
		g.setFont(FontManager.getRunescapeBoldFont());
		final Rectangle acc = acceptRect(ox, oy);
		final Rectangle dec = declineRect(ox, oy);
		button(g, acc, myAccept ? "✔ Accepted" : "Accept", LofTheme.GOLD, myAccept, acc.contains(mouse));
		button(g, dec, "Decline", LofTheme.EMBER, false, dec.contains(mouse));

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(WIN_W, WIN_H);
	}

	private static void chip(Graphics2D g, Rectangle rc, String label, boolean on, boolean hov)
	{
		g.setColor(on ? LofTheme.alpha(GREEN, 34) : (hov ? LofTheme.ROW_HOVER : LofTheme.ROW));
		g.fillRoundRect(rc.x, rc.y, rc.width, rc.height, 8, 8);
		g.setColor(on ? LofTheme.alpha(GREEN, 110) : LofTheme.alpha(LofTheme.EMBER, hov ? 130 : 40));
		g.drawRoundRect(rc.x, rc.y, rc.width - 1, rc.height - 1, 8, 8);
		// checkbox
		final int bx = rc.x + 8, by = rc.y + (rc.height - 14) / 2;
		g.setColor(on ? GREEN : new Color(0, 0, 0, 120));
		g.fillRoundRect(bx, by, 14, 14, 4, 4);
		if (on)
		{
			final Stroke old = g.getStroke();
			g.setColor(new Color(20, 20, 20));
			g.setStroke(new BasicStroke(2f));
			g.drawLine(bx + 3, by + 7, bx + 6, by + 10);
			g.drawLine(bx + 6, by + 10, bx + 11, by + 3);
			g.setStroke(old);
		}
		LofTheme.shadowText(g, label, bx + 20, rc.y + rc.height / 2 + 5, on ? LofTheme.TEXT : LofTheme.TEXT_DIM);
	}

	private static void button(Graphics2D g, Rectangle rc, String label, Color accent, boolean active, boolean hov)
	{
		g.setColor(active ? LofTheme.alpha(accent, 46) : (hov ? LofTheme.alpha(accent, 30) : new Color(255, 255, 255, 12)));
		g.fillRoundRect(rc.x, rc.y, rc.width, rc.height, 9, 9);
		g.setColor(LofTheme.alpha(accent, hov || active ? 200 : 120));
		final Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(1.4f));
		g.drawRoundRect(rc.x, rc.y, rc.width - 1, rc.height - 1, 9, 9);
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
