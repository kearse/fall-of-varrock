package org.alter.plugins.content.quests

import org.alter.api.ext.clearHintArrow
import org.alter.api.ext.getVarp
import org.alter.api.ext.message
import org.alter.api.ext.setVarp
import org.alter.game.model.attr.QUEST_GUIDE_MUTED_ATTR
import org.alter.game.model.attr.RECRUIT_GOBLIN_KILLS_ATTR
import org.alter.game.model.attr.SLAYER_TASK_NPC_ATTR
import org.alter.game.model.entity.Player
import org.alter.plugins.content.war.recruit.RecruitTrials
import org.alter.plugins.content.war.warprep.WarPrepChain

/**
 * **Quest Journal client feed** — publishes each custom quest chain's live state to the varps the
 * custom RuneLite client's Quest Journal plugin (`lofquests`) reads, and owns the **free-play
 * guidance mute** ([QUEST_GUIDE_MUTED_ATTR]) that lets a player switch the chains' hint arrows off.
 *
 * Varp layout (must match the client plugin's `LofQuestVarps`):
 *  - [RECRUIT_VARP] packed: bits 0-5 = [RecruitTrials.Step] ordinal, bits 6-9 = goblins killed on
 *    the FIGHT trial (0-15 clamp), bit 10 = slayer war-contract taken (splits the SLAY arrow's
 *    "see Vannaka" vs "kill the rats" phases).
 *  - [WARPREP_VARP] = [WarPrepChain.Step] ordinal.
 *  - [GUIDE_MUTED_VARP] = 1 while guidance is muted, else 0 (so the client toggle reflects state).
 *
 * Varps 4600-4608 and 4620-4623 are taken by the other client HUDs; quests own 4610-4612.
 * Non-zero varps persist ([VarpSerialisation]), but the attributes stay the source of truth —
 * everything here is re-derived and re-published on login and on the world poll.
 *
 * **Native quest tab.** We also mirror each custom quest's state into the progress varp of an OSRS
 * quest we've relabelled in the cache (see the `questTable` cache tool + docs/quest-tab-handoff.md).
 * The rev-228 quest-list clientscript colours each row by running `QUEST_STATUS_GET(<quest id>)`,
 * which resolves that quest's progress varp — so writing the reused quest's varp to
 * not-started / in-progress / complete drives the stock tab's red / yellow / green with no
 * clientscript edits. See [REUSED_QUEST_VARPS]; the `[not-started, complete]` values come from the
 * dumped quest table (`col19`) / known OSRS completion values.
 */
object QuestJournal {

    const val RECRUIT_VARP = 4610
    const val WARPREP_VARP = 4611
    const val GUIDE_MUTED_VARP = 4612

    // Reused OSRS quest progress varps that colour the relabelled native quest-tab rows. A value of
    // 0 reads as "not started" (red), the complete value as "finished" (green), anything between as
    // "in progress" (yellow). Keep these in lock-step with the `questTable` tool's REUSE table.
    /** Cook's Assistant varp — now the "Recruit Trials" row. Completes at 2. */
    const val RECRUIT_QUEST_VARP = 29
    private const val RECRUIT_QUEST_COMPLETE = 2
    /** Doric's Quest varp — now the "War-Prep I — Magic" row. Completes at 100. */
    const val WARPREP_QUEST_VARP = 31
    private const val WARPREP_QUEST_COMPLETE = 100

    /** True while the player has quest guidance muted (free-play mode). */
    fun muted(p: Player): Boolean = p.attr[QUEST_GUIDE_MUTED_ATTR] == true

    /** Flip the guidance mute; clears any live arrow on mute, redraws it on unmute. */
    fun toggleMute(p: Player) {
        val nowMuted = !muted(p)
        p.attr[QUEST_GUIDE_MUTED_ATTR] = nowMuted
        if (nowMuted) {
            p.clearHintArrow()
            p.message("<col=801700>Quest guidance muted.</col> The arrows will leave you be — free play. Type <col=801700>::questguide</col> (or use the Quest Journal) to turn them back on.")
        } else {
            p.message("<col=801700>Quest guidance back on.</col>")
            RecruitTrials.updateHintArrow(p)
            WarPrepChain.updateHintArrow(p)
        }
        sync(p)
    }

    /** Re-derive the journal varps from the player's quest state; only writes what changed. */
    fun sync(p: Player) {
        val recruitStep = RecruitTrials.step(p).ordinal and 0x3F
        val kills = (p.attr[RECRUIT_GOBLIN_KILLS_ATTR] ?: 0).coerceIn(0, 15)
        val contract = if (p.attr[SLAYER_TASK_NPC_ATTR] != null) 1 else 0
        val recruitPacked = recruitStep or (kills shl 6) or (contract shl 10)
        if (p.getVarp(RECRUIT_VARP) != recruitPacked) p.setVarp(RECRUIT_VARP, recruitPacked)

        val warprep = WarPrepChain.step(p).ordinal
        if (p.getVarp(WARPREP_VARP) != warprep) p.setVarp(WARPREP_VARP, warprep)

        val mutedFlag = if (muted(p)) 1 else 0
        if (p.getVarp(GUIDE_MUTED_VARP) != mutedFlag) p.setVarp(GUIDE_MUTED_VARP, mutedFlag)

        syncNativeTab(p)
    }

    /**
     * Colour the relabelled native quest-tab rows by writing the reused OSRS quest varps.
     *
     * Recruit Trials: the fresh-account start (TALK, ordinal 0) reads as *not started*; any later
     * step is *in progress*; DONE is *complete*. War-Prep: NONE (not begun / locked) is *not
     * started*; DONE is *complete*; anything between is *in progress*.
     */
    private fun syncNativeTab(p: Player) {
        val recruit = RecruitTrials.step(p)
        val recruitVal = when {
            recruit == RecruitTrials.Step.DONE -> RECRUIT_QUEST_COMPLETE
            recruit.ordinal == 0 -> 0 // TALK — not started yet
            else -> 1                 // in progress
        }
        setVarpSafely(p, RECRUIT_QUEST_VARP, recruitVal)

        val warprep = WarPrepChain.step(p)
        val warprepVal = when {
            warprep == WarPrepChain.Step.DONE -> WARPREP_QUEST_COMPLETE
            warprep == WarPrepChain.Step.NONE -> 0 // not begun / locked
            else -> 1                              // in progress
        }
        setVarpSafely(p, WARPREP_QUEST_VARP, warprepVal)
    }

    private fun setVarpSafely(p: Player, varp: Int, value: Int) {
        if (varp >= p.varps.maxVarps) return // defensive: never write out of range
        if (p.getVarp(varp) != value) p.setVarp(varp, value)
    }
}
