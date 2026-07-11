/*
 * Fall of Varrock — server announcement ticker (overlay).
 *
 * Roak-style: RuneScape font, coloured text on a transparent background (no panel), anchored
 * just above the chat box, left-aligned. Each line keeps the colour the server sent it in
 * (boss spawns orange, campaign captures gold, etc.), falling back to a warm yellow. The block
 * is capped at 3/4 of the canvas width so it never runs across the whole screen; longer lines
 * are truncated with an ellipsis.
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

	private final LofAnnouncementsPlugin plugin;
	private final Client client;

	@Inject
	private LofAnnouncementsOverlay(LofAnnouncementsPlugin plugin, Client client)
	{
		this.plugin = plugin;
		this.client = client;
		// Just above the chat box, left-aligned (the classic broadcast spot). Movable, so
		// players can reposition it.
		setPosition(OverlayPosition.BOTTOM_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setMovable(true);
		setSnappable(true);
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

		// Cap the block at 3/4 of the CHAT BOX width (the announcements sit directly above it,
		// left-aligned). This keeps the right quarter of the chat strip clear for the war-supply
		// dial and the Castle Wars timer, which anchor to ABOVE_CHATBOX_RIGHT — so the text never
		// runs under those. Fall back to the classic 519px chat width if the widget isn't loaded.
		final Widget chatbox = client.getWidget(ComponentID.CHATBOX_FRAME);
		final int chatW = chatbox != null ? chatbox.getWidth() : Math.min(519, client.getCanvasWidth());
		final int cap = Math.max(120, chatW * 3 / 4);

		// Pre-fit each line to the cap (ellipsised) and measure the widest.
		int w = 0;
		final String[] fitted = new String[raw.size()];
		for (int i = 0; i < raw.size(); i++)
		{
			final String text = ellipsise(fm, Text.removeTags(raw.get(i)), cap);
			fitted[i] = text;
			w = Math.max(w, fm.stringWidth(text));
		}

		int y = fm.getAscent();
		for (int i = 0; i < raw.size(); i++)
		{
			final Color color = colorOf(raw.get(i));
			final String text = fitted[i];
			final int x = 0; // left-aligned

			// Full 1px outline (not just a single drop-shadow) so warm colours stay legible
			// against the bright game world behind the transparent banner.
			graphics.setColor(Color.BLACK);
			graphics.drawString(text, x - 1, y);
			graphics.drawString(text, x + 1, y);
			graphics.drawString(text, x, y - 1);
			graphics.drawString(text, x, y + 1);
			graphics.setColor(color);
			graphics.drawString(text, x, y);
			y += lineH;
		}

		return new Dimension(w, raw.size() * lineH);
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
