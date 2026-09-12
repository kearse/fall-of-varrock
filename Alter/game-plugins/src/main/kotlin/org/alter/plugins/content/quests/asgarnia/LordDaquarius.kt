package org.alter.plugins.content.quests.asgarnia

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.NO_LOOT_ATTR
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.move.walkTo
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Lord Daquarius** as a recurring story character: the stock Kinshra commander (`npc.lord_daquarius`,
 * 4929 — Talk-to only, no Attack) spawned **owner-bound** for one player's scene and removed when the
 * scene ends. An owner-bound [Npc] is visible ONLY to its owner (`NetworkServiceFactory.shouldAdd`)
 * and is swept by the engine the moment the owner logs out (`WorldRemoveTask`), so two players
 * reading the same orders side by side each get their own Daquarius, nobody else in the Wilderness
 * sees a Kinshra lord standing about, and he can never be fought, farmed or camped.
 *
 * Old Wounds (Asgarnia — BREACH, Quest 4) introduces him this way twice; The White Wall (Quest 5)
 * is expected to reuse [appear] / [leave] for its own scenes. He is never the boss of anything.
 *
 * Lifecycle: [appear] (one live scene per owner — a new one dismisses the old), [leave] (walks
 * toward a tile and vanishes a few ticks later), [sweep] (the plugin timer: removes finished,
 * orphaned or stale scenes — a dialogue can die on death / logout / attack, the npc must not linger).
 */
object LordDaquarius {

    const val NPC = "npc.lord_daquarius"
    const val NAME = "Lord Daquarius"

    /** Safety ceiling on a scene npc's life (~5 minutes): a scene the player walked away from still ends. */
    private const val MAX_AGE_TICKS = 500
    private const val LEAVE_TICKS = 5

    private class Scene(val npc: Npc, val owner: Player) {
        var age = 0
        var leavingIn = -1
    }

    private val scenes = HashMap<Any, Scene>()

    private fun key(p: Player): Any = (p.uid.value as? String)?.lowercase() ?: p.uid.value

    fun id(): Int = runCatching { getRSCM(NPC) }.getOrDefault(-1)

    /** True while [p] has a live Daquarius scene (he is standing there, visible to them alone). */
    fun present(p: Player): Boolean = scenes[key(p)]?.npc?.let { it.index >= 0 } == true

    /**
     * Put Daquarius at [at] for [p] alone, facing them. Returns null (and logs) if the cache lacks the
     * npc or the spawn failed — callers narrate without him rather than fail the quest.
     */
    fun appear(p: Player, at: Tile): Npc? {
        dismiss(p)
        val id = id().takeIf { it >= 0 } ?: run {
            logger.warn { "[daquarius] '$NPC' does not resolve — the scene runs without him." }
            return null
        }
        return runCatching {
            val npc = Npc(p, id, at, p.world) // owner-bound: visible to p only, swept on their logout
            npc.walkRadius = 0
            npc.respawns = false
            npc.aggroCheck = { _, _ -> false }
            npc.attr[NO_LOOT_ATTR] = true
            p.world.spawn(npc)
            npc.setActive(true)
            npc.facePawn(p)
            p.facePawn(npc)
            scenes[key(p)] = Scene(npc, p)
            npc
        }.onFailure { logger.error(it) { "[daquarius] failed to spawn for ${p.username} at $at" } }.getOrNull()
    }

    /** He leaves: turns away, walks toward [toward] (if given) and is gone a few ticks later. */
    fun leave(p: Player, toward: Tile? = null) {
        val s = scenes[key(p)] ?: return
        p.resetFacePawn()
        s.npc.resetFacePawn()
        if (toward != null && s.npc.index >= 0) runCatching { s.npc.walkTo(toward) }
        s.leavingIn = LEAVE_TICKS
    }

    /** Remove [p]'s scene npc immediately (a new scene, or a plugin that needs him gone). */
    fun dismiss(p: Player) {
        scenes.remove(key(p))?.let { remove(it) }
    }

    /** The plugin timer: end scenes that finished leaving, lost their owner, or overstayed. */
    fun sweep(world: World) {
        if (scenes.isEmpty()) return
        for (s in scenes.values.toList()) {
            s.age++
            val gone = s.npc.index < 0 || !world.npcs.contains(s.npc)
            val done = s.leavingIn > 0 && --s.leavingIn == 0
            if (gone || done || s.age > MAX_AGE_TICKS || !s.owner.isOnline) {
                scenes.remove(key(s.owner))
                remove(s)
            }
        }
    }

    private fun remove(s: Scene) {
        val n = s.npc
        if (n.index >= 0 && n.world.npcs.contains(n)) runCatching { n.world.remove(n) }
    }
}
