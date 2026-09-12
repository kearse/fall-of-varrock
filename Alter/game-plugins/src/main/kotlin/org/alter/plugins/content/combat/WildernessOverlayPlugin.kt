package org.alter.plugins.content.combat

import org.alter.api.InterfaceDestination
import org.alter.api.cfg.Varbit
import org.alter.api.ext.closeInterface
import org.alter.api.ext.inWilderness
import org.alter.api.ext.isMulti
import org.alter.api.ext.openInterface
import org.alter.api.ext.message
import org.alter.api.ext.getVarbit
import org.alter.api.ext.getVarp
import org.alter.api.ext.setVarbit
import org.alter.api.ext.setVarp
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Drives client wilderness state — the missing piece behind "no Attack option / no wilderness
 * notification" even at wild coordinates.
 *
 * The stock OSRS client gates the right-click "Attack" on other players on the **IN_WILDERNESS
 * varbit (5963)** — NOT coordinates, NOT the overlay interface, NOT SetPlayerOp. The base server
 * never set that varbit, so the client never offered "Attack" (the overlay alone is cosmetic). This
 * sets varbit 5963 = 1 (and opens the wilderness level overlay) whenever a real player is on a
 * live PvP tile (`PvpZones.isWilderness`), clearing both on exit — on a short world timer, post
 * login (opening interfaces during the login sequence crashes the client; see the retired war HUD).
 * With 5963 set + the `SetPlayerOp("Attack")` sent on login, the client renders the Attack option;
 * the server then permits/denies the hit via `Combat.canEngage` ([PvpZones]).
 *
 * Also owns the client's crossed-swords multi icon (varbit 4605) INSIDE the wild, driven from
 * [PvpZones.isMultiCombat] so icon and AoE rules agree; outside the wild the PvE regions flagged
 * via `setMultiCombatRegion` keep driving it (`MultiwayCombatPlugin`).
 */
class WildernessOverlayPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        val timer = TimerKey()
        onWorldInit { world.timers[timer] = TICK }
        onTimer(timer) {
            world.players.forEach { p ->
                if (!p.entityType.isHumanControlled) return@forEach // skip clientless bots
                val inWild = PvpZones.isWilderness(p.tile)

                // Publish the AUTHORITATIVE wilderness level (0 when safe) to a varp the custom
                // client's skull overlay reads — so it never drifts from PvpZones like a hand-kept
                // client mirror does. >0 exactly when in the wild, so it doubles as the show/hide gate.
                val wildLevel = if (inWild) PvpZones.wildernessLevel(p.tile) else 0
                if (p.getVarp(WILD_LEVEL_VARP) != wildLevel) p.setVarp(WILD_LEVEL_VARP, wildLevel)

                val open = p.inWilderness()
                if (inWild && !open) {
                    p.openInterface(WILDERNESS_OVERLAY, InterfaceDestination.OVERLAY)
                    p.setVarbit(Varbit.IN_WILDERNESS, 1) // 5963 — the client's Attack-option gate
                } else if (!inWild && open) {
                    p.closeInterface(InterfaceDestination.OVERLAY)
                    p.setVarbit(Varbit.IN_WILDERNESS, 0)
                    // Hand the crossed-swords icon back to the PvE multi regions on the way out.
                    p.setVarbit(MULTIWAY_VARBIT, if (p.tile.isMulti(world)) 1 else 0)
                }

                // Crossed-swords icon inside the wild: single-by-default with MULTI boxes lives in
                // PvpZones, which MultiwayCombatPlugin (engine regions only) knows nothing about.
                if (inWild) {
                    val multi = if (PvpZones.isMultiCombat(p.tile, world)) 1 else 0
                    if (p.getVarbit(MULTIWAY_VARBIT) != multi) p.setVarbit(MULTIWAY_VARBIT, multi)
                }

                // Zone banner: announce on transition — the authoritative "where am I" cue. Note the
                // safe line: safe means safe from OTHER PLAYERS; Rogue Knights hunt the mainland too.
                val label = when {
                    PvpZones.isSingle(p.tile) -> "single"
                    PvpZones.isMulti(p.tile) -> "multi"
                    else -> "safe"
                }
                if (label != p.attr[ZONE_LABEL]) {
                    val prev = p.attr[ZONE_LABEL]
                    p.attr[ZONE_LABEL] = label
                    when (label) {
                        "single" -> p.message("<col=cc2222>Wilderness (lvl ${PvpZones.wildernessLevel(p.tile)}) — SINGLE combat. Items drop on death.</col>")
                        "multi" -> p.message("<col=cc2222>Wilderness (lvl ${PvpZones.wildernessLevel(p.tile)}) — MULTI combat. Items drop on death.</col>")
                        else -> if (prev != null) p.message("<col=33cc33>Safe from other players. Rogue Knights still hunt here.</col>")
                    }
                }
            }
            world.timers[timer] = TICK
        }
    }

    private companion object {
        const val WILDERNESS_OVERLAY = 90 // OSRS wilderness level widget
        const val WILD_LEVEL_VARP = 4606  // authoritative wilderness level for the client skull overlay
        const val MULTIWAY_VARBIT = 4605  // the client's crossed-swords multi-combat icon
        const val TICK = 2 // responsive enough to flip on/off as you cross the ditch
        val ZONE_LABEL = AttributeKey<String>() // last announced zone label (safe/single/multi)
    }
}
