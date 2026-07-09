/*
 * Kingdom of Lumbridge — teleport portal overlay (config).
 */
package net.runelite.client.plugins.lofteleports;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("lofteleports")
public interface LofTeleportsConfig extends Config
{
	@ConfigItem(
		keyName = "enabled",
		name = "Enable teleport menu",
		description = "Show the tabbed teleport window when the home portal is used.",
		position = 1
	)
	default boolean enabled()
	{
		return true;
	}
}
