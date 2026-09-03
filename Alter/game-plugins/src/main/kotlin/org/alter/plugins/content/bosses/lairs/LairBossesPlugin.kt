package org.alter.plugins.content.bosses.lairs

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
import org.alter.plugins.content.combat.Combat
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * Wires the [LairBosses] registry into the world: region force-load + multi-combat flags,
 * direct spawns at world init (engine-respawned by their def's `respawnDelay`), the shared
 * death → loot / tickets / Collection Log / kill ledger / pet hook, and the Kalphite Queen's
 * two-form life cycle (form 1 dies into form 2 at full health after the donor's transform
 * animation and 11-tick immunity; form 2 pays out and form 1 rises again 50 ticks later).
 */
class LairBossesPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        LairBosses.all.forEach { BossKills.register(it.key, it.name) }
        LairBosses.MULTI_REGIONS.forEach { setMultiCombatRegion(it) }

        onWorldInit {
            runCatching { world.definitions.loadRegions(world, world.chunks, LairBosses.REGIONS) }
                .onFailure { logger.error(it) { "lair-bosses: region force-load failed" } }
            LairBosses.all.forEach { boss -> boss.spawns.forEach { spawn(it.npcKey, it.tile, it.walkRadius, it.engineRespawn) } }
            LairBosses.SPINOLYPS.forEach { spawn(LairBosses.SPINOLYP_KEY, it, walkRadius = 0, engineRespawn = true) }
            logger.info { "lair-bosses: spawned ${LairBosses.all.size} lair bosses + ${LairBosses.SPINOLYPS.size} spinolyps." }
        }

        // Shared death hook per loot-carrying form.
        LairBosses.all.forEach { boss ->
            onNpcDeath(boss.lootKey) {
                val dead = npc
                val killer = dead.attr[KILLER_ATTR]?.get() as? Player
                if (killer != null) payout(boss, dead, killer)
                if (boss === LairBosses.KALPHITE_QUEEN) scheduleKqRespawn(dead.attr[SPAWN_TILE] ?: LairBosses.KQ_SPAWN)
            }
        }

        // Kalphite Queen form 1 → form 2 (donor `startDeath`: full hp, transform anim 6270 + gfx
        // 1055, locked & immune for 11 ticks, then back on the killer).
        onNpcDeath(LairBosses.KQ_FORM_1) {
            val dead = npc
            val killer = dead.attr[KILLER_ATTR]?.get() as? Player
            val at = Tile(dead.tile.x, dead.tile.z, dead.tile.height)
            val spawnTile = dead.attr[SPAWN_TILE] ?: LairBosses.KQ_SPAWN
            world.queue {
                wait(1)
                val form2 = Npc(getRSCM(LairBosses.KQ_FORM_2), at, world)
                form2.respawns = false
                form2.walkRadius = 6
                form2.attr[SPAWN_TILE] = spawnTile
                form2.attr[Combat.DAMAGE_TAKE_MULTIPLIER] = 0.0
                world.spawn(form2)
                form2.setActive(true)
                form2.animate(6270)
                form2.graphic(1055)
                wait(11)
                if (form2.isDead() || form2.index < 0) return@queue
                form2.attr.remove(Combat.DAMAGE_TAKE_MULTIPLIER)
                if (killer != null && !killer.isDead() && killer.tile.isWithinRadius(form2.tile, 12)) form2.attack(killer)
            }
        }
    }

    private fun spawn(key: String, tile: Tile, walkRadius: Int, engineRespawn: Boolean) {
        runCatching {
            val npc = Npc(getRSCM(key), tile, world)
            npc.walkRadius = walkRadius
            npc.attr[SPAWN_TILE] = tile
            world.spawn(npc)
            npc.respawns = engineRespawn // AFTER world.spawn — setNpcDefaults would clobber it
            npc.setActive(true)
        }.onFailure { logger.warn { "lair-bosses: failed to spawn '$key' at $tile: ${it.message}" } }
    }

    private fun scheduleKqRespawn(at: Tile) {
        world.queue {
            wait(LairBosses.KQ_RESPAWN_TICKS)
            spawn(LairBosses.KQ_FORM_1, at, walkRadius = 6, engineRespawn = false)
        }
    }

    private fun payout(boss: LairBosses.LairBoss, dead: Npc, killer: Player) =
        BossDeath.payout(
            world, killer, Tile(dead.tile.x, dead.tile.z, dead.tile.height),
            key = boss.key, name = boss.name, drops = boss.drops, tickets = boss.tickets,
            pet = boss.pet, petOneIn = boss.petOneIn, mainRolls = boss.mainRolls,
        )

    companion object {
        /** Where a lair npc was placed — the mole's burrow anchor and the KQ respawn point. */
        val SPAWN_TILE = AttributeKey<Tile>()
    }
}
