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
 * Castle Wars timer on the right; a longer headline wraps onto as many rows underneath as it
 * needs (bullet on the first row only) — nothing is ever cut off. Each line keeps the colour the
 * server sent it in, else a warm yellow.
 */
package net.runelite.client.plugins.lofannouncements;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
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

		// Wrap each headline to the cap — a long one continues on the row underneath, keeping its
		// colour, with the bullet on the first row only. Measure the widest resulting row.
		int w = 0;
		final List<Line> lines = new ArrayList<>();
		for (String r : raw)
		{
			final Color color = colorOf(r);
			final List<String> rows = wrap(fm, Text.removeTags(r), cap);
			for (int j = 0; j < rows.size(); j++)
			{
				lines.add(new Line(rows.get(j), color, j == 0));
				w = Math.max(w, fm.stringWidth(rows.get(j)));
			}
		}

		final int totalH = lines.size() * lineH - LINE_GAP; // no trailing gap under the last line
		final int baseX = chat.x + MARGIN;
		final int baseY = chat.y - totalH - MARGIN; // bottom-anchored just above the chat box

		int y = baseY + fm.getAscent();
		for (Line line : lines)
		{
			final String text = line.text;
			final int textX = baseX + indent;

			if (line.first)
			{
				// Diamond bullet, centred on the text line, outlined for legibility.
				final int cx = baseX + BULLET_R;
				final int cy = y - fm.getAscent() / 2 + 1;
				final int[] dx = {cx, cx + BULLET_R, cx, cx - BULLET_R};
				final int[] dy = {cy - BULLET_R, cy, cy + BULLET_R, cy};
				graphics.setColor(Color.BLACK);
				graphics.drawPolygon(dx, dy, 4);
				graphics.setColor(line.color);
				graphics.fillPolygon(dx, dy, 4);
			}

			// Full 1px outline so warm colours stay legible against the bright game world.
			graphics.setColor(Color.BLACK);
			graphics.drawString(text, textX - 1, y);
			graphics.drawString(text, textX + 1, y);
			graphics.drawString(text, textX, y - 1);
			graphics.drawString(text, textX, y + 1);
			graphics.setColor(line.color);
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

	/**
	 * Word-wrap text into rows of at most maxW pixels — every word is kept, nothing is truncated.
	 * A single word wider than maxW is hard-broken mid-word.
	 */
	private static List<String> wrap(FontMetrics fm, String text, int maxW)
	{
		final List<String> rows = new ArrayList<>();
		String rest = text.trim();
		while (!rest.isEmpty())
		{
			if (fm.stringWidth(rest) <= maxW)
			{
				rows.add(rest);
				return rows;
			}
			// Longest prefix that fits, preferring the last space inside it as the break point.
			int end = rest.length();
			while (end > 0 && fm.stringWidth(rest.substring(0, end)) > maxW)
			{
				end--;
			}
			int brk = rest.lastIndexOf(' ', end);
			if (brk <= 0)
			{
				brk = Math.max(1, end);
			}
			rows.add(rest.substring(0, brk).stripTrailing());
			rest = rest.substring(brk).trim();
		}
		return rows;
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

	/** One rendered row: a headline's first row (bulleted) or a wrapped continuation under it. */
	private static final class Line
	{
		private final String text;
		private final Color color;
		private final boolean first;

		private Line(String text, Color color, boolean first)
		{
			this.text = text;
			this.color = color;
			this.first = first;
		}
	}
}
