package org.alter.plugins.content.minigames.lms

import dev.openrune.cache.CacheManager.getNpc
import dev.openrune.cache.CacheManager.getObject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.model.queue.QueueTask
import org.alter.game.model.shop.PurchasePolicy
import org.alter.game.model.shop.ShopItem
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.PointsCurrency
import org.alter.plugins.content.economy.points
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Last Man Standing** — the original OSRS battle royale (NOT the PK Training Arena sparring ground).
 *
 * Talk to **Lisa** at the LMS lobby (or `::lms`) to queue. When the lobby fills — real queuers topped up
 * with bot competitors so a game runs even solo — the field is teleported onto an instanced copy of the
 * real LMS island, stripped to a starter kit, and set loose to scavenge crates and fight while a fog
 * closes in. Last one alive banks LMS points, spent at Lisa's reward shop.
 *
 * The engine lives in [LmsGame]; this plugin owns the host NPC, the tick timer, and the click/death/login
 * hooks. Cache bindings are guarded (WizardTower pattern) so a stale cache degrades gracefully instead of
 * dropping the plugin at boot.
 */
class LmsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val host = "npc.lisa"
    private val hostTile = Tile(3235, 3222, 0)
    private val lobbyTile = Tile(3235, 3225, 0)
    private val hostRegion = 12850 // Lumbridge region (also force-loaded by the war plugin)
    private val mapRegions = intArrayOf(13658, 13659) // the LMS island source regions
    private val timer = TimerKey()

    private val rewardShop = "Last Man Standing Rewards"

    init {
        LmsGame.lobbyTile = lobbyTile
        buildRewardShop()

        onWorldInit {
            LmsGame.world = world
            runCatching { world.definitions.loadRegions(world, world.chunks, intArrayOf(hostRegion) + mapRegions) }
                .onFailure { logger.warn { "lms: region force-load failed: ${it.message}" } }
            // Spawn the host DIRECTLY (the spawnNpc queue drains before onWorldInit — Fight Cave lesson).
            runCatching {
                val n = Npc(getRSCM(host), hostTile, world)
                n.walkRadius = 0
                n.lastFacingDirection = Direction.SOUTH
                world.spawn(n)
                n.setActive(true)
                logger.info { "lms: host '$host' spawned at $hostTile (index=${n.index})" }
            }.onFailure { logger.warn { "lms: host '$host' not spawned: ${it.message}" } }
            // Kill-switch: no engine tick while LMS is closed — no lobby countdown, no games, no fog.
            if (LmsGame.TEMPORARILY_DISABLED) {
                logger.info { "lms: TEMPORARILY DISABLED — new games blocked (gear recovery, shop and kit builder stay live)" }
            } else {
                world.timers[timer] = 1
            }
        }
        onTimer(timer) {
            LmsGame.tickAll()
            world.timers[timer] = 1
        }

        bindHost()
        bindCrate()

        onCommand("lms", description = "Teleport to the Last Man Standing lobby") {
            player.moveTo(lobbyTile)
            if (LmsGame.TEMPORARILY_DISABLED) {
                player.message("<col=801700>Last Man Standing is temporarily closed for maintenance.</col> Lisa's rewards and kit builder are still open.")
            } else {
                player.message("<col=8f00ff>Talk to Lisa</col> to enter Last Man Standing, or spend your LMS points at her rewards.")
            }
        }

        onPlayerPreDeath { LmsGame.onPreDeath(player) }
        onPlayerDeath { LmsGame.onDeath(player) }
        onLogin { LmsGame.onLogin(player) }
        onLogout { LmsGame.onLogout(player) }
    }

    // ───────────────────────────── host dialogue ─────────────────────────────

    private fun bindHost() {
        val acts = runCatching { getNpc(getRSCM(host)).actions.filterNotNull().filter { it.isNotBlank() } }
            .getOrDefault(emptyList())
        acts.filter { it.equals("talk-to", true) || it.equals("talk", true) }.forEach { act ->
            onNpcOption(host, option = act) { player.queue { hostDialog(player) } }
        }
        // Trade/Shop/Rewards open the points store DIRECTLY — no dialogue hop (storefront redesign).
        acts.filter { it.equals("trade", true) || it.equals("shop", true) || it.equals("rewards", true) }
            .forEach { act -> onNpcOption(host, option = act) { player.openShop(rewardShop) } }
        if (acts.none { it.equals("talk-to", true) || it.equals("talk", true) }) {
            logger.warn { "lms: host '$host' has no Talk-to (actions=$acts) — use ::lms + the shop stays reachable via dialogue-less entry." }
        }
    }

    private suspend fun QueueTask.hostDialog(p: Player) {
        val id = runCatching { getRSCM(host) }.getOrDefault(-1)
        chatNpc(p, "Welcome to Last Man Standing. Everything you own stays behind — you fight in the kit you've built with me, and scavenge the island's chests for upgrades. Last one breathing wins.", npc = id, title = "Lisa")
        when (options(p,
            "Join a game.",
            "Build my kit.",
            "Show me the rewards. (${p.points(PointKind.LMS)} pts)",
            "How does this work?",
            "Not now.",
        )) {
            1 -> {
                if (LmsGame.TEMPORARILY_DISABLED) {
                    chatNpc(p, "I'm afraid the island is closed while we deal with some... irregularities. Your kit and points are perfectly safe — check back soon.", npc = id, title = "Lisa")
                } else {
                    // Joining wipes gear for the island — never with the kit editor still up.
                    org.alter.plugins.content.kits.KitEditor.close(p)
                    LmsGame.join(p)
                }
            }
            2 -> openKitEditor(p)
            3 -> p.openShop(rewardShop)
            4 -> {
                chatNpc(p, "You'll spawn on the island wearing the kit you've built with me — everyone fights at the same combat stats, so it's all skill. The village chests hold weapons, armour, food and runes to upgrade with.", npc = id, title = "Lisa")
                chatNpc(p, "A fog closes in over time and burns anyone caught outside the safe zone, so keep moving toward the middle. Win to earn LMS points, and spend them here.", npc = id, title = "Lisa")
                chatNpc(p, "Short on players? Don't worry — I'll fill the field with competitors so there's always a fight.", npc = id, title = "Lisa")
            }
            5 -> chatPlayer(p, "Maybe later.")
        }
    }

    /** The OSRS-style loadout builder — the kit editor in LMS mode: the palette tabs are the five
     *  [LmsKits] categories, a click picks that bundle (persisted instantly), and the doll +
     *  inventory preview exactly what [LmsKits.apply] will deal out at game start. */
    private fun openKitEditor(p: Player) {
        if (org.alter.plugins.content.kits.KitEditor.isOpen(p)) return
        org.alter.plugins.content.kits.KitEditor.open(p, org.alter.plugins.content.kits.KitEditor.Mode.LMS)
    }

    // ───────────────────────────── loot crates ─────────────────────────────

    /** Bind the island's loot crate. We discover the object's real "open"-style action from the cache
     *  (like WizardTower's knight menu) and bind whatever exists, guarded so a mismatch can't drop boot. */
    private fun bindCrate() {
        val crateId = runCatching { getRSCM(LmsGame.CRATE_KEY) }.getOrNull() ?: LmsGame.CRATE_OBJ
        val actions = runCatching { getObject(crateId).actions.filterNotNull().filter { it.isNotBlank() } }
            .getOrDefault(emptyList())
        val openAct = actions.firstOrNull { it.lowercase() in OPEN_ACTIONS }
        if (openAct == null) {
            logger.warn { "lms: crate object $crateId has no open/search action (actions=$actions) — crates won't be lootable!" }
            return
        }
        runCatching {
            onObjOption(crateId, option = openAct) {
                LmsGame.onCrateOpen(player, player.getInteractingGameObj().tile)
            }
        }.onFailure { logger.warn { "lms: crate bind ($crateId,'$openAct') skipped: ${it.message}" } }
    }

    // ───────────────────────────── reward shop ─────────────────────────────

    /** Sell-only reward exchange paid in [PointKind.LMS] points (OSRS-style: points, not a traded item).
     *  Stock is resolved defensively — an unknown cache key is skipped, never fatal. Prices in LMS points. */
    private fun buildRewardShop() {
        val stock = REWARDS.mapNotNull { (key, amt, price) ->
            resolveOrNull(key)?.let { ShopItem(it, amt, price) }
        }
        createShop(rewardShop, PointsCurrency(PointKind.LMS), purchasePolicy = PurchasePolicy.BUY_NONE, stockSize = maxOf(stock.size, 1)) {
            stock.forEachIndexed { i, item -> items[i] = item }
        }
    }

    private fun resolveOrNull(key: String): Int? = runCatching { getRSCM(key) }.getOrNull()

    private companion object {
        private val OPEN_ACTIONS = setOf("open", "search", "loot", "ransack", "open-chest")

        /** (rscm key, stock amount, LMS-point price). Authentic LMS-flavoured wares; bad keys are skipped. */
        private val REWARDS: List<Triple<String, Int, Int>> = listOf(
            // Consumables (cheap resupply)
            Triple("item.saradomin_brew4", 1000, 2),
            Triple("item.super_restore4", 1000, 2),
            Triple("item.cooked_karambwan", 5000, 1),
            Triple("item.super_combat_potion4", 1000, 4),
            // NOTE (shop-economy-redesign §4): tradeable gear (gmaul/claws/AGS/occult/msb)
            // removed — the LMS crates keep the gear RNG, and the PvP gear chase lives at
            // PK Rewards (Blood Money). The point shop is consumables + cosmetics only.
            // Cosmetics (the classic LMS halos)
            Triple("item.saradomin_halo", Int.MAX_VALUE, 500),
            Triple("item.zamorak_halo", Int.MAX_VALUE, 500),
            Triple("item.guthix_halo", Int.MAX_VALUE, 500),
        )
    }
}
