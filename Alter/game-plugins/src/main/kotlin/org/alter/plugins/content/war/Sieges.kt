package org.alter.plugins.content.war

import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.combat.NpcCombatDef

/**
 * Registry of every city's defense. **Adding a city's war = adding a [SiegeConfig]
 * here** — the generic [SiegePlugin] picks it up automatically.
 *
 * All hardcoded battlefield geography lives in this one file: tiles, waypoints, raid
 * tiers, troop stats. Nothing in the plugins is city-specific.
 */
object Sieges {
    // Goblins are tanky SPEED-BUMPS, not blenders: LOW strength (small max hit so they don't melt a
    // max player even in a stack) but high HP (slow to kill — clearing them is a time-sink). Moderate
    // attack lands on the knights (def 60) but mostly whiffs on a high-defence player. aggressive so
    // they auto-attack anyone who wanders near.
    private val goblinDef = NpcCombatDef.DEFAULT.copy(
        attack = 50, strength = 22, defence = 30, hitpoints = 80,
        attackAnimation = 6184, blockAnimation = 6183, deathAnimation = listOf(6182),
        aggressiveRadius = 12, aggroTargetDelay = 8, aggressiveTimer = 200,
    )
    private val knightDef = NpcCombatDef.DEFAULT.copy(
        // Tanky enough to hold the line a while (so it's a real fight to watch) but still doomed
        // against 25 tanky goblins without player help. HP is the lever for "lasts longer, still loses".
        attack = 75, strength = 70, defence = 60, hitpoints = 100,
        attackAnimation = 407,
    )
    // Shock-troop goblins (hobgoblins) — fiercer than the rabble, still low-damage but tankier.
    // Animations are the HOBGOBLIN's (frame archive 425), not the goblin set above (archive 1576):
    // a hobgoblin is rigged to a different skeleton and deforms when given goblin animations.
    private val goblinEliteDef = NpcCombatDef.DEFAULT.copy(
        attack = 65, strength = 32, defence = 45, hitpoints = 140,
        attackAnimation = 164, blockAnimation = 165, deathAnimation = listOf(167),
        aggressiveRadius = 12, aggroTargetDelay = 8, aggressiveTimer = 200,
    )
    // General Zo — the defended VIP. Tanky so reaching him is a real fight (the knights buy
    // him time); if the horde overwhelms him the city falls. TUNE to taste.
    private val zoDef = NpcCombatDef.DEFAULT.copy(
        attack = 110, strength = 110, defence = 120, hitpoints = 400,
        attackAnimation = 407,
    )

    // The raid tiers. A weighted roll decides which a raid fields; only the Siege tier
    // is led by a Warlord. TUNE rosters/rarity here.
    private val RAID_TIERS = listOf(
        RaidTier(name = "Probe", rosterSize = 10, rarityWeight = 50, warlord = false),
        RaidTier(name = "Raid", rosterSize = 18, rarityWeight = 35, warlord = false),
        RaidTier(name = "Siege", rosterSize = 25, rarityWeight = 15, warlord = true),
    )

    /** Goblin objective: the Lumbridge castle GATE. The horde marches here and is HELD at the gate
     *  by [LUMBRIDGE_KEEP] — it fights the knights (and any players) here but never enters the
     *  castle. There is no "assault General Zo / breach the keep" objective any more; reaching the
     *  gate is as far as the horde gets. Open ground on the z3219 corridor, just EAST of the keep
     *  (verified against the region-12850 map dump). TUNABLE. */
    private val gate = Tile(3232, 3219, 0)

    /** The protected castle **keep**: the new-player respawn courtyard (respawn tile 3209,3216).
     *  War goblins (and the frontier roamers — see [CityFrontiers]) are hard-leashed OUT of this
     *  box and never target a player inside it, so a fresh account can never be spawn-camped during
     *  a breach. Its EAST edge (x3229) sits a few tiles WEST of the gate ([gate], x3232), so the
     *  horde is held at the gate, on open ground east of the keep, without ever entering it. Drawn
     *  around the courtyard from the region-12850 dump. TUNABLE. */
    val LUMBRIDGE_KEEP = Area(3204, 3206, 3229, 3228)

    /** The single goblin camp — east of the River Lum at (3256,3249). The horde marches WEST across
     *  the south bridge (z3225-3226, opened in [SiegeConfig.bridgeSpans]) up to the castle [gate],
     *  where the keep holds it. One prong = a legible, tunable raid. The Warlord anchors this camp. */
    private val camp = Tile(3256, 3249, 0)

    val LUMBRIDGE = SiegeConfig(
        frontId = WarStatePlugin.FRONTIER_NORTH,
        cityId = Cities.DEFAULT_CITY_ID, // defends the frontier city (see Cities/City.fronts)
        displayName = "Lumbridge",
        goalTile = gate,
        // Goblins are hard-leashed out of this courtyard and never target a player inside it, so a
        // breach can never spawn-camp a fresh account (the spawn sits deep inside the keep).
        castleKeep = LUMBRIDGE_KEEP,
        // Knights muster at the castle gates and march OUT to every field.
        knightMuster = listOf(Tile(3226, 3214, 0), Tile(3226, 3223, 0)),
        knightGateDoors = listOf(Tile(3226, 3214, 0), Tile(3226, 3223, 0)),
        attackerNpc = "npc.goblin",
        defenderNpc = "npc.knight_of_saradomin", // shown as "Knight of Lumbridge" via WarNpcNames
        attackerDef = goblinDef,
        defenderDef = knightDef,
        attackerEliteNpc = "npc.hobgoblin", // visually-distinct shock troops
        attackerEliteDef = goblinEliteDef,
        // Player-ATTACKABLE boss (general_wartface has no Attack option). hobgoblin_champion (3354)
        // is attackable + a distinct id (no onNpcDeath collision), shown as "Goblin Warlord" with a
        // boss combat level via the spawn override. Swap the model later if you want a unique look.
        warlordNpc = "npc.hobgoblin_champion",
        warlordSpawnTile = camp, // the Warlord holds at the camp; players/knights fight through to him
        lieutenantSpawnTile = camp, // (no lieutenant — hasLieutenant=false below)
        // General Zo (repurposed melee_combat_tutor, shown as "General Zo" via WarNpcNames).
        // He stands INSIDE the castle as the garrison's general — the horde no longer hunts him and
        // his death no longer fells the city (that mechanic was removed). Decorative/NPC role for
        // now. Inside [LUMBRIDGE_KEEP], so the war never reaches him. TUNE in-game.
        zoNpc = "npc.melee_combat_tutor",
        // Inside the GE hub's desk ring, west column — one tile south of Duke Horacio
        // (3220,3211), against the 2x2 pillar (3221-3222 x 3210-3211).
        zoTile = Tile(3220, 3210, 0),
        zoDef = zoDef,
        // ONE prong: the camp east of the river marches WEST over the south bridge into the castle.
        // The flow field does the real routing (the bridge is opened in bridgeSpans); these waypoints
        // are only the off-flow fallback. Coords from the map — TUNE freely.
        fields = listOf(
            FieldDef(
                name = "EAST",
                zone = Area(3205, 3205, 3268, 3268), // covers the camp, the south bridge, and the castle
                goblinSpawns = listOf(camp),
                // Flow field is OFF (see useFlowField): goblins march to the gate via walkTo — the
                // SAME pathfinder the knights use, which crosses the bridge fine. A SINGLE waypoint: a
                // multi-point chain backtracks here because nextWaypoint() returns the first point >2
                // away, so a point you've already passed pulls you back. One target = no oscillation;
                // the pathfinder routes the whole camp→bridge→gate path itself.
                attackerWaypoints = listOf(gate),
                defenderWaypoints = listOf(Tile(3230, 3221, 0), Tile(3238, 3225, 0)),
            ),
        ),
        raidTiers = RAID_TIERS,
        knightPoolMax = 8, // tiny garrison: 8 knights can't hold 25 tanky goblins alone — they lose
        // by default and goblins leak to Zo. Players turning up (and killing the Warlord) is the win.
        knightReplenishPeriodTicks = 4,
        minPeaceTicks = 200, // ~18 min between raids at a 3-game-tick director cadence
        maxPeaceTicks = 600, // ~54 min
        raidTimeoutTicks = 400,
        breachDist = 3,
        cityFallenTicks = 1200, // ~1 hr fallen before recovery
        baseGarrison = 2,
        goblinReinforcePerTick = 4, // steady trickle for the ~50-goblin roster
        // A PAIR of knights per tick from the two muster gates (alternating) — they march out
        // two-by-two in a column rather than popping out in a batch.
        knightReinforcePerTick = 2,
        // Flow field OFF: its BFS connectivity is stricter than the engine pathfinder (treats any
        // collision flag — fence edges, etc. — as a full wall) and wrongly declared the bridge
        // approach unreachable, freezing the goblins. With it off they march waypoints via walkTo
        // (the real pathfinder the knights use), which crosses the bridge correctly.
        useFlowField = false,
        // The south bridge (the main Lumbridge bridge): deck rows z3225-3226, runs E-W across the River
        // Lum. Its deck renders at level 0 but the tile keeps the level-0 river block, so the flow
        // field/pathfinder can't cross. The director clears the block on these deck rows so the camp
        // east of the river connects to Zo. Box kept tight to the deck (a taller box opens open water).
        // EXACTLY the wooden deck (west end (3240,3225/26) from in-game clicks → east tip (3249,3226)),
        // two tiles wide. Tight to the planks so units cross ON the deck single-file, not over the open
        // river beside it. A wider box clears too much water and they wade across (looked wrong).
        bridgeSpans = listOf(Area(3240, 3225, 3249, 3226)),
        bodyguardCount = 6, // a small elite wall, not 20 (25 knights must be able to clear it)
        hasLieutenant = false, // one prong, one boss — the Warlord is enough
        warlordHp = 700, // scaled to the small garrison
        knightDeployDelayTicks = 6, // ~10s: let the goblins reach & stack on the bridge before the sally
    )

    val all: List<SiegeConfig> = listOf(LUMBRIDGE)

    /** The siege driving [frontId], if any. */
    fun byFront(frontId: String): SiegeConfig? = all.firstOrNull { it.frontId == frontId }

    /** Every siege defending [cityId] (a city can have more than one front later). */
    fun forCity(cityId: Int): List<SiegeConfig> = all.filter { it.cityId == cityId }

    /** The front whose battlefield contains [tile] (for crediting a kill), or null. */
    fun frontAt(tile: Tile): String? =
        all.firstOrNull { siege -> siege.fields.any { it.zone.contains(tile) } }?.frontId
}
