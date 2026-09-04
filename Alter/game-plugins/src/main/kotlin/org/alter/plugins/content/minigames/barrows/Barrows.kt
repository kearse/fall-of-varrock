package org.alter.plugins.content.minigames.barrows

import dev.openrune.cache.CacheManager.getItem
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.combat.CombatStyle
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.plugins.content.bosses.BossKills
import org.alter.plugins.content.bosses.CollectionLog
import org.alter.plugins.content.combat.getCombatTarget
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Barrows** — the classic Morytania crypt run, ported from Kronos rev-184
 * (`activities/barrows/Barrows.java`, `BarrowsBrother.java`, `BarrowsRewards.java` + the six
 * `brothers/<Name>.java` fight scripts) with the reward chest brought up to the OSRS-wiki rules
 * the donor simplified. Classic-boss rule: recognisable loop, recognisable brothers, OSRS
 * loot identity — nothing FoV-flavoured.
 *
 * The loop:
 *  1. **Dig** on a mound (spade, delegated from `SpadePlugin`) → drop into that brother's
 *     crypt on plane 3. Digging starts a run if none is active; a run persists across relogs.
 *  2. **Search the sarcophagus** → the brother climbs out beside you and attacks
 *     ("You dare disturb my rest!"). Each run has one random **tunnel crypt** whose
 *     sarcophagus is the way down instead — but only once the other five brothers are
 *     slain (operator rule; OSRS lets you descend early). Stairs climb back to the mound.
 *  3. **Tunnels** (plane 0, the chest chamber) hold the crypt vermin; their kills add to
 *     your reward potential like the brothers do.
 *  4. **Open the chest** → the un-slain sixth brother ambushes you; open it again to loot.
 *     Loot uses the wiki mechanics: `1 + brothers` rolls (max 7), each roll a
 *     `1/(450 − 58·brothers)` shot at one of the 24 pieces, otherwise a draw of
 *     `1..potential` over the coin/rune/rack/key/helm bands. Reward potential =
 *     Σ combat levels of everything you killed (cap 1000) + 2 per brother (max 1012).
 *  5. **Prayer drains** the whole time you are underground.
 *
 * Shared world, per-player brothers: a brother belongs to whoever searched the sarcophagus,
 * leashes to them, and is removed when they leave the crypt or log out. The tunnel-door maze
 * is deferred (v2) — the tunnel sarcophagus drops you straight into the chest chamber, as
 * the donor does.
 */
object Barrows {

    // ───────────────────────────── geography ─────────────────────────────

    /** Every crypt and the tunnels: region 14231, all planes. */
    val CRYPT_BOUNDS = Area(3520, 9664, 3583, 9727)
    const val CRYPT_REGION = 14231

    /** Open ground just north of the mounds (portal landing / escape tile). */
    val SURFACE_LANDING = Tile(3565, 3306, 0)

    /** Donor tunnel drop-in (Kronos `teleport(3552, 9690, 0)`), south of the chest. */
    val TUNNEL_ENTRY = Tile(3552, 9690, 0)

    /** East of the chest — where the sixth brother rises. */
    val CHEST_AMBUSH = Tile(3553, 9695, 0)

    /**
     * The reward chest is a multi-loc: the map places base 20973 (nameless, no actions) and varbit
     * [CHEST_VARBIT] picks the child the client draws — 0 → closed chest 20723 [Open], 1 → open
     * chest 20724 [Search, Close]. The engine resolves clicks through `GameObject.getTransform`
     * BEFORE dispatch, so handlers must be bound on the CHILD ids: a binding on the base never
     * fires ("Nothing interesting happens" on the chest, player report 2026-09-03).
     */
    const val CHEST_BASE_KEY = "object.null_20973"
    const val CHEST_CLOSED_KEY = "object.chest_20723"
    const val CHEST_OPEN_KEY = "object.chest_20724"
    const val CHEST_VARBIT = 1394
    const val DIG_RADIUS = 3 // Kronos Spade.registerDig(Bounds(x, y, 0, 3))
    const val DIG_ANIM = 830
    const val PRAYER_DRAIN_TICKS = 5
    const val LEASH_TICKS = 5
    const val LEASH_RADIUS = 14

    // ───────────────────────────── reward maths ─────────────────────────────

    const val LEVEL_CAP = 1000
    const val MAX_ROLLS = 7
    const val PIECE_BASE = 450
    const val PIECE_PER_BROTHER = 58
    const val CLUE_BASE = 200
    const val CLUE_PER_BROTHER = 29
    const val CLUE_FLOOR = 29

    // ───────────────────────────── the brothers ─────────────────────────────

    /**
     * Cache facts from the donor: npc ids 1672-1677, dig spots, crypt landings (plane 3),
     * stairs and sarcophagus object ids (`Barrows.register(...)`), and the per-brother
     * combat numbers from `data/npcs/combat/<Name>.json` + `brothers/<Name>.java`.
     */
    enum class Brother(
        val key: String,
        val displayName: String,
        val npcKey: String,
        val level: Int,
        val maxHit: Int,
        val attackSpeed: Int,
        val attackAnim: Int,
        val blockAnim: Int,
        val style: CombatStyle,
        val range: Int,
        val dig: Tile,
        val landing: Tile,
        val stairsId: Int,
        val sarcophagusId: Int,
        val pieces: List<String>,
    ) {
        AHRIM(
            "ahrim", "Ahrim the Blighted", "npc.ahrim_the_blighted", 98, 25, 5, 727, -1, CombatStyle.MAGIC, 8,
            Tile(3565, 3289, 0), Tile(3557, 9703, 3), 20667, 20770,
            listOf("item.ahrims_hood", "item.ahrims_robetop", "item.ahrims_robeskirt", "item.ahrims_staff"),
        ),
        DHAROK(
            "dharok", "Dharok the Wretched", "npc.dharok_the_wretched", 115, 29, 6, 2067, 2079, CombatStyle.SLASH, 1,
            Tile(3576, 3298, 0), Tile(3556, 9718, 3), 20668, 20720,
            listOf("item.dharoks_helm", "item.dharoks_platebody", "item.dharoks_platelegs", "item.dharoks_greataxe"),
        ),
        GUTHAN(
            "guthan", "Guthan the Infested", "npc.guthan_the_infested", 115, 24, 5, 2080, 2079, CombatStyle.STAB, 1,
            Tile(3578, 3283, 0), Tile(3534, 9704, 3), 20669, 20722,
            listOf("item.guthans_helm", "item.guthans_platebody", "item.guthans_chainskirt", "item.guthans_warspear"),
        ),
        KARIL(
            "karil", "Karil the Tainted", "npc.karil_the_tainted", 98, 20, 4, 2075, 424, CombatStyle.RANGED, 8,
            Tile(3566, 3276, 0), Tile(3546, 9684, 3), 20670, 20771,
            listOf("item.karils_coif", "item.karils_leathertop", "item.karils_leatherskirt", "item.karils_crossbow"),
        ),
        TORAG(
            "torag", "Torag the Corrupted", "npc.torag_the_corrupted", 115, 24, 5, 2068, 2063, CombatStyle.CRUSH, 1,
            Tile(3554, 3283, 0), Tile(3568, 9683, 3), 20671, 20721,
            listOf("item.torags_helm", "item.torags_platebody", "item.torags_platelegs", "item.torags_hammers"),
        ),
        VERAC(
            "verac", "Verac the Defiled", "npc.verac_the_defiled", 115, 23, 5, 2062, 2063, CombatStyle.CRUSH, 1,
            Tile(3557, 3298, 0), Tile(3578, 9706, 3), 20672, 20772,
            listOf("item.veracs_helm", "item.veracs_brassard", "item.veracs_plateskirt", "item.veracs_flail"),
        );

        val bit: Int get() = 1 shl ordinal

        val npcId: Int by lazy { getRSCM(npcKey) }

        companion object {
            fun byNpcId(id: Int): Brother? = values().firstOrNull { it.npcId == id }
        }
    }

    /** The 24 chest uniques, in Collection Log order. */
    val PIECES: List<String> = Brother.values().flatMap { it.pieces }

    // ───────────────────────────── tunnel vermin ─────────────────────────────

    /** Crypt monsters (ids 1678-1685 have no ambient spawn rows — ours to place). Levels are OSRS. */
    data class TunnelMonster(val npcKey: String, val level: Int, val hp: Int, val maxHit: Int, val attackSpeed: Int, val spawn: Tile)

    val TUNNEL_MONSTERS = listOf(
        TunnelMonster("npc.crypt_rat", 43, 25, 3, 4, Tile(3548, 9691, 0)),
        TunnelMonster("npc.crypt_rat", 43, 25, 3, 4, Tile(3556, 9691, 0)),
        TunnelMonster("npc.bloodworm", 52, 25, 4, 4, Tile(3547, 9697, 0)),
        TunnelMonster("npc.crypt_spider", 56, 30, 4, 4, Tile(3557, 9697, 0)),
        TunnelMonster("npc.giant_crypt_rat", 76, 40, 6, 4, Tile(3549, 9700, 0)),
        TunnelMonster("npc.skeleton_1685", 77, 70, 7, 4, Tile(3555, 9700, 0)),
        TunnelMonster("npc.giant_crypt_spider", 79, 60, 7, 4, Tile(3552, 9686, 0)),
    )

    private val tunnelLevelById: Map<Int, Int> by lazy {
        TUNNEL_MONSTERS.mapNotNull { m -> runCatching { getRSCM(m.npcKey) }.getOrNull()?.let { it to m.level } }.toMap()
    }

    // ───────────────────────────── run state ─────────────────────────────

    /** `t=<tunnel ordinal>;k=<killed bitmask>;l=<level sum>;c=<chest opened 0/1>` — absent = no run. */
    val RUN_ATTR = AttributeKey<String>("barrows_run")
    val CHESTS_ATTR = AttributeKey<Int>(persistenceKey = "barrows_chests")

    class Run(var tunnel: Int, var killedMask: Int, var levelSum: Int, var chestOpened: Boolean) {
        fun killed(b: Brother) = killedMask and b.bit != 0
        fun brothersKilled() = Integer.bitCount(killedMask)
        fun potential() = minOf(levelSum, LEVEL_CAP) + 2 * brothersKilled()
        val tunnelBrother: Brother get() = Brother.values()[tunnel]

        fun encode() = "t=$tunnel;k=$killedMask;l=$levelSum;c=${if (chestOpened) 1 else 0}"

        companion object {
            fun decode(s: String?): Run? {
                if (s.isNullOrBlank()) return null
                val kv = s.split(";").mapNotNull { part ->
                    val i = part.indexOf('=')
                    if (i <= 0) null else part.substring(0, i) to part.substring(i + 1).toIntOrNull()
                }.toMap()
                val t = kv["t"] ?: return null
                if (t !in Brother.values().indices) return null
                return Run(t, kv["k"] ?: 0, kv["l"] ?: 0, (kv["c"] ?: 0) == 1)
            }
        }
    }

    fun run(p: Player): Run? = Run.decode(p.attr[RUN_ATTR])

    private fun save(p: Player, run: Run) {
        p.attr[RUN_ATTR] = run.encode()
    }

    private fun clear(p: Player) {
        p.attr.remove(RUN_ATTR)
    }

    private fun startRun(p: Player): Run {
        val run = Run(p.world.random(Brother.values().size - 1), 0, 0, false)
        save(p, run)
        return run
    }

    /** Live brothers per owner (transient). */
    private val live = HashMap<Player, MutableMap<Brother, Npc>>()

    // ───────────────────────────── actions ─────────────────────────────

    /**
     * Called by `SpadePlugin` before its own dig logic: on a mound → descend into that
     * brother's crypt and return true (handled). Anywhere else → false.
     */
    fun tryDig(p: Player): Boolean {
        if (p.tile.height != 0) return false
        val brother = Brother.values().firstOrNull { p.tile.isWithinRadius(it.dig, DIG_RADIUS) } ?: return false
        if (run(p) == null) startRun(p)
        p.queue {
            p.animate(DIG_ANIM)
            wait(2)
            p.moveTo(brother.landing)
            p.message("You've broken into a crypt!")
        }
        return true
    }

    fun search(p: Player, b: Brother) {
        val run = run(p) ?: startRun(p) // walked in some other way — a run starts here
        if (b == run.tunnelBrother) {
            // House rule (operator, 2026-09-03): the tunnel only opens once every OTHER brother
            // is down — the chest comes after the whole run, not after the first lucky dig.
            // (OSRS lets you drop in at any time and scales the loot; we gate instead.) The
            // tunnel brother himself still ambushes at the chest.
            val resting = Brother.values().filter { it != b && !run.killed(it) }
            if (resting.isNotEmpty()) {
                p.message("<col=801700>You've found a hidden tunnel, but the crypt's wards hold it shut.</col>")
                p.message("${resting.size} of the brothers still rest undisturbed — deal with them first.")
                return
            }
            p.message("<col=801700>You've found a hidden tunnel! You lower yourself into the darkness...</col>")
            despawnAll(p)
            p.moveTo(TUNNEL_ENTRY)
            return
        }
        if (run.killed(b)) {
            p.message("This sarcophagus appears to be empty.")
            return
        }
        val existing = live[p]?.get(b)
        if (existing != null && !existing.isDead() && existing.index >= 0) {
            p.message("${b.displayName} is already free!")
            return
        }
        if (p.getCombatTarget() != null) {
            p.message("You are under attack!")
            return
        }
        val at = p.world.snapToWalkable(Tile(p.tile.x + 1, p.tile.z + 1, p.tile.height), maxRadius = 4)
        spawnBrother(p, b, at)
    }

    fun climbOut(p: Player, b: Brother) {
        despawnAll(p)
        p.moveTo(b.dig)
        p.message("You climb the staircase back to the surface.")
    }

    /** The chest: first click wakes the sixth brother (if he still lives), the next click loots. */
    fun chest(p: Player) {
        val run = run(p)
        if (run == null) {
            p.message("The chest is sealed. Only those who have disturbed the brothers may open it.")
            return
        }
        // A relog mid-run resets varbits: re-show the open chest if the run already opened it.
        if (run.chestOpened && p.getVarbit(CHEST_VARBIT) == 0) p.setVarbit(CHEST_VARBIT, 1)
        val sixth = run.tunnelBrother
        if (!run.chestOpened) {
            run.chestOpened = true
            save(p, run)
            p.animate(535)
            p.setVarbit(CHEST_VARBIT, 1) // swap the loc to the open chest (Search/Close)
            if (!run.killed(sixth)) {
                p.message("<col=ff0000>${sixth.displayName} emerges from the shadows behind you!</col>")
                spawnBrother(p, sixth, p.world.snapToWalkable(CHEST_AMBUSH, maxRadius = 4))
            } else {
                p.message("The chest creaks open. Search it again to claim your reward.")
            }
            return
        }
        loot(p, run)
    }

    /** "Close" on the open chest — cosmetic; the run's opened flag stays (the sixth brother is up). */
    fun closeChest(p: Player) {
        p.animate(535)
        p.setVarbit(CHEST_VARBIT, 0)
    }

    fun brotherKilled(killer: Player, b: Brother) {
        live[killer]?.remove(b)
        val run = run(killer) ?: return
        if (run.killed(b)) return
        run.killedMask = run.killedMask or b.bit
        run.levelSum += b.level
        save(killer, run)
        BossKills.record(killer, b.key)
        killer.message("<col=4f9b4f>You have defeated ${b.displayName}.</col> Reward potential: ${run.potential()}.")
    }

    /** Tunnel vermin kills feed reward potential (their combat level) — mirrors the wiki rule. */
    fun tunnelKill(killer: Player, npcId: Int) {
        val level = tunnelLevelById[npcId] ?: return
        val run = run(killer) ?: return
        if (run.chestOpened) return
        run.levelSum += level
        save(killer, run)
    }

    /** Remove every brother belonging to [p] (leaving the crypt, tunnel drop, logout). */
    fun despawnAll(p: Player) {
        val mine = live.remove(p) ?: return
        mine.values.forEach { n -> if (!n.isDead() && n.index >= 0) p.world.remove(n) }
    }

    /** Underground the crypts sap your prayer: one point per heartbeat for everyone below. */
    fun drainPrayer(world: World) {
        world.players.forEach { p ->
            if (CRYPT_BOUNDS.contains(p.tile) && p.getSkills().getCurrentLevel(Skills.PRAYER) > 0) {
                p.getSkills().alterCurrentLevel(Skills.PRAYER, -1)
            }
        }
    }

    // ───────────────────────────── internals ─────────────────────────────

    private fun spawnBrother(owner: Player, b: Brother, at: Tile) {
        val world = owner.world
        val npc = Npc(b.npcId, at, world)
        npc.respawns = false
        world.spawn(npc)
        npc.setActive(true)
        npc.forceChat("You dare disturb my rest!")
        npc.attack(owner)
        live.getOrPut(owner) { java.util.EnumMap<Brother, Npc>(Brother::class.java) }[b] = npc

        // Leash: the brother is the owner's problem alone. Gone when the owner leaves the
        // crypt (or logs out); re-engages the owner if the fight drops.
        world.queue {
            while (!npc.isDead() && npc.index >= 0) {
                wait(LEASH_TICKS)
                val online = world.getPlayerForUid(owner.uid) != null
                if (!online || !npc.tile.isWithinRadius(owner.tile, LEASH_RADIUS)) {
                    if (!npc.isDead() && npc.index >= 0) world.remove(npc)
                    live[owner]?.remove(b)
                    break
                }
                if (npc.getCombatTarget() == null && !npc.isDead()) npc.attack(owner)
            }
        }
    }

    private fun loot(p: Player, run: Run) {
        val world = p.world
        val brothers = run.brothersKilled()
        val potential = run.potential().coerceAtLeast(1)
        val rolls = minOf(MAX_ROLLS, 1 + brothers)
        var pieces = 0

        p.animate(535)
        repeat(rolls) {
            if (brothers > 0 && world.chance(1, PIECE_BASE - PIECE_PER_BROTHER * brothers)) {
                val piece = PIECES[world.random(PIECES.size - 1)]
                give(p, piece, 1)
                pieces++
                val id = runCatching { getRSCM(piece) }.getOrNull()
                if (id != null) {
                    val name = getItem(id).name
                    world.players.forEach {
                        it.message("<col=ff0000>News: ${p.username} just received <col=ffae00>$name</col> from the Barrows chest!</col>")
                    }
                    if (CollectionLog.record(p, id)) p.message("<col=ffae00>New Collection Log slot: $name!</col>")
                }
            } else {
                val r = 1 + world.random(potential - 1) // 1..potential
                when {
                    r <= 380 -> give(p, "item.coins_995", lerp(r, 1, 380, 2, 774))
                    r <= 505 -> give(p, "item.mind_rune", lerp(r, 381, 505, 253, 336))
                    r <= 630 -> give(p, "item.chaos_rune", lerp(r, 506, 630, 112, 139))
                    r <= 755 -> give(p, "item.death_rune", lerp(r, 631, 755, 70, 83))
                    r <= 880 -> give(p, "item.blood_rune", lerp(r, 756, 880, 37, 43))
                    r <= 1005 -> give(p, "item.bolt_rack", lerp(r, 881, 1005, 35, 40))
                    r <= 1008 -> give(p, "item.tooth_half_of_key", 1)
                    r <= 1011 -> give(p, "item.loop_half_of_key", 1)
                    else -> {
                        give(p, "item.dragon_med_helm", 1)
                        world.players.forEach {
                            it.message("<col=ff0000>News: ${p.username} just received a <col=ffae00>Dragon med helm</col> from the Barrows chest!</col>")
                        }
                    }
                }
            }
        }
        if (world.chance(1, maxOf(CLUE_FLOOR, CLUE_BASE - CLUE_PER_BROTHER * brothers))) {
            give(p, "item.clue_scroll_elite", 1)
        }

        val chests = (p.attr[CHESTS_ATTR] ?: 0) + 1
        p.attr[CHESTS_ATTR] = chests
        BossKills.record(p, "barrows")
        p.message("<col=801700>You loot the Barrows chest.</col> Reward potential $potential, $rolls rolls" +
            (if (pieces > 0) ", $pieces Barrows piece${if (pieces > 1) "s" else ""}" else "") +
            ".")
        p.message("Your Barrows chest count is: <col=ff0000>$chests</col>.")

        clear(p)
        despawnAll(p)
        p.setVarbit(CHEST_VARBIT, 0)
        // The crypt has given up its treasure — the way out is up.
        p.moveTo(SURFACE_LANDING)
        p.message("The tunnels shudder and you scramble back to the surface.")
    }

    /** Linear band quantity: the wiki lists min..max per band; scale across the band's roll range. */
    private fun lerp(r: Int, lo: Int, hi: Int, qMin: Int, qMax: Int): Int {
        if (hi <= lo) return qMin
        return qMin + ((qMax - qMin).toLong() * (r - lo) / (hi - lo)).toInt()
    }

    private fun give(p: Player, key: String, amount: Int) {
        if (amount <= 0) return
        val id = runCatching { getRSCM(key) }.getOrNull()
        if (id == null) {
            logger.warn { "Barrows: unknown item key $key" }
            return
        }
        val added = p.inventory.add(item = id, amount = amount, assureFullInsertion = false)
        val leftover = amount - added.completed
        if (leftover > 0) p.world.spawn(GroundItem(id, leftover, p.tile, p))
    }
}
