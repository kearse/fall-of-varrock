package org.alter.plugins.content.hostilezones

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.getInteractingGameObj
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.model.queue.TaskPriority
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.announce.Announce
import org.alter.plugins.content.bots.PkBot
import org.alter.plugins.content.magic.TeleBlock
import org.alter.plugins.content.war.StaticTerrain
import org.alter.plugins.service.marketvalue.ItemMarketValueService
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Extraction points** — the announced exit of the extraction loop ([HostileZones]). Each
 * [ExtractionPoint] is a dynamic object (a smugglers' trapdoor) spawned at world init; using it
 * starts a [EXTRACT_TICKS]-tick channel that is cancelled by moving, taking damage or dying, and
 * refused outright while teleblocked. On completion the raider is moved to the zone's exit tile,
 * told what they got out with (the market value of what they carry minus what they walked in
 * with), broadcast to the realm above [BROADCAST_MIN_VALUE], and their [ExtractionRecords] entry
 * is booked. **Nothing is minted** — the reward is the loot already in the pack.
 *
 * Walking out of the box is still allowed and silent; the trapdoors are the bragging-rights exit
 * (and, deep in the wild where teleports are blocked, the only fast one).
 *
 * Binding rules: one bind per (object, verb); the verb is pre-checked against the cache so a bad
 * id logs and never drops the plugin; the handler routes by the clicked object's TILE, so any
 * vanilla object sharing the id elsewhere falls through untouched.
 */
class HostileExtractionPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val prices = world.getService(ItemMarketValueService::class.java)
    private val points = ArrayList<Pair<HostileZoneConfig, ExtractionPoint>>()

    init {
        for (cfg in HostileZones.all) for (pt in cfg.extractionPoints) points += cfg to pt

        points.map { it.second.objectName to it.second.option }.distinct().forEach { (name, option) ->
            val ok = runCatching { objHasOption(name, option) }.getOrDefault(false)
            if (!ok) {
                logger.error { "[HOSTILE EXTRACTION] '$name' has no '$option' verb in the cache — its extraction points are unclickable (fix the config)." }
                return@forEach
            }
            onObjOption(name, option) {
                val obj = player.getInteractingGameObj()
                val hit = points.firstOrNull { it.second.tile.sameAs(obj.tile) } ?: return@onObjOption
                beginExtraction(player, hit.first, hit.second)
            }
        }

        onWorldInit {
            for (cfg in HostileZones.all) {
                var spawned = 0
                for (pt in cfg.extractionPoints) {
                    val id = runCatching { getRSCM(pt.objectName) }.getOrNull() ?: continue
                    world.spawn(DynamicObject(id = id, type = OBJECT_TYPE, rot = pt.rot, tile = pt.tile))
                    spawned++
                }
                if (cfg.extractionPoints.isNotEmpty()) {
                    val verbs = cfg.extractionPoints.map { "${it.objectName}/${it.option}" }.distinct().joinToString()
                    HostileRuntime.extractionStatus[cfg.key] = "$spawned extraction point(s), $verbs"
                    logger.info { "[HOSTILE EXTRACTION] '${cfg.display}': $spawned point(s) spawned; bound $verbs." }
                }
            }
        }

        HostileRuntime.forceExtract = { p ->
            val zone = HostileZones.at(p.tile)
            if (zone != null) complete(p, zone)
            zone != null
        }
    }

    private fun beginExtraction(p: Player, zone: HostileZoneConfig, pt: ExtractionPoint) {
        if (p is PkBot) return
        if (TeleBlock.isBlocked(p)) {
            p.message("A magical force stops you from slipping away.")
            return
        }
        p.queue(TaskPriority.STANDARD) {
            val start = p.tile
            val hp = p.getCurrentHp()
            p.message("You begin to slip out through ${pt.label}...")
            p.animate(EXTRACT_ANIM)
            repeat(EXTRACT_TICKS) {
                wait(1)
                if (p.isDead() || !p.tile.sameAs(start) || p.getCurrentHp() < hp) {
                    p.animate(-1)
                    p.message("<col=cc2222>Your extraction is interrupted!</col>")
                    return@queue
                }
            }
            p.animate(-1)
            complete(p, zone)
        }
    }

    /** Move the raider out, price the haul, book it, brag. */
    private fun complete(p: Player, zone: HostileZoneConfig) {
        val carried = HostilePresence.carriedValue(p, prices)
        val entry = p.attr[HostilePresence.ENTRY_VALUE_ATTR]
        val haul = if (entry != null) (carried - entry).coerceAtLeast(0L) else carried
        p.moveTo(walkableExit(zone.exitTile))
        p.attr.remove(HostilePresence.ZONE_ATTR)
        p.attr.remove(HostilePresence.ENTRY_VALUE_ATTR)
        p.message("<col=4f9b4f>You extract from ${zone.display}</col> with ${"%,d".format(haul)} gp of loot.")
        if (haul >= BROADCAST_MIN_VALUE) {
            Announce.broadcast(world, "<col=ffcc00>${p.username} extracted from ${zone.display} with ${"%,d".format(haul)} gp of loot.</col>")
        }
        val rec = ExtractionRecords.record(p, zone.key, haul)
        logger.info { "[HOSTILE EXTRACTION] ${p.username} extracted from ${zone.key} haul=$haul (lifetime ${rec.count}, best ${rec.best})." }
    }

    private fun walkableExit(exit: Tile): Tile {
        if (StaticTerrain.isWalkable(exit.x, exit.z)) return exit
        val snapped = StaticTerrain.nearestWalkable(exit.x, exit.z, maxRadius = 4) ?: return exit
        return Tile(snapped.first, snapped.second, exit.height)
    }

    private companion object {
        /** Channel length (ticks) — 6 s to be found and hit. TUNE. */
        const val EXTRACT_TICKS = 10
        /** Hauls at/above this are broadcast realm-wide. TUNE. */
        const val BROADCAST_MIN_VALUE = 10_000L
        /** Climb-down animation for the channel. */
        const val EXTRACT_ANIM = 827
        /** Interactable scenery object type. */
        const val OBJECT_TYPE = 10
    }
}
