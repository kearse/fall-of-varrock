/*
 * Kingdom of Lumbridge — server announcement ticker (overlay).
 *
 * Roak-style: RuneScape font, coloured text on a transparent background (no panel), anchored
 * just above the chat box, left-aligned. Each line keeps the colour the server sent it in
 * (boss spawns orange, campaign captures gold, etc.), falling back to a warm yellow.
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

	@Inject
	private LofAnnouncementsOverlay(LofAnnouncementsPlugin plugin)
	{
		this.plugin = plugin;
		// Top-centre broadcast banner — clear of the chat box so it never collides with chat text
		// and always reads against the world behind it. Movable, so players can reposition it.
		setPosition(OverlayPosition.TOP_CENTER);
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

		// Pre-measure so we can centre each line within the banner's width.
		int w = 0;
		for (String line : raw)
		{
			w = Math.max(w, fm.stringWidth(Text.removeTags(line)));
		}

		int y = fm.getAscent();
		for (String line : raw)
		{
			final Color color = colorOf(line);
			final String text = Text.removeTags(line);
			final int x = (w - fm.stringWidth(text)) / 2;

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
