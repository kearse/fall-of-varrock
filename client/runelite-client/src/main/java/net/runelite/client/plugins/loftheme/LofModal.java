/*
 * Fall of Varrock — shared chrome for every lof overlay modal (docs/overlay-design-system.md
 * §5/§6A): viewport-centred origin, the panel + header + ember underline + close ✕, the footer
 * band (button row + status line), text fitting, and small shared utilities.
 *
 * Everything here is parameterised by window size. The earlier version hardcoded the 480x324
 * standard, so the 340px spoils window, the 492px vote window, the 512px kit editor and the shop
 * panel each COPIED the header/close/button code instead of calling it — and the copies then
 * drifted into three close-button sizes, three button styles and eight different footer baselines.
 * Draw chrome from here; don't hand-roll it.
 *
 * Not a plugin — a static utility like LofTheme (PluginManager only registers Plugin
 * subclasses, so this package is safe).
 */
package net.runelite.client.plugins.loftheme;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.ui.FontManager;

public final class LofModal
{
	/** The one default window size every standard modal uses (docs/overlay-design-system.md §6A).
	 *  Height fits inside the fixed-mode world view ({@link #FIXED_VIEWPORT_H}) so the window sits
	 *  centred in the game viewport, clear of the chat box — the way a default OSRS interface does. */
	public static final int W = 480;
	public static final int H = 324;
	public static final int ARC = 14;
	public static final int TITLE_H = 38;
	public static final int PAD = 14;

	/**
	 * The footer band (docs/overlay-design-system.md §5/§6A). Every modal's action buttons occupy
	 * ONE row, {@link #FOOTER_BTN_H} tall, {@link #FOOTER_GAP} above the panel's bottom edge — and
	 * the status line sits on its own baseline ABOVE that row ({@link #statusY}).
	 *
	 * This exists because windows used to pick their own footer baseline (H-18, H-22, H-24, H-26,
	 * H-40, H-46, H-52, H-56 were all in use) and several of them drew a full-width status string
	 * straight THROUGH their own button — the dice window's coin line over ROLL THE DIE, the mire's
	 * hint over CLAIM, the forge's confirm warning over FORGE IT. Route both through the helpers
	 * here and it cannot happen again: no text is ever drawn inside the button band.
	 */
	public static final int FOOTER_BTN_H = 32;
	public static final int FOOTER_GAP = 12;

	/**
	 * The vanilla fixed-mode world view (the game renders at 765×503; the world sits in the top-left
	 * 512×334). We centre windows inside this so they clear the inventory/tab column in fixed mode.
	 */
	public static final int FIXED_VIEWPORT_W = 512;
	public static final int FIXED_VIEWPORT_H = 334;

	/** Bottom band kept clear for the chat box, so a centred window never covers the chat (§6A). */
	public static final int CHATBOX_RESERVE = 165;

	public static final int COINS_ID = 995; // item.coins_995

	private LofModal()
	{
	}

	// ---------------------------------------------------------------------------------------------
	// Placement — the single source of truth for where a lof modal opens (§6A). Every framed window
	// routes through here so the client places all of them the same way. The behaviour is OSRS-
	// faithful and *dynamic*: fixed pixel size, re-centred on the live canvas each frame, and clamped
	// so it never runs off any edge — the same way vanilla interfaces re-centre without rescaling.
	// ---------------------------------------------------------------------------------------------

	/** Origin X for a standard {@link #W}-wide window. */
	public static int originX(Client client)
	{
		return originX(client, W);
	}

	/** Origin Y for a standard {@link #H}-tall window. */
	public static int originY(Client client)
	{
		return originY(client, H);
	}

	/**
	 * Centre a window {@code w} wide. In fixed mode keep it inside the ~512px world view so it clears
	 * the inventory/tab column; in resizable mode centre on the whole canvas. A window wider than the
	 * viewport (e.g. the kit editor) can't fit beside the inventory, so it centres on the full canvas
	 * instead. Always clamped so it never runs off either edge, even on a canvas narrower than {@code w}.
	 */
	public static int originX(Client client, int w)
	{
		final int canvasW = client.getCanvasWidth();
		final int viewportW = client.isResized() ? canvasW : Math.min(canvasW, FIXED_VIEWPORT_W);
		final int frame = w <= viewportW ? viewportW : canvasW;
		return clamp((frame - w) / 2, canvasW, w);
	}

	/**
	 * Centre a window {@code h} tall in the game viewport — the play area above the chat box — the way
	 * a default OSRS interface sits. In fixed mode the viewport is the {@link #FIXED_VIEWPORT_H}px world
	 * view; in resizable mode it's the canvas minus the chat band ({@link #CHATBOX_RESERVE}). A window
	 * that fits centres cleanly and clear of the chat; a window taller than the viewport (a big editor
	 * on a short canvas) pins to the top. Clamped so it never clips an edge.
	 */
	public static int originY(Client client, int h)
	{
		final int canvasH = client.getCanvasHeight();
		final int viewportH = client.isResized() ? (canvasH - CHATBOX_RESERVE) : FIXED_VIEWPORT_H;
		return clamp((viewportH - h) / 2, canvasH, h);
	}

	/** The placement rectangle of a standard {@link #W}×{@link #H} window on the current canvas. */
	public static Rectangle bounds(Client client)
	{
		return new Rectangle(originX(client), originY(client), W, H);
	}

	/** Clamp {@code pos} so a run of length {@code size} stays within {@code [0, extent]}; a run
	 *  bigger than the extent pins to the top/left edge rather than centring off-screen. */
	private static int clamp(int pos, int extent, int size)
	{
		if (size >= extent)
		{
			return 0;
		}
		return Math.max(0, Math.min(pos, extent - size));
	}

	// ---------------------------------------------------------------------------------------------
	// Footer band — the action-button row and the status line above it (§5). Single-sourced so the
	// two can never collide; see the FOOTER_BTN_H note.
	// ---------------------------------------------------------------------------------------------

	/** Top edge (canvas y) of the footer button row for a window {@code h} tall. */
	public static int footerBtnY(int oy, int h)
	{
		return oy + h - FOOTER_GAP - FOOTER_BTN_H;
	}

	/** Baseline (window-relative) of the status line — one row ABOVE the button band. */
	public static int statusY(int h)
	{
		return h - FOOTER_GAP - FOOTER_BTN_H - 12;
	}

	/** The right-aligned primary action button of a window {@code w}×{@code h}. */
	public static Rectangle footerButton(int ox, int oy, int w, int h, int btnW)
	{
		return new Rectangle(ox + w - PAD - btnW, footerBtnY(oy, h), btnW, FOOTER_BTN_H);
	}

	/** Standard-size window's right-aligned action button. */
	public static Rectangle footerButton(int ox, int oy, int btnW)
	{
		return footerButton(ox, oy, W, H, btnW);
	}

	/** The i-th chip in the left-aligned footer run (quantity pickers, stake chips, …). */
	public static Rectangle footerChip(int ox, int oy, int h, int i, int chipW, int gap)
	{
		return new Rectangle(ox + PAD + i * (chipW + gap), footerBtnY(oy, h), chipW, FOOTER_BTN_H);
	}

	/**
	 * The one-line status/hint above the footer buttons (§5.6): small font, left-aligned at the
	 * window padding, truncated to the panel width. Draws on {@link #statusY}, so it is structurally
	 * incapable of landing on a button.
	 */
	public static void statusLine(Graphics2D g, int ox, int oy, int w, int h, String text, Color col)
	{
		if (text == null || text.isEmpty())
		{
			return;
		}
		g.setFont(FontManager.getRunescapeSmallFont());
		LofTheme.shadowText(g, fit(g.getFontMetrics(), text, w - 2 * PAD), ox + PAD, oy + statusY(h), col);
	}

	/** Standard-size window's status line. */
	public static void statusLine(Graphics2D g, int ox, int oy, String text, Color col)
	{
		statusLine(g, ox, oy, W, H, text, col);
	}

	// ---------------------------------------------------------------------------------------------
	// Text fitting — the shared truncators. Four overlays each carried a private copy of this and
	// the ones that didn't have it are exactly the ones whose long names ran into the value beside
	// them, so it lives here now.
	// ---------------------------------------------------------------------------------------------

	/** {@code text} truncated with a trailing ellipsis so it fits {@code maxW} px. */
	public static String fit(FontMetrics fm, String text, int maxW)
	{
		if (text == null || text.isEmpty())
		{
			return "";
		}
		if (maxW <= 0)
		{
			return "";
		}
		if (fm.stringWidth(text) <= maxW)
		{
			return text;
		}
		final String ell = "…";
		final int ellW = fm.stringWidth(ell);
		int end = text.length();
		while (end > 0 && fm.stringWidth(text.substring(0, end)) + ellW > maxW)
		{
			end--;
		}
		return text.substring(0, end).stripTrailing() + ell;
	}

	/** Minimum gap kept between a row's label and its right-aligned value. */
	private static final int ROW_TEXT_GAP = 10;

	/**
	 * One list row's text: {@code left} label and {@code right} value on the same baseline inside a
	 * {@code w}-wide row starting at {@code x}. The label is truncated against the measured value,
	 * so a long item/destination name can never run into the number or pill beside it. Uses the
	 * caller's current font for both.
	 */
	public static void rowText(Graphics2D g, int x, int y, int w, String left, String right,
		Color leftCol, Color rightCol)
	{
		final FontMetrics fm = g.getFontMetrics();
		final int rightW = right == null || right.isEmpty() ? 0 : fm.stringWidth(right);
		if (rightW > 0)
		{
			LofTheme.shadowText(g, right, x + w - rightW, y, rightCol);
		}
		final int room = w - rightW - (rightW > 0 ? ROW_TEXT_GAP : 0);
		LofTheme.shadowText(g, fit(fm, left, room), x, y, leftCol);
	}

	/** Compact number for tight cells: 1,234 · 12.4k · 340k · 1.85M · 12M. */
	public static String compact(long v)
	{
		if (v >= 10_000_000)
		{
			return (v / 1_000_000) + "M";
		}
		if (v >= 1_000_000)
		{
			return String.format("%.2fM", v / 1_000_000.0);
		}
		if (v >= 100_000)
		{
			return (v / 1000) + "k";
		}
		if (v >= 10_000)
		{
			return String.format("%.1fk", v / 1000.0);
		}
		return fmt(v);
	}

	// ---------------------------------------------------------------------------------------------
	// Chrome — panel + header + close ✕. Parameterised by width/height/title-height: the old
	// versions hardcoded the 480x324 standard, which is why the 340px spoils window, the 492px vote
	// window, the 512px kit editor and the shop panel (sized to the native widget) each COPIED this
	// code instead of calling it — and then drifted apart. Anything with its own panel (the style
	// window punches a hole through its) calls header() alone.
	// ---------------------------------------------------------------------------------------------

	public static Rectangle closeRect(int ox, int oy, int w)
	{
		return new Rectangle(ox + w - 30, oy + 9, 20, 20);
	}

	public static Rectangle closeRect(int ox, int oy)
	{
		return closeRect(ox, oy, W);
	}

	/** Header strip + ember underline + logo + gold title + dim right-aligned subtitle + close ✕. */
	public static void header(Graphics2D g, int ox, int oy, int w, int titleH,
		String title, String subtitle, Point mouse, boolean showClose)
	{
		final int arc = ARC;
		final Shape headerClip = g.getClip();
		g.setClip(ox, oy, w, titleH);
		g.setColor(LofTheme.HEADER);
		g.fillRoundRect(ox, oy, w, titleH + arc, arc, arc);
		g.setClip(headerClip);
		LofTheme.emberUnderline(g, ox + 1, oy + titleH - 2, w - 2);

		final BufferedImage logo = LofTheme.logo();
		final int logoSz = Math.min(28, titleH - 10);
		int titleX = ox + 14;
		if (logo != null)
		{
			g.drawImage(logo, ox + 12, oy + (titleH - logoSz) / 2, logoSz, logoSz, null);
			titleX = ox + 12 + logoSz + 6;
		}
		final int baseline = oy + titleH / 2 + 6;
		g.setFont(FontManager.getRunescapeBoldFont());
		final int titleEnd = titleX + g.getFontMetrics().stringWidth(title);
		LofTheme.shadowText(g, title, titleX, baseline, LofTheme.GOLD);

		if (subtitle != null && !subtitle.isEmpty())
		{
			// Right-aligned, and truncated against the title's measured end — a long title plus a
			// long subtitle used to be free to overlap in the middle of the bar.
			g.setFont(FontManager.getRunescapeSmallFont());
			final FontMetrics fm = g.getFontMetrics();
			final int rightEdge = ox + w - (showClose ? 44 : 14);
			final String sub = fit(fm, subtitle, rightEdge - titleEnd - 10);
			LofTheme.shadowText(g, sub, rightEdge - fm.stringWidth(sub), baseline - 1, LofTheme.TEXT_DIM);
		}

		if (showClose)
		{
			closeButton(g, closeRect(ox, oy, w), mouse);
		}
	}

	/** The standard 20px rounded ✕, ember on hover. */
	public static void closeButton(Graphics2D g, Rectangle cr, Point mouse)
	{
		final boolean hov = cr.contains(mouse);
		g.setColor(hov ? LofTheme.EMBER : new Color(255, 255, 255, 18));
		g.fillRoundRect(cr.x, cr.y, cr.width, cr.height, 6, 6);
		g.setColor(hov ? LofTheme.TEXT : LofTheme.TEXT_DIM);
		final Stroke oldStroke = g.getStroke();
		g.setStroke(new BasicStroke(1.6f));
		g.drawLine(cr.x + 6, cr.y + 6, cr.x + cr.width - 7, cr.y + cr.height - 7);
		g.drawLine(cr.x + cr.width - 7, cr.y + 6, cr.x + 6, cr.y + cr.height - 7);
		g.setStroke(oldStroke);
	}

	/** Panel + header (§5.1-3). {@code showClose=false} for windows closed by a Decline button. */
	public static void frame(Graphics2D g, int ox, int oy, int w, int h, int titleH,
		String title, String subtitle, Point mouse, boolean showClose)
	{
		LofTheme.panel(g, ox, oy, w, h, ARC);
		header(g, ox, oy, w, titleH, title, subtitle, mouse, showClose);
	}

	/** Standard-size framed window with a close ✕. */
	public static void frame(Graphics2D g, int ox, int oy, String title, String subtitle, Point mouse)
	{
		frame(g, ox, oy, W, H, TITLE_H, title, subtitle, mouse, true);
	}

	// ---------------------------------------------------------------------------------------------
	// Scrolling — a modal whose content is taller than the window scrolls its list region instead of
	// growing (so the window still fits the game viewport, §6A). Shared so every scrolling modal looks
	// and behaves the same; mirrors the teleport list. contentH = total content px, viewH = the
	// visible list-region height.
	// ---------------------------------------------------------------------------------------------

	/** Clamp a pixel scroll offset to {@code [0, contentH - viewH]} (0 when everything fits). */
	public static int clampScroll(int scroll, int contentH, int viewH)
	{
		return Math.max(0, Math.min(scroll, Math.max(0, contentH - viewH)));
	}

	/** Draw the standard 5px ember scroll thumb on a faint track at the right edge of a list region.
	 *  No-op when the content fits (nothing to scroll). */
	public static void scrollbar(Graphics2D g, int x, int yTop, int viewH, int contentH, int scroll)
	{
		if (contentH <= viewH)
		{
			return;
		}
		g.setColor(LofTheme.alpha(Color.WHITE, 14));
		g.fillRoundRect(x, yTop, 5, viewH, 5, 5);
		final int max = contentH - viewH;
		final int thumbH = Math.max(24, (int) ((long) viewH * viewH / contentH));
		final int thumbY = yTop + (viewH - thumbH) * clampScroll(scroll, contentH, viewH) / max;
		g.setColor(LofTheme.alpha(LofTheme.EMBER, 190));
		g.fillRoundRect(x, thumbY, 5, thumbH, 5, 5);
	}

	/** Standard footer button (§5): accent-coloured when enabled, dim + inert when not. */
	public static void button(Graphics2D g, Rectangle r, String label, Color accent, boolean enabled, boolean hover)
	{
		if (enabled && hover)
		{
			g.setColor(LofTheme.alpha(accent, 50));
			g.fillRoundRect(r.x - 3, r.y - 3, r.width + 6, r.height + 6, 12, 12);
		}
		g.setColor(enabled ? LofTheme.alpha(accent, hover ? 60 : 30) : new Color(255, 255, 255, 8));
		g.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8);
		final Stroke oldStroke = g.getStroke();
		g.setStroke(new BasicStroke(1.4f));
		g.setColor(enabled ? accent : LofTheme.alpha(LofTheme.TEXT_DIM, 110));
		g.drawRoundRect(r.x, r.y, r.width, r.height, 8, 8);
		g.setStroke(oldStroke);

		g.setFont(FontManager.getRunescapeFont());
		final FontMetrics fm = g.getFontMetrics();
		LofTheme.shadowText(g, label, r.x + (r.width - fm.stringWidth(label)) / 2, r.y + r.height / 2 + 5,
			enabled ? accent : LofTheme.TEXT_DIM);
	}

	/** Total of one stackable item carried in the inventory. */
	public static long carried(Client client, int itemId)
	{
		final ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
		if (inv == null)
		{
			return 0;
		}
		long total = 0;
		for (Item item : inv.getItems())
		{
			if (item != null && item.getId() == itemId)
			{
				total += item.getQuantity();
			}
		}
		return total;
	}

	public static String fmt(long n)
	{
		return String.format("%,d", n);
	}
}
