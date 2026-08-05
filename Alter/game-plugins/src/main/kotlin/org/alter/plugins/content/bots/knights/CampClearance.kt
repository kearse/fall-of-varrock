package org.alter.plugins.content.bots.knights

import org.alter.api.ext.message
import org.alter.game.model.Area
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.attr.KNIGHT_KEY_ATTR
import org.alter.game.model.entity.Player
import org.alter.plugins.content.bots.BotManager
import org.alter.plugins.content.bots.BotZones
import org.alter.plugins.content.bots.PkBot
import org.alter.plugins.content.companion.Companion as CompanionPawn

/**
 * **The camp-clearance gate** — the "thin the camp, then the boss" rule of the Rogue Knight
 * ladder. Every camp's tier rogues (its ambient [PkBot] colony) must be cut down
 * [KnightCamp.clearGoal] times, per player and per camp, before that camp's named knights can be
 * fought. Clearing a camp is PERMANENT for that player and is also what stands its tier down:
 * the camp's rogues aggro un-cleared hunters on sight ([org.alter.plugins.content.bots.BotBrain])
 * and ignore cleared ones.
 *
 * Progress is one persistent Int per camp (`rogue_camp_kills_<key>` — [AttributeKey] equality is
 * by persistence key, so building them here keeps `Attributes.kt` out of the loop and a new camp
 * is just a new [RogueKnights.CAMPS] entry). Counts only ever go up; kills past the goal are
 * harmless. Crediting happens in `BotCombatPlugin`'s death hook via [campOf]; the boss veto rides
 * `Combat.canEngage` via [blocksBossEngagement].
 */
object CampClearance {

    private val killAttrs: Map<String, AttributeKey<Int>> =
        RogueKnights.CAMPS.associate { it.key to AttributeKey<Int>("rogue_camp_kills_${it.key}") }

    /** Colony rectangle per camp key ([BotZones] zone with the same key), for the tile fallback
     *  in [campOf] — wilderness-grid bots wandering inside a camp read as camp rogues to players,
     *  so their kills count too. */
    private val campAreas: Map<String, Area> by lazy {
        BotZones.all.filter { cfg -> killAttrs.containsKey(cfg.key) }.associate { it.key to it.area }
    }

    /** Throttle for the boss-veto message ([blocksBossEngagement] re-fires every combat cycle). */
    private val GATE_MSG_CYCLE_ATTR = AttributeKey<Int>()
    private const val MSG_COOLDOWN_CYCLES = 8

    fun kills(p: Player, camp: KnightCamp): Int =
        (killAttrs[camp.key]?.let { p.attr[it] } ?: 0).coerceAtLeast(0)

    fun goal(camp: KnightCamp): Int = camp.clearGoal

    /** True once [p] has thinned [camp]'s tier — camps with a non-positive goal are always open. */
    fun cleared(p: Player, camp: KnightCamp): Boolean =
        goal(camp) <= 0 || kills(p, camp) >= goal(camp)

    /**
     * The camp whose gate a kill of [bot] should tick, or null when the kill is nobody's tier
     * work: named knights ([KNIGHT_KEY_ATTR]) and companions never count, colony bots count for
     * their own camp, and any other bot counts for the camp whose ground it died on.
     */
    fun campOf(bot: PkBot): KnightCamp? {
        if (bot is CompanionPawn) return null
        if (bot.attr[KNIGHT_KEY_ATTR] != null) return null // bosses are the gate's PRIZE, not fuel
        bot.zoneKey?.let { key -> RogueKnights.CAMPS.firstOrNull { it.key == key }?.let { return it } }
        val key = campAreas.entries.firstOrNull { it.value.contains(bot.tile) }?.key ?: return null
        return RogueKnights.CAMPS.firstOrNull { it.key == key }
    }

    /** Tick [camp]'s gate for [p] (caller has already filtered to real-player killers). */
    fun creditKill(p: Player, camp: KnightCamp) {
        val attr = killAttrs[camp.key] ?: return
        if (cleared(p, camp)) {
            p.attr[attr] = kills(p, camp) + 1 // keep the lifetime tally honest past the goal
            return
        }
        val count = kills(p, camp) + 1
        p.attr[attr] = count
        val goal = goal(camp)
        if (count >= goal) {
            val boss = RogueKnightLadder.activeDef(p)?.takeIf { it.camp == camp }
            // A camp with no stationed knight has no boss to promise — the stand-down is the
            // whole prize there.
            val face = when {
                boss != null -> ", and <col=ffae00>${boss.name}</col> will face you now"
                RogueKnights.LADDER.any { it.camp == camp } -> ", and its knights will face you now"
                else -> ""
            }
            p.message("<col=4f9b4f>${camp.display.replaceFirstChar { it.uppercase() }} is thinned</col> — its rogues want no more of you$face.")
        } else if (RogueKnightLadder.unlocked(p)) {
            p.message("<col=801700>Camp gate:</col> $count/$goal of ${camp.display}'s rogues thinned.")
        }
    }

    /** One-line gate report for [camp] (::knights). */
    fun statusLine(p: Player, camp: KnightCamp): String =
        if (cleared(p, camp)) {
            "Camp gate: <col=4f9b4f>${camp.display} is thinned</col> — its knights will face you."
        } else {
            "Camp gate: <col=801700>${kills(p, camp)}/${goal(camp)}</col> of ${camp.display}'s rogues thinned — the knight won't fight you until the camp is."
        }

    /**
     * The boss veto ([org.alter.plugins.content.combat.Combat.canEngage], BEFORE its bot bypass):
     * true blocks a real player from engaging a named knight whose camp they haven't cleared.
     * Message is throttled — canEngage re-fires every combat cycle.
     */
    fun blocksBossEngagement(pawn: Player, target: Player): Boolean {
        if (pawn is PkBot) return false // bot-vs-bot / companion assists are not gated
        val bot = target as? PkBot ?: return false
        val key = bot.attr[KNIGHT_KEY_ATTR] ?: return false
        val def = RogueKnights.byKey(key) ?: return false
        if (cleared(pawn, def.camp)) return false
        val now = pawn.world.currentCycle
        val last = pawn.attr[GATE_MSG_CYCLE_ATTR] ?: -MSG_COOLDOWN_CYCLES
        if (now - last >= MSG_COOLDOWN_CYCLES) {
            pawn.attr[GATE_MSG_CYCLE_ATTR] = now
            val left = goal(def.camp) - kills(pawn, def.camp)
            pawn.message("<col=801700>${def.name} won't lower himself to you yet.</col> Thin the camp first: <col=ffae00>$left</col> more of ${def.camp.display}'s rogues.")
        }
        return true
    }

    /** The nearest live tier rogue of [camp] to [p], for the hunt marker's in-scene lock. */
    fun nearestCampBot(p: Player, camp: KnightCamp): PkBot? {
        var best: PkBot? = null
        var bestDist = Int.MAX_VALUE
        BotManager.active.forEach { bot ->
            if (bot.index < 0 || bot.isDead()) return@forEach
            if (bot.zoneKey != camp.key || bot.attr[KNIGHT_KEY_ATTR] != null) return@forEach
            val dist = bot.tile.getDistance(p.tile)
            if (dist < bestDist) {
                bestDist = dist
                best = bot
            }
        }
        return best
    }
}
