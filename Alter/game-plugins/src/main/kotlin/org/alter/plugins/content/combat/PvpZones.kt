package org.alter.plugins.content.combat

import org.alter.api.ext.Wilderness
import org.alter.api.ext.isMulti
import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.World

/**
 * Where HUMANS may fight each other, and at what level. This object answers exactly one question —
 * "is player-vs-player live on this tile, and how wide is the level bracket" — and nothing else:
 *  - **Wilderness (red):** the real OSRS wilderness ([Wilderness.SURFACE], north of the Edgeville
 *    ditch) plus its underground boss lairs, the Fallen Varrock PvP pocket ([VARROCK_POCKET]) and any
 *    live hostile zone. Attack is enabled, unprovoked attacks skull, deaths drop to the killer.
 *  - **Safe:** EVERYTHING else is safe FROM OTHER PLAYERS. A few safe carve-outs sit INSIDE the red
 *    (the GE / Varrock banks inside the pocket, Ferox Enclave) and override it; bank booths get an
 *    automatic radius via [BankSafezonePlugin].
 *  - **Single vs multi:** OSRS semantics — the wild is SINGLE by default and the [MULTI] boxes are the
 *    exception; a few areas are pinned single regardless ([SINGLE_OVERRIDES]).
 *  - **Level:** the OSRS depth formula ([Wilderness.levelAt]); lairs, hostile zones and pockets carry
 *    a fixed level. `canTeleport` and `Combat.canEngage` both read [wildernessLevel], so the teleport
 *    restriction and the combat bracket can never disagree.
 *
 * Rogue Knight PK bots are NOT governed here — they hunt the whole mainland, cities included, under
 * their own authority (`bots/RogueTerritory`). `Combat.canEngage` waves bot combat past every rule
 * in this file.
 *
 * Boxes marked TUNE are approximations — `::zone` in-game prints the live classification + level.
 */
object PvpZones {

    /**
     * The OSRS wilderness surface box — the authority for where the wild is. Bot spawns tile it
     * directly (see [org.alter.plugins.content.bots.BotZones]) so the deep-wild PKer grid can never
     * drift off this boundary.
     */
    val mainWilderness: Area = Wilderness.SURFACE

    /**
     * FALLEN VARROCK PvP POCKET (design authority 03 §3: "PvP inside Varrock remains OPEN"). The
     * city sits south of the ditch, so it is red by this box alone — a flat fixed level (the old
     * depth model read ~15-33 across the city) and single combat, with the GE + both banks carved
     * out below. Same box as `BotZones.fallen_varrock` / `WorldSpawnsPlugin.FALLEN_VARROCK`.
     * Level 20 keeps standard-spellbook teleports usable (they refuse ABOVE 20). TUNE.
     */
    val VARROCK_POCKET: Area = Area(3155, 3376, 3300, 3520)
    const val VARROCK_POCKET_LEVEL = 20

    /** Fixed-level red boxes OUTSIDE the OSRS surface box. */
    private val POCKETS: List<Pair<Area, Int>> = listOf(
        VARROCK_POCKET to VARROCK_POCKET_LEVEL,
    )

    /**
     * MULTI-combat boxes inside the wild (everything else in the wild is single). Transcribed from
     * the OSRS multi-combat map and chunk-rounded — all TUNE, verify by walking them with `::zone`.
     */
    private val MULTI: List<Area> = listOf(
        Area(3016, 3616, 3055, 3655), // Dark Warriors' Fortress
        Area(3192, 3616, 3231, 3655), // the Corporeal Beast entrance strip
        Area(3136, 3656, 3199, 3703), // Graveyard of Shadows
        Area(3200, 3728, 3271, 3775), // Bone Yard
        Area(3176, 3800, 3239, 3863), // Lava Dragon Isle
        Area(3272, 3856, 3335, 3903), // Demonic Ruins
        Area(3224, 3896, 3335, 3967), // Rogues' Castle + the Chaos Elemental
        Area(3088, 3920, 3127, 3967), // Mage Arena
        Area(2984, 3928, 3015, 3967), // Wilderness Agility Course
        Area(3032, 3936, 3079, 3967), // Pirates' Hideout
    )

    /**
     * Pinned SINGLE combat, winning over [MULTI]: the deep Rogue Knight camps fight 1v1 so the
     * ladder's tier hunts and boss duels stay fair (boxes mirror the BotZones colonies — TUNE
     * together), and the Varrock pocket is a 1v1 loot hub, not a pile.
     */
    private val SINGLE_OVERRIDES: List<Area> = listOf(
        Area(3020, 3675, 3055, 3705),  // the Wild Bandit Camp
        Area(2995, 3865, 3035, 3900),  // the Rogue Commander's Redoubt
        VARROCK_POCKET,
    )

    /** Safe carve-outs INSIDE the red (everywhere OUTSIDE the red is already safe). */
    private val SAFE_INSIDE_RED: List<Area> = listOf(
        Area(3140, 3470, 3185, 3515), // Grand Exchange (inside the Varrock pocket)
        Area(3178, 3432, 3196, 3453), // Varrock west bank
        Area(3250, 3416, 3257, 3424), // Varrock east bank
        Area(3125, 3617, 3155, 3648), // Ferox Enclave — OSRS safe island at level ~12-16. TUNE
    )

    /** Extra safe boxes registered at runtime (bank booths discovered in the world). */
    private val safeDynamic = mutableListOf<Area>()

    /**
     * HOSTILE ZONES ([org.alter.plugins.content.hostilezones.HostileZones]) — hostile ground turned
     * open-PvP loot grounds (the extraction loop). Each enabled zone is red; one with a fixed
     * `wildLevel` keeps that level regardless of depth (a zone with none inherits the depth level),
     * a `singleCombat` zone is 1v1, and ground outside [mainWilderness] becomes red through this
     * list. Banks stay safe via [BankSafezonePlugin]'s dynamic carve-outs.
     *
     * `by lazy` so classload order never matters: HostileZones is pure data and never references
     * this object back, but bots/plugins touch [mainWilderness] during their own class init and we
     * must not force the catalog to build before the RSCM is up. The registry hands out ONE list
     * instance refreshed in place, so caching it here is safe.
     */
    private val hostile: List<org.alter.plugins.content.hostilezones.HostileZoneConfig> by lazy {
        org.alter.plugins.content.hostilezones.HostileZones.all
    }

    private fun inRed(t: Tile): Boolean =
        mainWilderness.contains(t) ||
            Wilderness.DUNGEONS.any { it.first.contains(t) } ||
            POCKETS.any { it.first.contains(t) } ||
            hostile.any { it.area.contains(t) }

    private fun inCarveout(t: Tile): Boolean = SAFE_INSIDE_RED.any { it.contains(t) } || safeDynamic.any { it.contains(t) }

    /** OSRS default is single; lairs, [MULTI] boxes and non-1v1 hostile zones are multi. */
    private fun inSingle(t: Tile): Boolean {
        if (SINGLE_OVERRIDES.any { it.contains(t) }) return true
        if (hostile.any { it.singleCombat && it.area.contains(t) }) return true
        if (Wilderness.DUNGEONS.any { it.first.contains(t) }) return false
        if (MULTI.any { it.contains(t) }) return false
        if (hostile.any { it.area.contains(t) }) return false
        return true
    }

    /** True PvP-enabled wilderness tile (inside red, not a safe carve-out). */
    fun isWilderness(t: Tile): Boolean = inRed(t) && !inCarveout(t)

    /** Safe FROM PLAYERS = anything that isn't live wilderness (outside red, or a carve-out inside it). */
    fun isSafe(t: Tile): Boolean = !isWilderness(t)

    fun isSingle(t: Tile): Boolean = isWilderness(t) && inSingle(t)

    fun isMulti(t: Tile): Boolean = isWilderness(t) && !inSingle(t)

    /**
     * Multi-combat ground for AoE purposes (bursts/barrages, chinchompas, the d2h/dcb sweeps):
     * the multi wilderness OR a PvE region/chunk a content plugin flagged with
     * `setMultiCombatRegion` (boss lairs, the GWD throne rooms). The client's crossed-swords icon
     * is driven from this same predicate inside the wild ([WildernessOverlayPlugin]), so the two agree.
     */
    fun isMultiCombat(t: Tile, world: World): Boolean = isMulti(t) || t.isMulti(world)

    /** Wilderness level at [t] (0 if not live wilderness). */
    fun wildernessLevel(t: Tile): Int {
        if (!isWilderness(t)) return 0
        // Fixed levels first: lairs and pockets sit at latitudes where the depth math is meaningless.
        Wilderness.DUNGEONS.firstOrNull { it.first.contains(t) }?.let { return it.second }
        hostile.firstOrNull { it.area.contains(t) }?.wildLevel?.let { return it } // hostile zones: fixed level (null = depth)
        POCKETS.firstOrNull { it.first.contains(t) }?.let { return it.second }
        val depth = Wilderness.levelAt(t)
        // A depth-levelled hostile zone placed OFF the surface box has no depth to read — treat it
        // as open any-level PvP rather than a level-0 wild tile.
        return if (depth > 0) depth else Wilderness.MAX_LEVEL
    }

    /**
     * A safe carve-out: the GE / Varrock bank pockets / Ferox, or any bank radius registered at
     * runtime ([BankSafezonePlugin]). No human PvP even inside the red — and the Rogue Knights'
     * no-muster / no-unprovoked-ambush ground everywhere ("the banks survived behind barricades").
     */
    fun isCarveout(t: Tile): Boolean = inCarveout(t)

    /** Register a safe box (used to auto-protect bank booths). */
    fun addSafeArea(area: Area) {
        safeDynamic += area
    }

    fun safeAround(tile: Tile, radius: Int) {
        addSafeArea(Area(tile.x - radius, tile.z - radius, tile.x + radius, tile.z + radius))
    }
}
