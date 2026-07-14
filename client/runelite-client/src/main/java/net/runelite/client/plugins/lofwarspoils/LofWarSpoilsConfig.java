/*
 * Fall of Varrock — Spoils of War window (config).
 */
package net.runelite.client.plugins.lofwarspoils;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("lofwarspoils")
public interface LofWarSpoilsConfig extends Config
{
	@ConfigItem(
		keyName = "enabled",
		name = "Show war spoils window",
		description = "Show the Spoils of War claim window when you type ::claim after winning a war.",
		position = 1
	)
	default boolean enabled()
	{
		return true;
	}
}
