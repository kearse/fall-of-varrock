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
import org.alter.plugins.content.bots.RogueTerritory

/**
 * Drives client wilderness state â€” the missing piece behind "no Attack option / no wilderness
 * notification" even at wild coordinates.
 *
 * The stock OSRS client gates the right-click "Attack" on other players on the **IN_WILDERNESS
 * varbit (5963)** â€” NOT coordinates, NOT the overlay interface, NOT SetPlayerOp. The base server
 * never set that varbit, so the client never offered "Attack" (the overlay alone is cosmetic). This
 * sets varbit 5963 = 1 (and opens the wilderness level overlay) whenever a real player is on a
 * live PvP tile (`PvpZones.isWilderness`), clearing both on exit â€” on a short world timer, post
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
                // client's skull overlay reads â€” so it never drifts from PvpZones like a hand-kept
                // client mirror does. >0 exactly when in the wild, so it doubles as the show/hide gate.
                val wildLevel = if (inWild) PvpZones.wildernessLevel(p.tile) else 0
                if (p.getVarp(WILD_LEVEL_VARP) != wildLevel) p.setVarp(WILD_LEVEL_VARP, wildLevel)

                // ROGUE KNIGHT TERRITORY. "Safe" here has only ever meant safe from other PLAYERS;
                // Rogue Knights hunt the whole mainland by design (2026-09-12), so a player standing
                // on ground the skull overlay leaves blank can still be run down by a knight. That
                // read as a bug -€ "some area where it shows no pk zone has rogue knights pking you"
                // (2026-09-18) -€ because the client had no way to say so. Publish the same answer
                // RogueTerritory gives the knights themselves: 0 where no unprovoked knight may
                // engage (a city core, a sanctuary, a PvP carve-out, or inside the wilderness, where
                // the skull already speaks), else the danger band. The client draws the warning.
                val rogueDanger = when {
                    inWild -> 0 // the wilderness skull already tells this story
                    RogueTerritory.inCityCore(p.tile) -> 0
                    PvpZones.isCarveout(p.tile) -> 0
                    RogueTerritory.sanctuary(p) -> 0
                    !RogueTerritory.MAINLAND.contains(p.tile) -> 0
                    else -> RogueTerritory.dangerLevel(p.tile)
                }
                if (p.getVarp(ROGUE_DANGER_VARP) != rogueDanger) p.setVarp(ROGUE_DANGER_VARP, rogueDanger)

                val open = p.inWilderness()
                if (inWild && !open) {
                    p.openInterface(WILDERNESS_OVERLAY, InterfaceDestination.OVERLAY)
                    p.setVarbit(Varbit.IN_WILDERNESS, 1) // 5963 â€” the client's Attack-option gate
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

                // Zone banner: announce on transition â€” the authoritative "where am I" cue. Note the
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
                        "single" -> p.message("<col=cc2222>Wilderness (lvl ${PvpZones.wildernessLevel(p.tile)}) â€” SINGLE combat. Items drop on death.</col>")
                        "multi" -> p.message("<col=cc2222>Wilderness (lvl ${PvpZones.wildernessLevel(p.tile)}) â€” MULTI combat. Items drop on death.</col>")
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
        // Rogue Knight danger band outside the wild (0 = no unprovoked knight may engage here).
        // 4703 is the first id clear of every allocated HUD block: 4600-4608/4613-4626/4633-4637 are
        // HUDs, 4640-4679 is the kit editor, 4681-4683 and 4686-4699 are the quest blocks
        // (see QuestJournal's allocation map).
        const val ROGUE_DANGER_VARP = 4703
        const val MULTIWAY_VARBIT = 4605  // the client's crossed-swords multi-combat icon
        const val TICK = 2 // responsive enough to flip on/off as you cross the ditch
        val ZONE_LABEL = AttributeKey<String>() // last announced zone label (safe/single/multi)
    }
}

