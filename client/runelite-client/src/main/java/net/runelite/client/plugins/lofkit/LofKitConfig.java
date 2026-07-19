/*
 * Fall of Varrock — Kit editor overlay config.
 */
package net.runelite.client.plugins.lofkit;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("lofkit")
public interface LofKitConfig extends Config
{
	@ConfigItem(
		keyName = "enabled",
		name = "Enabled",
		description = "Show the kit editor window when the server opens it."
	)
	default boolean enabled()
	{
		return true;
	}
}
