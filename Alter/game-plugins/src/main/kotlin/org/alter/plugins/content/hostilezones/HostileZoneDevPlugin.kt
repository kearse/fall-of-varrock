package org.alter.plugins.content.hostilezones

import dev.openrune.cache.CacheManager.getObject
import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.move.moveTo
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.PvpZones
import org.alter.rscm.RSCM.getRSCM

/**
 * `::hostile` — the hostile-zone dev umbrella (DEV_POWER):
 *  `list` (default) · `spots [key]` · `reset [key]` · `muster <key>` · `extract` · `go <key>` ·
 *  `drop` · `probe <object.name>` (prints an object's cache verbs — confirm an extraction verb live).
 */
class HostileZoneDevPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("hostile", Privilege.DEV_POWER, description = "Hostile zones: ::hostile [list|spots|reset|muster|extract|go|drop|probe]") {
            val args = player.getCommandArgs()
            val sub = args.getOrNull(0)?.lowercase() ?: "list"
            val key = args.getOrNull(1)
            when (sub) {
                "list" -> list(player)
                "spots" -> zonesFor(key).forEach { z -> player.message("[hostile] ${z.display}: ${HostileRuntime.lootStatus[z.key]?.invoke() ?: "no loot engine"}") }
                "reset" -> zonesFor(key).forEach { z -> HostileRuntime.lootReset[z.key]?.invoke(); player.message("[hostile] ${z.display}: loot spots force-queued for refill.") }
                "muster" -> {
                    val z = HostileZones.byKey(key ?: "") ?: run { player.message("Usage: ::hostile muster <key>"); return@onCommand }
                    val g = HostileRuntime.occupiers[z.key] ?: run { player.message("[hostile] ${z.display} has no garrison."); return@onCommand }
                    g.muster(world)
                    player.message("[hostile] ${z.display}: garrison mustered (${g.livingEnemies(world).size} live).")
                }
                "extract" -> {
                    val ok = HostileRuntime.forceExtract?.invoke(player) ?: false
                    if (!ok) player.message("[hostile] You are not inside a hostile zone.")
                }
                "go" -> {
                    val z = HostileZones.byKey(key ?: "") ?: run { player.message("Usage: ::hostile go <key>"); return@onCommand }
                    player.moveTo(centerOf(z))
                    player.message("[hostile] Moved to ${z.display}.")
                }
                "drop" -> player.message("[hostile] ${HostileRuntime.supplyAdvance?.invoke() ?: "no supply-drop engine"}")
                "probe" -> {
                    val name = key ?: run { player.message("Usage: ::hostile probe <object.name>"); return@onCommand }
                    val actions = runCatching { getObject(getRSCM(name)).actions.filterNotNull() }.getOrNull()
                    player.message("[hostile] $name verbs: ${actions?.joinToString() ?: "unresolvable"}")
                }
                else -> player.message("Usage: ::hostile [list|spots|reset|muster <key>|extract|go <key>|drop|probe <object.name>]")
            }
        }
    }

    private fun list(p: org.alter.game.model.entity.Player) {
        val all = HostileZones.configured
        p.message("[hostile] ${all.size} configured, ${HostileZones.all.size} enabled (LIVE=${HostileZones.LIVE}).")
        for (z in all) {
            val c = centerOf(z)
            val zoning = if (z.enabled) {
                "red=${PvpZones.isWilderness(c)} single=${PvpZones.isSingle(c)} lvl=${PvpZones.wildernessLevel(c)}"
            } else {
                "disabled"
            }
            p.message("- ${z.key} (${z.kind.display}): $zoning")
            if (!z.enabled) continue
            HostileRuntime.lootStatus[z.key]?.let { p.message("    loot: ${it()}") }
            HostileRuntime.occupiers[z.key]?.let { p.message("    occupiers: ${it.livingEnemies(world).size} live") }
            HostileRuntime.extractionStatus[z.key]?.let { p.message("    extraction: $it") }
        }
    }

    private fun zonesFor(key: String?): List<HostileZoneConfig> =
        if (key == null) HostileZones.all else listOfNotNull(HostileZones.byKey(key))

    private fun centerOf(z: HostileZoneConfig): Tile =
        Tile((z.area.bottomLeftX + z.area.topRightX) / 2, (z.area.bottomLeftY + z.area.topRightY) / 2, 0)
}
