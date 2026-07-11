/*
 * Fall of Varrock — PK / KD stats overlay (config).
 */
package net.runelite.client.plugins.lofpkstats;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("lofpkstats")
public interface LofPkStatsConfig extends Config
{
	@ConfigItem(
		keyName = "enabled",
		name = "Show PK stats",
		description = "Show the Kills / Deaths / K/D / Elo info box.",
		position = 1
	)
	default boolean enabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showRank",
		name = "Show TOP% rank",
		description = "Append your global Elo percentile (e.g. 'TOP 78.2%') to the Elo line.",
		position = 2
	)
	default boolean showRank()
	{
		return true;
	}

	@ConfigItem(
		keyName = "fontSize",
		name = "Font size",
		description = "Text size of the PK stats box.",
		position = 3
	)
	default int fontSize()
	{
		return 13;
	}
}
