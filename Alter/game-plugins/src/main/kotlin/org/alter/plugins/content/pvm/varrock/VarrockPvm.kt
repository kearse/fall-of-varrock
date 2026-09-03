package org.alter.plugins.content.pvm.varrock

import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable
import org.alter.plugins.content.war.CityFrontiers
import org.alter.plugins.content.war.VarrockDistrict

/**
 * **Fallen Varrock — the persistent PvM layer** (design doc 05 §2/§4, handoff §7: "Varrock
 * remains a permanent endgame ecosystem: repeated assaults, exploration, salvage, captains,
 * Wardens, bosses, war-forging materials"). FoV-original content, always on, independent of
 * campaign state, living beside the ambient undead ([WorldSpawnsPlugin]) and the four captains.
 *
 *  - **Elite undead** — a stronger tier on fresh variant ids (no ambient rows, no other
 *    handlers) posted along the northern streets and the palace approach.
 *  - **Salvage piles** — searchable crates scattered through every district; each yields
 *    [SALVAGE_KEY] and, rarely, a [RELIC_KEY], then lies bare for a while. Searching can wake
 *    something.
 *  - **Malachai the Hollow** — a wandering named undead that rises at a random district centre
 *    on a clock, announced server-wide, gone again if nobody fells him.
 *  - **The Palace Warden** — the deep repeatable surface target (handoff §7 "Palace"): a
 *    zombie champion holding the palace, raising the dead and chilling the ground.
 *  - **Arrav Intelligence** — Captain Rovin's assignment board at the west bank pocket
 *    ([ArravIntelligence]).
 *
 * Rewards follow the ledger rule: supplies, salvage and relics (War-Forging materials);
 * War Effort and Commendations through the war team's existing APIs; no raw-GP piles.
 */
object VarrockPvm {

    // ───────────────────────────── items ─────────────────────────────

    /** Numulite (21555, stackable) renamed "Varrock salvage" — see itemOverrides/unique/varrock_salvage.yml. */
    const val SALVAGE_KEY = "item.varrock_salvage"
    /** Relic part 1 (2373) renamed "Relic of old Varrock". */
    const val RELIC_KEY = "item.varrock_relic"

    // ───────────────────────────── elite undead ─────────────────────────────

    enum class Style { MELEE, MAGIC }

    data class Elite(
        val key: String,
        val name: String,
        val npcKey: String,
        val hp: Int,
        val maxHit: Int,
        val style: Style,
        val attackAnim: Int,
        val blockAnim: Int,
        val deathAnim: Int,
        val projGfx: Int = -1,
        /** Poisons on a landed hit. */
        val poison: Int = 0,
        /** Drains this much prayer on a landed hit. */
        val prayerDrain: Int = 0,
    )

    val ELITES = listOf(
        Elite("fallen_legionnaire", "Fallen Legionnaire", "npc.skeleton_champion", 180, 24, Style.MELEE, 5480, 5482, 5494),
        Elite("bone_warlock", "Bone Warlock", "npc.skeleton_mage", 130, 22, Style.MAGIC, 5480, 5482, 5494, projGfx = 156),
        Elite("plague_zombie", "Plague Zombie", "npc.zombie_49", 160, 18, Style.MELEE, 5571, 5578, 5575, poison = 6),
        Elite("wailing_shade", "Wailing Shade", "npc.ghost_99", 120, 16, Style.MAGIC, 5532, 5535, 5537, projGfx = 100, prayerDrain = 4),
        Elite("drowned_marine", "Drowned Marine", "npc.zombie_pirate_564", 190, 26, Style.MELEE, 5651, 5648, 5658),
        Elite("screaming_banshee", "Screaming Banshee", "npc.banshee_414", 110, 15, Style.MAGIC, 1525, 1523, 1524, projGfx = 100, prayerDrain = 6),
        Elite("grave_knight", "Grave Knight", "npc.skeleton_83", 200, 28, Style.MELEE, 5480, 5482, 5494),
    )

    fun elite(npcId: Int): Elite? = ELITES.firstOrNull { runCatching { org.alter.rscm.RSCM.getRSCM(it.npcKey) }.getOrNull() == npcId }

    /** Elite posts: the hand-walked streets north of the square (palace approach), sampled. */
    val ELITE_POSTS: List<Tile> = CityFrontiers.VARROCK_STREETS.filter { it.z >= 3452 }.filterIndexed { i, _ -> i % 3 == 0 }.take(16)

    /** The elites' common loot — supplies + salvage, relic rare. */
    val ELITE_DROPS = DropTable(
        always = listOf(DropEntry("item.bones", 1, 1)),
        main = listOf(
            DropEntry(SALVAGE_KEY, 1, 3, weight = 30),
            DropEntry("item.death_rune", 10, 25, weight = 10),
            DropEntry("item.blood_rune", 6, 15, weight = 8),
            DropEntry("item.chaos_rune", 20, 40, weight = 8),
            DropEntry("item.grimy_ranarr_weed_noted", 1, 3, weight = 6),
            DropEntry("item.dragon_bones_noted", 1, 3, weight = 5),
            DropEntry("item.shark", 1, 3, weight = 8),
            DropEntry("item.super_restore4", 1, 1, weight = 4),
            DropEntry("item.runite_bar", 1, 1, weight = 3),
            DropEntry("item.adamantite_bar", 1, 3, weight = 6),
            DropEntry("item.coins_995", 500, 2500, weight = 12),
        ),
        rare = listOf(DropEntry(RELIC_KEY, 1, 1, oneInN = 60, log = true)),
    )

    // ───────────────────────────── salvage piles ─────────────────────────────

    /** The one searchable crate def the generic SearchCratesPlugin doesn't already own (it binds 354-358, 366, 1990, 1999, 2064). */
    const val CRATE_A = "object.crate_2071"
    const val CRATE_B = "object.crate_2071"
    const val SALVAGE_MIN = 2
    const val SALVAGE_MAX = 5
    const val RELIC_ONE_IN = 40
    const val AMBUSH_ONE_IN = 8
    const val PILE_RESPAWN_TICKS = 150
    const val PILE_COUNT = 28

    /** Bank pockets stay clean (PvpZones carve-outs). */
    private val NO_PILE = listOf(Area(3178, 3432, 3196, 3453), Area(3250, 3416, 3257, 3424), Area(3140, 3470, 3185, 3515))

    val PILE_TILES: List<Tile> = CityFrontiers.VARROCK_STREETS
        .filter { t -> NO_PILE.none { it.contains(t) } }
        .filterIndexed { i, _ -> i % 4 == 1 }
        .take(PILE_COUNT)

    // ───────────────────────────── Malachai the Hollow ─────────────────────────────

    const val HOLLOW_KEY = "npc.ghoul_champion"
    const val HOLLOW_NAME = "Malachai the Hollow"
    const val HOLLOW_HP = 450
    const val HOLLOW_EVERY_TICKS = 2000
    const val HOLLOW_LINGER_TICKS = 1000

    val HOLLOW_DROPS = DropTable(
        always = listOf(DropEntry(RELIC_KEY, 2, 2, log = true), DropEntry(SALVAGE_KEY, 8, 14)),
        main = listOf(
            DropEntry("item.blood_rune", 40, 80, weight = 8),
            DropEntry("item.death_rune", 60, 120, weight = 8),
            DropEntry("item.dragon_bones_noted", 5, 10, weight = 6),
            DropEntry("item.grimy_torstol_noted", 2, 4, weight = 4),
            DropEntry("item.runite_bar", 2, 4, weight = 5),
            DropEntry("item.super_restore4", 2, 2, weight = 5),
        ),
    )
    const val HOLLOW_TICKETS = 15
    const val HOLLOW_WAR_EFFORT = 20
    const val HOLLOW_COMMENDATIONS = 3

    // ───────────────────────────── The Palace Warden ─────────────────────────────

    const val WARDEN_KEY = "npc.zombies_champion"
    const val WARDEN_NAME = "The Palace Warden"
    const val WARDEN_ADD_KEY = "npc.zombie_49"
    val WARDEN_SPAWN = Tile(3224, 3480, 0)
    const val WARDEN_REGION = 12854
    const val WARDEN_HP = 900
    const val WARDEN_RESPAWN_TICKS = 1500

    val WARDEN_DROPS = DropTable(
        always = listOf(DropEntry(RELIC_KEY, 1, 2, log = true), DropEntry(SALVAGE_KEY, 12, 20), DropEntry("item.dragon_bones", 2, 2)),
        main = listOf(
            DropEntry("item.blood_rune", 80, 160, weight = 8),
            DropEntry("item.death_rune", 100, 200, weight = 8),
            DropEntry("item.soul_rune", 40, 80, weight = 6),
            DropEntry("item.grimy_ranarr_weed_noted", 8, 16, weight = 6),
            DropEntry("item.grimy_torstol_noted", 3, 6, weight = 4),
            DropEntry("item.runite_bar", 3, 6, weight = 5),
            DropEntry("item.dragon_bones_noted", 8, 15, weight = 5),
            DropEntry("item.super_restore4", 3, 3, weight = 5),
            DropEntry("item.saradomin_brew4", 3, 3, weight = 5),
        ),
    )
    const val WARDEN_TICKETS = 30
    const val WARDEN_WAR_EFFORT = 40
    const val WARDEN_COMMENDATIONS = 5
    const val WARDEN_EMBER_ONE_IN = 8

    fun districtName(tile: Tile): String = VarrockDistrict.at(tile)?.display ?: "Fallen Varrock"
}
