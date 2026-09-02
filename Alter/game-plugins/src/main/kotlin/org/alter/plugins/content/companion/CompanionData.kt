package org.alter.plugins.content.companion

import org.alter.game.model.skill.SkillSet
import org.bson.Document

/** One stored item (id + amount) at a container slot. */
class CompItem(val id: Int, val amount: Int)

/**
 * The persistent state of one companion: archetype, per-skill XP, worn gear, carried supplies, and
 * whether he is fielded or off duty ([dismissed]). Serialized to/from a bson [Document] (the same
 * primitive the player save system uses for items), then the whole roster is one JSON blob on
 * `COMPANIONS_ATTR`.
 *
 * Decoding never throws — a malformed blob yields an empty roster — so a bad save can't brick login.
 * A legacy `dead` flag (the removed revive-at-Zo path) decodes as [dismissed]: nothing is lost, he
 * is simply summonable again.
 */
class CompanionData(
    val archetype: CompanionStyle,
    val name: String,
    val skillXp: MutableMap<Int, Double>,
    val equipment: MutableMap<Int, CompItem>,
    val inventory: MutableMap<Int, CompItem>,
    var autoLoot: Boolean = false,
    /**
     * Off duty: he stays on the roster (levels + gear intact) but is NOT spawned — not on login,
     * not until the owner summons him. Only ONE companion is ever fielded
     * ([CompanionRegistry.ACTIVE_MAX]), so every other roster entry lives here.
     */
    var dismissed: Boolean = false,
    /** Saved attack-style varp (0-3); -1 = use the spawn default ([org.alter.plugins.content.bots.BotBrain.configureStyle]). */
    var attackStyle: Int = -1,
    /** Whether the companion auto-retaliates (panel toggle). */
    var autoRetaliate: Boolean = true,
    /** Owner-chosen autocast spell enum name, or null = auto-scale by Magic level. */
    var autocast: String? = null,
    /** Remembered ammo (arrow) item id for bank auto-restock; -1 = none. */
    var lastAmmoId: Int = -1,
) {
    fun toDocument(): Document = Document("archetype", archetype.name).apply {
        append("name", name)
        append("autoLoot", autoLoot)
        append("dismissed", dismissed)
        append("attackStyle", attackStyle)
        append("autoRetaliate", autoRetaliate)
        if (autocast != null) append("autocast", autocast)
        append("lastAmmoId", lastAmmoId)
        append("skills", Document().also { d -> skillXp.forEach { (k, v) -> d.append(k.toString(), v) } })
        append("equipment", itemsDoc(equipment))
        append("inventory", itemsDoc(inventory))
    }

    companion object {
        // Skill indices (mirror BotManager / Skills).
        const val ATTACK = 0; const val DEFENCE = 1; const val STRENGTH = 2
        const val HITPOINTS = 3; const val RANGED = 4; const val PRAYER = 5; const val MAGIC = 6

        /** A freshly recruited companion: combat skills at level 1, Hitpoints at 10, no gear/supplies. */
        fun fresh(archetype: CompanionStyle, taken: Collection<String> = emptyList()): CompanionData {
            val xp = mutableMapOf(
                ATTACK to 0.0, STRENGTH to 0.0, DEFENCE to 0.0, RANGED to 0.0, MAGIC to 0.0, PRAYER to 0.0,
                HITPOINTS to SkillSet.getXpForLevel(10),
            )
            return CompanionData(archetype, CompanionNames.pick(taken), xp, mutableMapOf(), mutableMapOf())
        }

        private fun fromDocument(doc: Document): CompanionData {
            val archetype = runCatching { CompanionStyle.valueOf(doc.getString("archetype")) }
                .getOrDefault(CompanionStyle.MELEE)
            val name = doc.getString("name") ?: CompanionNames.pick()
            val skills = HashMap<Int, Double>()
            doc.get("skills", Document::class.java)?.forEach { (k, v) -> skills[k.toInt()] = (v as Number).toDouble() }
            return CompanionData(
                archetype, name, skills, itemsFrom(doc, "equipment"), itemsFrom(doc, "inventory"),
                doc.getBoolean("autoLoot", false),
                // A pre-Block-1 "slain" companion simply comes back benched — no revive fee any more.
                dismissed = doc.getBoolean("dismissed", false) || doc.getBoolean("dead", false),
                attackStyle = doc.getInteger("attackStyle", -1),
                autoRetaliate = doc.getBoolean("autoRetaliate", true),
                autocast = doc.getString("autocast"),
                lastAmmoId = doc.getInteger("lastAmmoId", -1),
            )
        }

        /** Serialize a roster (fielded + benched: dismissed/dead) to the JSON blob stored on the owner. */
        fun rosterToBlob(list: List<CompanionData>): String =
            Document("list", list.map { it.toDocument() }).toJson()

        /** Parse a roster blob; null/garbage → empty list (never throws). */
        fun rosterFromBlob(blob: String?): List<CompanionData> {
            if (blob.isNullOrBlank()) return emptyList()
            return runCatching {
                Document.parse(blob).getList("list", Document::class.java).map { fromDocument(it) }
            }.getOrDefault(emptyList())
        }

        private fun itemsDoc(items: Map<Int, CompItem>): Document = Document().also { d ->
            items.forEach { (slot, it) -> d.append(slot.toString(), Document("id", it.id).append("amount", it.amount)) }
        }

        private fun itemsFrom(doc: Document, key: String): MutableMap<Int, CompItem> {
            val out = HashMap<Int, CompItem>()
            doc.get(key, Document::class.java)?.forEach { (slot, v) ->
                val d = v as Document
                out[slot.toInt()] = CompItem(d.getInteger("id"), d.getInteger("amount", 1))
            }
            return out
        }
    }
}
