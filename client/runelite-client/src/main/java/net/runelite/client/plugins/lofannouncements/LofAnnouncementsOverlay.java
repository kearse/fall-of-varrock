/*
 * Fall of Varrock — server announcement ticker (overlay).
 *
 * Roat-style: coloured broadcast lines stacked on a transparent background directly above the
 * chat box, left-aligned, each led by a small diamond bullet in the line's colour. The block is
 * bottom-anchored — the newest headline sits closest to the chat and older ones push upward.
 *
 * Position comes from the CHATBOX_FRAME widget bounds (the same rectangle OverlayRenderer trusts
 * for its snap corners, correct in fixed AND resizable modes); if the widget is missing/zeroed we
 * fall back to the classic fixed-mode math (chat box ~165px tall in the bottom-left). We draw at
 * ABSOLUTE canvas coordinates and undo the renderer's translate — a drag offset saved from the
 * era when this overlay was movable still gets applied to DYNAMIC overlays, which used to push
 * the ticker off-screen.
 *
 * The block is capped at 3/4 of the chat width so it never runs under the war-supply dial /
 * Castle Wars timer on the right; longer lines are ellipsised. Each line keeps the colour the
 * server sent it in, else a warm yellow.
 */
package net.runelite.client.plugins.lofannouncements;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.Text;

class LofAnnouncementsOverlay extends Overlay
{
	private static final Color DEFAULT_COLOR = new Color(255, 238, 130);
	private static final Pattern COL = Pattern.compile("<col=([0-9a-fA-F]{6})>");
	private static final int MARGIN = 4;
	/** Vertical gap between stacked lines — the glyphs plus their 1px outline fill the font's
	 *  whole metric height, so without explicit leading adjacent headlines touch and merge. */
	private static final int LINE_GAP = 4;
	/** Diamond bullet: half-diagonal in px, plus the gap between bullet and text. */
	private static final int BULLET_R = 3;
	private static final int BULLET_GAP = 4;

	private final LofAnnouncementsPlugin plugin;
	private final Client client;

	@Inject
	private LofAnnouncementsOverlay(LofAnnouncementsPlugin plugin, Client client)
	{
		this.plugin = plugin;
		this.client = client;
		// DYNAMIC: we place ourselves (absolute, above the chat box) rather than snapping to a
		// corner. Not movable — the position tracks the chat box every frame.
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!plugin.isEnabled())
		{
			return null;
		}

		final List<String> raw = plugin.getLines();
		if (raw.isEmpty())
		{
			return null;
		}

		// This overlay draws at ABSOLUTE canvas coordinates. Undo any translate the renderer
		// applied (see file header).
		final Rectangle selfBounds = getBounds();
		graphics.translate(-selfBounds.x, -selfBounds.y);

		graphics.setFont(FontManager.getRunescapeFont().deriveFont((float) plugin.fontSize()));
		final FontMetrics fm = graphics.getFontMetrics();
		final int lineH = fm.getHeight() + LINE_GAP;

		// Anchor on the chat box. Prefer the real widget bounds; fall back to fixed-mode math if
		// the widget reports nothing usable (it can be zeroed early in a session).
		final Rectangle chat = chatBounds();
		final int indent = BULLET_R * 2 + BULLET_GAP;

		// Cap at 3/4 of the chat width so the right quarter stays clear for the dial / CW timer.
		final int cap = Math.max(120, chat.width * 3 / 4 - indent);

		// Fit each line to the cap (ellipsised) and measure the widest.
		int w = 0;
		final String[] fitted = new String[raw.size()];
		for (int i = 0; i < raw.size(); i++)
		{
			final String text = ellipsise(fm, Text.removeTags(raw.get(i)), cap);
			fitted[i] = text;
			w = Math.max(w, fm.stringWidth(text));
		}

		final int totalH = raw.size() * lineH - LINE_GAP; // no trailing gap under the last line
		final int baseX = chat.x + MARGIN;
		final int baseY = chat.y - totalH - MARGIN; // bottom-anchored just above the chat box

		int y = baseY + fm.getAscent();
		for (int i = 0; i < raw.size(); i++)
		{
			final Color color = colorOf(raw.get(i));
			final String text = fitted[i];
			final int textX = baseX + indent;

			// Diamond bullet, centred on the text line, outlined for legibility.
			final int cx = baseX + BULLET_R;
			final int cy = y - fm.getAscent() / 2 + 1;
			final int[] dx = {cx, cx + BULLET_R, cx, cx - BULLET_R};
			final int[] dy = {cy - BULLET_R, cy, cy + BULLET_R, cy};
			graphics.setColor(Color.BLACK);
			graphics.drawPolygon(dx, dy, 4);
			graphics.setColor(color);
			graphics.fillPolygon(dx, dy, 4);

			// Full 1px outline so warm colours stay legible against the bright game world.
			graphics.setColor(Color.BLACK);
			graphics.drawString(text, textX - 1, y);
			graphics.drawString(text, textX + 1, y);
			graphics.drawString(text, textX, y - 1);
			graphics.drawString(text, textX, y + 1);
			graphics.setColor(color);
			graphics.drawString(text, textX, y);
			y += lineH;
		}

		return new Dimension(w + indent, totalH);
	}

	/** The chat box rectangle in canvas coordinates, from the widget when it's sane. */
	private Rectangle chatBounds()
	{
		final Widget chatbox = client.getWidget(ComponentID.CHATBOX_FRAME);
		if (chatbox != null && !chatbox.isHidden())
		{
			final Rectangle b = chatbox.getBounds();
			if (b != null && b.width > 0 && b.height > 0 && b.y > 0)
			{
				return b;
			}
		}
		// Classic fixed-mode fallback: ~519x165 in the bottom-left of the canvas.
		final int w = Math.min(519, client.getCanvasWidth());
		return new Rectangle(0, client.getCanvasHeight() - 165, w, 165);
	}

	/** Truncate text with a trailing "…" so it fits within maxW pixels. */
	private static String ellipsise(FontMetrics fm, String text, int maxW)
	{
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

	/** First &lt;col=RRGGBB&gt; tag's colour, or the default warm yellow. */
	private static Color colorOf(String raw)
	{
		final Matcher m = COL.matcher(raw);
		if (m.find())
		{
			try
			{
				return new Color(Integer.parseInt(m.group(1), 16));
			}
			catch (NumberFormatException ignored)
			{
				// fall through
			}
		}
		return DEFAULT_COLOR;
	}
}
