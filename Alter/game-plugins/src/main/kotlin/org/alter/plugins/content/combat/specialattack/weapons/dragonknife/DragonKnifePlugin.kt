package org.alter.plugins.content.combat.specialattack.weapons.dragonknife

import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.fireAmmoProjectile
import org.alter.plugins.content.combat.formula.RangedCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Dragon knife special attack: "Rapid Fire".
 * 25% energy. Throws two dragon knives in quick succession.
 *
 * Registered for EVERY wieldable dragon-knife def, not just the unpoisoned 22804.
 * [SpecialAttacks] keys on the exact worn item id, so the poisoned knives â€” which is what almost
 * everyone actually throws â€” had no special at all and the orb did nothing (player report
 * 2026-09-18, "dragon knives / throw axe unable to spec"). 22812/22814 sit in the SHIELD slot in
 * this cache and are deliberately left out; 27157 is a weapon-slot duplicate def and is included.
 */
class DragonKnifePlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        for (knife in KNIVES) {
            runCatching {
                SpecialAttacks.register(knife, 25) {
                    player.animate(id = 8291)
                    player.fireAmmoProjectile(target)

                    for (i in 0 until 2) {
                        val maxHit = RangedCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.0)
                        val accuracy = RangedCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 1.0)
                        player.dealHit(target = target, maxHit = maxHit, landHit = accuracy >= world.randomDouble(), delay = 1 + i)
                    }
                }
            }
        }
    }

    private companion object {
        val KNIVES = listOf(
            "item.dragon_knife",        // 22804
            "item.dragon_knifep",       // 22806 (p)
            "item.dragon_knifep_22808", // 22808 (p+)
            "item.dragon_knifep_22810", // 22810 (p++)
            "item.dragon_knife_27157",  // 27157 duplicate weapon-slot def
        )
    }
}
