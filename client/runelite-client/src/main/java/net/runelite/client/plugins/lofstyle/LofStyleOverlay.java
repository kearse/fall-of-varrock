/*
 * Fall of Varrock — Character Style window (renderer + hit-testing).
 *
 * The classic OSRS design screen, rebuilt on-brand: a centred framed modal whose MIDDLE is a
 * rounded cutout straight through the panel — the game world shows through it, and since the
 * window centres on the viewport your own character stands framed in the hole as the live 3D
 * model (the server hides your worn gear while the window is open, so the identikit you're
 * editing is what you see). Style rows with ◀ ▶ arrows run down the left, colour rows down
 * the right, gender under the colours, DONE under the portrait.
 */
package net.runelite.client.plugins.lofstyle;

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
import java.awt.geom.Area;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
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

class LofStyleOverlay extends Overlay implements LofWindows.Window
{
	// hit-test result codes
	static final int OUTSIDE = -1;
	static final int INSIDE = 0;
	static final int CLOSE = 1;
	static final int DONE = 2;
	static final int GENDER_MALE = 3;
	static final int GENDER_FEMALE = 4;
	static final int ARROW_BASE = 100; // + row*2 + (0 prev, 1 next)

	/** One editable row: kind 0 = body-part look (server option id), kind 1 = colour slot. */
	static final int[][] ROWS = {
		// left column — the full classic body-part set
		{0, 0}, // Hairstyle
		{0, 1}, // Facial hair (male only)
		{0, 2}, // Shirt
		{0, 3}, // Sleeves
		{0, 4}, // Hands
		{0, 5}, // Trousers
		{0, 6}, // Boots
		// right column — colours (classic order-ish, skin high because it's the popular one)
		{1, 0}, // Hair colour
		{1, 4}, // Skin tone
		{1, 1}, // Shirt colour
		{1, 2}, // Leg colour
		{1, 3}, // Boot colour
	};
	private static final String[] LABELS = {
		"Hairstyle", "Facial hair", "Shirt", "Sleeves", "Hands", "Trousers", "Boots",
		"Hair colour", "Skin tone", "Shirt colour", "Leg colour", "Boot colour",
	};
	static final int LEFT_ROWS = 7; // rows [0,LEFT_ROWS) draw left, the rest right

	private static final int WIN_W = LofModal.W;
	private static final int WIN_H = 420; // taller than the standard modal to frame the character model
	private static final int ARC = 14;
	private static final int TITLE_H = 38;
	private static final int PAD = 14;
	private static final int COL_W = 150;
	private static final int ROW_STEP = 30;
	private static final int ROW_H = 24;
	private static final int ARROW_W = 22;
	// the see-through portrait: everything between the two columns
	private static final int HOLE_X = PAD + COL_W + 6;      // 170
	private static final int HOLE_W = WIN_W - 2 * HOLE_X;   // 140
	private static final int HOLE_Y = 60;
	private static final int HOLE_H = WIN_H - HOLE_Y - 58;  // 302

	private final Client client;

	private boolean visible;
	private boolean female;
	private Runnable closeNotifier;

	@Inject
	private LofStyleOverlay(Client client)
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

	/** Ran on every close that ISN'T a DONE, so the server re-equips the preview. */
	void setCloseNotifier(Runnable r)
	{
		closeNotifier = r;
	}

	void setFemale(boolean f)
	{
		female = f;
	}

	boolean isFemale()
	{
		return female;
	}

	/** The facial-hair row is inert for female characters (the female identikit has no jaw). */
	boolean rowEnabled(int row)
	{
		return !(female && row == 1);
	}

	private int originX()
	{
		return LofModal.originX(client); // same 480 width as the standard modal
	}

	/** Centre on the game viewport (334px tall in fixed mode), not the whole canvas, so the
	 *  portrait hole frames the player rather than drifting down over the chatbox. */
	private int originY()
	{
		final int canvasH = client.getCanvasHeight();
		final int viewportH = client.isResized() ? canvasH : Math.min(canvasH, 334);
		return Math.max(0, Math.min((canvasH - WIN_H) / 2, (viewportH - WIN_H) / 2));
	}

	private Rectangle rowRect(int ox, int oy, int row)
	{
		final boolean left = row < LEFT_ROWS;
		final int idx = left ? row : row - LEFT_ROWS;
		final int x = left ? ox + PAD : ox + WIN_W - PAD - COL_W;
		return new Rectangle(x, oy + HOLE_Y + idx * ROW_STEP, COL_W, ROW_H);
	}

	private Rectangle arrowRect(int ox, int oy, int row, int dir)
	{
		final Rectangle r = rowRect(ox, oy, row);
		final int x = dir == 0 ? r.x : r.x + r.width - ARROW_W;
		return new Rectangle(x, r.y, ARROW_W, ROW_H);
	}

	private Rectangle genderRect(int ox, int oy, int i)
	{
		final int w = (COL_W - 8) / 2;
		final int x = ox + WIN_W - PAD - COL_W + i * (w + 8);
		return new Rectangle(x, oy + HOLE_Y + 5 * ROW_STEP + 24, w, ROW_H);
	}

	private Rectangle holeRect(int ox, int oy)
	{
		return new Rectangle(ox + HOLE_X, oy + HOLE_Y, HOLE_W, HOLE_H);
	}

	private Rectangle doneRect(int ox, int oy)
	{
		return new Rectangle(ox + HOLE_X, oy + WIN_H - 46, HOLE_W, 32);
	}

	int hitTest(Point p)
	{
		if (!visible)
		{
			return OUTSIDE;
		}
		final int ox = originX();
		final int oy = originY();
		if (!new Rectangle(ox, oy, WIN_W, WIN_H).contains(p))
		{
			return OUTSIDE;
		}
		if (LofModal.closeRect(ox, oy).contains(p))
		{
			return CLOSE;
		}
		if (doneRect(ox, oy).contains(p))
		{
			return DONE;
		}
		if (genderRect(ox, oy, 0).contains(p))
		{
			return GENDER_MALE;
		}
		if (genderRect(ox, oy, 1).contains(p))
		{
			return GENDER_FEMALE;
		}
		for (int row = 0; row < ROWS.length; row++)
		{
			if (!rowEnabled(row))
			{
				continue;
			}
			if (arrowRect(ox, oy, row, 0).contains(p))
			{
				return ARROW_BASE + row * 2;
			}
			if (arrowRect(ox, oy, row, 1).contains(p))
			{
				return ARROW_BASE + row * 2 + 1;
			}
		}
		// includes the portrait hole: clicking your model must never walk you out of frame
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

		final int ox = originX();
		final int oy = originY();
		final Point mouse = mousePoint();
		final Rectangle hole = holeRect(ox, oy);

		// panel with the portrait punched straight through (the game world is the preview)
		final Area holeArea = new Area(new RoundRectangle2D.Float(hole.x, hole.y, hole.width, hole.height, 10, 10));
		final Area shadow = new Area(new RoundRectangle2D.Float(ox + 3, oy + 4, WIN_W, WIN_H, ARC + 4, ARC + 4));
		shadow.subtract(holeArea);
		g.setColor(LofTheme.SHADOW);
		g.fill(shadow);
		final Area panel = new Area(new RoundRectangle2D.Float(ox, oy, WIN_W, WIN_H, ARC, ARC));
		panel.subtract(holeArea);
		g.setColor(LofTheme.PANEL_OPAQUE);
		g.fill(panel);
		g.setColor(LofTheme.alpha(LofTheme.EMBER_DARK, 210));
		g.drawRoundRect(ox, oy, WIN_W - 1, WIN_H - 1, ARC, ARC);

		// header
		final Shape headerClip = g.getClip();
		g.setClip(ox, oy, WIN_W, TITLE_H);
		g.setColor(LofTheme.HEADER);
		g.fillRoundRect(ox, oy, WIN_W, TITLE_H + ARC, ARC, ARC);
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
		LofTheme.shadowText(g, "Character Style", titleX, oy + 25, LofTheme.GOLD);
		g.setFont(FontManager.getRunescapeSmallFont());
		final String sub = "free restyle";
		LofTheme.shadowText(g, sub, ox + WIN_W - 44 - g.getFontMetrics().stringWidth(sub), oy + 24, LofTheme.TEXT_DIM);

		// close ✕
		final Rectangle cr = LofModal.closeRect(ox, oy);
		final boolean closeHov = cr.contains(mouse);
		g.setColor(closeHov ? LofTheme.EMBER : new Color(255, 255, 255, 18));
		g.fillRoundRect(cr.x, cr.y, cr.width, cr.height, 6, 6);
		g.setColor(closeHov ? LofTheme.TEXT : LofTheme.TEXT_DIM);
		final Stroke oldStroke = g.getStroke();
		g.setStroke(new BasicStroke(1.6f));
		g.drawLine(cr.x + 6, cr.y + 6, cr.x + cr.width - 7, cr.y + cr.height - 7);
		g.drawLine(cr.x + cr.width - 7, cr.y + 6, cr.x + 6, cr.y + cr.height - 7);
		g.setStroke(oldStroke);

		// portrait frame: a gold sill around the hole so it reads as the model window
		g.setStroke(new BasicStroke(1.4f));
		g.setColor(LofTheme.alpha(LofTheme.GOLD_DIM, 170));
		g.drawRoundRect(hole.x, hole.y, hole.width - 1, hole.height - 1, 10, 10);
		g.setColor(LofTheme.alpha(LofTheme.EMBER_DARK, 150));
		g.drawRoundRect(hole.x - 2, hole.y - 2, hole.width + 3, hole.height + 3, 12, 12);
		g.setStroke(oldStroke);

		// section labels
		g.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics fm = g.getFontMetrics();
		LofTheme.shadowText(g, "STYLE", ox + PAD + (COL_W - fm.stringWidth("STYLE")) / 2, oy + HOLE_Y - 6, LofTheme.GOLD_DIM);
		LofTheme.shadowText(g, "COLOURS", ox + WIN_W - PAD - COL_W + (COL_W - fm.stringWidth("COLOURS")) / 2, oy + HOLE_Y - 6, LofTheme.GOLD_DIM);
		LofTheme.shadowText(g, "YOUR LOOK", ox + (WIN_W - fm.stringWidth("YOUR LOOK")) / 2, oy + HOLE_Y - 6, LofTheme.GOLD_DIM);

		// rows
		g.setFont(FontManager.getRunescapeFont());
		fm = g.getFontMetrics();
		for (int row = 0; row < ROWS.length; row++)
		{
			final Rectangle r = rowRect(ox, oy, row);
			final boolean enabled = rowEnabled(row);
			g.setColor(LofTheme.ROW);
			g.fillRoundRect(r.x + ARROW_W + 3, r.y, r.width - 2 * (ARROW_W + 3), r.height, 6, 6);
			final String label = row == 1 && female ? "gents only" : LABELS[row];
			LofTheme.shadowText(g, label,
				r.x + (r.width - fm.stringWidth(label)) / 2, r.y + 16,
				enabled ? LofTheme.TEXT : LofTheme.TEXT_DIM);
			drawArrow(g, arrowRect(ox, oy, row, 0), false, enabled, mouse);
			drawArrow(g, arrowRect(ox, oy, row, 1), true, enabled, mouse);
		}

		// gender, under the colour rows
		g.setFont(FontManager.getRunescapeSmallFont());
		fm = g.getFontMetrics();
		LofTheme.shadowText(g, "GENDER",
			ox + WIN_W - PAD - COL_W + (COL_W - fm.stringWidth("GENDER")) / 2,
			oy + HOLE_Y + 5 * ROW_STEP + 16, LofTheme.GOLD_DIM);
		LofModal.button(g, genderRect(ox, oy, 0), "Male", female ? LofTheme.GOLD_DIM : LofTheme.GOLD, true,
			genderRect(ox, oy, 0).contains(mouse) || !female);
		LofModal.button(g, genderRect(ox, oy, 1), "Female", female ? LofTheme.GOLD : LofTheme.GOLD_DIM, true,
			genderRect(ox, oy, 1).contains(mouse) || female);
		g.setFont(FontManager.getRunescapeSmallFont());
		final int noteX = ox + WIN_W - PAD - COL_W;
		LofTheme.shadowText(g, "Switching resets body", noteX, oy + HOLE_Y + 5 * ROW_STEP + 66, LofTheme.TEXT_DIM);
		LofTheme.shadowText(g, "style; colours stay.", noteX, oy + HOLE_Y + 5 * ROW_STEP + 78, LofTheme.TEXT_DIM);

		// left-column footnote: why the gear vanished
		LofTheme.shadowText(g, "Your gear is set aside", ox + PAD, oy + HOLE_Y + LEFT_ROWS * ROW_STEP + 14, LofTheme.TEXT_DIM);
		LofTheme.shadowText(g, "while you restyle - it", ox + PAD, oy + HOLE_Y + LEFT_ROWS * ROW_STEP + 26, LofTheme.TEXT_DIM);
		LofTheme.shadowText(g, "returns when you close.", ox + PAD, oy + HOLE_Y + LEFT_ROWS * ROW_STEP + 38, LofTheme.TEXT_DIM);

		// done, under the portrait
		LofModal.button(g, doneRect(ox, oy), "DONE", LofTheme.GOLD, true, doneRect(ox, oy).contains(mouse));

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(WIN_W, WIN_H);
	}

	private void drawArrow(Graphics2D g, Rectangle r, boolean next, boolean enabled, Point mouse)
	{
		final boolean hov = enabled && r.contains(mouse);
		g.setColor(hov ? LofTheme.alpha(LofTheme.EMBER, 60) : LofTheme.alpha(LofTheme.GOLD, enabled ? 18 : 8));
		g.fillRoundRect(r.x, r.y, r.width, r.height, 6, 6);
		g.setColor(enabled ? (hov ? LofTheme.EMBER : LofTheme.GOLD) : LofTheme.alpha(LofTheme.TEXT_DIM, 90));
		final int cx = r.x + r.width / 2, cy = r.y + r.height / 2;
		final int d = next ? 1 : -1;
		g.fillPolygon(
			new int[]{cx + d * 4, cx - d * 3, cx - d * 3},
			new int[]{cy, cy - 6, cy + 6}, 3);
	}

	@Override
	public boolean isWindowVisible()
	{
		return visible;
	}

	/** Any non-DONE dismissal (✕, another window opening, a foreign modal) — tell the server
	 *  so it puts the player's gear back on. */
	@Override
	public void hideWindow()
	{
		if (!visible)
		{
			return;
		}
		visible = false;
		if (closeNotifier != null)
		{
			closeNotifier.run();
		}
	}

	private Point mousePoint()
	{
		final net.runelite.api.Point m = client.getMouseCanvasPosition();
		return m == null ? new Point(-1, -1) : new Point(m.getX(), m.getY());
	}
}
