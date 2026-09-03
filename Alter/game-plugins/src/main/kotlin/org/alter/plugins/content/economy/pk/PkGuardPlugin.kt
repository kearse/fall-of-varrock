package org.alter.plugins.content.economy.pk

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Staff/dev commands for the PK kill-legitimacy guard ([PkKillGuard]):
 *  - `::pkaudit <name>` (admin) — a player's guard state: address, account age, today's counters
 *    and their recent paying-kill ledger (online players only).
 *  - `::pkguard [on|off|rule <RULE> on|off]` (dev) — status / runtime toggles (not persisted).
 *  - `::pktest <name>` (dev) — dry-run the rules as if you had just killed <name>; writes nothing.
 */
class PkGuardPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("pkaudit", Privilege.ADMIN_POWER, description = "PK guard ledger for a player: ::pkaudit <name>") {
            val target = findPlayer(player.getCommandArgs()) ?: run {
                player.message("Usage: ::pkaudit <name> (the player must be online).")
                return@onCommand
            }
            PkKillGuard.describe(target).forEach { player.message(it) }
        }

        onCommand("pkguard", Privilege.DEV_POWER, description = "PK guard status / toggles: ::pkguard [on|off|rule <RULE> on|off]") {
            val args = player.getCommandArgs().map { it.lowercase() }
            when {
                args.isEmpty() -> PkKillGuard.status().forEach { player.message(it) }
                args[0] == "on" -> { PkKillGuard.enabled = true; player.message("PK guard enabled.") }
                args[0] == "off" -> { PkKillGuard.enabled = false; player.message("PK guard DISABLED (rules 1-4 still apply).") }
                args[0] == "rule" && args.size >= 3 -> {
                    val rule = PkKillGuard.Rule.entries.firstOrNull { it.name.equals(args[1], ignoreCase = true) }
                    if (rule == null) {
                        player.message("Unknown rule. Rules: ${PkKillGuard.Rule.entries.joinToString()}")
                    } else {
                        if (args[2] == "off") PkKillGuard.disabledRules += rule else PkKillGuard.disabledRules -= rule
                        player.message("Rule $rule ${if (args[2] == "off") "disabled" else "enabled"}.")
                    }
                }
                else -> player.message("Usage: ::pkguard [on|off|rule <RULE> on|off]")
            }
        }

        onCommand("pktest", Privilege.DEV_POWER, description = "Dry-run the PK guard as if you killed <name>: ::pktest <name>") {
            val target = findPlayer(player.getCommandArgs()) ?: run {
                player.message("Usage: ::pktest <name> (the player must be online).")
                return@onCommand
            }
            val v = PkKillGuard.assess(world, player, target)
            player.message(
                "PK guard dry-run vs ${target.username}: <col=801700>${v.rule}</col> - ${v.reason} " +
                    "(risked=${"%,d".format(v.risked)} wild=${v.wildLevel} ipsEqual=${v.ipsEqual})",
            )
        }
    }

    /** Online lookup by display name (spaces or underscores). */
    private fun findPlayer(args: Array<String>): Player? {
        if (args.isEmpty()) return null
        val joined = args.joinToString(" ")
        return world.getPlayerForName(joined) ?: world.getPlayerForName(joined.replace('_', ' '))
    }
}
