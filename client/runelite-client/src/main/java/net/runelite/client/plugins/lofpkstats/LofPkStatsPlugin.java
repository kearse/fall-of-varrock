/*
 * Kingdom of Lumbridge — PK / KD stats overlay.
 *
 * Renders the roak-style top-left info box: Kills, Deaths, K/D ratio, Elo + global TOP% rank.
 * The server's PkStatsPlugin publishes the four values into varps each refresh; this overlay
 * just reads them (no custom packets).
 */
package net.runelite.client.plugins.lofpkstats;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Kingdom of Lumbridge PK Stats",
	description = "Top-left PK info box: Kills, Deaths, K/D ratio and Elo rank.",
	tags = {"lof", "pk", "pvp", "kd", "elo", "stats"},
	enabledByDefault = true
)
public class LofPkStatsPlugin extends Plugin
{
	@Inject
	private OverlayManager overlayManager;

	@Inject
	private LofPkStatsOverlay overlay;

	@Provides
	LofPkStatsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LofPkStatsConfig.class);
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
