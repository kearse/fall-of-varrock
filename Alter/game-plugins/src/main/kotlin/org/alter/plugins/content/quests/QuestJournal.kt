package org.alter.plugins.content.quests

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.cfg.Varbit
import org.alter.api.cfg.Varp
import org.alter.api.ext.getVarbit
import org.alter.api.ext.getVarp
import org.alter.api.ext.message
import org.alter.api.ext.setVarbit
import org.alter.api.ext.setVarp
import org.alter.game.model.attr.QUEST_GUIDE_MUTED_ATTR
import org.alter.game.model.entity.Player
import org.alter.plugins.content.bots.knights.RogueKnightLadder
import org.alter.plugins.content.bots.knights.RogueKnights
import org.alter.plugins.content.quests.framework.QuestEngine
import org.alter.plugins.content.quests.framework.QuestRegistry

private val logger = KotlinLogging.logger {}

/**
 * **Quest Journal client feed** — publishes every quest's live state to the varps the custom
 * RuneLite client's Quest Journal plugin (`lofquests`) reads, and owns the **free-play guidance
 * mute** ([QUEST_GUIDE_MUTED_ATTR]) that lets a player switch the chains' hint arrows off.
 *
 * Since Block 1 the per-quest packing lives with each quest: [sync] walks
 * [QuestRegistry.all] and calls `publish` on every chain — the legacy chains' adapters
 * (`quests/framework/LegacyChains.kt`) carry the exact packings below, byte-identical; framework
 * quests publish through `QuestEngine.publish`. This object keeps the varp CONTRACT (the ids and
 * their layouts, which must match the client plugin's `LofQuestVarps`) plus the two non-quest
 * feeds (the Rogue Knight ladder dial and the mute flag).
 *
 * Varp layout:
 *  - [RECRUIT_VARP] (The Last Free City) packed: bits 0-5 = `RecruitTrials.Step` in STORY order
 *    (`RecruitTrials.clientOrdinal` — DEBRIEF 11, DONE 12), bits 6-9 = goblins defeated on the FIGHT
 *    step (0-15 clamp), bit 10 = slayer war-contract taken (splits the SLAY arrow's "see Vannaka"
 *    vs "hunt the goblins" phases).
 *  - [WARPREP_VARP] = `WarPrepChain.Step` ordinal.
 *  - [ROGUE_PROBLEM_VARP] packed: bits 0-5 = `RogueProblem.Step` ordinal, bits 6-11 = rogues felled
 *    on the HUNT step (0-63 clamp) so the client can render the "(x/30)" progress.
 *  - [WARPREP_RANGED_VARP] packed: bits 0-5 = `WarPrepRanged.Step` ordinal, bits 6-11 = enemies felled
 *    with a ranged weapon on the FIELD step (0-63 clamp) so the client can render the "(x/20)" progress.
 *  - [WARPREP_SURVIVAL_VARP] bits 0-5 = `WarPrepSurvival.Step` ordinal.
 *  - [CONQUEST_VARP] bits 0-5 = `Conquest.Step` ordinal (the endgame "King of Lumbridge" quest).
 *  - [KNIGHTS_VARP] packed: bits 0-7 = Rogue Knight ladder rank (knights beaten, 0-255 clamp),
 *    bits 8-15 = the active hunt's ladder index + 1 (0 = no active hunt), bits 16-19 = rogue camps
 *    cleared (0-15), bits 20-23 = total knight-hosting camps (0-15) — the last two feed the rogue
 *    quest window's left-side dial; the live tracking arrow is server-driven (`RogueKnightCampPlugin`).
 *  - [GUIDE_MUTED_VARP] = 1 while guidance is muted, else 0 (so the client toggle reflects state).
 *
 * Varps 4600-4608, 4613-4616, 4618-4623, 4625-4626 and 4633-4637 are taken by the other client HUDs,
 * and 4640-4679 is the kit editor's block (control + one varp per kit slot — 4643/4644/4645 briefly
 * squatted inside it, which made ::kits pop the quest journal once the varp-table ceiling fix let
 * kit publishes complete; renumbered out). Quests own 4610-4612, 4617, 4624, 4633, and 4681-4683
 * ([WARPREP_SURVIVAL_VARP], [KNIGHTS_VARP], and [QuestBook.OPEN_VARP] — the "open the Quest Journal
 * window, focused on quest N" pulse; not published here, pulsed on demand). Framework quests that
 * need a journal varp claim one in docs/overlay-design-system.md §8 (`QuestDefinition.journalVarp`)
 * — [FIRST_RECLAMATION_VARP] is the first such claim.
 * Non-zero varps persist ([VarpSerialisation]), but the attributes stay the source of truth —
 * everything here is re-derived and re-published on login and on the world poll.
 *
 * **Native quest tab.** Each custom quest's state is also mirrored into the progress varp of an
 * OSRS quest we've relabelled in the cache (see the `questTable` cache tool + docs/quest-tab-handoff.md).
 * The rev-228 quest-list clientscript colours each row by running `QUEST_STATUS_GET(<quest id>)`,
 * which resolves that quest's progress varp — so writing the reused quest's varp to
 * not-started / in-progress / complete drives the stock tab's red / yellow / green with no
 * clientscript edits. The `[not-started, complete]` values come from the dumped quest table
 * (`col19`) / known OSRS completion values.
 */
object QuestJournal {

    const val RECRUIT_VARP = 4610
    const val WARPREP_VARP = 4611
    const val GUIDE_MUTED_VARP = 4612
    const val ROGUE_PROBLEM_VARP = 4617 // 4613-4616 belong to the companion + slayer HUDs
    const val WARPREP_RANGED_VARP = 4624   // War-Prep II — Ranged (4618-4623, 4625-4626 belong to other HUDs)
    const val WARPREP_SURVIVAL_VARP = 4681 // War-Prep III — Survival (was 4643: kit editor's block)
    const val CONQUEST_VARP = 4633      // King of Lumbridge (endgame); 4635-4637 are companion indices
    const val KNIGHTS_VARP = 4682       // Rogue Knight ladder (rank + active hunt index; was 4644)

    // Framework quests (generic `QuestEngine.publish` packing: step index+1 bits 0-7 | progress
    // bits 8-19 | state bits 20-21) take ids from the 4686-4699 block reserved in
    // docs/overlay-design-system.md §8 — one per quest, recorded there individually.
    /** The North (Main Story Quest 3, `quests/north/TheNorth`). */
    const val NORTH_VARP = 4686
    /** First Reclamation (Main Story Quest 4, `quests/story/FirstReclamation`). */
    const val FIRST_RECLAMATION_VARP = 4687
    /** A Kingdom Alone (Main Story Quest 5, `quests/story/AKingdomAlone`). */
    const val KINGDOM_ALONE_VARP = 4688
    /** The regional phase's four strategic objectives (`quests/story/StrategicObjectives`). */
    const val BREACH_VARP = 4689
    const val SECURE_VARP = 4690
    const val UNDERSTAND_VARP = 4691
    const val SUSTAIN_VARP = 4692
    /** At the White Wall (Asgarnia — BREACH, Quest 1, `quests/asgarnia/AtTheWhiteWall`). Its native
     *  quest-tab row is the relabelled Recruitment Drive (varp 657, complete 2) — see `AtTheWhiteWall.nativeTabVarp`. */
    const val WHITE_WALL_VARP = 4693

    // Reused OSRS quest progress varps that colour the relabelled native quest-tab rows. A value of
    // 0 reads as "not started" (red), the complete value as "finished" (green), anything between as
    // "in progress" (yellow). Keep these in lock-step with the `questTable` tool's REUSE table.
    /** Cook's Assistant varp — now the "The Last Free City" row (the Recruit Trials chain). Completes at 2. */
    const val RECRUIT_QUEST_VARP = 29
    internal const val RECRUIT_QUEST_COMPLETE = 2
    /** Doric's Quest varp — now the "War-Prep I — Magic" row. Completes at 100. */
    const val WARPREP_QUEST_VARP = 31
    internal const val WARPREP_QUEST_COMPLETE = 100
    /** The Restless Ghost varp — now the "Rogue Hunting I" row (the 30-rogue hunt). Completes at 5. */
    const val ROGUE_QUEST_VARP = 107
    internal const val ROGUE_QUEST_COMPLETE = 5
    /** The Knight's Sword varp — now the "Rogue Hunting II" row (the Rogue Knight ladder).
     *  Completes at 7. */
    const val ROGUE_LADDER_QUEST_VARP = 122
    internal const val ROGUE_LADDER_QUEST_COMPLETE = 7
    /** Imp Catcher varp — now the "War-Prep II — Ranged" row. Completes at 2. */
    const val WARPREP_RANGED_QUEST_VARP = 160
    internal const val WARPREP_RANGED_QUEST_COMPLETE = 2
    /** Sheep Shearer varp — now the "War-Prep III — Survival" row. Completes at 21. */
    const val WARPREP_SURVIVAL_QUEST_VARP = 179
    internal const val WARPREP_SURVIVAL_QUEST_COMPLETE = 21
    /** Witch's Potion varp — now the "King of Lumbridge" row. Completes at 3. */
    const val KING_QUEST_VARP = 67
    internal const val KING_QUEST_COMPLETE = 3
    /** Ernest the Chicken varp — now the "The North" row (a framework quest: driven by
     *  `QuestEngine.publish` from `TheNorth.nativeTabVarp`). Completes at 3. */
    const val NORTH_QUEST_VARP = 32
    internal const val NORTH_QUEST_COMPLETE = 3
    /** Romeo & Juliet varp — now the "First Reclamation" row (driven by `QuestDefinition.nativeTabVarp`
     *  through `QuestEngine.publish`). Completes at 100. */
    const val FIRST_RECLAMATION_QUEST_VARP = 144
    internal const val FIRST_RECLAMATION_QUEST_COMPLETE = 100
    /** Rune Mysteries varp — now the "A Kingdom Alone" row (Main Story Quest 5). Completes at 6. */
    const val KINGDOM_ALONE_QUEST_VARP = 63
    internal const val KINGDOM_ALONE_QUEST_COMPLETE = 6
    /** Black Knights' Fortress varp — now the "BREACH - Asgarnia" objective row. Completes at 4. */
    const val BREACH_QUEST_VARP = 130
    internal const val BREACH_QUEST_COMPLETE = 4
    /** Prince Ali Rescue varp — now the "SECURE - Morytania" objective row. Completes at 110. */
    const val SECURE_QUEST_VARP = 273
    internal const val SECURE_QUEST_COMPLETE = 110
    /** Vampyre Slayer varp — now the "UNDERSTAND - Wilderness / Desert" objective row. Completes at 3. */
    const val UNDERSTAND_QUEST_VARP = 178
    internal const val UNDERSTAND_QUEST_COMPLETE = 3
    /** Pirate's Treasure varp — now the "SUSTAIN - Kandarin / War Effort" objective row. Completes at 4. */
    const val SUSTAIN_QUEST_VARP = 71
    internal const val SUSTAIN_QUEST_COMPLETE = 4

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
        // Every quest — legacy adapters + framework — publishes its own varps (isolated: one
        // throwing chain never blanks the others).
        QuestRegistry.all().forEach { chain ->
            runCatching { chain.publish(p) }.onFailure { logger.error(it) { "Quest publish failed: ${chain.key} for ${p.username}" } }
        }

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

        // Native summary tab: quest points + quest counts from the registry (framework quests carry
        // `questPoints`; the legacy chains award none). Replaces the zero placeholders the character
        // summary used to seed on login.
        val listed = QuestRegistry.all().filter { it.chainIndex != null && !it.hidden }
        val completed = listed.count { runCatching { it.complete(p) }.getOrDefault(false) }
        val framework = QuestRegistry.frameworkQuests().filter { !it.adminOnly }
        val questPoints = framework.filter { QuestEngine.isComplete(p, it) }.sumOf { it.questPoints }
        val questPointsTotal = framework.sumOf { it.questPoints }
        if (p.getVarp(Varp.QUEST_POINTS) != questPoints) p.setVarp(Varp.QUEST_POINTS, questPoints)
        if (p.getVarbit(Varbit.TOTAL_QUEST_POINT_COUNT) != questPointsTotal) p.setVarbit(Varbit.TOTAL_QUEST_POINT_COUNT, questPointsTotal)
        if (p.getVarbit(Varbit.TOTAL_QUEST_COUNT) != listed.size) p.setVarbit(Varbit.TOTAL_QUEST_COUNT, listed.size)
        if (p.getVarbit(Varbit.COMPLETED_QUESTS_COUNT) != completed) p.setVarbit(Varbit.COMPLETED_QUESTS_COUNT, completed)

        val mutedFlag = if (muted(p)) 1 else 0
        if (p.getVarp(GUIDE_MUTED_VARP) != mutedFlag) p.setVarp(GUIDE_MUTED_VARP, mutedFlag)
    }

    /** Write a native quest-tab row varp: bounds-checked, only on change. */
    internal fun setVarpSafely(p: Player, varp: Int, value: Int) {
        if (varp >= p.varps.maxVarps) return // defensive: never write out of range
        if (p.getVarp(varp) != value) p.setVarp(varp, value)
    }
}
