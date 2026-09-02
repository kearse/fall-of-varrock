package org.alter.plugins.content.quests.framework

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.NO_LOOT_ATTR
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.plugins.content.companion.CompanionPolicy
import org.alter.plugins.content.raids.RaidInstance
import org.alter.plugins.content.war.WarNpcNames
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

enum class EndReason { COMPLETE, LEFT, TIMEOUT, DEATH, LOGOUT, ADMIN }

/**
 * A quest's private copy of a map area for one player — a scripted fight, a cutscene room, a
 * trial ground — over [RaidInstance]. Spawn npcs with [spawnNpc] (source-area coordinates,
 * translated; loot-less; optionally renamed); [end] is idempotent and safe to call from any
 * path (death/logout teardown never double-teleports: the engine already moved the player).
 */
class QuestInstance internal constructor(
    val owner: Player,
    val instance: RaidInstance,
    val sourceArea: Area,
    val exit: Tile,
    val timeoutTicks: Int,
    private val onTick: ((QuestInstance) -> Unit)?,
    private val onEnd: ((QuestInstance, EndReason) -> Unit)?,
) {
    val npcs = ArrayList<Npc>()
    var ticks = 0
        internal set
    var ended = false
        private set
    var endReason: EndReason? = null
        private set

    val world: World get() = owner.world

    fun translate(src: Tile): Tile = instance.translate(src)

    fun contains(t: Tile): Boolean = instance.contains(t)

    /** Spawn [npcKey] at source tile [src] inside the instance. Loot-less; [name] overrides the display name. */
    fun spawnNpc(npcKey: String, src: Tile, name: String? = null, respawns: Boolean = false): Npc? {
        if (ended) return null
        return runCatching {
            val npc = Npc(getRSCM(npcKey), translate(src), world)
            npc.respawns = respawns
            npc.attr[NO_LOOT_ATTR] = true
            world.spawn(npc)
            npc.setActive(true)
            if (name != null) WarNpcNames.rename(npc, name)
            npcs += npc
            npc
        }.onFailure { logger.error(it) { "QuestInstance: could not spawn '$npcKey' for ${owner.username}" } }.getOrNull()
    }

    fun end(reason: EndReason) {
        if (ended) return
        ended = true
        endReason = reason
        npcs.forEach { n -> if (n.index >= 0 && world.npcs.contains(n)) { n.setCurrentHp(0); world.remove(n) } }
        npcs.clear()
        // DEATH: PlayerDeathAction already sent the owner to the instance exit and told the
        // allocator. LOGOUT: the allocator's logout hook did. Everything else: we move them out.
        if (reason != EndReason.DEATH && reason != EndReason.LOGOUT && owner.index >= 0 && contains(owner.tile)) {
            owner.moveTo(exit)
        }
        QuestInstances.forget(this)
        runCatching { onEnd?.invoke(this, reason) }.onFailure { logger.error(it) { "QuestInstance onEnd threw (${owner.username}, $reason)" } }
    }

    internal fun tick() {
        if (ended) return
        ticks++
        runCatching { onTick?.invoke(this) }.onFailure { logger.error(it) { "QuestInstance onTick threw (${owner.username})" } }
    }
}

/**
 * Registry + lifecycle of every live [QuestInstance] (one per owner). Enter with [enter]; the
 * world sweep ([tick]) ends instances whose owner left, died, logged out or timed out; a
 * [CompanionPolicy] rule benches the owner's companion for the duration.
 */
object QuestInstances {

    private val live = HashMap<Any, QuestInstance>()

    init {
        CompanionPolicy.register { owner, _ -> if (of(owner) != null) CompanionPolicy.Verdict("this trial is yours alone") else null }
    }

    private fun keyOf(p: Player): Any = (p.uid.value as? String)?.lowercase() ?: p.uid.value

    fun of(p: Player): QuestInstance? = live[keyOf(p)]

    fun liveCount(): Int = live.size

    /**
     * Allocate a private copy of [sourceArea] (chunk-aligned, [levels] planes), move [p] to
     * [landing] (source coordinates) and start ticking. A previous instance of theirs ends first.
     * Returns null (with a message) when the instance space is full.
     */
    fun enter(
        p: Player,
        sourceArea: Area,
        exit: Tile,
        landing: Tile,
        levels: Int = 1,
        timeoutTicks: Int = 0,
        onTick: ((QuestInstance) -> Unit)? = null,
        onEnd: ((QuestInstance, EndReason) -> Unit)? = null,
    ): QuestInstance? {
        of(p)?.end(EndReason.LEFT)
        val raid = RaidInstance.allocate(p.world, sourceArea, exit, p.uid, levels)
        if (raid == null) {
            p.message("The instance space is full right now — try again shortly.")
            return null
        }
        val qi = QuestInstance(p, raid, sourceArea, exit, timeoutTicks, onTick, onEnd)
        live[keyOf(p)] = qi
        p.moveTo(raid.translate(landing))
        return qi
    }

    internal fun forget(qi: QuestInstance) {
        val key = keyOf(qi.owner)
        if (live[key] === qi) live.remove(key)
    }

    /** The world sweep (every tick from [QuestFrameworkPlugin]). */
    fun tick(world: World) {
        if (live.isEmpty()) return
        for (qi in live.values.toList()) {
            val owner = qi.owner
            when {
                owner.index < 0 || !owner.isOnline -> qi.end(EndReason.LOGOUT)
                !qi.contains(owner.tile) -> qi.end(EndReason.LEFT)
                qi.timeoutTicks > 0 && qi.ticks >= qi.timeoutTicks -> {
                    owner.message("<col=801700>Time is up — the trial ground closes.</col>")
                    qi.end(EndReason.TIMEOUT)
                }
                else -> qi.tick()
            }
        }
    }

    fun onDeath(p: Player) { of(p)?.end(EndReason.DEATH) }

    fun onLogout(p: Player) { of(p)?.end(EndReason.LOGOUT) }
}
