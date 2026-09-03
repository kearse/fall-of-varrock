package org.alter.plugins.content.pvm.senntisten

import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.attr.AttributeKey
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable
import org.alter.plugins.content.pvm.varrock.VarrockPvm

/**
 * **Senntisten Expeditions** — repeatable PvM exploration beneath the Digsite (story doc 02 §12:
 * "The Last Adventurer → Senntisten Expeditions: repeatable PvM exploration, relics, materials,
 * logs and future boss access"; handoff §2: the buried Zarosian conduits east of Varrock).
 * FoV-original.
 *
 * Rev 228's cache has the Zarosian temple under the Digsite (region 13722: a western corridor
 * with the ladder, a gated altar hall, an eastern wing). An expedition is a **private copy** of
 * it entered by **operating a Digsite winch**: three waves of the temple's wardens push you from
 * the corridor into the hall, then **the Custodian of Senntisten** — a bound Zarosian demon —
 * holds the altar. Rewards are relics, salvage, an **expedition log** page, Commendations and
 * War Effort; nothing is raw GP. **Deep Senntisten** (the great dungeon beyond, regions
 * 13466/13721, harder wardens, rare rooms, original bosses) is the next tier behind
 * *Beneath the Fallen Empire*.
 */
object Senntisten {

    const val QUEST_FLAG = "quest.the_last_adventurer.done"
    const val ENFORCE_QUEST = false

    // ───────────────────────────── geography (region 13722) ─────────────────────────────

    val SOURCE = Area(3392, 9856, 3455, 9919)
    val ENTRY_SRC = Tile(3404, 9892, 0)
    val CORRIDOR_SRC = Tile(3405, 9888, 0)
    val HALL_SRC = Tile(3422, 9891, 0)
    val ALTAR_SRC = Tile(3423, 9886, 0)

    /** The Digsite winches on the surface — Operate to descend. */
    val WINCH_KEYS = listOf("object.winch_2351", "object.winch_2350")
    val SURFACE_EXIT = Tile(3369, 3426, 0)
    val LANDING = Tile(3368, 3426, 0)

    const val RUN_TICKS = 1200
    const val WAVE_GAP_TICKS = 6

    // ───────────────────────────── the wardens ─────────────────────────────

    enum class Style { MELEE, MAGIC }

    data class Warden(
        val key: String, val name: String, val npcKey: String, val hp: Int, val maxHit: Int, val style: Style,
        val attackAnim: Int, val blockAnim: Int, val deathAnim: Int, val projGfx: Int = -1, val prayerDrain: Int = 0,
    )

    val SENTINEL = Warden("sentinel", "Sentinel of Senntisten", "npc.skeleton_80", 150, 20, Style.MELEE, 5480, 5482, 5494)
    val LEGIONNAIRE = Warden("buried_legionnaire", "Buried Legionnaire", "npc.zombie_55", 170, 24, Style.MELEE, 5571, 5578, 5575)
    val ECHO = Warden("echo", "Echo of the Fall", "npc.ghost_95", 120, 18, Style.MAGIC, 5532, 5535, 5537, projGfx = 100, prayerDrain = 3)
    val WARLOCK = Warden("conduit_warlock", "Conduit Warlock", "npc.skeleton_82", 130, 22, Style.MAGIC, 5480, 5482, 5494, projGfx = 156)
    val WARDENS = listOf(SENTINEL, LEGIONNAIRE, ECHO, WARLOCK)

    fun warden(npcId: Int): Warden? = WARDENS.firstOrNull { runCatching { org.alter.rscm.RSCM.getRSCM(it.npcKey) }.getOrNull() == npcId }

    /** Wave rosters (spawned around the wave's anchor). */
    data class Wave(val anchor: Tile, val roster: List<Warden>)

    val WAVES = listOf(
        Wave(CORRIDOR_SRC, listOf(SENTINEL, SENTINEL, LEGIONNAIRE)),
        Wave(HALL_SRC, listOf(SENTINEL, LEGIONNAIRE, LEGIONNAIRE, ECHO, ECHO)),
        Wave(HALL_SRC, listOf(LEGIONNAIRE, LEGIONNAIRE, SENTINEL, ECHO, WARLOCK, WARLOCK)),
    )

    // ───────────────────────────── the Custodian ─────────────────────────────

    const val CUSTODIAN_KEY = "npc.lesser_demon_champion"
    const val CUSTODIAN_NAME = "The Custodian of Senntisten"
    const val CUSTODIAN_HP = 700

    // ───────────────────────────── rewards ─────────────────────────────

    const val LOG_KEY = "item.expedition_log"
    const val SALVAGE_PER_WAVE_MIN = 2
    const val SALVAGE_PER_WAVE_MAX = 4

    val CUSTODIAN_DROPS = DropTable(
        always = listOf(
            DropEntry(VarrockPvm.RELIC_KEY, 2, 2, log = true),
            DropEntry(VarrockPvm.SALVAGE_KEY, 10, 10),
            DropEntry(LOG_KEY, 1, 1, log = true),
        ),
        main = listOf(
            DropEntry("item.blood_rune", 60, 120, weight = 8),
            DropEntry("item.soul_rune", 30, 60, weight = 6),
            DropEntry("item.death_rune", 80, 160, weight = 8),
            DropEntry("item.grimy_torstol_noted", 2, 5, weight = 4),
            DropEntry("item.runite_bar", 2, 5, weight = 5),
            DropEntry("item.dragon_bones_noted", 6, 12, weight = 5),
            DropEntry("item.super_restore4", 2, 3, weight = 5),
        ),
    )
    const val CUSTODIAN_WAR_EFFORT = 30
    const val CUSTODIAN_COMMENDATIONS = 4

    val RUNS_ATTR = AttributeKey<Int>(persistenceKey = "senntisten_runs")
}
