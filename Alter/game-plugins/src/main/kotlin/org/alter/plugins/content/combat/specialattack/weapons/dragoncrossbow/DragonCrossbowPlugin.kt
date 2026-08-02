package org.alter.plugins.content.combat.specialattack.weapons.dragoncrossbow

import org.alter.api.ext.isMulti
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Npc
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.Combat
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.fireAmmoProjectile
import org.alter.plugins.content.combat.formula.RangedCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks
import org.alter.plugins.content.combat.strategy.RangedCombatStrategy
import org.alter.plugins.content.combat.PvpZones

/**
 * Dragon crossbow (21902) special attack: "Annihilate".
 * 60% energy, 20% extra damage; in multi-combat the bolt shatters and also strikes
 * attackable NPCs in the 3x3 around the target (OSRS Wiki, Dragon crossbow).
 */
class DragonCrossbowPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.dragon_crossbow", 60) {
            player.animate(id = 4230)
            player.fireAmmoProjectile(target)

            val delay = RangedCombatStrategy.getHitDelay(player.getCentreTile(), target.getCentreTile())
            val maxHit = RangedCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.2)
            val accuracy = RangedCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 1.0)
            player.dealHit(target = target, maxHit = maxHit, landHit = accuracy >= world.randomDouble(), delay = delay)

            // Multi splash: independent rolls against every other attackable NPC in the 3x3.
            if (PvpZones.isMulti(target.tile) || target.tile.isMulti(world)) {
                world.npcs.forEach { npc ->
                    if (npc != null && npc != target && !npc.isDead() &&
                        npc.def.isAttackable() && npc.combatDef.hitpoints != -1 &&
                        npc.tile.isWithinRadius(target.tile, 1) &&
                        Combat.canEngage(player, npc)
                    ) {
                        val splashLand = RangedCombatFormula.getAccuracy(player, npc, specialAttackMultiplier = 1.0) >= world.randomDouble()
                        player.dealHit(target = npc, maxHit = maxHit, landHit = splashLand, delay = delay)
                    }
                }
            }
        }
    }
}
