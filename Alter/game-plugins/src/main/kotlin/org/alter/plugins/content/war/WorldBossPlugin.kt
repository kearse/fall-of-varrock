package org.alter.plugins.content.war

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.npc
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.CITY_ID_ATTR
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.npcs.corp.CorpBeastPlugin
import org.alter.plugins.content.teleport.TeleportService
import org.alter.plugins.content.war.boss.BossRegistry
import org.alter.plugins.content.war.boss.BossScheduler
import org.alter.plugins.content.war.boss.BossSummon

/**
 * Host plugin for **city bosses** (the player-offense content loop). A boss spawns in a
 * city — passively on a rotation, or summoned on demand by a Lord+ — and players travel
 * there to kill it for loot split by damage contribution.
 *
 * This plugin only wires the engine seams; the logic lives in the `boss` package:
 *  - a world timer drives [BossScheduler] (rotation + unchallenged-withdrawal upkeep),
 *  - a death handler per boss npc funnels kills into [BossScheduler.onBossDeath] →
 *    [org.alter.plugins.content.war.boss.BossLoot] (untracked deaths are ignored).
 *
 * The on-demand summon command lives in the campaign-command plugin alongside the troop
 * dispatch it triggers.
 */
class WorldBossPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        val bossTimer = TimerKey()
        onWorldInit { world.timers[bossTimer] = TICK }
        onTimer(bossTimer) {
            BossScheduler.tick(world)
            world.timers[bossTimer] = TICK
        }

        // One death funnel per distinct boss npc. onBossDeath no-ops for any death that
        // isn't a tracked boss, so ordinary world spawns of these npcs are unaffected.
        // The Corporeal Beast is EXCLUDED: it also has an always-on cave lair, and only one death
        // handler may bind per npc id, so CorpBeastPlugin owns its single handler and dispatches
        // tracked (event) kills back to BossScheduler.onBossDeath itself.
        BossRegistry.npcNames().filter { it != CorpBeastPlugin.NPC }.forEach { name ->
            onNpcDeath(name) { BossScheduler.onBossDeath(world, npc) }
        }

        // ::summonboss <key> — Lord+ pays to summon a boss into their city (+ a backing raid).
        onCommand("summonboss", description = "Summon a boss to your city (Lord+, costs coins)") {
            val key = player.getCommandArgs().getOrNull(0)
            val def = key?.let { BossRegistry.byKey(it) }
            if (def == null) {
                player.message("Usage: ::summonboss <${BossRegistry.all.joinToString("|") { it.key }}>")
                return@onCommand
            }
            val cityId = player.attr[CITY_ID_ATTR] ?: org.alter.plugins.content.war.Cities.DEFAULT_CITY_ID
            BossSummon.trySummon(world, player, def, cityId)
        }

        // ::worldboss — teleport to the city world-boss arena (routes through TeleportService so
        // tele-block, combat lock and the teleport animation behave like a portal teleport).
        // NB: ::boss / ::boss <name> is owned by BossTeleportCommandsPlugin (per-boss lairs).
        onCommand("worldboss", description = "Teleport to the city world boss arena") {
            val cityId = player.attr[CITY_ID_ATTR] ?: org.alter.plugins.content.war.Cities.DEFAULT_CITY_ID
            val live = BossScheduler.bossesIn(cityId).isNotEmpty()
            if (!TeleportService.teleport(player, "corp_beast")) return@onCommand // canTeleport already messaged
            if (live) {
                player.message("<col=ffae00>The beast stalks these grounds. To arms!</col>")
            } else {
                player.message("<col=ffae00>No boss is active here yet — hold this ground until one appears.</col>")
            }
        }
    }

    private companion object {
        const val TICK = 5 // ~3s upkeep cadence
    }
}
