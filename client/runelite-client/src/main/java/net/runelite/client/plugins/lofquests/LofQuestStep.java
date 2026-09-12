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

	/** Counted objective size for a framework (generic-varp) quest step — the " (n/goal)" suffix is
	 *  drawn from the generic progress bits while the step is active; 0 = not a counted step. */
	private final int goal;

	LofQuestStep(int ordinal, String label, String detail, WorldPoint target, int goal)
	{
		this.ordinal = ordinal;
		this.label = label;
		this.detail = detail;
		this.target = target;
		this.goal = goal;
	}

	LofQuestStep(int ordinal, String label, String detail, WorldPoint target)
	{
		this(ordinal, label, detail, target, 0);
	}

	LofQuestStep(int ordinal, String label, WorldPoint target)
	{
		this(ordinal, label, null, target, 0);
	}
}
