package org.alter.plugins.content.bots.knights

import org.alter.api.ext.message
import org.alter.game.model.attr.ROGUE_KNIGHT_RANK_ATTR
import org.alter.game.model.attr.ROGUE_KNIGHT_TARGET_ATTR
import org.alter.game.model.entity.Player
import org.alter.plugins.content.announce.Announce
import org.alter.plugins.content.quests.QuestJournal
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
 *  - The ladder unlocks at The Rogue Problem's KNIGHT step (the Sergeant's first assignment is
 *    the quest beat itself) and keeps going long after the quest closes.
 */
object RogueKnightLadder {

    /** Named knights beaten so far (== the assigned knight's ladder index). */
    fun rank(p: Player): Int = (p.attr[ROGUE_KNIGHT_RANK_ATTR] ?: 0).coerceAtLeast(0)

    /** True once every knight on the ladder has been beaten at least once. */
    fun complete(p: Player): Boolean = rank(p) > RogueKnights.LADDER.lastIndex

    /** True once the Sergeant has opened the ladder (The Rogue Problem reached its KNIGHT beat). */
    fun unlocked(p: Player): Boolean =
        RogueProblem.step(p).ordinal >= RogueProblem.Step.KNIGHT.ordinal

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
            return
        }
        p.attr[ROGUE_KNIGHT_RANK_ATTR] = def.rank + 1
        clearFarmTarget(p) // the hunt follows the new assignment
        payFirstKill(p, def)
        RogueProblem.onAssignedKnightKill(p) // closes the quest's KNIGHT beat, if the player is on it
        val next = assignedDef(p)
        if (next != null) {
            p.message("<col=801700>${def.name} has fallen!</col> The Sergeant's next mark: <col=ffae00>${next.name}</col> at ${next.camp.display}.")
            if (next.camp != def.camp) p.message("Directions: ${next.camp.directions}")
        } else {
            p.message("<col=801700>${def.name} has fallen — the ladder is CLEARED.</col> Every knight remains yours to farm (::knights).")
            Announce.broadcast(p.world, "<col=ffcc00>${p.username} has beaten the entire Rogue Knight ladder — ${RogueKnights.LADDER.size} knights, ending with ${def.name}!</col>")
        }
        if (def.rank >= FANFARE_RANK && next != null) {
            Announce.broadcast(p.world, "<col=4f9b4f>${p.username} has struck down ${def.name} of ${def.camp.display}!</col>")
        }
        QuestJournal.sync(p)
    }

    /** One-line progress report (::knights header + the Sergeant's chatter). */
    fun statusLine(p: Player): String {
        if (!unlocked(p)) return "The Rogue Knights: the Recruiting Sergeant will open the hunt when you're ready (finish The Rogue Problem's street work)."
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
