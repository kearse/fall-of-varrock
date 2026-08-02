/*
 * Fall of Varrock — Task Helper.
 *
 * A Quest-Helper-style guide for Vannaka's combat contracts (the Slayer tasks): guidance arrows in
 * the scene and on the minimap point the way to the active contract's hunting ground, so a player
 * never has to guess where their assignment lives. The arrows use their own colour (cyan by
 * default) so they can't be mistaken for the gold Quest Journal arrows, and the whole thing is an
 * ordinary plugin — toggle it off in the plugin list to hunt unaided.
 *
 * Progress itself is already tracked by the Slayer dial in the war-dial row (`lofdials`, varp
 * 4616); this plugin reads the same varp to know a contract is active and to caption the arrow
 * with the kill count. The target tile arrives in varp 4638, published by the server's
 * SlayerHudPlugin from SlayerHuntingGrounds (see Alter) — no custom packets.
 */
package net.runelite.client.plugins.loftaskhelper;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Lof Task Helper",
	description = "Points guidance arrows at your active war-contract's hunting ground; the war-dial row tracks the kills.",
	tags = {"lof", "slayer", "task", "contract", "assignment", "helper", "guide", "arrow"},
	enabledByDefault = true
)
public class LofTaskHelperPlugin extends Plugin
{
	/** Packed Slayer-task varp (SlayerHudPlugin): bits 0-11 killed, bits 12-23 total; 0 = no task. */
	static final int VARP_SLAYER = 4616;

	/** Packed hunting-ground varp (SlayerHudPlugin): bits 0-13 x, bits 14-27 z, bits 28-29 height;
	 *  0 = no task, or a task with no mapped hunting ground. */
	static final int VARP_TARGET = 4638;

	/** Tiles from the target within which the player has clearly arrived — the monsters are all
	 *  around, so the arrow gets out of the way (mirrors the Quest Journal's in-sight hand-off). */
	private static final int ARRIVAL_RADIUS = 15;

	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private LofTaskHelperWorldOverlay worldOverlay;

	@Inject
	private LofTaskHelperMinimapOverlay minimapOverlay;

	@Inject
	private LofTaskHelperConfig config;

	@Provides
	LofTaskHelperConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LofTaskHelperConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(worldOverlay);
		overlayManager.add(minimapOverlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(worldOverlay);
		overlayManager.remove(minimapOverlay);
	}

	/** The hunting-ground tile the overlays should point at right now (null = nothing to draw). */
	WorldPoint activeTarget()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}
		if (client.getVarpValue(VARP_SLAYER) == 0)
		{
			return null; // no active contract
		}
		int packed = client.getVarpValue(VARP_TARGET);
		if (packed == 0)
		{
			return null; // contract has no mapped hunting ground
		}
		WorldPoint target = new WorldPoint(packed & 0x3FFF, (packed >> 14) & 0x3FFF, (packed >> 28) & 0x3);
		if (config.hideOnArrival())
		{
			Player local = client.getLocalPlayer();
			WorldPoint playerLoc = local != null ? local.getWorldLocation() : null;
			// Chebyshev distance; off-plane reads as Integer.MAX_VALUE and never counts as arrived.
			if (playerLoc != null && playerLoc.distanceTo(target) <= ARRIVAL_RADIUS)
			{
				return null;
			}
		}
		return target;
	}

	/** The arrow's kill-count caption, e.g. "12/30" (null = no active contract). */
	String progressText()
	{
		int packed = client.getVarpValue(VARP_SLAYER);
		if (packed == 0)
		{
			return null;
		}
		int killed = packed & 0xFFF;
		int total = (packed >> 12) & 0xFFF;
		return killed + "/" + total;
	}
}
