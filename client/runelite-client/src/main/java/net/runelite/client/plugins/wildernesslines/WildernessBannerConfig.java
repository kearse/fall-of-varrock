/*
 * Kingdom of Lumbridge — Wilderness / single / multi combat status banner (client overlay).
 */
package net.runelite.client.plugins.wildernesslines;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("lofwildbanner")
public interface WildernessBannerConfig extends Config
{
	@ConfigItem(
		keyName = "showWhenSafe",
		name = "Show in safe areas",
		description = "Also show a 'SAFE ZONE' banner when you are not in the wilderness.",
		position = 1
	)
	default boolean showWhenSafe()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showLevel",
		name = "Show wilderness level",
		description = "Show the wilderness level (or 'PK Zone') under the combat type.",
		position = 2
	)
	default boolean showLevel()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		keyName = "multiColor",
		name = "Multi color",
		description = "Banner color in multi-combat zones.",
		position = 3
	)
	default Color multiColor()
	{
		return new Color(255, 64, 64);
	}

	@Alpha
	@ConfigItem(
		keyName = "singleColor",
		name = "Single color",
		description = "Banner color in single-combat zones.",
		position = 4
	)
	default Color singleColor()
	{
		return new Color(80, 220, 80);
	}

	@Alpha
	@ConfigItem(
		keyName = "safeColor",
		name = "Safe color",
		description = "Banner color in safe areas (when 'Show in safe areas' is on).",
		position = 5
	)
	default Color safeColor()
	{
		return new Color(160, 160, 160);
	}
}
