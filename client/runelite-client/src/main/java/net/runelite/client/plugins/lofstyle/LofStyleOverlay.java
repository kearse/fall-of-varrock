/*
 * Fall of Varrock — Character Style window (renderer + hit-testing).
 *
 * A compact LEFT-ANCHORED panel (so your character stays visible beside it — the world model
 * IS the live preview): ◀ ▶ rows for hairstyle / facial hair / default clothes, colour rows,
 * gender buttons and DONE. Styled with LofTheme like every lof window.
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
		{0, 0}, // Hairstyle
		{0, 1}, // Facial hair (male only)
		{0, 2}, // Shirt style
		{0, 3}, // Sleeves
		{0, 5}, // Trousers
		{1, 0}, // Hair colour
		{1, 4}, // Skin tone
		{1, 1}, // Shirt colour
		{1, 2}, // Leg colour
	};
	private static final String[] LABELS = {
		"Hairstyle", "Facial hair", "Shirt style", "Sleeves", "Trousers",
		"Hair colour", "Skin tone", "Shirt colour", "Leg colour",
	};
	private static final int STYLE_ROWS = 5; // first N rows are looks, the rest colours

	private static final int WIN_W = 264;
	private static final int WIN_H = 392;
	private static final int WIN_X = 16; // left-anchored: your character stays in view
	private static final int TITLE_H = 38;
	private static final int PAD = 12;
	private static final int ROW_STEP = 26;
	private static final int ROW_H = 22;
	private static final int ARROW_W = 22;

	private final Client client;

	private boolean visible;
	private boolean female;

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

	private int originY()
	{
		return Math.max(0, (client.getCanvasHeight() - WIN_H) / 2);
	}

	private int rowY(int oy, int row)
	{
		// section labels sit above the looks block and the colours block
		final int sectionGap = row >= STYLE_ROWS ? 22 : 0;
		return oy + TITLE_H + 26 + sectionGap + row * ROW_STEP;
	}

	private Rectangle arrowRect(int oy, int row, int dir)
	{
		final int y = rowY(oy, row);
		final int x = dir == 0 ? WIN_X + PAD : WIN_X + WIN_W - PAD - ARROW_W;
		return new Rectangle(x, y, ARROW_W, ROW_H);
	}

	private Rectangle genderRect(int oy, int i)
	{
		final int w = (WIN_W - 2 * PAD - 8) / 2;
		return new Rectangle(WIN_X + PAD + i * (w + 8), oy + WIN_H - 84, w, 26);
	}

	private Rectangle doneRect(int oy)
	{
		return new Rectangle(WIN_X + PAD, oy + WIN_H - 44, WIN_W - 2 * PAD, 32);
	}

	private Rectangle closeRect(int oy)
	{
		return new Rectangle(WIN_X + WIN_W - 30, oy + 9, 20, 20);
	}

	int hitTest(Point p)
	{
		if (!visible)
		{
			return OUTSIDE;
		}
		final int oy = originY();
		if (!new Rectangle(WIN_X, oy, WIN_W, WIN_H).contains(p))
		{
			return OUTSIDE;
		}
		if (closeRect(oy).contains(p))
		{
			return CLOSE;
		}
		if (doneRect(oy).contains(p))
		{
			return DONE;
		}
		if (genderRect(oy, 0).contains(p))
		{
			return GENDER_MALE;
		}
		if (genderRect(oy, 1).contains(p))
		{
			return GENDER_FEMALE;
		}
		for (int row = 0; row < ROWS.length; row++)
		{
			if (!rowEnabled(row))
			{
				continue;
			}
			if (arrowRect(oy, row, 0).contains(p))
			{
				return ARROW_BASE + row * 2;
			}
			if (arrowRect(oy, row, 1).contains(p))
			{
				return ARROW_BASE + row * 2 + 1;
			}
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

		final int oy = originY();
		final Point mouse = mousePoint();

		LofTheme.panel(g, WIN_X, oy, WIN_W, WIN_H, 14);

		// header
		final Shape headerClip = g.getClip();
		g.setClip(WIN_X, oy, WIN_W, TITLE_H);
		g.setColor(LofTheme.HEADER);
		g.fillRoundRect(WIN_X, oy, WIN_W, TITLE_H + 14, 14, 14);
		g.setClip(headerClip);
		LofTheme.emberUnderline(g, WIN_X + 1, oy + TITLE_H - 2, WIN_W - 2);
		final BufferedImage logo = LofTheme.logo();
		int titleX = WIN_X + 12;
		if (logo != null)
		{
			g.drawImage(logo, WIN_X + 10, oy + 5, 28, 28, null);
			titleX = WIN_X + 44;
		}
		g.setFont(FontManager.getRunescapeBoldFont());
		LofTheme.shadowText(g, "Character Style", titleX, oy + 25, LofTheme.GOLD);

		// close ✕
		final Rectangle cr = closeRect(oy);
		final boolean closeHov = cr.contains(mouse);
		g.setColor(closeHov ? LofTheme.EMBER : new Color(255, 255, 255, 18));
		g.fillRoundRect(cr.x, cr.y, cr.width, cr.height, 6, 6);
		g.setColor(closeHov ? LofTheme.TEXT : LofTheme.TEXT_DIM);
		final Stroke oldStroke = g.getStroke();
		g.setStroke(new BasicStroke(1.6f));
		g.drawLine(cr.x + 6, cr.y + 6, cr.x + cr.width - 7, cr.y + cr.height - 7);
		g.drawLine(cr.x + cr.width - 7, cr.y + 6, cr.x + 6, cr.y + cr.height - 7);
		g.setStroke(oldStroke);

		// section labels
		g.setFont(FontManager.getRunescapeSmallFont());
		LofTheme.shadowText(g, "STYLE — your character beside this window is the preview",
			WIN_X + PAD, oy + TITLE_H + 16, LofTheme.GOLD_DIM);
		LofTheme.shadowText(g, "COLOURS", WIN_X + PAD, rowY(oy, STYLE_ROWS) - 6, LofTheme.GOLD_DIM);

		// rows
		g.setFont(FontManager.getRunescapeFont());
		final FontMetrics fm = g.getFontMetrics();
		for (int row = 0; row < ROWS.length; row++)
		{
			final int y = rowY(oy, row);
			final boolean enabled = rowEnabled(row);
			g.setColor(LofTheme.ROW);
			g.fillRoundRect(WIN_X + PAD + ARROW_W + 4, y, WIN_W - 2 * PAD - 2 * (ARROW_W + 4), ROW_H, 6, 6);
			final String label = row == 1 && female ? "Facial hair — gents only" : LABELS[row];
			LofTheme.shadowText(g, label,
				WIN_X + (WIN_W - fm.stringWidth(label)) / 2, y + 15,
				enabled ? LofTheme.TEXT : LofTheme.TEXT_DIM);
			drawArrow(g, arrowRect(oy, row, 0), false, enabled, mouse);
			drawArrow(g, arrowRect(oy, row, 1), true, enabled, mouse);
		}

		// gender buttons
		LofModal.button(g, genderRect(oy, 0), "Male", female ? LofTheme.GOLD_DIM : LofTheme.GOLD, true,
			genderRect(oy, 0).contains(mouse) || !female);
		LofModal.button(g, genderRect(oy, 1), "Female", female ? LofTheme.GOLD : LofTheme.GOLD_DIM, true,
			genderRect(oy, 1).contains(mouse) || female);
		g.setFont(FontManager.getRunescapeSmallFont());
		LofTheme.shadowText(g, "Switching resets body style; colours stay.", WIN_X + PAD, oy + WIN_H - 52, LofTheme.TEXT_DIM);

		// done
		LofModal.button(g, doneRect(oy), "DONE", LofTheme.GOLD, true, doneRect(oy).contains(mouse));

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
