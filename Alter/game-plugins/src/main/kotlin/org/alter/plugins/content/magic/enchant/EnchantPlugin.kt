package org.alter.plugins.content.magic.enchant

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.ext.*
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
 * **Enchant jewellery** (net-new Magic content). This cache (rev 228) exposes enchanting as a
 * single unified **"Jewellery Enchantments"** spell rather than the old Lvl-1..7 buttons, so we
 * bind that one spell and pick the result from the *target* gem item. Cast on a gem ring/amulet
 * → its enchanted form, consuming runes and granting Magic xp. Each pair is cache-resolved and
 * guarded; unresolved pairs are skipped.
 */
class EnchantPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private data class Result(val to: String, val xp: Double)

    // from gem-jewellery -> (enchanted result, Magic xp). Amulets + rings (most-used) for now;
    // necklaces/bracelets are an easy follow-on once their result keys are confirmed.
    private val recipes = mapOf(
        "item.sapphire_ring" to Result("item.ring_of_recoil", 17.5),
        "item.sapphire_amulet" to Result("item.amulet_of_magic", 17.5),
        "item.emerald_ring" to Result("item.ring_of_dueling8", 37.0),
        "item.emerald_amulet" to Result("item.amulet_of_defence", 37.0),
        "item.ruby_ring" to Result("item.ring_of_forging", 59.0),
        "item.ruby_amulet" to Result("item.amulet_of_strength", 59.0),
        "item.diamond_ring" to Result("item.ring_of_life", 67.0),
        "item.diamond_amulet" to Result("item.amulet_of_power", 67.0),
        "item.dragonstone_ring" to Result("item.ring_of_wealth", 78.0),
        "item.dragonstone_amulet" to Result("item.amulet_of_glory", 78.0),
    )

    // Resolved once: target item id -> (result id, xp). Pairs with a missing cache key are dropped.
    private val byItemId: Map<Int, Pair<Int, Double>> = recipes.mapNotNull { (from, res) ->
        if (resolves(from) && resolves(res.to)) getRSCM(from) to (getRSCM(res.to) to res.xp)
        else { logger.info { "enchant: skipping '$from'->'${res.to}' (unresolved)" }; null }
    }.toMap()

    private companion object {
        const val INV_INTERFACE = 149
        const val INV_COMPONENT = 0
        const val ENCHANT_ANIM = 719
        const val ENCHANT_GFX = 115
    }

    init {
        if (!MagicSpells.isLoaded()) MagicSpells.loadSpellRequirements(world)

        var bound = false
        MagicSpells.getMiscSpells().values.forEach { spell ->
            if (spell.name.lowercase().contains("jewellery enchantment")) {
                onSpellOnItem(spell.interfaceId, spell.component, INV_INTERFACE, INV_COMPONENT) { enchant(player, spell) }
                bound = true
            }
        }
        logger.info { "enchant: jewellery-enchant spell bound=$bound, ${byItemId.size} recipes" }
    }

    private fun enchant(player: Player, spell: SpellMetadata) {
        val itemId = player.getInteractingItemId()
        val slot = player.getInteractingItemSlot()
        val result = byItemId[itemId]
        if (result == null) {
            player.message("You can't enchant that.")
            return
        }
        if (!MagicSpells.canCast(player, spell.lvl, spell.items, requiredBook = spell.spellbook)) return
        if (player.inventory.remove(item = itemId, amount = 1, beginSlot = slot).completed == 0) return
        MagicSpells.removeRunes(player, spell.items)
        player.inventory.add(item = result.first, amount = 1)
        player.animate(ENCHANT_ANIM)
        player.graphic(ENCHANT_GFX)
        player.addXp(Skills.MAGIC, result.second)
    }

    private fun resolves(key: String): Boolean = try { getRSCM(key) >= 0 } catch (e: Exception) { false }
}
