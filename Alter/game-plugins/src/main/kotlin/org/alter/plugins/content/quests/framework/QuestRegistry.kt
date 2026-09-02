package org.alter.plugins.content.quests.framework

import org.alter.game.model.entity.Player

/**
 * The one shape every quest presents to the rest of the game — the six LEGACY step-machine
 * chains (via [LegacyChains]) and every framework [QuestDefinition] (via [FrameworkChain]) alike:
 * started / complete / objective line, plus the journal publish and login hooks the framework
 * plugin drives. Block-2 briefs ask [QuestRegistry.isComplete] and never touch a chain directly.
 */
interface QuestChain {
    val key: String
    val displayName: String

    /** The client Quest Journal chain slot (`LofQuest.CHAIN` order), or null if not listed there. */
    val chainIndex: Int?

    /** A side road: never the "next up" pick while a main-road quest is unstarted. */
    val optional: Boolean get() = false

    /** Never focused by `::quests` (admin/demo content). */
    val hidden: Boolean get() = false

    fun started(p: Player): Boolean
    fun complete(p: Player): Boolean
    fun objectiveLine(p: Player): String

    /** Publish this quest's state to the client journal varps (only writes on change). */
    fun publish(p: Player) {}

    fun resumeOnLogin(p: Player) {}
    fun beginIfEligible(p: Player) {}
}

/** [QuestChain] view of a framework [QuestDefinition], driven by [QuestEngine]. */
class FrameworkChain(val def: QuestDefinition) : QuestChain {
    override val key: String get() = def.key
    override val displayName: String get() = def.displayName
    override val chainIndex: Int? get() = def.chainIndex
    override val optional: Boolean get() = def.optional
    override val hidden: Boolean get() = def.adminOnly
    override fun started(p: Player): Boolean = QuestEngine.started(p, def)
    override fun complete(p: Player): Boolean = QuestEngine.isComplete(p, def)
    override fun objectiveLine(p: Player): String = QuestEngine.objectiveLine(p, def)
    override fun publish(p: Player) = QuestEngine.publish(p, def)
    override fun resumeOnLogin(p: Player) = QuestEngine.resume(p, def)
    override fun beginIfEligible(p: Player) = QuestEngine.beginIfEligible(p, def)
}

/**
 * Every quest in the game, by key. Static and order-free: the legacy chains register themselves
 * when this object loads; framework quests register from their plugin's init. `QuestJournal.sync`
 * publishes through [all]; `QuestBookPlugin` focuses through [activeChainIndex].
 */
object QuestRegistry {

    private val chains = LinkedHashMap<String, QuestChain>()
    private val framework = LinkedHashMap<String, QuestDefinition>()

    init {
        LegacyChains.all.forEach { register(it) }
    }

    fun register(chain: QuestChain) {
        require(!chains.containsKey(chain.key)) { "Quest key '${chain.key}' is already registered." }
        chains[chain.key] = chain
    }

    fun register(def: QuestDefinition) {
        framework[def.key] = def
        register(FrameworkChain(def))
    }

    fun all(): Collection<QuestChain> = chains.values
    fun frameworkQuests(): Collection<QuestDefinition> = framework.values
    fun byKey(key: String): QuestChain? = chains[key]
    fun definition(key: String): QuestDefinition? = framework[key]

    val legacyCount: Int get() = chains.size - framework.size
    val frameworkCount: Int get() = framework.size

    /** True if the quest with [key] (legacy or framework) is complete for [p]; unknown keys are false. */
    fun isComplete(p: Player, key: String): Boolean = byKey(key)?.complete(p) ?: false

    /**
     * The chain slot `::quests` should open on: the deepest in-progress listed quest, else the
     * first unstarted main-road quest, else the first unstarted quest of any kind, else slot 0.
     * (One documented divergence from the old hand-written chain: a player past War-Prep III who
     * is not yet King is focused on King of Lumbridge, matching the client's own pick.)
     */
    fun activeChainIndex(p: Player): Int {
        val listed = chains.values.filter { it.chainIndex != null && !it.hidden }.sortedBy { it.chainIndex }
        listed.filter { it.started(p) && !it.complete(p) }.maxByOrNull { it.chainIndex!! }?.let { return it.chainIndex!! }
        listed.firstOrNull { !it.started(p) && !it.optional }?.let { return it.chainIndex!! }
        listed.firstOrNull { !it.started(p) }?.let { return it.chainIndex!! }
        return 0
    }
}
