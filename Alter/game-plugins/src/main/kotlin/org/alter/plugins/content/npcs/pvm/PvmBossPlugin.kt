package org.alter.plugins.content.npcs.pvm

import dev.openrune.cache.CacheManager.getItem
import dev.openrune.cache.CacheManager.getNpc
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
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints
import org.alter.plugins.content.economy.awardTickets
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * Spawns every [PvmBosses] boss, registers its combat def (+ Slayer requirement/xp where set),
 * and handles its death → loot + Boss points + Collection Log + rare-drop broadcast (the
 * [org.alter.plugins.content.npcs.kbd.KbdBossPlugin] shape over the roster). OSRS attack
 * rotations live in [PvmBossCombatPlugin].
 */
class PvmBossPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        for (boss in PvmBosses.all) {
            spawnNpc(boss.key, boss.lair.x, boss.lair.z, boss.lair.height, boss.walkRadius)

            setCombatDef(boss.key) {
                configs {
                    attackSpeed = boss.attackSpeed
                    respawnDelay = RESPAWN_DELAY
                }
                aggro {
                    radius = 10
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
                    attackBonus = 120
                    strengthBonus = 100
                    defenceStab = 150
                    defenceSlash = 150
                    defenceCrush = 150
                    defenceMagic = 120
                    defenceRanged = 150
                }
                anims {
                    // The combat-def DSL requires a death animation, but OSRS npc defs carry none.
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

    private fun dropLoot(npc: Npc, killer: Player, boss: PvmBosses.PvmBoss) {
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

    /**
     * The combat-def DSL requires a death animation, but OSRS npc definitions don't store one
     * (death anims are script-driven). Fall back to the npc's own idle/stand animation — it's
     * model-appropriate and guaranteed to exist — so the boss loads cleanly; a bespoke death
     * anim per boss can be added to the registry later. [FALLBACK_DEATH] covers npcs with no
     * usable stand anim.
     */
    private fun deathAnimFor(npcKey: String): Int {
        val id = runCatching { getRSCM(npcKey) }.getOrNull() ?: return FALLBACK_DEATH
        val stand = runCatching { getNpc(id).standAnim }.getOrNull() ?: -1
        return if (stand > 0) stand else FALLBACK_DEATH
    }

    private companion object {
        const val RESPAWN_DELAY = 75
        const val FALLBACK_DEATH = 836 // generic death animation
    }
}
