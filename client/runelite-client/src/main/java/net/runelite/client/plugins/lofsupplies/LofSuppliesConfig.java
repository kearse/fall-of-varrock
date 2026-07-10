/*
 * Fall of Varrock — war-supply dial (config).
 */
package net.runelite.client.plugins.lofsupplies;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("lofsupplies")
public interface LofSuppliesConfig extends Config
{
	@ConfigItem(
		keyName = "enabled",
		name = "Show supply dial",
		description = "Show the realm war-supply gauge.",
		position = 1
	)
	default boolean enabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showNumbers",
		name = "Show supply count",
		description = "Show the supply total in the middle of the dial instead of a percentage.",
		position = 2
	)
	default boolean showNumbers()
	{
		return true;
	}
}
