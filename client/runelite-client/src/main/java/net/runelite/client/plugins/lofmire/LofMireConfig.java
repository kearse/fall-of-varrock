/*
 * Fall of Varrock — Mire Dispenser window (config).
 */
package net.runelite.client.plugins.lofmire;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("lofmire")
public interface LofMireConfig extends Config
{
	@ConfigItem(
		keyName = "enabled",
		name = "Enable Mire Dispenser window",
		description = "Show the Mire Run loot dispenser window when tagging the Ticket Dispenser.",
		position = 1
	)
	default boolean enabled()
	{
		return true;
	}
}
