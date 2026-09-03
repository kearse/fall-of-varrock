package org.alter.plugins.content.hostilezones

import org.alter.game.model.attr.HOSTILE_EXTRACTIONS_ATTR
import org.alter.game.model.entity.Player
import org.bson.Document

/**
 * A player's extraction record — persisted as a JSON blob on [HOSTILE_EXTRACTIONS_ATTR] (the
 * loot-key / companion pattern: bson [Document], decode never throws). No reward is minted from
 * it today; achievements / titles can read it later.
 */
object ExtractionRecords {

    class Record(var count: Int = 0, var best: Long = 0L, var bestZone: String = "", val byZone: MutableMap<String, Int> = HashMap()) {
        fun toJson(): String = Document("count", count)
            .append("best", best)
            .append("bestZone", bestZone)
            .append("byZone", Document(byZone.mapValues { it.value as Any }))
            .toJson()
    }

    fun load(p: Player): Record {
        val blob = p.attr[HOSTILE_EXTRACTIONS_ATTR] ?: return Record()
        return runCatching {
            val d = Document.parse(blob)
            val byZone = HashMap<String, Int>()
            d.get("byZone", Document::class.java)?.forEach { (k, v) -> byZone[k] = (v as? Number)?.toInt() ?: 0 }
            Record(
                count = d.getInteger("count", 0),
                best = (d.get("best") as? Number)?.toLong() ?: 0L,
                bestZone = d.getString("bestZone") ?: "",
                byZone = byZone,
            )
        }.getOrDefault(Record())
    }

    /** Book one successful extraction from [zoneKey] worth [haul] gp. Returns the updated record. */
    fun record(p: Player, zoneKey: String, haul: Long): Record {
        val r = load(p)
        r.count += 1
        r.byZone[zoneKey] = (r.byZone[zoneKey] ?: 0) + 1
        if (haul > r.best) {
            r.best = haul
            r.bestZone = zoneKey
        }
        p.attr[HOSTILE_EXTRACTIONS_ATTR] = r.toJson()
        return r
    }
}
