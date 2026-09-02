package org.alter.plugins.content.war.roguehunt

import dev.openrune.cache.CacheManager.getNpc
import org.alter.api.ext.message
import org.alter.api.ext.npc
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.attr.KNIGHT_KEY_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bots.BotManager
import org.alter.plugins.content.bots.PkBot
import org.alter.plugins.content.bots.knights.RogueKnights
import org.alter.plugins.content.hunt.TargetMarker
import org.alter.plugins.content.quests.QuestBook

/**
 * Wiring for [RogueProblem] (the Act II "Rogue Problem" quest). Resumes the per-player state on
 * login, drives the poll timer, counts quest-scoped rogue kills on the additive death list (cheap —
 * bails instantly for non-rogue kills, mirroring [RogueHuntPlugin]), and serves `::rogueproblem`.
 *
 * The quest is *offered* by the Recruiting Sergeant once War-Prep I is done — it is OPTIONAL and
 * nothing auto-starts it (design authority §8); this plugin only owns the passive wiring for a
 * player who accepted.
 */
class RogueProblemPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onLogin { RogueProblem.resumeOnLogin(player) }

        onTimer(RogueProblem.TIMER) { RogueProblem.pollTick(player) }

        // The HUNT step's guidance (mirrors the ladder's marker): the arrow hunts LIVE rogues —
        // the nearest tier rogue of a safe road camp when one's in reach, the nearest safe camp
        // from afar — never a bare map tile, so it always leads to something the hunter can kill.
        TargetMarker.register(TargetMarker.PRIORITY_HUNT) { p ->
            if (RogueProblem.step(p) != RogueProblem.Step.HUNT) null else huntMark(p)
        }

        onAnyNpcDeath {
            val killer = npc.attr[KILLER_ATTR]?.get() as? Player ?: return@onAnyNpcDeath
            if (RogueProblem.step(killer) == RogueProblem.Step.HUNT && RogueHunt.isRogue(npcName(npc.id))) {
                RogueProblem.onRogueKill(killer)
            }
        }

        onCommand("rogueproblem", description = "Open your Rogue Hunting quest in the Quest Journal") {
            player.message(RogueProblem.statusLine(player))
            val idx = if (RogueProblem.step(player).ordinal >= RogueProblem.Step.KNIGHT.ordinal)
                QuestBook.ROGUE_HUNTING_II else QuestBook.ROGUE_HUNTING_I
            QuestBook.open(player, idx)
        }
    }

    private fun npcName(id: Int): String? =
        runCatching { getNpc(id).name }.getOrNull()

    /** The nearest live safe-road tier rogue to [p] (named knights excluded — the boss is the
     *  KNIGHT step's prize), falling back to the nearest safe camp's center from afar. */
    private fun huntMark(p: Player): TargetMarker.Mark {
        val camps = RogueKnights.CAMPS.filter { it.safe }
        var best: PkBot? = null
        var bestDist = Int.MAX_VALUE
        BotManager.active.forEach { bot ->
            if (bot.index < 0 || bot.isDead()) return@forEach
            if (bot.attr[KNIGHT_KEY_ATTR] != null) return@forEach
            if (camps.none { it.key == bot.zoneKey }) return@forEach
            val dist = bot.tile.getDistance(p.tile)
            if (dist < bestDist) {
                bestDist = dist
                best = bot
            }
        }
        val nearestCamp = camps.minByOrNull { it.center.getDistance(p.tile) }?.center
        return TargetMarker.Mark(entity = best, fallback = nearestCamp)
    }
}
