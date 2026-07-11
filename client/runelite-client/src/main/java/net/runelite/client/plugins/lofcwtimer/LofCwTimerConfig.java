/*
 * Fall of Varrock — Castle Wars timer overlay (config).
 */
package net.runelite.client.plugins.lofcwtimer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("lofcwtimer")
public interface LofCwTimerConfig extends Config
{
	@ConfigItem(
		keyName = "enabled",
		name = "Show Castle Wars timer",
		description = "Show the countdown box while waiting for or fighting in Castle Wars"
	)
	default boolean enabled()
	{
		return true;
	}
}
