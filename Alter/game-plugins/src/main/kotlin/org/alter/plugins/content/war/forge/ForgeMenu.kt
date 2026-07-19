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
        val commId = id(WarForge.COMMENDATION_KEY)
        val emberId = id(WarForge.EMBER_KEY)
        val barId = id("item.runite_bar")
        val coinId = id("item.coins_995")
        p.message(
            "${PREFIX}H|${WarForge.RECIPES.size}|$commId|$emberId|$barId|$coinId|" +
                "${count(p, commId)}|${count(p, emberId)}|${count(p, barId)}|${count(p, coinId)}",
            ChatMessageType.CONSOLE,
        )
        WarForge.RECIPES.forEachIndexed { i, r ->
            val baseId = id(r.baseKey)
            val outId = id(r.outKey)
            if (baseId < 0 || outId < 0) return@forEachIndexed
            p.message(
                "${PREFIX}R|$i|${r.style}|$baseId|$outId|${r.commendations}|${r.bars}|${r.coins}|${r.embers}|${count(p, baseId)}",
                ChatMessageType.CONSOLE,
            )
        }
    }

    private fun id(key: String): Int = runCatching { getRSCM(key) }.getOrDefault(-1)

    private fun count(p: Player, itemId: Int): Int = if (itemId < 0) 0 else p.inventory.getItemCount(itemId)
}
