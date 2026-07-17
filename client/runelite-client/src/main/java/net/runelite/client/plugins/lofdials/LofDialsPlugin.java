/*
 * Fall of Varrock — war dial row.
 *
 * A right-anchored row of circular gauges above the chatbox. War supplies is pinned to the far right;
 * the campaign/conquest/boss progress dial and the Slayer-task dial stack to its left and appear only
 * when they have data. Each dial reads one packed varp published by a server HUD plugin
 * (WarSupplyHudPlugin / WarProgressPlugin / SlayerHudPlugin) — no custom packets.
 */
package net.runelite.client.plugins.lofdials;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Lof War Dials",
	description = "Row of war dials: Slayer task, campaign/conquest progress and the realm war-supply meter.",
	tags = {"lof", "war", "supplies", "slayer", "campaign", "conquest", "boss", "progress", "dial", "gauge"},
	enabledByDefault = true
)
public class LofDialsPlugin extends Plugin
{
	@Inject
	private OverlayManager overlayManager;

	@Inject
	private LofDialsOverlay overlay;

	@Provides
	LofDialsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LofDialsConfig.class);
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
