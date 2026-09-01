package org.alter.plugins.content.skills.slayer

import org.alter.plugins.content.war.Title

/**
 * A single Slayer assignment: hunt [amount] of [npcName] for [xpPerKill] Slayer xp each.
 * [minCombat]/[maxCombat] gate assignment to a suitable combat band; [minTitle] gates it to a
 * feudal rank (Vannaka's intro promises "the hardest are reserved for higher ranks" — this is
 * that gate); [weight] biases how often a task is picked; [assignable]=false keeps a task OUT
 * of the random pool (it's handed out only by a script, e.g. the tutorial rats). Data only —
 * see [SlayerPlugin].
 */
data class SlayerTask(
    val npcName: String,   // RSCM key, e.g. "npc.goblin"
    val display: String,   // plural label, e.g. "Goblins"
    val minCombat: Int,
    val amount: IntRange,
    val xpPerKill: Double,
    /** Above this combat level the task retires from the random pool (too trivial to be worth handing
     *  a stronger player). Default = never retires. */
    val maxCombat: Int = Int.MAX_VALUE,
    /** Relative frequency in the random pool (higher = assigned more often). */
    val weight: Int = 1,
    /** The lowest feudal rank Vannaka signs this contract to — the combat side of the rank-tiered
     *  faucet (see [ResourceContracts]): rank gates the CONTRACT, not the monster. */
    val minTitle: Title = Title.PEASANT,
    /** When false the task is NEVER handed out randomly — only a script assigns it (e.g. the tutorial
     *  rats, which must stay the scripted intro contract so they can't be pulled as an ordinary task). */
    val assignable: Boolean = true,
)

/**
 * The Slayer roster, tiered by rank like [ResourceContracts]: the Peasant band is drawn from
 * monsters that live in and around Lumbridge — the server's home — and each rank bought from
 * Duke Horacio unlocks tougher, better-paying work further afield. NPC keys are resolved (and
 * missing ones skipped) at plugin load, so an unknown key never crashes; expand this list as
 * content grows.
 */
object SlayerTasks {
    val ALL: List<SlayerTask> = listOf(
        // ── Peasant — the home roster around Lumbridge ──
        SlayerTask("npc.goblin", "Goblins", 1, 20..40, 10.0, maxCombat = 40, weight = 5),
        // Regular rats are the SCRIPTED tutorial contract only — never a random assignment (players
        // reported being handed "regular rats" as an ordinary task). assignable = false enforces that.
        SlayerTask("npc.rat_2854", "Rats", 1, 15..30, 4.0, assignable = false),
        SlayerTask("npc.giant_rat", "Giant rats", 1, 15..30, 6.0, maxCombat = 25, weight = 2),
        SlayerTask("npc.giant_spider", "Giant spiders", 1, 15..35, 12.0, maxCombat = 60, weight = 5),
        // npc.man / npc.chicken / npc.guard have NO bare RSCM key (only id-suffixed variants),
        // so the pool builder silently dropped these three tasks. Kill credit is by cache NAME,
        // so any resolvable variant of the same name works.
        SlayerTask("npc.man_385", "Men", 1, 15..30, 7.0, maxCombat = 35, weight = 3),
        SlayerTask("npc.woman", "Women", 1, 15..30, 7.0, maxCombat = 35, weight = 3),
        SlayerTask("npc.cow", "Cows", 1, 15..35, 8.0, maxCombat = 35, weight = 3),
        SlayerTask("npc.chicken_1173", "Chickens", 1, 15..30, 3.0, maxCombat = 20, weight = 2),
        // Skeletons have no sanctioned Slayer spawn yet (SlayerHuntingGrounds notes it), so the
        // task was assignable with nowhere to complete it. Gated off until a spawn is anchored.
        SlayerTask("npc.skeleton", "Skeletons", 10, 20..40, 15.0, weight = 5, assignable = false),
        SlayerTask("npc.zombie", "Zombies", 10, 20..40, 15.0, weight = 5),
        SlayerTask("npc.guard_397", "Guards", 15, 25..45, 18.0, weight = 5),
        // ── Commoner (10k) — work on the roads out of the home vale ──
        SlayerTask("npc.hobgoblin", "Hobgoblins", 20, 20..40, 22.0, weight = 4, minTitle = Title.COMMONER),
        SlayerTask("npc.dark_wizard", "Dark wizards", 20, 15..30, 20.0, weight = 4, minTitle = Title.COMMONER),
        // ── Squire (50k) ──
        SlayerTask("npc.black_knight", "Black Knights", 35, 15..30, 30.0, weight = 4, minTitle = Title.SQUIRE),
        SlayerTask("npc.hill_giant", "Hill Giants", 35, 15..30, 28.0, weight = 4, minTitle = Title.SQUIRE),
        // ── Soldier (150k) — the demons that overran the fallen cities ──
        SlayerTask("npc.lesser_demon", "Lesser demons", 55, 12..25, 45.0, weight = 4, minTitle = Title.SOLDIER),
        // ── Knight (500k) — top of the ladder ──
        SlayerTask("npc.greater_demon", "Greater demons", 70, 12..25, 55.0, weight = 4, minTitle = Title.KNIGHT),
    )
}
