package org.alter.plugins.content.combat.specialattack.weapons.dragondagger

import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.AreaSound
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

class DragonDaggerPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {
        
    init {

        val SPECIAL_REQUIREMENT = 25

        // The poisoned variants (dragon dagger(p/p+/p++)) are the ICONIC PK spec weapon —
        // registering only the base id left every poisoned dagger with a dead spec bar.
        for (item in listOf(
            "item.dragon_dagger",
            "item.dragon_daggerp",
            "item.dragon_daggerp_5680",
            "item.dragon_daggerp_5698",
        )) {
            SpecialAttacks.register(item, SPECIAL_REQUIREMENT) {
                player.animate(id = 1062)
                player.graphic(id = 252, height = 92)
                world.spawn(AreaSound(tile = player.tile, id = 2537, radius = 10, volume = 1))

                // OSRS "Puncture": two independent hits, both 15% extra accuracy and 15% extra
                // max, landing together on the same tick as a normal melee hit would.
                for (i in 0 until 2) {
                    val maxHit = MeleeCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.15)
                    val accuracy = MeleeCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 1.15)
                    val landHit = accuracy >= world.randomDouble()
                    player.dealHit(target = target, maxHit = maxHit, landHit = landHit, delay = 0)
                }
            }
        }
    }
}
