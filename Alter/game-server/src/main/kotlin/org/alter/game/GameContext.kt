package org.alter.game

import org.alter.game.model.Tile
import org.alter.game.model.entity.Client
import org.alter.game.saving.formats.SaveFormatType

/**
 * Holds vital information that the game needs in order to run (properly).
 *
 * @param initialLaunch a flag which indicates whether this game launch is
 * the first ever launch.
 *
 * @param name the game name.
 *
 * @param revision
 * The cache revision the server is currently loading.
 *
 * @param cycleTime the time, in milliseconds, in between each full game
 * cycle/tick.
 *
 * @param playerLimit the max amount of players that can be online in the
 * world at once.
 *
 * @param home the [Tile] that will be used as the home area and tile where
 * new players will start off.
 *
 * @param skillCount the max amount of skills for players.
 *
 * @param npcStatCount the stat count for npcs.
 *
 * @param runEnergy if players' run energy will be deducted whilst running.
 *
 * @param gItemPublicDelay the amount of cycles for a [org.alter.game.model.entity.GroundItem]
 * to become public if it's owned by a player.
 *
 * @param gItemDespawnDelay the amount of cycles for a [org.alter.game.model.entity.GroundItem]
 * to despawn.
 *
 * @param preloadMaps if true, all map data will be be loaded on start-up instead
 * of on-demand.
 *
 * @author Tom <rspsmods@gmail.com>
 */
data class GameContext(
    var initialLaunch: Boolean,
    val name: String,
    val revision: Int,
    val saveFormat: SaveFormatType,
    val cycleTime: Int,
    val playerLimit: Int,
    val home: Tile,
    val skillCount: Int,
    val npcStatCount: Int,
    val runEnergy: Boolean,
    val gItemPublicDelay: Int,
    val gItemDespawnDelay: Int,
    val preloadMaps: Boolean,
    val owners: List<String> = emptyList(),
    /**
     * Login usernames forced to the administrator privilege on every login —
     * the [owners] equivalent for staff who should get the `admin` power
     * without the owner rank. Lets staff be appointed from `game.yml` instead
     * of hand-editing a save file.
     */
    val admins: List<String> = emptyList(),
    /**
     * When true, only whitelisted accounts (see
     * [org.alter.game.saving.PlayerModeration]) and configured staff ([owners]
     * and [admins]) can log in — used for closed-beta/tester phases.
     */
    val whitelistOnly: Boolean = false,
) {
    /** True if [name] is listed under `owners` in game.yml. */
    fun isConfiguredOwner(name: String): Boolean = owners.any { matches(it, name) }

    /** True if [name] is listed under `admins` in game.yml. */
    fun isConfiguredAdmin(name: String): Boolean = admins.any { matches(it, name) }

    /** True if [name] is listed under either staff list in game.yml. */
    fun isConfiguredStaff(name: String): Boolean = isConfiguredOwner(name) || isConfiguredAdmin(name)

    /**
     * Config entries are written however the owner types them ("Player B"),
     * while logins arrive normalised ("player_b") — compare on the canonical
     * account key so both spellings match.
     */
    private fun matches(configured: String, name: String): Boolean =
        Client.normalizeLogin(configured) == Client.normalizeLogin(name)
}
