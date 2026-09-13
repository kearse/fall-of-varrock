package org.alter.plugins.content.minigames.magearena

import org.alter.game.model.Tile
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player

/**
 * **The Mage Arena** — Kolodion's trial, the god capes, and the Mage Bank.
 *
 * Three community reports from 2026-09-13 collapse into this one piece of content:
 *  - *"unable to get into the mage bank"* — the `mage_bank` teleport row was a COMING_SOON
 *    placeholder in `TeleportRegistry`, so the portal listed a destination it refused to send
 *    anyone to. It is now a real landing ([BANK]).
 *  - *"missing god capes"* — the plain god capes existed only as **50 gp cosmetics** on the
 *    Lumbridge stylist's cape rack. They are combat capes, so they moved here and the rack
 *    dropped them.
 *  - *"and god cape 2"* — the imbued (+magic) capes were in the cache and completely
 *    unobtainable. They are the Mage Arena II reward.
 *
 * **The loop.** Talk to Kolodion at the Mage Bank with [MAGIC_REQ] Magic. He sends you into the
 * arena against his five forms in sequence ([FORMS]); each one dies into the next, and felling
 * the last completes **Mage Arena I**. Back at the bank, the three god statues let you claim ONE
 * god's cape — the choice is permanent ([GOD_ATTR]), exactly as in OSRS.
 *
 * **Mage Arena II** is a pilgrimage rather than a fight. OSRS asks you to cast your god's spell at
 * three hidden wilderness locations; this server has no god spells in any spellbook, so the trial
 * keeps the shape (a deep-Wilderness journey, wearing the cape, at real risk) and drops the spell:
 * visit all three [SHRINES] wearing your god cape, then return to Kolodion for the imbue. The
 * shrines sit at 3 separate deep-wild sites so the trip is the content.
 *
 * Everything is persisted per account — the trial is a one-time unlock, not a repeatable grind.
 */
object MageArena {

    // ───────────────────────────── requirement ─────────────────────────────

    /** OSRS's Mage Arena requirement. Checked on the base level, like every other gate here. */
    const val MAGIC_REQ = 60

    // ───────────────────────────── places ─────────────────────────────

    /** The Mage Bank floor — safe (well outside `Wilderness.SURFACE`), banked, Kolodion's post. */
    val BANK = Tile(2539, 4716, 0)

    /** Where Kolodion stands, a few tiles west of the bank booth. */
    val KOLODION_TILE = Tile(2536, 4716, 0)

    /** The bank booth spawned on the Mage Bank floor. */
    val BANK_BOOTH_TILE = Tile(2541, 4716, 0)

    /** Where a challenger is put down inside the arena cage, and where each form spawns. */
    val ARENA_ENTRY = Tile(2539, 4710, 0)
    val ARENA_SPAWN = Tile(2539, 4706, 0)

    // ───────────────────────────── the gods ─────────────────────────────

    /**
     * The three alignments. [statue] is the cache statue object clicked at the bank to claim the
     * plain cape; [cape] and [imbued] are the Mage Arena I and II rewards.
     */
    enum class God(
        val display: String,
        val statue: String,
        val cape: String,
        val imbued: String,
        val shrineName: String,
        val shrine: Tile,
    ) {
        SARADOMIN(
            "Saradomin", "object.statue_of_saradomin_2873",
            "item.saradomin_cape", "item.imbued_saradomin_cape",
            "the Dark Warriors' Fortress", Tile(3036, 3629, 0),
        ),
        GUTHIX(
            "Guthix", "object.statue_of_guthix",
            "item.guthix_cape", "item.imbued_guthix_cape",
            "the Graveyard of Shadows", Tile(3166, 3679, 0),
        ),
        ZAMORAK(
            "Zamorak", "object.statue_of_zamorak_2874",
            "item.zamorak_cape", "item.imbued_zamorak_cape",
            "the Demonic Ruins", Tile(3295, 3885, 0),
        ),
        ;

        companion object {
            fun byName(name: String?): God? = values().firstOrNull { it.name == name }
        }
    }

    /**
     * The three Mage Arena II shrines, one per god, spread across separate deep-Wilderness sites so
     * the pilgrimage is a genuine trip rather than three clicks in one clearing. Each is a statue of
     * that god standing at a landmark [PvpZones][org.alter.plugins.content.combat.PvpZones] already
     * knows as multi-combat — you are exposed at every one of them, which is the point.
     *
     * A player visits ALL THREE regardless of which cape they wear: the trial is the journey, not
     * the alignment. TUNE the stand-on tiles in-game.
     */
    val SHRINES: List<God> = God.values().toList()

    // ───────────────────────────── the fight ─────────────────────────────

    /**
     * Kolodion's forms, fought back to back in this order. The ids are the cache's own ladder
     * (1605 → 1609); combat defs are registered by [MageArenaConfigsPlugin] rather than taken from
     * the world dump, because none of these npcs has an ambient spawn and so
     * `WorldSpawnsPlugin` never builds a def for them — an unregistered form would fight with the
     * DEFAULT 10 hp human def.
     */
    val FORMS = listOf(
        "npc.kolodion_1605",
        "npc.kolodion_1606",
        "npc.kolodion_1607",
        "npc.kolodion_1608",
        "npc.kolodion_1609",
    )

    const val KOLODION = "npc.kolodion"

    // ───────────────────────────── state ─────────────────────────────

    /** Set once the fifth form falls. Gates the statues. */
    val ARENA_I_ATTR = AttributeKey<Boolean>(persistenceKey = "mage_arena_i")

    /** The [God] name the player took a cape from — permanent, so a second statue is refused. */
    val GOD_ATTR = AttributeKey<String>(persistenceKey = "mage_arena_god")

    /** Bitmask of visited [SHRINES] ordinals; 0b111 means the pilgrimage is done. */
    val SHRINES_ATTR = AttributeKey<Int>(persistenceKey = "mage_arena_shrines")

    /** Set once Kolodion has imbued the cape. Gates a second imbue. */
    val ARENA_II_ATTR = AttributeKey<Boolean>(persistenceKey = "mage_arena_ii")

    /** Which form index the player is currently fighting (-1 = not in the arena). */
    val FORM_ATTR = AttributeKey<Int>(persistenceKey = "mage_arena_form")

    // ───────────────────────────── helpers ─────────────────────────────

    fun hasArenaI(p: Player): Boolean = p.attr[ARENA_I_ATTR] == true

    fun hasArenaII(p: Player): Boolean = p.attr[ARENA_II_ATTR] == true

    fun godOf(p: Player): God? = God.byName(p.attr[GOD_ATTR])

    fun visited(p: Player, god: God): Boolean = ((p.attr[SHRINES_ATTR] ?: 0) shr god.ordinal) and 1 == 1

    /** Record a shrine visit. Returns true if this was a NEW one. */
    fun markVisited(p: Player, god: God): Boolean {
        val before = p.attr[SHRINES_ATTR] ?: 0
        val after = before or (1 shl god.ordinal)
        if (after == before) return false
        p.attr[SHRINES_ATTR] = after
        return true
    }

    fun pilgrimageDone(p: Player): Boolean = (p.attr[SHRINES_ATTR] ?: 0) == (1 shl SHRINES.size) - 1

    fun shrinesLeft(p: Player): List<God> = SHRINES.filterNot { visited(p, it) }
}
