package org.alter.plugins.content.quests.asgarnia

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.NpcSkills
import org.alter.api.ext.message
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.attr.NO_LOOT_ATTR
import org.alter.game.model.combat.NpcCombatDef
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.plugins.content.combat.getCombatTarget
import org.alter.plugins.content.combat.isAttacking
import org.alter.plugins.content.combat.removeCombatTarget
import org.alter.plugins.content.quests.framework.EndReason
import org.alter.plugins.content.quests.framework.QuestEngine
import org.alter.plugins.content.quests.framework.QuestInstance
import org.alter.plugins.content.quests.framework.QuestInstances
import org.alter.plugins.content.quests.framework.Reward
import org.alter.plugins.content.war.WarNpcNames
import org.alter.plugins.content.war.artillery.CannonEmplacement
import org.alter.plugins.content.war.artillery.DwarfCannon
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/** The quest npc's owner (username) — a raider in the road cell or the workshop fight. */
internal val GUNS_QUEST_NPC_ATTR = AttributeKey<String>()

/** The stock Black Knight (516, level 33, has Attack) renamed at spawn — the Kinshra of the campaign. */
internal const val KINSHRA_NPC = "npc.black_knight"

/** Kinshra raiders: a real fight for a mid-story account, six at once, without a march's beef. */
internal val KINSHRA_RAIDER_DEF: NpcCombatDef = NpcCombatDef.DEFAULT.copy(
    attack = 60, strength = 55, defence = 55, hitpoints = 75,
    attackSpeed = 5, attackAnimation = 390, blockAnimation = 1156, deathAnimation = listOf(836),
    aggressiveRadius = 8, aggroTargetDelay = 4, aggressiveTimer = 400,
)

/** The cell's captain — "a slightly stronger existing variant": the same knight, harder. */
internal val KINSHRA_CAPTAIN_DEF: NpcCombatDef = KINSHRA_RAIDER_DEF.copy(attack = 75, strength = 65, defence = 70, hitpoints = 120)

/** Nulodion's yard guards (the Dwarven Mine's stock "Dwarf", 1401): sturdy, never aggressive to players. */
internal val YARD_GUARD_DEF: NpcCombatDef = NpcCombatDef.DEFAULT.copy(
    attack = 50, strength = 45, defence = 55, hitpoints = 80,
    attackSpeed = 4, attackAnimation = 99, blockAnimation = 100, deathAnimation = listOf(102),
    aggressiveRadius = 0,
)

/** Re-apply a combat def AFTER `world.spawn` (spawning resets combatDef + HP to the cache default). */
internal fun applyDef(npc: Npc, d: NpcCombatDef) {
    npc.combatDef = d
    npc.stats.setMaxLevel(NpcSkills.ATTACK, d.attack); npc.stats.setCurrentLevel(NpcSkills.ATTACK, d.attack)
    npc.stats.setMaxLevel(NpcSkills.STRENGTH, d.strength); npc.stats.setCurrentLevel(NpcSkills.STRENGTH, d.strength)
    npc.stats.setMaxLevel(NpcSkills.DEFENCE, d.defence); npc.stats.setCurrentLevel(NpcSkills.DEFENCE, d.defence)
    npc.setCurrentHp(d.hitpoints)
}

private fun Npc.alive(): Boolean = index >= 0 && !isDead()

/**
 * **The road encounter** — the Kinshra cell disrupting the surface end of the Keldagrim steel route
 * (design §22-24): five raiders and a captain camped in the trees west of Nulodion's yard. A
 * temporary PERSONAL spawn in the shared world: spawned when the player reaches the ROUTE step,
 * respawned (minus the dead) on login, removed on logout or when the step clears. Loot-less and
 * aggressive only to their owner, so a passer-by visiting Nulodion is not jumped by someone else's
 * quest. Anyone may help kill them — the step counts deaths, not who dealt them.
 */
object KinshraRoute {

    private class Cell(var owner: Player) {
        val npcs = ArrayList<Npc>()
        var captainAlive = false
    }

    private val cells = HashMap<String, Cell>()

    private fun key(p: Player) = p.username.lowercase()

    /** Spawn (or top up) [p]'s cell to the size the step still owes. Safe to call repeatedly. */
    fun ensure(p: Player) {
        if (QuestEngine.stepId(p, GunsOfAsgarnia) != GunsOfAsgarnia.ROUTE) return
        val cell = cells.getOrPut(key(p)) { Cell(p) }
        cell.owner = p
        cell.npcs.removeAll { !it.alive() }
        val dead = QuestEngine.counter(p, GunsOfAsgarnia)
        val want = (GunsOfAsgarnia.ROUTE_CELL_SIZE - dead).coerceAtLeast(0)
        val have = cell.npcs.size
        if (have >= want) return
        val world = p.world
        var toSpawn = want - have
        // The captain comes back only if he has not fallen yet (the "captain" counter).
        if (!cell.captainAlive && QuestEngine.counter(p, GunsOfAsgarnia, CAPTAIN_COUNTER) == 0 && toSpawn > 0) {
            spawn(world, p, GunsOfAsgarnia.ROUTE_CAPTAIN_TILE, KINSHRA_CAPTAIN_DEF, CAPTAIN_NAME)?.let { cell.npcs += it; cell.captainAlive = true; toSpawn-- }
        }
        val taken = cell.npcs.map { it.tile }.toSet()
        for (t in GunsOfAsgarnia.ROUTE_RAIDER_TILES) {
            if (toSpawn <= 0) break
            if (t in taken) continue
            spawn(world, p, t, KINSHRA_RAIDER_DEF, RAIDER_NAME)?.let { cell.npcs += it; toSpawn-- }
        }
        if (cell.npcs.isNotEmpty()) p.message("<col=cc2222>Kinshra voices in the trees west of the yard - the raiding cell is camped on the route.</col>")
    }

    private fun spawn(world: World, owner: Player, tile: Tile, def: NpcCombatDef, name: String): Npc? = runCatching {
        val npc = Npc(getRSCM(KINSHRA_NPC), world.snapToWalkable(tile, maxRadius = 2), world)
        npc.respawns = false
        npc.walkRadius = 2
        npc.lastFacingDirection = Direction.EAST
        npc.attr[NO_LOOT_ATTR] = true
        npc.attr[GUNS_QUEST_NPC_ATTR] = key(owner)
        world.spawn(npc)
        applyDef(npc, def)
        val ownerName = key(owner)
        npc.aggroCheck = { _, player -> player.username.equals(ownerName, ignoreCase = true) }
        npc.setActive(true)
        WarNpcNames.rename(npc, name)
        npc
    }.onFailure { logger.error(it) { "[guns] Kinshra raider failed to spawn at $tile for ${owner.username}" } }.getOrNull()

    /** Remove whatever is left of [p]'s cell (logout, step cleared, reset). */
    fun despawn(p: Player) {
        val cell = cells.remove(key(p)) ?: return
        cell.npcs.forEach { n -> if (n.alive()) runCatching { p.world.remove(n) } }
        cell.npcs.clear()
    }

    /** The additive npc-death hook: is this one of ours? Count it for its owner. */
    fun onNpcDeath(npc: Npc): Boolean {
        val owner = npc.attr[GUNS_QUEST_NPC_ATTR] ?: return false
        val cell = cells[owner] ?: return false
        if (!cell.npcs.remove(npc)) return false
        val p = cell.owner
        if (npc.combatDef === KINSHRA_CAPTAIN_DEF || cell.captainAlive && npc.combatDef.hitpoints == KINSHRA_CAPTAIN_DEF.hitpoints) {
            cell.captainAlive = false
            if (p.isOnline) QuestEngine.addCounter(p, GunsOfAsgarnia, CAPTAIN_COUNTER)
        }
        if (!p.isOnline || QuestEngine.stepId(p, GunsOfAsgarnia) != GunsOfAsgarnia.ROUTE) return true
        val n = QuestEngine.addCounter(p, GunsOfAsgarnia)
        if (n >= GunsOfAsgarnia.ROUTE_CELL_SIZE) {
            cells.remove(owner)
            p.message("<col=801700>The Kinshra raiding position has been cleared.</col>")
            p.message("With regular Asgarnian patrols, shipments between the Dwarven Mine and Falador could move safely again.")
        } else {
            p.message("<col=801700>${GunsOfAsgarnia.NAME}:</col> $n/${GunsOfAsgarnia.ROUTE_CELL_SIZE} of the raiding cell down.")
        }
        return true
    }

    private const val CAPTAIN_COUNTER = "captain"
    private const val RAIDER_NAME = "Kinshra Raider"
    private const val CAPTAIN_NAME = "Kinshra Raid Captain"
}

/**
 * **The workshop defence** (design §39-45) — a private copy of Nulodion's yard: Nulodion at his
 * bench, two Dwarven Mine guards, the newly finished gun on its stand ([CannonEmplacement]) and six
 * Kinshra in two waves. The player loads the gun with the thirty cannonballs Nulodion presses on
 * them (Fire on the cannon) and fights beside it; the gun assists, it does not win the fight alone.
 * Ends COMPLETE when the sixth Kinshra falls; a death, a logout, walking out or ten minutes end it
 * without credit, and Nulodion restarts it on request. Leftover cannonballs go back to the dwarf.
 */
object WorkshopDefence {

    private class Fight(val owner: Player, val qi: QuestInstance, val cannon: CannonEmplacement) {
        val knights = ArrayList<Npc>()
        val guards = ArrayList<Npc>()
        var nulodion: Npc? = null
        var wave1In = WAVE1_DELAY
        var wave2In = WAVE2_DELAY
        var wave1 = false
        var wave2 = false
        var complete = false
        var endIn = -1
        var shots = 0
    }

    private val live = HashMap<String, Fight>()

    private fun key(p: Player) = p.username.lowercase()

    fun isLive(p: Player): Boolean = live[key(p)]?.let { !it.qi.ended } ?: false

    /** Open the private yard for [p] and start the attack. False if the instance space is full. */
    fun start(p: Player): Boolean {
        if (isLive(p)) return true
        val world = p.world
        val landing = if (GunsOfAsgarnia.WORKSHOP_AREA.contains(p.tile) && p.tile.height == 0) p.tile else GunsOfAsgarnia.WORKSHOP_LANDING
        val qi = QuestInstances.enter(
            p, GunsOfAsgarnia.WORKSHOP_AREA, exit = GunsOfAsgarnia.WORKSHOP_LANDING, landing = landing,
            timeoutTicks = TIMEOUT_TICKS,
            onTick = { tick(it) },
            onEnd = { inst, reason -> end(inst, reason) },
        ) ?: return false

        // Fresh fight: the counter is the kill tally the client shows (n/6).
        val s = QuestEngine.counter(p, GunsOfAsgarnia)
        if (s != 0) QuestEngine.addCounter(p, GunsOfAsgarnia, delta = -s)

        val fight = Fight(p, qi, CannonEmplacement(world, qi.translate(GunsOfAsgarnia.WORKSHOP_CANNON_TILE), owner = p) { live[key(p)]?.knights ?: emptyList() })
        live[key(p)] = fight

        fight.nulodion = qi.spawnNpc(GunsOfAsgarnia.NULODION, GunsOfAsgarnia.WORKSHOP_NULODION_TILE)?.also { n ->
            n.walkRadius = 0
            n.aggroCheck = { _, _ -> false }
            n.faceTile(qi.translate(GunsOfAsgarnia.WORKSHOP_CANNON_TILE))
        }
        GunsOfAsgarnia.WORKSHOP_GUARD_TILES.forEach { t ->
            qi.spawnNpc(GUARD_NPC, t, name = GUARD_NAME)?.also { g ->
                applyDef(g, YARD_GUARD_DEF)
                g.walkRadius = 0
                g.aggroCheck = { _, _ -> false }
                fight.guards += g
            }
        }
        if (!fight.cannon.place()) p.message("<col=cc2222>The gun's stand is empty - the cache has no multicannon. Fight on; Nulodion will manage.</col>")
        fight.cannon.onShot = { _, damage -> onShot(fight, damage) }

        Reward.giveItem(p, DwarfCannon.CANNONBALL_KEY, GunsOfAsgarnia.WORKSHOP_CANNONBALLS)
        p.message("<col=801700>Nulodion presses a sack of ${GunsOfAsgarnia.WORKSHOP_CANNONBALLS} cannonballs into your hands. Fire the cannon to load it.</col>")
        return true
    }

    private fun spawnKnight(fight: Fight, src: Tile, def: NpcCombatDef, name: String) {
        val npc = fight.qi.spawnNpc(KINSHRA_NPC, src, name = name) ?: return
        applyDef(npc, def)
        npc.walkRadius = 3
        npc.attr[GUNS_QUEST_NPC_ATTR] = key(fight.owner)
        val ownerName = key(fight.owner)
        npc.aggroCheck = { _, player -> player.username.equals(ownerName, ignoreCase = true) }
        fight.knights += npc
    }

    private fun tick(qi: QuestInstance) {
        val fight = live[key(qi.owner)] ?: return
        if (fight.qi !== qi || qi.ended) return
        val p = fight.owner

        if (!fight.wave1 && --fight.wave1In <= 0) {
            fight.wave1 = true
            GunsOfAsgarnia.WORKSHOP_WAVE1_TILES.forEachIndexed { i, t ->
                spawnKnight(fight, t, if (i == 0) KINSHRA_CAPTAIN_DEF else KINSHRA_RAIDER_DEF, if (i == 0) "Kinshra Saboteur Captain" else "Kinshra Saboteur")
            }
            fight.nulodion?.forceChat("Kinshra! Through the west gap!")
            p.message("<col=cc2222>Kinshra saboteurs come through the yard's west opening.</col>")
        } else if (fight.wave1 && !fight.wave2 && --fight.wave2In <= 0) {
            fight.wave2 = true
            GunsOfAsgarnia.WORKSHOP_WAVE2_TILES.forEach { t -> spawnKnight(fight, t, KINSHRA_RAIDER_DEF, "Kinshra Saboteur") }
            fight.nulodion?.forceChat("More of them - round the mine!")
            p.message("<col=cc2222>A second party climbs through the fence gaps to the north.</col>")
        }

        runCatching { fight.cannon.tick() }.onFailure { logger.error(it) { "[guns] cannon tick failed" } }
        skirmish(fight)

        if (fight.endIn > 0 && --fight.endIn == 0) qi.end(EndReason.COMPLETE)
    }

    /** The yard guards strike the nearest living Kinshra near them; explicit attack only, never engine aggro. */
    private fun skirmish(fight: Fight) {
        for (g in fight.guards) {
            if (!g.alive()) continue
            val cur = g.getCombatTarget() as? Npc
            if (g.isAttacking() && cur != null && cur.alive()) continue
            val foe = fight.knights.filter { it.alive() && it.tile.getChebyshevDistance(g.tile) <= GUARD_REACH }.minByOrNull { it.tile.getChebyshevDistance(g.tile) }
            if (foe != null) g.attack(foe) else if (g.isAttacking()) { g.removeCombatTarget(); g.resetFacePawn() }
        }
    }

    /** §44: the first two shots get their lines. */
    private fun onShot(fight: Fight, damage: Int) {
        fight.shots++
        when (fight.shots) {
            1 -> { fight.owner.forceChat("That works."); fight.nulodion?.forceChat("Of course it works.") }
            2 -> fight.nulodion?.forceChat("I'm a dwarf.")
        }
        if (damage > 0 && fight.shots <= 2) fight.owner.message("The cannon's shot lands.")
    }

    /** The additive npc-death hook: a Kinshra of ours fell in someone's yard. */
    fun onNpcDeath(npc: Npc): Boolean {
        val owner = npc.attr[GUNS_QUEST_NPC_ATTR] ?: return false
        val fight = live[owner] ?: return false
        if (!fight.knights.remove(npc)) return false
        val p = fight.owner
        if (!p.isOnline || QuestEngine.stepId(p, GunsOfAsgarnia) != GunsOfAsgarnia.SABOTAGE) return true
        val n = QuestEngine.addCounter(p, GunsOfAsgarnia)
        if (n >= GunsOfAsgarnia.WORKSHOP_KNIGHTS) {
            fight.complete = true
            fight.endIn = END_DELAY
            p.message("<col=801700>The Kinshra sabotage force has been defeated.</col>")
            p.message("<col=801700>Asgarnia's first replacement multicannon has survived its field test.</col>")
            fight.nulodion?.forceChat("Still standing. Both of us.")
        } else {
            p.message("<col=801700>${GunsOfAsgarnia.NAME}:</col> $n/${GunsOfAsgarnia.WORKSHOP_KNIGHTS} saboteurs down.")
        }
        return true
    }

    private fun end(qi: QuestInstance, reason: EndReason) {
        val k = key(qi.owner)
        val fight = live[k] ?: return
        if (fight.qi !== qi) return
        live.remove(k)
        fight.cannon.remove()
        fight.knights.clear(); fight.guards.clear(); fight.nulodion = null
        val p = fight.owner
        // Nulodion takes his cannonballs back — they were never the reward.
        runCatching {
            val id = getRSCM(DwarfCannon.CANNONBALL_KEY)
            val have = p.inventory.getItemCount(id)
            if (have > 0) p.inventory.remove(id, minOf(have, GunsOfAsgarnia.WORKSHOP_CANNONBALLS))
        }
        if (!p.isOnline) return
        if (reason == EndReason.COMPLETE || fight.complete) {
            p.message("Nulodion collects the spare cannonballs. \"Those are for the next lot.\"")
        } else {
            p.message("<col=cc2222>The Kinshra pull back into the trees.</col> Speak to Nulodion at the yard when you are ready - they will come again.")
        }
    }

    private const val GUARD_NPC = "npc.dwarf_1401"
    private const val GUARD_NAME = "Dwarven Mine guard"
    private const val WAVE1_DELAY = 8      // ~5 s: the alarm lines are read before the first blade lands
    private const val WAVE2_DELAY = 30     // ~18 s after wave one
    private const val END_DELAY = 4
    private const val TIMEOUT_TICKS = 1000 // 10 minutes
    private const val GUARD_REACH = 6
}
