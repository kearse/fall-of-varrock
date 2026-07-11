/*
 * Fall of Varrock — Last Man Standing HUD (config).
 */
package net.runelite.client.plugins.loflms;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("loflms")
public interface LofLmsConfig extends Config
{
	@ConfigItem(
		keyName = "enabled",
		name = "Show LMS HUD",
		description = "Show the Last Man Standing overlay (players remaining, kills, fog countdown) during a game.",
		position = 1
	)
	default boolean enabled()
	{
		return true;
	}
}
