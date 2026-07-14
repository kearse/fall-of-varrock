/*
 * Fall of Varrock — storefront tab strip (config).
 */
package net.runelite.client.plugins.lofshoptabs;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("lofshoptabs")
public interface LofShopTabsConfig extends Config
{
	@ConfigItem(
		keyName = "enabled",
		name = "Enable shop tabs",
		description = "Show the store tab strip on the shop window for vendors with several stores.",
		position = 1
	)
	default boolean enabled()
	{
		return true;
	}
}
