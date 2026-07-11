/*
 * Fall of Varrock — one step of a custom quest.
 *
 * Mirrors a server-side chain step (the ordinal must match the server enum's ordinal). Steps with
 * a world anchor get the guidance arrow + tile highlight when their quest is tracked.
 */
package net.runelite.client.plugins.lofquests;

import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

@Getter
class LofQuestStep
{
	/** The server-side step ordinal this row represents. */
	private final int ordinal;

	/** Short checklist label, e.g. "Kill 5 goblins in the back woods". */
	private final String label;

	/** Optional longer hint shown under the label while the step is active (nullable). */
	private final String detail;

	/** Where the arrow points while this step is active (nullable = no fixed spot). */
	private final WorldPoint target;

	LofQuestStep(int ordinal, String label, String detail, WorldPoint target)
	{
		this.ordinal = ordinal;
		this.label = label;
		this.detail = detail;
		this.target = target;
	}

	LofQuestStep(int ordinal, String label, WorldPoint target)
	{
		this(ordinal, label, null, target);
	}
}
