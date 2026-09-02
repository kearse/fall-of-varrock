package org.alter.plugins.content.quests.framework

import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player
import org.bson.Document

/**
 * One framework quest's persistent state: the current **step id** (string — never an ordinal, so
 * steps can be inserted or renamed without a save migration), per-step counters ("kills"), and
 * the completion flag with timestamps.
 */
class QuestState(
    var step: String = "",
    var complete: Boolean = false,
    val counters: MutableMap<String, Int> = HashMap(),
    var startedAt: Long = 0L,
    var completedAt: Long = 0L,
) {
    fun toDocument(): Document = Document("step", step)
        .append("complete", complete)
        .append("startedAt", startedAt)
        .append("completedAt", completedAt)
        .append("counters", Document().also { d -> counters.forEach { (k, v) -> d.append(k, v) } })

    companion object {
        fun fromDocument(d: Document): QuestState {
            val counters = HashMap<String, Int>()
            d.get("counters", Document::class.java)?.forEach { (k, v) -> counters[k] = (v as Number).toInt() }
            return QuestState(
                step = d.getString("step") ?: "",
                complete = d.getBoolean("complete", false),
                counters = counters,
                startedAt = d.getLong("startedAt") ?: 0L,
                completedAt = d.getLong("completedAt") ?: 0L,
            )
        }
    }
}

/**
 * All framework quest states for a player live in ONE JSON blob on [QUEST_STATES_ATTR] (the
 * `CompanionData` bson pattern), keyed by quest key. A session cache ([CACHE]) avoids re-parsing;
 * every mutation goes through [save]. Decoding never throws — a malformed blob reads as "no
 * quests started".
 */
object QuestStates {

    val QUEST_STATES_ATTR = AttributeKey<String>("quest_states")
    private val CACHE = AttributeKey<MutableMap<String, QuestState>>()

    fun all(p: Player): MutableMap<String, QuestState> {
        p.attr[CACHE]?.let { return it }
        val m = decode(p.attr[QUEST_STATES_ATTR])
        p.attr[CACHE] = m
        return m
    }

    fun of(p: Player, key: String): QuestState? = all(p)[key]

    fun getOrCreate(p: Player, key: String): QuestState = all(p).getOrPut(key) { QuestState() }

    fun save(p: Player) {
        p.attr[QUEST_STATES_ATTR] = encode(all(p))
    }

    fun remove(p: Player, key: String) {
        all(p).remove(key)
        save(p)
    }

    private fun decode(blob: String?): MutableMap<String, QuestState> {
        val out = LinkedHashMap<String, QuestState>()
        if (blob.isNullOrBlank()) return out
        runCatching {
            Document.parse(blob).get("quests", Document::class.java)?.forEach { (k, v) ->
                if (v is Document) out[k] = QuestState.fromDocument(v)
            }
        }
        return out
    }

    private fun encode(m: Map<String, QuestState>): String =
        Document("quests", Document().also { d -> m.forEach { (k, s) -> d.append(k, s.toDocument()) } }).toJson()
}
