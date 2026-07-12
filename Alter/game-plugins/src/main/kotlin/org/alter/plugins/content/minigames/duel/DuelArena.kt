package org.alter.plugins.content.minigames.duel

import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.plugins.content.companion.Companion as CompanionPawn
import org.alter.plugins.content.companion.CompanionRegistry

/**
 * Live-duel registry. Kept as a top-level object (not plugin state) so [org.alter.plugins.content.combat.Combat]
 * can consult it statically: `canEngage` unlocks player-vs-player for exactly the two sides of an
 * in-progress staked duel, so they can fight in the (safe, non-wilderness) Duel Arena while everyone
 * else stays gated. Driven by [DuelArenaPlugin].
 */
object DuelArena {

    /**
     * Whether the challenge flow uses the custom clickable **rules-grid interface** (cache group
     * authored by DuelRulesCacheTool) instead of the chatbox rule menus. OFF by default until the
     * interface is authored into the cache AND verified at a live client (a custom-interface open
     * against a cache that lacks the group crashes the client → instant logout). Flip live with
     * `::duelgrid`; make it default-true here once verified.
     */
    var useRulesGrid = false

    /**
     * Whether the challenge flow uses the **themed client-overlay** rules screen
     * ([DuelRulesClientMenu] → `net.runelite.client.plugins.lofduel`). ON by default: unlike the
     * cache rules-grid, a client overlay can't crash the client, so it's safe to ship. Takes
     * precedence over [useRulesGrid]. Flip with `::dueloverlay`.
     */
    var useRulesOverlay = true

    /** Every duel currently in progress (counting down or fighting). */
    val active = mutableListOf<Duel>()

    /** The duel [p] is part of, or null. */
    fun duelOf(p: Player): Duel? = active.firstOrNull { it.has(p) }

    /** The rules of the duel [p] is currently in (any phase), or null — the guard consulted by the
     *  prayer/food/potion/movement enforcement points. */
    fun rulesOf(p: Player): DuelRules? = duelOf(p)?.rules

    /** True only while [a] and [b] are the two sides of the SAME duel that has begun fighting. */
    fun areDueling(a: Player, b: Player): Boolean {
        val d = duelOf(a) ?: return false
        return d.fighting && d.has(a) && d.has(b) && a !== b
    }

    /** A combatant's party root: a companion resolves to its owner, anyone else to themself. */
    private fun partyRoot(world: World, p: Player): Player =
        (p as? CompanionPawn)?.let { CompanionRegistry.ownerOf(world, it) } ?: p

    /**
     * Duel isolation — consulted by [org.alter.plugins.content.combat.Combat.canEngage] for every
     * player-vs-player engagement. Blocks any attack that would break a staked duel's bubble:
     *  - an outsider hitting a duelist (or a duelist hitting a bystander) while the duel runs,
     *  - anyone swinging during the countdown,
     *  - friendly fire inside a party,
     *  - COMPANIONS joining a duel whose rules don't allow them (the default). When the
     *    "Allow companions" rule is on, both parties' companions may fight the other party — the 4v4.
     */
    fun blocksEngagement(attacker: Player, target: Player): Boolean {
        val world = attacker.world
        val rootA = partyRoot(world, attacker)
        val rootT = partyRoot(world, target)
        val dA = duelOf(rootA)
        val dT = duelOf(rootT)
        if (dA == null && dT == null) return false // no duel involved — normal combat rules apply
        if (dA !== dT) return true                 // a duelist and an outsider — sealed both ways
        val d = dA!!                               // both parties belong to the same duel
        if (!d.fighting) return true               // countdown — nobody swings early
        if (rootA === rootT) return true           // same party — no friendly fire
        val companionInvolved = attacker !== rootA || target !== rootT
        if (!companionInvolved) return false       // the two principals — always allowed
        if (!d.rules.allowCompanions) return true  // companions barred by the rules (the default)
        return attacker in d.benched || target in d.benched // a KO'd companion is out for the duel
    }
}

/**
 * The agreed rule set for a duel. The clean, cleanly-enforceable classic toggles are here; the
 * equipment-slot disables / fun-weapons / obstacles toggles are a later slice.
 */
class DuelRules(
    val noMelee: Boolean = false,
    val noRanged: Boolean = false,
    val noMagic: Boolean = false,
    val noPrayer: Boolean = false,
    val noFood: Boolean = false,
    val noDrinks: Boolean = false,
    val noMovement: Boolean = false,
    val noForfeit: Boolean = false,
    /** Companions may fight alongside their owners (both sides) — up to a 4v4. Default OFF. */
    val allowCompanions: Boolean = false,
    /** Equipment slot ids that can't be worn (e.g. Boxing disables every slot). */
    val disabledSlots: Set<Int> = emptySet(),
    /** RSCM item ids allowed in the weapon slot (null = any weapon). Whip-only / DDS-only / fun weapons. */
    val allowedWeapons: Set<Int>? = null,
    /** Display label for the gear restriction (e.g. "Boxing", "Whip only"), or null. */
    val gearLabel: String? = null,
) {
    /** Human-readable one-liner for the challenge/confirm messaging. */
    fun summary(): String {
        val parts = buildList {
            if (noMelee) add("No Melee"); if (noRanged) add("No Ranged"); if (noMagic) add("No Magic")
            if (noPrayer) add("No Prayer"); if (noFood) add("No Food"); if (noDrinks) add("No Drinks")
            if (noMovement) add("No Movement"); if (noForfeit) add("No Forfeit")
            if (allowCompanions) add("Companions allowed")
            gearLabel?.let { add(it) }
        }
        return if (parts.isEmpty()) "No rules" else parts.joinToString(", ")
    }
}

/** One live duel: the two combatants, their escrowed stakes, and the fight phase. */
class Duel(
    val a: Player,
    val b: Player,
    /** [a]'s staked items, held in escrow — awarded (with [stakeB]) to the winner. */
    val stakeA: List<Item>,
    /** [b]'s staked items, held in escrow. */
    val stakeB: List<Item>,
    /** The agreed rule set enforced for the fight. */
    val rules: DuelRules = DuelRules(),
) {
    /** false during the 3-2-1 countdown (attacks blocked); true once "FIGHT!" is called. */
    var fighting = false

    /** Countdown ticks remaining before the fight starts. */
    var countdown = 0

    /**
     * Companions knocked out during a companions-allowed duel. A dead companion auto-respawns at
     * home with full HP (the companion system protects them) and would otherwise snap straight back
     * to its owner as an immortal reinforcement — benching keeps it OUT until the duel resolves.
     */
    val benched = mutableListOf<Player>()

    fun has(p: Player): Boolean = a === p || b === p
    fun opponentOf(p: Player): Player = if (a === p) b else a
    val stakes: List<Item> get() = stakeA + stakeB
}
