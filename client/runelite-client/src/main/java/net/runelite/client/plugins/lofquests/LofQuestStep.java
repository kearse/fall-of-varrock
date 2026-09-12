/*
 * Fall of Varrock — one step of a custom quest.
 *
 * Mirrors a server-side chain step (the ordinal must match the server enum's ordinal). Steps with
 * a world anchor get the guidance arrow + tile highlight when their quest is tracked; steps with
 * target creatures ({@link #npcs}) also highlight those creatures in the scene and on the minimap,
 * so the journal always says WHERE to go and WHO to talk to (or what to fight).
 */
package net.runelite.client.plugins.lofquests;

import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

@Getter
class LofQuestStep
{
	private static final int[] NO_NPCS = new int[0];

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

	/** Creature ids this step is about — the npc to talk to, or the enemies to fight — highlighted
	 *  in the scene and dotted on the minimap while the step is active. Empty = none. */
	private int[] npcIds = NO_NPCS;

	/** When > 0, only creatures within this many tiles of {@link #target} are highlighted — for a
	 *  stock npc id the quest shares with the rest of the world (the checkpoint's White Knights vs.
	 *  Falador's castle knights). 0 = highlight the id anywhere. */
	private int npcRadius;

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

	/** The creatures this step is about (see {@link #npcIds}). Builder-style, for the registry. */
	LofQuestStep npcs(int... ids)
	{
		this.npcIds = ids;
		return this;
	}

	/** Restrict the creature highlight to within [radius] tiles of the step's target (see {@link #npcRadius}). */
	LofQuestStep nearTarget(int radius)
	{
		this.npcRadius = radius;
		return this;
	}

	/** True if this step highlights creature id [npcId] at world location [where] (null = anywhere). */
	boolean highlights(int npcId, WorldPoint where)
	{
		if (!contains(npcIds, npcId))
		{
			return false;
		}
		if (npcRadius <= 0 || target == null || where == null)
		{
			return true;
		}
		return where.getPlane() == target.getPlane() && where.distanceTo(target) <= npcRadius;
	}

	static boolean contains(int[] ids, int id)
	{
		for (int candidate : ids)
		{
			if (candidate == id)
			{
				return true;
			}
		}
		return false;
	}
}
