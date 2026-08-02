package org.alter.plugins.content.combat.specialattack.weapons.voidwaker

import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealExactHit
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Voidwaker (item 27690) special attack: "Disrupt" — the real OSRS behaviour.
 *
 * 50% energy. A guaranteed hit (no accuracy roll) dealing between 50% and 150% of the
 * wielder's maximum melee hit, as magic damage. The old testing-era instakill (1% energy,
 * NPC AoE) has been removed; an admin-only variant can come back later under its own
 * item id.
 *
 * Visual IDs sourced from the rsmod symbol tables (cache name -> id):
 *  - seq 11275      = human_special02_voidwaker (player animation)
 *  - spotanim 2834  = fx_voidwaker02_special (cast graphic on the player)
 *  - spotanim 2363  = fx_voidwaker_impact (impact graphic on the target)
 */
class VoidwakerPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.voidwaker", 50) {
            player.animate(id = 11275)
            player.graphic(id = 2834, height = 124)

            val maxHit = MeleeCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.0)
            // Uniform 50%-150% of the melee max hit.
            val damage = (maxHit / 2) + world.random(maxHit)
            player.dealExactHit(target = target, damage = damage, delay = 0)
            target.graphic(id = 2363)
        }
    }
}
