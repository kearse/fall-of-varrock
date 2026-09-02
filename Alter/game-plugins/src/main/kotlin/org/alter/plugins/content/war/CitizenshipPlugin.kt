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
 * Citizenship for The War.
 *
 * Every player is a citizen of one [City]. Citizenship resolves the player's
 * home: their respawn tile (and the city their bank lives in). It is persisted
 * via [CITY_ID_ATTR]; the actual respawn override is written to the generic
 * [RESPAWN_TILE_ATTR] that core's death handler reads, so this plugin owns the
 * city concept while core stays generic.
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

        // ::city - show your citizenship AND the realm's live war.
        onCommand("city", description = "Show your citizenship + the realm's war") {
            val city = Cities.byId(player.attr.getOrDefault(CITY_ID_ATTR, -1))
            if (city == null) {
                player.message("You are not a citizen of any city.")
                return@onCommand
            }
            player.message("You are a citizen of <col=801700>${city.displayName}</col> (respawn ${city.respawnTile.x}, ${city.respawnTile.z}).")
            player.message("  ${warStatus(world)}")
        }

        // ::cities - overview of every city (admin/debug; also a name list).
        onCommand("cities", Privilege.ADMIN_POWER, description = "List all cities") {
            Cities.all.forEach { c ->
                val role = when {
                    c.id == Cities.DEFAULT_CITY_ID -> "home"
                    Campaigns.hostileByKey(c.key) != null -> "hostile target"
                    else -> "named"
                }
                player.message("${c.id}: <col=801700>${c.displayName}</col> - $role")
            }
            player.message(warStatus(world))
        }

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

    /** One-line status of the realm's live offensive war. */
    private fun warStatus(world: World): String {
        val march = CampaignRegistry.activeMarch()
        val hostile = Campaigns.HOSTILE.filter { CampaignRegistry.isAttacking(it.cityKey) }
        return when {
            hostile.isNotEmpty() -> "War: <col=ff4f4f>a campaign is under way in ${hostile.joinToString { it.displayName }}</col> — get to the front!"
            march != null -> "War: <col=4f9b4f>the realm's ${march.tier.display} is in the field</col> (${march.progressPct(world)}%) — <col=801700>::march</col> to rally."
            else -> "War: <col=4f9b4f>no column is out</col> — the next march musters on the half hour."
        }
    }
}
