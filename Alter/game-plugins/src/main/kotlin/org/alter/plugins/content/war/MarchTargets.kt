package org.alter.plugins.content.war

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.combat.NpcCombatDef

private val logger = KotlinLogging.logger {}

/** What kind of hostile ground a march strikes (flavour for the muster call / `::marches`). */
enum class MarchTargetKind(val display: String) {
    CAMP("hostile camp"),
    ROGUE_POSITION("rogue position"),
    UNDEAD_CAMP("undead camp"),
    FRONTIER("frontier"),
    VARROCK_OUTSKIRTS("Varrock outskirts"),
}

/** The Grand March boss at a grand-eligible target's rally point. Renamed + boosted at spawn. */
data class WardenDef(
    val title: String,
    val npc: String = "npc.black_knight",
    val attack: Int = 200,
    val strength: Int = 180,
    val defence: Int = 160,
    val hp: Int = 1500,
)

/**
 * One place the realm's scheduled marches can strike.
 *
 * The campaign engine only counts kills against the [HostileZone] registered under
 * `op.cityKey` ([CampaignDirector] musters it, suppresses its respawn and wins on
 * `startEnemies - living >= quota`), so every target OWNS a campaign-gated [FrontierConfig]
 * keyed by [key]: its garrison exists only while a march is fighting over it
 * ([CityFrontierPlugin.activationGate] → [CampaignRegistry.isAttacking]). It must stage at
 * least [CampaignTier.GRAND_MARCH]'s quota of enemies or a march there can never be won.
 * PK-bot camps are Players, not Npcs — they can be flavour around a target, never the count.
 */
data class MarchTarget(
    val key: String,
    val display: String,
    val kind: MarchTargetKind,
    /** Route + objective + battlefield. `op.cityKey` MUST equal [key]. */
    val op: CampaignOp,
    /** The garrison mustered while a march is live. `frontier.cityKey` MUST equal [key]. */
    val frontier: FrontierConfig,
    /** Relative pick weight in the scheduled rotation. */
    val weight: Int = 1,
    /** May host a GRAND MARCH (needs a [warden]). */
    val grandEligible: Boolean = false,
    val warden: WardenDef? = null,
) {
    init {
        require(op.cityKey == key) { "MarchTarget '$key': op.cityKey '${op.cityKey}' must equal the target key" }
        require(frontier.cityKey == key) { "MarchTarget '$key': frontier.cityKey '${frontier.cityKey}' must equal the target key" }
        require(!frontier.protectCity) { "MarchTarget '$key': the garrison must be campaign-gated (protectCity=false)" }
        require(!grandEligible || warden != null) { "MarchTarget '$key': grand-eligible targets need a WardenDef" }
    }
}

/**
 * The pool of hostile ground the realm's public **Marches** / **Grand Marches** strike
 * ([MarchPlugin]) and a Lord's `::operation` may sponsor: hostile camps, roadside rogue
 * positions, undead camps, frontier threats and the outskirts of Fallen Varrock — never the
 * deep city, which is reserved for Campaigns, Conquests and story events (design authority §6).
 *
 * Every entry is pure data (tiles, areas, `NpcCombatDef.copy`) so this object is safe to touch at
 * plugin-construction time. Keep the dependency ONE-WAY: this file may read [Campaigns] /
 * [CityFrontiers] helpers, but nothing those objects initialise may read back into here
 * ([Campaigns.ROUTED] is a getter for exactly that reason).
 *
 * Routes reuse the hand-walked corridors by truncation (Lumbridge→Draynor road, Lumbridge→Varrock
 * bridge road) so no new `::recroute` pass is needed. Adding a target = one entry in [pool].
 * Deferred until a route is walked: Port Sarim docks (rogue raiders), the Wild Bandit Camp.
 *
 * Every [EnemyLine] npc id gets a global death handler AND prunes that id's ambient world
 * spawns (`WorldSpawnsPlugin.finalizeSpawnData`), so line ids are variants with (near) zero
 * ambient rows in `npc_spawns.json`. Boot logs a loud ERROR for any id that isn't attackable.
 */
object MarchTargets {

    // --- combat defs (TUNE) ---

    /** Camp goblins — a beginner target, tougher than the level-2 newbie ring but still soft. */
    private val CAMP_GOBLIN_DEF = NpcCombatDef.DEFAULT.copy(
        attack = 8, strength = 8, defence = 6, hitpoints = 18,
        attackSpeed = 5, attackAnimation = 6184, blockAnimation = 6183, deathAnimation = listOf(6182),
        aggressiveRadius = 6, aggroTargetDelay = 4, aggressiveTimer = 400,
    )

    /** Roadside bandits / highwaymen — the rogue rank and file. Human model, generic melee anims. */
    private val ROAD_BANDIT_DEF = NpcCombatDef.DEFAULT.copy(
        attack = 25, strength = 25, defence = 20, hitpoints = 35,
        attackSpeed = 5, attackAnimation = 422, blockAnimation = 424, deathAnimation = listOf(836),
        aggressiveRadius = 6, aggroTargetDelay = 4, aggressiveTimer = 400,
    )

    /** Marauders — the street rabble on the Varrock approach. */
    private val MARAUDER_DEF = NpcCombatDef.DEFAULT.copy(
        attack = 30, strength = 28, defence = 25, hitpoints = 40,
        attackSpeed = 5, attackAnimation = 422, blockAnimation = 424, deathAnimation = listOf(836),
        aggressiveRadius = 6, aggroTargetDelay = 4, aggressiveTimer = 400,
    )

    /** Black Knights — Kinshra opportunists holding the road. Sword slash + shield block anims
     *  (the unarmed 422/424 read as punching with a sword). */
    private val BLACK_KNIGHT_DEF = NpcCombatDef.DEFAULT.copy(
        attack = 70, strength = 65, defence = 70, hitpoints = 90,
        attackSpeed = 5, attackAnimation = 390, blockAnimation = 1156, deathAnimation = listOf(836),
        aggressiveRadius = 6, aggroTargetDelay = 4, aggressiveTimer = 400,
    )

    /** Undead — Zemouregal's dead, spilled out of the city onto its southern road. */
    private val UNDEAD_DEF = NpcCombatDef.DEFAULT.copy(
        attack = 45, strength = 45, defence = 35, hitpoints = 60,
        attackSpeed = 5,
        aggressiveRadius = 6, aggroTargetDelay = 4, aggressiveTimer = 400,
    )

    private const val KNIGHT = "npc.knight_of_saradomin" // renamed "Knight of Lumbridge" at spawn

    /** The Lumbridge→Draynor road: out of the castle courtyard NORTH around the keep, then west.
     *  (The old Falador march route's first 15 hops — hand-tuned so the column never cuts through
     *  the castle building.) */
    private val LUMBRIDGE_WEST_ROAD: List<Tile> = listOf(
        Tile(3222, 3220, 0),                     // muster: Lumbridge castle courtyard
        Tile(3222, 3228, 0),                     // north out of the courtyard, clear of the keep
        Tile(3216, 3233, 0),                     // onto the path north of the keep (by the general store)
        Tile(3208, 3233, 0),                     // west, north of the castle building — no wall cut
        Tile(3200, 3231, 0),                     // drop onto the Draynor road, clear of Lumbridge
        Tile(3190, 3231, 0), Tile(3180, 3233, 0), Tile(3170, 3236, 0),
        Tile(3160, 3238, 0), Tile(3150, 3241, 0), Tile(3140, 3244, 0), Tile(3130, 3246, 0),
        Tile(3120, 3248, 0), Tile(3110, 3249, 0), Tile(3101, 3251, 0), // the Draynor road, west
    )

    // ---------------------------------------------------------------------------------------------
    // The goblin camp east of the castle (the newbie war field's own camp). Crosses the River Lum
    // at the z3226 bridge deck (x3239-3248) — the ONLY crossing on this corridor.
    val GOBLIN_CAMP: MarchTarget = run {
        val key = "goblin_camp"
        val rally = Tile(3254, 3234, 0)
        MarchTarget(
            key = key, display = "the Lumbridge goblin camp", kind = MarchTargetKind.CAMP, weight = 2,
            op = CampaignOp(
                cityKey = key, cityId = Cities.DEFAULT_CITY_ID, displayName = "the goblin camp",
                stagingTile = Tile(3222, 3220, 0), objectiveTile = rally,
                battleArea = Area(3236, 3218, 3270, 3252),
                alliedNpc = KNIGHT, alliedDef = Campaigns.ALLIED_DEF,
                timeoutTicks = 400,
                route = listOf(
                    Tile(3222, 3220, 0), Tile(3231, 3219, 0), Tile(3236, 3226, 0), // courtyard → bridge west mouth
                    Tile(3239, 3226, 0), Tile(3248, 3226, 0),                      // the z3226 bridge deck
                    Tile(3254, 3230, 0), rally,                                     // up to the camp
                ),
                aggroFromArea = Area(3244, 3224, 3266, 3246),
            ),
            frontier = FrontierConfig(
                cityKey = key, displayName = "the goblin camp",
                cityLimits = Area(3250, 3230, 3258, 3238),
                enemyLines = listOf(
                    EnemyLine(
                        level = 1, npcName = "npc.goblin_2247", combatDef = CAMP_GOBLIN_DEF,
                        gap = 2, depth = 8, spacing = 3, maxEnemies = 28, walkRadius = 4,
                        enemyNoun = "goblin", coinMin = 15, coinMax = 45, gearDropOneIn = 14,
                        campaignKillValue = 4_000, singleCombat = true, combatLevelOverride = 5,
                    ),
                ),
                keep = CityFrontiers.LUMBRIDGE_KEEP,
                protectCity = false,
            ),
        )
    }

    // The Bandit Hideout by the jail on the Draynor road — the Rogue Knight ladder's first camp.
    // The knights thin the bandit warband; the ladder's PK bots stay flavour (bots aren't counted).
    val BANDIT_HIDEOUT: MarchTarget = run {
        val key = "bandit_hideout"
        val rally = Tile(3110, 3230, 0) // = RogueKnights.BANDIT_HIDEOUT.center
        MarchTarget(
            key = key, display = "the Bandit Hideout", kind = MarchTargetKind.ROGUE_POSITION, weight = 3,
            grandEligible = true, warden = WardenDef(title = "The Warden of the Hideout"),
            op = CampaignOp(
                cityKey = key, cityId = Cities.DEFAULT_CITY_ID, displayName = "the Bandit Hideout",
                stagingTile = Tile(3222, 3220, 0), objectiveTile = rally,
                battleArea = Area(3090, 3210, 3130, 3255),
                alliedNpc = KNIGHT, alliedDef = Campaigns.ALLIED_DEF,
                timeoutTicks = 500,
                route = LUMBRIDGE_WEST_ROAD.take(14) + listOf(Tile(3110, 3241, 0), Tile(3110, 3233, 0), rally),
                aggroFromArea = Area(3095, 3215, 3125, 3248),
            ),
            frontier = FrontierConfig(
                cityKey = key, displayName = "the Bandit Hideout",
                cityLimits = Area(3106, 3226, 3114, 3234),
                enemyLines = listOf(
                    EnemyLine(
                        level = 1, npcName = "npc.bandit_736", combatDef = ROAD_BANDIT_DEF,
                        gap = 2, depth = 9, spacing = 3, maxEnemies = 30, walkRadius = 4,
                        enemyNoun = "bandit", coinMin = 40, coinMax = 90, gearDropOneIn = 12,
                        campaignKillValue = 30_000, singleCombat = true, combatLevelOverride = 28,
                    ),
                ),
                protectCity = false,
            ),
        )
    }

    // The coast road on Draynor's southern edge — the highwaymen who bleed the supply road.
    val DRAYNOR_ROAD: MarchTarget = run {
        val key = "draynor_road"
        val rally = Tile(3081, 3225, 0) // = RogueKnights.DRAYNOR.center
        MarchTarget(
            key = key, display = "the Draynor road", kind = MarchTargetKind.CAMP, weight = 2,
            op = CampaignOp(
                cityKey = key, cityId = Cities.DEFAULT_CITY_ID, displayName = "the Draynor road",
                stagingTile = Tile(3222, 3220, 0), objectiveTile = rally,
                battleArea = Area(3062, 3205, 3105, 3255),
                alliedNpc = KNIGHT, alliedDef = Campaigns.ALLIED_DEF,
                timeoutTicks = 500,
                route = LUMBRIDGE_WEST_ROAD + listOf(Tile(3093, 3243, 0), Tile(3086, 3235, 0), Tile(3082, 3228, 0), rally),
                aggroFromArea = Area(3068, 3210, 3100, 3245),
            ),
            frontier = FrontierConfig(
                cityKey = key, displayName = "the Draynor road",
                cityLimits = Area(3077, 3221, 3085, 3229),
                enemyLines = listOf(
                    EnemyLine(
                        level = 1, npcName = "npc.highwayman_519", combatDef = ROAD_BANDIT_DEF,
                        gap = 2, depth = 9, spacing = 3, maxEnemies = 30, walkRadius = 4,
                        enemyNoun = "highwayman", coinMin = 40, coinMax = 90, gearDropOneIn = 12,
                        campaignKillValue = 30_000, singleCombat = true, combatLevelOverride = 24,
                    ),
                ),
                protectCity = false,
            ),
        )
    }

    // The southern road into Fallen Varrock — the deepest a public march goes. The Varrock route
    // truncated at the stone circle; the garrison holds the road between the circle and the city
    // gate (south of the city's own street staging, which starts at z3384). Wilderness ground.
    val VARROCK_OUTSKIRTS: MarchTarget = run {
        val key = "varrock_outskirts"
        val rally = Tile(3213, 3376, 0)
        val staging = CityFrontiers.gridArea(Area(3206, 3346, 3230, 3378), 4)
        MarchTarget(
            key = key, display = "the Varrock outskirts", kind = MarchTargetKind.VARROCK_OUTSKIRTS, weight = 3,
            grandEligible = true, warden = WardenDef(title = "The Warden of the Southern Road"),
            op = CampaignOp(
                cityKey = key, cityId = Cities.VARROCK.id, displayName = "the Varrock outskirts",
                stagingTile = Tile(3231, 3219, 0), objectiveTile = rally,
                battleArea = Area(3180, 3335, 3260, 3399),
                alliedNpc = KNIGHT, alliedDef = Campaigns.ALLIED_DEF,
                timeoutTicks = 600,
                route = Campaigns.VARROCK.route.take(25) + listOf(rally), // ends at the stone circle, then the gate road
                aggroFromArea = Area(3190, 3340, 3245, 3399),
            ),
            frontier = FrontierConfig(
                cityKey = key, displayName = "the Varrock outskirts",
                cityLimits = Area(3206, 3346, 3230, 3378),
                enemyLines = listOf(
                    // Marauders on the far approach, Black Knights on the road, the dead nearest the gate.
                    EnemyLine(
                        level = 3, npcName = "npc.bandit_735", combatDef = MARAUDER_DEF,
                        gap = 0, depth = 0, spacing = 1, maxEnemies = 12, walkRadius = 4,
                        explicitStaging = staging.filter { it.z <= 3356 },
                        enemyNoun = "marauder", coinMin = 60, coinMax = 140, gearDropOneIn = 12,
                        campaignKillValue = 40_000, singleCombat = true, combatLevelOverride = 34,
                    ),
                    EnemyLine(
                        level = 2, npcName = "npc.black_knight_4959", combatDef = BLACK_KNIGHT_DEF,
                        gap = 0, depth = 0, spacing = 1, maxEnemies = 12, walkRadius = 4,
                        explicitStaging = staging.filter { it.z in 3357..3366 },
                        enemyNoun = "Black Knight", coinMin = 150, coinMax = 320, gearDropOneIn = 8,
                        campaignKillValue = 90_000, singleCombat = true, combatLevelOverride = 68,
                    ),
                    EnemyLine(
                        level = 1, npcName = "npc.skeleton_heavy", combatDef = UNDEAD_DEF,
                        gap = 0, depth = 0, spacing = 1, maxEnemies = 12, walkRadius = 4,
                        explicitStaging = staging.filter { it.z >= 3367 },
                        enemyNoun = "risen dead", coinMin = 100, coinMax = 220, gearDropOneIn = 10,
                        campaignKillValue = 60_000, singleCombat = true, combatLevelOverride = 55,
                    ),
                ),
                protectCity = false,
            ),
        )
    }

    /** The built-in rotation. Order is cosmetic; [weight] drives the pick. */
    private val builtIn: List<MarchTarget> = listOf(GOBLIN_CAMP, BANDIT_HIDEOUT, DRAYNOR_ROAD, VARROCK_OUTSKIRTS)
    private val registered = ArrayList<MarchTarget>()
    private val registerHooks = ArrayList<(MarchTarget) -> Unit>()

    /** Every target in the rotation: the built-ins plus everything content has [register]ed. */
    val pool: List<MarchTarget> get() = builtIn + registered

    /** Every target's garrison config, for [CityFrontierPlugin] to build alongside [CityFrontiers.all]. */
    val frontiers: List<FrontierConfig> get() = pool.map { it.frontier }

    fun byKey(key: String): MarchTarget? = pool.firstOrNull { it.key.equals(key, ignoreCase = true) }

    /**
     * **Add a target to the rotation from any plugin's `init`** — rogue camps, undead positions, a
     * frontier another team owns — without editing this file. Order-free: [CityFrontierPlugin]
     * builds the garrison of a target registered before OR after it constructs (via
     * [whenRegistered]), and the corridor / region force-loads read [pool] at world init. Keys must
     * be unique across march targets and hostile cities (a duplicate fails the registering plugin
     * at boot, loudly). Register at boot: a target added after world init still gets a garrison
     * but not the region force-load, and is logged as such.
     */
    fun register(target: MarchTarget): MarchTarget {
        require(byKey(target.key) == null) { "MarchTarget '${target.key}' is already registered" }
        require(Campaigns.hostileByKey(target.key) == null) { "MarchTarget '${target.key}' collides with a hostile city key" }
        registered += target
        logger.info { "[MARCH] registered march target '${target.key}' (${target.display}, ${target.kind.display}${if (target.grandEligible) ", grand-eligible" else ""})." }
        registerHooks.toList().forEach { hook ->
            runCatching { hook(target) }.onFailure { logger.error(it) { "[MARCH] register hook failed for '${target.key}'" } }
        }
        return target
    }

    /** Subscribe to future [register] calls (the frontier builder). Not replayed for targets already
     *  in [pool] — read that first. */
    fun whenRegistered(hook: (MarchTarget) -> Unit) { registerHooks += hook }

    /**
     * The next march's target: a weighted random pick over the targets that can actually be fought
     * right now — registered garrison ([Frontiers]), nothing already fighting over that key, and no
     * active op whose battlefield overlaps (kills would cross-credit). [grand] restricts the pick to
     * grand-eligible targets. Null = nothing eligible this cycle (the caller skips the cycle).
     */
    fun pick(world: World, grand: Boolean): MarchTarget? {
        val eligible = pool.filter { t ->
            (!grand || t.grandEligible) &&
                Frontiers.zone(t.key) != null &&
                !CampaignRegistry.isAttacking(t.key) &&
                !CampaignRegistry.overlapsActive(t.op.battleArea)
        }
        if (eligible.isEmpty()) {
            logger.info { "[MARCH] no eligible target (grand=$grand) — ${pool.count { Frontiers.zone(it.key) == null }} target(s) have no garrison zone." }
            return null
        }
        val total = eligible.sumOf { it.weight.coerceAtLeast(1) }
        var r = world.random(total - 1)
        for (t in eligible) {
            r -= t.weight.coerceAtLeast(1)
            if (r < 0) return t
        }
        return eligible.last()
    }

    /** The `::marches` board. */
    fun statusLines(nextMusterMins: Int, mustering: MarchTarget?, live: MarchTarget?, grandNext: Boolean): List<String> {
        val lines = ArrayList<String>()
        lines += "<col=801700>The realm's marches — hostile ground the Knight-Captain strikes:</col>"
        for (t in pool) {
            val state = when {
                live?.key == t.key -> "<col=ff4f4f>UNDER ATTACK NOW</col>"
                mustering?.key == t.key -> "<col=ffae00>mustering</col>"
                Frontiers.zone(t.key) == null -> "<col=801700>no garrison (misconfigured)</col>"
                CampaignRegistry.overlapsActive(t.op.battleArea) -> "contested by another operation"
                else -> "quiet"
            }
            val grand = if (t.grandEligible) " · Grand March target" else ""
            val wild = if (t.kind == MarchTargetKind.VARROCK_OUTSKIRTS) " · <col=ff4f4f>wilderness</col>" else ""
            lines += "  ${t.display} (${t.kind.display}$grand$wild): $state"
        }
        lines += when {
            live != null -> "A ${if (grandNext) "GRAND MARCH" else "march"} is in the field — <col=0000ff>::march</col> to rally to the column."
            mustering != null -> "The ${if (grandNext) "GRAND MARCH" else "march"} on ${mustering.display} sets out in ~$nextMusterMins minute(s)."
            else -> "The next march musters in ~$nextMusterMins minute(s)${if (grandNext) " — a GRAND MARCH" else ""}. Lords may sponsor one now: <col=0000ff>::operation <target></col>."
        }
        return lines
    }
}
