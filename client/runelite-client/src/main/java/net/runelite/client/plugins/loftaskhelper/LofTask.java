/*
 * Fall of Varrock — the Task Helper's combat-contract lore.
 *
 * Display data for the combat contract a player is SIGNED to: where each of Vannaka's targets
 * hunts (mirroring the server's SlayerHuntingGrounds blurbs) and what a kill pays. The tab only
 * ever shows the player's active contracts — this is the lookup that turns the contract varps'
 * npc into a readable card, not a browsable roster.
 *
 * Keep this in step with the server's SlayerTasks/SlayerHuntingGrounds when targets change —
 * nothing breaks if it drifts (the arrow and kill count are varp-driven; an unknown target just
 * gets a card with no location blurb), the lore text only goes stale.
 */
package net.runelite.client.plugins.loftaskhelper;

import lombok.Getter;

@Getter
enum LofTask
{
	// ── Peasant — the home roster around Lumbridge ──
	GOBLINS("Goblins", "Goblin", "The goblin field east of the castle.", 10),
	GIANT_RATS("Giant rats", "Giant rat", "The western swamp.", 6),
	GIANT_SPIDERS("Giant spiders", "Giant spider", "The spider nest east of the castle.", 12),
	MEN("Men", "Man", "Townsfolk by the castle.", 7),
	WOMEN("Women", "Woman", "Townsfolk by the castle.", 7),
	COWS("Cows", "Cow", "The cow field north-west of town.", 8),
	CHICKENS("Chickens", "Chicken", "The farm pen north-west of town.", 3),
	SKELETONS("Skeletons", "Skeleton", "No mapped hunting ground yet — no arrow, but the bones are out there.", 15),
	ZOMBIES("Zombies", "Zombie", "The Mire's undead corner, south-west of the yard house.", 15),
	GUARDS("Guards", "Guard", "The castle guards.", 18),
	// ── Commoner — work on the roads out of the home vale ──
	HOBGOBLINS("Hobgoblins", "Hobgoblin", "The hobgoblin peninsula south of the Crafting Guild.", 22),
	DARK_WIZARDS("Dark wizards", "Dark wizard", "The wizards' stone circle south-west of Falador.", 20),
	// ── Squire ──
	BLACK_KNIGHTS("Black Knights", "Black Knight", "The Black Knights' grounds north of fallen Falador.", 30),
	HILL_GIANTS("Hill Giants", "Hill Giant", "The giant camp east of Al Kharid.", 28),
	// ── Soldier — the demons that overran the fallen cities ──
	LESSER_DEMONS("Lesser demons", "Lesser demon", "The demolished Grand Exchange in fallen Varrock.", 45),
	// ── Knight — top of the ladder ──
	GREATER_DEMONS("Greater demons", "Greater demon", "Fallen Varrock's east street — raid-city ground, go armed.", 55);

	/** Plural label, matching Vannaka's contract wording (server SlayerTask.display). */
	private final String displayName;

	/** The target's cache name — how the live contract's npc id maps back to this entry. */
	private final String npcName;

	/** Where the monsters hunt, in words — shown on the card under the name. */
	private final String where;

	/** Slayer xp per kill. */
	private final int xpPerKill;

	LofTask(String displayName, String npcName, String where, int xpPerKill)
	{
		this.displayName = displayName;
		this.npcName = npcName;
		this.where = where;
		this.xpPerKill = xpPerKill;
	}
}
