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
 */
object QuestJournal {

    const val RECRUIT_VARP = 4610
    const val WARPREP_VARP = 4611
    const val GUIDE_MUTED_VARP = 4612

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
    }
}
