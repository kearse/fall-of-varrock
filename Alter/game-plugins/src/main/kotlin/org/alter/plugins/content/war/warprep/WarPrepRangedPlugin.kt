package org.alter.plugins.content.war.warprep

import org.alter.api.WeaponType
import org.alter.api.ext.hasWeaponType
import org.alter.api.ext.message
import org.alter.api.ext.npc
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Wiring for [WarPrepRanged] (the "War-Prep II — Ranged" quest). Resumes/begins the per-player state
 * on login, drives the poll timer, counts quest-scoped kills made **with a ranged weapon** on the
 * FIELD step (cheap — bails instantly off the tracked step, mirroring [org.alter.plugins.content.war.roguehunt.RogueProblemPlugin]),
 * and serves `::warpranged`.
 *
 * The quest is *given* by Vannaka (see `SlayerPlugin`), who drills the recruit, arms the marksman kit
 * and debriefs; this plugin only owns the passive wiring. It auto-begins the moment the player finishes
 * The Rogue Problem (the Knight rank-up — see `RankPurchase`), and the login hook here catches anyone
 * who reached Knight before this quest existed.
 */
class WarPrepRangedPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onLogin {
            // Legacy / interrupted: a player already past The Rogue Problem never got the quest started.
            WarPrepRanged.begin(player)
            WarPrepRanged.resumeOnLogin(player)
        }

        onTimer(WarPrepRanged.TIMER) { WarPrepRanged.pollTick(player) }

        onAnyNpcDeath {
            val killer = npc.attr[KILLER_ATTR]?.get() as? Player ?: return@onAnyNpcDeath
            if (WarPrepRanged.step(killer) == WarPrepRanged.Step.FIELD && killedWithRanged(killer)) {
                WarPrepRanged.onRangedKill(killer)
            }
        }

        onCommand("warpranged", description = "Show your War-Prep II (Ranged) quest objective") {
            player.message(WarPrepRanged.statusLine(player))
        }
    }

    /** The FIELD test only counts kills scored with a ranged weapon (mirrors the combat-class check
     *  in `CombatConfigs`). */
    private fun killedWithRanged(p: Player): Boolean =
        p.hasWeaponType(WeaponType.BOW, WeaponType.CHINCHOMPA, WeaponType.CROSSBOW, WeaponType.THROWN)
}
