package org.alter.plugins.content.mechanics.reports

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.GameContext
import org.alter.game.saving.formats.FormatHandler
import org.bson.Document
import java.util.concurrent.ConcurrentHashMap

/**
 * A single player-filed report (rule-breaker or bug), persisted in the
 * `reports` collection of the configured save format.
 */
data class Report(
    val id: String,
    val type: String,
    val reporterLogin: String,
    val reporterDisplay: String,
    val reportedName: String,
    val category: String,
    val details: String,
    val createdAt: Long,
    var status: String = STATUS_OPEN,
    var resolvedBy: String = "",
    var resolvedAt: Long = 0,
    var resolutionNote: String = "",
) {
    fun asDocument(): Document = Document().apply {
        // Both save formats key documents by this field (Mongo upserts filter on
        // it, loadAll() reads it back as the map key), so the report id lives here.
        append("loginUsername", id)
        append("type", type)
        append("reporterLogin", reporterLogin)
        append("reporterDisplay", reporterDisplay)
        append("reportedName", reportedName)
        append("category", category)
        append("details", details)
        append("createdAt", createdAt)
        append("status", status)
        append("resolvedBy", resolvedBy)
        append("resolvedAt", resolvedAt)
        append("resolutionNote", resolutionNote)
    }

    companion object {
        const val TYPE_PLAYER = "player"
        const val TYPE_BUG = "bug"
        const val STATUS_OPEN = "open"
        const val STATUS_RESOLVED = "resolved"

        fun fromDocument(doc: Document): Report? {
            val id = doc.getString("loginUsername") ?: return null
            return Report(
                id = id,
                type = doc.getString("type") ?: TYPE_PLAYER,
                reporterLogin = doc.getString("reporterLogin") ?: "",
                reporterDisplay = doc.getString("reporterDisplay") ?: "",
                reportedName = doc.getString("reportedName") ?: "",
                category = doc.getString("category") ?: "",
                details = doc.getString("details") ?: "",
                createdAt = (doc["createdAt"] as? Number)?.toLong() ?: 0,
                status = doc.getString("status") ?: STATUS_OPEN,
                resolvedBy = doc.getString("resolvedBy") ?: "",
                resolvedAt = (doc["resolvedAt"] as? Number)?.toLong() ?: 0,
                resolutionNote = doc.getString("resolutionNote") ?: "",
            )
        }
    }
}

/**
 * In-memory report book with write-through persistence, mirroring
 * [org.alter.game.saving.PlayerModeration]. Ids are sequential (`r000042`) —
 * lowercase and dot-free so they survive both the Json filename key and the
 * Mongo `loginUsername` key. Filing and resolving happen on the game thread,
 * so the plain [nextId] counter needs no synchronisation.
 */
object ReportStore {

    private val logger = KotlinLogging.logger {}

    private lateinit var serialization: FormatHandler

    private val reports = ConcurrentHashMap<String, Report>()

    private var nextId = 1

    fun init(gameContext: GameContext) {
        serialization = gameContext.saveFormat.createInstance("reports")
        serialization.loadAll().forEach { (_, doc) ->
            Report.fromDocument(doc)?.let { reports[it.id] = it }
        }
        nextId = (reports.keys.mapNotNull { it.removePrefix("r").toIntOrNull() }.maxOrNull() ?: 0) + 1
        serialization.init()
        logger.info { "Loaded ${reports.size} report(s); ${open().size} open." }
    }

    fun file(
        type: String,
        reporterLogin: String,
        reporterDisplay: String,
        reportedName: String,
        category: String,
        details: String,
    ): Report {
        val report = Report(
            id = "r%06d".format(nextId++),
            type = type,
            reporterLogin = reporterLogin,
            reporterDisplay = reporterDisplay,
            reportedName = reportedName,
            category = category,
            details = details,
            createdAt = System.currentTimeMillis(),
        )
        reports[report.id] = report
        persist(report)
        return report
    }

    /** Marks [id] resolved, or returns null if it's unknown or already resolved. */
    fun resolve(id: String, by: String, note: String): Report? {
        val report = reports[id.lowercase().trim()] ?: return null
        if (report.status == Report.STATUS_RESOLVED) return null
        report.status = Report.STATUS_RESOLVED
        report.resolvedBy = by
        report.resolvedAt = System.currentTimeMillis()
        report.resolutionNote = note
        persist(report)
        return report
    }

    fun get(id: String): Report? = reports[id.lowercase().trim()]

    fun open(): List<Report> = reports.values.filter { it.status == Report.STATUS_OPEN }.sortedBy { it.createdAt }

    fun all(): List<Report> = reports.values.sortedBy { it.createdAt }

    private fun persist(report: Report) {
        // A failed write must not blow up the report dialog — the in-memory copy
        // survives until restart and staff were already pinged on Discord.
        try {
            serialization.saveDocument(report.id, report.asDocument())
        } catch (e: Exception) {
            logger.error(e) { "Failed to persist report ${report.id}." }
        }
    }
}
