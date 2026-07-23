/*
 * Fall of Varrock — command window (renderer + hit-testing).
 *
 * A tabbed, centred window listing every command the player can run, one tab per rank
 * (Player, Donor, Moderator, Administrator, Developer, Owner). The active tab's commands scroll
 * in a list on the right. Styled with the shared LofTheme (shield logo, ember accents, antique
 * gold on warm near-black) to match the teleport portal and login screen. Rows are read-only;
 * the window is closed with the X (or by clicking outside it). Geometry lives here so the mouse
 * listener, wheel handler and renderer all agree.
 */
package net.runelite.client.plugins.lofcommands;

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
import net.runelite.client.plugins.lofcommands.LofCommandsPlugin.Entry;
import net.runelite.client.plugins.lofcommands.LofCommandsPlugin.Tab;
import net.runelite.client.plugins.loftheme.LofModal;
import net.runelite.client.plugins.loftheme.LofTheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class LofCommandsOverlay extends Overlay
{
	// hit-test result codes
	static final int OUTSIDE = -1;
	static final int INSIDE = 0;
	static final int CLOSE = 1;
	static final int TAB_BASE = 100;
	static final int ROW_BASE = 1000;

	// 480x400 framed-modal standard (docs/overlay-design-system.md §6A).
	private static final int WIN_W = LofModal.W;
	private static final int WIN_H = LofModal.H;
	private static final int WIN_ARC = 14;
	private static final int TITLE_H = 38;
	private static final int TAB_X = 10;
	private static final int TAB_W = 116;
	private static final int TAB_Y0 = TITLE_H + 16;
	private static final int TAB_H = 28;
	private static final int TAB_GAP = 32;
	private static final int LIST_X = 134;
	private static final int LIST_W = WIN_W - LIST_X - 10;     // 336
	private static final int SCROLLBAR_W = 5;
	private static final int VP_TOP = TITLE_H + 14;            // 52
	private static final int VP_BOTTOM = WIN_H - 12;           // 388
	private static final int VP_H = VP_BOTTOM - VP_TOP;        // 336
	private static final int CARD_X = LIST_X + 2;
	private static final int CARD_W = LIST_W - SCROLLBAR_W - 8; // 323
	private static final int CARD_H = 22;                      // §4 standard row height
	private static final int STEP = 26;

	private static final Color CLOSE_HOVER = LofTheme.EMBER;

	private final Client client;
	private final LofCommandsPlugin plugin;

	private boolean visible;

	// Scaled placement published by render() on the client thread; the mouse thread hit-tests via this cache only.
	private volatile LofModal.Placement placement;
	private int activeTab;
	private int scroll; // px

	@Inject
	private LofCommandsOverlay(Client client, LofCommandsPlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;
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

	int getActiveTab()
	{
		return activeTab;
	}

	void setActiveTab(int t)
	{
		if (t >= 0 && t < plugin.getTabs().size())
		{
			activeTab = t;
			scroll = 0;
		}
	}

	private List<Tab> tabs()
	{
		return plugin.getTabs();
	}

	private int rowCount()
	{
		final List<Tab> t = tabs();
		return activeTab < t.size() ? t.get(activeTab).rows.size() : 0;
	}

	private int maxScroll()
	{
		return Math.max(0, rowCount() * STEP - VP_H);
	}

	// Placement single-sourced in LofModal (§6A) — same anchor as every modal.
	private int originX() { return LofModal.originX(client, WIN_W); }

	private int originY() { return LofModal.originY(client, WIN_H); }

	/** Wheel scroll if the cursor is over the list; returns true if consumed. */
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
		if (!new Rectangle(ox + LIST_X, oy + VP_TOP, LIST_W, VP_H).contains(p))
		{
			return false;
		}
		scroll = clamp(scroll + rotation * STEP, 0, maxScroll());
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
		if (closeRect(ox, oy).contains(p))
		{
			return CLOSE;
		}
		for (int t = 0; t < tabs().size(); t++)
		{
			if (tabRect(ox, oy, t).contains(p))
			{
				return TAB_BASE + t;
			}
		}
		if (new Rectangle(ox + LIST_X, oy + VP_TOP, LIST_W, VP_H).contains(p))
		{
			final int rel = p.y - (oy + VP_TOP) + scroll;
			final int i = rel / STEP;
			if (i >= 0 && i < rowCount() && (rel - i * STEP) <= CARD_H)
			{
				return ROW_BASE + i;
			}
		}
		return INSIDE;
	}

	/** The "::command" text of the row at {@code index} in the active tab, or null if out of range. */
	String commandAt(int index)
	{
		final List<Tab> t = tabs();
		if (activeTab < t.size())
		{
			final List<Entry> rows = t.get(activeTab).rows;
			if (index >= 0 && index < rows.size())
			{
				return rows.get(index).cmd;
			}
		}
		return null;
	}

	private Rectangle closeRect(int ox, int oy)
	{
		return new Rectangle(ox + WIN_W - 30, oy + 9, 20, 20);
	}

	private Rectangle tabRect(int ox, int oy, int t)
	{
		return new Rectangle(ox + TAB_X, oy + TAB_Y0 + t * TAB_GAP, TAB_W, TAB_H);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!visible || !plugin.isEnabled() || client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}

		final List<Tab> tabs = tabs();
		if (tabs.isEmpty())
		{
			return null;
		}
		if (activeTab >= tabs.size())
		{
			activeTab = 0;
		}

		final Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// This overlay draws at ABSOLUTE canvas coordinates. Undo any translate the renderer
		// applied (e.g. a saved drag offset) so absolute means absolute.
		final java.awt.Rectangle selfBounds = getBounds();
		g.translate(-selfBounds.x, -selfBounds.y);


		final LofModal.Placement place = LofModal.beginWindow(g, client, WIN_W, WIN_H);
		placement = place;
		final int ox = place.ox, oy = place.oy;
		final Point mouse = place.toLocal(mousePoint());

		LofTheme.panel(g, ox, oy, WIN_W, WIN_H, WIN_ARC);

		// header bar with ember accent underline
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
		LofTheme.shadowText(g, "Commands", titleX, oy + 25, LofTheme.GOLD);
		g.setFont(FontManager.getRunescapeSmallFont());
		final Tab active = tabs.get(activeTab);
		final String rank = plugin.getRankTitle();
		final String sub = (rank.isEmpty() ? "" : rank + "  •  ") + active.rows.size() + " commands";
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

		// tab rail
		g.setColor(LofTheme.ROW);
		g.fillRoundRect(ox + TAB_X - 4, oy + TITLE_H + 8, TAB_W + 8, WIN_H - TITLE_H - 20, 10, 10);
		g.setFont(FontManager.getRunescapeFont());
		final FontMetrics tabFm = g.getFontMetrics();
		for (int t = 0; t < tabs.size(); t++)
		{
			final Rectangle tr = tabRect(ox, oy, t);
			final boolean sel = t == activeTab;
			final boolean hov = tr.contains(mouse);
			// §7 tab rail: selected = ember bar + alpha(ember,44) fill + gold label (one system).
			if (sel)
			{
				g.setColor(LofTheme.alpha(LofTheme.EMBER, 44));
				g.fillRoundRect(tr.x, tr.y, tr.width, tr.height, 8, 8);
				g.setColor(LofTheme.EMBER);
				g.fillRoundRect(tr.x, tr.y + 6, 3, tr.height - 12, 2, 2);
			}
			else if (hov)
			{
				g.setColor(LofTheme.ROW_HOVER);
				g.fillRoundRect(tr.x, tr.y, tr.width, tr.height, 8, 8);
			}
			final String label = ellipsise(tabFm, tabs.get(t).role, TAB_W - 20);
			LofTheme.shadowText(g, label, tr.x + 12, tr.y + 19, sel ? LofTheme.GOLD : (hov ? LofTheme.TEXT : LofTheme.TEXT_DIM));
		}

		// commands (clipped to viewport)
		final Shape oldClip = g.getClip();
		g.setClip(ox + LIST_X, oy + VP_TOP, LIST_W, VP_H);
		g.setFont(FontManager.getRunescapeFont());
		final FontMetrics fm = g.getFontMetrics();
		final List<Entry> rows = active.rows;
		for (int i = 0; i < rows.size(); i++)
		{
			final int cy = oy + VP_TOP + i * STEP - scroll;
			if (cy + CARD_H < oy + VP_TOP || cy > oy + VP_BOTTOM)
			{
				continue; // off-screen
			}
			final Entry e = rows.get(i);
			final int cx = ox + CARD_X;
			final boolean hov = mouse.x >= cx && mouse.x <= cx + CARD_W
				&& mouse.y >= cy && mouse.y <= cy + CARD_H
				&& mouse.y >= oy + VP_TOP && mouse.y <= oy + VP_BOTTOM;

			g.setColor(hov ? LofTheme.ROW_HOVER : LofTheme.ROW);
			g.fillRoundRect(cx, cy, CARD_W, CARD_H, 8, 8);
			if (hov)
			{
				g.setColor(LofTheme.alpha(LofTheme.EMBER, 150));
				g.drawRoundRect(cx, cy, CARD_W, CARD_H, 8, 8);
			}

			final int ty = cy + CARD_H / 2 + 4;
			LofTheme.shadowText(g, e.cmd, cx + 10, ty, LofTheme.GOLD);
			if (!e.desc.isEmpty())
			{
				final int cmdW = fm.stringWidth(e.cmd);
				final String tail = ellipsise(fm, "  —  " + e.desc, CARD_W - 20 - cmdW);
				LofTheme.shadowText(g, tail, cx + 10 + cmdW, ty, LofTheme.TEXT_DIM);
			}
		}
		g.setClip(oldClip);

		// scrollbar
		final int ms = maxScroll();
		if (ms > 0)
		{
			final int sbX = ox + LIST_X + LIST_W - SCROLLBAR_W;
			g.setColor(new Color(255, 255, 255, 14));
			g.fillRoundRect(sbX, oy + VP_TOP, SCROLLBAR_W, VP_H, SCROLLBAR_W, SCROLLBAR_W);
			final int content = rowCount() * STEP;
			final int thumbH = Math.max(24, VP_H * VP_H / content);
			final int thumbY = oy + VP_TOP + (VP_H - thumbH) * scroll / ms;
			g.setColor(LofTheme.alpha(LofTheme.EMBER, 190));
			g.fillRoundRect(sbX, thumbY, SCROLLBAR_W, thumbH, SCROLLBAR_W, SCROLLBAR_W);
		}

		LofModal.endWindow(g, place);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : oldAA);
		return new Dimension(WIN_W, WIN_H);
	}

	/** Truncate text with a trailing "…" so it fits within maxW pixels. */
	private static String ellipsise(FontMetrics fm, String text, int maxW)
	{
		if (maxW <= 0)
		{
			return "";
		}
		if (fm.stringWidth(text) <= maxW)
		{
			return text;
		}
		final int ellW = fm.stringWidth("…");
		int end = text.length();
		while (end > 0 && fm.stringWidth(text.substring(0, end)) + ellW > maxW)
		{
			end--;
		}
		return text.substring(0, end).stripTrailing() + "…";
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
