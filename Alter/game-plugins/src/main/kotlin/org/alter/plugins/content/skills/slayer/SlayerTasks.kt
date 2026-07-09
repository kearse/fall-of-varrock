package org.alter.plugins.content.skills.slayer

/**
 * A single Slayer assignment: hunt [amount] of [npcName] for [xpPerKill] Slayer xp each.
 * [minCombat] gates assignment to suitable players. Data only — see [SlayerPlugin].
 */
data class SlayerTask(
    val npcName: String,   // RSCM key, e.g. "npc.goblin"
    val display: String,   // plural label, e.g. "Goblins"
    val minCombat: Int,
    val amount: IntRange,
    val xpPerKill: Double,
)

/**
 * The starter (Turael-tier) Slayer roster, drawn from monsters that live in and around
 * Lumbridge — the server's home. NPC keys are resolved (and missing ones skipped) at
 * plugin load, so an unknown key never crashes; expand this list as content grows.
 */
object SlayerTasks {
    val ALL: List<SlayerTask> = listOf(
        SlayerTask("npc.goblin", "Goblins", 1, 20..40, 10.0),
        SlayerTask("npc.rat_2854", "Rats", 1, 15..30, 4.0), // the small rats that live around Lumbridge castle
        SlayerTask("npc.giant_rat", "Giant rats", 1, 15..30, 6.0),
        SlayerTask("npc.giant_spider", "Giant spiders", 1, 15..35, 12.0),
        SlayerTask("npc.man", "Men", 1, 15..30, 7.0),
        SlayerTask("npc.woman", "Women", 1, 15..30, 7.0),
        SlayerTask("npc.cow", "Cows", 1, 15..35, 8.0),
        SlayerTask("npc.chicken", "Chickens", 1, 15..30, 3.0),
        SlayerTask("npc.skeleton", "Skeletons", 10, 20..40, 15.0),
        SlayerTask("npc.zombie", "Zombies", 10, 20..40, 15.0),
        SlayerTask("npc.guard", "Guards", 15, 25..45, 18.0),
    )
}
