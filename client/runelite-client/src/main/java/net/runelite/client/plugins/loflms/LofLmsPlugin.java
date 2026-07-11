/*
 * Fall of Varrock — Last Man Standing HUD.
 *
 * The OSRS-style LMS overlay: while you're in a game it shows the players remaining, your kills
 * this round, and the countdown to the next fog contraction. The server's LmsGame packs the round
 * state into one varp each tick; this overlay reads it (no custom packets).
 */
package net.runelite.client.plugins.loflms;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Lof LMS",
	description = "Last Man Standing HUD: players remaining, kills, and the fog countdown.",
	tags = {"lof", "lms", "last man standing", "minigame", "pvp"},
	enabledByDefault = true
)
public class LofLmsPlugin extends Plugin
{
	@Inject
	private OverlayManager overlayManager;

	@Inject
	private LofLmsOverlay overlay;

	@Provides
	LofLmsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LofLmsConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
	}
}
