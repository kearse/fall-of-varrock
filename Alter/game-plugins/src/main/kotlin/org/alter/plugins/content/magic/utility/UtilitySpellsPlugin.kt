package org.alter.plugins.content.magic.utility

import dev.openrune.cache.CacheManager.getItem
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
 * **Standard-spellbook utility spells** (net-new Magic content): Superheat Item and
 * Bones to Bananas / Bones to Peaches. Reuses the [MagicSpells] metadata + the AlchemyPlugin
 * binding pattern (spell-on-item for superheat, spell button for bones-to-fruit). Spells are
 * matched by a keyword in their cache name; unmatched/unresolved entries are skipped.
 */
class UtilitySpellsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    /** A smeltable bar: all ores it needs + coal, the Smithing xp, and the spell-cast min level. */
    private data class Bar(val bar: String, val ores: Map<String, Int>, val coal: Int, val smithXp: Double)

    // Superheat recipes (mirror SmithingPlugin's smelt data). Steel is omitted because it shares
    // iron ore with the iron bar, which would make the ore→bar trigger ambiguous.
    private val bars = listOf(
        Bar("item.bronze_bar", mapOf("item.copper_ore" to 1, "item.tin_ore" to 1), 0, 6.2),
        Bar("item.iron_bar", mapOf("item.iron_ore" to 1), 0, 12.5),
        Bar("item.mithril_bar", mapOf("item.mithril_ore" to 1), 4, 30.0),
        Bar("item.adamantite_bar", mapOf("item.adamantite_ore" to 1), 6, 37.5),
        Bar("item.runite_bar", mapOf("item.runite_ore" to 1), 8, 50.0),
    ).filter { res(it.bar) && it.ores.keys.all { o -> res(o) } && (it.coal == 0 || res("item.coal")) }

    // Triggering ore -> bar (first bar wins per ore).
    private val oreToBar: Map<Int, Bar> = buildMap {
        bars.forEach { bar -> bar.ores.keys.forEach { ore -> putIfAbsent(getRSCM(ore), bar) } }
    }

    private companion object {
        const val INV_INTERFACE = 149
        const val INV_COMPONENT = 0
        const val SUPERHEAT_MAGIC_XP = 53.0
        const val SUPERHEAT_ANIM = 725
    }

    init {
        if (!MagicSpells.isLoaded()) MagicSpells.loadSpellRequirements(world)

        val bound = mutableListOf<String>()
        MagicSpells.getMiscSpells().values.forEach { spell ->
            val n = spell.name.lowercase()
            when {
                n.contains("superheat") -> {
                    onSpellOnItem(spell.interfaceId, spell.component, INV_INTERFACE, INV_COMPONENT) { superheat(player, spell) }
                    bound += spell.name
                }
                n.contains("bones to bananas") -> {
                    onButton(spell.interfaceId, spell.component) { bonesToFruit(player, spell, "item.banana") }
                    bound += spell.name
                }
                n.contains("bones to peaches") -> {
                    onButton(spell.interfaceId, spell.component) { bonesToFruit(player, spell, "item.peach") }
                    bound += spell.name
                }
            }
        }
        logger.info { "utility-spells: bound ${bound.size} (${bound.joinToString()}); superheat recipes=${oreToBar.size}" }
    }

    private fun superheat(player: Player, spell: SpellMetadata) {
        val oreId = player.getInteractingItemId()
        val bar = oreToBar[oreId]
        if (bar == null) {
            player.message("You can't superheat that.")
            return
        }
        if (!MagicSpells.canCast(player, spell.lvl, spell.items, requiredBook = spell.spellbook)) return

        // Verify all required ores + coal are present before consuming anything.
        for ((ore, amt) in bar.ores) {
            if (player.inventory.getItemCount(getRSCM(ore)) < amt) {
                player.message("You don't have enough ore to make this bar.")
                return
            }
        }
        if (bar.coal > 0 && player.inventory.getItemCount(getRSCM("item.coal")) < bar.coal) {
            player.message("You need ${bar.coal} coal to superheat this ore.")
            return
        }

        MagicSpells.removeRunes(player, spell.items)
        bar.ores.forEach { (ore, amt) -> player.inventory.remove(item = getRSCM(ore), amount = amt) }
        if (bar.coal > 0) player.inventory.remove(item = getRSCM("item.coal"), amount = bar.coal)
        player.inventory.add(item = getRSCM(bar.bar), amount = 1)
        player.animate(SUPERHEAT_ANIM)
        player.addXp(Skills.MAGIC, SUPERHEAT_MAGIC_XP)
        player.addXp(Skills.SMITHING, bar.smithXp)
        player.message("You superheat the ore into a ${getItem(getRSCM(bar.bar)).name?.lowercase() ?: "bar"}.")
    }

    private fun bonesToFruit(player: Player, spell: SpellMetadata, fruitKey: String) {
        if (!res("item.bones") || !res(fruitKey)) return
        if (!MagicSpells.canCast(player, spell.lvl, spell.items, requiredBook = spell.spellbook)) return

        val bonesId = getRSCM("item.bones")
        val count = player.inventory.getItemCount(bonesId)
        if (count == 0) {
            player.message("You aren't holding any bones!")
            return
        }
        MagicSpells.removeRunes(player, spell.items)
        player.inventory.remove(item = bonesId, amount = count)
        player.inventory.add(item = getRSCM(fruitKey), amount = count)
        player.animate(722)
        player.addXp(Skills.MAGIC, if (fruitKey == "item.peach") 35.5 else 25.0)
    }

    private fun res(key: String): Boolean = try { getRSCM(key) >= 0 } catch (e: Exception) { false }
}
