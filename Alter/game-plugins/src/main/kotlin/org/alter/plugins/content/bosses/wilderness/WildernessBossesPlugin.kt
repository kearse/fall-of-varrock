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
import org.alter.game.model.move.moveTo
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.BossDeath
import org.alter.plugins.content.bosses.BossKills
import org.alter.plugins.content.combat.getCombatTarget
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * Wires [WildernessBosses] into the world: lair regions force-loaded + multi-way, direct spawns
 * at world init (engine-respawned by their defs), the shared payout on death, Vet'ion's reborn
 * life cycle (form 1 dies into the reborn form at full health; the reborn pays out and form 1
 * rises again 50 ticks later), Scorpia's guardians dying with her, and Callisto's Den (the
 * surface cave entrance and the lair's exit).
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
                // Respawn countdown to everyone at the lair ("nice to have a respawn timer").
                val respawnTicks = if (boss === WildernessBosses.VETION) WildernessBosses.VETION_RESPAWN_TICKS else dead.combatDef.respawnDelay
                announceRespawn(boss.name, dead.attr[SPAWN_TILE] ?: boss.spawns.first().tile, respawnTicks)
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

        // ── Callisto's Den: surface entrance ↔ plane-1 lair (WildernessBosses.DEN_*). The
        // entrance is a plain object (no varbit children), so its cache options bind directly.
        // No fee and no diary gate here — a PK server buys the fight, not the errand.
        onObjOption(obj = WildernessBosses.DEN_ENTRANCE_KEY, option = "enter") {
            if (player.getCombatTarget() != null) {
                player.message("You can't squeeze into the den while you're fighting.")
            } else {
                player.moveTo(WildernessBosses.DEN_LANDING)
                player.message("<col=ff0000>You crawl down into Callisto's Den. The air is thick with the stink of bear.</col>")
            }
        }
        val bearId = getRSCM(WildernessBosses.CALLISTO_KEY)
        onObjOption(obj = WildernessBosses.DEN_ENTRANCE_KEY, option = "peek") {
            var bearUp = false
            var inside = 0
            world.npcs.forEach { n -> if (n.id == bearId && !n.isDead() && n.index >= 0) bearUp = true }
            world.players.forEach { p -> if (WildernessBosses.DEN_BOUNDS.contains(p.tile) && p.tile.height == WildernessBosses.DEN_LANDING.height) inside++ }
            val who = if (bearUp) "Callisto is prowling his den" else "Callisto's den lies quiet for now"
            val crowd = when (inside) { 0 -> "nobody else is down there" ; 1 -> "one adventurer is down there" ; else -> "$inside adventurers are down there" }
            player.message("You peek into the darkness: $who, and $crowd.")
        }
        onObjOption(obj = WildernessBosses.DEN_ENTRANCE_KEY, option = "check-fee") {
            player.message("The den asks no fee of you. Enter when you're ready — it is multi-way and still the Wilderness.")
        }
        bindExit()
    }

    /** The den's `Cave` exit: whatever option the def carries climbs back to the surface. */
    private fun bindExit() {
        val id = getRSCM(WildernessBosses.DEN_EXIT_KEY)
        val opts = runCatching { dev.openrune.cache.CacheManager.getObject(id).actions?.filterNotNull()?.filter { it.isNotBlank() } }.getOrNull().orEmpty()
        opts.forEach { opt ->
            onObjOption(obj = id, option = opt.lowercase()) {
                player.moveTo(WildernessBosses.DEN_SURFACE_LANDING)
                player.message("You climb back out into the Wilderness.")
            }
        }
        if (opts.isEmpty()) logger.warn { "wilderness-bosses: den exit $id has no bindable option." } else logger.info { "wilderness-bosses: bound den exit $id via $opts." }
    }

    /**
     * "<boss> respawns in N seconds" to every player near the lair, a 10-second warning, then the
     * respawn itself. The engine respawn is untouched — this only narrates it.
     */
    private fun announceRespawn(name: String, at: Tile, ticks: Int) {
        if (ticks <= 0) return
        fun tell(msg: String) {
            world.players.forEach { p ->
                if (p.tile.height == at.height && p.tile.isWithinRadius(at, RESPAWN_NOTICE_RADIUS)) p.message("<col=801700>$msg</col>")
            }
        }
        val seconds = (ticks * 6 + 5) / 10
        tell("$name will respawn in $seconds seconds.")
        world.queue {
            if (ticks > RESPAWN_WARNING_TICKS + 1) {
                wait(ticks - RESPAWN_WARNING_TICKS)
                tell("$name respawns in 10 seconds.")
                wait(RESPAWN_WARNING_TICKS + 1)
            } else {
                wait(ticks + 1)
            }
            tell("$name has respawned.")
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
        }.onFailure { logger.warn { "wilderness-bosses: failed to spawn '$key' at $tile: ${it.message}" } }
    }

    companion object {
        val SPAWN_TILE = AttributeKey<Tile>()

        /** Tiles around the lair's spawn point that hear the respawn countdown. */
        const val RESPAWN_NOTICE_RADIUS = 15
        /** ~10 seconds before the respawn. */
        const val RESPAWN_WARNING_TICKS = 17
    }
}
