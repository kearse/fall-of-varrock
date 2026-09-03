package org.alter.plugins.content.magic.lunar

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.Vengeance
import org.alter.plugins.content.magic.MagicSpells
import org.alter.plugins.content.magic.SpellMetadata

private val logger = KotlinLogging.logger {}

/**
 * **Lunar Vengeance** (net-new Magic content / the marquee Lunar PK spell). Binds the Vengeance
 * spell button (cache: interface 218, component 142, combat-type) and arms the reflect buff via
 * [Vengeance]. Requires the Lunar spellbook (reachable through SpellbookSwapPlugin), Magic 94 and
 * the spell's runes — all enforced by [MagicSpells.canCast]. 30s cooldown.
 */
class VengeancePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private companion object {
        const val VENGEANCE_XP = 112.0
    }

    init {
        if (!MagicSpells.isLoaded()) MagicSpells.loadSpellRequirements(world)

        val spell = MagicSpells.getCombatSpells().values.firstOrNull { it.name.equals("Vengeance", ignoreCase = true) }
        if (spell != null) {
            onButton(spell.interfaceId, spell.component) { cast(player, spell) }
            logger.info { "vengeance: bound at ${spell.interfaceId}:${spell.component} (lvl ${spell.lvl})" }
        } else {
            logger.warn { "vengeance: spell not found in cache metadata; not bound." }
        }
    }

    private fun cast(player: Player, spell: SpellMetadata) {
        if (Vengeance.isActive(player)) {
            player.message("You already have Vengeance active.")
            return
        }
        if (!Vengeance.isReady(player)) {
            player.message("You can only cast Vengeance once every 30 seconds.")
            return
        }
        if (!MagicSpells.canCast(player, spell.lvl, spell.items, requiredBook = spell.spellbook, spellName = spell.name)) return
        MagicSpells.removeRunes(player, spell.items, spellName = spell.name)
        Vengeance.activate(player)
        player.addXp(Skills.MAGIC, VENGEANCE_XP)
    }
}
