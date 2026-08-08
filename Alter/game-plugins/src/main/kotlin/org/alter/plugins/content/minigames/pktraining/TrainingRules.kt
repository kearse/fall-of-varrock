package org.alter.plugins.content.minigames.pktraining

/**
 * The rule set of one **companion sparring** bout — the duel rules screen's toggles, minus
 * everything stake-shaped (no forfeit, no wealth, no two-player accept handshake). A lightweight
 * PARALLEL of [org.alter.plugins.content.minigames.duel.DuelRules]: deliberately its own type, so
 * the sparring bout never has to masquerade as an active duel (stakes, escrow, 2-player state).
 *
 * Enforcement mirrors the duel sites one-for-one — each place that consults `DuelArena.rulesOf(p)`
 * also consults [CompanionSparring.rulesOf] (eating, potions, prayers, style bans, movement,
 * teleports, specials). XP is NEVER suppressed: earning combat XP is the point of the arena.
 */
data class TrainingRules(
    var noMelee: Boolean = false,
    var noRanged: Boolean = false,
    var noMagic: Boolean = false,
    var noPrayer: Boolean = false,
    var noFood: Boolean = false,
    var noDrinks: Boolean = false,
    var noMovement: Boolean = false,
    var noSpec: Boolean = false,
    /**
     * LMS-style level playing field: both fighters' combat CURRENT levels are set to 99 for the
     * bout only (base levels/XP untouched, restored at bout end — see the boost/restore pair in
     * CompanionSparringPlugin). Also unlocks the full loaner armoury for the bout, since the level
     * gates it enforces are met at 99s.
     */
    var maxStats: Boolean = false,
) {
    fun summary(): String {
        val on = buildList {
            if (noMelee) add("no melee")
            if (noRanged) add("no ranged")
            if (noMagic) add("no magic")
            if (noPrayer) add("no prayer")
            if (noFood) add("no food")
            if (noDrinks) add("no potions")
            if (noMovement) add("no movement")
            if (noSpec) add("no specials")
            if (maxStats) add("max stats")
        }
        return if (on.isEmpty()) "no restrictions" else on.joinToString(", ")
    }
}
