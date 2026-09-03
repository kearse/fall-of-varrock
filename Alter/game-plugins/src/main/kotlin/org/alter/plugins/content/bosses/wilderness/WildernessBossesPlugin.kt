package org.alter.plugins.content.bosses.wilderness

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.BossDeath
import org.alter.plugins.content.bosses.BossKills
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * Wires [WildernessBosses] into the world: lair regions force-loaded + multi-way, direct spawns
 * at world init (engine-respawned by their defs), the shared payout on death, Vet'ion's reborn
 * life cycle (form 1 dies into the reborn form at full health; the reborn pays out and form 1
 * rises again 50 ticks later) and Scorpia's guardians dying with her.
 */
class WildernessBossesPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        WildernessBosses.all.forEach { BossKills.register(it.key, it.name) }
        WildernessBosses.REGIONS.forEach { setMultiCombatRegion(it) }

        onWorldInit {
            runCatching { world.definitions.loadRegions(world, world.chunks, WildernessBosses.REGIONS) }
                .onFailure { logger.error(it) { "wilderness-bosses: region force-load failed" } }
            WildernessBosses.all.forEach { boss -> boss.spawns.forEach { spawn(it.npcKey, it.tile, it.walkRadius, it.engineRespawn) } }
            logger.info { "wilderness-bosses: spawned ${WildernessBosses.all.size} bosses across ${WildernessBosses.REGIONS.size} regions." }
        }

        WildernessBosses.all.forEach { boss ->
            onNpcDeath(boss.lootKey) {
                val dead = npc
                val killer = dead.attr[KILLER_ATTR]?.get() as? Player
                if (killer != null) {
                    BossDeath.payout(
                        world, killer, Tile(dead.tile.x, dead.tile.z, dead.tile.height),
                        key = boss.key, name = boss.name, drops = boss.drops,
                        pet = boss.pet, petOneIn = boss.petOneIn,
                    )
                }
                if (boss === WildernessBosses.VETION) {
                    val at = dead.attr[SPAWN_TILE] ?: boss.spawns.first().tile
                    world.queue {
                        wait(WildernessBosses.VETION_RESPAWN_TICKS)
                        spawn(WildernessBosses.VETION_KEY, at, walkRadius = 10, engineRespawn = false)
                    }
                }
                if (boss === WildernessBosses.SCORPIA) {
                    dead.attr[WildernessBossesCombatPlugin.SCORPIA_GUARDIANS]?.forEach { g ->
                        if (!g.isDead() && g.index >= 0) world.remove(g)
                    }
                }
            }
        }

        // Vet'ion form 1 → reborn (donor `startDeath`: transform, restore, "Now do it again!!").
        onNpcDeath(WildernessBosses.VETION_KEY) {
            val dead = npc
            val killer = dead.attr[KILLER_ATTR]?.get() as? Player
            val at = Tile(dead.tile.x, dead.tile.z, dead.tile.height)
            val spawnTile = dead.attr[SPAWN_TILE] ?: WildernessBosses.VETION.spawns.first().tile
            world.queue {
                wait(1)
                val reborn = Npc(getRSCM(WildernessBosses.VETION_REBORN_KEY), at, world)
                reborn.respawns = false
                reborn.walkRadius = 10
                reborn.attr[SPAWN_TILE] = spawnTile
                world.spawn(reborn)
                reborn.setActive(true)
                reborn.forceChat("Now do it again!!")
                if (killer != null && !killer.isDead() && killer.tile.isWithinRadius(reborn.tile, 12)) reborn.attack(killer)
            }
        }

        // Guardians drop nothing (claiming the id also keeps the generic table off them).
        onNpcDeath(WildernessBosses.GUARDIAN_KEY) { }
    }

    private fun spawn(key: String, tile: Tile, walkRadius: Int, engineRespawn: Boolean) {
        runCatching {
            val npc = Npc(getRSCM(key), tile, world)
            npc.walkRadius = walkRadius
            npc.attr[SPAWN_TILE] = tile
            world.spawn(npc)
            npc.respawns = engineRespawn // AFTER world.spawn — setNpcDefaults would clobber it
            npc.setActive(true)
        }.onFailure { logger.warn { "wilderness-bosses: failed to spawn '$key' at $tile: ${it.message}" } }
    }

    companion object {
        val SPAWN_TILE = AttributeKey<Tile>()
    }
}
