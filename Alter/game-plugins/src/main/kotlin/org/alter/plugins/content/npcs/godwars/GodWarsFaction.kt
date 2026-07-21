package org.alter.plugins.content.npcs.godwars

import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.rscm.RSCM.getRSCM

/**
 * **God Wars Dungeon factions** — the four warring armies of the dungeon and who belongs to
 * each. In OSRS every follower/general in the dungeon is hostile to *everything* that doesn't
 * share its god's allegiance: the four armies grind each other down in the main cavern while
 * players pick a side (or wear the god's item to pass unnoticed). This registry is the data
 * behind that behaviour; [GodWarsFactionCombatPlugin] reads it to spawn the roaming followers
 * and steer their combat.
 *
 * Membership covers three tiers, all keyed by their RSCM npc name:
 *  - the four **generals** (from [GodWarsBosses]) and their **bodyguards**, and
 *  - the roaming **followers** ([followers]) that populate the shared battlefield.
 *
 * The follower npc ids are unambiguous god-specific cache entries (each god's spiritual
 * mage/ranger/warrior trio, plus a signature creature), so tagging them by faction here never
 * affects the same-named generic monsters elsewhere in the world.
 */
object GodWarsFaction {

    enum class Faction(val display: String) {
        ARMADYL("Armadyl"),
        BANDOS("Bandos"),
        SARADOMIN("Saradomin"),
        ZAMORAK("Zamorak"),
    }

    /** The general → faction seed; bodyguards inherit their general's faction (see [keyFactions]). */
    private val generalFactions: Map<String, Faction> = mapOf(
        "npc.general_graardor" to Faction.BANDOS,
        "npc.kril_tsutsaroth" to Faction.ZAMORAK,
        "npc.kreearra_3162" to Faction.ARMADYL,
        "npc.commander_zilyana" to Faction.SARADOMIN,
    )

    /**
     * Roaming followers spawned onto the shared battlefield, one roster per faction. Each is a
     * god-specific cache id (the spiritual trio + a signature creature), verified against
     * `data/cfg/rscm/npc.rscm`.
     */
    val followers: Map<Faction, List<String>> = mapOf(
        Faction.ARMADYL to listOf(
            "npc.aviansie",
            "npc.spiritual_warrior_3166", "npc.spiritual_ranger_3167", "npc.spiritual_mage_3168",
        ),
        Faction.BANDOS to listOf(
            "npc.ork",
            "npc.spiritual_warrior_2243", "npc.spiritual_ranger_2242", "npc.spiritual_mage_2244",
        ),
        Faction.SARADOMIN to listOf(
            "npc.spiritual_warrior", "npc.spiritual_ranger", "npc.spiritual_mage",
        ),
        Faction.ZAMORAK to listOf(
            "npc.pyrefiend_433",
            "npc.spiritual_warrior_3159", "npc.spiritual_ranger_3160", "npc.spiritual_mage_3161",
        ),
    )

    /** Every GWD npc key → its faction (generals + bodyguards + followers). */
    private val keyFactions: Map<String, Faction> = buildMap {
        // Generals + their bodyguards, pulled from the boss roster so the two stay in sync.
        for (boss in GodWarsBosses.all) {
            val faction = generalFactions[boss.key] ?: continue
            put(boss.key, faction)
            boss.bodyguards.forEach { put(it, faction) }
        }
        // Roaming followers.
        followers.forEach { (faction, keys) -> keys.forEach { put(it, faction) } }
    }

    /** Resolved npc id → faction, built once (bad/unknown keys are simply skipped). */
    private val idFactions: Map<Int, Faction> by lazy {
        buildMap {
            keyFactions.forEach { (key, faction) ->
                runCatching { getRSCM(key) }.getOrNull()?.let { put(it, faction) }
            }
        }
    }

    /** The faction of the npc with this cache [id], or null if it isn't a GWD combatant. */
    fun factionOf(id: Int): Faction? = idFactions[id]

    /**
     * The main-dungeon battlefield: the open central cavern the four armies converge on. Its
     * region collision is already force-loaded by [org.alter.plugins.content.bosses.BossLairs]
     * (the GWD lairs), so followers can path here from the moment the world boots.
     *
     * [CENTRE] and the per-faction muster offsets are the real-OSRS main-dungeon floor (plane 2);
     * marked TUNE like the rest of the GWD coordinates — an in-game check may nudge them.
     */
    val CENTRE = Tile(2882, 5320, 2)

    /** Plane the whole dungeon (rooms + main cavern) sits on. */
    const val PLANE = 2

    /** Bounding box of the dungeon, used to scope any world-wide checks to GWD tiles. */
    val DUNGEON = Area(bottomLeftX = 2816, bottomLeftY = 5248, topRightX = 2968, topRightY = 5400)

    /** Where each faction's followers muster before advancing on [CENTRE]; keeps armies apart on
     *  spawn so they march in from four sides rather than spawning on top of each other. */
    val MUSTER: Map<Faction, Tile> = mapOf(
        Faction.ARMADYL to CENTRE.transform(-8, -8),
        Faction.BANDOS to CENTRE.transform(8, 8),
        Faction.ZAMORAK to CENTRE.transform(8, -8),
        Faction.SARADOMIN to CENTRE.transform(-8, 8),
    )
}
