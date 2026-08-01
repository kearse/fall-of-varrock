package org.alter.plugins.content.companion

import org.alter.game.model.PlayerUID
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.plugins.content.bots.BotLoadout
import org.alter.plugins.content.bots.BotStats
import org.alter.plugins.content.bots.BotStyle
import org.alter.plugins.content.bots.PkBot
import org.alter.plugins.content.combat.strategy.magic.CombatSpell

/** A companion's combat school — also its training focus and the gear it can wear. */
enum class CompanionStyle(val display: String, val botStyle: BotStyle) {
    MELEE("melee", BotStyle.MELEE),
    RANGE("ranged", BotStyle.RANGED),
    MAGE("magic", BotStyle.MAGIC),
}

/** What a companion is currently ordered to do. NB: the RuneLite panel highlights the active
 *  order by ORDINAL — only ever APPEND new orders, never reorder. */
enum class CompanionOrders { FOLLOW, TRAIN, DEPLOY, RETURN, ATTACK }

/**
 * A player-owned, permanent, **levelable** companion — a real [PkBot] (Player) tied to an [ownerUid].
 * Subclassing keeps `player is Companion` checks clean (death-no-drop, PvP honor, boss payout). Its
 * real skills + gear come from persistent [CompanionData] applied after spawn; the [loadout] is just a
 * vessel for the base combat style.
 */
class Companion(
    world: World,
    loadout: BotLoadout,
    val ownerUid: PlayerUID,
    val archetype: CompanionStyle,
) : PkBot(world, loadout) {
    /** Current orders (drives [CompanionBrain]). */
    var orders: CompanionOrders = CompanionOrders.FOLLOW
    /**
     * Where an [CompanionOrders.ATTACK] grind is anchored: the companion's tile when the order was
     * given. The companion holds this ground and farms the NPCs around IT (not the owner), so the
     * owner can walk away and leave it grinding. Cleared whenever any other order is issued.
     */
    var huntAnchor: Tile? = null
    /** Donor perk: auto-pick nearby loot straight into the owner's bank (see [CompanionLoot]). */
    var autoLoot: Boolean = false
    /** Slain-and-awaiting-recovery flag (revived at General Zo for a fee — keeps level + gear). */
    var dead: Boolean = false
    /** World cycle of the last honor-system chat message, so it isn't spammed each tick. */
    var lastHonorMsg: Int = 0
    /**
     * World cycle this companion entered the world. Only used by [CompanionRegistry]'s orphan sweep,
     * which needs a grace window so a companion spawned between two registry ticks (before its
     * roster entry is tracked) is never mistaken for an ownerless ghost and despawned.
     */
    var spawnCycle: Int = 0
    /**
     * Owner-chosen autocast override (panel "set spell"). `null` = the legacy behaviour: the mage
     * auto-scales its spell to its Magic level (see [CompanionMagic]). When set, that spell is used as
     * long as the companion's level allows it (the engine still gates the cast); otherwise it falls
     * back to the auto-scaled best. Only meaningful for [CompanionStyle.MAGE].
     */
    var autocastSpell: CombatSpell? = null
    /**
     * Last ammo (arrow) item id the companion fired, remembered so [CompanionRange] can auto-restock
     * the SAME type from the owner's bank even after the quiver fully empties (an empty stack drops the
     * equipment entry, losing the id). -1 = never had ammo.
     */
    var lastAmmoId: Int = -1
}

/**
 * Minimal vessel [BotLoadout]s for companions — empty gear (the player supplies it), no prayer,
 * trivial base stats (the real, persistent skills overwrite these right after spawn). Only the base
 * combat style matters here.
 */
object CompanionLoadouts {
    private fun vessel(style: BotStyle, key: String) = BotLoadout(
        key = key,
        displayName = "Companion",
        tier = "companion",
        combatLevel = 3,
        stats = BotStats(attack = 1, strength = 1, defence = 1, hitpoints = 10, ranged = 1, magic = 1, prayer = 1),
        baseStyle = style,
        gear = emptyMap(),
        usesPrayer = false,
    )

    fun forArchetype(a: CompanionStyle): BotLoadout = vessel(a.botStyle, "companion_${a.name.lowercase()}")
}
