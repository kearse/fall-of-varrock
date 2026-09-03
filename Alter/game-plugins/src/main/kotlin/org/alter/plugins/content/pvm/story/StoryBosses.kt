package org.alter.plugins.content.pvm.story

import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable
import org.alter.plugins.content.mechanics.Flags
import org.alter.plugins.content.pvm.varrock.VarrockPvm
import org.alter.plugins.content.war.Title
import org.alter.plugins.content.war.title

/**
 * The two **repeatable story bosses** (story doc 02 §12; PvM doc 05 §2): **Zemouregal** after
 * *Defender of Varrock* ("player + Arrav fight Zemouregal … repeatable Zemouregal boss, elite
 * Arrav Intelligence, materials, cosmetics, chase unique(s)") and **the Convergence** after
 * *The Fracture* ("deepest current Senntisten, repeatable boss, highest-tier materials,
 * status/title. Fracture persists"). FoV-original.
 *
 * Both are private instances entered through **Arrav** in the fallen palace's west hall (or
 * `::zemouregal` / `::convergence`). Quest flags are wired but not enforced until Team 3 lands
 * the quests; Knight rank stands in meanwhile.
 */
object StoryBosses {

    const val ZEMOUREGAL_FLAG = "quest.defender_of_varrock.done"
    const val FRACTURE_FLAG = "quest.the_fracture.done"
    const val ENFORCE_QUEST = false
    val MIN_TITLE = Title.KNIGHT

    fun mayFace(p: Player, flag: String): Boolean =
        Flags.has(p, flag) || (!ENFORCE_QUEST && p.title.ordinal >= MIN_TITLE.ordinal)

    /** Arrav, freed, waits in the fallen palace's west hall (shared world). */
    const val ARRAV_HUB_KEY = "npc.arrav_14129"
    val ARRAV_HUB = Tile(3213, 3479, 0)
    val HUB_LANDING = Tile(3212, 3478, 0)

    const val RUN_TICKS = 1500

    // ───────────────────────────── Zemouregal (fallen palace, region 12854) ─────────────────────────────

    const val ZEMOUREGAL_KEY = "npc.zemouregal"
    const val ZEMOUREGAL_NAME = "Zemouregal"
    const val ZEMOUREGAL_HP = 1200
    val PALACE_SOURCE = Area(3200, 3456, 3263, 3519)
    val PALACE_ENTRY = Tile(3212, 3478, 0)
    val PALACE_BOSS = Tile(3212, 3486, 0)
    val PALACE_ARRAV = Tile(3214, 3480, 0)

    /** Arrav the fighter — the Defender-of-Varrock ally model (no options). */
    const val ARRAV_ALLY_KEY = "npc.arrav"
    const val ARRAV_ALLY_HP = 600

    /** Zemouregal's risen — the temple wardens' ids (handler-owned, so no generic loot). */
    const val RISEN_KEY = "npc.zombie_55"

    val ZEMOUREGAL_DROPS = DropTable(
        always = listOf(
            DropEntry(VarrockPvm.RELIC_KEY, 3, 3),
            DropEntry(VarrockPvm.SALVAGE_KEY, 15, 15),
        ),
        main = listOf(
            DropEntry("item.blood_rune", 100, 200, weight = 8),
            DropEntry("item.death_rune", 100, 200, weight = 8),
            DropEntry("item.soul_rune", 50, 100, weight = 6),
            DropEntry("item.runite_bar", 4, 8, weight = 5),
            DropEntry("item.dragon_bones_noted", 10, 20, weight = 5),
            DropEntry("item.grimy_torstol_noted", 3, 6, weight = 4),
            DropEntry("item.super_restore4", 3, 4, weight = 5),
            DropEntry("item.saradomin_brew4", 2, 3, weight = 4),
        ),
        rare = listOf(
            DropEntry("item.arravs_axe", 1, 1, oneInN = 150, announce = true, log = true),
            DropEntry("item.mahjarrat_notes_aj", 1, 1, oneInN = 40, log = true),
            DropEntry("item.mahjarrat_notes_kz", 1, 1, oneInN = 40, log = true),
        ),
    )
    const val ZEMOUREGAL_WAR_EFFORT = 50
    const val ZEMOUREGAL_COMMENDATIONS = 6
    const val ZEMOUREGAL_EMBER_ONE_IN = 5

    // ───────────────────────────── the Convergence (Digsite dungeon, region 13465) ─────────────────────────────

    const val CONVERGENCE_KEY = "npc.the_nightmare_9425"
    const val CONVERGENCE_NAME = "The Convergence"
    const val CONVERGENCE_HP = 1500
    val DUNGEON_SOURCE = Area(3328, 9792, 3391, 9855)
    val DUNGEON_ENTRY = Tile(3368, 9809, 0)
    val DUNGEON_BOSS = Tile(3376, 9814, 0)
    val DUNGEON_EXIT = Tile(3369, 3426, 0)

    /** The Convergence's echoes — the expedition ghost (handler-owned, no generic loot). */
    const val ECHO_KEY = "npc.ghost_95"

    val CONVERGENCE_DROPS = DropTable(
        always = listOf(
            DropEntry(VarrockPvm.RELIC_KEY, 4, 4),
            DropEntry(VarrockPvm.SALVAGE_KEY, 20, 20),
        ),
        main = listOf(
            DropEntry("item.blood_rune", 150, 250, weight = 8),
            DropEntry("item.soul_rune", 80, 140, weight = 8),
            DropEntry("item.wrath_rune", 40, 80, weight = 5),
            DropEntry("item.runite_bar", 6, 10, weight = 5),
            DropEntry("item.dragon_bones_noted", 15, 25, weight = 5),
            DropEntry("item.grimy_torstol_noted", 4, 8, weight = 4),
            DropEntry("item.super_restore4", 3, 5, weight = 5),
            DropEntry("item.saradomin_brew4", 3, 4, weight = 4),
        ),
    )
    const val CONVERGENCE_WAR_EFFORT = 80
    const val CONVERGENCE_COMMENDATIONS = 10
    const val CONVERGENCE_EMBERS = 1

    val ZEMOUREGAL_KILLS = AttributeKey<Int>(persistenceKey = "zemouregal_kills")
    val CONVERGENCE_KILLS = AttributeKey<Int>(persistenceKey = "convergence_kills")
}
