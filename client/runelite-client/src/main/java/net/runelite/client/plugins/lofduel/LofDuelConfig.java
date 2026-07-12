/*
 * Fall of Varrock — duel rules overlay (config).
 */
package net.runelite.client.plugins.lofduel;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("lofduel")
public interface LofDuelConfig extends Config
{
	@ConfigItem(
		keyName = "enabled",
		name = "Enable duel rules window",
		description = "Show the themed Duel Arena rules window when a duel is agreed.",
		position = 1
	)
	default boolean enabled()
	{
		return true;
	}
}
