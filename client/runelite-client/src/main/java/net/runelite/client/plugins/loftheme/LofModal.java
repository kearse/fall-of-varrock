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
	/** The one default window size every standard modal uses (docs/overlay-design-system.md §6A). */
	public static final int W = 480;
	public static final int H = 400;
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
	 * Centre a window {@code h} tall in the game view, but keep its bottom edge above the chat box so
	 * it never covers the chat — the way a default OSRS interface sits in the viewport. On a roomy
	 * canvas this is a plain vertical centre; as the client shrinks the window rides up to stay off the
	 * chat, and only a window taller than the room above the chat (a minimised client) reaches into
	 * that band. Clamped so it never clips the top.
	 */
	public static int originY(Client client, int h)
	{
		final int canvasH = client.getCanvasHeight();
		final int aboveChat = canvasH - CHATBOX_RESERVE;
		return clamp(Math.min((canvasH - h) / 2, aboveChat - h), canvasH, h);
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

	public static Rectangle closeRect(int ox, int oy)
	{
		return new Rectangle(ox + W - 30, oy + 9, 20, 20);
	}

	/** Panel + header strip + logo + gold title + dim right-aligned subtitle + close ✕. */
	public static void frame(Graphics2D g, int ox, int oy, String title, String subtitle, Point mouse)
	{
		LofTheme.panel(g, ox, oy, W, H, ARC);

		final Shape headerClip = g.getClip();
		g.setClip(ox, oy, W, TITLE_H);
		g.setColor(LofTheme.HEADER);
		g.fillRoundRect(ox, oy, W, TITLE_H + ARC, ARC, ARC);
		g.setClip(headerClip);
		LofTheme.emberUnderline(g, ox + 1, oy + TITLE_H - 2, W - 2);

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
			LofTheme.shadowText(g, subtitle, ox + W - 44 - g.getFontMetrics().stringWidth(subtitle), oy + 24, LofTheme.TEXT_DIM);
		}

		final Rectangle cr = closeRect(ox, oy);
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
