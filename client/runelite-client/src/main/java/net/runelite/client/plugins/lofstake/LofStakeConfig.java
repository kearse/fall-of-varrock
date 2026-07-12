/*
 * Fall of Varrock — Duel Arena stake overlay (config).
 */
package net.runelite.client.plugins.lofstake;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("lofstake")
public interface LofStakeConfig extends Config
{
	@ConfigItem(
		keyName = "enabled",
		name = "Enable stake window",
		description = "Show the themed Duel Arena stake window over the trade screen.",
		position = 1
	)
	default boolean enabled()
	{
		return true;
	}
}
