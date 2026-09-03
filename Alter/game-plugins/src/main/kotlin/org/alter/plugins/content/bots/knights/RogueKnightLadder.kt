package org.alter.plugins.content.bots.knights

import org.alter.api.ext.message
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.attr.ROGUE_KNIGHT_RANK_ATTR
import org.alter.game.model.attr.ROGUE_KNIGHT_TARGET_ATTR
import org.alter.game.model.entity.Player
import org.alter.plugins.content.announce.Announce
import org.alter.plugins.content.hunt.TargetMarker
import org.alter.plugins.content.quests.QuestJournal
import org.alter.plugins.content.war.address
import org.alter.plugins.content.war.roguehunt.RogueProblem
import org.alter.rscm.RSCM.getRSCM

/**
 * **Rogue Knight ladder state** — the per-player progression logic over [RogueKnights.LADDER]
 * (pure state machine; [RogueKnightCampPlugin] owns spawning, arrows and hooks, the Recruiting
 * Sergeant speaks the briefs).
 *
 *  - **rank** = how many named knights the player has beaten; their ASSIGNED knight is always
 *    `LADDER[rank]`. Survives death — dying to a knight is the expected loop, never a reset.
 *  - **farm target** = an already-beaten knight the player has asked to hunt again (for its
 *    signature drops); cleared automatically whenever the rank advances.
 *  - The ladder opens two ways: The Rogue Problem's KNIGHT step (the Sergeant's first assignment
 *    is the quest beat itself), or a DIRECT challenge ([optIn] — "veteran PKers skip the lessons
 *    and challenge Rogues directly", design authority §9) with no quest at all. Breaking the
 *    WHOLE ladder is the quest's finish line ([RogueProblem.onLadderCleared]) for those on it,
 *    and every beaten knight stays farmable after.
 *  - Every rung pays War Effort ([RogueRewards]): the first kill of each knight, and capped
 *    repeat kills.
 */
object RogueKnightLadder {

    /** Direct ladder access without the quest (persisted; never cleared). */
    val OPT_IN_ATTR = AttributeKey<Boolean>("rogue_ladder_opt_in")

    /** Named knights beaten so far (== the assigned knight's ladder index). */
    fun rank(p: Player): Int = (p.attr[ROGUE_KNIGHT_RANK_ATTR] ?: 0).coerceAtLeast(0)

    /** True once every knight on the ladder has been beaten at least once. */
    fun complete(p: Player): Boolean = rank(p) > RogueKnights.LADDER.lastIndex

    /** True once the ladder is open to [p]: The Rogue Problem reached its KNIGHT beat, or they
     *  challenged the knights directly ([optIn]). */
    fun unlocked(p: Player): Boolean =
        RogueProblem.step(p).ordinal >= RogueProblem.Step.KNIGHT.ordinal || p.attr[OPT_IN_ATTR] == true

    /**
     * Open the ladder for [p] WITHOUT the quest (the Sergeant's "challenge them directly" branch,
     * `::knights challenge`). Idempotent; returns false when it was already open. The quest stays
     * offerable — accepting it later simply picks the climb up where it stands.
     */
    fun optIn(p: Player): Boolean {
        if (unlocked(p)) return false
        p.attr[OPT_IN_ATTR] = true
        p.message("<col=801700>The Rogue Knight ladder is open to you.</col> Fourteen named PKers, weakest to strongest — beat your mark and the next is named.")
        sergeantLines(p).forEach { p.message(it) }
        QuestJournal.sync(p)
        return true
    }

    /**
     * The Sergeant's ladder chatter for [p] — the current mark, where to find them, the camp gate
     * — shared by the quest dialogue, the direct-challenge branch and [optIn]. Assumes [unlocked].
     */
    fun sergeantLines(p: Player): List<String> {
        val target = activeDef(p)
            ?: return listOf("You've cleared the whole ladder, ${p.address} — all ${RogueKnights.LADDER.size} of them. The realm's deadliest blade. Any of them can be hunted again for their gear: <col=0000ff>::knights</col>.")
        if (target.rank < rank(p)) {
            return listOf("You're back on <col=801700>${target.name}</col> for the spoils — good hunting. ${statusLine(p)} (<col=0000ff>::huntnext</col> returns you to the ladder.)")
        }
        val lines = ArrayList<String>()
        lines += "Your mark: ${target.briefLine}"
        lines += "Find them at <col=801700>${target.camp.display}</col> — ${target.camp.directions} The marker leads; <col=0000ff>::knights</col> lists the whole ladder, and any beaten knight can be farmed again."
        if (!CampClearance.cleared(p, target.camp)) lines += "The camp guards its own: ${CampClearance.statusLine(p, target.camp)}"
        return lines
    }

    /**
     * The ladder index the player is actively hunting: their farm target if they've set one
     * (clamped to knights they've actually beaten), else their assigned knight. Null when the
     * ladder is locked, or fully complete with no farm target set.
     */
    fun targetIdx(p: Player): Int? {
        if (!unlocked(p)) return null
        val farm = p.attr[ROGUE_KNIGHT_TARGET_ATTR]
        if (farm != null) return farm.coerceIn(0, minOf(rank(p), RogueKnights.LADDER.lastIndex))
        return if (complete(p)) null else rank(p)
    }

    /** The knight the player is actively hunting (assigned or farm target), if any. */
    fun activeDef(p: Player): RogueKnightDef? = targetIdx(p)?.let { RogueKnights.byRank(it) }

    /** The player's ASSIGNED (progression) knight — null once the ladder is complete. */
    fun assignedDef(p: Player): RogueKnightDef? =
        if (!unlocked(p) || complete(p)) null else RogueKnights.byRank(rank(p))

    /**
     * Point the player back at a beaten knight to farm its drops. Returns false if they haven't
     * beaten it yet (can only farm behind your rank; the knight AT your rank is the assignment).
     */
    fun setFarmTarget(p: Player, idx: Int): Boolean {
        val def = RogueKnights.byRank(idx) ?: return false
        if (idx >= rank(p)) return false
        p.attr[ROGUE_KNIGHT_TARGET_ATTR] = idx
        p.message("<col=801700>Hunting ${def.name}</col> at ${def.camp.display} — the marker will lead you. (::knights to switch back.)")
        if (TargetMarker.muted(p)) {
            p.message("Your tracking arrow is switched off — type <col=801700>::huntarrow</col> to see the marker.")
        }
        return true
    }

    /** Back to the assigned knight (clears any farm target). */
    fun clearFarmTarget(p: Player) {
        p.attr.remove(ROGUE_KNIGHT_TARGET_ATTR)
    }

    /**
     * A named knight fell to [p] ([RogueKnightCampPlugin]'s death hook — only ever called for the
     * instance's BOUND hunter). First kill at the assigned rank advances the ladder and pays the
     * unlock; re-kills are the farm loop (signature rares only, handled by the drop path).
     */
    fun onKnightKilled(p: Player, def: RogueKnightDef) {
        if (def.rank != rank(p)) {
            p.message("<col=4f9b4f>${def.name} falls again.</col> Their gear and signature loot are yours to claim.")
            RogueRewards.onKnightRepeatKill(p, def) // capped War Effort trickle for the farm loop
            return
        }
        p.attr[ROGUE_KNIGHT_RANK_ATTR] = def.rank + 1
        clearFarmTarget(p) // the hunt follows the new assignment
        payFirstKill(p, def)
        RogueRewards.onKnightFirstKill(p, def) // War Effort for the rung (once — the rank advanced)
        RogueProblem.onAssignedKnightKill(p) // closes the quest's KNIGHT beat, if the player is on it
        val next = assignedDef(p)
        if (next != null) {
            p.message("<col=801700>${def.name} has fallen!</col> The Sergeant's next mark: <col=ffae00>${next.name}</col> at ${next.camp.display}.")
            if (next.camp != def.camp) p.message("Directions: ${next.camp.directions}")
        } else {
            p.message("<col=801700>${def.name} has fallen — the ladder is CLEARED.</col> Every knight remains yours to farm (::knights).")
            Announce.broadcast(p.world, "<col=ffcc00>${p.username} has beaten the entire Rogue Knight ladder — ${RogueKnights.LADDER.size} knights, ending with ${def.name}!</col>")
            RogueProblem.onLadderCleared(p) // the quest's finish line: every camp broken
        }
        if (def.rank >= FANFARE_RANK && next != null) {
            Announce.broadcast(p.world, "<col=4f9b4f>${p.username} has struck down ${def.name} of ${def.camp.display}!</col>")
        }
        QuestJournal.sync(p)
    }

    /** One-line progress report (::knights header + the Sergeant's chatter). */
    fun statusLine(p: Player): String {
        if (!unlocked(p)) return "The Rogue Knights: the ladder is closed to you — take the Recruiting Sergeant's Rogue Problem assignment, or challenge the knights directly (ask him, or <col=0000ff>::knights challenge</col>)."
        val active = activeDef(p)
        return when {
            active == null -> "The Rogue Knights: <col=4f9b4f>ladder cleared</col> — ${RogueKnights.LADDER.size}/${RogueKnights.LADDER.size} beaten. Set a farm target with ::knights."
            active.rank < rank(p) -> "The Rogue Knights: farming <col=ffae00>${active.name}</col> at ${active.camp.display} (${rank(p)}/${RogueKnights.LADDER.size} beaten)."
            else -> "The Rogue Knights: hunting <col=ffae00>${active.name}</col> at ${active.camp.display} (${rank(p)}/${RogueKnights.LADDER.size} beaten)."
        }
    }

    /** Pay the knight's first-kill unlock: inventory first, overflow to the BANK — never ground
     *  loot (the deep camps are PvP ground; a sniped unlock would poison the whole beat). */
    private fun payFirstKill(p: Player, def: RogueKnightDef) {
        if (def.firstKillRewards.isEmpty()) return
        val names = ArrayList<String>()
        for ((key, amount) in def.firstKillRewards) {
            runCatching {
                val id = getRSCM(key)
                val tx = p.inventory.add(id, amount, assureFullInsertion = false)
                val left = amount - tx.completed
                if (left > 0) p.bank.add(id, left)
                names += pretty(key) + if (amount > 1) " x$amount" else ""
            }
        }
        if (names.isNotEmpty()) {
            p.message("<col=4f9b4f>First-kill unlock:</col> ${names.joinToString(", ")} — the core of your next fight's kit (overflow banked).")
        }
    }

    private fun pretty(key: String): String =
        key.removePrefix("item.").replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** Ranks from here up get a realm-wide broadcast on a first kill (the deep-wild marquee names). */
    private const val FANFARE_RANK = 9
}
