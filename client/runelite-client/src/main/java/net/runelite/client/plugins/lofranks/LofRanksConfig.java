/*
 * Fall of Varrock — Feudal Ranks window (config).
 */
package net.runelite.client.plugins.lofranks;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("lofranks")
public interface LofRanksConfig extends Config
{
	@ConfigItem(
		keyName = "enabled",
		name = "Enable Feudal Ranks window",
		description = "Show the rank progression window when talking to Duke Horacio (or ::ranks).",
		position = 1
	)
	default boolean enabled()
	{
		return true;
	}
}
