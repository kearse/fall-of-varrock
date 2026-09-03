package org.alter.plugins.content.quests

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.INTERACTING_SLOT_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.quests.framework.QuestFollow
import org.alter.plugins.content.quests.framework.QuestRegistry

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

        // ::quests               — open the journal on the active quest
        // ::quests follow <key>  — the guidance arrow tracks THAT quest's objective (03 §7: only the
        //                          followed quest drives arrows); ::quests follow (no key) clears it.
        onCommand("quests", description = "Open the Quest Journal; ::quests follow <quest> points the arrow at that quest") {
            val a = player.getCommandArgs()
            if (a.getOrNull(0).equals("follow", ignoreCase = true)) {
                val key = a.getOrNull(1)
                if (key == null) {
                    QuestFollow.clear(player)
                    player.message("You follow no quest in particular — the arrow tracks your deepest quest in progress.")
                } else if (QuestFollow.follow(player, key)) {
                    val name = QuestRegistry.byKey(key.trim().lowercase())?.displayName ?: key
                    player.message("<col=4f9b4f>Following $name — the guidance arrow tracks its objective.</col>")
                } else {
                    player.message("<col=801700>No quest called '$key'. Quests: ${QuestRegistry.all().filter { !it.hidden }.joinToString(", ") { it.key }}.</col>")
                }
                return@onCommand
            }
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

    /** The quest the window defaults to: the deepest in-progress listed quest, else the next
     *  main-road quest due to start (see [QuestRegistry.activeChainIndex]). */
    private fun activeChainIndex(p: Player): Int = QuestRegistry.activeChainIndex(p)
}
