package org.alter.plugins.content.pvm.varrock

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.INTERACTING_OBJ_ATTR
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.BossDeath
import org.alter.plugins.content.bosses.BossKills
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints
import org.alter.plugins.content.war.VarrockDistrict
import org.alter.plugins.content.war.WarNpcNames
import org.alter.plugins.content.war.forge.WarForge
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * Wires [VarrockPvm] into the fallen city: elite posts, salvage piles, Malachai's clock, the
 * Palace Warden, Captain Rovin's board ([ArravIntelligencePlugin] handles the talking) and every
 * kill/salvage credit the assignments listen to.
 */
class VarrockPvmPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val piles = HashMap<DynamicObject, Tile>()
    private var hollow: Npc? = null
    private var hollowUntil = 0
    private var nextHollow = 0
    private var wardenRespawnAt = 0
    private var warden: Npc? = null

    init {
        BossKills.register("palace_warden", VarrockPvm.WARDEN_NAME)
        BossKills.register("malachai", VarrockPvm.HOLLOW_NAME)
        setMultiCombatRegion(VarrockPvm.WARDEN_REGION)

        val beat = TimerKey()
        onWorldInit {
            // PluginRepository.executeWorldInit runs hooks without isolation — a throw here would
            // silently skip every plugin's world-init after ours, so fail loudly and locally.
            runCatching {
                runCatching { world.definitions.loadRegions(world, world.chunks, intArrayOf(VarrockPvm.WARDEN_REGION)) }
                    .onFailure { logger.warn(it) { "varrock-pvm: palace region force-load failed" } }
                VarrockPvm.ELITE_POSTS.forEachIndexed { i, t ->
                    val e = VarrockPvm.ELITES[i % VarrockPvm.ELITES.size]
                    spawn(e.npcKey, t, walkRadius = 3, engineRespawn = true)?.let { WarNpcNames.rename(it, e.name) }
                }
                VarrockPvm.PILE_TILES.forEachIndexed { i, t -> placePile(t, i) }
                spawnWarden()
                nextHollow = world.currentCycle + VarrockPvm.HOLLOW_EVERY_TICKS / 2
                world.timers[beat] = 50
                logger.info { "varrock-pvm: ${VarrockPvm.ELITE_POSTS.size} elite posts, ${VarrockPvm.PILE_TILES.size} salvage piles, the Palace Warden posted." }
            }.onFailure { logger.error(it) { "varrock-pvm: world init failed" } }
        }
        onTimer(beat) {
            tickHollow()
            val w = warden
            if ((w == null || w.isDead() || w.index < 0) && world.currentCycle >= wardenRespawnAt) spawnWarden()
            world.timers[beat] = 50
        }

        // ── Elites: loot + assignment credit.
        VarrockPvm.ELITES.forEach { e ->
            onNpcDeath(e.npcKey) {
                val dead = npc
                if (dead.attr[VarrockPvmCombatPlugin.WARDEN_ADD] == true) return@onNpcDeath // the Warden's risen dead drop nothing
                val killer = dead.attr[KILLER_ATTR]?.get() as? Player ?: return@onNpcDeath
                VarrockPvm.ELITE_DROPS.roll(world).forEach { d ->
                    val id = runCatching { getRSCM(d.item) }.getOrNull() ?: return@forEach
                    world.spawn(GroundItem(id, d.amount, dead.tile, killer))
                    if (d.log) recordLog(killer, id)
                }
                ArravIntelligence.onEliteKill(killer, VarrockDistrict.at(dead.tile))
            }
        }

        // ── Salvage piles.
        listOf(VarrockPvm.CRATE_A, VarrockPvm.CRATE_B).distinct().forEach { key ->
            runCatching {
                onObjOption(obj = key, option = "search") {
                    val obj = player.attr[INTERACTING_OBJ_ATTR]?.get() as? DynamicObject
                    if (obj == null || obj !in piles) {
                        player.message("You search the crate but find nothing of use.")
                        return@onObjOption
                    }
                    searchPile(player, obj)
                }
            }.onFailure { logger.warn { "varrock-pvm: $key has no 'search' option (${it.message})" } }
        }

        // ── Malachai.
        onNpcDeath(VarrockPvm.HOLLOW_KEY) {
            val dead = npc
            hollow = null
            val killer = dead.attr[KILLER_ATTR]?.get() as? Player ?: return@onNpcDeath
            BossDeath.payout(world, killer, Tile(dead.tile.x, dead.tile.z, dead.tile.height), key = "malachai", name = VarrockPvm.HOLLOW_NAME, drops = VarrockPvm.HOLLOW_DROPS, tickets = VarrockPvm.HOLLOW_TICKETS)
            killer.addPoints(PointKind.WAR_EFFORT, VarrockPvm.HOLLOW_WAR_EFFORT)
            WarForge.awardCommendations(killer, VarrockPvm.HOLLOW_COMMENDATIONS)
            ArravIntelligence.onHollowKill(killer)
            world.players.forEach { it.message("<col=801700>${VarrockPvm.HOLLOW_NAME} has been laid to rest by ${killer.username}.</col>") }
        }

        // ── The Palace Warden.
        onNpcDeath(VarrockPvm.WARDEN_KEY) {
            val dead = npc
            warden = null
            wardenRespawnAt = world.currentCycle + VarrockPvm.WARDEN_RESPAWN_TICKS
            val killer = dead.attr[KILLER_ATTR]?.get() as? Player ?: return@onNpcDeath
            BossDeath.payout(world, killer, Tile(dead.tile.x, dead.tile.z, dead.tile.height), key = "palace_warden", name = VarrockPvm.WARDEN_NAME, drops = VarrockPvm.WARDEN_DROPS, tickets = VarrockPvm.WARDEN_TICKETS)
            killer.addPoints(PointKind.WAR_EFFORT, VarrockPvm.WARDEN_WAR_EFFORT)
            WarForge.awardCommendations(killer, VarrockPvm.WARDEN_COMMENDATIONS)
            if (world.chance(1, VarrockPvm.WARDEN_EMBER_ONE_IN)) {
                WarForge.awardEmbers(killer, 1)
                killer.message("<col=ffae00>The Warden's fire gutters out — you claim a Warden's ember.</col>")
                runCatching { getRSCM(WarForge.EMBER_KEY) }.getOrNull()?.let { recordLog(killer, it) }
            }
            ArravIntelligence.onWardenKill(killer)
            world.players.forEach { it.message("<col=801700>${VarrockPvm.WARDEN_NAME} has fallen to ${killer.username}. The palace stirs again in fifteen minutes.</col>") }
        }
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private fun recordLog(p: Player, id: Int) {
        if (org.alter.plugins.content.bosses.CollectionLog.record(p, id)) {
            p.message("<col=ffae00>New Collection Log slot: ${dev.openrune.cache.CacheManager.getItem(id).name}!</col>")
        }
    }

    private fun spawn(key: String, tile: Tile, walkRadius: Int, engineRespawn: Boolean): Npc? =
        runCatching {
            val npc = Npc(getRSCM(key), world.snapToWalkable(tile, maxRadius = 3), world)
            npc.walkRadius = walkRadius
            world.spawn(npc)
            npc.respawns = engineRespawn
            npc.setActive(true)
            npc
        }.onFailure { logger.warn { "varrock-pvm: failed to spawn '$key' at $tile: ${it.message}" } }.getOrNull()

    private fun spawnWarden() {
        warden = spawn(VarrockPvm.WARDEN_KEY, VarrockPvm.WARDEN_SPAWN, walkRadius = 4, engineRespawn = false)?.also {
            WarNpcNames.rename(it, VarrockPvm.WARDEN_NAME)
        }
    }

    private fun placePile(tile: Tile, i: Int) {
        val key = if (i % 2 == 0) VarrockPvm.CRATE_A else VarrockPvm.CRATE_B
        val id = runCatching { getRSCM(key) }.getOrNull() ?: return
        val at = world.snapToWalkable(tile, maxRadius = 2)
        val obj = DynamicObject(id, 10, world.random(3), at)
        world.spawn(obj)
        piles[obj] = at
    }

    private fun searchPile(p: Player, obj: DynamicObject) {
        val at = piles.remove(obj) ?: return
        world.remove(obj)
        p.animate(832)
        val n = VarrockPvm.SALVAGE_MIN + world.random(VarrockPvm.SALVAGE_MAX - VarrockPvm.SALVAGE_MIN)
        give(p, VarrockPvm.SALVAGE_KEY, n)
        var msg = "You pull $n pieces of salvage from the wreckage."
        if (world.chance(1, VarrockPvm.RELIC_ONE_IN)) {
            give(p, VarrockPvm.RELIC_KEY, 1)
            runCatching { getRSCM(VarrockPvm.RELIC_KEY) }.getOrNull()?.let { recordLog(p, it) }
            msg += " <col=ffae00>Something older glints beneath it — a relic of old Varrock!</col>"
        }
        p.message(msg)
        ArravIntelligence.onSalvage(p, n)
        // The ruins are never quiet for long.
        if (world.chance(1, VarrockPvm.AMBUSH_ONE_IN)) {
            val e = VarrockPvm.ELITES[world.random(VarrockPvm.ELITES.size - 1)]
            spawn(e.npcKey, Tile(at.x + 1, at.z, at.height), walkRadius = 3, engineRespawn = false)?.let { n2 ->
                WarNpcNames.rename(n2, e.name)
                n2.attack(p)
                p.message("<col=ff0000>The noise wakes something in the rubble — a ${e.name} claws its way out!</col>")
            }
        }
        // The pile refills later.
        val idx = VarrockPvm.PILE_TILES.indexOf(at).takeIf { it >= 0 } ?: 0
        world.queue {
            wait(VarrockPvm.PILE_RESPAWN_TICKS)
            placePile(at, idx)
        }
    }

    private fun tickHollow() {
        val h = hollow
        val now = world.currentCycle
        if (h != null) {
            if (h.isDead() || h.index < 0) { hollow = null; return }
            if (now >= hollowUntil) {
                world.remove(h)
                hollow = null
                nextHollow = now + VarrockPvm.HOLLOW_EVERY_TICKS
                world.players.forEach { it.message("<col=801700>${VarrockPvm.HOLLOW_NAME} slips back into the ruins.</col>") }
            }
            return
        }
        if (now < nextHollow) return
        val d = VarrockDistrict.all[world.random(VarrockDistrict.all.size - 1)]
        val n = spawn(VarrockPvm.HOLLOW_KEY, d.center, walkRadius = 6, engineRespawn = false) ?: run { nextHollow = now + 200; return }
        WarNpcNames.rename(n, VarrockPvm.HOLLOW_NAME)
        hollow = n
        hollowUntil = now + VarrockPvm.HOLLOW_LINGER_TICKS
        nextHollow = now + VarrockPvm.HOLLOW_EVERY_TICKS
        world.players.forEach { it.message("<col=ff0000>${VarrockPvm.HOLLOW_NAME} has been sighted in ${d.display} of Fallen Varrock!</col>") }
    }

    private fun give(p: Player, key: String, amount: Int) {
        val id = runCatching { getRSCM(key) }.getOrNull() ?: return
        val added = p.inventory.add(item = id, amount = amount, assureFullInsertion = false)
        val leftover = amount - added.completed
        if (leftover > 0) world.spawn(GroundItem(id, leftover, p.tile, p))
    }
}
