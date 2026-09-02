package org.alter.plugins.content.quests.framework

import org.alter.game.model.PlayerUID
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.plugins.content.hunt.TargetMarker
import org.alter.rscm.RSCM.getRSCM

/**
 * Guidance arrows for framework quests, through the shared [TargetMarker] (which already honours
 * both mutes — the Quest Journal guidance mute and `::huntarrow`). Claims at
 * [TargetMarker.PRIORITY_QUEST] (below the ladder/hunt marks): the deepest in-progress quest's
 * current step anchor — the nearest live npc for [QuestStep.anchorNpc] (re-scanned every few
 * sweeps, never every tick), else the [QuestStep.anchor] tile.
 */
object QuestArrows {

    private const val RESCAN_SWEEPS = 8
    private const val NPC_RADIUS = 64

    private class Cached(val questKey: String, val stepId: String, var npc: Npc?, var sweepsLeft: Int)

    private val cache = HashMap<PlayerUID, Cached>()

    fun install() {
        TargetMarker.register(TargetMarker.PRIORITY_QUEST) { p -> mark(p) }
    }

    private fun mark(p: Player): TargetMarker.Mark? {
        val live = QuestRegistry.frameworkQuests()
            .filter { it.serverArrow }
            .mapNotNull { q -> QuestEngine.step(p, q)?.let { q to it } }
            .maxByOrNull { it.first.chainIndex ?: -1 }
        if (live == null) { cache.remove(p.uid); return null }
        val (q, step) = live
        val npc = step.anchorNpc?.let { nearestNpc(p, q.key, step.id, it) }
        if (npc == null && step.anchor == null) return null
        return TargetMarker.Mark(entity = npc, fallback = step.anchor)
    }

    private fun nearestNpc(p: Player, questKey: String, stepId: String, npcKey: String): Npc? {
        val c = cache[p.uid]
        if (c != null && c.questKey == questKey && c.stepId == stepId) {
            val alive = c.npc?.let { it.index >= 0 && !it.isDead() } == true
            if (alive || --c.sweepsLeft > 0) return c.npc
        }
        val id = runCatching { getRSCM(npcKey) }.getOrNull()
        var best: Npc? = null
        var bestDist = Int.MAX_VALUE
        if (id != null) {
            p.world.npcs.forEach { n ->
                if (n.id != id || n.index < 0 || n.isDead() || n.tile.height != p.tile.height) return@forEach
                val d = n.tile.getDistance(p.tile)
                if (d <= NPC_RADIUS && d < bestDist) { bestDist = d; best = n }
            }
        }
        cache[p.uid] = Cached(questKey, stepId, best, RESCAN_SWEEPS)
        return best
    }
}
