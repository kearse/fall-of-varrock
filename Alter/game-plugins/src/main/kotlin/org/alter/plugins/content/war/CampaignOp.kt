package org.alter.plugins.content.war

import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.combat.NpcCombatDef

/**
 * The three scales of offensive operation a title-holder can command. Each maps to a
 * [CommandTier] gate and sets the allied force size, the win condition, the coin cost, and
 * the reward.
 *
 * - [RAID]   (Lord)     — a small party that backs a boss fight; no kill quota (it ends with
 *                          the boss). Dispatched *with* a boss summon, so it costs nothing
 *                          extra (the summon was already paid for).
 * - [CAMPAIGN](Minister) — a column that pushes the frontier; wins by clearing [quota] enemies.
 * - [CONQUEST](King)    — a full army that seizes the city; higher quota, bigger spoils pool.
 *
 * [coinPool] is the aggregate reward split among participants on a won campaign/conquest
 * ([CapturePayout]); RAID rewards come from the boss itself.
 */
enum class CampaignTier(
    val display: String,
    val troops: Int,
    val quota: Int,
    val cost: Int,
    val coinPool: Int,
    val prestige: Int,
    /** Realm war-supply the operation requires AND consumes on launch (the Mire fills it). RAID is free. */
    val supplyCost: Int,
    /** Max Commendations (the war-forging service token) a participant earns from a WON op,
     *  scaled by contribution share ([CapturePayout]). RAID pays via the boss instead. */
    val commendMax: Int = 0,
) {
    RAID("raid party", troops = 8, quota = 0, cost = 0, coinPool = 0, prestige = 10, supplyCost = 0),
    CAMPAIGN("campaign", troops = 40, quota = 60, cost = 3_000_000, coinPool = 750_000, prestige = 25, supplyCost = 1500, commendMax = 6),
    CONQUEST("conquest", troops = 64, quota = 140, cost = 15_000_000, coinPool = 3_000_000, prestige = 60, supplyCost = 2800, commendMax = 10),

    /**
     * The **realm's own scheduled warband** ([MarchPlugin]) — no commander, launched by the world
     * itself and free for ANY player to fight beside (`::march`); also the tier a Lord's
     * `::operation` sponsors. The beginner/mid player's entry into the war's offense: the command
     * ladder reads March (anyone) → operation (Lord) → Campaign (Minister) → Conquest (King).
     * **Supply-free**: Realm Supplies belong to the commanders' Campaigns and Conquests.
     */
    MARCH("march", troops = 10, quota = 15, cost = 0, coinPool = 0, prestige = 5, supplyCost = 0, commendMax = 3),

    /**
     * The **GRAND MARCH** ([MarchPlugin]) — every Nth scheduled march, upsized and led against
     * the target's **Warden** (a boss-tier defender whose fall pays the forge's ember
     * components). Same realm sponsorship, `::march` join and supply-free rule as a regular march.
     */
    GRAND_MARCH("grand march", troops = 16, quota = 20, cost = 0, coinPool = 0, prestige = 10, supplyCost = 0, commendMax = 5),
}

/**
 * Where and how an offensive operation runs against one city. Deliberately light: the frontier
 * geography already exists ([CityFrontiers]); a campaign only needs where allied troops muster,
 * where they push, and the band they fight in.
 *
 * @param cityKey matches the [FrontierConfig.cityKey] so [Frontiers] resolves the enemy zone — a
 *   hostile city key ("varrock") or a [MarchTargets] key ("bandit_hideout").
 * @param stagingTile where allied troops spawn (the city gate, just inside the safe edge).
 * @param objectiveTile the push target when no enemy/boss is in reach (deep in the frontier).
 * @param battleArea the band the campaign fights and counts kills in (the frontier, not the town).
 */
data class CampaignOp(
    val cityKey: String,
    val cityId: Int,
    val displayName: String,
    val stagingTile: Tile,
    val objectiveTile: Tile,
    val battleArea: Area,
    val alliedNpc: String,
    val alliedDef: NpcCombatDef,
    val timeoutTicks: Int = 300,
    /**
     * The cross-country **march route** from the muster point ([stagingTile]) to the city. The army
     * spawns at the first waypoint and advances waypoint-by-waypoint (short, always-pathable hops),
     * fighting any enemy in reach along the way. Empty = no march (troops muster at the objective,
     * the old boss-raid behaviour). Each waypoint is snapped to walkable terrain at runtime.
     */
    val route: List<Tile> = emptyList(),
    /**
     * Bridge-deck spans on the [route] whose level-0 tile is the river's BLOCK flag (the deck renders
     * on level 1). Cleared at boot so NPCs can path across.
     */
    val bridgeSpans: List<Area> = emptyList(),
    /**
     * The **engagement line**: marching troops do NOT stop to fight until they enter this area (then
     * they brawl everything in reach). So the column pushes straight through the road skirmishes and
     * only "goes hot" at the enemy city's doorstep (e.g. the Varrock stone circle). Null = fight from
     * the start (engage anything along the whole march).
     */
    val aggroFromArea: Area? = null,
)

/**
 * Registry of every city's offensive [CampaignOp]. **Adding a hostile target city = adding one
 * here** (plus its [FrontierConfig]/[City]). The scheduled marches' smaller targets live in
 * [MarchTargets].
 */
object Campaigns {
    /** Allied troops: a notch above the frontier knights so a paid army is felt, but still mortal.
     *  Shared with [MarchTargets]. */
    internal val ALLIED_DEF = NpcCombatDef.DEFAULT.copy(
        attack = 80, strength = 75, defence = 65, hitpoints = 110,
        attackAnimation = 407,
    )

    /**
     * The **home** muster — Lumbridge. Boss-help squads (`::sendtroops`, and the `::summonboss`
     * vanguard) form up here on the player and march to whatever **boss** has spawned around home.
     * Home is the player's capital, NOT a campaign target — you don't besiege your own city.
     */
    val HOME = CampaignOp(
        cityKey = "lumbridge",
        cityId = Cities.DEFAULT_CITY_ID,
        displayName = "Lumbridge",
        // Fallback muster (troops normally spawn on the player); the boss is the real objective.
        stagingTile = Tile(3222, 3262, 0),
        objectiveTile = Tile(3222, 3300, 0),
        battleArea = Area(3185, 3258, 3290, 3335),
        alliedNpc = "npc.knight_of_saradomin", // shown renamed (e.g. "BizzyZ's Vanguard") at spawn
        alliedDef = ALLIED_DEF,
    )

    /**
     * **Fallen Varrock** — the commanders' hostile target. A Minister/King `::campaign`s or
     * `::conquest`s here; the city's undead/rogue garrison ([CityFrontiers.VARROCK]) defends it, its
     * loot pools into the war-chest, and the spoils auto-bank by contribution share
     * ([CapturePayout]). Victory is a temporary battlefield success — the city stays fallen (design
     * authority §7). Battlefield regions are force-loaded by [CityFrontierPlugin] so the far-flung
     * defenders are pathable. Coords TUNABLE.
     */
    val VARROCK = CampaignOp(
        cityKey = "varrock",
        cityId = 2,
        displayName = "Varrock",
        stagingTile = Tile(3231, 3219, 0),  // muster east of Lumbridge castle (BizzyZ's recorded start)
        objectiveTile = Tile(3213, 3424, 0), // push target: Varrock square
        // Must span the whole frontier (cityLimits + the deepest ring reach) so every kill registers
        // to the pool. cityLimits 3180,3400→3270,3460 expanded ~52 tiles for the hero ring. TUNABLE.
        battleArea = Area(3128, 3348, 3322, 3512),
        alliedNpc = "npc.knight_of_saradomin", // renamed per-sponsor at spawn (e.g. "BizzyZ's Soldier")
        alliedDef = ALLIED_DEF,
        timeoutTicks = 900, // a cross-country march + clearing the garrison takes a while (~18 min cap)
        // March route = BizzyZ's HAND-WALKED path (::recroute), down-sampled every ~8 tiles. Crosses
        // the River Lum at the z3226 bridge, up the far east bank (x3250-3265), then NW into Varrock —
        // dodging the river's bend. Walked on foot, so every hop is genuinely pathable for the column.
        route = listOf(
            Tile(3231, 3219, 0), Tile(3236, 3226, 0), // funnel to the west deck mouth (no river-corner cut)
            Tile(3239, 3226, 0), Tile(3248, 3226, 0), Tile(3257, 3227, 0),
            Tile(3260, 3235, 0), Tile(3259, 3243, 0), Tile(3254, 3251, 0), Tile(3250, 3260, 0),
            Tile(3250, 3268, 0), Tile(3242, 3275, 0), Tile(3239, 3283, 0), Tile(3238, 3292, 0),
            Tile(3239, 3300, 0), Tile(3243, 3309, 0), Tile(3249, 3317, 0), Tile(3257, 3324, 0),
            Tile(3265, 3324, 0), Tile(3257, 3333, 0), Tile(3249, 3335, 0), Tile(3241, 3337, 0),
            Tile(3233, 3337, 0), Tile(3226, 3346, 0), Tile(3226, 3354, 0), Tile(3218, 3359, 0),
            Tile(3216, 3368, 0), Tile(3213, 3376, 0), Tile(3211, 3385, 0), Tile(3211, 3393, 0),
            Tile(3211, 3401, 0), Tile(3211, 3409, 0), Tile(3210, 3417, 0), Tile(3212, 3425, 0),
            Tile(3212, 3433, 0), Tile(3212, 3441, 0), Tile(3212, 3449, 0), Tile(3212, 3457, 0),
            Tile(3212, 3465, 0),
        ),
        // NO bridge-clearing: the z3226 deck BizzyZ crossed is already walkable on the ground, so the
        // column crosses it like any player. (An earlier wide clear here also freed the bridge's SIDE
        // tiles, which broke the player crossing — "walk on the side, hop on top" — and let the troops
        // drift off the deck. Leave the bridge untouched.)
        bridgeSpans = emptyList(),
        // Engagement line: the column ignores all road skirmishes and pushes straight to the Varrock
        // doorstep — the stone circle (~z3363) — then goes hot and fights everything from there into
        // the city. North of z3360 across the Varrock latitudes. TUNABLE.
        aggroFromArea = Area(3140, 3360, 3320, 3512),
    )

    /**
     * **Hostile cities** a Minister/King can `::campaign` or `::conquest`. Each needs its own frontier
     * ([CityFrontiers]) and battlefield region force-load. Fallen Varrock is the commanders' target;
     * the scheduled marches strike the smaller [MarchTargets] instead, so `::campaign`/`::conquest`
     * never point at those.
     */
    val HOSTILE: List<CampaignOp> = listOf(VARROCK)

    /** Every op that carries a cross-country [CampaignOp.route] whose corridor must be force-loaded and
     *  bridge-cleared at boot ([CampaignCommandPlugin.prepareMarchCorridors]) — the commanders' hostile
     *  targets plus every scheduled-march target. A getter so this object's init never touches
     *  [MarchTargets] (one-way dependency). */
    val ROUTED: List<CampaignOp> get() = HOSTILE + MarchTargets.pool.map { it.op }

    /** The home muster (v1: everyone's capital is Lumbridge). */
    fun home(): CampaignOp = HOME

    /** The next hostile city a commander marches on (campaigns/conquests), or null if none configured. */
    fun hostileTarget(): CampaignOp? = HOSTILE.firstOrNull()

    /** A hostile target by city key (e.g. `::campaign varrock`), or null if not a war target. */
    fun hostileByKey(key: String): CampaignOp? = HOSTILE.firstOrNull { it.cityKey.equals(key, ignoreCase = true) }

    fun byKey(key: String): CampaignOp? =
        (listOf(HOME) + HOSTILE + MarchTargets.pool.map { it.op }).firstOrNull { it.cityKey.equals(key, ignoreCase = true) }
}
