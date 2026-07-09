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
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
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
                n.contains("high level alchemy") -> bind(spell, 0.6, 65.0)
                n.contains("low level alchemy") -> bind(spell, 0.4, 31.0)
            }
        }
    }

    private fun bind(spell: SpellMetadata, rate: Double, xp: Double) {
        onSpellOnItem(spell.interfaceId, spell.component, INV_INTERFACE, INV_COMPONENT) {
            alch(player, spell, rate, xp)
        }
    }

    private fun alch(player: Player, spell: SpellMetadata, rate: Double, xp: Double) {
        val itemId = player.getInteractingItemId()
        val slot = player.getInteractingItemSlot()
        // Loaner-kit seal (gold-faucet guard, like the bond rule below): everything a PK-training
        // trainee holds mid-bout is borrowed — alching a loaned Dharok piece would mint its full
        // cache value in coins, and the coins survive the end-of-round gear restore.
        if (org.alter.plugins.content.minigames.pktraining.TrainingArena.kitted(player)) {
            player.message("You can't alchemise borrowed gear.")
            return
        }
        if (itemId == getRSCM("item.coins_995")) {
            player.message("You can't alchemise coins.")
            return
        }
        // Bonds must NEVER mint gold (bond spec §2.3): 13190's cache cost is 2m and can't be
        // overridden, so alching one would be a 1.2m faucet per $4.99. Hard-denied here.
        if (itemId in unalchable) {
            player.message("Bonds cannot be alchemised.")
            return
        }
        if (!MagicSpells.canCast(player, spell.lvl, spell.items, requiredBook = spell.spellbook)) return
        val value = (getItem(itemId).cost * rate).toInt().coerceAtLeast(1)
        if (player.inventory.remove(item = itemId, amount = 1, beginSlot = slot).completed == 0) return
        MagicSpells.removeRunes(player, spell.items)
        player.inventory.add(item = getRSCM("item.coins_995"), amount = value)
        player.addXp(Skills.MAGIC, xp)
        player.animate(ALCH_ANIM)
    }

    /** Items that may never be alched (gold-faucet guard). Resolved defensively at init. */
    private val unalchable: Set<Int> = listOf("item.bond", "item.bond_untradeable")
        .mapNotNull { key -> runCatching { getRSCM(key) }.getOrNull() }
        .toSet()

    private companion object {
        const val INV_INTERFACE = 149
        const val INV_COMPONENT = 0
        const val ALCH_ANIM = 713
    }
}
