/*
 * Fall of Varrock — supply-drop world-map marker.
 *
 * The server's raid-city supply drop (game-plugins content/raidzones/RareDropPlugin) announces a
 * warned drop ~hourly: a 5-minute warning naming a city+district, then the landing. Alongside the
 * human-facing headlines it sends machine lines on the BROADCAST channel (the FOV_INTRO: pattern):
 *
 *   FOV_RAID:WARN:<city>:<district>:<x>:<z>:<seconds>   — district centre, drop incoming
 *   FOV_RAID:DROP:<city>:<district>:<x>:<z>             — the exact landing tile
 *   FOV_RAID:CLEAR                                      — claimed, marker comes down
 *
 * This plugin turns those into a star on the world map (snap-to-edge + click-to-jump, like party
 * member markers): the WARN phase marks the district centre — the convergence point — and the
 * landing swaps it for the exact tile. Late log-ins are covered server-side (the current phase's
 * line is replayed on login). Markers also self-expire locally in case a CLEAR is missed.
 *
 * The raw machine lines are blanked from the chatbox here, and lofannouncements excludes the
 * prefix from the ticker (same contract as LofIntroPlugin.TRIGGER_PREFIX).
 */
package net.runelite.client.plugins.lofsupplydrop;

import java.awt.image.BufferedImage;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Fall of Varrock Supply Drop",
	description = "Marks the raid cities' warned supply drop on the world map.",
	tags = {"lof", "raid", "supply", "drop", "map", "marker"},
	enabledByDefault = true
)
public class LofSupplyDropPlugin extends Plugin
{
	/** Server machine-line prefix (mirror of RareDropPlugin.MAP_PREFIX — keep in sync). */
	public static final String TRIGGER_PREFIX = "FOV_RAID:";

	private static final BufferedImage ICON =
		ImageUtil.loadImageResource(LofSupplyDropPlugin.class, "supply_drop_icon.png");

	/** Local marker safety-expiry: WARN gets its lead time + a minute's grace (the DROP line
	 *  normally replaces it); a DROP marker matches the server's ~1h unclaimed lifetime. */
	private static final long WARN_GRACE_MS = 60_000L;
	private static final long DROP_LIFETIME_MS = 62 * 60_000L;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private WorldMapPointManager worldMapPointManager;

	private WorldMapPoint marker;
	private long markerExpiresAt;

	@Override
	protected void shutDown()
	{
		removeMarker();
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.BROADCAST)
		{
			return;
		}

		final String visible = Text.removeTags(event.getMessage()).trim();
		if (!visible.startsWith(TRIGGER_PREFIX))
		{
			return;
		}

		// Machine line, never meant to be read: blank it in the chatbox (the announcements
		// ticker hides all broadcasts anyway; this covers clients running without it).
		if (event.getMessageNode() != null)
		{
			event.getMessageNode().setValue("");
			clientThread.invokeLater(client::refreshChat);
		}

		handle(visible.substring(TRIGGER_PREFIX.length()).trim());
	}

	private void handle(String payload)
	{
		final String[] parts = payload.split(":");
		try
		{
			switch (parts[0])
			{
				case "WARN":
				{
					// WARN:<city>:<district>:<x>:<z>:<seconds>
					final int x = Integer.parseInt(parts[3]);
					final int z = Integer.parseInt(parts[4]);
					final long seconds = Long.parseLong(parts[5]);
					placeMarker(new WorldPoint(x, z, 0),
						"Supply drop incoming — " + parts[2] + " of " + parts[1],
						System.currentTimeMillis() + seconds * 1000L + WARN_GRACE_MS);
					break;
				}
				case "DROP":
				{
					// DROP:<city>:<district>:<x>:<z>
					final int x = Integer.parseInt(parts[3]);
					final int z = Integer.parseInt(parts[4]);
					placeMarker(new WorldPoint(x, z, 0),
						"Supply drop — DOWN in " + parts[2] + " of " + parts[1],
						System.currentTimeMillis() + DROP_LIFETIME_MS);
					break;
				}
				case "CLEAR":
					removeMarker();
					break;
				default:
					log.debug("unknown supply-drop line: {}", payload);
					break;
			}
		}
		catch (RuntimeException e)
		{
			// A malformed line must never take the plugin down — drop it and log.
			log.warn("bad supply-drop line '{}'", payload, e);
		}
	}

	private void placeMarker(WorldPoint where, String tooltip, long expiresAt)
	{
		removeMarker();
		final WorldMapPoint point = new WorldMapPoint(where, ICON);
		point.setSnapToEdge(true);
		point.setJumpOnClick(true);
		point.setName("Supply drop");
		point.setTooltip(tooltip);
		worldMapPointManager.add(point);
		marker = point;
		markerExpiresAt = expiresAt;
	}

	private void removeMarker()
	{
		if (marker != null)
		{
			worldMapPointManager.remove(marker);
			marker = null;
		}
	}

	/** Local safety net: a marker whose CLEAR/replacement never arrived expires on its own. */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (marker != null && System.currentTimeMillis() >= markerExpiresAt)
		{
			removeMarker();
		}
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		// Dev aid: ::supplydroptest cycles WARN (at your tile) -> DROP -> CLEAR locally.
		if (!"supplydroptest".equalsIgnoreCase(event.getCommand()) || client.getLocalPlayer() == null)
		{
			return;
		}
		final WorldPoint me = client.getLocalPlayer().getWorldLocation();
		if (marker == null)
		{
			handle("WARN:Falador:the Old Market:" + me.getX() + ":" + me.getY() + ":300");
		}
		else if (markerExpiresAt - System.currentTimeMillis() < DROP_LIFETIME_MS / 2)
		{
			handle("DROP:Falador:the Old Market:" + me.getX() + ":" + me.getY());
		}
		else
		{
			handle("CLEAR");
		}
	}
}
