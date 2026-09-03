package org.alter.plugins.content.minigames.gotr

import dev.openrune.cache.CacheManager
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.TileGraphic
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.attr.SAFE_DEATH_ATTR
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.entity.GameObject
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.CollectionLog
import org.alter.plugins.content.combat.SafeDeaths
import org.alter.plugins.content.economy.SpecialShopGuard
import org.alter.plugins.content.interfaces.bank.openBank
import org.alter.plugins.content.skills.GatheringTools
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * The Temple of the Eye: lobby, round loop, mining / workbench / cells / altars / Great Guardian,
 * the barrier line the creatures batter, scoring and the two reward npcs. See [Gotr] and
 * `docs/pvm/minigames-b-spec.md`.
 */
class GotrPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private class Barrier(var cell: Gotr.Cell?, var hp: Int, var broken: Boolean, var obj: GameObject?)

    private class Game(val startedAt: Int) {
        val players = LinkedHashSet<Player>()
        var power = 0.0
        var elementalOpen: Gotr.Altar? = null
        var catalyticOpen: Gotr.Altar? = null
        var portalsCloseAt = 0
        var nextPortalsAt = Gotr.FIRST_PORTALS
        var nextHugeAt = Gotr.HUGE_PORTAL_INTERVAL
        var hugeCloseAt = 0
        var hugePortal: DynamicObject? = null
        var shocked = false
        var ended = false
        val creatures = mutableListOf<Npc>()
        var lastReport = 0
    }

    private var game: Game? = null
    private var lobbyCountdown = -1
    private val barriers = Gotr.CELL_TILES.map { Barrier(null, 0, false, null) }

    // per-player, per-game
    private val ELEM = AttributeKey<Int>()
    private val CAT = AttributeKey<Int>()
    private val MINED = AttributeKey<Int>()

    init {
        SafeDeaths.register(Gotr.TEMPLE)
        SpecialShopGuard.register(Gotr.SHOP.mapNotNull { runCatching { getRSCM(it.key) }.getOrNull() })

        onWorldInit {
            runCatching {
                world.definitions.loadRegions(world, world.chunks, intArrayOf(Gotr.REGION))
                barriers.forEachIndexed { i, b -> setSlot(i, b, Gotr.INACTIVE_TILE_ID) }
                listOf(Gotr.REWARDS_GUARDIAN_KEY to Gotr.REWARDS_GUARDIAN_TILE, Gotr.FELIX_KEY to Gotr.FELIX_TILE).forEach { (key, tile) ->
                    val n = Npc(getRSCM(key), world.snapToWalkable(tile, maxRadius = 2), world)
                    n.respawns = true
                    n.walkRadius = 0
                    world.spawn(n)
                }
                val gg = Npc(getRSCM(Gotr.GREAT_GUARDIAN_KEY), Gotr.GUARDIAN_TILE, world)
                gg.respawns = true
                gg.walkRadius = 0
                world.spawn(gg)
                GotrCombatPlugin.arena = object : GotrCombatPlugin.Arena {
                    override fun barrierAt(column: Int) = barriers.getOrNull(column)?.let { it.cell != null && !it.broken } ?: false
                    override fun hitBarrier(column: Int, damage: Int) = damageBarrier(column, damage)
                    override fun drainPower(percent: Int) { game?.let { g -> g.power = maxOf(0.0, g.power - percent) } }
                    override fun active() = game?.let { !it.ended } ?: false
                }
                logger.info { "gotr: Temple of the Eye armed — ${barriers.size} barrier slots, ${Gotr.ALTARS.size} altars, Great Guardian posted." }
            }.onFailure { logger.error(it) { "gotr: world-init failed" } }
            world.queue { lobbyLoop(this) }
        }

        // ── lobby / gate ──
        listOf("pass", "quick-pass").forEach { opt -> onObjOption(obj = Gotr.BARRIER_KEY, option = opt) { passBarrier(player) } }
        onObjOption(obj = Gotr.BARRIER_KEY, option = "peek") { status(player) }
        onObjOption(obj = Gotr.BANK_CHEST_KEY, option = "use") { player.openBank() }
        onCommand("gotr", description = "Guardians of the Rift status") { status(player) }

        // ── mining ──
        Gotr.Node.values().forEach { node -> node.keys.forEach { key -> onObjOption(obj = key, option = "mine") { player.queue { mine(player, node) } } } }
        Gotr.RUBBLE_KEYS.forEach { key -> onObjOption(obj = key, option = "climb") { player.queue { climbRubble(player) } } }
        onObjOption(obj = "object.portal_43729", option = "enter") { hugePortal(player) }

        // ── crafting ──
        onObjOption(obj = Gotr.WORKBENCH_KEY, option = "work-at") { player.queue { workbench(player) } }
        onObjOption(obj = Gotr.UNCHARGED_CELLS_KEY, option = "take-10") { takeUncharged(player, 10) }
        onObjOption(obj = Gotr.UNCHARGED_CELLS_KEY, option = "take-1") { takeUncharged(player, 1) }
        onObjOption(obj = Gotr.WEAK_CELLS_KEY, option = "take") { takeWeakCell(player) }
        Gotr.ALTARS.forEach { altar -> onObjOption(obj = altar.objKey, option = "enter") { player.queue { imbue(player, altar) } } }
        onNpcOption(npc = Gotr.GREAT_GUARDIAN_KEY, option = "power-up") { powerUp(player) }
        Gotr.SLOT_KEYS.forEach { key ->
            runCatching { onObjOption(obj = key, option = 1) { placeCell(player) } }
                .onFailure { logger.warn { "gotr: $key has no option 1 (${it.message})" } }
        }
        // An empty (inactive) tile has no click option in 228: the cell is used on it.
        Gotr.Cell.values().forEach { cell ->
            Gotr.SLOT_KEYS.forEach { key ->
                runCatching { onItemOnObj(obj = key, item = cell.item) { placeCell(player) } }
            }
        }
        onObjOption(obj = Gotr.DEPOSIT_POOL_KEY, option = "deposit-runes") { depositRunes(player) }
        onObjOption(obj = Gotr.DEPOSIT_POOL_KEY, option = "deposit-items") { depositRunes(player) }

        // ── rewards ──
        onNpcOption(npc = Gotr.REWARDS_GUARDIAN_KEY, option = "talk-to") { player.queue { rewards(player) } }
        onNpcOption(npc = Gotr.REWARDS_GUARDIAN_KEY, option = "trade-with") { player.queue { rewards(player) } }
        onNpcOption(npc = Gotr.FELIX_KEY, option = "talk-to") { player.queue { shop(player) } }

        // ── creature deaths (keep the roster tidy) ──
        listOf(Gotr.LEECH_KEY, Gotr.WALKER_KEY, Gotr.GUARDIAN_KEY).forEach { key -> onNpcDeath(key) { val d = npc; game?.creatures?.remove(d) } }

        // ── presence ──
        onEnterRegion(Gotr.REGION) { player.attr[SAFE_DEATH_ATTR] = true; status(player) }
        onExitRegion(Gotr.REGION) { leaveTemple(player) }
        onLogout { if (player.tile.regionId == Gotr.REGION) { leaveTemple(player); player.moveTo(Gotr.LOBBY) } }
    }

    // ───────────────────────────── loops ─────────────────────────────

    private fun lobbyPlayers(): List<Player> {
        val out = ArrayList<Player>()
        world.players.forEach { p: Player -> if (p.tile.regionId == Gotr.REGION && p.tile.z < Gotr.GAME_MIN_Z) out.add(p) }
        return out
    }

    private fun templePlayers(): List<Player> {
        val out = ArrayList<Player>()
        world.players.forEach { p: Player -> if (p.tile.regionId == Gotr.REGION && p.tile.z >= Gotr.GAME_MIN_Z && !p.isDead()) out.add(p) }
        return out
    }

    private suspend fun lobbyLoop(task: QueueTask) {
        while (true) {
            if (game == null) {
                val waiting = lobbyPlayers()
                if (waiting.isEmpty()) lobbyCountdown = -1
                else {
                    if (lobbyCountdown < 0) { lobbyCountdown = Gotr.LOBBY_WAIT; waiting.forEach { it.message("<col=0099ff>The rift stirs — the next game starts in ${Gotr.LOBBY_WAIT * 6 / 10} seconds. Pass the barrier when it drops.</col>") } }
                    lobbyCountdown -= 5
                    if (lobbyCountdown % 50 == 0 && lobbyCountdown > 0) waiting.forEach { it.message("Game starts in ${lobbyCountdown * 6 / 10} seconds.") }
                    if (lobbyCountdown <= 0) startGame(waiting)
                }
            }
            task.wait(5)
        }
    }

    private fun startGame(waiting: List<Player>) {
        val g = Game(world.currentCycle)
        game = g
        barriers.forEachIndexed { i, b -> b.cell = null; b.hp = 0; b.broken = false; setSlot(i, b, Gotr.INACTIVE_TILE_ID) }
        waiting.forEach { p ->
            g.players += p
            p.attr[ELEM] = 0; p.attr[CAT] = 0; p.attr[MINED] = 0
            p.moveTo(world.snapToWalkable(Tile(Gotr.GAME_ENTRY.x + world.random(4) - 2, Gotr.GAME_ENTRY.z, 0), maxRadius = 2))
            p.message("<col=0099ff>The barrier drops. Two minutes to prepare: mine guardian remains, craft essence at the workbench, place cells. The rift opens after that.</col>")
        }
        world.queue { gameLoop(this, g) }
    }

    private suspend fun gameLoop(task: QueueTask, g: Game) {
        while (!g.ended) {
            task.wait(2)
            val t = world.currentCycle - g.startedAt
            g.players.removeAll { it.index < 0 || it.tile.regionId != Gotr.REGION || it.tile.z < Gotr.GAME_MIN_Z }
            templePlayers().forEach { p -> if (g.players.add(p)) { p.attr[ELEM] = 0; p.attr[CAT] = 0; p.attr[MINED] = 0 } }
            if (g.players.isEmpty()) { endGame(g, success = false); return }
            if (t >= Gotr.MAX_GAME_TICKS) { endGame(g, success = false); return }

            if (t >= g.nextPortalsAt) openPortals(g, t)
            if (g.portalsCloseAt in 1..t) { g.elementalOpen = null; g.catalyticOpen = null; g.portalsCloseAt = 0; g.players.forEach { it.message("The altar portals close.") } }
            if (t >= g.nextHugeAt) openHugePortal(g, t)
            if (g.hugeCloseAt in 1..t) { g.hugePortal?.let { world.remove(it) }; g.hugePortal = null; g.hugeCloseAt = 0 }

            if (t >= Gotr.PREP_TICKS && t % 10 == 0) spawnCreatures(g)
            if (!g.shocked && g.power >= Gotr.SHOCK_POWER) shock(g)
            if (g.power >= 100.0) { endGame(g, success = true); return }

            if (t - g.lastReport >= 42) { g.lastReport = t; g.players.forEach { statusLine(it, g) } }
        }
    }

    private fun openPortals(g: Game, t: Int) {
        val maxRc = g.players.maxOfOrNull { it.getSkills().getBaseLevel(Skills.RUNECRAFTING) } ?: 1
        val elem = Gotr.ALTARS.filter { it.kind == Gotr.Kind.ELEMENTAL && it.level <= maxRc }.ifEmpty { listOf(Gotr.ALTARS.first()) }
        val cat = Gotr.ALTARS.filter { it.kind == Gotr.Kind.CATALYTIC && it.level <= maxRc }.ifEmpty { listOf(Gotr.ALTARS[4]) }
        g.elementalOpen = elem[world.random(elem.size - 1)]
        g.catalyticOpen = cat[world.random(cat.size - 1)]
        g.portalsCloseAt = t + Gotr.PORTAL_OPEN
        g.nextPortalsAt = t + Gotr.PORTAL_INTERVAL
        listOf(g.elementalOpen!!, g.catalyticOpen!!).forEach { a -> world.spawn(TileGraphic(Tile(a.tile.x, a.tile.z, 0), 1039, 0)) }
        g.players.forEach { it.message("<col=ff00ff>Portals open: the Guardian of ${g.elementalOpen!!.name} (elemental) and the Guardian of ${g.catalyticOpen!!.name} (catalytic) — ${Gotr.PORTAL_OPEN * 6 / 10} seconds.</col>") }
    }

    private fun openHugePortal(g: Game, t: Int) {
        g.hugePortal?.let { world.remove(it) }
        val portal = DynamicObject(Gotr.HUGE_PORTAL_ID, 10, 0, Gotr.HUGE_PORTAL_TILE)
        world.spawn(portal)
        g.hugePortal = portal
        g.hugeCloseAt = t + Gotr.HUGE_PORTAL_OPEN
        g.nextHugeAt = t + Gotr.HUGE_PORTAL_INTERVAL
        g.players.forEach { it.message("<col=ff00ff>A portal to the huge guardian remains opens in the west — ${Gotr.HUGE_PORTAL_OPEN * 6 / 10} seconds.</col>") }
    }

    private fun spawnCreatures(g: Game) {
        val alive = g.creatures.count { it.index >= 0 && !it.isDead() }
        if (alive >= 8 + 2 * g.players.size) return
        val n = 1 + world.random(2)
        repeat(n) {
            val roll = world.random(99)
            val (key, role) = if (!g.shocked) when { roll < 70 -> Gotr.LEECH_KEY to "leech"; roll < 90 -> Gotr.WALKER_KEY to "walker"; else -> Gotr.GUARDIAN_KEY to "guardian" }
            else when { roll < 40 -> Gotr.LEECH_KEY to "leech"; roll < 75 -> Gotr.WALKER_KEY to "walker"; else -> Gotr.GUARDIAN_KEY to "guardian" }
            runCatching {
                val npc = Npc(getRSCM(key), world.snapToWalkable(Tile(Gotr.RIFT_SPAWN.x + world.random(6) - 3, Gotr.RIFT_SPAWN.z, 0), maxRadius = 3), world)
                npc.respawns = false
                world.spawn(npc)
                npc.setActive(true)
                g.creatures += npc
                GotrCombatPlugin.drive(world, npc, role, world.random(barriers.size - 1))
            }.onFailure { logger.warn { "gotr: failed to spawn $key: ${it.message}" } }
        }
    }

    private fun shock(g: Game) {
        g.shocked = true
        barriers.forEachIndexed { i, b -> if (b.cell != null && !b.broken) damageBarrier(i, Gotr.SHOCK_DAMAGE) }
        g.players.forEach { it.message("<col=ff0000>The temple rumbles as the rift glows — every barrier is shaken (−${Gotr.SHOCK_DAMAGE}) and the abyss spews out its warriors!</col>") }
    }

    private fun endGame(g: Game, success: Boolean) {
        if (g.ended) return
        g.ended = true
        game = null
        g.creatures.forEach { if (it.index >= 0 && !it.isDead()) world.remove(it) }
        g.creatures.clear()
        g.hugePortal?.let { world.remove(it) }
        val players = g.players.toList()
        players.forEach { p ->
            if (success) score(p) else p.message("<col=ff0000>The rift overwhelms the temple. No rewards this time.</col>")
            removeGameItems(p)
            p.moveTo(world.snapToWalkable(Tile(Gotr.LOBBY.x + world.random(4) - 2, Gotr.LOBBY.z, 0), maxRadius = 2))
        }
        lobbyCountdown = Gotr.LOBBY_WAIT_AFTER_GAME
    }

    private fun score(p: Player) {
        val elem = p.attr[ELEM] ?: 0
        val cat = p.attr[CAT] ?: 0
        fun points(energy: Int): Int { var pts = energy / 100; if (world.random(99) < energy % 100) pts++; return pts }
        val ep = points(elem); val cp = points(cat)
        p.attr[Gotr.ELEMENTAL_POINTS] = (p.attr[Gotr.ELEMENTAL_POINTS] ?: 0) + ep
        p.attr[Gotr.CATALYTIC_POINTS] = (p.attr[Gotr.CATALYTIC_POINTS] ?: 0) + cp
        p.attr[Gotr.GAMES] = (p.attr[Gotr.GAMES] ?: 0) + 1
        val total = elem + cat
        if (total >= Gotr.MIN_ENERGY_FOR_XP) p.addXp(Skills.RUNECRAFTING, p.getSkills().getBaseLevel(Skills.RUNECRAFTING) * Gotr.XP_PER_LEVEL)
        p.message("<col=0099ff>The rift is closed!</col> Energy $elem elemental / $cat catalytic → +$ep elemental and +$cp catalytic points (you now have ${p.attr[Gotr.ELEMENTAL_POINTS]} / ${p.attr[Gotr.CATALYTIC_POINTS]}).${if (total < Gotr.MIN_ENERGY_FOR_XP) " Too little energy for the Guardian's blessing (${Gotr.MIN_ENERGY_FOR_XP} needed)." else ""}")
    }

    // ───────────────────────────── barriers ─────────────────────────────

    private fun setSlot(i: Int, b: Barrier, id: Int) {
        val tile = Gotr.CELL_TILES[i]
        b.obj?.let { world.remove(it) } ?: world.getObject(tile, type = 22)?.let { world.remove(it) }
        val obj = DynamicObject(id, 22, 0, tile)
        world.spawn(obj)
        b.obj = obj
    }

    private fun damageBarrier(column: Int, damage: Int) {
        val b = barriers.getOrNull(column) ?: return
        if (b.cell == null || b.broken) return
        b.hp -= damage
        if (b.hp <= 0) {
            b.cell = null; b.hp = 0; b.broken = true
            setSlot(column, b, Gotr.BROKEN_TILE_ID)
            game?.players?.forEach { it.message("<col=ff0000>A barrier has been broken! Repair it with ${Gotr.REPAIR_FRAGMENTS} guardian fragments.</col>") }
        }
    }

    private fun placeCell(p: Player) {
        val g = game ?: run { p.message("There's no game in progress."); return }
        val obj = p.getInteractingGameObj()
        val i = Gotr.CELL_TILES.indexOfFirst { it.sameAs(obj.tile) }
        if (i < 0) return
        val b = barriers[i]
        if (b.broken) {
            val frags = getRSCM(Gotr.FRAGMENTS)
            if (p.inventory.getItemCount(frags) < Gotr.REPAIR_FRAGMENTS) { p.message("You need ${Gotr.REPAIR_FRAGMENTS} guardian fragments to repair this tile."); return }
            p.inventory.remove(frags, Gotr.REPAIR_FRAGMENTS)
            b.broken = false
            setSlot(i, b, Gotr.INACTIVE_TILE_ID)
            addEnergy(p, g, Gotr.REPAIR_ENERGY, Gotr.REPAIR_ENERGY)
            p.animate(3676)
            p.message("You repair the cell tile.")
            return
        }
        val held = Gotr.Cell.values().reversed().firstOrNull { p.inventory.contains(getRSCM(it.item)) }
        if (held == null) { p.message("You need a charged cell to power this tile."); return }
        if (b.cell != null && held.ordinal <= b.cell!!.ordinal) { p.message("This barrier is already at least that strong."); return }
        p.inventory.remove(getRSCM(held.item), 1)
        val strengthening = b.cell != null
        b.cell = held
        b.hp = held.hp
        setSlot(i, b, Gotr.TILE_IDS[held] ?: Gotr.INACTIVE_TILE_ID)
        p.animate(827)
        if (strengthening) addEnergy(p, g, held.strengthen, held.strengthen) else addEnergy(p, g, Gotr.PLACE_ENERGY, Gotr.PLACE_ENERGY)
        p.message("You place the ${held.name.lowercase()} cell — a ${held.name.lowercase()} barrier rises.")
    }

    // ───────────────────────────── actions ─────────────────────────────

    private fun passBarrier(p: Player) {
        if (p.tile.z >= Gotr.GAME_MIN_Z) { p.moveTo(Gotr.LOBBY); leaveGame(p); p.message("You leave the temple — this game's energy is forfeit."); return }
        val g = game
        if (g == null) { p.message("The barrier holds. The next game starts ${if (lobbyCountdown > 0) "in ${lobbyCountdown * 6 / 10} seconds" else "once someone is waiting"}."); return }
        if (p.getSkills().getBaseLevel(Skills.RUNECRAFTING) < Gotr.RUNECRAFT_REQ) { p.message("You need a Runecraft level of ${Gotr.RUNECRAFT_REQ} to help the Great Guardian."); return }
        p.moveTo(Gotr.GAME_ENTRY)
        if (g.players.add(p)) { p.attr[ELEM] = 0; p.attr[CAT] = 0; p.attr[MINED] = 0 }
        p.message("You join the game in progress.")
    }

    private suspend fun QueueTask.mine(p: Player, node: Gotr.Node) {
        val g = game ?: run { p.message("There's no game in progress."); return }
        val held = GatheringTools.bestPickaxe(p)
        val pick = held.tool ?: run { p.message(if (held.anyHeld) "You don't have the Mining level for your pickaxe." else "You need a pickaxe to mine the guardian remains."); return }
        if (node.huge && g.hugePortal == null && p.tile.x > 3596) { p.message("The huge remains lie beyond the western portal — wait for it to open."); return }
        val frags = getRSCM(Gotr.FRAGMENTS)
        val lvl = p.getSkills().getBaseLevel(Skills.MINING)
        val chance = 55 + lvl * 3 / 10
        while (game === g && !g.ended) {
            if (p.inventory.freeSlotCount == 0 && !p.inventory.contains(frags)) { p.message("Your pack is full."); break }
            p.animate(pick.anim)
            wait(3)
            if (game !== g || g.ended) break
            if (world.random(99) < chance) {
                val n = node.minFrags + world.random(node.maxFrags - node.minFrags)
                p.inventory.add(frags, n)
                val mined = p.attr[MINED] ?: 0
                val credited = minOf(n, maxOf(0, Gotr.MINING_XP_CAP_FRAGMENTS - mined))
                p.attr[MINED] = mined + n
                if (credited > 0) p.addXp(Skills.MINING, credited * 5.0)
            }
        }
        p.animate(-1)
    }

    private suspend fun QueueTask.climbRubble(p: Player) {
        if (p.getSkills().getBaseLevel(Skills.AGILITY) < Gotr.AGILITY_REQ_LARGE) { p.message("You need an Agility level of ${Gotr.AGILITY_REQ_LARGE} to climb the rubble."); return }
        p.lock(); p.animate(839); wait(2)
        p.moveTo(if (p.tile.x < 3635) Gotr.RUBBLE_EAST_LANDING else Gotr.RUBBLE_WEST_LANDING)
        p.unlock()
    }

    private fun hugePortal(p: Player) {
        val g = game ?: return
        if (g.hugePortal == null) { p.message("The portal has closed."); return }
        p.moveTo(if (p.tile.x > 3596) Gotr.HUGE_PORTAL_LANDING else Tile(Gotr.HUGE_PORTAL_TILE.x + 1, Gotr.HUGE_PORTAL_TILE.z, 0))
        p.graphic(110, 0)
    }

    private suspend fun QueueTask.workbench(p: Player) {
        val g = game ?: run { p.message("There's no game in progress."); return }
        val frags = getRSCM(Gotr.FRAGMENTS); val ess = getRSCM(Gotr.ESSENCE)
        if (!p.inventory.contains(frags)) { p.message("You have no guardian fragments to work."); return }
        while (game === g && !g.ended && p.inventory.contains(frags) && p.inventory.freeSlotCount > 0) {
            p.animate(899)
            wait(2)
            val batch = minOf(5, p.inventory.getItemCount(frags), p.inventory.freeSlotCount)
            if (batch <= 0) break
            p.inventory.remove(frags, batch)
            p.inventory.add(ess, batch)
        }
        p.animate(-1)
        if (p.inventory.freeSlotCount == 0) p.message("Your pack is full of guardian essence.")
    }

    private fun takeUncharged(p: Player, n: Int) {
        if (game == null) { p.message("There's no game in progress."); return }
        val id = getRSCM(Gotr.UNCHARGED_CELL)
        val have = p.inventory.getItemCount(id)
        val take = minOf(n, 10 - have, p.inventory.freeSlotCount)
        if (take <= 0) { p.message("You can't carry more than 10 uncharged cells."); return }
        p.inventory.add(id, take)
        p.message("You take $take uncharged cell${if (take > 1) "s" else ""}.")
    }

    private fun takeWeakCell(p: Player) {
        if (game == null) { p.message("There's no game in progress."); return }
        if (Gotr.Cell.values().any { p.inventory.contains(getRSCM(it.item)) }) { p.message("You can only carry one charged cell at a time."); return }
        if (p.inventory.freeSlotCount == 0) { p.message("Your pack is full."); return }
        p.inventory.add(getRSCM(Gotr.Cell.WEAK.item), 1)
        p.message("You take a weak cell.")
    }

    private suspend fun QueueTask.imbue(p: Player, altar: Gotr.Altar) {
        val g = game ?: run { p.message("There's no game in progress."); return }
        if (g.elementalOpen !== altar && g.catalyticOpen !== altar) { p.message("The Guardian of ${altar.name}'s portal is closed."); return }
        if (p.getSkills().getBaseLevel(Skills.RUNECRAFTING) < altar.level) { p.message("You need a Runecraft level of ${altar.level} to imbue ${altar.name.lowercase()} runes."); return }
        val ess = getRSCM(Gotr.ESSENCE)
        val count = p.inventory.getItemCount(ess)
        if (count == 0) { p.message("You have no guardian essence to imbue."); return }
        p.lock(); p.animate(791); p.graphic(186, 100)
        wait(3)
        p.unlock()
        if (game !== g || g.ended) return
        p.inventory.remove(ess, count)
        p.inventory.add(getRSCM(altar.rune), count)
        val stone = if (altar.kind == Gotr.Kind.ELEMENTAL) Gotr.ELEMENTAL_STONE else Gotr.CATALYTIC_STONE
        p.inventory.add(getRSCM(stone), count)
        p.addXp(Skills.RUNECRAFTING, altar.xp * count)
        val unchargedId = getRSCM(Gotr.UNCHARGED_CELL)
        var charged = ""
        if (p.inventory.contains(unchargedId) && Gotr.Cell.values().none { p.inventory.contains(getRSCM(it.item)) }) {
            val cell = Gotr.cellFor(altar.level)
            p.inventory.remove(unchargedId, 1)
            p.inventory.add(getRSCM(cell.item), 1)
            charged = " One cell is charged: ${cell.name.lowercase()}."
        }
        p.message("<col=ff00ff>You imbue $count guardian essence at the Guardian of ${altar.name}: $count ${altar.name.lowercase()} runes and $count ${altar.kind.name.lowercase()} guardian stones.$charged</col>")
    }

    private fun powerUp(p: Player) {
        val g = game ?: run { p.message("The Great Guardian sleeps between games."); return }
        val e = getRSCM(Gotr.ELEMENTAL_STONE); val c = getRSCM(Gotr.CATALYTIC_STONE)
        val ne = p.inventory.getItemCount(e); val nc = p.inventory.getItemCount(c)
        if (ne + nc == 0) { p.message("You have no guardian stones to give."); return }
        if (ne > 0) p.inventory.remove(e, ne)
        if (nc > 0) p.inventory.remove(c, nc)
        addEnergy(p, g, ne * Gotr.ENERGY_PER_STONE, nc * Gotr.ENERGY_PER_STONE)
        val perStone = 100.0 / (Gotr.STONES_PER_PLAYER * maxOf(1, g.players.size))
        g.power = minOf(100.0, g.power + (ne + nc) * perStone)
        p.runEnergy = minOf(10000.0, p.runEnergy + (ne + nc) * 100.0)
        p.animate(827)
        p.message("<col=0099ff>You power the Great Guardian with ${ne + nc} stones. Power ${g.power.toInt()}% — your energy ${p.attr[ELEM]} elemental / ${p.attr[CAT]} catalytic.</col>")
    }

    private fun addEnergy(p: Player, g: Game, elem: Int, cat: Int) {
        p.attr[ELEM] = minOf(Gotr.ENERGY_CAP_PER_TYPE, (p.attr[ELEM] ?: 0) + elem)
        p.attr[CAT] = minOf(Gotr.ENERGY_CAP_PER_TYPE, (p.attr[CAT] ?: 0) + cat)
    }

    private fun depositRunes(p: Player) {
        val runeIds = Gotr.ALTARS.map { getRSCM(it.rune) }
        var moved = 0
        runeIds.forEach { id -> val n = p.inventory.getItemCount(id); if (n > 0 && p.inventory.remove(id, n).hasSucceeded()) { p.bank.add(id, n); moved += n } }
        p.message(if (moved > 0) "You deposit $moved runes into the pool — they flow to your bank." else "You have no runes to deposit.")
    }

    private fun leaveGame(p: Player) { game?.players?.remove(p); p.attr.remove(ELEM); p.attr.remove(CAT); p.attr.remove(MINED) }

    private fun leaveTemple(p: Player) { leaveGame(p); p.attr.remove(SAFE_DEATH_ATTR); removeGameItems(p) }

    private fun removeGameItems(p: Player) {
        Gotr.GAME_ITEMS.map { getRSCM(it) }.forEach { id -> val n = p.inventory.getItemCount(id); if (n > 0) p.inventory.remove(id, n) }
    }

    private fun status(p: Player) {
        val g = game
        p.message("<col=0099ff>Guardians of the Rift:</col> points ${p.attr[Gotr.ELEMENTAL_POINTS] ?: 0} elemental / ${p.attr[Gotr.CATALYTIC_POINTS] ?: 0} catalytic, games ${p.attr[Gotr.GAMES] ?: 0}, pearls ${p.inventory.getItemCount(getRSCM(Gotr.PEARLS))}.")
        if (g != null) statusLine(p, g) else p.message(if (lobbyCountdown > 0) "Next game in ${lobbyCountdown * 6 / 10} seconds." else "No game running — wait in the lobby and one will start.")
    }

    private fun statusLine(p: Player, g: Game) {
        val t = world.currentCycle - g.startedAt
        val portals = if (g.elementalOpen != null) "portals: ${g.elementalOpen!!.name} + ${g.catalyticOpen!!.name} (${maxOf(0, g.portalsCloseAt - t) * 6 / 10}s)" else "next portals in ${maxOf(0, g.nextPortalsAt - t) * 6 / 10}s"
        val standing = barriers.count { it.cell != null && !it.broken }
        p.message("<col=0099ff>Power ${g.power.toInt()}%</col> | $portals | barriers $standing/${barriers.size} | you: ${p.attr[ELEM] ?: 0} elemental / ${p.attr[CAT] ?: 0} catalytic | ${if (t < Gotr.PREP_TICKS) "rift opens in ${(Gotr.PREP_TICKS - t) * 6 / 10}s" else "rift open"}")
    }

    // ───────────────────────────── rewards ─────────────────────────────

    private suspend fun QueueTask.rewards(p: Player) {
        val id = getRSCM(Gotr.REWARDS_GUARDIAN_KEY)
        val ep = p.attr[Gotr.ELEMENTAL_POINTS] ?: 0; val cp = p.attr[Gotr.CATALYTIC_POINTS] ?: 0
        chatNpc(p, "You have $ep elemental and $cp catalytic points. Each search costs<br>one of each; ${Gotr.PEARLS_PER_SEARCH} abyssal pearls buy a search too.", npc = id, title = "Rewards Guardian")
        when (options(p, "Search", "Big-search (5)", "Search with ${Gotr.PEARLS_PER_SEARCH} pearls", "Nothing", title = "Rewards Guardian")) {
            1 -> search(p, 1, pearls = false)
            2 -> search(p, 5, pearls = false)
            3 -> search(p, 1, pearls = true)
        }
    }

    private fun search(p: Player, times: Int, pearls: Boolean) {
        var done = 0
        repeat(times) {
            if (pearls) {
                val pid = getRSCM(Gotr.PEARLS)
                if (p.inventory.getItemCount(pid) < Gotr.PEARLS_PER_SEARCH) return@repeat
                p.inventory.remove(pid, Gotr.PEARLS_PER_SEARCH)
            } else {
                val ep = p.attr[Gotr.ELEMENTAL_POINTS] ?: 0; val cp = p.attr[Gotr.CATALYTIC_POINTS] ?: 0
                if (ep < 1 || cp < 1) return@repeat
                p.attr[Gotr.ELEMENTAL_POINTS] = ep - 1; p.attr[Gotr.CATALYTIC_POINTS] = cp - 1
            }
            rollReward(p); done++
        }
        if (done == 0) p.message(if (pearls) "You need ${Gotr.PEARLS_PER_SEARCH} abyssal pearls." else "You need at least one elemental and one catalytic point.")
        else p.message("You search the Rewards Guardian $done time${if (done > 1) "s" else ""}. Points left: ${p.attr[Gotr.ELEMENTAL_POINTS] ?: 0} / ${p.attr[Gotr.CATALYTIC_POINTS] ?: 0}.")
    }

    private fun rollReward(p: Player) {
        val total = Gotr.REWARD_TABLE.sumOf { it.weight }
        var roll = world.random(total - 1)
        val pick = Gotr.REWARD_TABLE.first { roll -= it.weight; roll < 0 }
        when (pick.key) {
            "POUCH" -> {
                val next = Gotr.POUCHES.firstOrNull { k -> val id = getRSCM(k); !p.inventory.contains(id) && !p.bank.contains(id) }
                if (next != null) give(p, next, 1) else give(p, Gotr.PEARLS, 14 + world.random(2))
            }
            "TALISMAN" -> {
                val tw = Gotr.TALISMANS.sumOf { it.second }
                var r = world.random(tw - 1)
                val t = Gotr.TALISMANS.first { r -= it.second; r < 0 }
                give(p, t.first, 1)
            }
            else -> give(p, pick.key, pick.min + world.random(pick.max - pick.min))
        }
        val once = (p.attr[Gotr.ONCE_REWARDS] ?: "").split(",").filter { it.isNotBlank() }.toMutableSet()
        Gotr.RARES.forEach { rare ->
            if (rare.once && rare.key in once) return@forEach
            if (world.chance(1, rare.oneIn)) {
                give(p, rare.key, 1)
                if (rare.once) { once += rare.key; p.attr[Gotr.ONCE_REWARDS] = once.joinToString(",") }
                val name = CacheManager.getItem(getRSCM(rare.key)).name ?: rare.key
                world.players.forEach { it.message("<col=ff0000>News: ${p.username} just received <col=ffae00>$name</col> from the Rewards Guardian!</col>") }
                if (rare.key in Gotr.LOGGED && CollectionLog.record(p, getRSCM(rare.key))) p.message("<col=ffae00>New Collection Log slot: $name!</col>")
            }
        }
    }

    private suspend fun QueueTask.shop(p: Player) {
        val id = getRSCM(Gotr.FELIX_KEY)
        if (!Gotr.SHOP_ENABLED) { chatNpc(p, "The temple's supplies aren't for sale just now.", npc = id, title = "Apprentice Felix"); return }
        val pearls = p.inventory.getItemCount(getRSCM(Gotr.PEARLS))
        chatNpc(p, "Temple Supplies — abyssal pearls only. You have $pearls.", npc = id, title = "Apprentice Felix")
        val page1 = Gotr.SHOP.take(4); val page2 = Gotr.SHOP.drop(4)
        val first = options(p, *page1.map { "${it.name} (${it.pearls})" }.toTypedArray(), "More...", title = "Temple Supplies — $pearls pearls")
        val pick = when {
            first in 1..page1.size -> page1[first - 1]
            first == page1.size + 1 -> { val s = options(p, *page2.map { "${it.name} (${it.pearls})" }.toTypedArray(), title = "Temple Supplies — $pearls pearls"); page2.getOrNull(s - 1) }
            else -> null
        } ?: return
        val pid = getRSCM(Gotr.PEARLS)
        if (p.inventory.getItemCount(pid) < pick.pearls) { chatNpc(p, "You need ${pick.pearls} pearls for the ${pick.name.lowercase()}.", npc = id, title = "Apprentice Felix"); return }
        if (p.inventory.freeSlotCount == 0) { p.message("You need a free inventory slot."); return }
        p.inventory.remove(pid, pick.pearls)
        val item = getRSCM(pick.key)
        p.inventory.add(item, 1)
        if (pick.key in Gotr.LOGGED && CollectionLog.record(p, item)) p.message("<col=ffae00>New Collection Log slot: ${pick.name}!</col>")
        chatNpc(p, "${pick.name} — ${p.inventory.getItemCount(pid)} pearls remain.", npc = id, title = "Apprentice Felix")
    }

    private fun give(p: Player, key: String, amount: Int) {
        val id = runCatching { getRSCM(key) }.getOrNull() ?: return
        val added = p.inventory.add(item = id, amount = amount, assureFullInsertion = false)
        val leftover = amount - added.completed
        if (leftover > 0) p.bank.add(id, leftover)
    }
}
