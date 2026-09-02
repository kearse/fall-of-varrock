package org.alter.plugins.content.war

import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.combat.NpcCombatDef

/**
 * One **enemy line** of a city's frontier: a ring of one hostile npc type at a fixed
 * distance band around the city. A frontier stacks several of these at increasing
 * distance so the battlefield is layered in concentric bands.
 *
 * [level] is the line's label; **level 1 = the furthest-back line** (the convention the
 * defenders' [DefenderLine.targetLevel] points at). It does not have to match distance
 * order in code — the geometry comes purely from [gap]/[depth] — but keep level 1 the
 * outermost so "push to level 1" reads as "push all the way out".
 *
 * The band starts [gap] tiles outside the city limits and is [depth] tiles thick;
 * density is [spacing] (one enemy per ~`spacing^2` tiles), hard-capped at [maxEnemies].
 *
 * [npcName] MUST be an id unique to this line (not shared with the siege, another line,
 * or another frontier): the engine keeps ONE global combat def + ONE death handler per
 * npc id, so a shared id throws on load or silently clobbers the loot.
 */
data class EnemyLine(
    val level: Int,
    val npcName: String,
    val combatDef: NpcCombatDef,
    val gap: Int,
    val depth: Int,
    val spacing: Int,
    val maxEnemies: Int,
    val walkRadius: Int = 8,
    val enemyNoun: String = "enemy",
    val coinMin: Int = 20,
    val coinMax: Int = 60,
    /** ~1-in-N kills also drop a gear piece. */
    val gearDropOneIn: Int = 12,
    /** The gp-VALUE a kill of this line adds to a CAMPAIGN war-chest pool (master design brief §3C) —
     *  kept separate from [coinMin]/[coinMax] so the war plunder can be lucrative (the campaign's
     *  whole point) WITHOUT making a casual kill a gp faucet. 0 = fall back to the coin+gear value. */
    val campaignKillValue: Int = 0,
    /** 1v1 vs players (only one of this line fights a given player at a time). Use on the
     *  front/new-player line so it isn't a swarm; leave false on deeper lines. */
    val singleCombat: Boolean = false,
    /** Client-displayed combat level override (null = the npc's cache level). Set to 2 on the
     *  front line so it reads as a low-level newbie monster. */
    val combatLevelOverride: Int? = null,
    /** Rank-based aggro floor (master design brief §4): this line ignores players whose feudal rank
     *  ordinal is >= this. Default = never ignore. e.g. [Title].SQUIRE.ordinal so goblins leave
     *  Squire+ alone, hobgoblins SOLDIER+, ogres KNIGHT+ — ranking up *feels* like power. */
    val aggroFloorRank: Int = Int.MAX_VALUE,
    /**
     * Explicit muster tiles (e.g. a hand-WALKED street set via `::recroute`) used INSTEAD of the
     * ring geometry — one enemy per tile, so defenders stand exactly on reachable streets and never
     * inside sealed buildings. When set, [gap]/[depth]/[spacing] are ignored (still capped by
     * [maxEnemies] and walkability-filtered). Null = the normal concentric ring.
     */
    val explicitStaging: List<Tile>? = null,
)

/**
 * The **good-guy line** of a frontier: friendly knights that muster and roam exactly the
 * way the enemy lines do, but fight the enemies instead of the player. They spread across
 * a ring band ([gap]/[depth]/[spacing]) — set it to span the whole frontier so knights
 * interleave with every enemy line — and wander naturally via [walkRadius], engaging any
 * enemy that comes within reach. No scripted march: the natural muster-and-roam is what
 * spreads the war across the ring.
 *
 * Population is [countRatio] × the frontier's total enemy count (0.5 ≈ "half as many
 * knights as monsters"). Stats are pushed per-npc via [combatDef] WITHOUT a global
 * `setCombatDef` (the knight id is shared with the war system).
 */
data class DefenderLine(
    val npcName: String,
    val combatDef: NpcCombatDef,
    val gap: Int = 0,
    val depth: Int = 17,
    val spacing: Int = 6,
    val walkRadius: Int = 12,
    /** Knights ≈ this fraction of the total enemy count across all lines (RING groups only). */
    val countRatio: Double = 0.5,
    /**
     * Explicit muster patch. When set, knights muster on the walkable tiles INSIDE this
     * rectangle (gridded by [spacing]) instead of the full ring, and the group size is the
     * fixed [count] — for dropping a targeted squad into a spot the even ring sampling
     * misses (e.g. the land strip between the river and Al Kharid). [gap]/[depth]/[countRatio]
     * are ignored when [area] is set.
     */
    val area: Area? = null,
    /** Fixed squad size when [area] is set ("a handful"). Ignored for ring groups. */
    val count: Int = 0,
    /** Optional display rename applied at spawn via [WarNpcNames] (must be a known key). */
    val displayName: String? = null,
)

/**
 * Configuration for one city's **hostile frontier**: one or more layered [enemyLines]
 * and an optional friendly [defender] line. [CityFrontierPlugin] turns each frontier
 * into a single [HostileZone]; adding a city = adding a [FrontierConfig] to
 * [CityFrontiers.all], no new code.
 */
data class FrontierConfig(
    val cityKey: String,
    val displayName: String,
    /** The safe town+castle rectangle (edge-inclusive). Every ring is built around this. */
    val cityLimits: Area,
    /** Enemy lines, layered by distance. Order doesn't matter; [EnemyLine.level] labels them. */
    val enemyLines: List<EnemyLine>,
    /** Friendly knight groups that fight the lines (a full-ring muster, targeted squads, or both). */
    val defenders: List<DefenderLine> = emptyList(),
    /**
     * No-spawn rectangles. Any muster tile (enemy OR knight) inside one of these is dropped,
     * on top of the [StaticTerrain] walkable filter — for carving out an area you don't want
     * the war in even though it's walkable (e.g. the Lumbridge swamp south of the city).
     */
    val exclude: List<Area> = emptyList(),
    /**
     * The castle **keep** — the new-player respawn courtyard — hard-protected on top of
     * [cityLimits] (no aggro in, leashed out). Usually a sub-area of [cityLimits]; kept separate so
     * the spawn stays safe even if [cityLimits] is retuned ([CityFrontiers.LUMBRIDGE_KEEP]).
     * Null = none.
     */
    val keep: Area? = null,
    /**
     * Whether [cityLimits] is a PROTECTED town the enemies are leashed OUT of (the home-defence case,
     * Lumbridge). Set false for an ENEMY city we attack (Varrock): its defenders belong IN the city,
     * so there's no safe-area leash pushing them off their own streets.
     */
    val protectCity: Boolean = true,
)

/**
 * Registry of every city under a [FrontierConfig]. All hardcoded frontier geography
 * (city box, ring distances, density, npc types) lives here; [CityFrontierPlugin] is
 * generic.
 */
object CityFrontiers {

    /** The protected castle **keep**: the new-player respawn courtyard (respawn tile 3209,3216).
     *  Frontier enemies are hard-leashed OUT of this box and never target a player inside it, so a
     *  fresh account can never be spawn-camped. Drawn around the courtyard from the region-12850
     *  dump. TUNABLE. */
    val LUMBRIDGE_KEEP = Area(3204, 3206, 3229, 3228)

    // --- combat defs (TUNE to taste) ---

    /** FRONT line — the main war mass at the city edge. Sturdy on purpose (real hp + bite) so
     *  the brawl with the knights LASTS and doesn't read as a one-sided mow-down. Goblin
     *  attack/block/death animations (6184/6183/6182). */
    private val GOBLIN_DEF = NpcCombatDef.DEFAULT.copy(
        // TRUE level-2 newbie goblins: trivial stats so a fresh account can train on them safely
        // (combined with the 1v1 front line + level-2 display override). The knights share this
        // band and mow them, but the in-place respawn refills every slot each zone tick — the
        // front reads as a perpetual melee, and new players can still tag their own goblin (1v1).
        attack = 1, strength = 1, defence = 1, hitpoints = 5,
        attackSpeed = 5,
        attackAnimation = 6184, blockAnimation = 6183, deathAnimation = listOf(6182),
        // aggroTargetDelay 4 = re-checks aggro every ~2.4s — still a quick hand-off to the next
        // goblin after a kill, but half the aggro-scan rate of the old delay-2 (cheaper per tick
        // across the whole front line). aggressiveTimer high so they don't tire of you.
        aggressiveRadius = 6, aggroTargetDelay = 4, aggressiveTimer = 2000,
    )

    /** Mid line. The hobgoblin's OWN animations: it is rigged to frame archive 425 (animations
     *  162-167), not the goblin's archive 1576. 6189/6191/6190 used to be set here, which are
     *  goblin animations — the exact skeleton mismatch the comment was trying to avoid. Confirm
     *  any replacement with `gradlew :game-server:npcDef -PnpcArgs="anims 132"`. */
    private val HOBGOBLIN_DEF = NpcCombatDef.DEFAULT.copy(
        attack = 24, strength = 24, defence = 18, hitpoints = 29,
        attackSpeed = 5,
        attackAnimation = 164, blockAnimation = 165, deathAnimation = listOf(167),
        aggressiveRadius = 6, aggroTargetDelay = 8, aggressiveTimer = 200,
    )

    /** Far line — toughest. A standard (attackable) ogre. NOTE: uses the generic melee
     *  animations (422/424/836) — verify the ogre swing in-game and set ogre attack anim ids
     *  if it looks stiff. (ogre_GUARD/trader/merchant are dialogue-only NPCs with no Attack
     *  option — use a plain combat ogre like ogre_2095, see [LUMBRIDGE].) */
    private val OGRE_DEF = NpcCombatDef.DEFAULT.copy(
        attack = 44, strength = 48, defence = 34, hitpoints = 60,
        attackSpeed = 6,
        aggressiveRadius = 6, aggroTargetDelay = 8, aggressiveTimer = 200,
    )

    /** Knights of Lumbridge. Knight attack animation 407 (per the war system); a notch
     *  below the war knights so the frontier stays an ongoing skirmish, not a steamroll. */
    private val KNIGHT_DEF = NpcCombatDef.DEFAULT.copy(
        attack = 60, strength = 55, defence = 60, hitpoints = 70,
        attackSpeed = 5,
        attackAnimation = 407,
    )

    val LUMBRIDGE = FrontierConfig(
        cityKey = "lumbridge",
        displayName = "Lumbridge",
        // Town + castle footprint (castle centre 3222,3219). TUNABLE — the box every ring hangs
        // off AND the leashed safe zone. North edge reaches z 3260 to enclose the NW buildings
        // (general store @3211,3247 + furnace @3227,3257) — earlier 3245 left them outside, so
        // goblins spawned/roamed among them.
        cityLimits = Area(3200, 3190, 3270, 3260),
        enemyLines = listOf(
            // Level 1 — THE FRONT. Hugs the city edge (gap 1), dense (spacing 5) and big
            // (220), so the war is right there the instant you leave the gate. The knights
            // share this band, so this is where the brawl actually happens.
            EnemyLine(
                level = 1, npcName = "npc.goblin_2245", combatDef = GOBLIN_DEF,
                gap = 1, depth = 16, spacing = 5, maxEnemies = 220, // in-place respawn keeps the line full
                enemyNoun = "goblin", coinMin = 15, coinMax = 45,
                singleCombat = true, // 1v1 for new players leaving town
                combatLevelOverride = 2, // reads as a level-2 newbie goblin
                aggroFloorRank = Title.SQUIRE.ordinal, // §4: goblins ignore Squire+ — outgrow the front line
            ),
            // Level 2 — hobgoblins a step out beyond the front. Lighter; mostly player ground.
            EnemyLine(
                level = 2, npcName = "npc.hobgoblin_2241", combatDef = HOBGOBLIN_DEF,
                gap = 20, depth = 18, spacing = 9, maxEnemies = 90,
                enemyNoun = "hobgoblin", coinMin = 20, coinMax = 60,
                aggroFloorRank = Title.SOLDIER.ordinal, // §4: hobgoblins ignore Soldier+
            ),
            // Level 3 — ogres, the deep/toughest line for players pushing out. Uses a plain
            // combat ogre (ogre_2095) — the ogre_guard variant is a Talk-to NPC with no Attack.
            EnemyLine(
                level = 3, npcName = "npc.ogre_2095", combatDef = OGRE_DEF,
                gap = 40, depth = 18, spacing = 11, maxEnemies = 60,
                enemyNoun = "ogre", coinMin = 60, coinMax = 140, gearDropOneIn = 8,
                aggroFloorRank = Title.KNIGHT.ordinal, // §4: ogres ignore Knight+
            ),
        ),
        defenders = listOf(
            // Knights share the GOBLIN band exactly (gap 1, depth 16 — the same start/end lines as
            // the front line), so the two sides interleave and are always brawling right outside the
            // city limits. Goblins respawn in place every zone tick, so the melee never runs dry.
            // countRatio 0.3 sizes the force to the total enemy count.
            DefenderLine(
                npcName = "npc.knight_of_saradomin", combatDef = KNIGHT_DEF,
                gap = 1, depth = 16, spacing = 6,
                walkRadius = 8, countRatio = 0.3,
                displayName = "Knight of Lumbridge",
            ),
        ),
        // Southern cutoff: NOTHING (goblins or knights) spawns south of y=3199 — keeps the war
        // out of the swamp. A wide horizontal band below the line; the frontier wraps N/E/W only.
        exclude = listOf(Area(3000, 2000, 3500, 3199)),
        // The castle keep (shared with the siege) — roaming frontier goblins are leashed out of the
        // respawn courtyard and never aggro a player inside it. (Already inside cityLimits, but kept
        // explicit so the spawn is hard-protected regardless of how cityLimits is tuned.)
        keep = LUMBRIDGE_KEEP,
    )

    // ====================== VARROCK — the FALLEN city, the commanders' HOSTILE target ==========
    // STORY (design authority, Sept 2026): Varrock fell twelve years ago to Zemouregal's undead
    // assault and the Senntisten catastrophe beneath it; it stays fallen. The dead still hold its
    // streets (see WorldSpawnsPlugin.applyFallenVarrock, the ambient layer) alongside the rogues who
    // moved in. A Minister/King campaign/conquest here is a temporary battlefield victory over that
    // host — never a reclamation: the risen dead on the approach + lower square, skeletal warriors
    // through the square, the undead champions who hold the ruined palace. The rogue occupiers
    // (marauders, Black Knights) hold the southern road OUTSIDE the walls — the public marches'
    // ground ([MarchTargets.VARROCK_OUTSKIRTS]). These are END-GAME enemies (no aggro-floor).
    //
    // Undead npc ids are DELIBERATELY variants with no ambient world spawns (zombie_880, skeleton_924,
    // skeleton_hero) so the frontier's global combat def + death handler don't collide with the
    // ambient undead (zombie 26 / skeleton 70) that WorldSpawnsPlugin spawns in the city. The
    // NpcAnims layer supplies each model's own attack/block/death set (see NpcAnims). TUNABLE.

    /** The risen dead — the front line holding the approach + lower square. */
    private val V_RISEN_DEAD_DEF = NpcCombatDef.DEFAULT.copy(
        attack = 60, strength = 65, defence = 50, hitpoints = 80,
        attackSpeed = 5,
        aggressiveRadius = 6, aggroTargetDelay = 4, aggressiveTimer = 400,
    )

    /** Skeletal warriors — the heavy line through the square. */
    private val V_SKELETAL_WARRIOR_DEF = NpcCombatDef.DEFAULT.copy(
        attack = 95, strength = 90, defence = 80, hitpoints = 130,
        attackSpeed = 5,
        aggressiveRadius = 6, aggroTargetDelay = 4, aggressiveTimer = 400,
    )

    /** Undead champions — the palace line, deepest and richest (they hold the city's plunder). */
    private val V_UNDEAD_CHAMPION_DEF = NpcCombatDef.DEFAULT.copy(
        attack = 120, strength = 120, defence = 100, hitpoints = 180,
        attackSpeed = 5,
        aggressiveRadius = 6, aggroTargetDelay = 4, aggressiveTimer = 400,
    )

    /** BizzyZ's hand-WALKED Varrock streets + central square (via `::recroute`) — every tile is on
     *  reachable public ground, so defenders placed here never sit in a sealed building. Partitioned
     *  by depth into the three lines below; the deep-castle overshoot (z>3472) is trimmed. Also the
     *  source of the [VarrockDistrict] centres. */
    internal val VARROCK_STREETS: List<Tile> = listOf(
        // South main street (x3211, climbing north into the city)
        Tile(3211, 3384, 0), Tile(3211, 3386, 0), Tile(3211, 3388, 0), Tile(3211, 3390, 0),
        Tile(3211, 3392, 0), Tile(3211, 3394, 0), Tile(3211, 3396, 0), Tile(3211, 3398, 0),
        Tile(3211, 3400, 0), Tile(3211, 3402, 0), Tile(3211, 3404, 0), Tile(3211, 3406, 0),
        Tile(3211, 3408, 0), Tile(3211, 3410, 0), Tile(3211, 3412, 0), Tile(3211, 3414, 0),
        Tile(3211, 3416, 0), Tile(3211, 3418, 0), Tile(3211, 3420, 0), Tile(3211, 3422, 0),
        // Central square (sprawling east + west)
        Tile(3213, 3423, 0), Tile(3215, 3423, 0), Tile(3216, 3424, 0), Tile(3218, 3426, 0),
        Tile(3220, 3427, 0), Tile(3222, 3427, 0), Tile(3224, 3429, 0), Tile(3222, 3429, 0),
        Tile(3220, 3430, 0), Tile(3220, 3432, 0), Tile(3219, 3433, 0), Tile(3217, 3433, 0),
        Tile(3215, 3434, 0), Tile(3213, 3434, 0), Tile(3211, 3434, 0), Tile(3209, 3433, 0),
        Tile(3207, 3431, 0), Tile(3208, 3429, 0), Tile(3206, 3429, 0), Tile(3204, 3429, 0),
        Tile(3202, 3429, 0), Tile(3200, 3429, 0), Tile(3198, 3429, 0), Tile(3196, 3429, 0),
        Tile(3196, 3431, 0), Tile(3196, 3433, 0), Tile(3195, 3435, 0), Tile(3195, 3437, 0),
        Tile(3195, 3439, 0), Tile(3197, 3441, 0), Tile(3199, 3440, 0), Tile(3201, 3439, 0),
        Tile(3203, 3439, 0), Tile(3205, 3439, 0), Tile(3207, 3438, 0), Tile(3209, 3437, 0),
        Tile(3211, 3436, 0),
        // North square + approach to the castle
        Tile(3212, 3438, 0), Tile(3212, 3440, 0), Tile(3212, 3442, 0), Tile(3212, 3444, 0),
        Tile(3212, 3446, 0), Tile(3212, 3448, 0), Tile(3210, 3448, 0), Tile(3209, 3446, 0),
        Tile(3207, 3444, 0), Tile(3207, 3446, 0), Tile(3206, 3448, 0), Tile(3206, 3450, 0),
        Tile(3205, 3452, 0), Tile(3207, 3452, 0), Tile(3209, 3450, 0), Tile(3210, 3449, 0),
        Tile(3212, 3449, 0), Tile(3214, 3449, 0), Tile(3216, 3448, 0), Tile(3216, 3446, 0),
        Tile(3217, 3444, 0), Tile(3219, 3444, 0), Tile(3221, 3446, 0), Tile(3222, 3447, 0),
        Tile(3222, 3449, 0), Tile(3220, 3451, 0), Tile(3219, 3452, 0), Tile(3217, 3452, 0),
        Tile(3215, 3454, 0), Tile(3213, 3456, 0), Tile(3213, 3458, 0),
        // Castle courtyard (trimmed at z3472)
        Tile(3213, 3460, 0), Tile(3212, 3462, 0), Tile(3210, 3462, 0), Tile(3208, 3461, 0),
        Tile(3207, 3461, 0), Tile(3206, 3462, 0), Tile(3206, 3464, 0), Tile(3207, 3466, 0),
        Tile(3209, 3466, 0), Tile(3211, 3466, 0), Tile(3213, 3466, 0), Tile(3215, 3466, 0),
        Tile(3217, 3466, 0), Tile(3219, 3464, 0), Tile(3221, 3462, 0), Tile(3216, 3468, 0),
        Tile(3214, 3468, 0), Tile(3213, 3470, 0), Tile(3212, 3472, 0), Tile(3212, 3470, 0),
        Tile(3212, 3468, 0), Tile(3212, 3467, 0), Tile(3212, 3465, 0), Tile(3212, 3463, 0),
    )

    val VARROCK = FrontierConfig(
        cityKey = "varrock",
        displayName = "Varrock",
        // Unused for spawn geometry now (explicit street staging below); kept for reference/leash off.
        cityLimits = Area(3180, 3400, 3270, 3472),
        enemyLines = listOf(
            // The undead host stands on the WALKED streets (one per tile), tiered by depth: the risen
            // dead on the approach + lower square, skeletal warriors through the square, the undead
            // champions by the palace. Small walkRadius so they hold their street and don't wander into
            // a building. maxEnemies high (no cap — the tile counts ARE the garrison). campaignKillValue
            // tuned for a ~10m pool. The ids are variants with no ambient world spawns (zombie_880,
            // skeleton_924, skeleton_hero), so the frontier's global combat def + death handler never
            // collide with the ambient undead layer (zombie 26 / skeleton 70). Boot logs a loud ERROR
            // if any id turns out not attackable.
            EnemyLine(
                level = 3, npcName = "npc.zombie_880", combatDef = V_RISEN_DEAD_DEF,
                gap = 0, depth = 0, spacing = 1, maxEnemies = 200, walkRadius = 4,
                explicitStaging = VARROCK_STREETS.filter { it.z <= 3439 },
                enemyNoun = "risen dead", coinMin = 80, coinMax = 180, gearDropOneIn = 12,
                campaignKillValue = 140_000,
                combatLevelOverride = 82,
                singleCombat = true, // 1v1 + anti-stack: defenders fight one foe, the rest wait their turn
            ),
            EnemyLine(
                level = 2, npcName = "npc.skeleton_924", combatDef = V_SKELETAL_WARRIOR_DEF,
                gap = 0, depth = 0, spacing = 1, maxEnemies = 200, walkRadius = 4,
                explicitStaging = VARROCK_STREETS.filter { it.z in 3440..3459 },
                enemyNoun = "skeletal warrior", coinMin = 200, coinMax = 400, gearDropOneIn = 8,
                campaignKillValue = 350_000,
                combatLevelOverride = 121,
                singleCombat = true,
            ),
            EnemyLine(
                level = 1, npcName = "npc.skeleton_hero", combatDef = V_UNDEAD_CHAMPION_DEF,
                gap = 0, depth = 0, spacing = 1, maxEnemies = 200, walkRadius = 4,
                explicitStaging = VARROCK_STREETS.filter { it.z in 3460..3472 },
                enemyNoun = "undead champion", coinMin = 400, coinMax = 800, gearDropOneIn = 5,
                campaignKillValue = 700_000,
                combatLevelOverride = 145,
                singleCombat = true,
            ),
        ),
        // No friendly defenders, no keep. protectCity=false: this is an enemy city we ASSAULT — its
        // garrison holds its own streets (no leash pushing it off them).
        protectCity = false,
    )

    val all: List<FrontierConfig> = listOf(LUMBRIDGE, VARROCK)

    /** Expand an [Area] outward by [m] tiles on every side. */
    private fun Area.expand(m: Int) =
        Area(bottomLeftX - m, bottomLeftY - m, topRightX + m, topRightY + m)

    /**
     * Evenly-spread muster points across a ring band around [limits]: inside the OUTER box
     * (limits + gap + depth) but outside the INNER box (limits + gap). Pure geometry —
     * walkability is filtered by the caller, since the collision map isn't loaded when the
     * registry is constructed. `Area.contains` is edge-inclusive, so a point on the inner
     * (gap) edge counts as inside and is excluded → the gap stays clear.
     */
    fun ringStaging(limits: Area, gap: Int, depth: Int, spacing: Int): List<Tile> {
        val inner = limits.expand(gap)
        val outer = limits.expand(gap + depth)
        val step = spacing.coerceAtLeast(1)
        val cells = ArrayList<Tile>()
        var x = outer.bottomLeftX
        while (x <= outer.topRightX) {
            var z = outer.bottomLeftY
            while (z <= outer.topRightY) {
                if (!inner.contains(x, z)) cells += Tile(x, z, 0)
                z += step
            }
            x += step
        }
        return cells
    }

    /** True if (x,z) falls inside any no-spawn rectangle. */
    fun isExcluded(exclude: List<Area>, x: Int, z: Int): Boolean = exclude.any { it.contains(x, z) }

    /** Grid every tile inside [area] at [spacing] steps (for an explicit muster patch). */
    fun gridArea(area: Area, spacing: Int): List<Tile> {
        val step = spacing.coerceAtLeast(1)
        val cells = ArrayList<Tile>()
        var x = area.bottomLeftX
        while (x <= area.topRightX) {
            var z = area.bottomLeftY
            while (z <= area.topRightY) { cells += Tile(x, z, 0); z += step }
            x += step
        }
        return cells
    }

    /** Trim [tiles] to at most [max], keeping an even spread (not a head-truncation). */
    fun capEvenly(tiles: List<Tile>, max: Int): List<Tile> {
        if (max <= 0) return emptyList()
        if (tiles.size <= max) return tiles
        val stride = tiles.size.toDouble() / max
        return (0 until max).map { tiles[(it * stride).toInt()] }
    }
}
