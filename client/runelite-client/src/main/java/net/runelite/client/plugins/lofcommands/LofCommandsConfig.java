/*
 * Fall of Varrock — command window (config).
 */
package net.runelite.client.plugins.lofcommands;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("lofcommands")
public interface LofCommandsConfig extends Config
{
	@ConfigItem(
		keyName = "enabled",
		name = "Show command window",
		description = "Show the on-screen command window when you type ::commands (instead of dumping it to chat).",
		position = 1
	)
	default boolean enabled()
	{
		return true;
	}
}
