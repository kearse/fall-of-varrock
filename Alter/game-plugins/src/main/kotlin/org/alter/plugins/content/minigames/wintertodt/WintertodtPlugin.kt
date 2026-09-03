package org.alter.plugins.content.minigames.wintertodt

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.TileGraphic
import org.alter.game.model.World
import org.alter.game.model.attr.RESPAWN_TILE_ATTR
import org.alter.game.model.attr.SAFE_DEATH_ATTR
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.entity.GameObject
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.model.move.walkTo
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.CollectionLog
import org.alter.plugins.content.combat.SafeDeaths
import org.alter.plugins.content.skills.GatheringTools
import org.alter.rscm.RSCM.getRSCM
import dev.openrune.cache.CacheManager

private val logger = KotlinLogging.logger {}

/**
 * The Wintertodt round loop and every action inside the arena. See [Wintertodt] for the rules.
 */
class WintertodtPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private class Brazier(val spot: Wintertodt.BrazierSpot) {
        var obj: GameObject? = null
        var state: Int = Wintertodt.EMPTY_ID
        var pyro: Npc? = null
        var pyroHp: Int = Wintertodt.PYRO_MAX_HP
        var breaking = false
        val alive get() = pyroHp > 0
    }

    private val braziers = Wintertodt.BRAZIERS.map { Brazier(it) }
    private var storm: GameObject? = null
    private var energy = 0
    private var restTicks = Wintertodt.REST_TICKS
    private var started = false
    private var textTicks = 0

    private val active get() = restTicks <= 0

    init {
        SafeDeaths.register(Wintertodt.AREA)

        onWorldInit {
            runCatching {
                world.definitions.loadRegions(world, world.chunks, intArrayOf(Wintertodt.REGION))
                braziers.forEach { b ->
                    b.obj = world.getObject(b.spot.tile, type = 10)
                    val pyro = Npc(Wintertodt.PYROMANCER_ID, b.spot.pyroTile, world)
                    pyro.respawns = false
                    pyro.walkRadius = 0
                    world.spawn(pyro)
                    b.pyro = pyro
                }
                storm = world.getObject(Wintertodt.STORM_TILE, type = 10)
                setStorm(false)
                runCatching {
                    val ignisia = Npc(getRSCM(Wintertodt.IGNISIA_KEY), Wintertodt.IGNISIA_TILE, world)
                    ignisia.respawns = true
                    ignisia.walkRadius = 0
                    world.spawn(ignisia)
                }
                logger.info { "wintertodt: 4 braziers, 4 pyromancers posted; first round in ${Wintertodt.REST_TICKS} ticks." }
            }.onFailure { logger.error(it) { "wintertodt: world-init failed" } }
            world.queue { loop(this) }
        }

        // ── doors, roots, herbs, crates ──
        onObjOption(obj = "object.doors_of_dinh", option = "enter") { player.queue { doors(player) } }
        onObjOption(obj = "object.bruma_roots", option = "chop") { player.queue { cutRoots(player) } }
        onObjOption(obj = "object.sprouting_roots", option = "pick") { player.queue { pickHerbs(player) } }
        onObjOption(obj = "object.crate_29316", option = "take-hammer") { takeTool(player, "item.hammer", "a hammer") }
        onObjOption(obj = "object.crate_29317", option = "take-knife") { takeTool(player, "item.knife", "a knife") }
        onObjOption(obj = "object.crate_29318", option = "take-axe") { takeTool(player, "item.bronze_axe", "an axe") }
        onObjOption(obj = "object.crate_29319", option = "take-tinderbox") { takeTool(player, "item.tinderbox", "a tinderbox") }
        onObjOption(obj = "object.crate_29320", option = "take-concoction") { takeConcoctions(player, 1) }
        onObjOption(obj = "object.crate_29320", option = "take-5 concoctions") { takeConcoctions(player, 5) }
        onObjOption(obj = "object.crate_29320", option = "take-10 concoctions") { takeConcoctions(player, 10) }

        // ── braziers ──
        onObjOption(obj = Wintertodt.EMPTY_BRAZIER, option = "light") { brazierAt(player)?.let { b -> player.queue { light(player, b) } } }
        onObjOption(obj = Wintertodt.BROKEN_BRAZIER, option = "fix") { brazierAt(player)?.let { b -> player.queue { fix(player, b) } } }
        onObjOption(obj = Wintertodt.BURNING_BRAZIER, option = "feed") { brazierAt(player)?.let { b -> player.queue { feed(player, b) } } }

        // ── fletching & potions ──
        onItemOnItem("item.knife", Wintertodt.ROOT) { player.queue { fletch(player) } }
        onItemOnItem(Wintertodt.UNF_POTION, Wintertodt.HERB) { makePotion(player) }
        Wintertodt.POTIONS.forEach { pot ->
            onItemOnNpc(pot, Wintertodt.PYROMANCER_KEY) { player.getInteractingNpc().let { n -> healPyro(player, n) } }
        }
        onNpcOption(npc = Wintertodt.PYROMANCER_KEY, option = "help") { healPyro(player, player.getInteractingNpc()) }

        // ── supply crate ──
        onItemOption(item = Wintertodt.SUPPLY_CRATE, option = "open") { openCrate(player) }

        // ── presence ──
        onEnterRegion(Wintertodt.REGION) {
            player.attr[SAFE_DEATH_ATTR] = true
            if (player.attr[PREV_RESPAWN] == null) player.attr[PREV_RESPAWN] = player.attr[RESPAWN_TILE_ATTR] ?: -1
            player.attr[RESPAWN_TILE_ATTR] = Wintertodt.OUTSIDE.coordinate
            status(player)
        }
        onExitRegion(Wintertodt.REGION) { leaveState(player) }
        onLogout { if (player.tile.regionId == Wintertodt.REGION) { leaveState(player); player.moveTo(Wintertodt.OUTSIDE) } }
        onCommand("wt", description = "Wintertodt status") { status(player) }
    }

    // ───────────────────────────── the round loop (every 2 ticks) ─────────────────────────────

    private suspend fun loop(task: QueueTask) {
        while (true) {
            if (restTicks > 0) {
                restTicks -= 2
                if (restTicks <= 0) start()
            } else {
                val players = arenaPlayers()
                if (players.isNotEmpty()) {
                    coldDamage(players)
                    extinguish()
                    areaAttack(players)
                    freezePyromancers()
                    pyroText()
                    dealDamage(players)
                }
            }
            task.wait(2)
        }
    }

    private fun regionPlayers(): List<Player> {
        val out = ArrayList<Player>()
        world.players.forEach { p -> if (p.tile.regionId == Wintertodt.REGION) out += p }
        return out
    }

    private fun arenaPlayers(): List<Player> = regionPlayers().filter { !it.isDead() }

    private fun litCount(): Int = braziers.count { it.state == Wintertodt.BURNING_ID }

    private fun start() {
        setStorm(true)
        braziers.forEach { b ->
            setBrazier(b, Wintertodt.EMPTY_ID)
            if (!b.alive) revive(b)
        }
        energy = Wintertodt.MAX_ENERGY
        started = true
        regionPlayers().forEach { it.message("<col=0099ff>The Wintertodt stirs — the braziers must burn!</col>") }
    }

    private fun coldDamage(players: List<Player>) {
        val lit = litCount()
        players.forEach { p ->
            if (p.tile.z > Wintertodt.ARENA_MIN_Z - 1 && world.chance(1, 25)) {
                val dmg = world.random(Wintertodt.coldDamage(p, lit))
                if (dmg > 0) p.hit(damage = dmg, delay = 0)
                p.message("The cold of the Wintertodt seeps into your bones.")
            }
        }
    }

    private fun dealDamage(players: List<Player>) {
        var damage = 0
        braziers.forEach { b ->
            if (b.alive && b.state == Wintertodt.BURNING_ID) {
                shootFlame(b)
                damage += 5
            }
        }
        if (damage > 0) {
            val before = energy
            energy -= damage
            listOf(2625, 1750, 875).forEach { mark -> if (before > mark && energy <= mark) players.forEach { it.message("<col=0099ff>The Wintertodt's energy is down to ${energy * 100 / Wintertodt.MAX_ENERGY}%.</col>") } }
            if (energy <= 0) death()
        } else {
            energy = minOf(Wintertodt.MAX_ENERGY, energy + 5)
        }
    }

    private fun death() {
        started = false
        restTicks = Wintertodt.REST_TICKS
        setStorm(false)
        braziers.forEach { b ->
            b.pyro?.forceChat("We can rest for a time.")
            revive(b)
            setBrazier(b, Wintertodt.EMPTY_ID)
        }
        regionPlayers().forEach { award(it) }
    }

    private fun award(p: Player) {
        removeGameItems(p)
        val points = p.attr[Wintertodt.POINTS] ?: 0
        p.addXp(Skills.FIREMAKING, p.getSkills().getBaseLevel(Skills.FIREMAKING) * 100.0)
        if (points > (p.attr[Wintertodt.HIGHSCORE] ?: 0)) {
            p.attr[Wintertodt.HIGHSCORE] = points
            p.message("You have a new high score! $points")
        }
        val subdued = (p.attr[Wintertodt.SUBDUED] ?: 0) + 1
        p.attr[Wintertodt.SUBDUED] = subdued
        p.message("<col=0099ff>The Wintertodt is subdued!</col> Your subdued count is: <col=ff0000>$subdued</col>.")
        if (points >= Wintertodt.CRATE_POINTS) {
            var crates = points / Wintertodt.CRATE_POINTS
            if (world.random(Wintertodt.CRATE_POINTS - 1) < points % Wintertodt.CRATE_POINTS) crates++
            p.message(if (crates > 1) "You have gained $crates supply crates!" else "You have gained a supply crate!")
            give(p, Wintertodt.SUPPLY_CRATE, crates)
        } else {
            p.message("You did not earn enough points to be worthy of a gift from the citizens of Kourend this time.")
        }
        p.attr[Wintertodt.POINTS] = 0
    }

    private fun pyroText() {
        textTicks += 2
        if (textTicks < 8) return
        textTicks = 0
        braziers.forEach { b ->
            val pyro = b.pyro ?: return@forEach
            when {
                !b.alive -> pyro.forceChat(Wintertodt.PYRO_DOWN_TEXT[world.random(Wintertodt.PYRO_DOWN_TEXT.size - 1)])
                b.state == Wintertodt.EMPTY_ID -> pyro.forceChat("Light this brazier!")
                b.state == Wintertodt.BROKEN_ID -> pyro.forceChat("Fix this brazier!")
                world.chance(1, 4) -> pyro.forceChat("Yemalo shi cardito!")
            }
            if (b.alive && b.state == Wintertodt.BURNING_ID && world.chance(1, 3)) pyro.animate(4432)
        }
    }

    private fun freezePyromancers() {
        val up = braziers.filter { it.alive && it.pyro != null }
        if (up.isEmpty()) return
        if (world.random(up.size * 30 - 1) < up.size) {
            val b = up[world.random(up.size - 1)]
            val pyro = b.pyro ?: return
            val snow = DynamicObject(Wintertodt.SNOW_EFFECT, 10, 0, pyro.tile)
            world.spawn(snow)
            world.queue {
                wait(4)
                world.spawn(TileGraphic(pyro.tile, 502, 90))
                world.remove(snow)
                hurtPyro(b, 6 + world.random(4))
            }
        }
    }

    private fun hurtPyro(b: Brazier, dmg: Int) {
        val pyro = b.pyro ?: return
        if (!b.alive) return
        b.pyroHp = maxOf(0, b.pyroHp - dmg)
        pyro.hit(damage = minOf(dmg, maxOf(1, pyro.getCurrentHp() - 1)), delay = 0)
        if (b.pyroHp <= 0) {
            pyro.setTransmogId(Wintertodt.INCAPACITATED_ID)
            pyro.forceChat("Aargh... the cold...")
        }
    }

    private fun revive(b: Brazier) {
        val pyro = b.pyro ?: return
        b.pyroHp = Wintertodt.PYRO_MAX_HP
        pyro.setTransmogId(Wintertodt.PYROMANCER_ID)
        pyro.setCurrentHp(pyro.getMaxHp())
    }

    private fun extinguish() {
        braziers.forEach { b ->
            if (b.state != Wintertodt.BURNING_ID || b.breaking) return@forEach
            // donor: rollDie((energy + 1500) / 10, 10)
            val sides = maxOf(1, (energy + 1500) / 10)
            if (world.random(sides - 1) < 10) {
                if (world.chance(1, if (energy < Wintertodt.MAX_ENERGY / 2) 2 else 3)) breakBrazier(b)
                else {
                    world.spawn(TileGraphic(b.spot.tile, 502, 115))
                    setBrazier(b, Wintertodt.EMPTY_ID)
                }
            }
        }
    }

    private fun breakBrazier(b: Brazier) {
        b.breaking = true
        val t = b.spot.tile
        val snows = listOf(Tile(t.x + 1, t.z, 0), Tile(t.x, t.z + 1, 0), Tile(t.x + 1, t.z + 1, 0), Tile(t.x + 2, t.z + 1, 0), Tile(t.x + 1, t.z + 2, 0))
            .map { DynamicObject(Wintertodt.SNOW_EFFECT, 10, 0, it).also { o -> world.spawn(o) } }
        world.queue {
            wait(4)
            snows.forEach { world.remove(it) }
            b.breaking = false
            if (active) {
                setBrazier(b, Wintertodt.BROKEN_ID)
                world.spawn(TileGraphic(t, 502, 90))
                arenaPlayers().forEach { p ->
                    if (Tile(t.x + 1, t.z + 1, 0).isWithinRadius(p.tile, 2)) {
                        p.message("The brazier is broken and shrapnel damages you.")
                        val dmg = world.random(Wintertodt.brazierDamage(p))
                        if (dmg > 0) p.hit(damage = dmg, delay = 0)
                    }
                }
            }
        }
    }

    private fun areaAttack(players: List<Player>) {
        if (!world.chance(1, 25)) return
        val target = players[world.random(players.size - 1)]
        if (target.tile.z < Wintertodt.ARENA_MIN_Z) return
        val base = target.tile
        val tiles = listOf(base, Tile(base.x + 1, base.z + 1, 0), Tile(base.x + 1, base.z - 1, 0), Tile(base.x - 1, base.z + 1, 0), Tile(base.x - 1, base.z - 1, 0))
        val snows = tiles.map { DynamicObject(Wintertodt.SNOW_EFFECT, 10, 0, it).also { o -> world.spawn(o) } }
        world.queue {
            wait(4)
            snows.forEach { world.remove(it) }
            val marks = tiles.mapIndexed { i, t -> DynamicObject(if (i == 0) Wintertodt.SNOW_CENTRE else Wintertodt.SNOW_RING, 10, 0, t).also { o -> world.spawn(o) } }
            arenaPlayers().forEach { p ->
                if (p.tile.isWithinRadius(base, 1)) {
                    p.message("The freezing cold attack of the Wintertodt's magic hits you.")
                    val dmg = world.random(Wintertodt.areaDamage(p))
                    if (dmg > 0) p.hit(damage = dmg, delay = 0)
                }
            }
            wait(30)
            marks.forEach { world.remove(it) }
        }
    }

    private fun shootFlame(b: Brazier) {
        runCatching {
            val flame = Npc(Wintertodt.FLAME_ID, Tile(b.spot.tile.x + b.spot.flameDx, b.spot.tile.z + b.spot.flameDz, 0), world)
            flame.respawns = false
            world.spawn(flame)
            flame.walkTo(Wintertodt.CENTRE)
            world.queue { wait(10); if (flame.index >= 0) world.remove(flame) }
        }
    }

    // ───────────────────────────── objects ─────────────────────────────

    private fun setBrazier(b: Brazier, id: Int) {
        val current = b.obj ?: world.getObject(b.spot.tile, type = 10)
        if (current != null && current.id == id) { b.state = id; return }
        current?.let { world.remove(it) }
        val fresh = DynamicObject(id, 10, current?.rot ?: 0, b.spot.tile)
        world.spawn(fresh)
        b.obj = fresh
        b.state = id
    }

    private fun setStorm(activeStorm: Boolean) {
        val id = if (activeStorm) Wintertodt.ACTIVE_STORM else Wintertodt.INACTIVE_STORM
        val current = storm ?: world.getObject(Wintertodt.STORM_TILE, type = 10)
        if (current == null || current.id == id) return
        world.remove(current)
        val fresh = DynamicObject(id, 10, current.rot, Wintertodt.STORM_TILE)
        world.spawn(fresh)
        storm = fresh
    }

    private fun brazierAt(p: Player): Brazier? {
        val obj = p.getInteractingGameObj()
        return braziers.firstOrNull { it.spot.tile.sameAs(obj.tile) }
    }

    // ───────────────────────────── player actions ─────────────────────────────

    private suspend fun QueueTask.doors(p: Player) {
        val entering = p.tile.z < 3966
        if (entering) {
            if (p.getSkills().getBaseLevel(Skills.FIREMAKING) < Wintertodt.FIREMAKING_REQ) {
                p.message("You need a Firemaking level of ${Wintertodt.FIREMAKING_REQ} to assist in subduing the Wintertodt.")
                return
            }
            p.lock()
            wait(1)
            p.moveTo(Wintertodt.INSIDE)
            p.unlock()
        } else {
            if (options(p, "Leave and lose all progress.", "Stay.", title = "Are you sure you want to leave?") == 1) {
                p.lock()
                wait(1)
                p.moveTo(Wintertodt.OUTSIDE)
                p.attr[Wintertodt.POINTS] = 0
                p.unlock()
            }
        }
    }

    private fun activeCheck(p: Player): Boolean {
        if (!active) { p.message("There's no need to do that at this time."); return false }
        return true
    }

    private suspend fun QueueTask.cutRoots(p: Player) {
        if (!activeCheck(p)) return
        val held = GatheringTools.bestAxe(p)
        val axe = held.tool
        if (axe == null) {
            p.message(if (held.anyHeld) "You do not have an axe which you have the Woodcutting level to use." else "You need an axe to chop the bruma roots — there's one in the crate by the door.")
            return
        }
        if (p.inventory.freeSlotCount == 0) { p.message("Not enough space in your inventory."); return }
        val wc = p.getSkills().getBaseLevel(Skills.WOODCUTTING)
        while (p.inventory.freeSlotCount > 0 && active) {
            p.animate(axe.anim)
            wait(3)
            if (world.random(99) < 20 + (12 - GatheringToolsIndex.indexOf(axe.key)) * 3 + wc / 4) {
                p.inventory.add(getRSCM(Wintertodt.ROOT), 1)
                p.message("You get a bruma root.")
                p.addXp(Skills.WOODCUTTING, wc * 0.3)
            }
        }
        p.animate(-1)
    }

    private object GatheringToolsIndex {
        private val order = listOf("crystal", "3rd age", "infernal", "dragon", "gilded", "rune", "adamant", "mithril", "black", "steel", "iron", "bronze")
        fun indexOf(key: String) = order.indexOf(key).takeIf { it >= 0 } ?: 11
    }

    private suspend fun QueueTask.pickHerbs(p: Player) {
        if (!activeCheck(p)) return
        if (p.inventory.freeSlotCount == 0) { p.message("Not enough space in your inventory."); return }
        while (p.inventory.freeSlotCount > 0 && active) {
            p.animate(2282)
            wait(3)
            p.inventory.add(getRSCM(Wintertodt.HERB), 1)
            p.message("You pick a bruma herb.")
            p.addXp(Skills.FARMING, 1.0)
        }
        p.animate(-1)
    }

    private suspend fun QueueTask.fletch(p: Player) {
        val root = getRSCM(Wintertodt.ROOT)
        val kindling = getRSCM(Wintertodt.KINDLING)
        while (p.inventory.contains(root)) {
            p.animate(1248)
            wait(3)
            if (!p.inventory.remove(root, amount = 1).hasSucceeded()) break
            p.inventory.add(kindling, 1)
            p.addXp(Skills.FLETCHING, p.getSkills().getBaseLevel(Skills.FLETCHING) * 0.6)
        }
        p.animate(-1)
    }

    private fun makePotion(p: Player) {
        val unf = getRSCM(Wintertodt.UNF_POTION)
        val herb = getRSCM(Wintertodt.HERB)
        if (!p.inventory.contains(unf) || !p.inventory.contains(herb)) return
        if (p.inventory.remove(herb, amount = 1).hasSucceeded() && p.inventory.remove(unf, amount = 1).hasSucceeded()) {
            p.inventory.add(getRSCM(Wintertodt.POTIONS[0]), 1)
            p.addXp(Skills.HERBLORE, 1.0)
            p.message("You mix the herb into the concoction — a rejuvenation potion.")
        }
    }

    private suspend fun QueueTask.light(p: Player, b: Brazier) {
        if (!activeCheck(p)) return
        if (!p.inventory.contains(getRSCM("item.tinderbox"))) { p.message("You need a tinderbox to light that brazier."); return }
        if (!b.alive) { p.message("You must heal the Pyromancer before lighting the brazier."); return }
        p.animate(733)
        wait(3)
        if (!b.alive) { p.message("You must heal the Pyromancer before lighting the brazier."); p.animate(-1); return }
        if (b.state != Wintertodt.EMPTY_ID) { p.animate(-1); return }
        setBrazier(b, Wintertodt.BURNING_ID)
        p.animate(-1)
        p.addXp(Skills.FIREMAKING, p.getSkills().getBaseLevel(Skills.FIREMAKING) * 6.0)
        addPoints(p, Wintertodt.PTS_LIGHT)
        p.message("You light the brazier.")
    }

    private suspend fun QueueTask.fix(p: Player, b: Brazier) {
        if (!activeCheck(p)) return
        if (!p.inventory.contains(getRSCM("item.hammer"))) { p.message("You need a hammer to repair that brazier."); return }
        p.animate(3676)
        wait(3)
        if (b.state != Wintertodt.BROKEN_ID) { p.animate(-1); return }
        setBrazier(b, Wintertodt.EMPTY_ID)
        p.animate(-1)
        p.message("You fix the brazier.")
        addPoints(p, Wintertodt.PTS_FIX)
        p.addXp(Skills.CONSTRUCTION, p.getSkills().getBaseLevel(Skills.CONSTRUCTION) * 4.0)
    }

    private suspend fun QueueTask.feed(p: Player, b: Brazier) {
        if (!activeCheck(p)) return
        val root = getRSCM(Wintertodt.ROOT)
        val kindling = getRSCM(Wintertodt.KINDLING)
        if (!p.inventory.contains(root) && !p.inventory.contains(kindling)) { p.message("You don't have any bruma roots."); return }
        while (true) {
            if (b.state != Wintertodt.BURNING_ID) { p.message("The brazier has gone out."); p.animate(-1); return }
            if (!p.inventory.contains(root) && !p.inventory.contains(kindling)) { p.message("You have run out of bruma roots."); p.animate(-1); return }
            p.animate(832)
            wait(2)
            if (b.state != Wintertodt.BURNING_ID) continue
            val fm = p.getSkills().getBaseLevel(Skills.FIREMAKING)
            val base = p.getSkills().getCurrentLevel(Skills.FIREMAKING) + 5
            if (p.inventory.remove(kindling, amount = 1).hasSucceeded()) {
                p.addXp(Skills.FIREMAKING, fm * 3.8 + base)
                addPoints(p, Wintertodt.PTS_KINDLING)
            } else if (p.inventory.remove(root, amount = 1).hasSucceeded()) {
                p.addXp(Skills.FIREMAKING, fm * 3.0 + base)
                addPoints(p, Wintertodt.PTS_ROOT)
            }
            wait(2)
        }
    }

    private fun healPyro(p: Player, npc: Npc) {
        if (!activeCheck(p)) return
        val b = braziers.firstOrNull { it.pyro === npc } ?: return
        val potion = Wintertodt.POTIONS.map { getRSCM(it) }.firstOrNull { p.inventory.contains(it) }
        if (potion == null) { p.message("You'll need a rejuvenation potion to heal the Pyromancer."); return }
        if (b.alive && b.pyroHp >= Wintertodt.PYRO_MAX_HP) { p.message("The Pyromancer doesn't need any healing right now."); return }
        val idx = Wintertodt.POTIONS.map { getRSCM(it) }.indexOf(potion)
        val next = if (idx == Wintertodt.POTIONS.size - 1) getRSCM(Wintertodt.VIAL) else getRSCM(Wintertodt.POTIONS[idx + 1])
        if (!p.inventory.remove(potion, amount = 1).hasSucceeded()) return
        p.inventory.add(next, 1)
        p.animate(832)
        if (!b.alive) {
            revive(b)
            b.pyro?.forceChat("Thank you! Back to it.")
            p.message("You revive the Pyromancer.")
        } else {
            b.pyroHp = minOf(Wintertodt.PYRO_MAX_HP, b.pyroHp + 5)
            b.pyro?.setCurrentHp(minOf(b.pyro!!.getMaxHp(), b.pyro!!.getCurrentHp() + 5))
            p.message("You heal the Pyromancer.")
        }
        addPoints(p, Wintertodt.PTS_HEAL)
    }

    private fun takeTool(p: Player, key: String, name: String) {
        val id = getRSCM(key)
        when {
            p.inventory.contains(id) -> p.message("You already have $name.")
            p.inventory.freeSlotCount == 0 -> p.message("Not enough space in your inventory.")
            else -> { p.inventory.add(id, 1); p.message("You take $name from the crate.") }
        }
    }

    private fun takeConcoctions(p: Player, amount: Int) {
        if (p.inventory.freeSlotCount == 0) { p.message("Not enough space in your inventory."); return }
        val n = minOf(p.inventory.freeSlotCount, amount)
        p.inventory.add(getRSCM(Wintertodt.UNF_POTION), n)
        p.message("You take the unfinished potion${if (n > 1) "s" else ""} from the crate.")
    }

    private fun addPoints(p: Player, amount: Int) {
        val old = p.attr[Wintertodt.POINTS] ?: 0
        val now = old + amount
        p.attr[Wintertodt.POINTS] = now
        p.attr[Wintertodt.LIFETIME_POINTS] = (p.attr[Wintertodt.LIFETIME_POINTS] ?: 0) + amount
        if (old < Wintertodt.CRATE_POINTS && now >= Wintertodt.CRATE_POINTS) p.message("You have helped enough to earn a supply crate. Further work will go towards better rewards.")
    }

    private fun status(p: Player) {
        val energyPct = if (active) energy * 100 / Wintertodt.MAX_ENERGY else 0
        val lit = litCount()
        p.message(
            if (active) "<col=0099ff>Wintertodt:</col> energy $energyPct%, $lit/4 braziers lit, your points: ${p.attr[Wintertodt.POINTS] ?: 0}."
            else "<col=0099ff>Wintertodt:</col> resting — the next round begins in ${maxOf(0, restTicks) * 6 / 10} seconds. Your points: ${p.attr[Wintertodt.POINTS] ?: 0}."
        )
    }

    private fun leaveState(p: Player) {
        p.attr.remove(SAFE_DEATH_ATTR)
        p.attr[PREV_RESPAWN]?.let { prev -> if (prev >= 0) p.attr[RESPAWN_TILE_ATTR] = prev else p.attr.remove(RESPAWN_TILE_ATTR) }
        p.attr.remove(PREV_RESPAWN)
        removeGameItems(p)
        p.attr[Wintertodt.POINTS] = 0
    }

    private fun removeGameItems(p: Player) {
        Wintertodt.GAME_ITEMS.map { getRSCM(it) }.forEach { id ->
            val n = p.inventory.getItemCount(id)
            if (n > 0) p.inventory.remove(id, amount = n)
        }
    }

    // ───────────────────────────── the crate ─────────────────────────────

    private fun openCrate(p: Player) {
        val crate = getRSCM(Wintertodt.SUPPLY_CRATE)
        if (p.inventory.freeSlotCount < 2) { p.message("You need more free inventory space to open the crate."); return }
        if (!p.inventory.remove(crate, amount = 1).hasSucceeded()) return
        val fm = p.getSkills().getBaseLevel(Skills.FIREMAKING)
        val pool = Wintertodt.SUPPLIES.filter { fm >= it.minLevel }
        val total = pool.sumOf { it.weight }
        repeat(Wintertodt.CRATE_ROLLS) {
            var roll = world.random(total - 1)
            val pick = pool.first { roll -= it.weight; roll < 0 }
            give(p, pick.key, pick.min + world.random(pick.max - pick.min))
        }
        Wintertodt.UNIQUES.forEach { u ->
            if (world.chance(1, u.oneIn)) {
                give(p, u.key, 1)
                val name = CacheManager.getItem(getRSCM(u.key)).name ?: u.key
                p.message("<col=ff0000>The crate holds something rare: $name!</col>")
                if (u.key in Wintertodt.LOGGED) CollectionLog.record(p, getRSCM(u.key))
            }
        }
        p.message("You open the supply crate and sort through the citizens' gifts.")
    }

    private fun give(p: Player, key: String, amount: Int) {
        val id = runCatching { getRSCM(key) }.getOrNull() ?: return
        val added = p.inventory.add(item = id, amount = amount, assureFullInsertion = false)
        val leftover = amount - added.completed
        if (leftover > 0) world.spawn(GroundItem(id, leftover, p.tile, p))
    }

    companion object {
        private val PREV_RESPAWN = AttributeKey<Int>()
    }
}
