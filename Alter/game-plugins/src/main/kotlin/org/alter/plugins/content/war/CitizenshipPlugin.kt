package org.alter.plugins.content.war

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.CITY_ID_ATTR
import org.alter.game.model.attr.RESPAWN_TILE_ATTR
import org.alter.game.model.move.moveTo
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Citizenship for The War (Phase 1, step 2 - see `docs/war-system-design.md`).
 *
 * Every player is a citizen of one [City]. Citizenship resolves the player's
 * home: their respawn tile (and the city their bank lives in). It is persisted
 * via [CITY_ID_ATTR]; the actual respawn override is written to the generic
 * [RESPAWN_TILE_ATTR] that core's death handler reads, so this plugin owns the
 * city concept while core stays generic.
 *
 * A citizen's effort will later feed *their* city's fronts ([City.fronts]) - the
 * link between this and [WarState] siege pressure.
 */
class CitizenshipPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onLogin {
            val storedId = player.attr[CITY_ID_ATTR]
            val needsAssignment = storedId == null || Cities.byId(storedId) == null
            val city = if (needsAssignment) Cities.default() else Cities.byId(storedId!!)!!

            // Keep both attributes current every login: a returning player picks
            // up any change to their city's respawn tile, and the generic respawn
            // override always matches their citizenship.
            player.attr[CITY_ID_ATTR] = city.id
            player.attr[RESPAWN_TILE_ATTR] = city.respawnTile.coordinate

            // Only relocate brand-new / unassigned citizens; returning players
            // keep their saved position.
            if (needsAssignment) {
                player.moveTo(city.respawnTile)
                player.message("You are now a citizen of <col=801700>${city.displayName}</col>.")
            }
        }

        // ::city - show your citizenship AND your city's war status.
        onCommand("city", description = "Show your citizenship + your city's war") {
            val city = Cities.byId(player.attr.getOrDefault(CITY_ID_ATTR, -1))
            if (city == null) {
                player.message("You are not a citizen of any city.")
                return@onCommand
            }
            player.message("You are a citizen of <col=801700>${city.displayName}</col> (respawn ${city.respawnTile.x}, ${city.respawnTile.z}).")
            if (city.fronts.isEmpty()) {
                player.message("Your city has no active front - it is, for now, at peace.")
            } else {
                city.fronts.forEach { player.message("  ${frontStatus(it)}") }
            }
        }

        // ::cities - overview of every city and its war (admin/debug; also a name list).
        onCommand("cities", Privilege.ADMIN_POWER, description = "List all cities + war status") {
            Cities.all.forEach { c ->
                val war = if (c.fronts.isEmpty()) "no front" else c.fronts.joinToString(" - ") { frontStatus(it) }
                player.message("${c.id}: <col=801700>${c.displayName}</col> - $war")
            }
        }

        // (helper below)

        // ::setcity <id> - change citizenship (updates respawn immediately).
        onCommand("setcity", Privilege.ADMIN_POWER, description = "Set your citizenship") {
            val id = player.getCommandArgs().getOrNull(0)?.toIntOrNull()
            val city = id?.let { Cities.byId(it) }
            if (city == null) {
                val options = Cities.all.joinToString { "${it.id}=${it.displayName}" }
                player.message("Usage: ::setcity <id>. Cities: $options")
                return@onCommand
            }
            player.attr[CITY_ID_ATTR] = city.id
            player.attr[RESPAWN_TILE_ATTR] = city.respawnTile.coordinate
            player.message("Citizenship set to <col=801700>${city.displayName}</col>; you respawn here now.")
        }
    }

    /** One-line war status for a [WarState] front, using the defense's display name. */
    private fun frontStatus(frontId: String): String {
        val label = Sieges.byFront(frontId)?.displayName ?: frontId
        return when (WarState.phaseOf(frontId)) {
            WarState.Phase.PEACE -> "$label: <col=4f9b4f>AT PEACE</col>"
            WarState.Phase.UNDER_RAID -> {
                val s = WarState.raidStatus(frontId)
                "$label: <col=ff4f4f>UNDER RAID</col> (${s.tierName}, ${s.goblinsAlive} goblins)"
            }
            WarState.Phase.CITY_FALLEN -> "$label: <col=ff4f4f>FALLEN</col>"
        }
    }
}
