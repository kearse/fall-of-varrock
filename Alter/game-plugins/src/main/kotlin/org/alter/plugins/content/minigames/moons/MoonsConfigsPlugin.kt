package org.alter.plugins.content.minigames.moons

import org.alter.api.dsl.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.deathAnimFor

/**
 * Combat definitions for the Moons of Peril (OSRS wiki infoboxes): 500 hp, level 329, 6-tick
 * attacks, attack 258 / strength 100 / defence 60 / magic 100, magic defence 500 (15% weak to
 * air), per-Moon melee defence (Blue: stab/slash 100, crush 0; Eclipse: slash/crush 100),
 * poison + venom immune. Rev 228's Moon defs carry no animation archives, so no attack/block
 * anims are set; death falls back through [deathAnimFor].
 */
class MoonsConfigsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        moon(Moons.Moon.BLOOD, stab = 60, slash = 60, crush = 60)
        moon(Moons.Moon.BLUE, stab = 100, slash = 100, crush = 0)
        moon(Moons.Moon.ECLIPSE, stab = 60, slash = 100, crush = 100)

        // Blood jaguar (40): a short-lived add — 100 hp, melee, stand 9091 / walk 9090 / attack 10958.
        setCombatDef(Moons.JAGUAR_KEY) {
            immunities { poison = true; venom = true }
            configs { attackSpeed = 4; respawnDelay = 0 }
            aggro { radius = 6; searchDelay = 1 }
            stats { hitpoints = 100; attack = 60; strength = 60; defence = 40; magic = 1; ranged = 1 }
            anims { attack = 10958; death = 9091 }
        }

        // Moon shield: a scripted prop (no options) that orbits the Eclipse Moon; anims 10973/10974.
        setCombatDef(Moons.SHIELD_KEY) {
            immunities { poison = true; venom = true }
            configs { attackSpeed = 10; respawnDelay = 0 }
            stats { hitpoints = 1 }
            anims { death = 10973 }
        }
    }

    private fun moon(m: Moons.Moon, stab: Int, slash: Int, crush: Int) {
        setCombatDef(m.npcKey) {
            immunities { poison = true; venom = true }
            configs { attackSpeed = Moons.ATTACK_SPEED; respawnDelay = 0 }
            aggro { radius = 12; searchDelay = 1 }
            stats { hitpoints = Moons.MOON_HP; attack = 258; strength = 100; defence = 60; magic = 100; ranged = 1 }
            bonuses { defenceStab = stab; defenceSlash = slash; defenceCrush = crush; defenceMagic = 500; defenceRanged = 1 }
            anims { death = deathAnimFor(m.npcKey) }
        }
    }
}
