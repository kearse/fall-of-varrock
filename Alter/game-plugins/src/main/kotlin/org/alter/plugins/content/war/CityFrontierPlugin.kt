package org.alter.plugins.content.war

import dev.openrune.cache.CacheManager.getNpc
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.api.ext.npc
import org.alter.game.Server
import org.alter.game.model.Area
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * Drives every city's **hostile frontier** ([CityFrontiers.all]) from one world timer.
 *
 * A frontier is now LAYERED: several concentric [EnemyLine]s of different npc types at
 * increasing distance, plus an optional friendly [DefenderLine] of knights that muster
 * by the city and march out to a chosen enemy line, fighting along the way. Each frontier
 * becomes one [HostileZone]: enemy packs hold their rings and aggro players; the knight
 * pack is [Faction.FRIENDLY] and is steered by the zone's NPC-vs-NPC skirmish.
 *
 * Add a city by adding a [FrontierConfig] - no new code. Each enemy line uses its OWN
 * npc id so its global combat def + death/loot handler don't collide.
 */
class CityFrontierPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    /** Enemy npc ids already bound — one combat def + death handler per id (a dup throws / is lost). */
    private val seen = HashSet<String>()
    private val zones = ArrayList<HostileZone>()

    init {
        // The city frontiers plus every scheduled-march target's garrison ([MarchTargets]) — a march
        // target is just a small campaign-gated frontier keyed by the target key. Targets that content
        // registers AFTER this plugin constructed (plugin load order is unspecified) are built by the
        // registration hook, so `MarchTargets.register` is order-free.
        (CityFrontiers.all + MarchTargets.frontiers).forEach { buildFrontier(it) }
        MarchTargets.whenRegistered { buildFrontier(it.frontier) }

        // Far-flung frontiers (e.g. Varrock) have NO runtime collision until a player streams the
        // region in — so their NPCs would spawn unable to path or fight. Force-load every frontier's
        // battle regions at world-init (idempotent; near-spawn Lumbridge is effectively a no-op).
        // Regions only get collision once a player is near; force-load so far-flung defenders path.
        onWorldInit { forceLoadFrontierRegions(world) }

        val timer = TimerKey()
        onWorldInit { world.timers[timer] = TICK }
        onTimer(timer) {
            zones.forEach { it.tick(world) }
            world.timers[timer] = TICK
        }

        // Single-combat (1v1) enforcement on its OWN every-game-tick timer — tighter than the
        // zone tick, so a second goblin that aggros a new player is released before it can hit.
        val sweepTimer = TimerKey()
        onWorldInit { world.timers[sweepTimer] = 1 }
        onTimer(sweepTimer) {
            zones.forEach { it.enforceSingleCombatTick(world) }
            world.timers[sweepTimer] = 1
        }
    }

    /**
     * Build one frontier's garrison: a combat def + loot handler per enemy line, muster points, the
     * [HostileZone] and its [Frontiers] registration. Isolated — a bad config never sinks the rest.
     *
     * Ring staging is filtered to walkable land via [StaticTerrain] (the runtime collision map isn't
     * populated this early); object/wall jitter is handled at spawn by HostileZone. Combat defs are
     * registered once per enemy npc id ([seen] — setCombatDef throws on a dup and onNpcDeath silently
     * keeps only one handler). The knight id is NOT registered globally (it's shared with the war
     * system); its stats ride on the pack's own combatDef.
     */
    private fun buildFrontier(cfg: FrontierConfig) {
          try {
            val packs = ArrayList<MonsterPack>()
            var totalEnemies = 0

            for (line in cfg.enemyLines) {
                if (!seen.add(line.npcName)) {
                    logger.error { "Frontier '${cfg.cityKey}' line ${line.level} reuses npc ${line.npcName}; skipped (one combat def + death handler per npc id)." }
                    continue
                }
                setCombatDef(line.npcName, line.combatDef)
                registerLoot(line)

                // Players can only attack an npc whose CACHE def has an "Attack" option (+ a combat
                // level) - setCombatDef doesn't add it. Flag a misconfigured line loudly at boot
                // (e.g. a Talk-to-only "guard"/"trader" npc) so it isn't shipped unkillable.
                val def = getNpc(getRSCM(line.npcName))
                if (def.combatLevel <= 0 || def.actions.none { it == "Attack" }) {
                    logger.error { "Frontier '${cfg.cityKey}' line ${line.level} npc ${line.npcName} is NOT player-attackable (combatLevel=${def.combatLevel}, actions=${def.actions.filterNotNull()}) - pick a combat npc with an Attack option." }
                }

                // Explicit hand-walked street tiles (Varrock) OR the concentric ring (Lumbridge).
                val raw = line.explicitStaging
                    ?: CityFrontiers.ringStaging(cfg.cityLimits, line.gap, line.depth, line.spacing)
                val walkable = raw
                    .filter { StaticTerrain.isWalkable(it.x, it.z) && !CityFrontiers.isExcluded(cfg.exclude, it.x, it.z) }
                val staging = CityFrontiers.capEvenly(walkable, line.maxEnemies)
                totalEnemies += staging.size
                logger.info { "Frontier '${cfg.displayName}' line ${line.level} (${line.enemyNoun}): ${staging.size} muster points (${line.npcName})." }

                packs += MonsterPack(
                    npcName = line.npcName,
                    count = staging.size, // one enemy per muster point => even fill
                    walkRadius = line.walkRadius,
                    staging = staging,
                    faction = Faction.ENEMY,
                    singleCombat = line.singleCombat,
                    combatLevelOverride = line.combatLevelOverride,
                    aggroFloorRank = line.aggroFloorRank,
                )
            }

            // Knight groups muster + roam the same way the enemy lines do (no scripted march - the
            // skirmish only steers them onto enemies in reach; otherwise they wander their walkRadius).
            // A RING group spreads countRatio Ã— the total enemy count around the city; an AREA group
            // drops a fixed squad into one rectangle the ring sampling misses (e.g. the east strip).
            for ((i, d) in cfg.defenders.withIndex()) {
                val isSquad = d.area != null
                val muster = if (isSquad) {
                    CityFrontiers.gridArea(d.area!!, d.spacing)
                } else {
                    CityFrontiers.ringStaging(cfg.cityLimits, d.gap, d.depth, d.spacing)
                }.filter { StaticTerrain.isWalkable(it.x, it.z) && !CityFrontiers.isExcluded(cfg.exclude, it.x, it.z) }

                if (muster.isEmpty()) {
                    logger.error { "Frontier '${cfg.cityKey}' defender group $i has no walkable land; skipped." }
                    continue
                }
                val want = if (isSquad) d.count else (totalEnemies * d.countRatio).toInt().coerceAtLeast(0)
                val staging = CityFrontiers.capEvenly(muster, want)
                val where = if (isSquad) "squad at ${d.area}" else "ring (~${d.countRatio} Ã— $totalEnemies enemies)"
                logger.info { "Frontier '${cfg.displayName}' defenders[$i]: ${staging.size} knights - $where." }
                packs += MonsterPack(
                    npcName = d.npcName,
                    count = staging.size,
                    walkRadius = d.walkRadius,
                    staging = staging,
                    faction = Faction.FRIENDLY,
                    combatDef = d.combatDef,
                    displayName = d.displayName,
                )
            }

            if (packs.isNotEmpty()) {
                // cityLimits is the protected town: enemies won't aggro into it and are leashed
                // back out if they cross the edge, so the town stays safe.
                // An enemy city (protectCity=false) has NO leash — its defenders hold their own streets.
                val zone = HostileZone(
                    name = "${cfg.displayName} Frontier",
                    packs = packs,
                    safeArea = if (cfg.protectCity) cfg.cityLimits else null,
                    keep = cfg.keep,
                    activeWhen = activationGate(cfg),
                )
                Frontiers.register(cfg.cityKey, zone) // so the campaign engine can suppress/count it
                zones += zone
            }
          } catch (e: Throwable) {
            logger.error(e) { "Frontier '${cfg.cityKey}' failed to build; skipped (other frontiers unaffected)." }
          }
    }

    /**
     * The activation gate for a frontier (see [HostileZone.activeWhen]) — what keeps an empty city's
     * garrison from costing npc-cycle time:
     *  - **Home frontier** ([FrontierConfig.protectCity] = true, Lumbridge): live only while a player
     *    is inside the battlefield box (cityLimits grown by the deepest ring + a streaming margin), so
     *    the ~480-strong frontier despawns when nobody's around and re-musters as a player approaches.
     *  - **Enemy ground** (protectCity = false: Varrock, every march target): live only while an op
     *    fights over it ([CampaignRegistry.isAttacking]) — so the garrison exists ONLY during the war
     *    and the ground is otherwise just its ambient spawns + players.
     */
    private fun activationGate(cfg: FrontierConfig): (World) -> Boolean {
        if (!cfg.protectCity) {
            val key = cfg.cityKey
            return { _ -> CampaignRegistry.isAttacking(key) }
        }
        val reach = maxOf(
            cfg.enemyLines.maxOfOrNull { it.gap + it.depth } ?: 0,
            cfg.defenders.maxOfOrNull { it.gap + it.depth } ?: 0,
        ) + ACTIVATION_MARGIN
        val b = cfg.cityLimits
        val box = Area(b.bottomLeftX - reach, b.bottomLeftY - reach, b.topRightX + reach, b.topRightY + reach)
        return { w -> w.players.any { it.index >= 0 && box.contains(it.tile) } }
    }

    /** Build collision for every region a frontier's rings overlap, so its NPCs are pathable with no
     *  player nearby. Idempotent — [org.alter.game.fs.DefinitionSet.loadRegions] skips active regions. */
    private fun forceLoadFrontierRegions(world: World) {
        val regions = sortedSetOf<Int>()
        val all = CityFrontiers.all + MarchTargets.frontiers
        for (cfg in all) {
            val reach = (cfg.enemyLines.maxOfOrNull { it.gap + it.depth } ?: 0) + 8
            val box = cfg.cityLimits
            val xMin = box.bottomLeftX - reach; val xMax = box.topRightX + reach
            val zMin = box.bottomLeftY - reach; val zMax = box.topRightY + reach
            for (rx in (xMin shr 6)..(xMax shr 6)) for (rz in (zMin shr 6)..(zMax shr 6)) regions += (rx shl 8) or rz
        }
        runCatching { world.definitions.loadRegions(world, world.chunks, regions.toIntArray()) }
            .onFailure { logger.error(it) { "[FRONTIER] region force-load failed" } }
        logger.info { "[FRONTIER] force-loaded ${regions.size} region(s) for ${all.size} frontier(s) (incl. ${MarchTargets.frontiers.size} march target(s))." }
    }

    /** Coins on every player kill, plus a low-rate tiered weapon/armour drop. While a CAMPAIGN is
     *  being fought over this tile, the same spoils are diverted into that campaign's war-chest pool
     *  ([CampaignRegistry.creditCityKill]) instead of the floor — to be split among the participants
     *  by contribution at the end of the raid (master design brief §3C). */
    private fun registerLoot(line: EnemyLine) {
        onNpcDeath(line.npcName) {
            val mob = npc
            val killer = mob.attr[KILLER_ATTR]?.get() as? Player ?: return@onNpcDeath

            val coins = (line.coinMin..line.coinMax).random()
            val gear = if ((1..line.gearDropOneIn).random() == 1) rollGear() else null // (itemKey, poolValue)

            // Pool the spoils if a campaign owns this battlefield; otherwise drop them on the floor.
            // A campaign uses the rich [campaignKillValue] (the war plunder) when set, so the pool is
            // lucrative without inflating casual floor drops; falls back to the coin+gear value.
            val poolValue = if (line.campaignKillValue > 0) line.campaignKillValue else coins + (gear?.second ?: 0)
            val inWar = CampaignRegistry.creditCityKill(mob.tile, poolValue)

            // War relic: ONLY while a campaign/conquest is being fought over this ground can a fallen
            // enemy yield a 3rd age piece — the prestige line's monster source (see [ThirdAge]). Rolled
            // for the killer and handed straight to them (never pooled), so it can't be split away.
            if (inWar && ThirdAge.rollWarDrop()) ThirdAge.awardDrop(world, killer, mob.tile)

            if (inWar) return@onNpcDeath

            world.spawn(GroundItem(getRSCM("item.coins_995"), coins, mob.tile))
            killer.message("<col=ffcf48>The ${line.enemyNoun} drops ${"%,d".format(coins)} coins.</col>")
            if (gear != null) world.spawn(GroundItem(getRSCM(gear.first), 1, mob.tile))
        }
    }

    /** Roll the tiered gear table; returns the item key and its representative war-chest value. */
    private fun rollGear(): Pair<String, Int> = when ((1..100).random()) {
        in 1..65 -> COMMON_GEAR.random() to COMMON_VALUE
        in 66..92 -> UNCOMMON_GEAR.random() to UNCOMMON_VALUE
        else -> RARE_GEAR.random() to RARE_VALUE
    }

    /** Cross every [metals] tier with every [pieces] type into `item.<metal>_<piece>`. */
    private fun gear(metals: List<String>, pieces: List<String>): List<String> =
        metals.flatMap { m -> pieces.map { p -> "item.${m}_$p" } }

    // Drop tables (all confirmed to exist in item.rscm). Common metals carry the full
    // piece set; adamant/rune are rarer and only the staples.
    private val COMMON_GEAR = gear(listOf("bronze", "iron", "steel"), FULL_PIECES)
    private val UNCOMMON_GEAR = gear(listOf("black", "mithril"), FULL_PIECES)
    private val RARE_GEAR = gear(listOf("adamant", "rune"), RARE_PIECES)

    private companion object {
        const val TICK = 2 // ~1.2s population upkeep — fast respawn so the front line refills quickly

        // Tiles beyond the deepest ring a player must enter before a home frontier musters. A region
        // is ~64 tiles; ~1.5 chunks of lead means the garrison is up before the player is in the thick
        // of it (maintain fills every slot in the activation tick, so there's no slow trickle).
        const val ACTIVATION_MARGIN = 24

        // Representative gp-value a dropped gear piece adds to a campaign's war-chest pool, by table
        // rarity (TUNABLE). Rare = the rune-tier loot that makes marching on a city worthwhile.
        const val COMMON_VALUE = 500
        const val UNCOMMON_VALUE = 2_000
        const val RARE_VALUE = 8_000

        val FULL_PIECES = listOf(
            "dagger", "sword", "longsword", "scimitar", "warhammer", "battleaxe",
            "platelegs", "chainbody", "platebody", "full_helm", "sq_shield", "kiteshield",
        )
        val RARE_PIECES = listOf("scimitar", "longsword", "battleaxe", "platebody", "full_helm", "kiteshield")
    }
}
