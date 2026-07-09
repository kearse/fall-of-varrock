package org.alter.plugins.content.war

import org.alter.api.ext.message
import org.alter.api.ext.npc
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.CITY_ID_ATTR
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.Player
import org.alter.rscm.RSCM.getRSCM
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * The **player-facing consumer** of The War. [SiegePlugin] / [AttackDirector] run the
 * battle and own its broadcasts; this plugin only rewards players for joining in and
 * warns them when their city is in danger, per-front across every city in [Sieges]:
 *  - loots **coins** per rabble goblin killed in a war field, and the richer **shock-troop
 *    table** ([WarDrops]) per hobgoblin (the Warlord table lives in [SiegePlugin]);
 *  - records each kill as **war-effort contribution** ([WarParticipation]) so a winning
 *    defense pays out rare loot ([RaidRewards]);
 *  - **warns** players logging in while THEIR city is under raid or fallen.
 */
class WarEffortPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val fronts: List<String> = Sieges.all.map { it.frontId }

    init {
        // --- Coins + contribution per rabble goblin killed in a war field. ---
        onNpcDeath("npc.goblin") {
            val goblin = npc
            val front = Sieges.frontAt(goblin.tile) ?: return@onNpcDeath
            if (WarState.phaseOf(front) != WarState.Phase.UNDER_RAID) return@onNpcDeath
            val killer = goblin.attr[KILLER_ATTR]?.get() as? Player ?: return@onNpcDeath
            killer.inventory.add(COINS, COINS_PER_GOBLIN)
            WarParticipation.record(front, killer, 1)
        }

        // --- Shock troops (hobgoblins) drop the richer war table + more contribution. ---
        onNpcDeath("npc.hobgoblin") {
            val hob = npc
            val front = Sieges.frontAt(hob.tile) ?: return@onNpcDeath
            if (WarState.phaseOf(front) != WarState.Phase.UNDER_RAID) return@onNpcDeath
            val killer = hob.attr[KILLER_ATTR]?.get() as? Player ?: return@onNpcDeath
            WarDrops.onShockTroopKill(world, killer, hob)
            WarParticipation.record(front, killer, 3)
        }

        // --- Warn players who log in while THEIR city is in trouble. ---
        onLogin {
            val worst = playerFronts(player)
                .firstOrNull { WarState.phaseOf(it) != WarState.Phase.PEACE } ?: return@onLogin
            when (WarState.phaseOf(worst)) {
                WarState.Phase.CITY_FALLEN ->
                    player.message("<col=ff4f4f>${cityName(worst)} has fallen to the goblins - General Zo is rallying to retake it. Take care.</col>")
                WarState.Phase.UNDER_RAID ->
                    player.message("<col=ff4f4f>${cityName(worst)} is under a goblin raid! The knights are fighting at the front - lend a blade.</col>")
                else -> {}
            }
        }
    }

    /** The fronts of the player's city (falls back to all fronts if unassigned). */
    private fun playerFronts(player: Player): List<String> =
        Cities.byId(player.attr[CITY_ID_ATTR] ?: Cities.DEFAULT_CITY_ID)?.fronts ?: fronts

    private fun cityName(front: String): String = Sieges.byFront(front)?.displayName ?: "The frontier"

    private companion object {
        const val COINS_PER_GOBLIN = 50 // coins looted per goblin a player kills
        val COINS = getRSCM("item.coins_995")
    }
}
