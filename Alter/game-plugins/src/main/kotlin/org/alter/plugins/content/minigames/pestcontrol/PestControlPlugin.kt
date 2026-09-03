package org.alter.plugins.content.minigames.pestcontrol

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.getCombatTarget
import org.alter.plugins.content.companion.CompanionPolicy
import org.alter.plugins.content.raids.RaidInstance
import org.alter.plugins.content.war.WarNpcNames
import org.alter.rscm.RSCM.getRSCM
import dev.openrune.cache.CacheManager

private val logger = KotlinLogging.logger {}

/**
 * Landers, games, portals, the Void Knight and the commendation shop. See [PestControl].
 */
class PestControlPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private class Portal(val def: PestControl.PortalDef) {
        var npc: Npc? = null
        var shieldDropped = false
        var dead = false
    }

    private class Game(val lander: PestControl.Lander, val instance: RaidInstance, val players: MutableList<Player>) {
        val bounds: Area
        val portals = PestControl.PORTALS.map { Portal(it) }
        var knight: Npc? = null
        var squire: Npc? = null
        val spawned = mutableListOf<Npc>()
        var lifespan = PestControl.LIFESPAN_TICKS
        var cycles = 0
        var ended = false

        init {
            val sw = instance.translate(Tile(PestControl.ARENA.bottomLeftX, PestControl.ARENA.bottomLeftY, 0))
            val ne = instance.translate(Tile(PestControl.ARENA.topRightX, PestControl.ARENA.topRightY, 0))
            bounds = Area(sw.x, sw.z, ne.x, ne.z)
        }

        fun inside(p: Player) = bounds.contains(p.tile)
    }

    private class Boat(val lander: PestControl.Lander) {
        val waiting = mutableListOf<Player>()
        var departure = PestControl.DEPARTURE_TICKS
        var game: Game? = null
    }

    private val boats = PestControl.Lander.values().map { Boat(it) }
    private val games = mutableListOf<Game>()

    init {
        CompanionPolicy.denyInstanceOf(PestControl.ARENA, "The Void Knights admit only their own")

        onWorldInit {
            runCatching {
                val knight = Npc(getRSCM(PestControl.SHOP_KNIGHT_KEY), world.snapToWalkable(PestControl.SHOP_KNIGHT_TILE, maxRadius = 3), world)
                knight.respawns = true
                knight.walkRadius = 0
                world.spawn(knight)
                logger.info { "pest-control: 3 landers armed (party min ${PestControl.MIN_PARTY}); Void Knight exchange posted." }
            }.onFailure { logger.error(it) { "pest-control: world-init failed" } }
            boats.forEach { boat -> world.queue { boatLoop(this, boat) } }
        }

        boats.forEach { boat ->
            onObjOption(obj = boat.lander.gangplank, option = "cross") { join(player, boat) }
            onObjOption(obj = boat.lander.ladder, option = "climb") { leaveBoat(player, boat, message = "You leave the lander.") }
        }

        onNpcOption(npc = PestControl.SQUIRE_KEY, option = "talk-to") {
            val g = gameOf(player) ?: return@onNpcOption
            player.queue {
                chatNpc(player, "Be quick, we're under attack!", npc = getRSCM(PestControl.SQUIRE_KEY), title = "Squire")
                if (options(player, "I'd like to leave.", "Nevermind.", title = "Select an Option") == 1) leaveGame(player, g, "You leave the battleground.")
            }
        }
        onNpcOption(npc = PestControl.SQUIRE_KEY, option = "leave") { gameOf(player)?.let { leaveGame(player, it, "You leave the battleground.") } }

        onNpcOption(npc = PestControl.SHOP_KNIGHT_KEY, option = "talk-to") { player.queue { knightTalk(player) } }
        onNpcOption(npc = PestControl.SHOP_KNIGHT_KEY, option = "exchange") { player.queue { shop(player) } }
        onCommand("pc", description = "Pest Control status") { status(player) }

        PestControl.ALL_PESTS.forEach { key ->
            onNpcDeath(key) {
                val dead = npc
                val killer = dead.attr[KILLER_ATTR]?.get() as? Player
                if (killer != null) killer.attr[PestControl.ACTIVITY] = (killer.attr[PestControl.ACTIVITY] ?: 0) + 15
                if (key in PestControl.ALL_SPLATTERS) splat(dead)
                games.forEach { it.spawned.remove(dead) }
            }
        }
        PestControl.PORTALS.forEach { def ->
            onNpcDeath(def.open) {
                val dead = npc
                val killer = dead.attr[KILLER_ATTR]?.get() as? Player
                if (killer != null) killer.attr[PestControl.ACTIVITY] = (killer.attr[PestControl.ACTIVITY] ?: 0) + 50
                val g = games.firstOrNull { game -> game.portals.any { it.npc === dead } } ?: return@onNpcDeath
                val portal = g.portals.first { it.npc === dead }
                portal.dead = true
                g.players.forEach { it.message("<col=ff00ff>The ${portal.def.name} portal has been destroyed!</col>") }
                if (g.portals.all { it.dead }) end(g, failed = false)
            }
        }
        PestControl.Lander.values().forEach { lander ->
            onNpcDeath(lander.knightKey) {
                val dead = npc
                val g = games.firstOrNull { it.knight === dead } ?: return@onNpcDeath
                end(g, failed = true)
            }
        }

        onLogout {
            boats.forEach { b -> b.waiting.remove(player) }
            gameOf(player)?.let { g -> g.players.remove(player) }
        }
    }

    // ───────────────────────────── landers ─────────────────────────────

    private fun join(p: Player, boat: Boat) {
        if (boats.any { p in it.waiting } || gameOf(p) != null) return
        if (p.combatLevel < boat.lander.combatReq) {
            p.message("You require a combat level of at least ${boat.lander.combatReq} or higher to join this lander.")
            return
        }
        val plank = p.getInteractingGameObj()
        val offset = if (plank.rot == 0) 4 else -4
        p.lock()
        boat.waiting += p
        p.moveTo(Tile(p.tile.x + offset, p.tile.z, p.tile.height))
        p.unlock()
        p.message("<col=0000ff>You board the ${boat.lander.title} lander.</col> Commendation points: ${p.attr[PestControl.COMMENDATIONS] ?: 0}. " + departureText(boat))
    }

    private fun leaveBoat(p: Player, boat: Boat, message: String) {
        if (!boat.waiting.remove(p)) return
        p.moveTo(boat.lander.exit)
        p.message(message)
    }

    private fun departureText(boat: Boat): String {
        val ready = boat.waiting.size
        return if (ready < PestControl.MIN_PARTY) "Players ready: $ready (${PestControl.MIN_PARTY - ready} needed)."
        else "Players ready: $ready. Next departure in ${boat.departure * 6 / 10} seconds."
    }

    private suspend fun boatLoop(task: QueueTask, boat: Boat) {
        while (true) {
            boat.waiting.removeAll { it.index < 0 || it.tile.regionId != 10537 }
            if (boat.waiting.size >= PestControl.MIN_PARTY) {
                boat.departure -= 5
                if (boat.departure % 50 == 0 && boat.departure > 0) boat.waiting.forEach { it.message(departureText(boat)) }
                if (boat.departure <= 0) {
                    if (boat.game == null || boat.game!!.ended) startGame(boat)
                    boat.departure = PestControl.DEPARTURE_TICKS
                }
            } else {
                boat.departure = PestControl.DEPARTURE_TICKS
            }
            task.wait(5)
        }
    }

    // ───────────────────────────── the game ─────────────────────────────

    private fun startGame(boat: Boat) {
        val party = boat.waiting.toMutableList()
        boat.waiting.clear()
        if (party.isEmpty()) return
        val instance = RaidInstance.allocate(world = world, sourceArea = PestControl.ARENA, exitTile = boat.lander.exit, owner = party.first().uid)
        if (instance == null) {
            party.forEach { it.message("The lander's crew can't find clear water — try again shortly.") }
            return
        }
        val g = Game(boat.lander, instance, party)
        games += g
        boat.game = g
        runCatching {
            g.squire = spawn(g, PestControl.SQUIRE_KEY, PestControl.SQUIRE_TILE)
            val knight = spawn(g, boat.lander.knightKey, PestControl.KNIGHT_TILE)
            g.knight = knight
            g.portals.forEach { portal ->
                val npc = spawn(g, portal.def.shielded, portal.def.tile)
                npc.setCurrentHp(boat.lander.portalHp)
                WarNpcNames.rename(npc, "${portal.def.name} portal")
                portal.npc = npc
            }
        }.onFailure { logger.warn(it) { "pest-control: failed to set up the battleground" } }
        party.forEach { p ->
            p.attr[PestControl.ACTIVITY] = 0
            p.moveTo(g.instance.translate(Tile(PestControl.SPAWN_BASE.x + world.random(3), PestControl.SPAWN_BASE.z + world.random(4), 0)))
            p.message("<col=0000ff>You must defend the Void Knight while the portals are unsummoned. The ritual takes twenty minutes, so help by destroying them yourselves! Now GO GO GO!</col>")
        }
        world.queue { gameLoop(this, g) }
    }

    private fun spawn(g: Game, key: String, sourceTile: Tile): Npc {
        val npc = Npc(getRSCM(key), world.snapToWalkable(g.instance.translate(sourceTile), maxRadius = 3), world)
        npc.respawns = false
        npc.walkRadius = 0
        world.spawn(npc)
        npc.setActive(true)
        g.spawned += npc
        return npc
    }

    private suspend fun gameLoop(task: QueueTask, g: Game) {
        while (!g.ended) {
            task.wait(2)
            g.cycles++
            g.lifespan -= 2
            g.players.removeAll { it.index < 0 || !g.inside(it) }
            if (g.players.isEmpty() || g.lifespan <= 0) { end(g, failed = true); return }

            if (g.cycles % 4 == 0) g.players.forEach { p -> p.attr[PestControl.ACTIVITY] = maxOf(0, (p.attr[PestControl.ACTIVITY] ?: 0) - 2) }
            if (g.cycles % 9 == 0) g.portals.filter { !it.dead }.forEach { spawnPests(g, it) }
            if (g.cycles % 14 == 0) spawnKnightPests(g)
            if (g.cycles % 17 == 0) dropRandomShield(g)
            if (g.cycles % 2 == 0) pressKnight(g)
            if (g.cycles % 50 == 0) g.players.forEach { status(it) }
        }
    }

    private fun spawnPests(g: Game, portal: Portal) {
        val base = portal.npc?.tile ?: return
        val count = 2 + world.random(1 + g.lander.ordinal)
        repeat(count) {
            val (dx, dz) = when (world.random(4)) { 1 -> -3 to 2; 2 -> 2 to -3; 3 -> 4 to 1; 4 -> 1 to 4; else -> 3 to 0 }
            val key = g.lander.pests[world.random(g.lander.pests.size - 1)]
            runCatching {
                val pest = Npc(getRSCM(key), world.snapToWalkable(Tile(base.x + dx, base.z + dz, base.height), maxRadius = 3), world)
                pest.respawns = false
                world.spawn(pest)
                pest.setActive(true)
                g.spawned += pest
            }
        }
    }

    private fun spawnKnightPests(g: Game) {
        val knight = g.knight ?: return
        val key = g.lander.pests[world.random(g.lander.pests.size - 1)]
        val at = world.snapToWalkable(Tile(knight.tile.x + world.random(6) - 3, knight.tile.z + world.random(6) - 3, knight.tile.height), maxRadius = 3)
        runCatching {
            val pest = Npc(getRSCM(key), at, world)
            pest.respawns = false
            world.spawn(pest)
            pest.setActive(true)
            pest.facePawn(knight)
            g.spawned += pest
        }
    }

    /** Pests beside the Void Knight gnaw at him (npc-vs-npc combat is scripted). */
    private fun pressKnight(g: Game) {
        val knight = g.knight ?: return
        if (knight.isDead()) return
        g.spawned.forEach { pest ->
            if (pest !== knight && pest.index >= 0 && !pest.isDead() && pest.getCombatTarget() == null && pest.tile.isWithinRadius(knight.tile, 1) && g.portals.none { it.npc === pest }) {
                pest.facePawn(knight)
                pest.animate(pest.combatDef.attackAnimation)
                knight.hit(damage = 1 + world.random(4), delay = 1)
            }
        }
    }

    private fun dropRandomShield(g: Game) {
        val candidates = g.portals.filter { !it.dead && !it.shieldDropped }
        if (candidates.isEmpty()) return
        val portal = candidates[world.random(candidates.size - 1)]
        val old = portal.npc ?: return
        val hp = old.getCurrentHp()
        world.remove(old)
        g.spawned.remove(old)
        val open = spawn(g, portal.def.open, portal.def.tile)
        open.setCurrentHp(hp)
        WarNpcNames.rename(open, "${portal.def.name} portal")
        portal.npc = open
        portal.shieldDropped = true
        g.knight?.forceChat("The ${portal.def.name} Portal's Shield, has fallen.")
        g.players.forEach { it.message("<col=ff00ff>The ${portal.def.name} Portal's shield has fallen!</col>") }
    }

    private fun splat(dead: Npc) {
        dead.graphic(650, 0)
        world.players.forEach { p -> if (p.tile.height == dead.tile.height && p.tile.isWithinRadius(dead.tile, 1) && !p.isDead()) p.hit(damage = 20, delay = 0) }
    }

    private fun end(g: Game, failed: Boolean) {
        if (g.ended) return
        g.ended = true
        games.remove(g)
        val squire = getRSCM(PestControl.SQUIRE_KEY)
        g.players.toList().forEach { p ->
            val activity = p.attr[PestControl.ACTIVITY] ?: 0
            p.attr.remove(PestControl.ACTIVITY)
            p.moveTo(g.lander.exit)
            p.queue {
                when {
                    failed -> chatNpc(p, "The Void Knight has fallen, or the portals stood too long —<br>no commendations this time.", npc = squire, title = "Squire")
                    activity < PestControl.ACTIVITY_NEEDED -> chatNpc(p, "The knights noticed your lack of zeal in that battle and<br>have not presented you with any points.", npc = squire, title = "Squire")
                    else -> {
                        val pts = g.lander.points
                        p.attr[PestControl.COMMENDATIONS] = (p.attr[PestControl.COMMENDATIONS] ?: 0) + pts
                        val winsKey = when (g.lander) { PestControl.Lander.NOVICE -> PestControl.NOVICE_WINS; PestControl.Lander.INTERMEDIATE -> PestControl.INTERMEDIATE_WINS; PestControl.Lander.VETERAN -> PestControl.VETERAN_WINS }
                        p.attr[winsKey] = (p.attr[winsKey] ?: 0) + 1
                        chatNpc(p, "Congratulations! You managed to destroy all the portals!<br>We've awarded you $pts Void Knight Commendation points.<br>You now have ${p.attr[PestControl.COMMENDATIONS]}.", npc = squire, title = "Squire")
                    }
                }
            }
        }
        g.players.clear()
        g.spawned.forEach { if (it.index >= 0 && !it.isDead()) world.remove(it) }
        g.spawned.clear()
    }

    private fun leaveGame(p: Player, g: Game, message: String) {
        g.players.remove(p)
        p.attr.remove(PestControl.ACTIVITY)
        p.moveTo(g.lander.exit)
        p.message(message)
    }

    private fun gameOf(p: Player): Game? = games.firstOrNull { p in it.players }

    private fun status(p: Player) {
        val g = gameOf(p)
        if (g == null) {
            p.message("<col=0000ff>Pest Control:</col> commendations ${p.attr[PestControl.COMMENDATIONS] ?: 0}; wins novice ${p.attr[PestControl.NOVICE_WINS] ?: 0} / intermediate ${p.attr[PestControl.INTERMEDIATE_WINS] ?: 0} / veteran ${p.attr[PestControl.VETERAN_WINS] ?: 0}.")
            return
        }
        val portals = g.portals.joinToString(" ") { "${it.def.name}: ${if (it.dead) "<col=ff0000>down</col>" else if (it.shieldDropped) "<col=00ff00>${it.npc?.getCurrentHp() ?: 0}</col>" else "<col=ffff00>shielded</col>"}" }
        val activity = p.attr[PestControl.ACTIVITY] ?: 0
        p.message("<col=0000ff>Pest Control:</col> ${g.lifespan / 100} min left | Knight ${g.knight?.getCurrentHp() ?: 0} | activity ${if (activity >= PestControl.ACTIVITY_NEEDED) "<col=00ff00>$activity</col>" else "<col=ff0000>$activity</col>"} | $portals")
    }

    // ───────────────────────────── the Void Knight ─────────────────────────────

    private suspend fun QueueTask.knightTalk(p: Player) {
        val id = getRSCM(PestControl.SHOP_KNIGHT_KEY)
        chatNpc(p, "Welcome to the Void Knights' Outpost. The landers leave from the<br>docks — novice, intermediate and veteran. Bring the portals down and<br>the Order pays in commendations; I hold the armoury.", npc = id, title = "Void Knight")
        chatNpc(p, "You have ${p.attr[PestControl.COMMENDATIONS] ?: 0} commendation points.", npc = id, title = "Void Knight")
    }

    private suspend fun QueueTask.shop(p: Player) {
        val id = getRSCM(PestControl.SHOP_KNIGHT_KEY)
        val pts = p.attr[PestControl.COMMENDATIONS] ?: 0
        val page1 = PestControl.REWARDS.take(4)
        val page2 = PestControl.REWARDS.drop(4)
        val first = options(p, *page1.map { "${it.name} (${it.cost})" }.toTypedArray(), "More...", title = "Void Knight armoury — $pts points")
        val pick = when {
            first in 1..page1.size -> page1[first - 1]
            first == page1.size + 1 -> {
                val second = options(p, *page2.map { "${it.name} (${it.cost})" }.toTypedArray(), title = "Void Knight armoury — $pts points")
                page2.getOrNull(second - 1)
            }
            else -> null
        } ?: return
        buy(p, pick, id)
    }

    private suspend fun QueueTask.buy(p: Player, reward: PestControl.Reward, npcId: Int) {
        val pts = p.attr[PestControl.COMMENDATIONS] ?: 0
        if (pts < reward.cost) { chatNpc(p, "You need ${reward.cost} commendation points for that — you have $pts.", npc = npcId, title = "Void Knight"); return }
        if (p.inventory.freeSlotCount == 0) { p.message("You do not have enough space in your inventory."); return }
        val base = PestControl.ELITE_BASE[reward.key]
        if (base != null) {
            val baseId = getRSCM(base)
            if (!p.inventory.contains(baseId)) {
                chatNpc(p, "Elite void needs the plain piece to work from — bring me a ${CacheManager.getItem(baseId).name}.", npc = npcId, title = "Void Knight")
                return
            }
            p.inventory.remove(item = baseId, amount = 1)
        }
        p.attr[PestControl.COMMENDATIONS] = pts - reward.cost
        p.inventory.add(getRSCM(reward.key), 1)
        chatNpc(p, "The Order thanks you. ${reward.name} — ${pts - reward.cost} points remain.", npc = npcId, title = "Void Knight")
    }
}
