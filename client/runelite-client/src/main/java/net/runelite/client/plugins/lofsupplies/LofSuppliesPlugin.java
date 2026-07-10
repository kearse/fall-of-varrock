/*
 * Fall of Varrock — realm war-supply dial.
 *
 * A circular gauge that keeps the realm's war-supply meter (the skilling → war bridge) in front
 * of every player. The server's WarSupplyHudPlugin packs meter/max + campaign-ready flags into a
 * varp each refresh; this overlay reads it (no custom packets).
 */
package net.runelite.client.plugins.lofsupplies;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Lof War Supplies",
	description = "Circular gauge for the realm's war-supply meter.",
	tags = {"lof", "war", "supplies", "quartermaster", "mire", "gauge"},
	enabledByDefault = true
)
public class LofSuppliesPlugin extends Plugin
{
	@Inject
	private OverlayManager overlayManager;

	@Inject
	private LofSuppliesOverlay overlay;

	@Provides
	LofSuppliesConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LofSuppliesConfig.class);
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
