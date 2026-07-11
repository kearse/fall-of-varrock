/*
 * Fall of Varrock — Quest Journal scene overlay.
 *
 * Draws the tracked quest's objective in the 3D scene: a bouncing arrow above the target tile and
 * an outline on the tile itself. Only renders when the target is on the player's plane and inside
 * the loaded scene — the minimap overlay covers the "it's far away" case.
 */
package net.runelite.client.plugins.lofquests;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

class LofQuestsWorldOverlay extends Overlay
{
	/** How high (game units) above the tile the arrow floats. */
	private static final int ARROW_HEIGHT = 220;

	private final Client client;
	private final LofQuestsPlugin plugin;
	private final LofQuestsConfig config;

	@Inject
	LofQuestsWorldOverlay(Client client, LofQuestsPlugin plugin, LofQuestsConfig config)
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

		Color color = config.arrowColor();

		if (config.highlightTargetTile())
		{
			Polygon poly = Perspective.getCanvasTilePoly(client, lp);
			if (poly != null)
			{
				OverlayUtil.renderPolygon(graphics, poly, color);
			}
		}

		if (config.showWorldArrow())
		{
			Point above = Perspective.localToCanvas(client, lp, client.getPlane(), ARROW_HEIGHT);
			if (above != null)
			{
				LofArrow.drawWorldArrow(graphics, color, above.getX(), above.getY());
			}
		}

		return null;
	}
}
