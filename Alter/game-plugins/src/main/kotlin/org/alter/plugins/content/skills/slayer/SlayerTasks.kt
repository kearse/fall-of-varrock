package org.alter.plugins.content.skills.slayer

/**
 * A single Slayer assignment: hunt [amount] of [npcName] for [xpPerKill] Slayer xp each.
 * [minCombat]/[maxCombat] gate assignment to a suitable combat band; [weight] biases how often
 * a task is picked; [assignable]=false keeps a task OUT of the random pool (it's handed out only
 * by a script, e.g. the tutorial rats). Data only — see [SlayerPlugin].
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
    /** When false the task is NEVER handed out randomly — only a script assigns it (e.g. the tutorial
     *  rats, which must stay the scripted intro contract so they can't be pulled as an ordinary task). */
    val assignable: Boolean = true,
)

/**
 * The starter (Turael-tier) Slayer roster, drawn from monsters that live in and around
 * Lumbridge — the server's home. NPC keys are resolved (and missing ones skipped) at
 * plugin load, so an unknown key never crashes; expand this list as content grows.
 */
object SlayerTasks {
    val ALL: List<SlayerTask> = listOf(
        SlayerTask("npc.goblin", "Goblins", 1, 20..40, 10.0, maxCombat = 40, weight = 5),
        // Regular rats are the SCRIPTED tutorial contract only — never a random assignment (players
        // reported being handed "regular rats" as an ordinary task). assignable = false enforces that.
        SlayerTask("npc.rat_2854", "Rats", 1, 15..30, 4.0, assignable = false),
        SlayerTask("npc.giant_rat", "Giant rats", 1, 15..30, 6.0, maxCombat = 25, weight = 2),
        SlayerTask("npc.giant_spider", "Giant spiders", 1, 15..35, 12.0, maxCombat = 60, weight = 5),
        SlayerTask("npc.man", "Men", 1, 15..30, 7.0, maxCombat = 35, weight = 3),
        SlayerTask("npc.woman", "Women", 1, 15..30, 7.0, maxCombat = 35, weight = 3),
        SlayerTask("npc.cow", "Cows", 1, 15..35, 8.0, maxCombat = 35, weight = 3),
        SlayerTask("npc.chicken", "Chickens", 1, 15..30, 3.0, maxCombat = 20, weight = 2),
        SlayerTask("npc.skeleton", "Skeletons", 10, 20..40, 15.0, weight = 5),
        SlayerTask("npc.zombie", "Zombies", 10, 20..40, 15.0, weight = 5),
        SlayerTask("npc.guard", "Guards", 15, 25..45, 18.0, weight = 5),
    )
}
