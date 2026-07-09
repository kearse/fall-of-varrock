package org.alter.plugins.content.npcs.elite

import dev.openrune.cache.CacheManager.getItem
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.dsl.setCombatDef
import org.alter.api.ext.message
import org.alter.api.ext.npc
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.CollectionLog
import org.alter.plugins.content.bosses.deathAnimFor
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints
import org.alter.plugins.content.economy.awardTickets
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * Spawns every [EliteBosses] boss, registers its combat def (+ Slayer requirement and the
 * required death anim via [deathAnimFor]), and handles death → loot + Boss points + Collection
 * Log + broadcast. Phased OSRS rotations live in [EliteBossCombatPlugin].
 */
class EliteBossPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        for (boss in EliteBosses.all) {
            spawnNpc(boss.key, boss.lair.x, boss.lair.z, boss.lair.height, boss.walkRadius)

            setCombatDef(boss.key) {
                configs {
                    attackSpeed = boss.attackSpeed
                    respawnDelay = RESPAWN_DELAY
                }
                aggro {
                    radius = 12
                    searchDelay = 1
                }
                stats {
                    hitpoints = boss.hp
                    attack = boss.att
                    strength = boss.str
                    defence = boss.def
                    magic = boss.mag
                    ranged = boss.rng
                }
                bonuses {
                    attackBonus = 150
                    strengthBonus = 130
                    defenceStab = 180
                    defenceSlash = 180
                    defenceCrush = 180
                    defenceMagic = 150
                    defenceRanged = 180
                }
                anims {
                    death = deathAnimFor(boss.key)
                }
                if (boss.slayerReq > 0) {
                    slayerData {
                        levelRequirement = boss.slayerReq
                        xp = boss.slayerXp
                    }
                }
            }

            onNpcDeath(boss.key) {
                val killer = npc.attr[KILLER_ATTR]?.get() as? Player ?: return@onNpcDeath
                dropLoot(npc, killer, boss)
            }
        }
    }

    private fun dropLoot(npc: Npc, killer: Player, boss: EliteBosses.EliteBoss) {
        killer.awardTickets(PointKind.BOSS, boss.bossPoints)
        boss.loot.roll(world).forEach { drop ->
            val id = runCatching { getRSCM(drop.item) }.getOrNull() ?: return@forEach
            world.spawn(GroundItem(id, drop.amount, npc.tile, killer))
            val name = runCatching { getItem(id).name }.getOrNull() ?: return@forEach
            if (drop.announce) {
                world.players.forEach {
                    it.message("<col=ff0000>News: ${killer.username} just received <col=ffae00>$name</col> from ${boss.name}!</col>")
                }
            }
            if (drop.log && CollectionLog.record(killer, id)) {
                killer.message("<col=ffae00>New Collection Log slot: $name!</col>")
            }
        }
        killer.message("<col=ff0000>You have slain ${boss.name}.</col> (+${boss.bossPoints} Boss Tickets)")
    }

    private companion object {
        const val RESPAWN_DELAY = 100
    }
}
