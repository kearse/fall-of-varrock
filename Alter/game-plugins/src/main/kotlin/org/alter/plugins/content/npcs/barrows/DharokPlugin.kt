package org.alter.plugins.content.npcs.barrows

import org.alter.api.*
import org.alter.api.cfg.*
import org.alter.api.dsl.*
import org.alter.api.ext.*
import org.alter.game.*
import org.alter.game.model.*
import org.alter.game.model.attr.*
import org.alter.game.model.container.*
import org.alter.game.model.container.key.*
import org.alter.game.model.entity.*
import org.alter.game.model.item.*
import org.alter.game.model.queue.*
import org.alter.game.model.shop.*
import org.alter.game.model.timer.*
import org.alter.game.plugin.*

class DharokPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {
        
    init {
        // No overworld spawn: Dharok is summoned in his crypt by BarrowsMinigamePlugin
        // (search the sarcophagus). This file only registers his combat def.

        setCombatDef("npc.dharok_the_wretched") {
            species { +NpcSpecies.UNDEAD }

            configs {
                attackSpeed = 4 // TUNE: barrows tick rate (wiki-verify)
                respawnDelay = 50
            }

            aggro {
                radius = 4
                searchDelay = 2
            }

            stats {
                hitpoints = 115
                attack = 130
                strength = 130
                defence = 130
            }

            bonuses {
                attackBonus = 80
                strengthBonus = 80
                defenceStab = 100
                defenceSlash = 100
                defenceCrush = 100
                defenceMagic = 100
                defenceRanged = 100
            }

            anims {
                attack = 2067 // greataxe swing
                block = 424
                death = 2925
            }

            immunities { poison = true }
        }
    }
}
