/*
 * Fall of Varrock — war dial row (config).
 */
package net.runelite.client.plugins.lofdials;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("lofdials")
public interface LofDialsConfig extends Config
{
	@ConfigItem(
		keyName = "enabled",
		name = "Show dial row",
		description = "Show the row of war dials (slayer task, campaign/conquest, war supplies).",
		position = 1
	)
	default boolean enabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showSupplies",
		name = "War supplies dial",
		description = "Show the realm war-supply gauge (pinned to the far right).",
		position = 2
	)
	default boolean showSupplies()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showProgress",
		name = "Campaign / conquest dial",
		description = "Show progress through the campaign, conquest or city boss you are fighting.",
		position = 3
	)
	default boolean showProgress()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showSlayer",
		name = "Slayer task dial",
		description = "Show how close you are to completing your current Slayer task.",
		position = 4
	)
	default boolean showSlayer()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showNumbers",
		name = "Show counts",
		description = "Show raw counts in the middle of each dial instead of a percentage.",
		position = 5
	)
	default boolean showNumbers()
	{
		return false;
	}
}
