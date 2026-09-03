package org.alter.plugins.content.bosses.slayer

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.BossDeath
import org.alter.plugins.content.bosses.BossKills
import org.alter.plugins.content.companion.CompanionPolicy
import org.alter.plugins.content.raids.RaidInstance
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * Wires [SlayerBosses] into the world — spawns, life cycles and payouts:
 *
 *  - **Kraken**: the boss and its four tentacles rest as whirlpools. **Disturb** a tentacle
 *    whirlpool to surface it; the Kraken's whirlpool only surfaces once every tentacle is up
 *    (donor: hits on the whirlpool are blocked while tentacle whirlpools remain). Death pays
 *    out, clears the tentacles, and the whirlpools return 30 ticks later.
 *  - **Cerberus**: three lairs, engine-respawned.
 *  - **Thermonuclear smoke devil**: engine-respawned.
 *  - **Skotizo**: a **dark totem** at the catacombs altar (or `::skotizo`) opens a private
 *    instance of his chamber with four altars that awaken on their own clocks; kill an
 *    awakened altar to put it back to sleep. Death pays out and the chamber releases you.
 *  - **Demonic gorillas**: a dozen in the Crash Site Cavern; prayer switches are form swaps
 *    that carry hitpoints and fight state; engine-free respawn after 50 ticks.
 */
class SlayerBossesPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        SlayerBosses.all.forEach { BossKills.register(it.key, it.name.removePrefix("the ").removePrefix("a ").replaceFirstChar { c -> c.uppercase() }) }
        SlayerBosses.MULTI_REGIONS.forEach { setMultiCombatRegion(it) }
        CompanionPolicy.denyInstanceOf(SlayerBosses.SKOTIZO_SOURCE, "Skotizo's chamber is a solo fight")

        onWorldInit {
            runCatching { world.definitions.loadRegions(world, world.chunks, SlayerBosses.REGIONS) }
                .onFailure { logger.error(it) { "slayer-bosses: region force-load failed" } }
            spawnKrakenPools()
            SlayerBosses.CERBERUS_SPAWNS.forEach { spawn(SlayerBosses.CERBERUS, it, walkRadius = 3, engineRespawn = true) }
            spawn(SlayerBosses.THERMY, SlayerBosses.THERMY_SPAWN, walkRadius = 3, engineRespawn = true)
            SlayerBosses.GORILLA_SPAWNS.forEach { spawnGorilla(it) }
            logger.info { "slayer-bosses: spawned Kraken pools, ${SlayerBosses.CERBERUS_SPAWNS.size} Cerberus, Thermy, ${SlayerBosses.GORILLA_SPAWNS.size} gorillas." }
        }

        // ── Kraken: disturb → surface.
        onNpcOption(npc = SlayerBosses.TENTACLE_WHIRLPOOL, option = "disturb") {
            val pool = npc
            val surfaced = swapForm(pool, SlayerBosses.TENTACLE, keepHp = true)
            surfaced.animate(3860)
            surfaced.attack(player)
        }
        onNpcOption(npc = SlayerBosses.KRAKEN_WHIRLPOOL, option = "disturb") {
            val pool = npc
            if (player.getSkills().getBaseLevel(Skills.SLAYER) < 87) {
                player.message("You need a Slayer level of 87 to disturb the Kraken.")
                return@onNpcOption
            }
            if (tentaclePoolsNear(pool.tile) > 0) {
                player.message("The Kraken is protected by its tentacles — disturb them first.")
                return@onNpcOption
            }
            val kraken = swapForm(pool, SlayerBosses.KRAKEN, keepHp = true)
            kraken.animate(7135)
            kraken.attack(player)
        }
        onNpcDeath(SlayerBosses.KRAKEN) {
            val dead = npc
            val killer = dead.attr[KILLER_ATTR]?.get() as? Player
            if (killer != null) pay(SlayerBosses.KRAKEN_BOSS, dead, killer)
            // The tentacles sink with it; the pools return together.
            world.npcs.forEach { n ->
                if ((n.id == getRSCM(SlayerBosses.TENTACLE) || n.id == getRSCM(SlayerBosses.TENTACLE_WHIRLPOOL)) &&
                    n.tile.isWithinRadius(SlayerBosses.KRAKEN_SPAWN, 12) && !n.isDead() && n.index >= 0
                ) world.remove(n)
            }
            world.queue {
                wait(SlayerBosses.KRAKEN_RESPAWN_TICKS)
                spawnKrakenPools()
            }
        }
        onNpcDeath(SlayerBosses.TENTACLE) { } // no drops; they come back with the Kraken

        // ── Cerberus / Thermy: plain shared-world payouts.
        onNpcDeath(SlayerBosses.CERBERUS) {
            val killer = npc.attr[KILLER_ATTR]?.get() as? Player ?: return@onNpcDeath
            pay(SlayerBosses.CERBERUS_BOSS, npc, killer)
        }
        onNpcDeath(SlayerBosses.THERMY) {
            val killer = npc.attr[KILLER_ATTR]?.get() as? Player ?: return@onNpcDeath
            pay(SlayerBosses.THERMY_BOSS, npc, killer)
        }

        // ── Skotizo: totem on the catacombs altar, or the command.
        runCatching {
            onObjOption(obj = SlayerBosses.SKOTIZO_ALTAR_OBJ, option = "teleport") { enterSkotizo(player) }
        }.onFailure { logger.warn { "slayer-bosses: catacombs altar has no 'teleport' option (${it.message}); ::skotizo only" } }
        onCommand("skotizo", description = "Use a dark totem to face Skotizo") { enterSkotizo(player) }
        onNpcDeath(SlayerBosses.SKOTIZO) {
            val dead = npc
            val killer = dead.attr[KILLER_ATTR]?.get() as? Player
            if (killer != null) {
                pay(SlayerBosses.SKOTIZO_BOSS, dead, killer)
                killer.message("<col=801700>The chamber falls quiet. The altar's pull fades — you are returned to the catacombs.</col>")
                world.queue {
                    wait(8)
                    if (world.instanceAllocator.getMap(killer.tile) != null) killer.moveTo(SlayerBosses.SKOTIZO_EXIT)
                }
            }
        }
        SlayerBosses.ALTARS.forEach { a ->
            onNpcDeath(a.awakenedKey) {
                // Put the altar back to sleep where it stood; the boss loses its buff.
                val dead = npc
                val boss = dead.attr[ALTAR_OWNER]
                if (boss != null && !boss.isDead() && boss.index >= 0) {
                    boss.attr[SKOTIZO_AWAKE] = ((boss.attr[SKOTIZO_AWAKE] ?: 1) - 1).coerceAtLeast(0)
                    spawnAltar(a, Tile(dead.tile.x, dead.tile.z, dead.tile.height), boss)
                }
            }
        }

        // ── Gorillas: any form's death pays and respawns the melee form at its post.
        SlayerBosses.GORILLA_FORMS.forEach { key ->
            onNpcDeath(key) {
                val dead = npc
                val killer = dead.attr[KILLER_ATTR]?.get() as? Player
                if (killer != null) pay(SlayerBosses.GORILLA_BOSS, dead, killer)
                val post = dead.attr[SPAWN_TILE] ?: return@onNpcDeath
                world.queue {
                    wait(SlayerBosses.GORILLA_RESPAWN_TICKS)
                    spawnGorilla(post)
                }
            }
        }
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private fun pay(boss: SlayerBosses.SlayerBoss, dead: Npc, killer: Player) =
        BossDeath.payout(
            world, killer, Tile(dead.tile.x, dead.tile.z, dead.tile.height),
            key = boss.key, name = boss.name, drops = boss.drops, tickets = boss.tickets,
            pet = boss.pet, petOneIn = boss.petOneIn,
        )

    private fun spawn(key: String, tile: Tile, walkRadius: Int, engineRespawn: Boolean): Npc? =
        runCatching {
            val npc = Npc(getRSCM(key), tile, world)
            npc.walkRadius = walkRadius
            npc.attr[SPAWN_TILE] = tile
            world.spawn(npc)
            npc.respawns = engineRespawn // AFTER world.spawn — setNpcDefaults would clobber it
            npc.setActive(true)
            npc
        }.onFailure { logger.warn { "slayer-bosses: failed to spawn '$key' at $tile: ${it.message}" } }.getOrNull()

    /** Replace [old] with form [newKey] on its tile (optionally carrying current hp); returns the new npc. */
    private fun swapForm(old: Npc, newKey: String, keepHp: Boolean): Npc {
        val at = Tile(old.tile.x, old.tile.z, old.tile.height)
        val hp = old.getCurrentHp()
        val post = old.attr[SPAWN_TILE]
        world.remove(old)
        val n = Npc(getRSCM(newKey), at, world)
        n.respawns = false
        n.walkRadius = 0
        if (post != null) n.attr[SPAWN_TILE] = post
        world.spawn(n)
        if (keepHp) n.setCurrentHp(minOf(n.getMaxHp(), hp.coerceAtLeast(1)))
        n.setActive(true)
        return n
    }

    private fun spawnKrakenPools() {
        spawn(SlayerBosses.KRAKEN_WHIRLPOOL, SlayerBosses.KRAKEN_SPAWN, walkRadius = 0, engineRespawn = false)
        SlayerBosses.TENTACLE_OFFSETS.forEach { (dx, dz) ->
            spawn(SlayerBosses.TENTACLE_WHIRLPOOL, Tile(SlayerBosses.KRAKEN_SPAWN.x + dx, SlayerBosses.KRAKEN_SPAWN.z + dz, 0), walkRadius = 0, engineRespawn = false)
        }
    }

    private fun tentaclePoolsNear(tile: Tile): Int {
        val poolId = getRSCM(SlayerBosses.TENTACLE_WHIRLPOOL)
        var n = 0
        world.npcs.forEach { if (it.id == poolId && it.index >= 0 && !it.isDead() && it.tile.isWithinRadius(tile, 12)) n++ }
        return n
    }

    private fun spawnGorilla(post: Tile) {
        spawn(SlayerBosses.GORILLA_MELEE, post, walkRadius = 3, engineRespawn = false)
    }

    // ───────────────────────────── Skotizo ─────────────────────────────

    private fun enterSkotizo(p: Player) {
        if (world.instanceAllocator.getMap(p.tile) != null) {
            p.message("You're already inside an instance — leave it first.")
            return
        }
        val totem = getRSCM(SlayerBosses.DARK_TOTEM)
        if (!p.inventory.contains(totem)) {
            p.message("You need a <col=801700>dark totem</col> to awaken Skotizo.")
            return
        }
        val instance = RaidInstance.allocate(world = world, sourceArea = SlayerBosses.SKOTIZO_SOURCE, exitTile = SlayerBosses.SKOTIZO_EXIT, owner = p.uid)
        if (instance == null) {
            p.message("The altar's power is exhausted — try again in a moment.")
            return
        }
        p.inventory.remove(totem, 1)
        p.animate(3865)
        p.graphic(1296)
        val boss = Npc(getRSCM(SlayerBosses.SKOTIZO), instance.translate(SlayerBosses.SKOTIZO_SPAWN_SRC), world)
        boss.respawns = false
        boss.walkRadius = 8
        boss.attr[SKOTIZO_AWAKE] = 0
        world.spawn(boss)
        boss.setActive(true)
        boss.animate(4623)
        SlayerBosses.ALTARS.forEach { a -> spawnAltar(a, instance.translate(a.src), boss) }
        p.moveTo(instance.translate(SlayerBosses.SKOTIZO_PLAYER_SRC))
        p.message("<col=ff0000>The altar drags you down into Skotizo's chamber. Kill the awakened altars to weaken him!</col>")
        world.queue {
            wait(2)
            if (!boss.isDead() && boss.index >= 0 && !p.isDead()) boss.attack(p)
        }
    }

    /** A dormant altar that wakes on its own clock (donor: every 30-90 ticks while the boss lives). */
    private fun spawnAltar(a: SlayerBosses.Altar, at: Tile, boss: Npc) {
        val dormant = Npc(getRSCM(a.dormantKey), at, world)
        dormant.respawns = false
        dormant.attr[ALTAR_OWNER] = boss
        world.spawn(dormant)
        dormant.setActive(true)
        world.queue {
            wait(30 + world.random(60))
            if (boss.isDead() || boss.index < 0) { if (dormant.index >= 0) world.remove(dormant); return@queue }
            if (dormant.index < 0) return@queue
            world.remove(dormant)
            val awake = Npc(getRSCM(a.awakenedKey), at, world)
            awake.respawns = false
            awake.attr[ALTAR_OWNER] = boss
            world.spawn(awake)
            awake.setActive(true)
            awake.animate(1472)
            boss.attr[SKOTIZO_AWAKE] = (boss.attr[SKOTIZO_AWAKE] ?: 0) + 1
            // Sweep the altar away with the boss so instances don't leak npcs.
            while (!boss.isDead() && boss.index >= 0 && awake.index >= 0 && !awake.isDead()) wait(5)
            if (awake.index >= 0 && !awake.isDead() && (boss.isDead() || boss.index < 0)) world.remove(awake)
        }
    }

    companion object {
        val SPAWN_TILE = AttributeKey<Tile>()
        val SKOTIZO_AWAKE = AttributeKey<Int>()
        val ALTAR_OWNER = AttributeKey<Npc>()

        /** Gorilla prayer swap: a new form on the same tile carrying hp, post and fight state, back on its target. */
        fun swapGorilla(world: World, old: Npc, newKey: String, state: SlayerBossesCombatPlugin.GorillaState, target: Pawn) {
            val at = Tile(old.tile.x, old.tile.z, old.tile.height)
            val hp = old.getCurrentHp()
            val post = old.attr[SPAWN_TILE]
            world.remove(old)
            val n = Npc(getRSCM(newKey), at, world)
            n.respawns = false
            n.walkRadius = 3
            if (post != null) n.attr[SPAWN_TILE] = post
            state.lastHp = hp
            n.attr[SlayerBossesCombatPlugin.GORILLA_STATE] = state
            world.spawn(n)
            n.setCurrentHp(minOf(n.getMaxHp(), hp.coerceAtLeast(1)))
            n.setActive(true)
            n.attack(target)
        }
    }
}
