package org.alter.plugins.content.war.forge

import org.alter.api.ChatMessageType
import org.alter.api.ext.message
import org.alter.api.ext.setVarp
import org.alter.game.model.entity.Player
import org.alter.rscm.RSCM.getRSCM

/**
 * Server half of the client-drawn **War Forge** window (`lofforge`): the Royal Smith's recipes as
 * base → result rows with real item icons and a live material checklist, replacing the nested
 * dialogue menus.
 *
 * State rides `~LOFFORGE~` CONSOLE lines (parsed + hidden client-side):
 *   header  `~LOFFORGE~H|<n>|commId|emberId|barId|coinId|commHave|embersHave|barsHave|coinsHave`
 *   recipes `~LOFFORGE~R|<i>|<style>|baseId|outId|comm|bars|coins|embers|baseHave`
 * The window opens on a varp 4627 pulse; the forge comes back as `::forge make <i>` → `forgeclick`
 * (handled in [RoyalSmithPlugin], which owns the rank gate + broadcast).
 */
object ForgeMenu {
    /** Overlay-open varp (docs/overlay-design-system.md §8) — pulsed to 0, never persisted. */
    const val OPEN_VARP = 4627

    private const val PREFIX = "~LOFFORGE~"

    fun open(p: Player) {
        push(p)
        p.setVarp(OPEN_VARP, 1)
        p.queue { wait(2); p.setVarp(OPEN_VARP, 0) }
    }

    /** Push recipes + carried counts (also called after a forging so the checklist updates). */
    fun push(p: Player) {
        val commId = safeId(WarForge.COMMENDATION_KEY)
        val emberId = safeId(WarForge.EMBER_KEY)
        val barId = safeId("item.runite_bar")
        val coinId = safeId("item.coins_995")
        // Resolve FIRST and advertise the resolved count — the client only commits the list once
        // it has that many rows, so skipping unresolvable recipes after promising the full count
        // would leave the window permanently empty. Rows keep their ORIGINAL recipe index (the
        // "::forge make <i>" contract).
        val rows = WarForge.RECIPES.mapIndexedNotNull { i, r ->
            val baseId = safeId(r.baseKey)
            val outId = safeId(r.outKey)
            if (baseId < 0 || outId < 0) null else Triple(i, r, baseId to outId)
        }
        p.message(
            "${PREFIX}H|${rows.size}|$commId|$emberId|$barId|$coinId|" +
                "${count(p, commId)}|${count(p, emberId)}|${count(p, barId)}|${count(p, coinId)}",
            ChatMessageType.CONSOLE,
        )
        rows.forEach { (i, r, ids) ->
            val (baseId, outId) = ids
            p.message(
                "${PREFIX}R|$i|${r.style}|$baseId|$outId|${r.commendations}|${r.bars}|${r.coins}|${r.embers}|${count(p, baseId)}",
                ChatMessageType.CONSOLE,
            )
        }
    }

    private fun safeId(key: String): Int = runCatching { getRSCM(key) }.getOrDefault(-1) // -1 = unresolvable (unlike WarForge.id, which throws)

    private fun count(p: Player, itemId: Int): Int = if (itemId < 0) 0 else p.inventory.getItemCount(itemId)
}
