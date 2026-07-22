/*
 * Fall of Varrock — build stamp (overlay).
 *
 * A small "FoV <buildId>" pill snapped to the top-left of the game view. Deliberately plain and
 * unobtrusive: warm text on a translucent dark pill, drawn relative to the snap corner (0,0) so
 * it works in fixed and resizable modes and stays movable/hideable via the standard overlay menu.
 */
package net.runelite.client.plugins.lofbuild;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.plugins.loftheme.LofTheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

class LofBuildOverlay extends Overlay
{
	private static final Color BG = LofTheme.alpha(LofTheme.PANEL_OPAQUE, 160);
	private static final Color TEXT = LofTheme.GOLD_DIM;
	private static final int PAD_X = 5;
	private static final int PAD_Y = 3;

	private final LofBuildPlugin plugin;

	@Inject
	private LofBuildOverlay(LofBuildPlugin plugin)
	{
		this.plugin = plugin;
		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(OverlayPriority.LOW);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		final String text = "FoV " + plugin.getBuildId();

		graphics.setFont(FontManager.getRunescapeSmallFont());
		final FontMetrics fm = graphics.getFontMetrics();
		final int w = fm.stringWidth(text) + PAD_X * 2;
		final int h = fm.getHeight() + PAD_Y * 2;

		graphics.setColor(BG);
		graphics.fillRect(0, 0, w, h);

		LofTheme.shadowText(graphics, text, PAD_X, PAD_Y + fm.getAscent(), TEXT);

		return new Dimension(w, h);
	}
}
