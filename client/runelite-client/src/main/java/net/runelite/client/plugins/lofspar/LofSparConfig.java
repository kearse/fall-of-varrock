/*
 * Fall of Varrock — companion sparring settings overlay (config).
 */
package net.runelite.client.plugins.lofspar;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("lofspar")
public interface LofSparConfig extends Config
{
	@ConfigItem(
		keyName = "enabled",
		name = "Enable sparring settings window",
		description = "Show the themed Companion Sparring settings window when you challenge your companion.",
		position = 1
	)
	default boolean enabled()
	{
		return true;
	}
}
