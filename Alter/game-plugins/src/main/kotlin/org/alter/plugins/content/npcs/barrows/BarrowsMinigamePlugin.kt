package org.alter.plugins.content.npcs.barrows

import dev.openrune.cache.CacheManager.getObject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.Plugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable
import org.alter.rscm.RSCM.getRSCM
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

/**
 * **Barrows minigame — the crypt run (Phase B2).**
 *
 * Real-cache geography (decoded via the map-dump tool, region 14231): six **crypts** on
 * level 3 — each a sarcophagus + a staircase back up to its overworld mound — and the
 * **tunnel chest chamber** on level 0 (the reward chest 20973, ringed by four torches).
 *
 * The run, matching OSRS:
 *  1. **Dig** a mound (a spade-use on the mound tile — delegated here from `SpadePlugin`).
 *  2. Descend into that brother's crypt; **search the sarcophagus** to make him climb out
 *     and fight (combat + set effects live in [BarrowsCombatPlugin]). Climb the staircase
 *     to leave. Each kill raises your **reward potential**.
 *  3. One crypt (random per run) is the **tunnel** — searching *its* sarcophagus drops you
 *     into the tunnels by the chest instead of spawning the brother.
 *  4. **Open the chest** to loot (scaled by brothers slain); the final, un-slain brother
 *     **ambushes** you. Slaying him (or having cleared all six) lets you escape.
 *  5. **Prayer drains** the whole time you're underground.
 *
 * **B2 scope note:** the full random **tunnel-maze door puzzle** (navigating the 20730 doors
 * room-to-room) is deferred to B2b — for now searching the tunnel sarcophagus places you in
 * the chest chamber directly. Everything else is the real OSRS loop on real coordinates.
 */
class BarrowsMinigamePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        // Prayer-drain heartbeat while anyone is in the Barrows underground.
        val drain = TimerKey()
        onWorldInit { world.timers[drain] = PRAYER_DRAIN_TICKS }
        onTimer(drain) { drainPrayer(world); world.timers[drain] = PRAYER_DRAIN_TICKS }

        // Per-crypt object hooks: search the sarcophagus, climb the staircase out.
        CRYPTS.forEachIndexed { idx, c ->
            bindObj(c.sarcophagusId, listOf("Search", "Open")) { search(player, idx) }
            bindObj(c.staircaseId, listOf("Climb-up", "Climb up", "Climb")) { climbOut(player, idx) }
        }
        // The reward chest.
        bindObj(CHEST_ID, listOf("Open", "Search")) { openChest(player) }

        // Credit brother kills toward the killer's run (one death handler per brother id).
        CRYPTS.map { it.brotherKey }.distinct().forEach { key ->
            runCatching {
                onNpcDeath(key) {
                    val killer = npc.attr[KILLER_ATTR]?.get() as? Player ?: return@onNpcDeath
                    brotherKilled(killer, npc.id)
                }
            }.onFailure { logger.error(it) { "Barrows: failed onNpcDeath bind for $key" } }
        }

        onLogout { runs.remove(player) }

        onCommand("barrows", description = "Teleport to the Barrows mounds") {
            player.moveTo(SURFACE_EXIT)
            player.message("You arrive at the Barrows mounds. Dig a mound with a spade to enter a crypt.")
        }
    }

    /**
     * Bind the first of [options] that the object actually exposes. `onObjOption` throws if the
     * option is absent (and lowercases internally, so case here is irrelevant); we try each and
     * keep the first that sticks — robust to whichever label the cache uses.
     */
    private fun bindObj(id: Int, options: List<String>, logic: Plugin.() -> Unit) {
        // Prefer a known-good label.
        for (opt in options) {
            if (runCatching { onObjOption(obj = id, option = opt, logic = logic) }.isSuccess) return
        }
        // Fall back to whatever option the cache object actually exposes (e.g. the chest 20973
        // isn't "Open"/"Search" — bind its first real action instead of leaving it unclickable).
        val actions = runCatching { getObject(id).actions }.getOrNull()
            ?.filterNotNull()?.filter { it.isNotBlank() }.orEmpty()
        for (opt in actions) {
            if (runCatching { onObjOption(obj = id, option = opt, logic = logic) }.isSuccess) {
                logger.info { "Barrows: bound object $id via its cache option '$opt'." }
                return
            }
        }
        logger.warn { "Barrows: object $id has no bindable option (tried $options; cache actions=$actions)." }
    }

    companion object {
        const val DIG_ANIM = 830
        const val MOUND_RADIUS = 2
        const val PRAYER_DRAIN_TICKS = 5 // ~3s between prayer drips underground
        const val CHEST_ID = 20973
        const val BARROWS_PIECE_ONE_IN = 6 // per reward roll, chance at a brother's equipment
        const val DRAGON_MED_ONE_IN = 8

        val TUNNEL_ENTRY = Tile(3548, 9695, 0) // inside the chest chamber, west of the chest
        val CHEST_AMBUSH_TILE = Tile(3553, 9695, 0) // east of the chest
        val SURFACE_EXIT = Tile(3565, 3306, 0) // open ground just north of the mounds

        /** A single crypt: its brother, overworld dig spot, underground tiles, and its objects. */
        data class Crypt(
            val brotherKey: String,
            val displayName: String,
            val moundCenter: Tile,   // overworld dig spot (level 0)
            val landing: Tile,       // where you arrive in the crypt (level 3)
            val brotherSpawn: Tile,  // where the brother climbs out (level 3)
            val sarcophagusId: Int,
            val staircaseId: Int,
        )

        // Coordinates from the region-14231 map dump; crypt↔brother pairing is consistent (it
        // doesn't affect gameplay which OSRS brother historically owned which crypt).
        val CRYPTS = listOf(
            Crypt("npc.ahrim_the_blighted", "Ahrim the Blighted", Tile(3565, 3289, 0), Tile(3548, 9686, 3), Tile(3552, 9683, 3), sarcophagusId = 20771, staircaseId = 20670),
            Crypt("npc.dharok_the_wretched", "Dharok the Wretched", Tile(3576, 3298, 0), Tile(3565, 9686, 3), Tile(3570, 9684, 3), sarcophagusId = 20721, staircaseId = 20671),
            Crypt("npc.guthan_the_infested", "Guthan the Infested", Tile(3577, 3282, 0), Tile(3558, 9701, 3), Tile(3556, 9697, 3), sarcophagusId = 20770, staircaseId = 20667),
            Crypt("npc.karil_the_tainted", "Karil the Tainted", Tile(3565, 3275, 0), Tile(3536, 9704, 3), Tile(3540, 9703, 3), sarcophagusId = 20722, staircaseId = 20669),
            Crypt("npc.torag_the_corrupted", "Torag the Corrupted", Tile(3552, 3283, 0), Tile(3576, 9704, 3), Tile(3572, 9706, 3), sarcophagusId = 20772, staircaseId = 20672),
            Crypt("npc.verac_the_defiled", "Verac the Defiled", Tile(3557, 3297, 0), Tile(3556, 9716, 3), Tile(3552, 9714, 3), sarcophagusId = 20720, staircaseId = 20668),
        )

        /** The chest's common-loot table; rolled once per point of reward potential. */
        val CHEST_TABLE = DropTable(
            main = listOf(
                DropEntry("item.coins_995", 5_000, 20_000, weight = 30),
                DropEntry("item.mind_rune", 200, 700, weight = 20),
                DropEntry("item.chaos_rune", 100, 300, weight = 18),
                DropEntry("item.death_rune", 50, 200, weight = 14),
                DropEntry("item.blood_rune", 50, 150, weight = 10),
                DropEntry("item.bolt_rack", 35, 75, weight = 20),
            ),
            rare = listOf(
                DropEntry("item.dragon_med_helm", 1, 1, oneInN = DRAGON_MED_ONE_IN, announce = true, log = true),
            ),
        )

        /** The 24 brothers' equipment pieces — the iconic chest uniques (missing keys are skipped). */
        val BARROWS_PIECES = listOf(
            "item.ahrims_hood", "item.ahrims_robetop", "item.ahrims_robeskirt", "item.ahrims_staff",
            "item.dharoks_helm", "item.dharoks_platebody", "item.dharoks_platelegs", "item.dharoks_greataxe",
            "item.guthans_helm", "item.guthans_platebody", "item.guthans_chainskirt", "item.guthans_warspear",
            "item.karils_coif", "item.karils_leathertop", "item.karils_leatherskirt", "item.karils_crossbow",
            "item.torags_helm", "item.torags_platebody", "item.torags_platelegs", "item.torags_hammers",
            "item.veracs_helm", "item.veracs_brassard", "item.veracs_plateskirt", "item.veracs_flail",
        )

        /** One player's in-progress run (in-memory; resets on relog — acceptable for v1). */
        class Run(val tunnelCrypt: Int) {
            val killed = BooleanArray(CRYPTS.size)
            var chestOpened = false
            fun potential() = killed.count { it }
        }

        private val runs = HashMap<Player, Run>()
        private val spawnedBrothers = HashMap<Int, Npc>() // crypt index -> live brother

        /**
         * Called from `SpadePlugin`: if the player is digging on a mound, take them into the
         * crypt and return true (handled); otherwise false (normal dig logic continues).
         */
        fun tryDig(player: Player): Boolean {
            if (player.tile.height != 0) return false
            val idx = CRYPTS.indexOfFirst { onMound(player, it) }
            if (idx < 0) return false
            val crypt = CRYPTS[idx]
            val world = player.world
            runs.getOrPut(player) { Run(world.random(CRYPTS.size - 1)) }

            player.queue {
                player.animate(DIG_ANIM)
                wait(2)
                player.moveTo(crypt.landing)
                player.message("You dig your way into ${crypt.displayName}'s crypt...")
            }
            return true
        }

        private fun onMound(player: Player, c: Crypt): Boolean {
            val t = player.tile
            return abs(t.x - c.moundCenter.x) <= MOUND_RADIUS && abs(t.z - c.moundCenter.z) <= MOUND_RADIUS
        }

        fun search(player: Player, idx: Int) {
            val run = runs[player]
            if (run == null) {
                player.message("You search the sarcophagus, but nothing happens.")
                return
            }
            val crypt = CRYPTS[idx]
            val world = player.world

            if (idx == run.tunnelCrypt) {
                player.message("<col=8f0000>You've found a tunnel! You lower yourself into the darkness...</col>")
                player.moveTo(TUNNEL_ENTRY)
                return
            }
            if (run.killed[idx]) {
                player.message("You have already defeated ${crypt.displayName}.")
                return
            }
            val existing = spawnedBrothers[idx]
            if (existing != null && existing.index >= 0 && !existing.isDead() && world.npcs.contains(existing)) {
                player.message("${crypt.displayName} is already free!")
                return
            }
            spawnBrother(world, crypt, idx, crypt.brotherSpawn)
            player.message("<col=8f0000>${crypt.displayName} bursts from the sarcophagus!</col>")
        }

        fun climbOut(player: Player, idx: Int) {
            player.moveTo(CRYPTS[idx].moundCenter)
            player.message("You climb the staircase back to the surface.")
        }

        fun openChest(player: Player) {
            val run = runs[player]
            if (run == null || run.chestOpened) {
                player.message("You search the chest, but it is bare.")
                return
            }
            run.chestOpened = true
            awardChest(player, run.potential())

            val tIdx = run.tunnelCrypt
            if (!run.killed[tIdx]) {
                val crypt = CRYPTS[tIdx]
                spawnBrother(player.world, crypt, tIdx, CHEST_AMBUSH_TILE)
                player.message("<col=ff0000>${crypt.displayName} emerges from the shadows to ambush you!</col>")
            } else {
                finishRun(player) // all six already slain → free exit
            }
        }

        fun brotherKilled(killer: Player, npcId: Int) {
            val idx = CRYPTS.indexOfFirst { runCatching { getRSCM(it.brotherKey) }.getOrNull() == npcId }
            if (idx < 0) return
            spawnedBrothers.remove(idx)
            val run = runs[killer] ?: return
            run.killed[idx] = true
            killer.message("<col=007f00>You have defeated ${CRYPTS[idx].displayName}.</col>")
            if (idx == run.tunnelCrypt && run.chestOpened) finishRun(killer)
        }

        private fun spawnBrother(world: World, crypt: Crypt, idx: Int, tile: Tile) {
            val npc = Npc(getRSCM(crypt.brotherKey), tile, world)
            npc.respawns = false
            world.spawn(npc)
            npc.setActive(true)
            spawnedBrothers[idx] = npc
        }

        private fun finishRun(player: Player) {
            runs.remove(player)
            player.moveTo(SURFACE_EXIT)
            player.message("<col=8f0000>You escape the Barrows crypts to the surface.</col>")
        }

        private fun awardChest(player: Player, potential: Int) {
            val world = player.world
            // Guaranteed coin pile, scaled by reward potential.
            drop(player, "item.coins_995", 3_000 + potential * 4_000 + world.random(3_000))
            // One roll of the table per point of potential (+1 base), each with a shot at a unique.
            repeat(1 + potential) {
                CHEST_TABLE.roll(world).forEach { drop(player, it.item, it.amount) }
                if (world.chance(1, BARROWS_PIECE_ONE_IN)) {
                    BARROWS_PIECES.getOrNull(world.random(BARROWS_PIECES.size - 1))?.let { drop(player, it, 1) }
                }
            }
            player.message("<col=ffae00>You loot the Barrows chest.</col> (reward potential: $potential)")
        }

        private fun drop(player: Player, key: String, amount: Int) {
            if (amount <= 0) return
            val id = runCatching { getRSCM(key) }.getOrNull() ?: return
            player.world.spawn(GroundItem(id, amount, player.tile, player))
        }

        private fun drainPrayer(world: World) {
            world.players.forEach { p ->
                val t = p.tile
                if (t.x in 3520..3583 && t.z in 9664..9727) {
                    if (p.getSkills().getCurrentLevel(Skills.PRAYER) > 0) {
                        p.getSkills().alterCurrentLevel(Skills.PRAYER, -1)
                    }
                }
            }
        }
    }
}
