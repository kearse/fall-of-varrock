/*
 * Fall of Varrock — Castle Wars timer overlay.
 *
 * Shows the countdown above the chatbox (right side): "Next war" while in a waiting room,
 * "War ends" plus the live team score while fighting. The server's CastleWars engine publishes
 * the values into varps each tick; this overlay just reads them (no custom packets).
 */
package net.runelite.client.plugins.lofcwtimer;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Fall of Varrock Castle Wars Timer",
	description = "Countdown above the chatbox while waiting for or fighting in Castle Wars.",
	tags = {"lof", "castle", "wars", "cw", "timer", "minigame"},
	enabledByDefault = true
)
public class LofCwTimerPlugin extends Plugin
{
	@Inject
	private OverlayManager overlayManager;

	@Inject
	private LofCwTimerOverlay overlay;

	@Provides
	LofCwTimerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LofCwTimerConfig.class);
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
