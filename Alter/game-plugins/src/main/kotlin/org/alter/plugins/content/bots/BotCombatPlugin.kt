package org.alter.plugins.content.bots

import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.CW_BOT_ATTR
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.attr.LMS_BOT_ATTR
import org.alter.game.model.attr.SPAR_BOT_ATTR
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.game.model.timer.TimerKey
import org.alter.plugins.content.combat.PvpZones
import org.alter.plugins.content.economy.pk.LootKeys
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
// Aliased: this class has its own `companion object`, whose implicit name `Companion` would SHADOW
// a bare `import ...companion.Companion`, making `bot is Companion` always-false (the bug that
// despawned + stripped every companion on death). The alias is unambiguous.
import org.alter.plugins.content.companion.Companion as CompanionPawn

/**
 * Combat wiring for [PkBot] fake-players:
 *  - makes bots attackable ANYWHERE (the "Attack" right-click option is enabled on every client,
 *    and `Combat.canEngage` bypasses the wilderness/level gate when a bot is involved),
 *  - runs the per-tick [BotBrain] for every live bot (aggro acquisition + NH decisions),
 *  - on death, the bot **drops its entire kit** (worn gear + inventory) to the killer and despawns.
 */
class BotCombatPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val botAggroTimer = TimerKey()

    init {
        // Show the "Attack" option on players for everyone, so bots can be attacked outside the
        // wilderness. Real player-vs-player is still gated server-side by Combat.canEngage.
        onLogin {
            player.sendOption("Attack", ATTACK_OPTION_SLOT)
        }

        // Start the global bot-aggression heartbeat.
        onWorldInit {
            world.timers[botAggroTimer] = AGGRO_SCAN_TICKS
        }
        onTimer(botAggroTimer) {
            if (BotManager.active.isNotEmpty()) {
                // Iterate a snapshot — a bot may despawn (death) mid-loop.
                BotManager.active.toList().forEach { BotBrain.tick(world, it) }
            }
            world.timers[botAggroTimer] = AGGRO_SCAN_TICKS
        }

        // Drop the bot's full kit BEFORE the death sequence respawns/strips it.
        onPlayerPreDeath {
            val bot = player as? PkBot ?: return@onPlayerPreDeath
            if (bot is CompanionPawn) return@onPlayerPreDeath // companions keep their gear (Step 6 adds PvP gear-risk)
            // Sparring partners are owned by the PK Training Arena: they NEVER drop loot (a trainee
            // farms them for free). The arena's own death handler strips + despawns them.
            if (bot.attr[SPAR_BOT_ATTR] == true) return@onPlayerPreDeath
            // Last Man Standing competitors are owned by the LMS engine — it handles their round loot
            // and despawn, so skip the wilderness full-kit faucet here (same as spar bots).
            if (bot.attr[LMS_BOT_ATTR] == true) return@onPlayerPreDeath
            // Castle Wars fillers respawn endlessly during a game — dropping a kit per death would be
            // an infinite gear faucet. The Castle Wars engine owns their lifecycle.
            if (bot.attr[CW_BOT_ATTR] == true) return@onPlayerPreDeath
            val killer = bot.attr[KILLER_ATTR]?.get() as? Player
            dropAllGear(world, bot, killer)
        }

        // Remove the bot from the world at the end of its death sequence.
        onPlayerDeath {
            val bot = player as? PkBot ?: return@onPlayerDeath
            // A companion is NOT despawned — the standard PlayerDeathAction already respawned it at
            // home with full HP; its brain walks it back. So a training death never loses the companion.
            if (bot is CompanionPawn) return@onPlayerDeath
            // Sparring partners are despawned by the arena (which also messages the trainee), not here.
            if (bot.attr[SPAR_BOT_ATTR] == true) return@onPlayerDeath
            // LMS competitors are despawned by the LMS engine's elimination handler, not here.
            if (bot.attr[LMS_BOT_ATTR] == true) return@onPlayerDeath
            // Castle Wars fillers are despawned + re-queued by the Castle Wars engine, not here.
            if (bot.attr[CW_BOT_ATTR] == true) return@onPlayerDeath
            BotManager.despawn(world, bot)
        }
    }

    /**
     * The bot's entire kit (worn gear + inventory) goes to the killer: sealed in a loot key for a
     * real-player kill in the wilderness (same as killing a real player), otherwise as ground loot
     * on the death tile (killer-owned window for a real player, public for a bot/no killer).
     */
    private fun dropAllGear(world: World, bot: PkBot, killer: Player?) {
        val tile = bot.tile
        val kit = ArrayList<Item>()
        for (i in 0 until bot.equipment.capacity) {
            bot.equipment[i]?.let { kit += it }
            bot.equipment[i] = null
        }
        for (i in 0 until bot.inventory.capacity) {
            bot.inventory[i]?.let { kit += it }
            bot.inventory[i] = null
        }
        val realKiller = killer?.takeIf { it !is PkBot }
        val overflow = if (realKiller != null && PvpZones.isWilderness(tile)) {
            LootKeys.tryAward(realKiller, bot.username, kit) // null = no key → everything drops
        } else {
            null
        }
        (overflow ?: kit).forEach { world.spawn(GroundItem(it.id, it.amount, tile, realKiller)) }
    }

    private companion object {
        /**
         * Right-click option slot for "Attack". MUST be >= 2: `sendOption(id)` sends wire slot
         * `id-1`, and the client ignores wire slot 0 (it renders player-op slots 1..8). id=1 → wire
         * slot 0 → silently dropped (the bug that hid Attack for ages). id=2 → wire slot 1 → renders
         * at the top of the menu. Follow/Trade/Report use ids 3/4/5, so 2 is free.
         */
        const val ATTACK_OPTION_SLOT = 2

        /** How often (ticks) the brain heartbeat runs — every tick, for snappy prayer switching. */
        const val AGGRO_SCAN_TICKS = 1
    }
}
