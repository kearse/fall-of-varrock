package org.alter.plugins.content.war

import org.alter.api.ext.message
import org.alter.api.ext.npc
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.CITY_ID_ATTR
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.Player
import org.alter.plugins.content.bots.PkBot
import org.alter.plugins.content.drops.GenericDrops
// Aliased: this class has its own `companion object`, whose implicit name `Companion` would SHADOW
// a bare `import ...companion.Companion`, making `killer is Companion` always-false.
import org.alter.plugins.content.companion.Companion as CompanionPawn
import org.alter.plugins.content.companion.CompanionRegistry
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
        // Registering this per-id handler marks goblin (655) as handler-owned everywhere, which
        // suppresses the generic drop table globally — so a goblin killed OUTSIDE a raid must roll
        // the normal table here or it drops nothing anywhere.
        onNpcDeath("npc.goblin") {
            val goblin = npc
            val killer = goblin.attr[KILLER_ATTR]?.get() as? Player
            val front = Sieges.frontAt(goblin.tile)
            if (front == null || WarState.phaseOf(front) != WarState.Phase.UNDER_RAID) {
                if (killer != null) GenericDrops.rollAndDrop(world, goblin, killer)
                return@onNpcDeath
            }
            val credited = creditKiller(killer) ?: return@onNpcDeath
            credited.inventory.add(COINS, COINS_PER_GOBLIN)
            WarParticipation.record(front, credited, 1)
        }

        // --- Shock troops (hobgoblins) drop the richer war table + more contribution. ---
        onNpcDeath("npc.hobgoblin") {
            val hob = npc
            val killer = hob.attr[KILLER_ATTR]?.get() as? Player
            val front = Sieges.frontAt(hob.tile)
            if (front == null || WarState.phaseOf(front) != WarState.Phase.UNDER_RAID) {
                if (killer != null) GenericDrops.rollAndDrop(world, hob, killer)
                return@onNpcDeath
            }
            val credited = creditKiller(killer) ?: return@onNpcDeath
            WarDrops.onShockTroopKill(world, credited, hob)
            WarParticipation.record(front, credited, 3)
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

    /**
     * Resolve who actually earns a war kill: a companion's kill is credited to its human owner, and
     * any other fake player (PK bot) earns nothing — so war rewards, like boss spoils, can't leak to
     * a player's own NPC ally. (Check [CompanionPawn] before [PkBot]: Companion is a PkBot subclass.)
     */
    private fun creditKiller(killer: Player?): Player? = when (killer) {
        null -> null
        is CompanionPawn -> CompanionRegistry.ownerOf(world, killer)
        is PkBot -> null
        else -> killer
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
