/*
 * Fall of Varrock — Grand Exchange window (config).
 */
package net.runelite.client.plugins.lofge;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("lofge")
public interface LofGeConfig extends Config
{
	@ConfigItem(
		keyName = "enabled",
		name = "Show Grand Exchange window",
		description = "Show the custom Grand Exchange offer window when you talk to the GE clerk or type ::ge.",
		position = 1
	)
	default boolean enabled()
	{
		return true;
	}
}
