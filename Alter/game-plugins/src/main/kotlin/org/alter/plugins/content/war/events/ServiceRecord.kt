package org.alter.plugins.content.war.events

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player
import org.alter.plugins.content.war.CampaignOp
import org.alter.plugins.content.war.CampaignTier
import org.bson.Document

private val logger = KotlinLogging.logger {}

/**
 * A player's **service ledger** — what they have actually done for the kingdom, per operation
 * kind, persisted for life. This is the contribution record quests, achievements and rank
 * eligibility read after the fact ("did this player meaningfully fight in a major assault?" —
 * the Veteran of Varrock question, design authority §5), which the runtime-only
 * `CampaignDirector.participation` map cannot answer once the payout has run.
 *
 * Counts are per tier (fought = credited as a participant, whatever the outcome; won = the op
 * was a victory). [lastShare] / [lastWon] remember the player's most recent contribution share
 * (percent of the op's participation) and outcome per op key (`"march:goblin_camp"`,
 * `"operation:bandit_hideout"`, `"campaign:varrock"`, `"conquest:varrock"`). [suppliesContributed]
 * is the lifetime Realm Supplies value the player has handed in.
 *
 * Serialized as one bson [Document] JSON blob on [ServiceRecords.SERVICE_RECORD_ATTR] (the
 * `CompanionData` pattern). Decoding never throws — a malformed blob yields an empty record.
 */
class ServiceRecord(
    var marchesFought: Int = 0,
    var marchesWon: Int = 0,
    var operationsFought: Int = 0,
    var operationsWon: Int = 0,
    var campaignsFought: Int = 0,
    var campaignsWon: Int = 0,
    var conquestsFought: Int = 0,
    var conquestsWon: Int = 0,
    var suppliesContributed: Int = 0,
    val lastShare: MutableMap<String, Int> = HashMap(),
    val lastWon: MutableMap<String, Boolean> = HashMap(),
) {
    /** Total operations of every kind this player has been credited for. */
    val fought: Int get() = marchesFought + operationsFought + campaignsFought + conquestsFought
    val won: Int get() = marchesWon + operationsWon + campaignsWon + conquestsWon

    fun toDocument(): Document = Document().apply {
        append("marchesFought", marchesFought); append("marchesWon", marchesWon)
        append("operationsFought", operationsFought); append("operationsWon", operationsWon)
        append("campaignsFought", campaignsFought); append("campaignsWon", campaignsWon)
        append("conquestsFought", conquestsFought); append("conquestsWon", conquestsWon)
        append("suppliesContributed", suppliesContributed)
        append("lastShare", Document().also { d -> lastShare.forEach { (k, v) -> d.append(k, v) } })
        append("lastWon", Document().also { d -> lastWon.forEach { (k, v) -> d.append(k, v) } })
    }

    companion object {
        fun fromDocument(doc: Document): ServiceRecord {
            fun int(key: String) = (doc.get(key) as? Number)?.toInt()?.coerceAtLeast(0) ?: 0
            val rec = ServiceRecord(
                int("marchesFought"), int("marchesWon"), int("operationsFought"), int("operationsWon"),
                int("campaignsFought"), int("campaignsWon"), int("conquestsFought"), int("conquestsWon"),
                int("suppliesContributed"),
            )
            doc.get("lastShare", Document::class.java)?.forEach { (k, v) -> (v as? Number)?.let { rec.lastShare[k] = it.toInt() } }
            doc.get("lastWon", Document::class.java)?.forEach { (k, v) -> (v as? Boolean)?.let { rec.lastWon[k] = it } }
            return rec
        }
    }
}

/**
 * Owner of the per-player [ServiceRecord] blob: decode/encode, the write hooks the war and the
 * Supply Depot call, and the read API for quests/achievements.
 */
object ServiceRecords {

    /** Persistent (the key string is what the save layer matches on — keep it stable). */
    val SERVICE_RECORD_ATTR = AttributeKey<String>("service_record")

    /** Session-only decoded cache so hot paths don't re-parse JSON. */
    private val CACHE = AttributeKey<ServiceRecord>()

    /** The op key a contribution is filed under. A Lord-sponsored MARCH-tier column is an
     *  "operation"; the realm's own march is a "march". */
    fun opKey(tier: CampaignTier, op: CampaignOp, sponsored: Boolean): String {
        val kind = when {
            tier == CampaignTier.MARCH && sponsored -> "operation"
            tier == CampaignTier.GRAND_MARCH -> "march"
            else -> tier.name.lowercase()
        }
        return "$kind:${op.cityKey}"
    }

    fun of(p: Player): ServiceRecord {
        p.attr[CACHE]?.let { return it }
        val rec = decode(p.attr[SERVICE_RECORD_ATTR])
        p.attr[CACHE] = rec
        return rec
    }

    fun save(p: Player, rec: ServiceRecord) {
        p.attr[CACHE] = rec
        p.attr[SERVICE_RECORD_ATTR] = rec.toDocument().toJson()
    }

    /** Decode a blob; garbage → an empty record (never throws). */
    fun decode(blob: String?): ServiceRecord {
        if (blob.isNullOrBlank()) return ServiceRecord()
        return runCatching { ServiceRecord.fromDocument(Document.parse(blob)) }
            .onFailure { logger.warn(it) { "Malformed service record blob — starting an empty record." } }
            .getOrDefault(ServiceRecord())
    }

    /**
     * Credit [p] for an operation: bumps the tier's fought/won counters and remembers the share.
     * [sharePct] is the player's slice of the op's participation (0-100); a sponsor who never
     * swung a sword is still credited with a 0% share.
     */
    fun recordOp(p: Player, tier: CampaignTier, op: CampaignOp, sponsored: Boolean, sharePct: Int, won: Boolean) {
        val rec = of(p)
        val key = opKey(tier, op, sponsored)
        when (key.substringBefore(':')) {
            "march" -> { rec.marchesFought++; if (won) rec.marchesWon++ }
            "operation" -> { rec.operationsFought++; if (won) rec.operationsWon++ }
            "campaign" -> { rec.campaignsFought++; if (won) rec.campaignsWon++ }
            "conquest" -> { rec.conquestsFought++; if (won) rec.conquestsWon++ }
            else -> return // RAID parties are boss support, not a service credit
        }
        rec.lastShare[key] = sharePct.coerceIn(0, 100)
        rec.lastWon[key] = won
        save(p, rec)
    }

    /** A depot hand-in worth [amount] Realm Supplies. */
    fun recordSupplies(p: Player, amount: Int) {
        if (amount <= 0) return
        val rec = of(p)
        rec.suppliesContributed += amount
        save(p, rec)
    }

    /**
     * True if [p]'s most recent credit for [opKey] (e.g. `"campaign:varrock"`) was a WIN with at
     * least [minShare]% of the participation — the "meaningful participation" check.
     */
    fun didParticipate(p: Player, opKey: String, minShare: Int = 1, mustWin: Boolean = true): Boolean {
        val rec = of(p)
        val share = rec.lastShare[opKey] ?: return false
        if (share < minShare) return false
        return !mustWin || (rec.lastWon[opKey] == true)
    }

    /** The `::service` readout. */
    fun statusLines(p: Player): List<String> {
        val r = of(p)
        return listOf(
            "<col=801700>Your service to the kingdom:</col>",
            "  Marches: ${r.marchesWon}/${r.marchesFought} won · Operations: ${r.operationsWon}/${r.operationsFought} · Campaigns: ${r.campaignsWon}/${r.campaignsFought} · Conquests: ${r.conquestsWon}/${r.conquestsFought}",
            "  Realm Supplies handed in: <col=4f9b4f>${"%,d".format(r.suppliesContributed)}</col>",
            "  Lifetime War Effort: <col=4f9b4f>${"%,d".format(org.alter.plugins.content.economy.PointKind.WAR_EFFORT.let { p.attr[it.attr] ?: 0 })}</col> (never spent — it is your standing).",
        )
    }
}
