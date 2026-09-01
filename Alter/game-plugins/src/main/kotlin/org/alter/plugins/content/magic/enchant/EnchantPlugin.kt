package org.alter.plugins.content.magic.enchant

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
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

    /** An enchant recipe: result item, Magic xp, the tier's Magic level, and its rune cost. */
    private data class Result(val to: String, val xp: Double, val level: Int, val tier: Int)

    // Per-tier Magic level requirement (OSRS Enchant Jewellery). The unified cache spell can't
    // carry per-tier levels/runes, so each recipe names its own tier and we gate on it — the
    // old code used the single spell's (lvl-1) level + runes for EVERY tier, so a dragonstone
    // amulet -> glory enchanted at the sapphire cost.
    private val recipes = mapOf(
        "item.sapphire_ring" to Result("item.ring_of_recoil", 17.5, 7, 1),
        "item.sapphire_amulet" to Result("item.amulet_of_magic", 17.5, 7, 1),
        "item.emerald_ring" to Result("item.ring_of_dueling8", 37.0, 27, 2),
        "item.emerald_amulet" to Result("item.amulet_of_defence", 37.0, 27, 2),
        "item.ruby_ring" to Result("item.ring_of_forging", 59.0, 49, 3),
        "item.ruby_amulet" to Result("item.amulet_of_strength", 59.0, 49, 3),
        "item.diamond_ring" to Result("item.ring_of_life", 67.0, 57, 4),
        "item.diamond_amulet" to Result("item.amulet_of_power", 67.0, 57, 4),
        "item.dragonstone_ring" to Result("item.ring_of_wealth", 78.0, 68, 5),
        "item.dragonstone_amulet" to Result("item.amulet_of_glory", 78.0, 68, 5),
    )

    // Per-tier rune cost (item key -> amount), OSRS Enchant Jewellery.
    private val tierRunes: Map<Int, List<Pair<String, Int>>> = mapOf(
        1 to listOf("item.water_rune" to 1, "item.cosmic_rune" to 1),
        2 to listOf("item.air_rune" to 3, "item.cosmic_rune" to 1),
        3 to listOf("item.fire_rune" to 5, "item.cosmic_rune" to 1),
        4 to listOf("item.earth_rune" to 10, "item.cosmic_rune" to 1),
        5 to listOf("item.water_rune" to 15, "item.earth_rune" to 15, "item.cosmic_rune" to 1),
    )

    // Resolved once: target item id -> Result. Pairs with a missing cache key are dropped.
    private val byItemId: Map<Int, Result> = recipes.mapNotNull { (from, res) ->
        if (resolves(from) && resolves(res.to)) getRSCM(from) to res
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
        // Per-tier level + runes (not the unified spell's). Fall back to the spell's own runes
        // only if a tier's rune list can't be resolved.
        val runes = tierRunes[result.tier]
            ?.mapNotNull { (key, amt) -> runCatching { Item(getRSCM(key), amt) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: spell.items
        if (!MagicSpells.canCast(player, result.level, runes, requiredBook = spell.spellbook)) return
        if (player.inventory.remove(item = itemId, amount = 1, beginSlot = slot).completed == 0) return
        MagicSpells.removeRunes(player, runes)
        player.inventory.add(item = result.to.let { getRSCM(it) }, amount = 1)
        player.animate(ENCHANT_ANIM)
        player.graphic(ENCHANT_GFX)
        player.addXp(Skills.MAGIC, result.xp)
    }

    private fun resolves(key: String): Boolean = try { getRSCM(key) >= 0 } catch (e: Exception) { false }
}
