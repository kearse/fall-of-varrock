package org.alter.plugins.content.war

import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.plugins.content.announce.Announce

/**
 * **The reconquest of Falador** (story-and-grind-design §5) — the overrun city the realm's scheduled
 * marches move on ([MarchPlugin]/[Campaigns.FALADOR]), split into districts, each with a persistent
 * **pressure meter** ([WarState]) that march victories fill:
 *
 *   marches soften a district → BROKEN: the occupiers' grip on the streets fails
 *   all districts broken     → Falador is routed — the realm can turn on demon-held Varrock
 *
 * Every march is targeted at one district ([marchTarget]); winning it credits that district
 * ([creditMarchWin]). Broken districts also **weaken the demons for the commanders**: each one shaves
 * the kill quota of a campaign/conquest on demon-held Varrock ([effectiveQuota]) — so the public
 * marches on Falador genuinely prepare the ground the endgame in Varrock is fought on. `::districts`
 * shows the map. The district labels carry over from the old Varrock reconquest (the named captains in
 * [org.alter.plugins.content.war.captains] fled with them); the approach waypoints are Falador street
 * hops off the march route's city mouth (~3040,3340), snapped to walkable at runtime and self-healing
 * via the march unstick — TUNE in-game with `::recroute`.
 */
enum class District(
    val key: String,
    val display: String,
    /** Street waypoints from the march route's Falador city mouth (~3040,3340) to the district. */
    val approach: List<Tile>,
    /** March wins to break the district's grip. TUNE. */
    val breakAt: Int,
) {
    SLUMS(
        "slums", "the Slums",
        listOf(Tile(3033, 3345, 0), Tile(3025, 3351, 0), Tile(3017, 3355, 0), Tile(3013, 3357, 0)),
        breakAt = 3,
    ),
    OLD_MARKET(
        "old-market", "the Old Market",
        listOf(Tile(3028, 3348, 0), Tile(3009, 3357, 0), Tile(2989, 3363, 0), Tile(2971, 3369, 0), Tile(2957, 3371, 0)),
        breakAt = 3,
    ),
    EAST_QUARTER(
        "east-quarter", "the East Quarter",
        listOf(Tile(3044, 3338, 0), Tile(3045, 3336, 0)),
        breakAt = 3,
    ),
    MUSEUM_QUARTER(
        "museum-quarter", "the Museum Quarter",
        listOf(Tile(3030, 3344, 0), Tile(3013, 3345, 0), Tile(2997, 3343, 0), Tile(2985, 3342, 0), Tile(2977, 3341, 0)),
        breakAt = 4,
    ),
    ;

    /** Where the column finally rallies (the approach's last hop). */
    val rally: Tile get() = approach.last()
}

object Districts {

    /** Quota shaved off a campaign/conquest per broken district, in percent. TUNE. */
    private const val QUOTA_DISCOUNT_PCT = 10
    /** The quota never drops below this fraction of its base, however broken the city. */
    private const val QUOTA_FLOOR_PCT = 50

    fun pressure(d: District): Int = WarState.getDistrictPressure(d.key).coerceAtMost(d.breakAt)

    fun isBroken(d: District): Boolean = pressure(d) >= d.breakAt

    fun brokenCount(): Int = District.values().count { isBroken(it) }

    fun allBroken(): Boolean = District.values().all { isBroken(it) }

    /**
     * The next march's target: a random unbroken district, or — once the whole city is
     * softened — a random broken one to patrol (holds the ground; adds no pressure).
     */
    fun marchTarget(world: World): District {
        val unbroken = District.values().filter { !isBroken(it) }
        val pool = unbroken.ifEmpty { District.values().toList() }
        return pool[world.random(pool.size - 1)]
    }

    /** Credit a march victory to [d]: raise its pressure and tell the realm what it meant. */
    fun creditMarchWin(world: World, d: District) {
        if (isBroken(d)) {
            Announce.broadcast(world, "<col=4f9b4f>The march holds ${d.display} — Falador's occupiers find no footing there.</col>")
            return
        }
        val p = WarState.addDistrictPressure(d.key, 1).coerceAtMost(d.breakAt)
        if (p >= d.breakAt) {
            Announce.broadcast(world, "<col=ffcc00>${d.display.replaceFirstChar { it.uppercase() }} is BROKEN — the occupiers' grip on the district fails! (${brokenCount()}/${District.values().size} districts)</col>")
            if (allBroken()) {
                Announce.broadcast(world, "<col=ffcc00>Every district of Fallen Falador is broken — the occupiers are routed. The realm can turn its full strength on demon-held Varrock; a King's ::conquest awaits!</col>")
            }
        } else {
            Announce.broadcast(world, "<col=4f9b4f>The march softened ${d.display} — district pressure $p/${d.breakAt}.</col>")
        }
    }

    /**
     * The kill quota a [tier] op on [cityKey] actually needs: each broken Falador district shaves
     * [QUOTA_DISCOUNT_PCT]% off a campaign/conquest on demon-held Varrock (floor [QUOTA_FLOOR_PCT]%) —
     * routing the occupiers out of Falador loosens the demons' hold, so the public marches' ground-
     * softening is made real for the commanders' endgame. Marches themselves use their base quota.
     */
    fun effectiveQuota(cityKey: String, tier: CampaignTier): Int {
        if (!cityKey.equals("varrock", ignoreCase = true)) return tier.quota
        if (tier != CampaignTier.CAMPAIGN && tier != CampaignTier.CONQUEST) return tier.quota
        val pct = (100 - QUOTA_DISCOUNT_PCT * brokenCount()).coerceAtLeast(QUOTA_FLOOR_PCT)
        return (tier.quota * pct / 100).coerceAtLeast(1)
    }

    /** The `::districts` report. */
    fun statusLines(): List<String> {
        val lines = ArrayList<String>()
        lines += "<col=801700>Fallen Falador — the reconquest:</col>"
        for (d in District.values()) {
            lines += if (isBroken(d)) "  ${d.display}: <col=ffcc00>BROKEN</col>"
            else "  ${d.display}: pressure <col=4f9b4f>${pressure(d)}/${d.breakAt}</col>"
        }
        lines += if (allBroken()) {
            "<col=ffcc00>Falador is routed — a King's ::conquest can now purge demon-held Varrock.</col>"
        } else {
            "March with the knights (<col=ffae00>::march</col>) to soften Falador's districts; broken districts weaken the demons' hold on Varrock for the commanders' campaigns."
        }
        return lines
    }
}
