/*
 * Fall of Varrock — Spoils of War window (renderer + hit-testing).
 *
 * A small centred window listing a war commander's unclaimed spoils — each row an item sprite with
 * its name and quantity — plus a "Bank All" and a "To Inventory" button; clicking a row takes that
 * one item to the inventory. Styled with the shared LofTheme to match the command window and portal.
 * A separate timed toast banner (top-centre) is drawn even while the window is closed, prompting the
 * commander to ::claim after a war is won. Geometry lives here so the mouse/wheel handlers agree.
 */
package net.runelite.client.plugins.lofwarspoils;

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
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.loftheme.LofModal;
import net.runelite.client.plugins.loftheme.LofTheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class LofWarSpoilsOverlay extends Overlay
{
	// hit-test result codes
	static final int OUTSIDE = -1;
	static final int INSIDE = 0;
	static final int CLOSE = 1;
	static final int BANK_ALL = 2;
	static final int TAKE_ALL = 3;
	static final int ROW_BASE = 1000;

	private static final int WIN_W = 340;
	private static final int WIN_H = 320;
	private static final int WIN_ARC = 14;
	private static final int TITLE_H = 38;

	private static final int MARGIN = 12;
	private static final int BTN_H = 30;
	private static final int BTN_BAR_Y = WIN_H - BTN_H - MARGIN; // 278
	private static final int BTN_W = (WIN_W - MARGIN * 3) / 2;   // 152
	private static final int VP_TOP = TITLE_H + 8;               // 46
	private static final int VP_BOTTOM = BTN_BAR_Y - 8;          // 270
	private static final int VP_H = VP_BOTTOM - VP_TOP;          // 224
	private static final int ROW_H = 40;
	private static final int ROW_STEP = 44;
	private static final int SCROLLBAR_W = 5;

	private static final Color CLOSE_HOVER = LofTheme.EMBER;

	private final Client client;
	private final LofWarSpoilsPlugin plugin;
	private final ItemManager itemManager;

	private boolean visible;
	private int scroll; // px

	@Inject
	private LofWarSpoilsOverlay(Client client, LofWarSpoilsPlugin plugin, ItemManager itemManager)
	{
		this.client = client;
		this.plugin = plugin;
		this.itemManager = itemManager;
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

	void resetScroll()
	{
		scroll = 0;
	}

	private int rowCount()
	{
		return plugin.getItems().size();
	}

	private int maxScroll()
	{
		return Math.max(0, rowCount() * ROW_STEP - VP_H);
	}

	// Placement single-sourced in LofModal (§6A) — same anchor as every modal.
	private int originX() { return LofModal.originX(client, WIN_W); }

	private int originY() { return LofModal.originY(client, WIN_H); }

	/** Wheel scroll if the cursor is over the list; returns true if consumed. */
	boolean handleScroll(Point p, int rotation)
	{
		if (!visible)
		{
			return false;
		}
		final int ox = originX(), oy = originY();
		if (!new Rectangle(ox + MARGIN, oy + VP_TOP, WIN_W - MARGIN * 2, VP_H).contains(p))
		{
			return false;
		}
		scroll = clamp(scroll + rotation * ROW_STEP, 0, maxScroll());
		return true;
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
		if (bankRect(ox, oy).contains(p))
		{
			return BANK_ALL;
		}
		if (takeRect(ox, oy).contains(p))
		{
			return TAKE_ALL;
		}
		final Rectangle vp = new Rectangle(ox + MARGIN, oy + VP_TOP, WIN_W - MARGIN * 2, VP_H);
		if (vp.contains(p))
		{
			final int rel = p.y - (oy + VP_TOP) + scroll;
			final int i = rel / ROW_STEP;
			if (i >= 0 && i < rowCount() && (rel - i * ROW_STEP) <= ROW_H)
			{
				return ROW_BASE + i;
			}
		}
		return INSIDE;
	}

	private Rectangle closeRect(int ox, int oy)
	{
		return new Rectangle(ox + WIN_W - 30, oy + 9, 20, 20);
	}

	private Rectangle bankRect(int ox, int oy)
	{
		return new Rectangle(ox + MARGIN, oy + BTN_BAR_Y, BTN_W, BTN_H);
	}

	private Rectangle takeRect(int ox, int oy)
	{
		return new Rectangle(ox + MARGIN * 2 + BTN_W, oy + BTN_BAR_Y, BTN_W, BTN_H);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!plugin.isEnabled() || client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}

		final Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// Absolute canvas coordinates — undo any renderer translate (same trick as the other windows).
		final Rectangle selfBounds = getBounds();
		g.translate(-selfBounds.x, -selfBounds.y);

		final String toast = plugin.activeToast();
		if (toast != null)
		{
			drawToast(g, toast);
		}

		if (!visible)
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
			return toast != null ? new Dimension(client.getCanvasWidth(), 40) : null;
		}

		final int ox = originX(), oy = originY();
		final Point mouse = mousePoint();
		final List<LofWarSpoilsPlugin.Item> items = plugin.getItems();

		LofTheme.panel(g, ox, oy, WIN_W, WIN_H, WIN_ARC);

		// header bar with ember underline
		final Shape headerClip = g.getClip();
		g.setClip(ox, oy, WIN_W, TITLE_H);
		g.setColor(LofTheme.HEADER);
		g.fillRoundRect(ox, oy, WIN_W, TITLE_H + WIN_ARC, WIN_ARC, WIN_ARC);
		g.setClip(headerClip);
		LofTheme.emberUnderline(g, ox + 1, oy + TITLE_H - 2, WIN_W - 2);

		// shield logo + title
		final BufferedImage logo = LofTheme.logo();
		int titleX = ox + 14;
		if (logo != null)
		{
			g.drawImage(logo, ox + 12, oy + 5, 28, 28, null);
			titleX = ox + 46;
		}
		g.setFont(FontManager.getRunescapeBoldFont());
		LofTheme.shadowText(g, "Spoils of War", titleX, oy + 25, LofTheme.GOLD);
		g.setFont(FontManager.getRunescapeSmallFont());
		final String sub = items.size() + (items.size() == 1 ? " item" : " items");
		LofTheme.shadowText(g, sub, ox + WIN_W - 44 - g.getFontMetrics().stringWidth(sub), oy + 24, LofTheme.TEXT_DIM);

		// close button
		final Rectangle cr = closeRect(ox, oy);
		final boolean closeHov = cr.contains(mouse);
		g.setColor(closeHov ? CLOSE_HOVER : new Color(255, 255, 255, 18));
		g.fillRoundRect(cr.x, cr.y, cr.width, cr.height, 6, 6);
		g.setColor(closeHov ? LofTheme.TEXT : LofTheme.TEXT_DIM);
		final Stroke oldStroke = g.getStroke();
		g.setStroke(new BasicStroke(1.4f));
		g.drawLine(cr.x + 6, cr.y + 6, cr.x + cr.width - 7, cr.y + cr.height - 7);
		g.drawLine(cr.x + cr.width - 7, cr.y + 6, cr.x + 6, cr.y + cr.height - 7);
		g.setStroke(oldStroke);

		// item rows (clipped to viewport)
		final Shape oldClip = g.getClip();
		g.setClip(ox + MARGIN, oy + VP_TOP, WIN_W - MARGIN * 2, VP_H);
		g.setFont(FontManager.getRunescapeFont());
		final FontMetrics fm = g.getFontMetrics();
		final int rowW = WIN_W - MARGIN * 2 - (maxScroll() > 0 ? SCROLLBAR_W + 4 : 0);
		for (int i = 0; i < items.size(); i++)
		{
			final int ry = oy + VP_TOP + i * ROW_STEP - scroll;
			if (ry + ROW_H < oy + VP_TOP || ry > oy + VP_BOTTOM)
			{
				continue; // off-screen
			}
			final LofWarSpoilsPlugin.Item it = items.get(i);
			final int rx = ox + MARGIN;
			final boolean hov = mouse.x >= rx && mouse.x <= rx + rowW
				&& mouse.y >= ry && mouse.y <= ry + ROW_H
				&& mouse.y >= oy + VP_TOP && mouse.y <= oy + VP_BOTTOM;

			g.setColor(hov ? LofTheme.ROW_HOVER : LofTheme.ROW);
			g.fillRoundRect(rx, ry, rowW, ROW_H, 8, 8);
			if (hov)
			{
				g.setColor(LofTheme.alpha(LofTheme.EMBER, 150));
				g.drawRoundRect(rx, ry, rowW, ROW_H, 8, 8);
			}

			final BufferedImage img = it.itemId > 0 ? itemManager.getImage(it.itemId) : null;
			if (img != null)
			{
				g.drawImage(img, rx + 6, ry + (ROW_H - img.getHeight()) / 2, null);
			}

			final String name = itemName(it.itemId);
			final int ty = ry + ROW_H / 2 + 4;
			LofTheme.shadowText(g, name, rx + 48, ty, LofTheme.GOLD);
			if (it.qty > 1)
			{
				final String q = "x " + String.format("%,d", it.qty);
				LofTheme.shadowText(g, q, rx + rowW - 10 - fm.stringWidth(q), ty, LofTheme.TEXT_DIM);
			}
		}
		g.setClip(oldClip);

		// scrollbar
		final int ms = maxScroll();
		if (ms > 0)
		{
			final int sbX = ox + WIN_W - MARGIN - SCROLLBAR_W;
			g.setColor(new Color(255, 255, 255, 14));
			g.fillRoundRect(sbX, oy + VP_TOP, SCROLLBAR_W, VP_H, SCROLLBAR_W, SCROLLBAR_W);
			final int content = rowCount() * ROW_STEP;
			final int thumbH = Math.max(24, VP_H * VP_H / content);
			final int thumbY = oy + VP_TOP + (VP_H - thumbH) * scroll / ms;
			g.setColor(LofTheme.alpha(LofTheme.EMBER, 190));
			g.fillRoundRect(sbX, thumbY, SCROLLBAR_W, thumbH, SCROLLBAR_W, SCROLLBAR_W);
		}

		// button bar
		drawButton(g, bankRect(ox, oy), "Bank All", mouse, true);
		drawButton(g, takeRect(ox, oy), "To Inventory", mouse, false);

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(WIN_W, WIN_H);
	}

	private void drawButton(Graphics2D g, Rectangle r, String label, Point mouse, boolean primary)
	{
		final boolean hov = r.contains(mouse);
		g.setColor(primary
			? LofTheme.alpha(LofTheme.EMBER, hov ? 90 : 60)
			: LofTheme.alpha(LofTheme.PANEL_OPAQUE, hov ? 210 : 160));
		g.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8);
		final Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(hov ? 1.6f : 1.0f));
		g.setColor(LofTheme.alpha(primary ? LofTheme.GOLD : LofTheme.EMBER, hov ? 220 : 140));
		g.drawRoundRect(r.x, r.y, r.width - 1, r.height - 1, 8, 8);
		g.setStroke(old);
		g.setFont(FontManager.getRunescapeFont());
		final int tw = g.getFontMetrics().stringWidth(label);
		LofTheme.shadowText(g, label, r.x + (r.width - tw) / 2, r.y + r.height / 2 + 5,
			hov ? LofTheme.GOLD : LofTheme.TEXT);
	}

	/** Top-centre timed banner prompting the commander to ::claim (drawn even when the window is closed). */
	private void drawToast(Graphics2D g, String text)
	{
		g.setFont(FontManager.getRunescapeBoldFont());
		final FontMetrics fm = g.getFontMetrics();
		final int pad = 14;
		final int w = fm.stringWidth(text) + pad * 2;
		final int h = 28;
		final int x = Math.max(4, (client.getCanvasWidth() - w) / 2);
		final int y = 8;
		g.setColor(LofTheme.alpha(LofTheme.PANEL_OPAQUE, 230));
		g.fillRoundRect(x, y, w, h, 10, 10);
		final Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(1.4f));
		g.setColor(LofTheme.alpha(LofTheme.GOLD, 220));
		g.drawRoundRect(x, y, w - 1, h - 1, 10, 10);
		g.setStroke(old);
		LofTheme.shadowText(g, text, x + pad, y + h / 2 + 5, LofTheme.GOLD);
	}

	private String itemName(int id)
	{
		try
		{
			final String n = itemManager.getItemComposition(id).getName();
			return n == null || n.isEmpty() ? "Item " + id : n;
		}
		catch (Exception e)
		{
			return "Item " + id;
		}
	}

	private Point mousePoint()
	{
		final net.runelite.api.Point m = client.getMouseCanvasPosition();
		return m == null ? new Point(-1, -1) : new Point(m.getX(), m.getY());
	}

	private static int clamp(int v, int lo, int hi)
	{
		return Math.max(lo, Math.min(hi, v));
	}
}
