/*
 * Fall of Varrock — Task Helper config.
 */
package net.runelite.client.plugins.loftaskhelper;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("loftaskhelper")
public interface LofTaskHelperConfig extends Config
{
	@ConfigItem(
		keyName = "showWorldArrow",
		name = "World arrow",
		description = "Draw an arrow over your contract's hunting ground in the game scene",
		position = 1
	)
	default boolean showWorldArrow()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showMinimapArrow",
		name = "Minimap arrow",
		description = "Point toward your contract's hunting ground on the minimap",
		position = 2
	)
	default boolean showMinimapArrow()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showProgressLabel",
		name = "Kill-count caption",
		description = "Caption the world arrow with your contract's kill count (e.g. 12/30)",
		position = 3
	)
	default boolean showProgressLabel()
	{
		return true;
	}

	@ConfigItem(
		keyName = "highlightTargets",
		name = "Highlight assigned monsters",
		description = "Outline your contract's monsters in the scene and dot them on the minimap once they're in "
			+ "sight — every variant that counts for the kill is marked, and the arrow hands off to the outlines",
		position = 4
	)
	default boolean highlightTargets()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		keyName = "arrowColor",
		name = "Arrow colour",
		description = "Colour of the task guidance arrows and monster outlines (cyan by default, so they never read as the gold quest arrows)",
		position = 6
	)
	default Color arrowColor()
	{
		return new Color(0x00, 0xE5, 0xFF);
	}
}
