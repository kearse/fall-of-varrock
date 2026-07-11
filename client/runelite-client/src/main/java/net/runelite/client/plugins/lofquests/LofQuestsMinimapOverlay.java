/*
 * Fall of Varrock — Quest Journal minimap overlay.
 *
 * When the tracked objective sits inside the minimap's view, a small arrow bobs over its spot;
 * when it's beyond the edge, an arrow at the minimap's rim points the way (the classic Quest
 * Helper behaviour — see LofArrow for the adapted math).
 */
package net.runelite.client.plugins.lofquests;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.geom.Line2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class LofQuestsMinimapOverlay extends Overlay
{
	private final Client client;
	private final LofQuestsPlugin plugin;
	private final LofQuestsConfig config;

	@Inject
	LofQuestsMinimapOverlay(Client client, LofQuestsPlugin plugin, LofQuestsConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showMinimapArrow())
		{
			return null;
		}

		WorldPoint target = plugin.activeTarget();
		Player player = client.getLocalPlayer();
		if (target == null || player == null)
		{
			return null;
		}

		// In view: a small arrow bobbing over the objective's minimap spot.
		LocalPoint lp = LocalPoint.fromWorld(client, target);
		if (lp != null)
		{
			Point onMinimap = Perspective.localToMinimap(client, lp);
			if (onMinimap != null)
			{
				Line2D.Double line = new Line2D.Double(
					onMinimap.getX(), onMinimap.getY() - 18,
					onMinimap.getX(), onMinimap.getY() - 8);
				LofArrow.drawMinimapArrow(graphics, line, config.arrowColor());
				return null;
			}
		}

		// Out of view: an arrow at the minimap's rim pointing toward the objective.
		WorldPoint playerWp = player.getWorldLocation();
		Point playerOnMinimap = player.getMinimapLocation();
		Point direction = LofArrow.getMinimapDirectionPoint(client, playerWp, target);
		if (playerOnMinimap != null && direction != null)
		{
			LofArrow.drawMinimapArrow(graphics, LofArrow.getEdgeLine(playerOnMinimap, direction), config.arrowColor());
		}

		return null;
	}
}
