/*
 * Fall of Varrock — Muster Companions window (config).
 */
package net.runelite.client.plugins.lofrecruit;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("lofrecruit")
public interface LofRecruitConfig extends Config
{
	@ConfigItem(
		keyName = "enabled",
		name = "Enable Muster Companions window",
		description = "Show the companion recruiter window when asking General Zo for troops.",
		position = 1
	)
	default boolean enabled()
	{
		return true;
	}
}
