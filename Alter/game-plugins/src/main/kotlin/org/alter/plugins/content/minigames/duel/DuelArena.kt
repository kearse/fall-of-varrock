package org.alter.plugins.content.minigames.duel

import dev.openrune.cache.CacheManager.getItem
import org.alter.api.EquipmentType
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.plugins.content.companion.Companion as CompanionPawn
import org.alter.plugins.content.companion.CompanionRegistry
import org.alter.plugins.content.raids.RaidInstance
import org.alter.rscm.RSCM.getRSCM

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
 * The agreed rule set for a duel — the classic 13-rule grid minus the two that need interface/map
 * work (Show Inventories → Phase 3, Obstacles → Phase 4), plus our companions rule.
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
    /** Classic bit 2 (2016): the weapon worn at FIGHT! is locked for the whole duel — kills the
     *  hasta bait-and-switch and the mid-duel DDS-spec finisher. */
    val noWeaponSwitch: Boolean = false,
    /** Classic bit 13: special attacks can't be used (arming the spec bar is denied). */
    val noSpec: Boolean = false,
    /** Classic bit 12: attacks only land with a whitelisted joke weapon ([FUN_WEAPONS]) — bare
     *  fists are NOT allowed (the whitelist is also enforced at the equip point). */
    val funWeapons: Boolean = false,
    /** Classic bit 3 (informational, no enforcement): each player can see the OTHER's backpack
     *  and worn gear on the stake/confirm screens — item identities, stack quantities hidden. */
    val showInventories: Boolean = false,
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
            if (noWeaponSwitch) add("No Weapon Switch"); if (noSpec) add("No Special Attacks")
            if (showInventories) add("Show Inventories")
            if (allowCompanions) add("Companions allowed")
            gearLabel?.let { add(it) }
        }
        return if (parts.isEmpty()) "No rules" else parts.joinToString(", ")
    }

    /**
     * True when a duel under these rules bars [itemId] from equipment slot [slotId] — the ONE
     * wearability answer shared by the equip-revert handler, the duel-start strip, and the
     * stake-screen space check. Covers: a disabled slot, a non-whitelisted weapon, and the classic
     * implication that disabling the weapon OR shield slot also bans every 2H weapon (a 2H
     * occupies both, so either restriction covers it — this is what makes the official Whip
     * preset "any ONE-HANDED weapon").
     */
    fun barsWorn(slotId: Int, itemId: Int): Boolean {
        if (slotId in disabledSlots) return true
        if (slotId == EquipmentType.WEAPON.id) {
            if (allowedWeapons?.let { itemId !in it } == true) return true
            if (EquipmentType.SHIELD.id in disabledSlots &&
                getItem(itemId).equipType == EquipmentType.SHIELD.id
            ) return true // a 2H weapon occupies the (disabled) shield slot
        }
        return false
    }

    /**
     * The single winnability/escapability gate every rules entry path (overlay, cache grid,
     * chatbox menus) and [begin] itself must pass — the classic dependency matrix. Each banned
     * pair below exists because the original game shipped without it and someone weaponised the
     * gap (see docs/duel-arena-research.md §2.3). Returns the refusal message, or null when the
     * rule set is fightable.
     *
     * No Forfeit + No Movement is deliberately ALLOWED (it was the standard whip-stake format,
     * and both bits are set in the decoded official Whip preset): with melee guaranteed available
     * (No Forfeit + No Melee is banned below), adjacent No-Movement spawns mean the fight can
     * always progress, and the 15-minute draw timer ends any turtled stalemate.
     */
    fun validate(): String? {
        val meleeOnlyWeapons = funWeapons || allowedWeapons != null || EquipmentType.WEAPON.id in disabledSlots
        return when {
            noMelee && noRanged && noMagic ->
                "You must leave at least one combat style available."
            noForfeit && noMelee ->
                "No Forfeit can't be set with No Melee — a fighter could run out of ammo or runes."
            // Every weapon whitelist we offer (whip / DDS / fun weapons) and bare-fist boxing are
            // melee — pairing them with No Melee leaves no sanctioned way to swing.
            noMelee && meleeOnlyWeapons ->
                "That weapon restriction is a melee restriction — it can't be set with No Melee."
            else -> null
        }
    }

    companion object {
        /** Resolve a set of RSCM item names to ids, skipping any that don't exist in the cache. */
        fun weaponIds(vararg names: String): Set<Int> =
            names.mapNotNull { runCatching { getRSCM(it) }.getOrNull() }.toSet()

        /** The negative-bonus joke weapons sanctioned by the Fun Weapons rule. */
        val FUN_WEAPONS: Set<Int> by lazy {
            weaponIds(
                "item.rubber_chicken", "item.stale_baguette", "item.giant_frog_legs",
                "item.mole_slippers", "item.frozen_whip_mix",
            )
        }

        /** The house "Whip only" whitelist (stricter than the official Whip preset). */
        val WHIP_WEAPONS: Set<Int> by lazy { weaponIds("item.abyssal_whip") }

        /** The house "DDS only" whitelist. */
        val DDS_WEAPONS: Set<Int> by lazy {
            weaponIds(
                "item.dragon_dagger", "item.dragon_dagger_p",
                "item.dragon_dagger_p+", "item.dragon_dagger_p++",
            )
        }
    }
}

/** How a duel ended — picks the resolution messaging. */
enum class DuelEnd { DEATH, FORFEIT, LOGOUT }

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

    /** Ticks fought since "FIGHT!" — staked duels are called a draw at the classic 15 minutes. */
    var fightTicks = 0

    /**
     * The weapon each principal wore at FIGHT! (null = bare fists), captured only under the
     * No Weapon Switch rule — the id the equip handlers and the tick backstop hold the weapon
     * slot to for the rest of the duel.
     */
    var lockedWeaponA: Int? = null
    var lockedWeaponB: Int? = null

    /** Capture both principals' FIGHT!-moment weapons for the No Weapon Switch rule. */
    fun lockWeapons(weaponSlot: Int) {
        lockedWeaponA = a.equipment[weaponSlot]?.id
        lockedWeaponB = b.equipment[weaponSlot]?.id
    }

    fun lockedWeaponOf(p: Player): Int? = if (a === p) lockedWeaponA else lockedWeaponB

    /**
     * Set (via the pre-death hook) when BOTH principals' death sequences overlap — a double KO.
     * The first post-death hook to fire then resolves the duel as a draw instead of letting
     * processing order pick the winner. Never set for exhibition duels (the tournament needs a
     * winner, and keeps the first-death-loses behaviour).
     */
    var drawPending = false

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
