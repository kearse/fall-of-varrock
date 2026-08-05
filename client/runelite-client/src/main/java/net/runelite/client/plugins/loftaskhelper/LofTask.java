/*
 * Fall of Varrock — the Task Helper's contract roster.
 *
 * A client-side mirror of the server's SlayerTasks + SlayerHuntingGrounds (Alter,
 * org.alter.plugins.content.skills.slayer): every contract Vannaka can sign, where its monsters
 * hunt, and the terms (combat band, kill count, xp, rank gate). The sidebar tab lists these so a
 * player can read what each task is and pick one to be guided to — the pick only drives the
 * client's arrows and highlights; the CONTRACT itself still comes from Vannaka.
 *
 * Keep this in step with the server lists when tasks or hunting grounds change — nothing breaks
 * if it drifts (the active contract's arrow is varp-driven, not roster-driven), the roster just
 * goes stale.
 */
package net.runelite.client.plugins.loftaskhelper;

import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

@Getter
enum LofTask
{
	// ── Peasant — the home roster around Lumbridge ──
	GOBLINS("Goblins", "Goblin", new WorldPoint(3247, 3244, 0),
		"The goblin field east of the castle.", 1, 40, "20–40", 10, null),
	GIANT_RATS("Giant rats", "Giant rat", new WorldPoint(3163, 3173, 0),
		"The western swamp.", 1, 25, "15–30", 6, null),
	GIANT_SPIDERS("Giant spiders", "Giant spider", new WorldPoint(3246, 3248, 0),
		"The spider nest east of the castle.", 1, 60, "15–35", 12, null),
	MEN("Men", "Man", new WorldPoint(3216, 3219, 0),
		"Townsfolk by the castle.", 1, 35, "15–30", 7, null),
	WOMEN("Women", "Woman", new WorldPoint(3217, 3205, 0),
		"Townsfolk by the castle.", 1, 35, "15–30", 7, null),
	COWS("Cows", "Cow", new WorldPoint(3178, 3316, 0),
		"The cow field north-west of town.", 1, 35, "15–35", 8, null),
	CHICKENS("Chickens", "Chicken", new WorldPoint(3172, 3293, 0),
		"The farm pen north-west of town.", 1, 20, "15–30", 3, null),
	SKELETONS("Skeletons", "Skeleton", null,
		"No mapped hunting ground yet — no arrow, but the bones are out there.", 10, 0, "20–40", 15, null),
	ZOMBIES("Zombies", "Zombie", new WorldPoint(3231, 3191, 0),
		"The Mire's undead corner, south-west of the yard house.", 10, 0, "20–40", 15, null),
	GUARDS("Guards", "Guard", new WorldPoint(3221, 3222, 0),
		"The castle guards.", 15, 0, "25–45", 18, null),
	// ── Commoner — work on the roads out of the home vale ──
	HOBGOBLINS("Hobgoblins", "Hobgoblin", new WorldPoint(2910, 3287, 0),
		"The hobgoblin peninsula south of the Crafting Guild.", 20, 0, "20–40", 22, "Commoner"),
	DARK_WIZARDS("Dark wizards", "Dark wizard", new WorldPoint(2907, 3335, 0),
		"The wizards' stone circle south-west of Falador.", 20, 0, "15–30", 20, "Commoner"),
	// ── Squire ──
	BLACK_KNIGHTS("Black Knights", "Black Knight", new WorldPoint(3025, 3514, 0),
		"The Black Knights' grounds north of fallen Falador.", 35, 0, "15–30", 30, "Squire"),
	HILL_GIANTS("Hill Giants", "Hill Giant", new WorldPoint(3375, 3148, 0),
		"The giant camp east of Al Kharid.", 35, 0, "15–30", 28, "Squire"),
	// ── Soldier — the demons that overran the fallen cities ──
	LESSER_DEMONS("Lesser demons", "Lesser demon", new WorldPoint(3165, 3482, 0),
		"The demolished Grand Exchange in fallen Varrock.", 55, 0, "12–25", 45, "Soldier"),
	// ── Knight — top of the ladder ──
	GREATER_DEMONS("Greater demons", "Greater demon", new WorldPoint(3230, 3428, 0),
		"Fallen Varrock's east street — raid-city ground, go armed.", 70, 0, "12–25", 55, "Knight");

	/** Plural label, matching Vannaka's contract wording (server SlayerTask.display). */
	private final String displayName;

	/** The target's cache name — highlights match every npc variant sharing it, exactly like the
	 *  server credits kills. */
	private final String npcName;

	/** A known-walkable tile in the hunting ground (server SlayerHuntingGrounds); null = the task
	 *  has no mapped ground, so tracking it highlights monsters but draws no arrow. */
	private final WorldPoint huntingGround;

	/** Where the monsters hunt, in words — shown on the card under the name. */
	private final String where;

	/** Lowest combat level Vannaka assigns this contract to. */
	private final int minCombat;

	/** Combat level above which the task retires from Vannaka's pool (0 = never retires). */
	private final int maxCombat;

	/** Kill-count band of an assignment, e.g. "20–40". */
	private final String killRange;

	/** Slayer xp per kill. */
	private final int xpPerKill;

	/** Feudal rank that unlocks the contract (null = open to everyone). */
	private final String rankGate;

	LofTask(String displayName, String npcName, WorldPoint huntingGround, String where,
		int minCombat, int maxCombat, String killRange, int xpPerKill, String rankGate)
	{
		this.displayName = displayName;
		this.npcName = npcName;
		this.huntingGround = huntingGround;
		this.where = where;
		this.minCombat = minCombat;
		this.maxCombat = maxCombat;
		this.killRange = killRange;
		this.xpPerKill = xpPerKill;
		this.rankGate = rankGate;
	}

	/** One line of contract terms for the card, e.g. "Combat 20+ · 20–40 kills · 22 xp each". */
	String terms()
	{
		StringBuilder sb = new StringBuilder();
		if (minCombat > 1)
		{
			sb.append("Combat ").append(minCombat).append("+ · ");
		}
		else if (maxCombat > 0)
		{
			sb.append("Combat up to ").append(maxCombat).append(" · ");
		}
		sb.append(killRange).append(" kills · ").append(xpPerKill).append(" Slayer xp each");
		return sb.toString();
	}
}
