package org.alter.plugins.content.quests.framework

import dev.openrune.cache.CacheManager.getNpc
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.CHATBOX_CHILD
import org.alter.api.ext.chatNpc
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.model.queue.TaskPriority
import org.alter.game.plugin.KotlinPlugin
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/** A dialogue script: runs inside the player's queue (the `chatNpc`/`options` coroutine world). */
typealias TalkScript = suspend QueueTask.(Player) -> Unit

/**
 * **NPC dialogue registry** — several systems can own an NPC's "Talk-to" without editing each
 * other: each registers a [Branch] at a priority; on click the branches are asked in descending
 * priority order and the first that **claims** the conversation (returns a script) runs. Quests
 * register at [PRIORITY_QUEST] (via `QuestDefinition.talk`), an NPC's everyday chatter at
 * [PRIORITY_DEFAULT], and [placeholder] lines at the bottom so an NPC is never mute.
 *
 * Bind the click itself with [bindTalk] — the one defensive binder (cache option pre-checked,
 * deduplicated) — instead of a raw `onNpcOption`, which throws at construction (and drops the
 * whole plugin) when the cache npc lacks the option.
 */
object NpcTalk {

    const val PRIORITY_QUEST = 100
    const val PRIORITY_DEFAULT = 0
    const val PRIORITY_PLACEHOLDER = -1000

    fun interface Branch {
        /** The script to run for [p], or null to pass to the next branch. Must not throw. */
        fun claim(p: Player): TalkScript?
    }

    private class Entry(val priority: Int, val branch: Branch)

    private val byNpc = HashMap<Int, MutableList<Entry>>()

    /** Register a branch on [npcKey]. Returns false (and logs) if the key doesn't resolve. */
    fun register(npcKey: String, priority: Int = PRIORITY_DEFAULT, branch: Branch): Boolean {
        val id = runCatching { getRSCM(npcKey) }.getOrNull() ?: run {
            logger.warn { "NpcTalk: npc key '$npcKey' does not resolve — branch not registered." }
            return false
        }
        val list = byNpc.getOrPut(id) { ArrayList() }
        list += Entry(priority, branch)
        list.sortByDescending { it.priority }
        return true
    }

    /** Bottom-priority fallback lines so an NPC with no live branch still speaks. */
    fun placeholder(npcKey: String, title: String, vararg lines: String): Boolean =
        register(npcKey, PRIORITY_PLACEHOLDER) { _ ->
            { p ->
                val id = getRSCM(npcKey)
                for (line in lines) chatNpc(p, line, npc = id, title = title)
            }
        }

    /** The chat-box slot every `chatNpc` / `chatPlayer` / `options` / `messageBox` box opens on. */
    private const val CHATBOX_PARENT = 162

    /** True while a dialogue box is on [p]'s screen — the player is mid-conversation. */
    fun inDialogue(p: Player): Boolean = p.interfaces.isOccupied(CHATBOX_PARENT, CHATBOX_CHILD)

    /**
     * Route a click on [npcId] for [p]: run the first claiming branch. False = nobody claimed.
     *
     * A click while a dialogue box is already open REPLACES that conversation (OSRS: any new
     * interaction closes the box) instead of stacking in front of it. The pawn queue is LIFO and a
     * chat box does not count as an open "menu", so a STANDARD task would play immediately and the
     * interrupted scene would resume afterwards — which is how The North's debrief once ran straight
     * into First Reclamation's brief. STRONG terminates the queued tasks first; each interrupted
     * dialogue closes its own box through its `terminateAction`.
     */
    fun talk(p: Player, npcId: Int): Boolean {
        val entries = byNpc[npcId] ?: return false
        var chosen: TalkScript? = null
        var chosenPriority = 0
        for (e in entries) {
            val script = runCatching { e.branch.claim(p) }
                .onFailure { logger.error(it) { "NpcTalk branch threw for npc $npcId / ${p.username}" } }
                .getOrNull() ?: continue
            if (chosen == null) {
                chosen = script
                chosenPriority = e.priority
                // Only keep scanning to detect a second quest-level claimant (below).
                if (e.priority < PRIORITY_QUEST) break
                continue
            }
            // Two quest-level branches wanted the same click. The loser is invisible to the player
            // (two live quests on one NPC, or a tie broken by plugin load order) — say so in the log.
            if (e.priority >= PRIORITY_QUEST) {
                logger.debug { "NpcTalk: npc $npcId / ${p.username}: branch @${e.priority} also claimed; @$chosenPriority ran." }
            }
            break
        }
        val script = chosen ?: return false
        val priority = if (inDialogue(p)) TaskPriority.STRONG else TaskPriority.STANDARD
        p.queue(priority) { script.invoke(this, p) }
        return true
    }

    /**
     * Run [npcId]'s everyday branch (priority ≤ [PRIORITY_DEFAULT]) INLINE from inside another
     * script — a quest's one-line reminder followed by the NPC's normal menu, so a live story step
     * never hides what the NPC does for a living. No-op if no such branch claims.
     */
    suspend fun runDefault(task: QueueTask, p: Player, npcId: Int) {
        val entries = byNpc[npcId] ?: return
        for (e in entries) {
            if (e.priority > PRIORITY_DEFAULT) continue
            val script = runCatching { e.branch.claim(p) }
                .onFailure { logger.error(it) { "NpcTalk default branch threw for npc $npcId / ${p.username}" } }
                .getOrNull() ?: continue
            script.invoke(task, p)
            return
        }
    }

    fun bound(npcId: Int): Boolean = byNpc.containsKey(npcId)
}

/** Npc ids whose Talk-to click is already routed to [NpcTalk] (duplicate binds throw). */
private val talkBound = HashSet<Int>()

/**
 * Bind [npcKey]'s **Talk-to** click to the [NpcTalk] router — defensively: an unresolvable key or
 * a cache npc without the option logs a warning and returns false instead of throwing (a raw
 * `onNpcOption` would drop the whole plugin), and a second call for the same npc is a no-op.
 * Register branches with [NpcTalk.register] / `QuestDefinition.talk` separately, in any order.
 */
fun KotlinPlugin.bindTalk(npcKey: String): Boolean {
    val id = runCatching { getRSCM(npcKey) }.getOrNull() ?: run {
        logger.warn { "bindTalk: npc key '$npcKey' does not resolve — not bound." }
        return false
    }
    if (id in talkBound) return true
    val actions = runCatching { getNpc(id).actions.filterNotNull().filter { it.isNotBlank() } }.getOrDefault(emptyList())
    val talk = actions.firstOrNull { it.equals("Talk-to", ignoreCase = true) } ?: run {
        logger.warn { "bindTalk: '$npcKey' has no Talk-to option in cache (options=$actions) — not bound." }
        return false
    }
    talkBound += id
    onNpcOption(npcKey, option = talk) {
        // Route by the BOUND id, never `npc.id`: an npc-option plugin runs with the clicking
        // player as its context, so the `Plugin.npc` accessor (`ctx as Npc`) throws a
        // ClassCastException here — which is exactly how the Sergeant went mute after PR-9
        // (the engine swallowed the throw and the click did nothing). The binding is per npc
        // id anyway, so the id is the right key.
        if (!NpcTalk.talk(player, id)) player.message("They have nothing to say to you right now.")
    }
    return true
}
