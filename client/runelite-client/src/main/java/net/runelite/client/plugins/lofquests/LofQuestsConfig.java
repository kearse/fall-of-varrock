/*
 * Fall of Varrock — Quest Journal config.
 */
package net.runelite.client.plugins.lofquests;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("lofquests")
public interface LofQuestsConfig extends Config
{
	@ConfigItem(
		keyName = "showWorldArrow",
		name = "World arrow",
		description = "Draw an arrow over the tracked quest's objective in the game scene",
		position = 1
	)
	default boolean showWorldArrow()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showMinimapArrow",
		name = "Minimap arrow",
		description = "Point toward the tracked quest's objective on the minimap",
		position = 2
	)
	default boolean showMinimapArrow()
	{
		return true;
	}

	@ConfigItem(
		keyName = "highlightTargetTile",
		name = "Highlight objective tile",
		description = "Outline the tracked objective's tile in the game scene",
		position = 3
	)
	default boolean highlightTargetTile()
	{
		return true;
	}

	@ConfigItem(
		keyName = "autoTrack",
		name = "Auto-track active quest",
		description = "Automatically track whichever quest is in progress when none is selected",
		position = 4
	)
	default boolean autoTrack()
	{
		return true;
	}

	@ConfigItem(
		keyName = "highlightObjectiveNpcs",
		name = "Highlight objective creatures",
		description = "Outline the tracked objective's target creatures in the scene and dot them on the "
			+ "minimap (e.g. the castle rats) so small, wandering targets are easy to find",
		position = 6
	)
	default boolean highlightObjectiveNpcs()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		keyName = "arrowColor",
		name = "Arrow colour",
		description = "Colour of the guidance arrows and tile highlight",
		position = 5
	)
	default Color arrowColor()
	{
		return new Color(0xFF, 0xD2, 0x00);
	}
}
