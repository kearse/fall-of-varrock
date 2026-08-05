package org.alter.plugins.content.quests

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.INTERACTING_SLOT_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.war.Conquest
import org.alter.plugins.content.war.roguehunt.RogueProblem
import org.alter.plugins.content.war.warprep.WarPrepChain
import org.alter.plugins.content.war.warprep.WarPrepRanged
import org.alter.plugins.content.war.warprep.WarPrepSurvival

private val logger = KotlinLogging.logger {}

/**
 * Wiring for the [QuestBook] window: the native quest-tab click ([QUEST_TAB], component 7 — armed
 * by `CharacterSummaryPlugin`) and the `::quests` command. The per-quest status commands open the
 * window focused on their own quest from their own plugins (they still print their status line).
 */
class QuestBookPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        // A quest row was clicked in the stock quest tab. The slot the client sends is the row's
        // position in the (hidden/relabelled) list, whose order mirrors QuestTablePatch.PLAN — which
        // is the same order as the client chain (Recruit, War-Prep I, Rogue Hunting I/II, War-Prep
        // II/III, King), so a 0..6 slot maps 1:1 to the chain index. A slot outside that range can
        // only be a raw col0 quest id (a fallback below). The exact slot semantics of a DBTable list
        // can only be *confirmed* in-game, so log the raw value on first run, then drop this log.
        onButton(QUEST_TAB, QUEST_LIST_COMPONENT) {
            val slot = player.attr[INTERACTING_SLOT_ATTR] ?: return@onButton
            logger.info { "[questbook] quest-tab click slot=$slot by ${player.username}" } // TEMP: confirm slot→quest, then remove
            val idx = chainIndexForSlot(slot) ?: return@onButton
            QuestBook.open(player, idx)
        }

        onCommand("quests", description = "Open the Quest Journal window") {
            QuestBook.open(player, activeChainIndex(player))
        }
    }

    /** Map a clicked quest-tab slot to a chain index (row-position first, col0-id as fallback). */
    private fun chainIndexForSlot(slot: Int): Int? =
        if (slot in 0..QuestBook.KING) slot else COL0_TO_CHAIN[slot]

    private companion object {
        const val QUEST_TAB = 399
        const val QUEST_LIST_COMPONENT = 7

        /** Reused OSRS quest col0 id → our chain index (the QuestTablePatch.PLAN mapping), used only
         *  if the click ever delivers a col0 id instead of a row position. */
        val COL0_TO_CHAIN = mapOf(
            1 to QuestBook.RECRUIT_TRIALS,      // Cook's Assistant
            11 to QuestBook.WARPREP_MAGIC,      // Doric's Quest
            3 to QuestBook.ROGUE_HUNTING_I,     // The Restless Ghost
            14 to QuestBook.ROGUE_HUNTING_II,   // The Knight's Sword
            9 to QuestBook.WARPREP_RANGED,      // Imp Catcher
            5 to QuestBook.WARPREP_SURVIVAL,    // Sheep Shearer
            13 to QuestBook.KING,               // Witch's Potion
        )
    }

    /** The quest the window defaults to: the deepest in-progress chain quest, else the next one
     *  the player is due to start, else Recruit Trials. Mirrors the client's active-quest pick. */
    private fun activeChainIndex(p: Player): Int {
        val r = RogueProblem.step(p).ordinal
        val knight = RogueProblem.Step.KNIGHT.ordinal
        return when {
            Conquest.started(p) && !Conquest.complete(p) -> QuestBook.KING
            WarPrepSurvival.started(p) && !WarPrepSurvival.complete(p) -> QuestBook.WARPREP_SURVIVAL
            WarPrepRanged.started(p) && !WarPrepRanged.complete(p) -> QuestBook.WARPREP_RANGED
            r >= knight && !RogueProblem.complete(p) -> QuestBook.ROGUE_HUNTING_II
            RogueProblem.started(p) && !RogueProblem.complete(p) -> QuestBook.ROGUE_HUNTING_I
            WarPrepChain.started(p) && !WarPrepChain.complete(p) -> QuestBook.WARPREP_MAGIC
            // nothing in progress — point at the next unstarted rung
            RogueProblem.complete(p) -> QuestBook.WARPREP_RANGED
            r >= knight -> QuestBook.ROGUE_HUNTING_II
            WarPrepChain.complete(p) -> QuestBook.ROGUE_HUNTING_I
            else -> QuestBook.RECRUIT_TRIALS
        }
    }
}
