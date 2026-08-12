/*
 * Fall of Varrock — shared frame helpers for the standard 480x400 overlay modal
 * (docs/overlay-design-system.md §5/§6A): viewport-centred origin, the panel + header +
 * ember underline + close ✕, footer buttons, and small shared utilities.
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
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Varbits;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
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
	 * The vanilla fixed-mode world view (the game renders at 765×503; the world sits in the top-left
	 * 512×334). We centre windows inside this so they clear the inventory/tab column in fixed mode.
	 */
	public static final int FIXED_VIEWPORT_W = 512;
	public static final int FIXED_VIEWPORT_H = 334;

	/** Bottom band kept clear for the chat box, so a centred window never covers the chat (§6A). */
	public static final int CHATBOX_RESERVE = 165;

	/**
	 * UI-scale reference canvas — the vanilla fixed-mode render size. At this logical canvas a window
	 * draws at its authored (1.0x) size; the scale grows as the logical canvas grows past it, so our
	 * custom windows aren't tiny on a big screen (docs/overlay-design-system.md §6 "Scaling").
	 */
	public static final int SCALE_BASE_W = 765;
	public static final int SCALE_BASE_H = 503;
	/** Cap (in whole steps) so a huge canvas doesn't blow the window up past a comfortable size.
	 *  The scale is always an INTEGER — the RuneScape font is a pixel font and only stays crisp at
	 *  integer multiples of its design size, so 2x is the largest crisp step we allow. */
	public static final int SCALE_MAX = 2;

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
	// UI scaling — one screen-aware scale factor so custom windows grow with the canvas instead of
	// looking tiny on a big screen, the OSRS way (docs/overlay-design-system.md §6 "Scaling"). We scale from the
	// LOGICAL canvas (getCanvasWidth/Height), never the stretched/window dimensions, so RuneLite's
	// Stretched Mode composes cleanly on top (fixed+stretched keeps the logical canvas small → scale
	// 1.0 and Stretched Mode does the enlarging; resizable grows the logical canvas → our windows grow).
	// ---------------------------------------------------------------------------------------------

	/**
	 * The current window scale for this canvas — always a WHOLE integer (1x, 2x). The RuneScape font
	 * is a pixel font: it only stays crisp at integer multiples of its design size, so a fractional
	 * scale (e.g. 1.6x → a 25.6px font) blurs every glyph. We therefore floor the canvas ratio to a
	 * whole step rather than scaling continuously. Compute on the client thread ({@code render}).
	 */
	public static float uiScale(Client client)
	{
		final float byW = client.getCanvasWidth() / (float) SCALE_BASE_W;
		final float byH = client.getCanvasHeight() / (float) SCALE_BASE_H;
		final int step = (int) Math.floor(Math.min(byW, byH));
		return Math.max(1, Math.min(step, SCALE_MAX));
	}

	/**
	 * A window's placement for one frame: scaled origin + scale factor. Computed on the client thread in
	 * {@code render()} via {@link #beginWindow}; cache it in a {@code volatile} field so the mouse thread
	 * hit-tests against the same values (§8). {@link #toLocal} maps a canvas mouse point back into the
	 * window's authored (un-scaled) coordinate space, so existing {@code ox+PAD}-based rects still work.
	 */
	public static final class Placement
	{
		public final int ox;
		public final int oy;
		public final float scale;
		private final AffineTransform saved;
		private final Object savedTextAA;
		private final Object savedFractionalMetrics;

		private Placement(int ox, int oy, float scale, AffineTransform saved, Object savedTextAA, Object savedFractionalMetrics)
		{
			this.ox = ox;
			this.oy = oy;
			this.scale = scale;
			this.saved = saved;
			this.savedTextAA = savedTextAA;
			this.savedFractionalMetrics = savedFractionalMetrics;
		}

		/** Map a canvas point into the window's authored coordinate space (anchored at {@code ox,oy}). */
		public Point toLocal(Point canvas)
		{
			return new Point(ox + Math.round((canvas.x - ox) / scale), oy + Math.round((canvas.y - oy) / scale));
		}
	}

	/**
	 * Begin a scaled window. Computes the (integer) scale, places the scaled window with the standard
	 * origin authority, and applies a pivot-scale about the origin so the caller keeps drawing at its
	 * existing {@code ox + PAD …} coordinates using the authored base constants — the transform does the
	 * scaling. Because the scale is a whole integer and text is drawn with antialiasing off (below), the
	 * RuneScape pixel font stays crisp. Draw the window, then call {@link #endWindow}.
	 */
	public static Placement beginWindow(Graphics2D g, Client client, int baseW, int baseH)
	{
		final float s = uiScale(client);
		final int ox = originX(client, Math.round(baseW * s));
		final int oy = originY(client, Math.round(baseH * s));
		return begin(g, ox, oy, s);
	}

	/** Shared tail of the beginWindow variants: pivot-scale about the origin + crisp-text hints. */
	private static Placement begin(Graphics2D g, int ox, int oy, float s)
	{
		final AffineTransform saved = g.getTransform();
		g.translate(ox, oy);
		g.scale(s, s);
		g.translate(-ox, -oy);
		// The RuneScape font is a pixel font — antialiasing/fractional metrics soften its 1px strokes
		// into a blur. Force them off so glyphs render as crisp, hard-edged pixels (shapes keep their
		// own KEY_ANTIALIASING; this only governs text). Saved + restored in endWindow so the hint
		// never leaks to the next overlay drawn on this shared Graphics2D.
		final Object savedTextAA = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
		final Object savedFractionalMetrics = g.getRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
		return new Placement(ox, oy, s, saved, savedTextAA, savedFractionalMetrics);
	}

	/**
	 * Canvas bounds of the live inventory panel for the current layout (fixed / resizable-classic /
	 * resizable-bottom-line), or null when it isn't on screen. Reads widgets — call on the client
	 * thread (i.e. from {@code render()}), never from the mouse thread.
	 */
	public static Rectangle inventoryBounds(Client client)
	{
		final Widget w;
		if (client.isResized())
		{
			w = client.getVarbitValue(Varbits.SIDE_PANELS) == 1
				? client.getWidget(ComponentID.RESIZABLE_VIEWPORT_BOTTOM_LINE_INVENTORY_CONTAINER)
				: client.getWidget(ComponentID.RESIZABLE_VIEWPORT_INVENTORY_CONTAINER);
		}
		else
		{
			w = client.getWidget(ComponentID.FIXED_VIEWPORT_INVENTORY_CONTAINER);
		}
		return w == null || w.isHidden() ? null : w.getBounds();
	}

	/**
	 * {@link #beginWindow} for the modals that need the REAL inventory clickable beside them (the
	 * stake and duel-confirm screens — their items are added by clicking inventory slots). Fixed
	 * mode already clears the inventory column by construction; in resizable mode, where the plain
	 * origin centres on the whole canvas, the window is shifted left just far enough to clear the
	 * inventory panel. On a canvas too narrow for both it pins to the left edge — the overlays'
	 * hit-tests treat any residual overlap with the inventory as pass-through.
	 */
	public static Placement beginWindowBesideInventory(Graphics2D g, Client client, int baseW, int baseH)
	{
		final float s = uiScale(client);
		final int scaledW = Math.round(baseW * s);
		int ox = originX(client, scaledW);
		if (client.isResized())
		{
			final Rectangle inv = inventoryBounds(client);
			if (inv != null && ox + scaledW > inv.x - 8)
			{
				ox = Math.max(0, inv.x - 8 - scaledW);
			}
		}
		final int oy = originY(client, Math.round(baseH * s));
		return begin(g, ox, oy, s);
	}

	/** End a scaled window — restore the transform and text hints captured by {@link #beginWindow}. */
	public static void endWindow(Graphics2D g, Placement p)
	{
		g.setTransform(p.saved);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
			p.savedTextAA != null ? p.savedTextAA : RenderingHints.VALUE_TEXT_ANTIALIAS_DEFAULT);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
			p.savedFractionalMetrics != null ? p.savedFractionalMetrics : RenderingHints.VALUE_FRACTIONALMETRICS_DEFAULT);
	}

	public static Rectangle closeRect(int ox, int oy)
	{
		return closeRect(ox, oy, W);
	}

	/** Close ✕ for a window {@code w} wide (anchored to its top-right, like the default). */
	public static Rectangle closeRect(int ox, int oy, int w)
	{
		return new Rectangle(ox + w - 30, oy + 9, 20, 20);
	}

	/** Panel + header strip + logo + gold title + dim right-aligned subtitle + close ✕ (default size). */
	public static void frame(Graphics2D g, int ox, int oy, String title, String subtitle, Point mouse)
	{
		frame(g, ox, oy, W, H, title, subtitle, mouse);
	}

	/** Same frame at a custom {@code w}×{@code h} — for windows that deviate from the default size
	 *  (e.g. the kit editor and Grand Exchange), so they still get the shared header/logo/close chrome. */
	public static void frame(Graphics2D g, int ox, int oy, int w, int h, String title, String subtitle, Point mouse)
	{
		LofTheme.panel(g, ox, oy, w, h, ARC);

		final Shape headerClip = g.getClip();
		g.setClip(ox, oy, w, TITLE_H);
		g.setColor(LofTheme.HEADER);
		g.fillRoundRect(ox, oy, w, TITLE_H + ARC, ARC, ARC);
		g.setClip(headerClip);
		LofTheme.emberUnderline(g, ox + 1, oy + TITLE_H - 2, w - 2);

		final BufferedImage logo = LofTheme.logo();
		int titleX = ox + 14;
		if (logo != null)
		{
			g.drawImage(logo, ox + 12, oy + 5, 28, 28, null);
			titleX = ox + 46;
		}
		g.setFont(FontManager.getRunescapeBoldFont());
		LofTheme.shadowText(g, title, titleX, oy + 25, LofTheme.GOLD);
		if (subtitle != null)
		{
			g.setFont(FontManager.getRunescapeSmallFont());
			LofTheme.shadowText(g, subtitle, ox + w - 44 - g.getFontMetrics().stringWidth(subtitle), oy + 24, LofTheme.TEXT_DIM);
		}

		final Rectangle cr = closeRect(ox, oy, w);
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

	/**
	 * Standard footer button (§5). The fill + border carry the {@code accent} (GOLD = primary/Accept,
	 * EMBER = Decline/destructive, GOLD_DIM = secondary), but the LABEL is always drawn in a
	 * high-contrast colour — never the low-contrast accent — so a dark accent (ember) can't render
	 * red-on-dark and "mix in". Dim + inert when disabled.
	 */
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
			enabled ? label(accent) : LofTheme.TEXT_DIM);
	}

	/**
	 * The legible label colour for a button of the given accent, on our dark panel: a light accent
	 * (gold, gold-dim) reads fine as-is, but a darker accent (ember/red) would "mix in", so its label
	 * falls back to near-white {@link LofTheme#TEXT}. Perceptual luminance; the threshold sits between
	 * EMBER (~98, → white) and GOLD_DIM (~139, keeps its hue) so only the red case is rewritten.
	 */
	private static Color label(Color accent)
	{
		final double lum = 0.299 * accent.getRed() + 0.587 * accent.getGreen() + 0.114 * accent.getBlue();
		return lum >= 120 ? accent : LofTheme.TEXT;
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
