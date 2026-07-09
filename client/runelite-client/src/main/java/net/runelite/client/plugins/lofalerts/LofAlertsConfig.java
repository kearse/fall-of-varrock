/*
 * Kingdom of Lumbridge — server-driven on-screen alerts.
 */
package net.runelite.client.plugins.lofalerts;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("lofalerts")
public interface LofAlertsConfig extends Config
{
	@ConfigItem(
		keyName = "showBanner",
		name = "Show banner",
		description = "Show an on-screen banner when the server raises an alert.",
		position = 1
	)
	default boolean showBanner()
	{
		return true;
	}

	@ConfigItem(
		keyName = "playNotification",
		name = "Play notification",
		description = "Fire a desktop / sound notification when an alert triggers.",
		position = 2
	)
	default boolean playNotification()
	{
		return true;
	}

	@ConfigItem(
		keyName = "bannerText",
		name = "Banner text",
		description = "Text shown in the alert banner.",
		position = 3
	)
	default String bannerText()
	{
		return "⚔ SIEGE INCOMING! ⚔";
	}

	@ConfigItem(
		keyName = "durationSeconds",
		name = "Banner duration (s)",
		description = "How many seconds the banner stays on screen.",
		position = 4
	)
	default int durationSeconds()
	{
		return 6;
	}

	@ConfigItem(
		keyName = "alertVarp",
		name = "Alert varp (advanced)",
		description = "The server varplayer index watched for alerts. Non-zero = alert. Must match the server.",
		position = 5
	)
	default int alertVarp()
	{
		return 4600;
	}
}
