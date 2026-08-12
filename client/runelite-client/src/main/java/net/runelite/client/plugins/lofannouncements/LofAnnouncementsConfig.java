/*
 * Fall of Varrock — server announcement ticker (config).
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
		keyName = "hideFromChat",
		name = "Hide from chat box",
		description = "Keep server broadcasts out of the chat box — they only show in the ticker (roat-style).",
		position = 2
	)
	default boolean hideFromChat()
	{
		return true;
	}

	@ConfigItem(
		keyName = "maxLines",
		name = "Lines shown",
		description = "How many of the most recent announcements to show at once.",
		position = 3
	)
	default int maxLines()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "lifetimeSeconds",
		name = "Hold time (s)",
		description = "How long each announcement stays on screen before fading out.",
		position = 4
	)
	default int lifetimeSeconds()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "fontSize",
		name = "Font size",
		description = "Text size of the announcement ticker.",
		position = 5
	)
	default int fontSize()
	{
		return 16;
	}
}
