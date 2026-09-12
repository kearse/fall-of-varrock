package org.alter.plugins.content.bots

import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player
import org.alter.plugins.content.combat.PvpZones
import org.alter.plugins.content.mechanics.onboarding.FirstLoginFlow
import org.alter.plugins.content.war.StaticTerrain

/**
 * The Rogue Knights' own law: where they muster, whom they may hunt, and how dangerous a stretch
 * of ground is. Deliberately SEPARATE from [PvpZones], which only answers "can HUMANS fight here" —
 * Rogue Knights hunt the whole mainland, cities included, so leaving town is dangerous while the
 * real wilderness (north of the Edgeville ditch) stays the only place players fight each other.
 * "Practise on the knights, then cross the ditch."
 *
 * Three questions, three seams:
 *  - [canMuster] — the colony spawn filter. Grid knights never muster inside a [CITY_CORES] box
 *    (they spawn on the roads and fields OUTSIDE town and chase you in), never on a bank radius,
 *    never in a [SANCTUARIES] box. Pinned camps (`tier != null`) are placed by hand and may sit
 *    anywhere — the organized rogue camps on the Draynor/Sarim road are inside town limits on purpose.
 *  - [canHunt] — the aggro gate. Never a player who can't fight back ([sanctuary]: onboarding,
 *    cutscene-locked, inside an instance, on a sanctuary tile), never across floors; an
 *    UNPROVOKED knight also respects the post-death / post-login truce and the bank radii. A
 *    player who swings first gets the fight wherever they stand (the `provokedBy` latch).
 *  - [dangerLevel] — the tiering input. Inside live wilderness it IS the wilderness level (OSRS
 *    depth, so the deep wild stays elite); on the mainland it scales with distance from the nearest
 *    safe city ([CITY_ANCHORS]): metal fodder at the walls, budget sets on the roads, mid mains
 *    between cities. Elite NHers never spawn on the mainland by construction.
 *
 * Every box and band is TUNE — `::zone` prints the live danger level / core / bank / truce state.
 */
object RogueTerritory {

    /** The overworld colony box (see [BotZones]): the Rimmington coast → the Digsite, the Sarim
     *  road → the Edgeville ditch. Kandarin / Karamja / Morytania have no content yet. TUNE. */
    val MAINLAND: Area = Area(2880, 3140, 3450, 3522)

    /** City cores: no grid muster, no idle roam INTO them — chase allowed. TUNE. */
    val CITY_CORES: List<Area> = listOf(
        Area(3182, 3172, 3290, 3280), // Lumbridge: town + spawn + The Mire + the Recruit Trials woods
        Area(3075, 3240, 3110, 3275), // Draynor Village
        Area(2942, 3300, 3066, 3400), // Falador, the whole walled city
        Area(3265, 3145, 3330, 3200), // Al Kharid
        Area(3010, 3205, 3055, 3235), // Port Sarim docks
        Area(2940, 3200, 2975, 3235), // Rimmington
        Area(3067, 3488, 3098, 3522), // Edgeville — the PvP staging town
        Area(3140, 3470, 3185, 3515), // Grand Exchange
    )

    /** Never hunt here, never muster here. Varrock is deliberately NOT one — it fell. */
    val SANCTUARIES: List<Area> = listOf(
        Area(3340, 3400, 3400, 3450), // the Digsite: Senntisten winch / story-boss exits land here
    )

    /** Danger anchors — the centre of each surviving safe city. Varrock excluded (fallen). TUNE. */
    val CITY_ANCHORS: List<Tile> = listOf(
        Tile(3222, 3218), // Lumbridge
        Tile(3093, 3245), // Draynor
        Tile(2965, 3380), // Falador
        Tile(3293, 3180), // Al Kharid
        Tile(3040, 3202), // Port Sarim
        Tile(2957, 3214), // Rimmington
        Tile(3093, 3495), // Edgeville
    )

    /**
     * World cycle until which a player is under truce — no UNPROVOKED knight aggro. Session-only
     * and deliberately not `resetOnDeath`: it is written AFTER the death sequence has already
     * cleared the reset-on-death attributes (`PlayerDeathAction.respawn`).
     */
    val ROGUE_TRUCE_UNTIL_ATTR = AttributeKey<Int>()

    fun inCityCore(t: Tile): Boolean = CITY_CORES.any { it.contains(t) }

    private fun inSanctuary(t: Tile): Boolean = SANCTUARIES.any { it.contains(t) }

    /** Colony muster filter: walkable, not a sanctuary, not a bank radius, and (grid zones only) not a city core. */
    fun canMuster(cfg: BotZoneConfig, t: Tile): Boolean =
        StaticTerrain.isWalkable(t.x, t.z) &&
            !inSanctuary(t) &&
            !PvpZones.isBankSafe(t) &&
            (cfg.tier != null || !inCityCore(t))

    /** Idle-roam destination filter: a grid knight never strolls into a city core on its own. */
    fun canRoamTo(bot: PkBot, t: Tile): Boolean = !bot.cityAware || !inCityCore(t)

    /**
     * HARD protection — a player no knight may touch, provoked or not. Also consulted by
     * `Combat.canEngage` every combat cycle, so an in-flight fight ends the tick this becomes true.
     */
    fun sanctuary(p: Player): Boolean =
        p is PkBot || !p.isOnline || p.invisible ||
            FirstLoginFlow.isOnboarding(p) ||
            !p.lock.canAttack() ||
            p.world.instanceAllocator.isInstanceSpace(p.tile) ||
            inSanctuary(p.tile)

    /** May [bot] hunt [p]? [provoked] = the player started (or owns) this fight. */
    fun canHunt(bot: PkBot, p: Player, provoked: Boolean): Boolean {
        if (sanctuary(p)) return false
        if (p.tile.height != bot.tile.height) return false
        if (provoked) return true
        return !onTruce(p) && !PvpZones.isBankSafe(p.tile)
    }

    fun onTruce(p: Player): Boolean = (p.attr[ROGUE_TRUCE_UNTIL_ATTR] ?: 0) > p.world.currentCycle

    fun grantTruce(p: Player, ticks: Int) {
        p.attr[ROGUE_TRUCE_UNTIL_ATTR] = p.world.currentCycle + ticks
    }

    /** May [p] wake a colony? (A frozen new player in the courtyard doesn't spawn knights.) */
    fun activates(p: Player): Boolean =
        p !is PkBot && p.isOnline && !p.invisible && !FirstLoginFlow.isOnboarding(p)

    /**
     * Pseudo wilderness level for [BotZones.tierForWildLevel]: the real level inside live wilderness
     * (OSRS depth in the wild, the pocket's fixed level in Varrock), else a band by distance from
     * the nearest safe city. Bands map onto the tier ladder (≤10 metal, ≤20 budget, ≤30 mid, ≤40
     * high) — 35 is the mainland ceiling, so T_ELITE only ever spawns in the deep wild. TUNE.
     */
    fun dangerLevel(t: Tile): Int {
        if (PvpZones.isWilderness(t)) return PvpZones.wildernessLevel(t)
        val d = CITY_ANCHORS.minOf { t.getDistance(it) }
        return when {
            d < NEAR_CITY -> 5
            d < ON_THE_ROAD -> 15
            d < BETWEEN_CITIES -> 25
            else -> 35
        }
    }

    private const val NEAR_CITY = 40       // tiles from an anchor: metal-armour fodder
    private const val ON_THE_ROAD = 90     // budget PK sets
    private const val BETWEEN_CITIES = 150 // mid mains; beyond = high / maxers
}
