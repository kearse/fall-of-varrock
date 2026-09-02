package org.alter.plugins.content.quests.framework

import org.alter.api.ext.getVarp
import org.alter.api.ext.setVarp
import org.alter.game.model.attr.RECRUIT_GOBLIN_KILLS_ATTR
import org.alter.game.model.attr.SLAYER_TASK_NPC_ATTR
import org.alter.game.model.entity.Player
import org.alter.plugins.content.quests.QuestBook
import org.alter.plugins.content.quests.QuestJournal
import org.alter.plugins.content.war.Conquest
import org.alter.plugins.content.war.recruit.RecruitTrials
import org.alter.plugins.content.war.roguehunt.RogueProblem
import org.alter.plugins.content.war.warprep.WarPrepChain
import org.alter.plugins.content.war.warprep.WarPrepRanged
import org.alter.plugins.content.war.warprep.WarPrepSurvival

/**
 * **LEGACY** — thin [QuestChain] adapters over the six pre-Block-2 step-machine quests (seven
 * journal rows: the rogue chain is two). Each `publish` carries the exact varp packing that used
 * to live in `QuestJournal.sync`/`syncNativeTab`, byte-identical, so the client contract
 * (`LofQuestVarps`) is untouched. The chains themselves are unchanged; these only present them.
 * They retire with the chains once Block-2 quests replace the onboarding hallway.
 */
object LegacyChains {

    val all: List<QuestChain> = listOf(
        RecruitTrialsChain, WarPrepMagicChain, RogueHuntingIChain, RogueHuntingIIChain,
        WarPrepRangedChain, WarPrepSurvivalChain, KingChain,
    )

    private fun Player.writeIfChanged(varp: Int, value: Int) {
        if (getVarp(varp) != value) setVarp(varp, value)
    }

    object RecruitTrialsChain : QuestChain {
        override val key = "recruit_trials"
        override val displayName = "Recruit Trials"
        override val chainIndex = QuestBook.RECRUIT_TRIALS
        override fun started(p: Player): Boolean = RecruitTrials.step(p).ordinal > 0 // TALK = handed to every fresh citizen
        override fun complete(p: Player): Boolean = RecruitTrials.step(p) == RecruitTrials.Step.DONE
        override fun objectiveLine(p: Player): String {
            val s = RecruitTrials.step(p)
            if (s != RecruitTrials.Step.FIGHT) return s.objective
            return "${s.objective} (${p.attr[RECRUIT_GOBLIN_KILLS_ATTR] ?: 0}/${RecruitTrials.GOBLIN_GOAL})"
        }
        override fun publish(p: Player) {
            val recruitStep = RecruitTrials.step(p).ordinal and 0x3F
            val kills = (p.attr[RECRUIT_GOBLIN_KILLS_ATTR] ?: 0).coerceIn(0, 15)
            val contract = if (p.attr[SLAYER_TASK_NPC_ATTR] != null) 1 else 0
            p.writeIfChanged(QuestJournal.RECRUIT_VARP, recruitStep or (kills shl 6) or (contract shl 10))

            val recruit = RecruitTrials.step(p)
            val recruitVal = when {
                recruit == RecruitTrials.Step.DONE -> QuestJournal.RECRUIT_QUEST_COMPLETE
                recruit.ordinal == 0 -> 0 // TALK — not started yet
                else -> 1                 // in progress
            }
            QuestJournal.setVarpSafely(p, QuestJournal.RECRUIT_QUEST_VARP, recruitVal)
        }
    }

    object WarPrepMagicChain : QuestChain {
        override val key = "warprep_magic"
        override val displayName = "War-Prep I — Magic"
        override val chainIndex = QuestBook.WARPREP_MAGIC
        override fun started(p: Player): Boolean = WarPrepChain.started(p)
        override fun complete(p: Player): Boolean = WarPrepChain.complete(p)
        override fun objectiveLine(p: Player): String = WarPrepChain.objectiveLine(p)
        override fun publish(p: Player) {
            p.writeIfChanged(QuestJournal.WARPREP_VARP, WarPrepChain.step(p).ordinal)

            val warprep = WarPrepChain.step(p)
            val warprepVal = when {
                warprep == WarPrepChain.Step.DONE -> QuestJournal.WARPREP_QUEST_COMPLETE
                warprep == WarPrepChain.Step.NONE -> 0 // not begun / locked
                else -> 1                              // in progress
            }
            QuestJournal.setVarpSafely(p, QuestJournal.WARPREP_QUEST_VARP, warprepVal)
        }
    }

    /** The 30-rogue hunt — the first half of the shared rogue chain. Optional (design §8). */
    object RogueHuntingIChain : QuestChain {
        override val key = "rogue_hunting_1"
        override val displayName = "Rogue Hunting I"
        override val chainIndex = QuestBook.ROGUE_HUNTING_I
        override val optional = true
        override fun started(p: Player): Boolean = RogueProblem.started(p)
        override fun complete(p: Player): Boolean = RogueProblem.step(p).ordinal >= RogueProblem.Step.KNIGHT.ordinal
        override fun objectiveLine(p: Player): String = RogueProblem.statusLine(p)
        override fun publish(p: Player) {
            val rogueStep = RogueProblem.step(p).ordinal and 0x3F
            val rogueKills = RogueProblem.huntKills(p).coerceIn(0, 63)
            p.writeIfChanged(QuestJournal.ROGUE_PROBLEM_VARP, rogueStep or (rogueKills shl 6))

            // The Act II rogue chain drives TWO native rows, windowed off one Step machine:
            //  - Rogue Hunting I (the 30-rogue hunt): complete the moment the hunt clears (KNIGHT
            //    step onward); BRIEF/HUNT in progress; NONE not begun / locked.
            val rogue = RogueProblem.step(p)
            val huntVal = when {
                rogue.ordinal >= RogueProblem.Step.KNIGHT.ordinal -> QuestJournal.ROGUE_QUEST_COMPLETE
                rogue == RogueProblem.Step.NONE -> 0 // not begun / locked
                else -> 1                            // in progress
            }
            QuestJournal.setVarpSafely(p, QuestJournal.ROGUE_QUEST_VARP, huntVal)
        }
    }

    /** The Rogue Knight ladder — the second half of the shared rogue chain. Optional (design §8). */
    object RogueHuntingIIChain : QuestChain {
        override val key = "rogue_hunting_2"
        override val displayName = "Rogue Hunting II"
        override val chainIndex = QuestBook.ROGUE_HUNTING_II
        override val optional = true
        override fun started(p: Player): Boolean = RogueProblem.step(p).ordinal >= RogueProblem.Step.KNIGHT.ordinal
        override fun complete(p: Player): Boolean = RogueProblem.complete(p)
        override fun objectiveLine(p: Player): String = RogueProblem.statusLine(p)
        override fun publish(p: Player) {
            //  - Rogue Hunting II (the Rogue Knight ladder): locked until the hunt clears; complete
            //    when every camp is broken (DONE); the knight/report/ladder stretch is in progress.
            val rogue = RogueProblem.step(p)
            val ladderVal = when {
                rogue == RogueProblem.Step.DONE -> QuestJournal.ROGUE_LADDER_QUEST_COMPLETE
                rogue.ordinal >= RogueProblem.Step.KNIGHT.ordinal -> 1 // climbing
                else -> 0 // locked until Rogue Hunting I clears
            }
            QuestJournal.setVarpSafely(p, QuestJournal.ROGUE_LADDER_QUEST_VARP, ladderVal)
        }
    }

    object WarPrepRangedChain : QuestChain {
        override val key = "warprep_ranged"
        override val displayName = "War-Prep II — Ranged"
        override val chainIndex = QuestBook.WARPREP_RANGED
        override fun started(p: Player): Boolean = WarPrepRanged.started(p)
        override fun complete(p: Player): Boolean = WarPrepRanged.complete(p)
        override fun objectiveLine(p: Player): String = WarPrepRanged.objectiveLine(p)
        override fun publish(p: Player) {
            val rangedStep = WarPrepRanged.step(p).ordinal and 0x3F
            val rangedKills = WarPrepRanged.fieldKills(p).coerceIn(0, 63)
            p.writeIfChanged(QuestJournal.WARPREP_RANGED_VARP, rangedStep or (rangedKills shl 6))

            // War-Prep II — Ranged: NONE (locked until War-Prep I finishes / not begun) is not
            // started; DONE is complete; any drill/skirmish/rank step in between is in progress.
            val ranged = WarPrepRanged.step(p)
            val rangedVal = when {
                ranged == WarPrepRanged.Step.DONE -> QuestJournal.WARPREP_RANGED_QUEST_COMPLETE
                ranged == WarPrepRanged.Step.NONE -> 0 // not begun / locked
                else -> 1                              // in progress
            }
            QuestJournal.setVarpSafely(p, QuestJournal.WARPREP_RANGED_QUEST_VARP, rangedVal)
        }
    }

    object WarPrepSurvivalChain : QuestChain {
        override val key = "warprep_survival"
        override val displayName = "War-Prep III — Survival"
        override val chainIndex = QuestBook.WARPREP_SURVIVAL
        override fun started(p: Player): Boolean = WarPrepSurvival.started(p)
        override fun complete(p: Player): Boolean = WarPrepSurvival.complete(p)
        override fun objectiveLine(p: Player): String = WarPrepSurvival.objectiveLine(p)
        override fun publish(p: Player) {
            p.writeIfChanged(QuestJournal.WARPREP_SURVIVAL_VARP, WarPrepSurvival.step(p).ordinal and 0x3F)

            // War-Prep III — Survival: NONE (locked until War-Prep II finishes / not begun) is not started;
            // DONE is complete; anything between is in progress.
            val survival = WarPrepSurvival.step(p)
            val survivalVal = when {
                survival == WarPrepSurvival.Step.DONE -> QuestJournal.WARPREP_SURVIVAL_QUEST_COMPLETE
                survival == WarPrepSurvival.Step.NONE -> 0 // not begun / locked
                else -> 1                                  // in progress
            }
            QuestJournal.setVarpSafely(p, QuestJournal.WARPREP_SURVIVAL_QUEST_VARP, survivalVal)
        }
    }

    object KingChain : QuestChain {
        override val key = "king_of_lumbridge"
        override val displayName = "King of Lumbridge"
        override val chainIndex = QuestBook.KING
        override fun started(p: Player): Boolean = Conquest.started(p)
        override fun complete(p: Player): Boolean = Conquest.complete(p)
        override fun objectiveLine(p: Player): String = Conquest.statusLine(p)
        override fun publish(p: Player) {
            p.writeIfChanged(QuestJournal.CONQUEST_VARP, Conquest.step(p).ordinal and 0x3F)

            // King of Lumbridge: NONE (not yet King / not begun) is not started; DONE is complete;
            // anything between is in progress.
            val conquest = Conquest.step(p)
            val conquestVal = when {
                conquest == Conquest.Step.DONE -> QuestJournal.KING_QUEST_COMPLETE
                conquest == Conquest.Step.NONE -> 0 // not begun / locked
                else -> 1                           // in progress
            }
            QuestJournal.setVarpSafely(p, QuestJournal.KING_QUEST_VARP, conquestVal)
        }
    }
}
