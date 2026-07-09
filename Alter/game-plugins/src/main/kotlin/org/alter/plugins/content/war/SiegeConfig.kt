package org.alter.plugins.content.war

import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.combat.NpcCombatDef

/**
 * One battlefield within a city's defense ([SiegeConfig]). Attackers spawn at
 * [goblinSpawns] and march [attackerWaypoints] (camp → … → the castle goal); the
 * defenders march [defenderWaypoints] (muster → … → the attacker camp). The two
 * lines clash in the middle. Troop counts are NOT fixed here — the [Commander] AI
 * maneuvers the raid's finite roster across all of a city's fields each tick.
 */
data class FieldDef(
    /** Short human name for broadcasts ("EAST", "NORTH"). */
    val name: String,
    val zone: Area,
    val goblinSpawns: List<Tile>,
    val attackerWaypoints: List<Tile>,
    val defenderWaypoints: List<Tile>,
)

/**
 * One difficulty tier a raid can roll. Sets the **finite goblin roster** for the
 * raid; rarer/larger tiers field a [warlord]. The AI maneuvers this roster — the
 * tier only decides *how much force* it has to spend.
 */
data class RaidTier(
    val name: String,
    val rosterSize: Int,
    /** Relative chance of rolling this tier (weighted pick across all tiers). */
    val rarityWeight: Int,
    /** Whether this tier is led by the Goblin Warlord (the knights' kill objective). */
    val warlord: Boolean,
)

/**
 * A whole city's defense, as pure data. Defines the [WarState] front it drives, the
 * castle goal the attackers push toward (where General Zo stands — reaching it is a
 * breach), the defenders' muster + the gate doors to hold open, the combat NPCs, the
 * raid tiers, and every [fields] battlefield.
 *
 * **Adding a city = adding one of these to [Sieges].** The generic [SiegePlugin]
 * runs them all; no code changes per city.
 */
data class SiegeConfig(
    /** The [WarState] front key this defense drives. */
    val frontId: String,
    /** The [City] this defends (see [Cities]); ties the war to citizenship. */
    val cityId: Int,
    val displayName: String,
    /** Where the attackers are trying to reach — the castle / General Zo. Reaching it
     *  (within [breachDist]) overruns the city. */
    val goalTile: Tile,
    /** The protected castle **keep** (e.g. the new-player respawn courtyard). War goblins are
     *  HARD-LEASHED out of this box and never target a player standing inside it, so a fresh
     *  account can never be spawn-camped during a breach. Keep it WEST of [goalTile] so the horde
     *  can still reach General Zo at the gate and the siege resolves. Null = no keep (the whole
     *  battlefield is fair game, the pre-keep behaviour). */
    val castleKeep: Area? = null,
    /** Defender muster — knights spawn here and march out. */
    val knightMuster: List<Tile>,
    /** Doors at/around the muster the war holds permanently open (NPCs can't open doors). */
    val knightGateDoors: List<Tile>,
    val attackerNpc: String,
    val defenderNpc: String,
    val attackerDef: NpcCombatDef,
    val defenderDef: NpcCombatDef,
    /** The elite "shock troop" NPC (visually distinct, e.g. hobgoblin) seeded into the
     *  goblin commander's current **main thrust** field. */
    val attackerEliteNpc: String,
    val attackerEliteDef: NpcCombatDef,
    /** The named boss that leads warlord-tier raids (the knights' kill objective).
     *  Killing it routs the horde (the goblin AI is disrupted). */
    val warlordNpc: String,
    /** Where the Warlord spawns AND holds — he stays back here so players must fight through the
     *  horde to reach him (defaults to the goal if unset). */
    val warlordSpawnTile: Tile = goalTile,
    /** Where the NE lieutenant mini-boss holds (defaults to the goal if unset). */
    val lieutenantSpawnTile: Tile = goalTile,

    /** **General Zo** — the defended VIP the goblins hunt. He stands at [zoTile] (inside the
     *  castle); the goblin strike force attacks him directly, and his death **fells the city**.
     *  Players can't attack him (his cache NPC has no Attack option); goblins call attack()
     *  directly, so he's huntable by the horde but un-griefable. TUNE [zoTile] in-game. */
    val zoNpc: String,
    val zoTile: Tile,
    val zoDef: NpcCombatDef,

    val fields: List<FieldDef>,

    /** The raid tiers (Probe / Raid / Siege …) and their rarity. */
    val raidTiers: List<RaidTier>,
    /** The castle's full knight garrison — the AI never fields more than this at once. */
    val knightPoolMax: Int = 100,
    /** During peace, the pool regenerates +1 every this-many siege ticks toward the max. */
    val knightReplenishPeriodTicks: Int = 4,
    /** Random gap between raids (siege ticks). */
    val minPeaceTicks: Int = 200,
    val maxPeaceTicks: Int = 600,
    /** A raid that drags on this long (siege ticks) ends as a draw (horde withdraws). */
    val raidTimeoutTicks: Int = 400,
    /** Attackers within this many tiles of [goalTile] count as breaching (city falls). */
    val breachDist: Int = 3,
    /** How long the city stays *fallen* (siege ticks) after a breach before recovery. */
    val cityFallenTicks: Int = 3000,

    /** Each field keeps at least this many of each side present (so no field empties). */
    val baseGarrison: Int = 4,
    /** Per-field per-tick spawn RATE; a capped rate lets player kills thin a field. */
    val goblinReinforcePerTick: Int = 8,
    val knightReinforcePerTick: Int = 4,
    /** Goblins follow the [TacticalMap] flow field toward Zo (terrain-aware mass pathing),
     *  falling back to waypoints when off-grid. Flip off to use pure waypoints. */
    val useFlowField: Boolean = true,
    /** Bridge spans (boxes) whose deck is rendered at level 0 but whose level-0 tile is still
     *  flagged as the blocked river underneath — so NPCs can't cross. At raid setup the director
     *  clears the level-0 block on the bridge tiles (those blocked on L0 but walkable on L1), so the
     *  flow field and pathfinder can route troops across. Lets a camp sit across a river. */
    val bridgeSpans: List<Area> = emptyList(),
    /** Size of the elite wall the Warlord replenishes while he lives (0 = no bodyguards). */
    val bodyguardCount: Int = 20,
    /** Whether a warlord-tier raid also fields the NE lieutenant mini-boss. */
    val hasLieutenant: Boolean = true,
    /** The Warlord boss's hitpoints (scale to the garrison size). */
    val warlordHp: Int = 2000,
    /** Hold the knights back this many raid ticks before they deploy, so the goblins reach and stack
     *  on the bridge first and the clash happens there (not at the castle gates). */
    val knightDeployDelayTicks: Int = 0,
) {
    /** Weighted-random pick of a raid tier using [rarityWeight]. */
    fun rollTier(roll: (Int) -> Int): RaidTier {
        val total = raidTiers.sumOf { it.rarityWeight }.coerceAtLeast(1)
        var r = roll(total)
        for (tier in raidTiers) {
            r -= tier.rarityWeight
            if (r < 0) return tier
        }
        return raidTiers.last()
    }

    /** Bounding box of the whole battlefield (every field zone + the castle + Zo), padded —
     *  the extent of the [TacticalMap] grid. Returns [minX, minZ, width, height]. */
    fun battleBox(pad: Int = 4): IntArray {
        var minX = goalTile.x; var minZ = goalTile.z; var maxX = goalTile.x; var maxZ = goalTile.z
        fun fold(x: Int, z: Int) { if (x < minX) minX = x; if (z < minZ) minZ = z; if (x > maxX) maxX = x; if (z > maxZ) maxZ = z }
        fold(zoTile.x, zoTile.z)
        fields.forEach { f ->
            fold(f.zone.bottomLeftX, f.zone.bottomLeftY); fold(f.zone.topRightX, f.zone.topRightY)
        }
        minX -= pad; minZ -= pad; maxX += pad; maxZ += pad
        return intArrayOf(minX, minZ, maxX - minX + 1, maxZ - minZ + 1)
    }
}
