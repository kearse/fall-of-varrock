package org.alter.plugins.content.war

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.npc
import org.alter.api.ext.openDoor
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.collision.WALL_DIAGONAL
import org.alter.game.model.combat.NpcCombatDef
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.model.priv.Privilege
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.plugins.content.objects.door.DoorService

private val logger = KotlinLogging.logger {}

/**
 * The generic **defense driver**. Runs every city in [Sieges]: builds a [WarFront] per
 * battlefield + the two [Commander]s, owns one [AttackDirector] per city (the War Brain),
 * ticks them, and holds each garrison's gate doors open so knights can always march out.
 *
 * Everything city-specific is data in [Sieges]; adding a city changes no code here.
 * [WarEffortPlugin] is the player-facing *consumer* (loot, broadcasts, login warnings).
 */
class SiegePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    /** Mild default combat def so natural (non-war) goblins still fight; war goblins get
     *  their stronger stats pushed in by [WarFront]. */
    private val baseGoblinDef = NpcCombatDef.DEFAULT.copy(
        attack = 25, strength = 25, defence = 18, hitpoints = 18,
        attackAnimation = 6184, blockAnimation = 6183, deathAnimation = listOf(6182),
        aggressiveRadius = 6, aggroTargetDelay = 8, aggressiveTimer = 200,
    )

    private fun broadcast(msg: String) = world.players.forEach { it.message(msg) }

    private val directors: List<AttackDirector>

    init {
        directors = Sieges.all.map { config ->
            setCombatDef(config.attackerNpc, baseGoblinDef)
            val fields = config.fields.map { build(config, it) }
            val goblinCommander = Commander(
                frontId = config.frontId, fields = fields,
                side = Commander.Side.ATTACKER, doctrine = Commander.Doctrine.EXPLOIT_GAP,
                baseGarrison = config.baseGarrison,
            )
            val knightCommander = Commander(
                frontId = config.frontId, fields = fields,
                side = Commander.Side.DEFENDER, doctrine = Commander.Doctrine.REINFORCE_THREAT,
                baseGarrison = config.baseGarrison,
            )
            AttackDirector(config, fields, goblinCommander, knightCommander, ::broadcast)
        }

        // Consistency check: each defense must link to a real city that lists its front.
        directors.forEach { d ->
            val city = Cities.byId(d.config.cityId)
            when {
                city == null -> logger.warn { "Defense '${d.config.frontId}' points at unknown cityId ${d.config.cityId}." }
                d.config.frontId !in city.fronts -> logger.warn { "City '${city.displayName}' doesn't list front '${d.config.frontId}'." }
                else -> logger.info { "Defense '${d.config.frontId}' defends ${city.displayName}." }
            }
        }

        // Killing a Warlord routs that raid's horde (disrupt + morale collapse) + loot.
        directors.forEach { d ->
            onNpcDeath(d.config.warlordNpc) {
                val dead = npc
                if (dead === d.warlord) {
                    val killer = dead.attr[KILLER_ATTR]?.get() as? Player
                    d.onWarlordKilled(world, killer)
                    killer?.let { WarDrops.onWarlordKill(world, it, dead) }
                }
            }
            // General Zo slain -> the city falls.
            onNpcDeath(d.config.zoNpc) {
                if (npc === d.zo) d.onZoKilled(world)
            }
        }

        val tickIntervalTicks = 3
        val siegeTimer = TimerKey()
        onWorldInit {
            if (!DEFENSE_ENABLED) {
                // The defensive goblin siege is RETIRED in favour of the offensive model (boss raids
                // + title-commanded campaigns). The engine (WarFront/Commander/AttackDirector/…) is
                // kept and the directors are still built so the admin test commands below and a
                // future re-enable work; we just never auto-run a raid. The open-world frontier
                // ([CityFrontierPlugin]) is unaffected — it's the path campaigns push through.
                logger.info { "Defensive siege retired (offense-only): AttackDirector idle, engine retained." }
                // Zo is still a permanent courtyard fixture (war-status + future feudal-command
                // dialogue via GeneralZoPlugin), so post him even though the raids that used to
                // own/spawn him are off. Without this he never appears.
                directors.forEach { it.postDecorativeZo(world) }
                return@onWorldInit
            }
            // Boot diagnostic: does the server JVM see the LLM key? (boolean only - never the key)
            logger.info { "War LLM: ANTHROPIC_API_KEY visible to server = ${WarLlm.available}; model = ${WarLlm.model}" }
            WarMemory.load()
            directors.forEach { keepGateDoorsOpen(it.config); it.init(world) }
            world.timers[siegeTimer] = tickIntervalTicks
        }
        onTimer(siegeTimer) {
            if (!DEFENSE_ENABLED) return@onTimer
            directors.forEach { keepGateDoorsOpen(it.config); it.tick(world) }
            world.timers[siegeTimer] = tickIntervalTicks
        }

        // ::warfront - teleport to the first defense's first field + status.
        onCommand("warfront", Privilege.ADMIN_POWER, description = "Teleport to a war front") {
            val d = directors.firstOrNull() ?: return@onCommand
            player.moveTo(d.config.fields.first().goblinSpawns.first())
            d.statusLines(world).forEach { player.message(it) }
        }

        // ::warmap - read the War Brain's live per-field allocation + posture.
        onCommand("warmap", Privilege.ADMIN_POWER, description = "Show the AI's per-field allocation") {
            directors.forEach { d -> d.statusLines(world).forEach { player.message(it) } }
        }

        // ::warreset - wipe all units and return every defense to its neutral baseline.
        onCommand("warreset", Privilege.ADMIN_POWER, description = "Reset all defenses: wipe units, baseline") {
            directors.forEach { it.reset(world) }
            WarState.save(force = true)
            player.message("Battle reset: all units cleared; defenses returned to baseline.")
        }

        // ::warraid [tier] - force an immediate raid (optionally of a named tier) for testing.
        onCommand("warraid", Privilege.ADMIN_POWER, description = "Force a raid: ::warraid [probe|raid|siege]") {
            val d = directors.firstOrNull() ?: return@onCommand
            val name = player.getCommandArgs().getOrNull(0)
            val tier = name?.let { d.tierByName(it) }
            if (name != null && tier == null) {
                player.message("Unknown tier '$name'. Try: ${d.config.raidTiers.joinToString { it.name }}")
                return@onCommand
            }
            d.reset(world)
            d.beginRaid(world, forced = tier)
            player.message("Forced a ${tier?.name ?: "random"} raid on ${d.config.displayName}.")
        }

        // ::warfall - force the city into the fallen state (test the penalty cycle).
        onCommand("warfall", Privilege.ADMIN_POWER, description = "Force a city-fallen state (test)") {
            val d = directors.firstOrNull() ?: return@onCommand
            d.forceCityFall(world)
            player.message("${d.config.displayName} forced into the fallen state.")
        }

        // ::warlog - toggle the per-tick coordinate datasheet (NDJSON replay) for the first front.
        onCommand("warlog", Privilege.ADMIN_POWER, description = "Toggle the war replay datasheet") {
            val d = directors.firstOrNull() ?: return@onCommand
            val on = WarRecorder.toggle(d.config.frontId)
            player.message("War replay recording ${if (on) "ON" else "OFF"} for ${d.config.displayName} (data/saves/world/war_replay_${d.config.frontId}.ndjson).")
        }

        // ::warllm - toggle the live LLM commander (needs ANTHROPIC_API_KEY in the environment).
        onCommand("warllm", Privilege.ADMIN_POWER, description = "Toggle the live LLM war commander") {
            if (!WarLlm.available) {
                player.message("<col=ff4f4f>LLM commander unavailable: set ANTHROPIC_API_KEY in the server environment.</col>")
                return@onCommand
            }
            WarLlm.enabled = !WarLlm.enabled
            player.message("LLM war commander ${if (WarLlm.enabled) "<col=4f9b4f>ON</col>" else "OFF"} (model ${WarLlm.model}). It steers postures/focus on a slow cadence; the heuristic covers the gaps.")
        }
    }

    private companion object {
        /**
         * Master switch for the old **defensive** goblin siege of Lumbridge. Retired (false) now
         * that the war is player-offense: bosses spawn in cities and title-holders command troops
         * into them. Flip to true to bring the defensive raids back (the engine is fully intact).
         */
        const val DEFENSE_ENABLED = false
    }

    private fun build(config: SiegeConfig, f: FieldDef): WarFront = WarFront(
        frontId = config.frontId,
        name = f.name,
        zone = f.zone,
        attackerNpcs = listOf(config.attackerNpc),
        attackerStaging = vet("${f.name} goblin-spawn", f.goblinSpawns),
        defenderNpcs = listOf(config.defenderNpc),
        defenderStaging = vet("${f.name} knight-muster", config.knightMuster),
        attackerObjectives = vet("${f.name} attacker-route", f.attackerWaypoints),
        defenderObjectives = vet("${f.name} defender-route", f.defenderWaypoints),
        attackerDef = config.attackerDef,
        defenderDef = config.defenderDef,
        attackerEliteNpc = config.attackerEliteNpc,
        attackerEliteDef = config.attackerEliteDef,
        keep = config.castleKeep,
    )

    /**
     * Snap any hand-authored siege tile that landed on blocked terrain (River Lum / swamp) to the
     * nearest walkable tile via [StaticTerrain] (cache-decoded - the runtime collision map isn't up
     * at init). Routes need every waypoint, so we snap rather than drop (cf. the frontier ring, which
     * filters). Already-walkable tiles pass through untouched; every change is logged.
     */
    private fun vet(label: String, tiles: List<Tile>): List<Tile> = tiles.map { t ->
        val w = StaticTerrain.nearestWalkable(t.x, t.z, t.height)
        when {
            w == null -> {
                logger.warn { "Siege [$label] tile (${t.x},${t.z},${t.height}) is on blocked terrain and no walkable tile is within range - left as-is." }
                t
            }
            w.first == t.x && w.second == t.z -> t
            else -> {
                logger.info { "Siege [$label] tile (${t.x},${t.z}) snapped off blocked terrain to nearest walkable (${w.first},${w.second})." }
                Tile(w.first, w.second, t.height)
            }
        }
    }

    /** Hold a defense's gate doors open (re-open any currently shut). */
    private fun keepGateDoorsOpen(config: SiegeConfig) {
        if (config.knightGateDoors.isEmpty()) return
        val service = world.getService(DoorService::class.java, searchSubclasses = true) ?: return
        val closedToOpened = service.doors.associate { it.closed to it.opened }
        for (tile in config.knightGateDoors) {
            for (type in intArrayOf(0, WALL_DIAGONAL)) {
                val obj = world.getObject(tile, type) ?: continue
                val openId = closedToOpened[obj.id] ?: continue
                try {
                    world.openDoor(obj, opened = openId, invertTransform = obj.type == WALL_DIAGONAL)
                } catch (e: Exception) {
                    // never let a door quirk break the tick
                }
            }
        }
    }
}
