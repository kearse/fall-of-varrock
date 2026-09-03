package org.alter.plugins.content.pvm.senntisten

import org.alter.api.NpcSpecies
import org.alter.api.dsl.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/** Combat definitions for the temple's wardens and the Custodian (FoV-original numbers; anims from each skeleton's own archive). */
class SenntistenConfigsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        Senntisten.WARDENS.forEach { w ->
            setCombatDef(w.npcKey) {
                species { +NpcSpecies.UNDEAD }
                immunities { poison = true; venom = true }
                configs { attackSpeed = if (w.style == Senntisten.Style.MAGIC) 5 else 4; respawnDelay = 0 }
                aggro { radius = 10; searchDelay = 1 }
                stats {
                    hitpoints = w.hp
                    attack = 130; strength = 120; defence = 110; magic = if (w.style == Senntisten.Style.MAGIC) 130 else 1; ranged = 1
                }
                bonuses { defenceStab = 70; defenceSlash = 80; defenceCrush = 40; defenceMagic = 50; defenceRanged = 70 }
                anims { attack = w.attackAnim; block = w.blockAnim; death = w.deathAnim }
            }
        }

        // The Custodian — a lesser demon champion bound to the altar: 700 hp, melee + magic, 5 ticks.
        setCombatDef(Senntisten.CUSTODIAN_KEY) {
            species { +NpcSpecies.DEMON }
            immunities { poison = true; venom = true }
            configs { attackSpeed = 5; respawnDelay = 0 }
            aggro { radius = 12; searchDelay = 1 }
            stats { hitpoints = Senntisten.CUSTODIAN_HP; attack = 190; strength = 180; defence = 170; magic = 190; ranged = 1 }
            bonuses { defenceStab = 110; defenceSlash = 120; defenceCrush = 60; defenceMagic = 140; defenceRanged = 130 }
            anims { attack = 4678; block = 4679; death = 4677 }
        }
    }
}
