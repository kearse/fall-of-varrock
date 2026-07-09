/*
 * Kingdom of Lumbridge — server announcement ticker (config).
 */
package net.runelite.client.plugins.lofannouncements;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("lofannouncements")
public interface LofAnnouncementsConfig extends Config
{
	@ConfigItem(
		keyName = "enabled",
		name = "Show ticker",
		description = "Show server announcements (boss spawns, campaigns, events) above the chat box.",
		position = 1
	)
	default boolean enabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "maxLines",
		name = "Lines shown",
		description = "How many of the most recent announcements to show at once.",
		position = 2
	)
	default int maxLines()
	{
		return 2;
	}

	@ConfigItem(
		keyName = "lifetimeSeconds",
		name = "Hold time (s)",
		description = "How long each announcement stays on screen before fading out.",
		position = 3
	)
	default int lifetimeSeconds()
	{
		return 25;
	}

	@ConfigItem(
		keyName = "fontSize",
		name = "Font size",
		description = "Text size of the announcement ticker.",
		position = 4
	)
	default int fontSize()
	{
		return 14;
	}
}
