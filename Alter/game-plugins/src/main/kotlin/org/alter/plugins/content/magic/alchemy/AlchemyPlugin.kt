package org.alter.plugins.content.magic.alchemy

import dev.openrune.cache.CacheManager.getItem
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.ext.getInteractingItemId
import org.alter.api.ext.getInteractingItemSlot
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.service.game.ItemMetadataService
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.economy.SpecialShopGuard
import org.alter.plugins.content.magic.MagicSpells
import org.alter.plugins.content.magic.SpellMetadata
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **High/Low Alchemy** (the non-combat Magic gp faucet + item sink). Cast the alchemy
 * spell on an inventory item → turn it into coins (high = 60% of value, low = 40%),
 * consuming the spell's runes and granting Magic xp. A controlled gp faucet AND an item
 * sink (the item is destroyed). Bound by spell-on-item to the inventory (149,0).
 */
class AlchemyPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        if (!MagicSpells.isLoaded()) MagicSpells.loadSpellRequirements(world)
        MagicSpells.getMiscSpells().values.forEach { spell ->
            val n = spell.name.lowercase()
            when {
                n.contains("high level alchemy") -> bind(spell, 0.6, 65.0, HIGH_ALCH_DELAY)
                n.contains("low level alchemy") -> bind(spell, 0.4, 31.0, LOW_ALCH_DELAY)
            }
        }
    }

    private fun bind(spell: SpellMetadata, rate: Double, xp: Double, delayTicks: Int) {
        onSpellOnItem(spell.interfaceId, spell.component, INV_INTERFACE, INV_COMPONENT) {
            alch(player, spell, rate, xp, delayTicks)
        }
    }

    private fun alch(player: Player, spell: SpellMetadata, rate: Double, xp: Double, delayTicks: Int) {
        // OSRS cast cadence: high alch is a 5-tick action, low alch 3 (the Kronos alchDelay
        // pattern). Without this gate the spell chains as fast as packets arrive — an
        // unbounded gp/xp faucet.
        if (player.timers.has(ALCH_DELAY)) return
        val itemId = player.getInteractingItemId()
        val slot = player.getInteractingItemSlot()
        if (itemId == getRSCM("item.coins_995")) {
            player.message("You can't alchemise coins.")
            return
        }
        // Bonds/tickets must NEVER mint gold (bond spec §2.3; tickets are PvM/vote point
        // currencies, not gp). Hard-denied here.
        if (itemId in unalchable) {
            player.message("You can't alchemise that.")
            return
        }
        // Untradeables (halos, prestige/rank capes, ...) are point-shop rewards whose cache
        // costs were never balanced as gp — alching them is an uncontrolled points→gp mint.
        if (!getItem(itemId).isTradeable) {
            player.message("You can't alchemise that.")
            return
        }
        // Special-currency shop wares (Boss Ticket gear etc.): alch was the other half of the
        // ticket-shop→gp infinite loop (Justiciar chest: 1.2m in tickets → 3.6m alch).
        if (SpecialShopGuard.isGuarded(itemId)) {
            player.message("You can't alchemise that.")
            return
        }
        if (!MagicSpells.canCast(player, spell.lvl, spell.items, requiredBook = spell.spellbook)) return
        // Explicit YAML alch overrides win over the cost-derived value; an explicit 0
        // (TradeableCapes.yml's fire cape) means "this must never pay out" — refuse the
        // cast entirely rather than eating the item for nothing.
        val explicit = if (rate >= 0.6) ItemMetadataService.highAlchOverride(itemId) else ItemMetadataService.lowAlchOverride(itemId)
        if (explicit != null && explicit <= 0) {
            player.message("You can't alchemise that.")
            return
        }
        val value = explicit ?: (getItem(itemId).cost * rate).toInt().coerceAtLeast(1)
        if (player.inventory.remove(item = itemId, amount = 1, beginSlot = slot).completed == 0) return
        MagicSpells.removeRunes(player, spell.items)
        player.inventory.add(item = getRSCM("item.coins_995"), amount = value)
        player.addXp(Skills.MAGIC, xp)
        player.animate(ALCH_ANIM)
        player.timers[ALCH_DELAY] = delayTicks
    }

    /** Items that may never be alched (gold-faucet guard). Resolved defensively at init. */
    private val unalchable: Set<Int> =
        listOf("item.bond", "item.bond_untradeable", "item.boss_ticket", "item.vote_ticket")
            .mapNotNull { key -> runCatching { getRSCM(key) }.getOrNull() }
            .toSet()

    private companion object {
        const val INV_INTERFACE = 149
        const val INV_COMPONENT = 0
        const val ALCH_ANIM = 713
        const val HIGH_ALCH_DELAY = 5
        const val LOW_ALCH_DELAY = 3

        /** Gate between alchemy casts (shared by high/low — OSRS blocks cross-casting too). */
        val ALCH_DELAY = TimerKey()
    }
}
