package org.alter.plugins.content.quests

import org.alter.api.ext.clearHintArrow
import org.alter.api.ext.getVarp
import org.alter.api.ext.message
import org.alter.api.ext.setVarp
import org.alter.game.model.attr.QUEST_GUIDE_MUTED_ATTR
import org.alter.game.model.attr.RECRUIT_GOBLIN_KILLS_ATTR
import org.alter.game.model.attr.SLAYER_TASK_NPC_ATTR
import org.alter.game.model.entity.Player
import org.alter.plugins.content.bots.knights.RogueKnightLadder
import org.alter.plugins.content.bots.knights.RogueKnights
import org.alter.plugins.content.war.Conquest
import org.alter.plugins.content.war.recruit.RecruitTrials
import org.alter.plugins.content.war.roguehunt.RogueProblem
import org.alter.plugins.content.war.warprep.WarPrepChain
import org.alter.plugins.content.war.warprep.WarPrepRanged
import org.alter.plugins.content.war.warprep.WarPrepSurvival

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
 *  - [ROGUE_PROBLEM_VARP] packed: bits 0-5 = [RogueProblem.Step] ordinal, bits 6-11 = rogues felled
 *    on the HUNT step (0-63 clamp) so the client can render the "(x/30)" progress.
 *  - [WARPREP_RANGED_VARP] packed: bits 0-5 = [WarPrepRanged.Step] ordinal, bits 6-11 = enemies felled
 *    with a ranged weapon on the FIELD step (0-63 clamp) so the client can render the "(x/20)" progress.
 *  - [WARPREP_SURVIVAL_VARP] bits 0-5 = [WarPrepSurvival.Step] ordinal.
 *  - [CONQUEST_VARP] bits 0-5 = [Conquest.Step] ordinal (the endgame "King of Lumbridge" quest).
 *  - [KNIGHTS_VARP] packed: bits 0-7 = Rogue Knight ladder rank (knights beaten, 0-255 clamp),
 *    bits 8-15 = the active hunt's ladder index + 1 (0 = no active hunt), bits 16-19 = rogue camps
 *    cleared (0-15), bits 20-23 = total knight-hosting camps (0-15) — the last two feed the rogue
 *    quest window's left-side dial; the live tracking arrow is server-driven (`RogueKnightCampPlugin`).
 *  - [GUIDE_MUTED_VARP] = 1 while guidance is muted, else 0 (so the client toggle reflects state).
 *
 * Varps 4600-4608, 4613-4616, 4618-4623, 4625-4626 and 4633-4637 are taken by the other client HUDs;
 * quests own 4610-4612, 4617, 4624, 4633, 4643, 4644 and 4645 ([QuestBook.OPEN_VARP] — the
 * "open the Quest Journal window, focused on quest N" pulse; not published here, pulsed on demand).
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
    const val ROGUE_PROBLEM_VARP = 4617 // 4613-4616 belong to the companion + slayer HUDs
    const val WARPREP_RANGED_VARP = 4624   // War-Prep II — Ranged (4618-4623, 4625-4626 belong to other HUDs)
    const val WARPREP_SURVIVAL_VARP = 4643 // War-Prep III — Survival
    const val CONQUEST_VARP = 4633      // King of Lumbridge (endgame); 4635-4637 are companion indices
    const val KNIGHTS_VARP = 4644       // Rogue Knight ladder (rank + active hunt index)

    // Reused OSRS quest progress varps that colour the relabelled native quest-tab rows. A value of
    // 0 reads as "not started" (red), the complete value as "finished" (green), anything between as
    // "in progress" (yellow). Keep these in lock-step with the `questTable` tool's REUSE table.
    /** Cook's Assistant varp — now the "Recruit Trials" row. Completes at 2. */
    const val RECRUIT_QUEST_VARP = 29
    private const val RECRUIT_QUEST_COMPLETE = 2
    /** Doric's Quest varp — now the "War-Prep I — Magic" row. Completes at 100. */
    const val WARPREP_QUEST_VARP = 31
    private const val WARPREP_QUEST_COMPLETE = 100
    /** The Restless Ghost varp — now the "Rogue Hunting I" row (the 30-rogue hunt). Completes at 5. */
    const val ROGUE_QUEST_VARP = 107
    private const val ROGUE_QUEST_COMPLETE = 5
    /** The Knight's Sword varp — now the "Rogue Hunting II" row (the Rogue Knight ladder).
     *  Completes at 7. */
    const val ROGUE_LADDER_QUEST_VARP = 122
    private const val ROGUE_LADDER_QUEST_COMPLETE = 7
    /** Imp Catcher varp — now the "War-Prep II — Ranged" row. Completes at 2. */
    const val WARPREP_RANGED_QUEST_VARP = 160
    private const val WARPREP_RANGED_QUEST_COMPLETE = 2
    /** Sheep Shearer varp — now the "War-Prep III — Survival" row. Completes at 21. */
    const val WARPREP_SURVIVAL_QUEST_VARP = 179
    private const val WARPREP_SURVIVAL_QUEST_COMPLETE = 21
    /** Witch's Potion varp — now the "King of Lumbridge" row. Completes at 3. */
    const val KING_QUEST_VARP = 67
    private const val KING_QUEST_COMPLETE = 3

    /** True while the player has quest guidance muted (free-play mode). */
    fun muted(p: Player): Boolean = p.attr[QUEST_GUIDE_MUTED_ATTR] == true

    /** Flip the guidance mute; clears any live arrow on mute, redraws it on unmute. */
    fun toggleMute(p: Player) {
        val nowMuted = !muted(p)
        p.attr[QUEST_GUIDE_MUTED_ATTR] = nowMuted
        // Guidance arrows are drawn client-side now; [GUIDE_MUTED_VARP] (synced below) tells the
        // Quest Journal plugin whether to draw them, so the toggle is just the flag + a line.
        if (nowMuted) {
            p.message("<col=801700>Quest guidance off.</col> The on-screen arrows will leave you be — free play. Type <col=801700>::questguide</col> (or use the Quest Journal) to turn them back on.")
        } else {
            p.message("<col=801700>Quest guidance on.</col> The Quest Journal will point the way again.")
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

        val rogueStep = RogueProblem.step(p).ordinal and 0x3F
        val rogueKills = RogueProblem.huntKills(p).coerceIn(0, 63)
        val roguePacked = rogueStep or (rogueKills shl 6)
        if (p.getVarp(ROGUE_PROBLEM_VARP) != roguePacked) p.setVarp(ROGUE_PROBLEM_VARP, roguePacked)

        val rangedStep = WarPrepRanged.step(p).ordinal and 0x3F
        val rangedKills = WarPrepRanged.fieldKills(p).coerceIn(0, 63)
        val rangedPacked = rangedStep or (rangedKills shl 6)
        if (p.getVarp(WARPREP_RANGED_VARP) != rangedPacked) p.setVarp(WARPREP_RANGED_VARP, rangedPacked)

        val survivalStep = WarPrepSurvival.step(p).ordinal and 0x3F
        if (p.getVarp(WARPREP_SURVIVAL_VARP) != survivalStep) p.setVarp(WARPREP_SURVIVAL_VARP, survivalStep)

        val conquest = Conquest.step(p).ordinal and 0x3F
        if (p.getVarp(CONQUEST_VARP) != conquest) p.setVarp(CONQUEST_VARP, conquest)

        // Rogue Knight ladder: rank + the active hunt (index+1; 0 = none), plus the rogue quest
        // window's left-side dial data — camps cleared / total. A knight-hosting camp is "cleared"
        // once every knight it stations is beaten (max rank < knights beaten). Packed here so the
        // client dial never has to mirror the ladder's camp layout.
        val knightRank = RogueKnightLadder.rank(p).coerceIn(0, 255)
        val knightHunt = ((RogueKnightLadder.targetIdx(p) ?: -1) + 1).coerceIn(0, 255)
        val knightCamps = RogueKnights.LADDER.map { it.camp }.distinct()
        val campsCleared = knightCamps.count { camp ->
            RogueKnights.LADDER.filter { it.camp == camp }.all { it.rank < knightRank }
        }.coerceIn(0, 15)
        val campsTotal = knightCamps.size.coerceIn(0, 15)
        val knightsPacked = knightRank or (knightHunt shl 8) or (campsCleared shl 16) or (campsTotal shl 20)
        if (p.getVarp(KNIGHTS_VARP) != knightsPacked) p.setVarp(KNIGHTS_VARP, knightsPacked)

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

        // The Act II rogue chain drives TWO native rows, windowed off one Step machine:
        //  - Rogue Hunting I (the 30-rogue hunt): complete the moment the hunt clears (KNIGHT
        //    step onward); BRIEF/HUNT in progress; NONE not begun / locked.
        //  - Rogue Hunting II (the Rogue Knight ladder): locked until the hunt clears; complete
        //    when every camp is broken (DONE); the knight/report/ladder stretch is in progress.
        val rogue = RogueProblem.step(p)
        val huntVal = when {
            rogue.ordinal >= RogueProblem.Step.KNIGHT.ordinal -> ROGUE_QUEST_COMPLETE
            rogue == RogueProblem.Step.NONE -> 0 // not begun / locked
            else -> 1                            // in progress
        }
        setVarpSafely(p, ROGUE_QUEST_VARP, huntVal)
        val ladderVal = when {
            rogue == RogueProblem.Step.DONE -> ROGUE_LADDER_QUEST_COMPLETE
            rogue.ordinal >= RogueProblem.Step.KNIGHT.ordinal -> 1 // climbing
            else -> 0 // locked until Rogue Hunting I clears
        }
        setVarpSafely(p, ROGUE_LADDER_QUEST_VARP, ladderVal)

        // War-Prep II — Ranged: NONE (locked until The Rogue Problem finishes / not begun) is not
        // started; DONE is complete; any drill/skirmish/rank step in between is in progress.
        val ranged = WarPrepRanged.step(p)
        val rangedVal = when {
            ranged == WarPrepRanged.Step.DONE -> WARPREP_RANGED_QUEST_COMPLETE
            ranged == WarPrepRanged.Step.NONE -> 0 // not begun / locked
            else -> 1                              // in progress
        }
        setVarpSafely(p, WARPREP_RANGED_QUEST_VARP, rangedVal)

        // War-Prep III — Survival: NONE (locked until War-Prep II finishes / not begun) is not started;
        // DONE is complete; anything between is in progress.
        val survival = WarPrepSurvival.step(p)
        val survivalVal = when {
            survival == WarPrepSurvival.Step.DONE -> WARPREP_SURVIVAL_QUEST_COMPLETE
            survival == WarPrepSurvival.Step.NONE -> 0 // not begun / locked
            else -> 1                                  // in progress
        }
        setVarpSafely(p, WARPREP_SURVIVAL_QUEST_VARP, survivalVal)

        // King of Lumbridge: NONE (not yet King / not begun) is not started; DONE is complete;
        // anything between is in progress.
        val conquest = Conquest.step(p)
        val conquestVal = when {
            conquest == Conquest.Step.DONE -> KING_QUEST_COMPLETE
            conquest == Conquest.Step.NONE -> 0 // not begun / locked
            else -> 1                           // in progress
        }
        setVarpSafely(p, KING_QUEST_VARP, conquestVal)
    }

    private fun setVarpSafely(p: Player, varp: Int, value: Int) {
        if (varp >= p.varps.maxVarps) return // defensive: never write out of range
        if (p.getVarp(varp) != value) p.setVarp(varp, value)
    }
}
