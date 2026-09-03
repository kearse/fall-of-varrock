package org.alter.plugins.content.minigames.barrows

import dev.openrune.cache.CacheManager.getObject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.Plugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.BossKills
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * Wires the Barrows run ([Barrows]) into the world: crypt objects, brother deaths, tunnel
 * vermin, the prayer-drain heartbeat, the region force-load and the `::barrows` alias.
 * `SpadePlugin` calls [Barrows.tryDig] for the mound dig.
 */
class BarrowsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        BossKills.register("barrows", "Barrows chests")
        Barrows.Brother.values().forEach { BossKills.register(it.key, it.displayName) }

        // Heartbeat: prayer drain underground.
        val drain = TimerKey()
        onWorldInit {
            world.timers[drain] = Barrows.PRAYER_DRAIN_TICKS
            // Crypt + tunnel collision must exist before anyone stands there (the far-lair
            // freeze), and the vermin need walkable tiles to snap onto.
            runCatching { world.definitions.loadRegions(world, world.chunks, intArrayOf(Barrows.CRYPT_REGION)) }
                .onFailure { logger.warn(it) { "Barrows: failed to force-load region ${Barrows.CRYPT_REGION}" } }
            spawnVermin()
        }
        onTimer(drain) {
            Barrows.drainPrayer(world)
            world.timers[drain] = Barrows.PRAYER_DRAIN_TICKS
        }

        // Per-crypt objects + per-brother death credit.
        Barrows.Brother.values().forEach { b ->
            bindObj(b.sarcophagusId, listOf("search", "open")) { Barrows.search(player, b) }
            bindObj(b.stairsId, listOf("climb-up", "climb up", "climb")) { Barrows.climbOut(player, b) }
            onNpcDeath(b.npcKey) {
                val killer = npc.attr[KILLER_ATTR]?.get() as? Player ?: return@onNpcDeath
                Barrows.brotherKilled(killer, b)
            }
        }

        // The reward chest is a multi-loc: base 20973 (no actions of its own) shows child 20723
        // "Chest" [Open] at varbit 1394 = 0 and child 20724 "Chest" [Search, Close] at 1. The
        // client sends the BASE id with the child's option slot, so bind by slot: 1 = Open/Search
        // (same handler — Barrows.chest decides by run state), 2 = Close.
        onObjOption(obj = Barrows.CHEST_KEY, option = 1) { Barrows.chest(player) }
        onObjOption(obj = Barrows.CHEST_KEY, option = 2) { Barrows.closeChest(player) }

        // Tunnel vermin: their kills feed reward potential. Per-id handlers so the generic
        // osrsbox table stays off (they'd otherwise litter the chest chamber); they drop
        // nothing themselves — the chest is the payout.
        Barrows.TUNNEL_MONSTERS.map { it.npcKey }.distinct().forEach { key ->
            runCatching {
                onNpcDeath(key) {
                    val killer = npc.attr[KILLER_ATTR]?.get() as? Player ?: return@onNpcDeath
                    Barrows.tunnelKill(killer, npc.id)
                }
            }.onFailure { logger.warn(it) { "Barrows: no npc for $key" } }
        }

        onLogout { Barrows.despawnAll(player) }

        onCommand("barrows", description = "Teleport to the Barrows mounds") {
            player.moveTo(Barrows.SURFACE_LANDING)
            player.message("You arrive at the Barrows mounds. Dig into a mound with a spade to enter its crypt.")
        }
    }

    private fun spawnVermin() {
        Barrows.TUNNEL_MONSTERS.forEach { m ->
            val id = runCatching { getRSCM(m.npcKey) }.getOrNull() ?: return@forEach
            val tile = world.snapToWalkable(m.spawn, maxRadius = 6)
            val n = Npc(id, tile, world)
            n.respawns = true
            n.walkRadius = 2
            world.spawn(n)
            n.setActive(true)
        }
    }

    /**
     * Bind the first of [options] the object actually exposes (`onObjOption` throws on an
     * absent option); fall back to whatever action the cache def carries so a nameless or
     * relabelled object is still clickable, and log what was bound.
     */
    private fun bindObj(id: Int, options: List<String>, logic: Plugin.() -> Unit) {
        for (opt in options) {
            if (runCatching { onObjOption(obj = id, option = opt, logic = logic) }.isSuccess) return
        }
        val actions = runCatching { getObject(id).actions }.getOrNull()
            ?.filterNotNull()?.filter { it.isNotBlank() }.orEmpty()
        for (opt in actions) {
            if (runCatching { onObjOption(obj = id, option = opt, logic = logic) }.isSuccess) {
                logger.info { "Barrows: bound object $id via its cache option '$opt'." }
                return
            }
        }
        logger.warn { "Barrows: object $id has no bindable option (tried $options; cache actions=$actions)." }
    }
}
