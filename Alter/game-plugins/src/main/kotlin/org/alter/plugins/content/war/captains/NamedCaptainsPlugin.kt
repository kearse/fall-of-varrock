package org.alter.plugins.content.war.captains

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.announce.Announce
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints
import org.alter.plugins.content.war.District
import org.alter.plugins.content.war.WarNpcNames
import org.alter.plugins.content.war.forge.WarForge
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Named captains** (story-and-grind-design §4/§6) — the bounty board's marquee targets.
 * One elite, uniquely-named cutthroat per district on a long respawn. Since the demons took
 * Varrock, the captains fled the city and now hole up in overrun Falador (their district labels
 * carry over for the bounty board / pressure meter). Killing one pays a fat bounty (coins +
 * Commendations + War Effort) and rolls their **signature weapon** — the spec-weapon chase the
 * forge's armour lines don't cover.
 *
 * `::bounties` shows the board. Captains use their base model's combat def with boosted
 * stats, so animations stay right; their base ids are rogue-family, so a captain kill also
 * ticks the Sergeant's rogue-hunting tally. All numbers TUNE.
 */
class NamedCaptainsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private data class CaptainDef(
        val name: String,
        val district: District,
        val npcKey: String,
        val tile: Tile,
        val signatureKey: String,
        val signatureDisplay: String,
    )

    /** One captain per district — driven out of demon-held Varrock, they now hole up in overrun
     *  Falador (see WorldSpawnsPlugin.applyFallenFalador). Tiles TUNE. */
    private val DEFS = listOf(
        CaptainDef("Karn the Red", District.SLUMS, "npc.rogue_526", Tile(3013, 3357, 0), "item.dragon_claws", "Dragon claws"),
        CaptainDef("Silas the Hollow", District.OLD_MARKET, "npc.dark_wizard", Tile(2957, 3371, 0), "item.nightmare_staff", "Nightmare staff"),
        CaptainDef("Vex of the Row", District.EAST_QUARTER, "npc.highwayman", Tile(3045, 3336, 0), "item.armadyl_crossbow", "Armadyl crossbow"),
        CaptainDef("Grimjaw", District.MUSEUM_QUARTER, "npc.bandit_leader", Tile(2977, 3341, 0), "item.dragon_warhammer", "Dragon warhammer"),
    )

    /** Live captain NPC per def index; absent = slain and awaiting respawn. */
    private val alive = HashMap<Int, Npc>()
    /** World cycle each slain captain returns at. */
    private val respawnAt = HashMap<Int, Int>()

    init {
        val timer = TimerKey()
        onWorldInit {
            DEFS.forEachIndexed { i, def -> spawnCaptain(i, def) }
            world.timers[timer] = RESPAWN_POLL
        }
        onTimer(timer) {
            DEFS.forEachIndexed { i, def ->
                if (!alive.containsKey(i) && world.currentCycle >= (respawnAt[i] ?: 0)) spawnCaptain(i, def)
            }
            world.timers[timer] = RESPAWN_POLL
        }

        // Bounty payout: additive death hook, bails instantly for non-captain kills.
        onAnyNpcDeath {
            val idx = alive.entries.firstOrNull { it.value === npc }?.key ?: return@onAnyNpcDeath
            val def = DEFS[idx]
            alive.remove(idx)
            respawnAt[idx] = world.currentCycle + RESPAWN_TICKS
            val killer = npc.attr[KILLER_ATTR]?.get() as? Player
            if (killer != null && killer.index >= 0) {
                giveCoins(killer, BOUNTY_COINS)
                WarForge.awardCommendations(killer, BOUNTY_COMMENDATIONS)
                killer.addPoints(PointKind.WAR_EFFORT, BOUNTY_WAR_EFFORT)
                killer.message("<col=4f9b4f>Bounty claimed:</col> ${def.name} of ${def.district.display} — ${"%,d".format(BOUNTY_COINS)} coins, banked.")
                // (The Rogue Problem's old "captain" beat now lives on the Rogue Knight ladder —
                // captains are standalone bounty content again.)
                if (world.random(SIGNATURE_RATE - 1) == 0) {
                    val id = getRSCM(def.signatureKey)
                    world.spawn(GroundItem(id, 1, npc.tile, killer))
                    Announce.broadcast(world, "<col=ffcc00>${killer.username} has claimed ${def.name}'s ${def.signatureDisplay} in ${def.district.display}!</col>")
                } else {
                    Announce.broadcast(world, "<col=4f9b4f>${def.name}, captain of ${def.district.display}, has been slain by ${killer.username}. The rats will crown a successor within the hour.</col>")
                }
            } else {
                Announce.broadcast(world, "<col=4f9b4f>${def.name}, captain of ${def.district.display}, has fallen in the street fighting.</col>")
            }
        }

        // ::bounties — the board: who's at large, who's down and for how long.
        onCommand("bounties", description = "Show the wanted captains holed up in Fallen Falador") {
            player.message("<col=801700>Wanted — the captains holed up in Fallen Falador:</col>")
            DEFS.forEachIndexed { i, def ->
                if (alive.containsKey(i)) {
                    player.message("  ${def.name} — ${def.district.display}: <col=801700>AT LARGE</col> (carries the ${def.signatureDisplay})")
                } else {
                    val mins = ((respawnAt[i] ?: 0) - world.currentCycle).coerceAtLeast(0) * 6 / 600
                    player.message("  ${def.name} — ${def.district.display}: slain, a successor rises in ~${mins}m")
                }
            }
            player.message("Bounty per head: <col=ffae00>${"%,d".format(BOUNTY_COINS)} coins + $BOUNTY_COMMENDATIONS Commendations + $BOUNTY_WAR_EFFORT War Effort</col>. Their streets are PvP ground.")
        }
    }

    private fun spawnCaptain(idx: Int, def: CaptainDef) {
        runCatching {
            val npc = Npc(getRSCM(def.npcKey), def.tile, world)
            npc.walkRadius = 3
            world.spawn(npc)
            WarNpcNames.rename(npc, def.name)
            // Boost on top of the base model's combat def so animations/sounds stay right.
            npc.combatDef = npc.combatDef.copy(
                attack = CAPTAIN_ATTACK, strength = CAPTAIN_STRENGTH,
                defence = CAPTAIN_DEFENCE, hitpoints = CAPTAIN_HP,
            )
            npc.stats.setMaxLevel(org.alter.api.NpcSkills.ATTACK, CAPTAIN_ATTACK)
            npc.stats.setCurrentLevel(org.alter.api.NpcSkills.ATTACK, CAPTAIN_ATTACK)
            npc.stats.setMaxLevel(org.alter.api.NpcSkills.STRENGTH, CAPTAIN_STRENGTH)
            npc.stats.setCurrentLevel(org.alter.api.NpcSkills.STRENGTH, CAPTAIN_STRENGTH)
            npc.stats.setMaxLevel(org.alter.api.NpcSkills.DEFENCE, CAPTAIN_DEFENCE)
            npc.stats.setCurrentLevel(org.alter.api.NpcSkills.DEFENCE, CAPTAIN_DEFENCE)
            npc.setCurrentHp(CAPTAIN_HP)
            npc.respawns = false // we run the long respawn ourselves
            npc.setActive(true)
            alive[idx] = npc
        }.onFailure { logger.error(it) { "[CAPTAINS] failed to spawn ${def.name} (${def.npcKey})" } }
    }

    /** Bounty coins straight to the bank (their streets are PvP — don't hand out a pking bonus). */
    private fun giveCoins(p: Player, amount: Int) {
        runCatching {
            val id = getRSCM("item.coins_995")
            if (p.bank.add(id, amount).completed < amount) {
                p.world.spawn(GroundItem(id, amount, p.tile, p))
            }
        }
    }

    private companion object {
        const val RESPAWN_TICKS = 6000 // ~60 min at 0.6s. TUNE.
        const val RESPAWN_POLL = 100 // check for due respawns every ~1 min
        const val BOUNTY_COINS = 100_000
        const val BOUNTY_COMMENDATIONS = 2
        const val BOUNTY_WAR_EFFORT = 25
        // 1-in-N signature-weapon roll per kill. At the ~60-min respawn this caps each
        // signature at ~1.6/day realm-wide (was 1/8 ≈ 3/day — flooding); expected ~15
        // contested hourly kills per drop keeps the PK-shop price (claws 12k BM) the
        // deliberately slower pity path (shop-economy-redesign R3/§5.5). TUNE.
        const val SIGNATURE_RATE = 15
        const val CAPTAIN_ATTACK = 150
        const val CAPTAIN_STRENGTH = 140
        const val CAPTAIN_DEFENCE = 120
        const val CAPTAIN_HP = 400
    }
}
