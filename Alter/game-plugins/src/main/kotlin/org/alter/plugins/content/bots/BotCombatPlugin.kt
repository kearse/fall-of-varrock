package org.alter.plugins.content.bots

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey
import org.alter.plugins.content.bots.knights.CampClearance
import org.alter.plugins.content.economy.pk.LootKeys
import org.alter.plugins.content.quests.QuestJournal
import org.alter.plugins.content.war.roguehunt.RogueHunt
import org.alter.plugins.content.war.roguehunt.RogueProblem
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
// Aliased: this class has its own `companion object`, whose implicit name `Companion` would SHADOW
// a bare `import ...companion.Companion`, making `bot is Companion` always-false (the bug that
// despawned + stripped every companion on death). The alias is unambiguous.
import org.alter.plugins.content.companion.Companion as CompanionPawn

private val logger = KotlinLogging.logger {}

/**
 * Combat wiring for [PkBot] fake-players:
 *  - makes bots attackable ANYWHERE (the "Attack" right-click option is enabled on every client,
 *    and `Combat.canEngage` bypasses the wilderness/level gate when a bot is involved),
 *  - runs the per-tick [BotBrain] for every live bot (aggro acquisition + NH decisions),
 *  - on death, a real-player killer is paid a **Blood Money bounty** ([RogueBounty]) and rolls the
 *    bot's **PK-set rare pool** ([PkLootPools]); the worn kit never drops and the bot despawns.
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
            // Nobody gets jumped by a Rogue Knight in the first seconds after logging in.
            RogueTerritory.grantTruce(player, TRUCE_AFTER_LOGIN)
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

        // Pay + roll for the killer BEFORE the death sequence respawns the bot (KILLER_ATTR is fresh).
        onPlayerPreDeath {
            val bot = player as? PkBot ?: return@onPlayerPreDeath
            if (bot is CompanionPawn) return@onPlayerPreDeath // companions keep their gear (Step 6 adds PvP gear-risk)
            // ::botduel test bots: no bounty, no rares, no rogue credit — just tell the harness who fell.
            if (bot.duelPartner != null) {
                BotDuel.onDeath(bot)
                return@onPlayerPreDeath
            }
            // (The old SPAR/LMS/CW bot guards were removed: the PK arena, LMS and Castle Wars
            // engines were purged 2026-08-28, so nothing ever set those attrs.)
            // Only a LIVE real player is a killer: never another bot, never a stale KILLER_ATTR
            // pointing at a player object that has already logged out (index -1).
            val killer = (bot.attr[KILLER_ATTR]?.get() as? Player)?.takeIf { it !is PkBot && it.index >= 0 }
            rewardKiller(world, bot, killer)
            creditRogueKill(bot, killer)
        }

        // Remove the bot from the world at the end of its death sequence.
        onPlayerDeath {
            val bot = player as? PkBot
            if (bot == null) {
                // A HUMAN just respawned (this fires after respawn + unlock): a short truce so the
                // knight that killed them — or the next one over — doesn't re-engage before they can
                // bank, regear or walk away. Unprovoked aggro only; real PvP is untouched.
                RogueTerritory.grantTruce(player, TRUCE_AFTER_DEATH)
                return@onPlayerDeath
            }
            // A companion is NOT despawned — the standard PlayerDeathAction already respawned it at
            // home with full HP; its brain walks it back. So a training death never loses the companion.
            if (bot is CompanionPawn) return@onPlayerDeath
            BotManager.despawn(world, bot)
        }
    }

    /**
     * A real-player kill pays the killer a Blood Money bounty straight to the inventory
     * ([RogueBounty]: half the player-kill rate, named ladder knights double) and rolls the bot's
     * PK-set rare pool ([PkLootPools]). Rolled rares seal into a loot key for ANY real-player kill
     * — wilderness or safe camp alike, same as a player kill — and ground-drop killer-owned on the
     * death tile only when no key can be minted (full inventory). Most kills roll nothing, so most
     * kills mint no key.
     *
     * The worn kit and inventory NEVER drop (2026-09-12: full-kit drops flooded the gear economy)
     * — nothing is stripped here; the bot despawns with them in `onPlayerDeath`. Bot-on-bot and
     * no-killer deaths pay and roll nothing.
     *
     * The bounty is fenced with `runCatching`: a throwing pre-death hook aborts every LATER hook
     * (`PlayerDeathAction`), and `RogueKnightCampPlugin`'s rank credit runs after this one.
     */
    private fun rewardKiller(world: World, bot: PkBot, killer: Player?) {
        if (killer == null) return
        runCatching { RogueBounty.pay(world, bot, killer) }
            .onFailure { logger.error(it) { "rogue bounty failed: ${killer.username} <- ${bot.username} (${bot.loadout.key})" } }
        val rares = PkLootPools.bonusDrops(world, bot, killer)
        if (rares.isEmpty()) return
        val overflow = LootKeys.tryAward(killer, bot.username, rares) // null = no key → everything drops
        (overflow ?: rares).forEach { world.spawn(GroundItem(it.id, it.amount, bot.tile, killer)) }
    }

    /**
     * A slain "Rogue Knight" PKer counts toward the rogue-hunt tally + the Rogue Problem quest, exactly
     * like a rogue-family NPC does — the bots carry that name ([BotManager]'s `ROGUE_NAME`) and players
     * (rightly) expect the kill to register: the lone Rogue Knight at the Lumbridge goblin camp, and the
     * geared wilderness PKers a questing Squire actually fights. Only a real-player kill counts — every
     * companion/spar/LMS/CW bot already returned above, so anything reaching here is a wilderness/ambush
     * rogue. Syncs the Quest Journal so the on-screen counter ticks the instant the kill lands rather
     * than on the next ~2s poll (the "stays updated" ask).
     */
    private fun creditRogueKill(bot: PkBot, killer: Player?) {
        val hunter = killer?.takeIf { it !is PkBot } ?: return
        if (!RogueHunt.isRogue(bot.username)) return
        RogueHunt.onKill(hunter)      // lifetime milestone tally (Recruiting Sergeant bounties)
        RogueProblem.onRogueKill(hunter) // Act II quest HUNT step, if the hunter is on it
        // A camp's tier rogue also ticks that camp's clearance gate (thin the camp → its knights
        // will fight you and its tier stands down). Bosses/companions resolve to no camp.
        CampClearance.campOf(bot)?.let { CampClearance.creditKill(hunter, it) }
        QuestJournal.sync(hunter)     // push the counter to the client immediately
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

        /** Truce (ticks) against unprovoked knight aggro after a login / after a death respawn. */
        const val TRUCE_AFTER_LOGIN = 50   // ~30 s
        const val TRUCE_AFTER_DEATH = 100  // ~60 s
    }
}
