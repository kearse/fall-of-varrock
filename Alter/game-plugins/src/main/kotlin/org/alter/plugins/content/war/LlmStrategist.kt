package org.alter.plugins.content.war

import io.github.oshai.kotlinlogging.KotlinLogging
import org.bson.Document
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

/**
 * Live-LLM configuration + global on/off switch. The key comes from the environment
 * (`ANTHROPIC_API_KEY`); the model is overridable via `WAR_LLM_MODEL` (defaults to Opus).
 * Toggle the commander at runtime with `::warllm` (only meaningful when [available]).
 *
 * Set `WAR_LLM_MODEL=claude-haiku-4-5` for the lowest latency/cost — a good fit for a
 * tactical decision on a ~10s cadence.
 */
object WarLlm {
    val apiKey: String? = System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }
    val model: String = System.getenv("WAR_LLM_MODEL")?.takeIf { it.isNotBlank() } ?: "claude-opus-4-8"

    /** Toggled by `::warllm`. Off by default — opt in deliberately (it costs tokens). */
    @Volatile var enabled: Boolean = false

    val available: Boolean get() = apiKey != null
}

/**
 * **The LLM commander (live).** Wraps a deterministic [fallback] and, while [WarLlm.enabled],
 * asks Claude for high-level orders on a slow cadence — *off the game thread*. [decide] never
 * blocks: it snapshots a compact battle brief, hands it to a background worker, and returns the
 * last cached [StrategicIntent] (or the heuristic if none is fresh). The worker does the HTTP
 * call, validates the JSON, and publishes the result for the next tick.
 *
 * Safety: one call in flight at a time; a hard request timeout; any failure (no key, non-200,
 * unparseable/invalid JSON) silently falls back to the heuristic; cached orders expire so a
 * stalled LLM can't freeze the AI on a stale plan.
 */
class LlmStrategist(private val fallback: Strategist) : Strategist {
    @Volatile private var cached: StrategicIntent? = null
    @Volatile private var cachedAtTick = Int.MIN_VALUE
    private val inFlight = AtomicBoolean(false)
    private var tick = 0
    private var lastCallTick = Int.MIN_VALUE

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "war-llm-commander").apply { isDaemon = true }
    }

    override fun decide(a: BattleAssessment, ctx: StrategistContext): StrategicIntent {
        tick++
        val key = WarLlm.apiKey
        if (WarLlm.enabled && key != null && tick - lastCallTick >= CADENCE_TICKS && inFlight.compareAndSet(false, true)) {
            lastCallTick = tick
            // Snapshot to immutable data ON THE GAME THREAD; the worker touches no game state.
            val brief = buildBrief(a, ctx)
            val nFields = a.fieldViews.size
            val submitTick = tick
            worker.submit {
                try {
                    val intent = callClaude(key, brief, nFields)
                    if (intent != null) { cached = intent; cachedAtTick = submitTick }
                } catch (e: Exception) {
                    logger.warn(e) { "war-llm call failed; using heuristic" }
                } finally {
                    inFlight.set(false)
                }
            }
        }
        // Use a cached LLM plan only while it's fresh; else fall back so the AI never freezes.
        val fresh = cached?.takeIf { tick - cachedAtTick <= STALE_TICKS }
        return fresh ?: fallback.decide(a, ctx)
    }

    /** A compact, model-friendly snapshot of the battlefield. */
    private fun buildBrief(a: BattleAssessment, ctx: StrategistContext): String {
        val sb = StringBuilder()
        sb.append("RAID tier=").append(ctx.tierName)
            .append(" goblinReserveLeft=").append(ctx.goblinReserve)
            .append(" knightPoolLeft=").append(ctx.knightPool).append('\n')
        sb.append("Warlord alive=").append(a.warlordAlive).append(" hp%=").append(a.warlordHpPct)
            .append(" field=").append(ctx.warlordFieldIndex).append('\n')
        sb.append("Fields (index:name goblins knights players avgPlayerLvl distToCastle):\n")
        a.fieldViews.forEach { f ->
            val avg = if (f.playerCount > 0) f.playerPower / f.playerCount else 0
            sb.append(' ').append(f.index).append(':').append(f.name)
                .append(" g=").append(f.goblins).append(" k=").append(f.knights)
                .append(" p=").append(f.playerCount).append(" lvl=").append(avg)
                .append(" dist=").append(if (f.frontDist == Int.MAX_VALUE) -1 else f.frontDist).append('\n')
        }
        sb.append("Output the JSON order now.")
        return sb.toString()
    }

    private fun callClaude(key: String, brief: String, nFields: Int): StrategicIntent? {
        // No `temperature`/`thinking` — both are removed on Opus 4.8 (400 if sent); thinking off
        // keeps latency low. We force JSON-only output and extract the object defensively.
        val body = Document("model", WarLlm.model)
            .append("max_tokens", 200)
            .append("system", SYSTEM)
            .append("messages", listOf(Document("role", "user").append("content", brief)))
            .toJson()

        val request = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
            .timeout(Duration.ofSeconds(12))
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            logger.warn { "war-llm HTTP ${response.statusCode()}: ${response.body().take(300)}" }
            return null
        }
        val doc = Document.parse(response.body())
        val content = doc.get("content") as? List<*> ?: return null
        val text = (content.firstOrNull() as? Document)?.getString("text") ?: return null
        return parseIntent(text, nFields)
    }

    /** Pull the JSON object out of the model text and validate it into a [StrategicIntent]. */
    private fun parseIntent(text: String, nFields: Int): StrategicIntent? {
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val o = Document.parse(text.substring(start, end + 1))
            val gp = GoblinPosture.valueOf(o.getString("goblinPosture").trim().uppercase())
            val kp = KnightPosture.valueOf(o.getString("knightPosture").trim().uppercase())
            val gf = (o.get("goblinFocus") as? Number)?.toInt()?.coerceIn(0, nFields - 1) ?: return null
            val kf = (o.get("knightFocus") as? Number)?.toInt()?.coerceIn(0, nFields - 1) ?: return null
            StrategicIntent(gp, kp, gf, kf)
        } catch (e: Exception) {
            logger.warn { "war-llm returned unusable JSON: ${text.take(200)}" }
            null
        }
    }

    private companion object {
        const val CADENCE_TICKS = 6  // director ticks between calls (~11s at 3 game ticks/tick)
        const val STALE_TICKS = 20   // drop a cached plan older than this (~36s) -> heuristic
        val SYSTEM = """
            You are the dual tactical AI commanding BOTH sides of a RuneScape castle siege: the goblin
            horde AND the knight defenders. Read the battlefield brief and issue orders that make the
            fight dynamic, challenging, and fun for the human players.
            Goblins push to reach the castle (General Zo). Goblin postures: THRUST (mass on one field and
            drive for the castle), PROBE (spread out, bleed the knights, conserve), RALLY (mass around the
            Warlord to protect it). Knights defend. Knight postures: HOLD (meet the line), HUNT (send a
            squad to kill the Warlord), GUARD_ZO (collapse back to defend the castle).
            goblinFocus and knightFocus are 0-based field indices to concentrate on.
            Hunt the highest-level players, coordinate, and exploit weak fields.
            Respond with ONLY a single-line JSON object and nothing else, no markdown:
            {"goblinPosture":"THRUST|PROBE|RALLY","knightPosture":"HOLD|HUNT|GUARD_ZO","goblinFocus":0,"knightFocus":0}
        """.trimIndent()
    }
}
