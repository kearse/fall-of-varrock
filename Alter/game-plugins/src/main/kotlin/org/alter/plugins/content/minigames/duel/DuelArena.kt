package org.alter.plugins.content.minigames.duel

import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.plugins.content.companion.Companion as CompanionPawn
import org.alter.plugins.content.companion.CompanionRegistry
import org.alter.plugins.content.raids.RaidInstance

/**
 * Live-duel registry. Kept as a top-level object (not plugin state) so [org.alter.plugins.content.combat.Combat]
 * can consult it statically: `canEngage` unlocks player-vs-player for exactly the two sides of an
 * in-progress staked duel, so they can fight inside their private (safe, instanced) arena copy while
 * everyone else stays gated. Driven by [DuelArenaPlugin].
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

    /**
     * True when [attacker] and [target] belong to the SAME live duel and [blocksEngagement] has
     * cleared them — i.e. the duel itself sanctions this swing, so it needs no wilderness/level gate
     * and may happen inside the safe arena. Covers the two principals AND, in a companions-allowed
     * duel, both sides' companions (the 4v4) — which the ordinary PvP rules would otherwise block
     * now that companions no longer ride the PK-bot "attackable anywhere" bypass.
     */
    fun sanctionsEngagement(attacker: Player, target: Player): Boolean {
        val duel = duelOf(partyRoot(attacker.world, attacker)) ?: return false
        if (duel !== duelOf(partyRoot(target.world, target))) return false
        return !blocksEngagement(attacker, target)
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

/** One live duel: the two combatants, their escrowed stakes, their private arena, and the fight phase. */
class Duel(
    val a: Player,
    val b: Player,
    /** [a]'s staked items, held in escrow — awarded (with [stakeB]) to the winner. */
    val stakeA: List<Item>,
    /** [b]'s staked items, held in escrow. */
    val stakeB: List<Item>,
    /** The agreed rule set enforced for the fight. */
    val rules: DuelRules = DuelRules(),
    /** This duel's private instanced copy of the arena pit (one per duel; torn down when it empties). */
    val instance: RaidInstance,
    /** Where [a] stood when the stakes locked in — returned there when the duel resolves. */
    val returnA: Tile,
    /** Where [b] stood when the stakes locked in. */
    val returnB: Tile,
    /**
     * An exhibition duel carries no stakes and no stake messaging — the Automatic Tournament runs
     * its matches as exhibition [Duel]s so ALL the duel machinery (countdown, sealed bubble, rule
     * enforcement, death/logout resolution, teleport lock) applies unchanged; the tournament owns
     * the outcome via [onResolved].
     */
    val exhibition: Boolean = false,
) {
    /** Fired exactly once when the duel resolves: (winner, loser). Owner-content hook (tournament). */
    var onResolved: ((Player, Player) -> Unit)? = null

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
    fun returnTileOf(p: Player): Tile = if (a === p) returnA else returnB
    val stakes: List<Item> get() = stakeA + stakeB
}
