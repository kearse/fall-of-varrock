package org.alter.plugins.content.war

import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.plugins.content.announce.Announce

/**
 * **The reconquest of Varrock** (story-and-grind-design §5) — the fallen city split into
 * districts, each with a persistent **pressure meter** ([WarState]) that march victories fill:
 *
 *   marches soften a district → BROKEN: its grip on the streets fails
 *   all districts broken     → the Palace road is open — the King's conquest awaits
 *
 * Every march is targeted at one district ([marchTarget]); winning it credits that district
 * ([creditMarchWin]). Broken districts also **weaken the garrison for the commanders**: each
 * one shaves the kill quota of a campaign/conquest on the city ([effectiveQuota]) — so the
 * public marches genuinely prepare the ground the endgame is fought on. `::districts` shows
 * the map. Approach waypoints are hand-picked street hops off the campaign route's city mouth
 * (snapped to walkable at runtime, self-healing via the march unstick) — TUNE in-game.
 */
enum class District(
    val key: String,
    val display: String,
    /** Street waypoints from the campaign route's city mouth (~3212,3425) to the district. */
    val approach: List<Tile>,
    /** March wins to break the district's grip. TUNE. */
    val breakAt: Int,
) {
    SLUMS(
        "slums", "the Slums",
        listOf(Tile(3202, 3427, 0), Tile(3192, 3427, 0), Tile(3182, 3427, 0)),
        breakAt = 3,
    ),
    OLD_MARKET(
        "old-market", "the Old Market",
        listOf(Tile(3213, 3424, 0)),
        breakAt = 3,
    ),
    EAST_QUARTER(
        "east-quarter", "the East Quarter",
        listOf(Tile(3222, 3427, 0), Tile(3232, 3427, 0), Tile(3242, 3427, 0), Tile(3252, 3427, 0)),
        breakAt = 3,
    ),
    MUSEUM_QUARTER(
        "museum-quarter", "the Museum Quarter",
        listOf(Tile(3222, 3428, 0), Tile(3232, 3436, 0), Tile(3243, 3444, 0), Tile(3253, 3451, 0)),
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
            Announce.broadcast(world, "<col=4f9b4f>The march holds ${d.display} — Varrock's occupiers find no footing there.</col>")
            return
        }
        val p = WarState.addDistrictPressure(d.key, 1).coerceAtMost(d.breakAt)
        if (p >= d.breakAt) {
            Announce.broadcast(world, "<col=ffcc00>${d.display.replaceFirstChar { it.uppercase() }} is BROKEN — the occupiers' grip on the district fails! (${brokenCount()}/${District.values().size} districts)</col>")
            if (allBroken()) {
                Announce.broadcast(world, "<col=ffcc00>Every district of Fallen Varrock is broken — the Palace road lies open. The realm awaits its King's conquest!</col>")
            }
        } else {
            Announce.broadcast(world, "<col=4f9b4f>The march softened ${d.display} — district pressure $p/${d.breakAt}.</col>")
        }
    }

    /**
     * The kill quota a [tier] op on [cityKey] actually needs: each broken district shaves
     * [QUOTA_DISCOUNT_PCT]% off a campaign/conquest on Varrock (floor [QUOTA_FLOOR_PCT]%) —
     * the marches' ground-softening made real. Marches/raids use their base quota.
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
        lines += "<col=801700>Fallen Varrock — the reconquest:</col>"
        for (d in District.values()) {
            lines += if (isBroken(d)) "  ${d.display}: <col=ffcc00>BROKEN</col>"
            else "  ${d.display}: pressure <col=4f9b4f>${pressure(d)}/${d.breakAt}</col>"
        }
        lines += if (allBroken()) {
            "<col=ffcc00>The Palace road is open — a King's ::conquest will purge the city.</col>"
        } else {
            "March with the knights (<col=ffae00>::march</col>) to soften the districts; broken districts weaken the garrison for the commanders' campaigns."
        }
        return lines
    }
}
