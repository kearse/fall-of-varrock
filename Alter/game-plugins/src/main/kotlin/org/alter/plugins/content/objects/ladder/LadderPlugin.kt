package org.alter.plugins.content.objects.ladder

import dev.openrune.cache.CacheManager.getObject
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
import org.alter.game.model.move.moveTo
import org.alter.game.model.queue.*
import org.alter.game.model.shop.*
import org.alter.game.model.timer.*
import org.alter.game.plugin.*
import org.alter.plugins.content.minigames.wizardtower.WizardTower

class LadderPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        /**Stairs*/

        val stairs =
            arrayOf(
                "object.staircase_16672",
                "object.staircase_16673",
                "object.staircase_16671",
            )

        stairs.forEach { stairs ->
            if (objHasOption(obj = stairs, option = "climb")) {
                onObjOption(obj = stairs, option = "climb") {
                    climbstairs(player)
                }
            }
            if (objHasOption(obj = stairs, option = "climb-up")) {
                onObjOption(obj = stairs, option = "climb-up") {
                    climbupstairs(player)
                }
            }
            if (objHasOption(obj = stairs, option = "climb-down")) {
                onObjOption(obj = stairs, option = "climb-down") {
                    climbdownstairs(player)
                }
            }
        }

        /** Wizard's Tower spiral staircases (ground/1st/2nd floor). These object ids
         *  aren't in the RSCM name table, so bind them by raw id. Options vary per
         *  floor, so guard each bind — onObjOption(Int) throws (and drops the plugin)
         *  if the option is absent. Inside a Wizard Tower minigame run the minigame owns
         *  the climb entirely (its floors are side-by-side islands, and climbing up is
         *  gated on clearing the floor) — WizardTower.handleClimb returns false for
         *  anyone not in a run, who then gets the normal stairs. */
        intArrayOf(12536, 12537, 12538).forEach { stair ->
            val actions = getObject(stair).actions.map { it?.lowercase() }
            if (actions.contains("climb")) {
                onObjOption(stair, option = "climb") {
                    player.queue {
                        when (options(player, "Climb up the stairs.", "Climb down the stairs.")) {
                            1 -> if (!WizardTower.handleClimb(player, up = true)) climbupstairs(player)
                            2 -> if (!WizardTower.handleClimb(player, up = false)) climbdownstairs(player)
                        }
                    }
                }
            }
            if (actions.contains("climb-up")) {
                onObjOption(stair, option = "climb-up") { if (!WizardTower.handleClimb(player, up = true)) climbupstairs(player) }
            }
            if (actions.contains("climb-down")) {
                onObjOption(stair, option = "climb-down") { if (!WizardTower.handleClimb(player, up = false)) climbdownstairs(player) }
            }
        }

        /**Ladders*/

        val ladders =
            arrayOf(
                "object.ladder_12964",
                "object.ladder_12965",
                "object.ladder_16683",
                "object.ladder_12966",
                "object.ladder_16679",
                "object.ladder_16684",
            )

        ladders.forEach { ladder ->
            if (objHasOption(obj = ladder, option = "climb")) {
                onObjOption(obj = ladder, option = "climb") {
                    climbladder(player)
                }
            }
            if (objHasOption(obj = ladder, option = "climb-up")) {
                onObjOption(obj = ladder, option = "climb-up") {
                    climbupladder(player)
                }
            }
            if (objHasOption(obj = ladder, option = "climb-down")) {
                onObjOption(obj = ladder, option = "climb-down") {
                    climbdownladder(player)
                }
            }
        }

        /**Trapdoors.*/

        onObjOption("object.trapdoor_14880", option = "climb-down") {
            player.moveTo(3210, 9616, 0)
        }
        onObjOption("object.ladder_17385", option = "climb-up") {
            player.moveTo(3210, 3216, 0)
        }
    }

    /**Function for ladders.*/

    fun climbupladder(player: Player) {
        player.queue {
            player.animate(828)
            player.lock()
            wait(2)
            player.moveTo(player.tile.x, player.tile.z, player.tile.height + 1)
            player.unlock()
        }
    }

    fun climbdownladder(player: Player) {
        player.queue {
            player.animate(828)
            player.lock()
            wait(2)
            player.moveTo(player.tile.x, player.tile.z, player.tile.height - 1)
            player.unlock()
        }
    }

    fun climbladder(player: Player) {
        player.queue {
            when (options(player, "Climb up the ladder.", "Climb down the ladder")) {
                1 -> climbupladder(player)
                2 -> climbdownladder(player)
            }
        }
    }

    /**Function for stairs.*/

    fun climbupstairs(player: Player) {
        player.moveTo(player.tile.x, player.tile.z, player.tile.height + 1)
    }

    fun climbdownstairs(player: Player) {
        player.moveTo(player.tile.x, player.tile.z, player.tile.height - 1)
    }

    fun climbstairs(player: Player) {
        player.queue {
            when (options(player, "Climb up the stairs.", "Climb down the stairs.")) {
                1 -> climbupstairs(player)
                2 -> climbdownstairs(player)
            }
        }
    }
}
