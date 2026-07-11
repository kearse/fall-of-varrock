/*
 * Fall of Varrock — war progress bar.
 *
 * Shows how far through the current fight the player is: a campaign's kill-quota progress, or the
 * HP depleted on a nearby city boss. The server's WarProgressPlugin packs the context + percent
 * into a varp each refresh; this overlay reads it (no custom packets).
 */
package net.runelite.client.plugins.lofwarbar;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Lof War Bar",
	description = "Progress bar for the campaign or city boss you are fighting.",
	tags = {"lof", "war", "campaign", "boss", "progress", "raid"},
	enabledByDefault = true
)
public class LofWarBarPlugin extends Plugin
{
	@Inject
	private OverlayManager overlayManager;

	@Inject
	private LofWarBarOverlay overlay;

	@Provides
	LofWarBarConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LofWarBarConfig.class);
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
