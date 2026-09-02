package org.alter.plugins.content.combat

import org.alter.api.InterfaceDestination
import org.alter.api.cfg.Varbit
import org.alter.api.ext.closeInterface
import org.alter.api.ext.inWilderness
import org.alter.api.ext.openInterface
import org.alter.api.ext.message
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
 * sets varbit 5963 = 1 (and opens the wilderness level overlay) whenever a real player is at
 * wilderness coordinates (`Tile.getWildernessLevel() > 0`), clearing both on exit — on a short world
 * timer, post login (opening interfaces during the login sequence crashes the client; see
 * the retired war HUD). With 5963 set + the `SetPlayerOp("Attack")` sent on login, the client renders the
 * Attack option; the server then permits/denies the hit via `Combat.canEngage` (`SafeZones`).
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
                }

                // Zone banner: announce on transition (the vanilla overlay's level is wrong in our
                // custom red, so this is the authoritative "where am I" cue).
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
                        else -> if (prev != null) p.message("<col=33cc33>You are now in a safe area.</col>")
                    }
                }
            }
            world.timers[timer] = TICK
        }
    }

    private companion object {
        const val WILDERNESS_OVERLAY = 90 // OSRS wilderness level widget
        const val WILD_LEVEL_VARP = 4606  // authoritative wilderness level for the client skull overlay
        const val TICK = 2 // responsive enough to flip on/off as you cross the ditch
        val ZONE_LABEL = AttributeKey<String>() // last announced zone label (safe/single/multi)
    }
}
