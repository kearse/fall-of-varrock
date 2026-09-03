package org.alter.plugins.content.magic.teleblock

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.PrayerIcon
import org.alter.api.Skills
import org.alter.api.ext.hasPrayerIcon
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.formula.MagicCombatFormula
import org.alter.plugins.content.combat.strategy.magic.CombatSpellEffects
import org.alter.plugins.content.magic.MagicSpells
import org.alter.plugins.content.magic.TeleBlock

private val logger = KotlinLogging.logger {}

/**
 * **Tele Block** (net-new Magic content / the other marquee PK spell). Cast on a player to stop
 * them teleporting for 5 minutes. Registers a non-damaging combat-spell effect (so it shares the
 * spellbook cast path without colliding with the damage-spell bindings) and the worn-off message.
 * Enforcement lives in [TeleBlock] + canTeleport. Requires Magic 85 + runes (checked by canCast).
 */
class TeleBlockPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private companion object {
        const val TELEBLOCK_XP_LAND = 95.0  // OSRS: 95 xp on a successful cast
        const val TELEBLOCK_XP_SPLASH = 84.0 // 84 xp on a splash (failed magic roll)
        const val CAST_ANIM = 1819
        const val SPLASH_GFX = 85
    }

    init {
        CombatSpellEffects.register("Tele Block") { caster, target, spell ->
            if (target !is Player) {
                caster.message("You can only Tele Block other players.")
                return@register
            }
            if (TeleBlock.isBlocked(target)) {
                caster.message("That player is already affected by Tele Block.")
                return@register
            }
            if (!MagicSpells.canCast(caster, spell.lvl, spell.items, requiredBook = spell.spellbook, spellName = spell.name)) return@register
            MagicSpells.removeRunes(caster, spell.items, spellName = spell.name)
            caster.animate(CAST_ANIM)
            // OSRS: teleblock rolls magic accuracy; a miss splashes (no block, less xp).
            if (MagicCombatFormula.getAccuracy(caster, target) < caster.world.randomDouble()) {
                caster.addXp(Skills.MAGIC, TELEBLOCK_XP_SPLASH)
                target.graphic(SPLASH_GFX, 96)
                caster.message("Your spell splashes harmlessly against ${target.username}.")
                return@register
            }
            caster.addXp(Skills.MAGIC, TELEBLOCK_XP_LAND)
            // OSRS: Protect from Magic halves the teleblock to 2.5 minutes.
            val ticks =
                if (target.hasPrayerIcon(PrayerIcon.PROTECT_FROM_MAGIC)) {
                    TeleBlock.DURATION_TICKS / 2
                } else {
                    TeleBlock.DURATION_TICKS
                }
            TeleBlock.apply(target, ticks)
            target.message("You have been teleblocked.")
            caster.message("You teleblock ${target.username}.")
        }

        onTimer(TeleBlock.TIMER) {
            player.message("Your Tele Block has worn off.")
        }

        logger.info { "teleblock: effect registered." }
    }
}
