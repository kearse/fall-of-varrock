package org.alter.plugins.content.pvm.varrock

import org.alter.api.NpcSpecies
import org.alter.api.dsl.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/** Combat definitions for the Fallen Varrock PvM layer (FoV-original numbers; anims from each npc's own frame archive). */
class VarrockPvmConfigsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        VarrockPvm.ELITES.forEach { e ->
            setCombatDef(e.npcKey) {
                species { +NpcSpecies.UNDEAD }
                immunities { poison = true; venom = true }
                configs { attackSpeed = if (e.style == VarrockPvm.Style.MAGIC) 5 else 4; respawnDelay = 60 }
                aggro { radius = 5; searchDelay = 2 }
                stats {
                    hitpoints = e.hp
                    attack = 120; strength = 110; defence = 100; magic = if (e.style == VarrockPvm.Style.MAGIC) 120 else 1; ranged = 1
                }
                bonuses { defenceStab = 60; defenceSlash = 70; defenceCrush = 30; defenceMagic = 40; defenceRanged = 60 }
                anims { attack = e.attackAnim; block = e.blockAnim; death = e.deathAnim }
            }
        }

        // Malachai the Hollow — a ghoul champion in the streets: 450 hp, hard-hitting melee, a wail.
        setCombatDef(VarrockPvm.HOLLOW_KEY) {
            species { +NpcSpecies.UNDEAD }
            immunities { poison = true; venom = true }
            configs { attackSpeed = 4; respawnDelay = 0 }
            aggro { radius = 8; searchDelay = 1 }
            stats { hitpoints = VarrockPvm.HOLLOW_HP; attack = 170; strength = 150; defence = 140; magic = 100; ranged = 1 }
            bonuses { defenceStab = 90; defenceSlash = 110; defenceCrush = 40; defenceMagic = 80; defenceRanged = 100 }
            anims { attack = 822; block = 823; death = 820 }
        }

        // The Palace Warden — a zombie champion holding the palace: 900 hp, melee + necrotic bolt.
        setCombatDef(VarrockPvm.WARDEN_KEY) {
            species { +NpcSpecies.UNDEAD }
            immunities { poison = true; venom = true }
            configs { attackSpeed = 5; respawnDelay = 0 }
            aggro { radius = 10; searchDelay = 1 }
            stats { hitpoints = VarrockPvm.WARDEN_HP; attack = 200; strength = 190; defence = 180; magic = 180; ranged = 1 }
            bonuses { defenceStab = 120; defenceSlash = 140; defenceCrush = 30; defenceMagic = 120; defenceRanged = 150 }
            anims { attack = 5571; block = 5578; death = 5575 }
        }
    }
}
