/*
 * Fall of Varrock — Task Helper scene overlay.
 *
 * Draws the active contract's hunting ground in the 3D scene: a bouncing arrow above the anchor
 * tile, captioned with the contract's kill count. Only renders when the target is on the player's
 * plane and inside the loaded scene — the minimap overlay covers the "it's far away" case.
 */
package net.runelite.client.plugins.loftaskhelper;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.lofquests.LofArrow;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class LofTaskHelperWorldOverlay extends Overlay
{
	/** How high (game units) above the tile the arrow floats. */
	private static final int ARROW_HEIGHT = 220;

	private final Client client;
	private final LofTaskHelperPlugin plugin;
	private final LofTaskHelperConfig config;

	@Inject
	LofTaskHelperWorldOverlay(Client client, LofTaskHelperPlugin plugin, LofTaskHelperConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showWorldArrow())
		{
			return null;
		}

		WorldPoint target = plugin.activeTarget();
		if (target == null || target.getPlane() != client.getPlane())
		{
			return null;
		}

		LocalPoint lp = LocalPoint.fromWorld(client, target);
		if (lp == null)
		{
			return null; // outside the loaded scene — the minimap arrow takes over
		}

		Point above = Perspective.localToCanvas(client, lp, client.getPlane(), ARROW_HEIGHT);
		if (above == null)
		{
			return null;
		}

		Color color = config.arrowColor();
		LofArrow.drawWorldArrow(graphics, color, above.getX(), above.getY());

		if (config.showProgressLabel())
		{
			String text = plugin.progressText();
			if (text != null)
			{
				graphics.setFont(FontManager.getRunescapeSmallFont());
				FontMetrics fm = graphics.getFontMetrics();
				int tx = above.getX() - fm.stringWidth(text) / 2;
				int ty = above.getY() - 20; // just above the arrow's tail
				graphics.setColor(Color.BLACK);
				graphics.drawString(text, tx + 1, ty + 1);
				graphics.setColor(color);
				graphics.drawString(text, tx, ty);
			}
		}

		return null;
	}
}
