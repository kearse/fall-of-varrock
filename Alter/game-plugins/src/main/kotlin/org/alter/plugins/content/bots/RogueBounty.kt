package org.alter.plugins.content.bots

import org.alter.api.ext.message
import org.alter.game.model.World
import org.alter.game.model.attr.KNIGHT_KEY_ATTR
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.plugins.content.bots.knights.RogueKnightDef
import org.alter.plugins.content.bots.knights.RogueKnights
import org.alter.plugins.content.economy.pk.PkRewardsPlugin
import org.alter.rscm.RSCM.getRSCM

/**
 * **The Rogue Knight bounty** — what a slain [PkBot] is worth in Blood Money.
 *
 * Bots used to drop their entire worn kit + inventory on death; whip/Bandos/Ancestral-class bots
 * respawning as loot piñatas flooded the gear economy (operator, 2026-09-12: "it ruins the
 * economy, it's too easy to farm the gear"). Now the kit NEVER drops. A real-player kill pays a
 * Blood Money bounty straight to the killer's inventory instead, and the only ITEMS a bot yields
 * are the rare-tier rolls of its band's PK-set pool ([PkLootPools]) — so PK gear is a Blood Money
 * grind at the PK Rewards shelf plus a genuine rare chase, never a farm.
 *
 * Amount = the player-kill formula ([PkRewardsPlugin.bloodMoneyFor], 25 + 3 × combat level) ×
 * [BOT_MULT] (half — a bot is practice, a human is the real payout) × [NAMED_KNIGHT_MULT] for a
 * named ladder knight (they're per-hunter boss fights with HP/reaction overrides). Multipliers
 * fold before the single truncation. A bot's `combatLevel` is its loadout's displayed level:
 * bronze fodder (6) → 21, an elite NH roamer (126) → 201, Sir Brack (iron, 11) → 58, Lord Vexmar
 * (elite, 126) → 403 ≈ one real max-level kill. All TUNE.
 *
 * **No cap, no guard (operator decision).** This sits OUTSIDE [org.alter.plugins.content.economy.pk.PkKillGuard]
 * — that ledger is human-vs-human by design and denies `BOT_VICTIM` at rule 2 (which is also what
 * keeps `PkRewardsPlugin`'s hook from paying the same kill twice). If farming data ever calls for
 * a per-killer daily bot-bounty cap, it goes in [pay] before the inventory add, on the
 * `RogueRewards.rollDay` epoch-day-attr pattern — not in the guard.
 */
object RogueBounty {

    // ---- TUNE ---------------------------------------------------------------------------------
    /** Bot kills pay this fraction of the player-kill formula. */
    const val BOT_MULT = 0.5
    /** Named ladder knights ([KNIGHT_KEY_ATTR] → [RogueKnights.byKey]) pay this × the bot bounty. */
    const val NAMED_KNIGHT_MULT = 2.0

    private val bm: Int by lazy { getRSCM("item.blood_money") }

    /** The ladder def behind a named knight bot, or null for an ambient Rogue Knight. */
    fun knightOf(bot: PkBot): RogueKnightDef? = bot.attr[KNIGHT_KEY_ATTR]?.let { RogueKnights.byKey(it) }

    /** What a real-player kill of [bot] pays (never negative; 0 only for a nonsense combat level). */
    fun bountyFor(bot: PkBot): Int {
        val mult = BOT_MULT * (if (knightOf(bot) != null) NAMED_KNIGHT_MULT else 1.0)
        return (PkRewardsPlugin.bloodMoneyFor(bot.combatLevel) * mult).toInt().coerceAtLeast(0)
    }

    /**
     * Pay [killer] for slaying [bot]: inventory first, any overflow as a killer-owned ground stack
     * on the KILLER's tile (the `PkRewardsPlugin` pattern — Blood Money stacks, so a full pack
     * still merges into one owned pile). The caller has already filtered [killer] to a live real
     * player (never a [PkBot], never an unregistered object). Returns the amount paid.
     */
    fun pay(world: World, bot: PkBot, killer: Player): Int {
        val reward = bountyFor(bot)
        if (reward <= 0) return 0
        // (A daily cap, if one is ever wanted, gates HERE — see the class doc.)
        val added = killer.inventory.add(item = bm, amount = reward, assureFullInsertion = false)
        val leftover = reward - added.completed
        if (leftover > 0) world.spawn(GroundItem(bm, leftover, killer.tile, killer))
        val name = knightOf(bot)?.name ?: bot.username
        killer.message("<col=990000>Blood money:</col> +${"%,d".format(reward)} for slaying $name.")
        return reward
    }
}
