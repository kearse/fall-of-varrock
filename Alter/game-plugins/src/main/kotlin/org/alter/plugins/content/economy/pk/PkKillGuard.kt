package org.alter.plugins.content.economy.pk

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.game.model.World
import org.alter.game.model.attr.ACCOUNT_CREATED_AT_ATTR
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.Client
import org.alter.game.model.entity.Player
import org.alter.plugins.content.bots.PkBot
import org.alter.plugins.content.combat.DeathRisk
import org.alter.plugins.content.combat.PvpZones
import org.alter.plugins.content.combat.SafeDeaths
import org.alter.plugins.service.marketvalue.ItemMarketValueService
import org.bson.Document

/**
 * **PK kill legitimacy** — the one gate every Blood-Money / Elo consumer asks before paying for a
 * player kill (design authority 05 §7: "real-player kills mint BM; anti-alt/farming protections
 * matter"). It never changes the reward FORMULA (that stays in [PkRewardsPlugin]); it only says
 * whether this particular kill pays at all, and why not.
 *
 * Rules, in order (first failure wins) — all thresholds are TUNE constants below:
 *  - SELF / BOT_VICTIM / NOT_HUMAN — only a human killing a human counts (companion killing blows
 *    already resolve to the owner before `KILLER_ATTR` is written, so an owner is "human")
 *  - SAFE_ZONE — the victim died on a non-PvP tile or a designed-safe death ([SafeDeaths])
 *  - SAME_IP — both accounts logged in from one address (alts / boosting on one machine)
 *  - REPEAT_VICTIM — the killer slew this victim within [SAME_VICTIM_COOLDOWN_SEC]
 *  - PAIR_CAP — this PAIR has produced [PAIR_DAILY_CAP] payouts in 24h (either direction — A
 *    kills B and B kills A share one count, which closes kill-trading)
 *  - VICTIM_CASHOUT_CAP — the victim has fed [VICTIM_DAILY_FEED_CAP] payouts today (suicide
 *    accounts feeding a friend)
 *  - KILLER_DAILY_CAP — the killer has minted from [KILLER_DAILY_MINT_CAP] kills today
 *  - LOW_RISK — the victim risked less than [MIN_RISK_GP] of tradeables ([DeathRisk]: kept
 *    items excluded, unclaimed loot-key contents included)
 *  - FRESH_ACCOUNT — the victim's account is younger than [MIN_ACCOUNT_AGE_MS]
 *
 * A denial only zeroes the PAYOUT: the fight, the skull, the death drop and the loot key are
 * untouched (a same-IP household still loots each other; they just don't mint currency).
 *
 * **Compute once per death.** Four independent `onPlayerPreDeath` hooks read `KILLER_ATTR` in an
 * unpinned order and one of them (the death drop) mutates the victim's containers. So:
 * [captureRisk] snapshots the risked value before any mutation, and [verdictFor] evaluates the
 * rules exactly once per death (cached on a reset-on-death attribute keyed by world cycle),
 * commits the ledgers/counters exactly once when the kill is legitimate, and hands every later
 * caller the same verdict. Nothing in here may throw — a throwing pre-death hook aborts every
 * remaining hook (see PlayerDeathAction) — so [assess] fails closed with [Rule.GUARD_ERROR].
 */
object PkKillGuard {

    private val log = KotlinLogging.logger {}
    private val auditLog = KotlinLogging.logger("pk-audit")

    enum class Rule {
        OK, SELF, BOT_VICTIM, NOT_HUMAN, SAFE_ZONE, DISABLED,
        SAME_IP, REPEAT_VICTIM, PAIR_CAP, VICTIM_CASHOUT_CAP, KILLER_DAILY_CAP, LOW_RISK, FRESH_ACCOUNT,
        GUARD_ERROR,
    }

    class Verdict(
        val rule: Rule,
        val reason: String,
        val risked: Long,
        val wildLevel: Int,
        val ipsEqual: Boolean,
        val cycle: Int,
    ) {
        val ok: Boolean get() = rule == Rule.OK
    }

    // ---- TUNE ---------------------------------------------------------------------------------
    /** Killing the same victim again inside this window pays nothing. */
    const val SAME_VICTIM_COOLDOWN_SEC = 1800
    /** Paying kills per PAIR of accounts per rolling 24h, counted in both directions. */
    const val PAIR_DAILY_CAP = 3
    /** How many payouts one victim can feed per day (all killers combined). */
    const val VICTIM_DAILY_FEED_CAP = 10
    /** How many kills one killer can mint from per day. */
    const val KILLER_DAILY_MINT_CAP = 20
    /** The victim must have risked at least this much (gp, market value) for the kill to pay. */
    const val MIN_RISK_GP = 20_000L
    /** Victims on accounts younger than this feed nothing. */
    const val MIN_ACCOUNT_AGE_MS = 86_400_000L
    /** Ledger stamps older than this are pruned. */
    const val LEDGER_WINDOW_SEC = 86_400
    /** Distinct victims kept per killer ledger (oldest pruned first). */
    const val LEDGER_MAX_NAMES = 64

    /** Runtime kill-switch (`::pkguard off`): rules 1-4 (self/bot/human/zone) always apply. */
    @Volatile var enabled = true
    /** Individually disabled rules (`::pkguard rule SAME_IP off`) — runtime only, not persisted. */
    val disabledRules: MutableSet<Rule> = mutableSetOf()

    // ---- state --------------------------------------------------------------------------------
    /** Per-player ledger of recent paying kills: `{"v":[{"n":"<login>","t":[epochSec,...]}]}`. Both
     *  sides of a kill record the OTHER account, so a pair's count is symmetric. Persistent. */
    val PK_RECENT_VICTIMS_ATTR = AttributeKey<String>(persistenceKey = "pk_recent_victims")
    /** Epoch-day + count of payouts this VICTIM fed today. Persistent (Ints only — never Long). */
    val PK_FED_DAY_ATTR = AttributeKey<Int>(persistenceKey = "pk_fed_day")
    val PK_FED_COUNT_ATTR = AttributeKey<Int>(persistenceKey = "pk_fed_count")
    /** Epoch-day + count of kills this KILLER minted from today. Persistent. */
    val PK_MINT_DAY_ATTR = AttributeKey<Int>(persistenceKey = "pk_mint_day")
    val PK_MINT_COUNT_ATTR = AttributeKey<Int>(persistenceKey = "pk_mint_count")

    /** Per-death caches (cleared on respawn, and re-checked against the world cycle). */
    private val PK_VERDICT_ATTR = AttributeKey<Verdict>(resetOnDeath = true)
    private val PK_RISKED_VALUE_ATTR = AttributeKey<Long>(resetOnDeath = true)

    /**
     * Snapshot what the victim is about to lose BEFORE the death drop strips their containers.
     * Called first thing by PvpDeathDropPlugin's hook; harmless to call twice.
     */
    fun captureRisk(world: World, victim: Player) {
        if (victim.attr[PK_RISKED_VALUE_ATTR] != null) return
        runCatching {
            victim.attr[PK_RISKED_VALUE_ATTR] = DeathRisk.riskedValue(victim, prices(world))
        }.onFailure { log.warn(it) { "pk-guard: could not price ${victim.username}'s risk" } }
    }

    /**
     * The verdict for [victim]'s current death — computed on the first call, cached for the rest
     * of the death sequence. Null when no Player resolved as the killer (poison, no damage...).
     * On a legitimate kill the FIRST call also commits the ledgers/counters and tells the killer
     * why a payout was refused, so consumers never double-book and the chat line appears once.
     */
    fun verdictFor(world: World, victim: Player): Verdict? {
        val killer = victim.attr[KILLER_ATTR]?.get() as? Player ?: return null
        victim.attr[PK_VERDICT_ATTR]?.let { if (it.cycle == world.currentCycle) return it }
        val verdict = assess(world, killer, victim)
        victim.attr[PK_VERDICT_ATTR] = verdict
        runCatching {
            if (verdict.ok) commit(killer, victim)
            else if (verdict.rule.isPlayerFacing()) killer.message("<col=990000>Blood money:</col> none - ${verdict.reason}.")
        }.onFailure { log.error(it) { "pk-guard: commit failed for ${killer.username} -> ${victim.username}" } }
        return verdict
    }

    /** Pure evaluation — reads state, writes nothing, never throws. Used by [verdictFor] and `::pktest`. */
    fun assess(world: World, killer: Player, victim: Player): Verdict {
        val wild = runCatching { PvpZones.wildernessLevel(victim.tile) }.getOrDefault(0)
        val ipsEqual = ipOf(killer) != null && ipOf(killer) == ipOf(victim)
        var risked = -1L
        return try {
            fun deny(rule: Rule, reason: String) = Verdict(rule, reason, risked, wild, ipsEqual, world.currentCycle)
            fun active(rule: Rule) = enabled && rule !in disabledRules

            if (killer === victim) return deny(Rule.SELF, "self-kill")
            if (victim is PkBot) return deny(Rule.BOT_VICTIM, "bot victim")
            if (!killer.entityType.isHumanControlled || !victim.entityType.isHumanControlled) {
                return deny(Rule.NOT_HUMAN, "not a human-vs-human kill")
            }
            if (!PvpZones.isWilderness(victim.tile) || SafeDeaths.isSafeDeath(victim)) {
                return deny(Rule.SAFE_ZONE, "not a wilderness death")
            }
            if (!enabled) return Verdict(Rule.DISABLED, "guard disabled", risked, wild, ipsEqual, world.currentCycle)

            if (active(Rule.SAME_IP) && ipsEqual) return deny(Rule.SAME_IP, "your opponent shares your connection")

            val now = nowSec()
            val victimKey = keyOf(victim)
            val killerKey = keyOf(killer)
            val stamps = ledger(killer)[victimKey].orEmpty().filter { now - it < LEDGER_WINDOW_SEC }
            if (active(Rule.REPEAT_VICTIM) && stamps.any { now - it < SAME_VICTIM_COOLDOWN_SEC }) {
                return deny(Rule.REPEAT_VICTIM, "you slew them too recently")
            }
            if (active(Rule.PAIR_CAP) && stamps.size >= PAIR_DAILY_CAP) {
                return deny(Rule.PAIR_CAP, "you've fought them enough today")
            }
            if (active(Rule.VICTIM_CASHOUT_CAP) && dayCount(victim, PK_FED_DAY_ATTR, PK_FED_COUNT_ATTR) >= VICTIM_DAILY_FEED_CAP) {
                return deny(Rule.VICTIM_CASHOUT_CAP, "they've fallen to PKers too often today")
            }
            if (active(Rule.KILLER_DAILY_CAP) && dayCount(killer, PK_MINT_DAY_ATTR, PK_MINT_COUNT_ATTR) >= KILLER_DAILY_MINT_CAP) {
                return deny(Rule.KILLER_DAILY_CAP, "you've reached today's Blood Money limit")
            }
            risked = victim.attr[PK_RISKED_VALUE_ATTR] ?: DeathRisk.riskedValue(victim, prices(world))
            if (active(Rule.LOW_RISK) && risked < MIN_RISK_GP) {
                return deny(Rule.LOW_RISK, "they risked too little (min ${"%,d".format(MIN_RISK_GP)} gp)")
            }
            val created = victim.attr[ACCOUNT_CREATED_AT_ATTR]
            if (active(Rule.FRESH_ACCOUNT) && created != null && System.currentTimeMillis() - created < MIN_ACCOUNT_AGE_MS) {
                return deny(Rule.FRESH_ACCOUNT, "their account is too new")
            }
            // (killerKey is only needed on commit; evaluated here so a broken key surfaces as GUARD_ERROR.)
            check(killerKey.isNotEmpty())
            Verdict(Rule.OK, "legitimate kill", risked, wild, ipsEqual, world.currentCycle)
        } catch (t: Throwable) {
            log.error(t) { "pk-guard: assess threw for ${killer.username} -> ${victim.username}; failing closed" }
            Verdict(Rule.GUARD_ERROR, "guard error", risked, wild, ipsEqual, world.currentCycle)
        }
    }

    /** One structured line per audited death — grep `pk-audit` in the server log. */
    fun audit(killer: Player, victim: Player, verdict: Verdict, reward: Int) {
        val t = victim.tile
        val v = if (verdict.ok) "OK" else "DENY:${verdict.rule}"
        auditLog.info {
            "pk-audit killer=${keyOf(killer)} victim=${keyOf(victim)} verdict=$v ipsEqual=${verdict.ipsEqual} " +
                "reward=$reward risked=${verdict.risked} wild=${verdict.wildLevel} tile=${t.x},${t.z},${t.height} " +
                "kcb=${killer.combatLevel} vcb=${victim.combatLevel} cycle=${verdict.cycle}"
        }
    }

    /** Human-readable state for `::pkaudit`. */
    fun describe(target: Player): List<String> {
        val out = ArrayList<String>()
        val created = target.attr[ACCOUNT_CREATED_AT_ATTR]
        val ageH = created?.let { (System.currentTimeMillis() - it) / 3_600_000L }
        out += "PK guard - ${target.username} (${keyOf(target)}): ip=${ipOf(target) ?: "?"} accountAge=${ageH?.let { "${it}h" } ?: "unknown"}"
        out += "Today: fed ${dayCount(target, PK_FED_DAY_ATTR, PK_FED_COUNT_ATTR)}/$VICTIM_DAILY_FEED_CAP payouts, " +
            "minted from ${dayCount(target, PK_MINT_DAY_ATTR, PK_MINT_COUNT_ATTR)}/$KILLER_DAILY_MINT_CAP kills."
        val now = nowSec()
        val entries = ledger(target).entries
            .map { (name, stamps) -> name to stamps.filter { now - it < LEDGER_WINDOW_SEC } }
            .filter { it.second.isNotEmpty() }
            .sortedByDescending { it.second.max() }
        if (entries.isEmpty()) out += "No paying kills in the last 24h."
        for ((name, stamps) in entries) {
            out += "- $name: ${stamps.size} in 24h, last ${(now - stamps.max()) / 60} min ago"
        }
        return out
    }

    fun status(): List<String> = listOf(
        "PK guard: ${if (enabled) "ON" else "OFF"}; disabled rules: ${if (disabledRules.isEmpty()) "none" else disabledRules.joinToString()}",
        "cooldown=${SAME_VICTIM_COOLDOWN_SEC}s pairCap=$PAIR_DAILY_CAP/day victimFeedCap=$VICTIM_DAILY_FEED_CAP/day " +
            "killerMintCap=$KILLER_DAILY_MINT_CAP/day minRisk=${"%,d".format(MIN_RISK_GP)}gp minAccountAge=${MIN_ACCOUNT_AGE_MS / 3_600_000L}h",
    )

    // ---- internals ----------------------------------------------------------------------------

    /** Book a legitimate kill on both accounts (symmetric pair ledger + the two daily counters). */
    private fun commit(killer: Player, victim: Player) {
        val now = nowSec()
        addStamp(killer, keyOf(victim), now)
        addStamp(victim, keyOf(killer), now)
        bump(victim, PK_FED_DAY_ATTR, PK_FED_COUNT_ATTR)
        bump(killer, PK_MINT_DAY_ATTR, PK_MINT_COUNT_ATTR)
    }

    private fun Rule.isPlayerFacing(): Boolean = this !in setOf(Rule.OK, Rule.SELF, Rule.BOT_VICTIM, Rule.NOT_HUMAN, Rule.SAFE_ZONE, Rule.DISABLED)

    /** Stable account key: the login name for humans, else the display name lowercased. */
    private fun keyOf(p: Player): String = (p as? Client)?.loginUsername ?: p.username.lowercase()

    private fun ipOf(p: Player): String? = (p as? Client)?.remoteIp

    private fun prices(world: World): ItemMarketValueService? = world.getService(ItemMarketValueService::class.java)

    private fun nowSec(): Int = (System.currentTimeMillis() / 1000L).toInt()

    private fun today(): Int = (System.currentTimeMillis() / 86_400_000L).toInt()

    private fun dayCount(p: Player, dayKey: AttributeKey<Int>, countKey: AttributeKey<Int>): Int =
        if (p.attr[dayKey] == today()) (p.attr[countKey] ?: 0) else 0

    private fun bump(p: Player, dayKey: AttributeKey<Int>, countKey: AttributeKey<Int>) {
        val count = dayCount(p, dayKey, countKey) + 1
        p.attr[dayKey] = today()
        p.attr[countKey] = count
    }

    /** Decoded ledger: other-account key -> stamps (epoch seconds). Decode never throws. */
    private fun ledger(p: Player): Map<String, List<Int>> {
        val blob = p.attr[PK_RECENT_VICTIMS_ATTR] ?: return emptyMap()
        return runCatching {
            Document.parse(blob).getList("v", Document::class.java).associate { d ->
                d.getString("n") to d.getList("t", Integer::class.java).map { it.toInt() }
            }
        }.getOrDefault(emptyMap())
    }

    private fun addStamp(p: Player, other: String, now: Int) {
        val pruned = ledger(p).mapValues { (_, stamps) -> stamps.filter { now - it < LEDGER_WINDOW_SEC } }
            .filterValues { it.isNotEmpty() }
            .toMutableMap()
        pruned[other] = (pruned[other].orEmpty() + now)
        // Bound the blob: keep the most recently active names.
        val kept = pruned.entries.sortedByDescending { it.value.max() }.take(LEDGER_MAX_NAMES)
        p.attr[PK_RECENT_VICTIMS_ATTR] = Document(
            "v",
            kept.map { (name, stamps) -> Document("n", name).append("t", stamps) },
        ).toJson()
    }
}
