package org.alter.plugins.content.bots

import org.alter.api.ext.*
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.game.Server
import org.alter.plugins.content.combat.PvpZones

/**
 * Admin commands for the PKer-bot system.
 *
 * This is a scaffolding/validation surface for the bot foundation — it proves the fake-player
 * renders with full top-tier gear before the NH combat brain and roaming systems are layered on.
 *
 *   ::spawnbot [loadout]  - spawn a bot at your tile (default: elite_nh)
 *   ::bots                - list live bots + the available loadouts
 *   ::clearbots           - despawn every bot
 */
class BotPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("spawnbot", Privilege.DEV_POWER, description = "Spawn a PKer bot at your tile") {
            val args = player.getCommandArgs()
            val key = if (args.isNotEmpty()) args[0] else "elite_nh"
            val loadout = BotLoadouts.get(key)
            if (loadout == null) {
                player.message("Unknown loadout '$key'. Options: ${BotLoadouts.keys().joinToString(", ")}")
                return@onCommand
            }
            val bot = BotManager.spawn(world, loadout, player.tile)
            if (bot == null) {
                player.message("Failed to spawn bot (world player limit reached?).")
            } else {
                player.message("Spawned ${loadout.displayName} (${loadout.key}) bot as '${bot.username}'. Live bots: ${BotManager.active.size}")
            }
        }

        onCommand("bots", Privilege.DEV_POWER, description = "List live PKer bots") {
            player.message("Live bots: ${BotManager.active.size}")
            BotManager.active.forEach { player.message(" - ${it.username} [${it.loadout.key}] @ ${it.tile.x},${it.tile.z}") }
            player.message("Loadouts: ${BotLoadouts.keys().joinToString(", ")}")
        }

        onCommand("zone", Privilege.DEV_POWER, description = "Show the PvP zone classification at your tile") {
            val t = player.tile
            player.message("Zone @ ${t.x},${t.z}: wild=${PvpZones.isWilderness(t)} safe=${PvpZones.isSafe(t)} single=${PvpZones.isSingle(t)} multi=${PvpZones.isMulti(t)} wildLvl=${PvpZones.wildernessLevel(t)}")
        }

        onCommand("botzones", Privilege.DEV_POWER, description = "List bot zones + their centre tiles") {
            player.message("Bot zones (bots spawn when you're within ~32 tiles; despawn ~36s after you leave):")
            BotZones.all.forEach { z ->
                val cx = (z.area.bottomLeftX + z.area.topRightX) / 2
                val cz = (z.area.bottomLeftY + z.area.topRightY) / 2
                player.message(" - ${z.key} (${z.displayName}): centre $cx,$cz  [::botgo ${z.key}]")
            }
        }

        onCommand("botgo", Privilege.DEV_POWER, description = "Teleport to a bot zone (::botgo <key>)") {
            val key = player.getCommandArgs().getOrNull(0)
            val z = BotZones.all.firstOrNull { it.key == key }
            if (z == null) {
                player.message("Usage: ::botgo <key>. Zones: ${BotZones.all.joinToString(", ") { it.key }}")
                return@onCommand
            }
            val cx = (z.area.bottomLeftX + z.area.topRightX) / 2
            val cz = (z.area.bottomLeftY + z.area.topRightY) / 2
            player.moveTo(Tile(cx, cz))
            player.message("Teleported to ${z.displayName} ($cx,$cz). Bots spawn within a few seconds.")
        }

        onCommand("clearbots", Privilege.DEV_POWER, description = "Despawn all PKer bots") {
            val n = BotManager.active.size
            BotManager.despawnAll(world)
            player.message("Despawned $n bot(s).")
        }
    }
}
