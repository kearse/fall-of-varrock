/*
 * Fall of Varrock — Wilderness / single / multi combat status banner.
 *
 * Client-side overlay that shows a top-right banner with the player's current PvP zone
 * (MULTI vs SINGLE combat + wilderness level). Zone geometry is mirrored from the
 * server's PvpZones.kt in WildernessZones — keep them in sync.
 */
package net.runelite.client.plugins.wildernesslines;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Fall of Varrock Wilderness Banner",
	description = "Top-right banner showing your current PvP zone (multi/single combat + wilderness level).",
	tags = {"lof", "wilderness", "wild", "pvp", "multi", "single", "banner", "pk"},
	enabledByDefault = true
)
public class WildernessBannerPlugin extends Plugin
{
	@Inject
	private OverlayManager overlayManager;

	@Inject
	private WildernessBannerOverlay overlay;

	@Provides
	WildernessBannerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(WildernessBannerConfig.class);
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
