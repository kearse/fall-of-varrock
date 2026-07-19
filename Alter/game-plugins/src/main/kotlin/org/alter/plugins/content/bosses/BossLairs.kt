package org.alter.plugins.content.bosses

/**
 * The catalog of **boss lairs** — one entry per real-OSRS-coordinate boss arena.
 *
 * It exists to solve two recurring problems for far-flung lairs (see the war-troop freeze,
 * `rsps-war-collision-loading`):
 *  1. **Collision is only built where a player stands.** A boss that roams (wilderness
 *     bosses, Zulrah's snakelings, Vet'ion's hounds) or that we want pathable before the
 *     first player arrives needs its lair region's collision map **force-loaded at boot**.
 *  2. **Multi-way combat** must be flagged per region (GWD, the Wilderness bosses, Corp).
 *
 * [BossLairsPlugin] reads this list at world-init: it force-loads every [Lair.box]'s regions
 * and flags the [Lair.multi] ones as multi-combat. **Adding a lair = adding a line here.**
 *
 * Boxes are `[baseX, baseZ, width, height]` in world tiles, sized to comfortably cover the
 * arena + the boss's roam radius. Coordinates marked TUNE want an in-game check.
 */
object BossLairs {

    data class Lair(
        val key: String,
        /** `[baseX, baseZ, width, height]` world-tile box covering the arena + roam radius. */
        val box: IntArray,
        /** Whether the lair is multi-way combat (flag its regions). */
        val multi: Boolean = false,
    )

    val all: List<Lair> = listOf(
        // KBD already sets its own multi region (9033) in KbdConfigsPlugin; listed here so the
        // collision force-load covers the whole lair (harmless / idempotent).
        Lair("kbd", intArrayOf(2240, 4672, 64, 64), multi = true),
        // Barrows: the overworld mounds + the underground crypts/tunnels (region 14231, the
        // crypt brothers spawn on level 3 when their sarcophagus is searched — force-load so
        // they're pathable). Single-way.
        Lair("barrows_mounds", intArrayOf(3540, 3260, 64, 64)),
        Lair("barrows_crypts", intArrayOf(3520, 9664, 64, 64)),

        // ── Wilderness bosses (Phase C) — all multi-way; force-load so they path before a
        //    player arrives (they roam). Boxes cover lair + roam radius. Coords ↔ WildernessBosses.
        Lair("callisto", intArrayOf(3276, 3816, 48, 48), multi = true),
        Lair("vetion", intArrayOf(3215, 3755, 48, 48), multi = true),
        Lair("venenatis", intArrayOf(3291, 3719, 48, 48), multi = true),
        Lair("scorpia", intArrayOf(3208, 10313, 48, 48), multi = true),
        Lair("chaos_elemental", intArrayOf(3255, 3892, 48, 48), multi = true),
        Lair("chaos_fanatic", intArrayOf(2954, 3827, 48, 48), multi = true),
        Lair("crazy_archaeologist", intArrayOf(2956, 3666, 48, 48), multi = true),

        // ── Slayer / PvM bosses (Phase D) — force-load lair collision; multi where the OSRS
        //    lair is multi-way. Coords ↔ PvmBosses. (Corp Beast omitted — handled by CorpBeastPlugin.)
        Lair("kraken", intArrayOf(2256, 9990, 48, 48)),
        Lair("cerberus", intArrayOf(1288, 1228, 48, 48)),
        Lair("giant_mole", intArrayOf(1736, 5140, 48, 48)),
        Lair("kalphite_queen", intArrayOf(3484, 9470, 48, 48), multi = true),
        Lair("sarachnis", intArrayOf(1900, 9898, 48, 48)),
        Lair("smoke_devil", intArrayOf(2360, 9428, 48, 48)),
        Lair("demonic_gorilla", intArrayOf(2394, 9750, 48, 48), multi = true),
        Lair("skotizo", intArrayOf(1697, 10066, 48, 48), multi = true),
        Lair("dagannoth_kings", intArrayOf(2876, 4426, 48, 48), multi = true),

        // ── God Wars Dungeon (Phase F) — the four throne rooms (level 2), all multi-way.
        //    Coords ↔ GodWarsBosses. (Killcount door deferred.)
        Lair("gwd_bandos", intArrayOf(2848, 5344, 48, 48), multi = true),
        Lair("gwd_zamorak", intArrayOf(2912, 5312, 48, 48), multi = true),
        Lair("gwd_armadyl", intArrayOf(2824, 5280, 48, 48), multi = true),
        // Saradomin encampment sits at z≈5285-5309 / x≈2884-2905 (cache-verified) — the old
        // 2896,5248 box missed the room entirely, so its collision was never force-loaded.
        Lair("gwd_saradomin", intArrayOf(2880, 5280, 48, 48), multi = true),

        // ── Elite / endgame bosses (G-tier) — force-load; multi where the OSRS lair is. Coords ↔ EliteBosses.
        Lair("vorkath", intArrayOf(2256, 4032, 48, 48)),
        Lair("alchemical_hydra", intArrayOf(1344, 10208, 48, 48)),
        Lair("phantom_muspah", intArrayOf(2096, 5624, 48, 48)),
        Lair("abyssal_sire", intArrayOf(2944, 4360, 48, 48), multi = true),
        Lair("nex", intArrayOf(2904, 5184, 48, 48), multi = true),
        Lair("grotesque_guardians", intArrayOf(3392, 3520, 48, 48), multi = true),

        // ── Revenant Caves (wilderness multi-NPC cave) — multi-way; force-load the interior.
        Lair("revenant_caves", intArrayOf(3200, 10112, 64, 64), multi = true),
    )
}
