package org.alter.plugins.content.bosses.lairs

import dev.openrune.cache.CacheManager.getItem
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.BossKills
import org.alter.plugins.content.bosses.CollectionLog
import org.alter.plugins.content.combat.Combat
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.awardTickets
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

    private fun payout(boss: LairBosses.LairBoss, dead: Npc, killer: Player) {
        killer.awardTickets(PointKind.BOSS, boss.tickets)
        val kc = BossKills.record(killer, boss.key)
        boss.drops.roll(world, mainRolls = boss.mainRolls).forEach { drop ->
            val id = runCatching { getRSCM(drop.item) }.getOrNull()
            if (id == null) {
                logger.warn { "lair-bosses: unknown drop key ${drop.item} on ${boss.key}" }
                return@forEach
            }
            world.spawn(GroundItem(id, drop.amount, dead.tile, killer))
            val name = getItem(id).name
            if (drop.announce) {
                world.players.forEach {
                    it.message("<col=ff0000>News: ${killer.username} just received <col=ffae00>$name</col> from ${boss.name}!</col>")
                }
            }
            if (drop.log && CollectionLog.record(killer, id)) {
                killer.message("<col=ffae00>New Collection Log slot: $name!</col>")
            }
        }
        val petKey = boss.pet
        if (petKey != null && world.chance(1, boss.petOneIn)) {
            val pet = runCatching { getRSCM(petKey) }.getOrNull()
            if (pet != null) {
                val add = killer.inventory.add(item = pet, amount = 1, assureFullInsertion = false)
                if (add.completed == 0) killer.bank.add(pet, 1)
                world.players.forEach {
                    it.message("<col=ff0000>News: ${killer.username} just received a <col=ffae00>${getItem(pet).name}</col> from ${boss.name}!</col>")
                }
                if (CollectionLog.record(killer, pet)) {
                    killer.message("<col=ffae00>New Collection Log slot: ${getItem(pet).name}!</col>")
                }
            }
        }
        killer.message("<col=ff0000>You have slain ${boss.name}.</col> Kill count: $kc (+${boss.tickets} Boss Tickets)")
    }

    companion object {
        /** Where a lair npc was placed — the mole's burrow anchor and the KQ respawn point. */
        val SPAWN_TILE = AttributeKey<Tile>()
    }
}
