package org.alter.plugins.content.combat.strategy.magic

import org.alter.api.Spellbook
import org.alter.api.ext.getInteractingNpc
import org.alter.api.ext.getInteractingPlayer
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.api.ext.setVarbit
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.entity.debugMagicSpells
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.Combat
import org.alter.plugins.content.magic.MagicSpells
import org.alter.plugins.content.magic.SpellMetadata

class CombatSpellsPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {

        if (!MagicSpells.isLoaded()) {
            MagicSpells.loadSpellRequirements(world)
        }

        MagicSpells.getCombatSpells().forEach { entry ->
            val requirement = entry.value
            val standard = requirement.spellbook == Spellbook.NORMAL.id
            val ancients = requirement.spellbook == Spellbook.ANCIENTS.id

            if (standard || ancients) {
                onSpellOnNpc(requirement.interfaceId, requirement.component) {
                    castCombatSpellOnPawn(player, player.getInteractingNpc(), requirement)
                }

                onSpellOnPlayer(requirement.interfaceId, requirement.component) {
                    castCombatSpellOnPawn(player, player.getInteractingPlayer(), requirement)
                }
            }
        }
    }

    fun castCombatSpellOnPawn(
        player: Player,
        pawn: Pawn,
        spellMetadata: SpellMetadata,
    ) {
        val combatSpell = CombatSpell.values.firstOrNull { spell -> spell.id == spellMetadata.paramItem }
        if (combatSpell != null) {
            player.attr[Combat.CASTING_SPELL] = combatSpell
            // The dedicated "choose spell to autocast" interface isn't wired on this server,
            // so casting a combat spell on a target also selects it for autocast: the combat
            // loop (CombatPlugin) re-applies CASTING_SPELL each attack while this varbit is
            // non-zero, and clearStaleAutocast() clears it when a non-magic weapon is equipped.
            // Result: cast once -> keep casting that spell, instead of re-clicking every tick.
            player.setVarbit(Combat.SELECTED_AUTOCAST_VARBIT, combatSpell.autoCastId)
            player.attack(pawn)
        } else {
            // Not a damage spell — dispatch to the non-damaging combat-spell effects (Tele Block,
            // etc.). The effect handler owns its level/rune checks and xp.
            if (CombatSpellEffects.tryExecute(player, pawn, spellMetadata)) return
            /*
             * The spell is not defined in [CombatSpell].
             */
            if (player.debugMagicSpells) {
                player.message("Undefined combat spell: [spellId=${spellMetadata.paramItem}, name=${spellMetadata.name}]")
            }
        }
    }
}
