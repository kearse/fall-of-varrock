package org.alter.plugins.content.minigames.moons

import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable

/**
 * **Moons of Peril** — LOCKED PLANNED preservation content (design doc 05 §3): the
 * Neypotzli loop built to the OSRS-wiki spec (no Kronos donor — 2024 content). Rev 228's
 * cache carries the dungeon map (Cam Torum → antechamber → three chambers + camps), the three
 * Moons, the blood jaguar, the moon shield, the Lunar Chest, the braziers/stoves/crates and
 * every set piece and weapon.
 *
 * The loop: walk into a chamber doorway → a private copy of the chamber with the Moon at its
 * centre. Every Moon shares the frame: 500 hp, 6-tick **three-strike** melee (4 / 8 / 20 — a
 * miss ends the combo), **Eyatlalli's glyph** rotating clockwise round the room every two
 * standard attacks (stand on it or take rapid damage), and after six standard attacks one of
 * two alternating **specials** (Blood: rain / jaguar; Blue: weapon freeze / frost storm;
 * Eclipse: searing rays / clones). Subduing a Moon teleports you back to the camp side of its
 * door. The **Lunar Chest** (Ancient Shrine) unlocks once all three have been subdued at least
 * once; from then on it pays out for any number subdued in the current run — 1 / 3 / 6 common
 * rolls and, per subdued Moon, a **1/56** shot at one of that Moon's four pieces — then re-locks
 * the run. Re-entering a chamber you have already subdued this run spawns the **enraged** Moon
 * (harder, no extra chest credit).
 *
 * Where rev 228 can't follow the live game (the Moon defs have no animation archives; the
 * gathering/cooking supply skilling loop) the plugin notes the substitute inline.
 */
object Moons {

    enum class Moon(val key: String, val displayName: String, val npcKey: String, val bit: Int, val pieces: List<String>) {
        BLOOD("blood_moon", "Blood Moon", "npc.blood_moon", 1, listOf("item.blood_moon_helm", "item.blood_moon_chestplate", "item.blood_moon_tassets", "item.dual_macuahuitl")),
        BLUE("blue_moon", "Blue Moon", "npc.blue_moon", 2, listOf("item.blue_moon_helm", "item.blue_moon_chestplate", "item.blue_moon_tassets", "item.blue_moon_spear")),
        ECLIPSE("eclipse_moon", "Eclipse Moon", "npc.eclipse_moon", 4, listOf("item.eclipse_moon_helm", "item.eclipse_moon_chestplate", "item.eclipse_moon_tassets", "item.eclipse_atlatl")),
    }

    /**
     * A chamber: its cache region (the instance source), arena centre + radius, the doorway
     * trigger tiles on the shared map, where you appear inside, the doorway tiles inside the
     * copy (walk out to leave) and the shared-map tile you step out to. The `outside` tiles are
     * on PLANE 1 — the hub's walkway level (see [ANTECHAMBER]); the triggers are x/z areas and
     * fire on either plane.
     */
    data class Chamber(
        val moon: Moon,
        val source: Area,
        val centre: Tile,
        val radius: Int,
        val entryTrigger: Area,
        val insideEntry: Tile,
        val exitTrigger: Area,
        val outside: Tile,
    )

    val CHAMBERS = listOf(
        // Blood Moon — region 5526, circular pit centred (1391, 9632), door east (braziers 1411,9629/9635).
        Chamber(
            Moon.BLOOD, Area(1344, 9600, 1407, 9663), Tile(1391, 9632, 0), 9,
            entryTrigger = Area(1406, 9628, 1410, 9636), insideEntry = Tile(1402, 9632, 0),
            exitTrigger = Area(1404, 9628, 1407, 9636), outside = Tile(1412, 9632, 1),
        ),
        // Eclipse Moon — region 6038, pit centred (1495, 9632), door west (braziers 1468,9629/9635).
        Chamber(
            Moon.ECLIPSE, Area(1472, 9600, 1535, 9663), Tile(1495, 9632, 0), 9,
            entryTrigger = Area(1469, 9628, 1473, 9636), insideEntry = Tile(1484, 9632, 0),
            exitTrigger = Area(1472, 9628, 1475, 9636), outside = Tile(1467, 9632, 1),
        ),
        // Blue Moon — region 5783, pit centred (1440, 9681), door south (braziers 1437/1443, 9660; stairs 9664).
        Chamber(
            Moon.BLUE, Area(1408, 9664, 1471, 9727), Tile(1440, 9681, 0), 9,
            entryTrigger = Area(1436, 9661, 1444, 9663), insideEntry = Tile(1440, 9669, 0),
            exitTrigger = Area(1436, 9664, 1444, 9666), outside = Tile(1440, 9658, 1),
        ),
    )

    fun chamber(moon: Moon): Chamber = CHAMBERS.first { it.moon == moon }

    /** Blue Moon's two Frost Storm braziers — the chamber's east and west ends (source coords). */
    val BLUE_BRAZIERS_SRC = listOf(Tile(1428, 9680, 0), Tile(1456, 9680, 0))

    /**
     * Antechamber landing (portal / `::moons`) — PLANE 1, the walkway level (mirror of the
     * `TeleportRegistry` row): the rev-228 map dump shows plane 0 here is a sealed corridor while
     * plane 1 runs north into the monolith hub. The chamber pits stay plane 0 (instanced copies);
     * the Lunar Chest is baked at plane 0 in the Ancient Shrine.
     */
    val ANTECHAMBER = Tile(1440, 9570, 1)
    val CHEST_TILE = Tile(1512, 9579, 0)
    const val CHEST_KEY = "object.lunar_chest"
    const val SUPPLY_CRATES_KEY = "object.supply_crates"
    const val COOKING_STOVE_KEY = "object.cooking_stove"
    const val BLUE_BRAZIER_KEY = "object.blue_moon_brazier"
    const val GLYPH_KEY = "object.moonfire"
    const val JAGUAR_KEY = "npc.blood_jaguar"
    const val SHIELD_KEY = "npc.moon_shield"

    // ───────────────────────────── fight numbers (wiki) ─────────────────────────────

    const val MOON_HP = 500
    const val ATTACK_SPEED = 6
    val STRIKES = intArrayOf(4, 8, 20)
    const val STANDARD_PER_SPECIAL = 6
    const val GLYPH_ROTATE_EVERY = 2
    const val GLYPH_RADIUS = 6
    const val OFF_GLYPH_DAMAGE = 3
    const val ENRAGED_MULT = 1.5

    // ───────────────────────────── run state ─────────────────────────────

    /** `s=<subdued bitmask this run>;u=<1 once every Moon has ever been subdued>` */
    val RUN_ATTR = AttributeKey<String>("moons_run")
    val CHESTS_ATTR = AttributeKey<Int>(persistenceKey = "moons_chests")

    class Run(var subdued: Int, var unlocked: Boolean) {
        fun has(m: Moon) = subdued and m.bit != 0
        fun count() = Integer.bitCount(subdued)
        fun encode() = "s=$subdued;u=${if (unlocked) 1 else 0}"

        companion object {
            fun decode(s: String?): Run {
                if (s.isNullOrBlank()) return Run(0, false)
                val kv = s.split(";").mapNotNull { p -> p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it) to p.substring(it + 1).toIntOrNull() } }.toMap()
                return Run(kv["s"] ?: 0, (kv["u"] ?: 0) == 1)
            }
        }
    }

    fun run(p: Player): Run = Run.decode(p.attr[RUN_ATTR])
    fun save(p: Player, r: Run) { p.attr[RUN_ATTR] = r.encode() }

    // ───────────────────────────── Lunar Chest ─────────────────────────────

    const val UNIQUE_ONE_IN = 56
    fun commonRolls(subdued: Int) = when (subdued) { 0 -> 0; 1 -> 1; 2 -> 3; else -> 6 }

    /** The wiki's common table (weights out of 30). */
    val COMMON = DropTable(
        main = listOf(
            DropEntry("item.atlatl_dart", 72, 120, weight = 5),
            DropEntry("item.blessed_bone_shards", 160, 179, weight = 2),
            DropEntry("item.wyrmling_bones_noted", 42, 54, weight = 1),
            DropEntry("item.sunkissed_bones_noted", 6, 12, weight = 3),
            DropEntry("item.swamp_tar", 79, 119, weight = 4),
            DropEntry("item.water_orb_noted", 30, 45, weight = 2),
            DropEntry("item.supercompost_noted", 6, 12, weight = 3),
            DropEntry("item.soft_clay_noted", 15, 25, weight = 3),
            DropEntry("item.grimy_harralander_noted", 12, 18, weight = 3),
            DropEntry("item.grimy_irit_leaf_noted", 12, 18, weight = 1),
            DropEntry("item.maple_seed", 1, 2, weight = 2),
            DropEntry("item.yew_seed", 1, 1, weight = 1),
        ),
    )

    // ───────────────────────────── camp supplies (v1 substitute) ─────────────────────────────

    /** Supply crates hand out a fixed camp kit every [CRATE_COOLDOWN_TICKS] (the gathering loop is v2). */
    const val CRATE_COOLDOWN_TICKS = 300
    const val CRATE_BREAM = 8
    val CRATE_ATTR = AttributeKey<Int>()
}
