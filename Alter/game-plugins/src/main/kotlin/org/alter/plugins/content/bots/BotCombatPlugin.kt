package org.alter.plugins.content.bots

import org.alter.api.EquipmentType
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
import org.alter.plugins.content.bots.knights.CampClearance
import org.alter.plugins.content.combat.PvpZones
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
            creditRogueKill(bot, killer)
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
     *
     * On top of the kit, a real killer also rolls the bot's **PK-set loot pool** ([PkLootPools]) —
     * the tier's rare gear chase (escalating to claws / AGS / voidwaker-class uniques), or a named
     * rogue knight's signature table. Rolled rares join the kit BEFORE the key is sealed, so they
     * ride the same loot-key / killer-owned-drop flow as the gear.
     */
    private fun dropAllGear(world: World, bot: PkBot, killer: Player?) {
        val tile = bot.tile
        val kit = ArrayList<Item>()
        val ammoSlot = EquipmentType.AMMO.id
        for (i in 0 until bot.equipment.capacity) {
            // The worn quiver is dressing — bots are geared via Item(id) so it holds a single arrow
            // (they don't consume ammo). Dropping it reads as a bugged "1 arrow" drop; skip it.
            bot.equipment[i]?.let { if (i != ammoSlot || it.amount > 1) kit += it }
            bot.equipment[i] = null
        }
        for (i in 0 until bot.inventory.capacity) {
            bot.inventory[i]?.let { kit += it }
            bot.inventory[i] = null
        }
        val realKiller = killer?.takeIf { it !is PkBot }
        kit += PkLootPools.bonusDrops(world, bot, realKiller)
        val overflow = if (realKiller != null && PvpZones.isWilderness(tile)) {
            LootKeys.tryAward(realKiller, bot.username, kit) // null = no key → everything drops
        } else {
            null
        }
        (overflow ?: kit).forEach { world.spawn(GroundItem(it.id, it.amount, tile, realKiller)) }
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
    }
}
