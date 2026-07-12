/*
 * Fall of Varrock — server announcement ticker (overlay).
 *
 * Coloured broadcast lines drawn on a transparent background, positioned ABSOLUTELY just above
 * the chat box, left-aligned. We compute the position from the chat-box widget instead of using a
 * BOTTOM_LEFT snap corner, because in fixed mode that corner sits at the viewport bottom and the
 * text renders behind the opaque chat frame (invisible). The block is capped at 3/4 of the chat
 * width so it never runs under the war-supply dial / Castle Wars timer on the right; longer lines
 * are ellipsised. Each line keeps the colour the server sent it in, else a warm yellow.
 */
package net.runelite.client.plugins.lofannouncements;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import net.runelite.api.Client;
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

		graphics.setFont(FontManager.getRunescapeFont());
		final FontMetrics fm = graphics.getFontMetrics();
		final int lineH = fm.getHeight();

		// Position straight from the canvas — NOT the chat-box widget, which can report an
		// off-screen/zeroed location in fixed mode and hide the ticker entirely (that was the bug).
		// The classic chat box is ~165px tall in the bottom-left; sit just above it, left-aligned.
		final int chatX = MARGIN;
		final int chatW = Math.min(519, client.getCanvasWidth());
		final int chatTop = client.getCanvasHeight() - 165;

		// Cap at 3/4 of the chat width so the right quarter stays clear for the dial / CW timer.
		final int cap = Math.max(120, chatW * 3 / 4);

		// Fit each line to the cap (ellipsised) and measure the widest.
		int w = 0;
		final String[] fitted = new String[raw.size()];
		for (int i = 0; i < raw.size(); i++)
		{
			final String text = ellipsise(fm, Text.removeTags(raw.get(i)), cap);
			fitted[i] = text;
			w = Math.max(w, fm.stringWidth(text));
		}

		final int totalH = raw.size() * lineH;
		final int baseX = chatX + MARGIN;
		final int baseY = chatTop - totalH - MARGIN; // sit just above the chat box

		int y = baseY + fm.getAscent();
		for (int i = 0; i < raw.size(); i++)
		{
			final Color color = colorOf(raw.get(i));
			final String text = fitted[i];

			// Full 1px outline so warm colours stay legible against the bright game world.
			graphics.setColor(Color.BLACK);
			graphics.drawString(text, baseX - 1, y);
			graphics.drawString(text, baseX + 1, y);
			graphics.drawString(text, baseX, y - 1);
			graphics.drawString(text, baseX, y + 1);
			graphics.setColor(color);
			graphics.drawString(text, baseX, y);
			y += lineH;
		}

		return new Dimension(w, totalH);
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
