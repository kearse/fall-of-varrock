package org.alter.plugins.content.drops

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

/**
 * The real-OSRS NPC drop registry (the content faucet, growth roadmap Phase 4 — the
 * everyday-monster counterpart to the bespoke boss [org.alter.plugins.content.bosses.DropTable]s).
 *
 * Data is generated from the open **osrsbox** monster dataset into `data/cfg/drops/npc_drops.json`
 * and mirrors real OSRS drop tables and rates. Each [DropRow] is an independent Bernoulli roll at
 * its real drop [rarity] (osrsbox already flattens OSRS weighted tables into per-item probabilities,
 * so expected per-item rates match the live game).
 *
 *  - [byId]   — the table keyed by the exact OSRS/cache npc id (the common, precise path).
 *  - [byName] — a lowercased-name → representative-npc-id fallback so *every* variant id of a
 *               monster (e.g. the many Rat / Goblin ids) resolves to a table even when that
 *               specific id isn't in the dataset.
 *
 * Pure data + [tableFor]; the owning [NpcDropPlugin] resolves items, applies the economy dials,
 * and spawns the loot.
 */
object NpcDropTables {
    private val logger = KotlinLogging.logger {}

    /** One real OSRS drop: [itemId] at [rarity] (0..1), a [min]..[max] stack, rolled [rolls] times. */
    class DropRow(val itemId: Int, val rarity: Double, val min: Int, val max: Int, val rolls: Int)

    private val byId = HashMap<Int, List<DropRow>>()
    private val byName = HashMap<String, Int>()

    @Volatile
    var loaded = false
        private set

    /** Number of npc ids with a table (for boot logging). */
    val size: Int get() = byId.size

    fun load(path: String = "../data/cfg/drops/npc_drops.json") {
        if (loaded) return
        val file = File(path)
        if (!file.exists()) {
            logger.warn { "NPC drop data not found at ${file.absolutePath} — generic drops disabled." }
            loaded = true
            return
        }
        val root = ObjectMapper().readTree(file)

        val idNode = root.get("byId")
        idNode?.fields()?.forEach { (key, rowsNode) ->
            val npcId = key.toIntOrNull() ?: return@forEach
            val rows = ArrayList<DropRow>(rowsNode.size())
            rowsNode.forEach { r ->
                // [itemId, rarity, qmin, qmax, rolls]
                if (r.size() >= 4) {
                    rows.add(
                        DropRow(
                            itemId = r.get(0).asInt(),
                            rarity = r.get(1).asDouble(),
                            min = r.get(2).asInt(),
                            max = r.get(3).asInt(),
                            rolls = if (r.size() >= 5) r.get(4).asInt(1) else 1,
                        ),
                    )
                }
            }
            if (rows.isNotEmpty()) byId[npcId] = rows
        }

        val nameNode = root.get("byName")
        nameNode?.fields()?.forEach { (name, idValue) ->
            byName[name.lowercase()] = idValue.asInt()
        }

        loaded = true
        logger.info { "Loaded real-OSRS drop tables for ${byId.size} npcs (${byName.size} name aliases)." }
    }

    /**
     * The drop table for [npcId], or null if none. Tries the exact id first, then falls back to
     * matching the monster's [name] (lazily supplied so the cache lookup is only paid on a miss).
     */
    fun tableFor(npcId: Int, name: () -> String?): List<DropRow>? {
        byId[npcId]?.let { return it }
        val nm = name()?.lowercase()?.trim()?.ifEmpty { null } ?: return null
        val repId = byName[nm] ?: return null
        return byId[repId]
    }
}

/**
 * Runtime dials for the generic drop system, loaded from `data/cfg/drops/config.yml`. Lets the
 * economy be tuned (faucet width, exclusions) without regenerating drop data or recompiling.
 */
object NpcDropConfig {
    private val logger = KotlinLogging.logger {}

    var enabled = true
        private set
    var coinMultiplier = 1.0
        private set
    var quantityMultiplier = 1.0
        private set
    var excludeIds: Set<Int> = emptySet()
        private set

    /** ITEM ids never dropped by the generic handler, whatever table lists them. */
    var excludeItemIds: Set<Int> = emptySet()
        private set

    fun load(path: String = "../data/cfg/drops/config.yml") {
        val file = File(path)
        if (!file.exists()) {
            logger.warn { "Drop config not found at ${file.absolutePath} — using authentic defaults." }
            return
        }
        val node = YAMLMapper().readTree(file)
        node.get("enabled")?.let { enabled = it.asBoolean(true) }
        node.get("coinMultiplier")?.let { coinMultiplier = it.asDouble(1.0) }
        node.get("quantityMultiplier")?.let { quantityMultiplier = it.asDouble(1.0) }
        node.get("excludeIds")?.let { arr ->
            excludeIds = arr.mapNotNull { it.asInt() }.toSet()
        }
        node.get("excludeItemIds")?.let { arr ->
            excludeItemIds = arr.mapNotNull { it.asInt() }.toSet()
        }
        logger.info {
            "Drop config: enabled=$enabled coinMultiplier=$coinMultiplier " +
                "quantityMultiplier=$quantityMultiplier excludeIds=${excludeIds.size} excludeItemIds=${excludeItemIds.size}"
        }
    }
}
