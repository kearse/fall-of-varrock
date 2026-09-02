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
import org.alter.plugins.content.quests.QuestBook

/**
 * Wiring for [WarPrepRanged] (the "War-Prep II — Ranged" quest). Resumes/begins the per-player state
 * on login, drives the poll timer, counts quest-scoped kills made **with a ranged weapon** on the
 * FIELD step (cheap — bails instantly off the tracked step, mirroring [org.alter.plugins.content.war.roguehunt.RogueProblemPlugin]),
 * and serves `::warpranged`.
 *
 * The quest is *given* by Vannaka (see `SlayerPlugin`), who arms the marksman kit and debriefs;
 * this plugin only owns the passive wiring. It auto-begins the moment the player finishes War-Prep I
 * (see `WarPrepChain.grantCompletion` / the rank-bought hook in `LegacyRankHooks`), and the login
 * hook here catches anyone who finished War-Prep I before this gate existed.
 */
class WarPrepRangedPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onLogin {
            // Legacy / interrupted: a player already past War-Prep I never got the quest started.
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

        onCommand("warpranged", description = "Open War-Prep II (Ranged) in the Quest Journal") {
            player.message(WarPrepRanged.statusLine(player))
            QuestBook.open(player, QuestBook.WARPREP_RANGED)
        }
    }

    /** The FIELD test only counts kills scored with a ranged weapon (mirrors the combat-class check
     *  in `CombatConfigs`). */
    private fun killedWithRanged(p: Player): Boolean =
        p.hasWeaponType(WeaponType.BOW, WeaponType.CHINCHOMPA, WeaponType.CROSSBOW, WeaponType.THROWN)
}
