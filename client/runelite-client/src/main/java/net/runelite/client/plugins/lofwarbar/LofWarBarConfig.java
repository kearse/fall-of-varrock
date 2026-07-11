/*
 * Fall of Varrock — war progress bar (config).
 */
package net.runelite.client.plugins.lofwarbar;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("lofwarbar")
public interface LofWarBarConfig extends Config
{
	@ConfigItem(
		keyName = "enabled",
		name = "Show progress bar",
		description = "Show a progress bar for the campaign or city boss you are fighting.",
		position = 1
	)
	default boolean enabled()
	{
		return true;
	}
}
