package org.alter.plugins.content.quests.asgarnia

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.NpcSkills
import org.alter.api.ext.chatNpc
import org.alter.api.ext.chatPlayer
import org.alter.api.ext.message
import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.combat.NpcCombatDef
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.move.walkTo
import org.alter.game.model.queue.QueueTask
import org.alter.plugins.content.combat.getCombatTarget
import org.alter.plugins.content.combat.isAttacking
import org.alter.plugins.content.combat.removeCombatTarget
import org.alter.plugins.content.quests.framework.EndReason
import org.alter.plugins.content.quests.framework.QuestEngine
import org.alter.plugins.content.quests.framework.QuestInstance
import org.alter.plugins.content.quests.framework.QuestInstances
import org.alter.rscm.RSCM.getRSCM
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.max

private val logger = KotlinLogging.logger {}

/**
 * **The Battle of the Pass** — A Matter of Trolls' coalition battle: a private copy of Death
 * Plateau ([QuestInstances] over the stock map, unchanged) where the Imperial Guard holds the
 * plateau's north-east gap (the only way down to Burthorpe), the splinter warband comes at them in
 * staged waves from the west of the plateau, My Arm's Stronghold trolls climb in over the southern
 * cliffs to hit the flank, Snowflake's Weiss fighters come down from the north-western heights to
 * close the ring, and the war-chief enters once his warband is thinned. Every npc is a stock model
 * (soldiers, mountain trolls, ice trolls, a troll general, My Arm, Snowflake) renamed at spawn.
 *
 * The player's own objective is five warband trolls (credited to anyone who damaged them — see
 * [AMatterOfTrollsPlugin]); allied kills advance the ENCOUNTER (wave triggers count every hostile
 * death) but not the player's requirement. The war-chief's death is the victory whoever lands it.
 *
 * Ally/enemy behaviour is the `HostileZone`/`GoblinCampPlugin` skirmish loop scoped to one
 * instance: enemies aggro the owner (engine aggro, dead-simple `aggroCheck`) and fight back any ally
 * in reach, otherwise press toward the guard line; allies engage the nearest enemy in reach,
 * otherwise walk to their group's goal. All of it runs inside the guarded instance tick — never the
 * engine's aggro path.
 *
 * Failure (death, leaving, timeout, logout) ends the instance and puts the quest back on READY so
 * Denulth can send the player up again; nothing is lost but the attempt.
 */
object BattleOfThePass {

    /** Tags warband npcs; [OWNER] is the instance owner (kill credit + aggro target). */
    val HOSTILE = AttributeKey<Boolean>()
    val CHIEF = AttributeKey<Boolean>()
    val OWNER = AttributeKey<WeakReference<Player>>()

    /** The stock Death Plateau ground: the plateau, its north band and the southern cliff lobe (4×4 chunks). */
    val AREA = Area(2848, 3576, 2879, 3607)

    /** Where the player lands: beside the guard line at the north-east gap. */
    val LANDING = Tile(2874, 3597, 0)

    const val TIMEOUT_TICKS = 1500
    const val PLAYER_KILLS = 5

    // ---- positions (source coordinates; verified walkable on the region 11319/11320 collision dumps) ----

    private val GUARD_POSTS = listOf(
        Tile(2871, 3597, 0), Tile(2873, 3598, 0), Tile(2875, 3597, 0),
        Tile(2877, 3598, 0), Tile(2874, 3595, 0), Tile(2876, 3595, 0),
    )
    private val GUARD_LINE = Tile(2873, 3597, 0)

    /** The warband's muster ground: the plateau's west half. */
    private val WAVE_1 = listOf(Tile(2856, 3588, 0), Tile(2859, 3590, 0), Tile(2856, 3592, 0), Tile(2860, 3594, 0), Tile(2862, 3588, 0), Tile(2853, 3586, 0))
    private val WAVE_2 = listOf(Tile(2857, 3594, 0), Tile(2860, 3596, 0), Tile(2855, 3597, 0), Tile(2862, 3597, 0), Tile(2858, 3592, 0))
    private val WAVE_3 = listOf(Tile(2856, 3590, 0), Tile(2860, 3591, 0), Tile(2865, 3592, 0), Tile(2857, 3595, 0))

    /** My Arm's flank: the southern cliff lobe below the plateau (they climb up the cliffs). */
    private val FLANK = listOf(Tile(2870, 3580, 0), Tile(2873, 3580, 0), Tile(2876, 3580, 0), Tile(2871, 3579, 0), Tile(2875, 3579, 0))
    private val FLANK_GOAL = Tile(2864, 3590, 0)

    /** Snowflake's fighters: the north-western heights of the plateau. */
    private val NORTH = listOf(Tile(2854, 3598, 0), Tile(2856, 3597, 0), Tile(2853, 3599, 0), Tile(2858, 3599, 0), Tile(2855, 3596, 0))
    private val NORTH_GOAL = Tile(2862, 3592, 0)

    private val CHIEF_SPAWN = Tile(2872, 3595, 0)
    private val CHIEF_GUARD = listOf(Tile(2870, 3597, 0), Tile(2874, 3597, 0))
    private val REINFORCEMENTS = listOf(Tile(2858, 3596, 0), Tile(2856, 3593, 0))

    /** Denulth comes up through the gap for the epilogue. */
    private val DENULTH_ARRIVES = Tile(2874, 3602, 0)
    private val DENULTH_STANDS = Tile(2873, 3599, 0)

    // ---- tuning ----
    private const val AFTER_VICTORY_TICKS = 150
    private const val HOSTILE_ENGAGE = 10
    private const val ALLY_ENGAGE = 12
    private const val CHIEF_CALLS = 2
    private const val CHIEF_CALL_EVERY = 40

    fun isHostileOf(p: Player, npc: Npc): Boolean = npc.attr[HOSTILE] == true && npc.attr[OWNER]?.get() === p

    /** Open the pass for [p] (the BATTLE step's onEnter). Falls back to READY if the instance space is full. */
    fun open(p: Player) {
        val battle = Battle(p)
        val qi = QuestInstances.enter(
            p, sourceArea = AREA, exit = AMatterOfTrolls.BURTHORPE_CAMP, landing = LANDING, timeoutTicks = TIMEOUT_TICKS,
            onTick = { battle.tick() },
            onEnd = { _, reason -> battle.end(reason) },
        )
        if (qi == null) {
            QuestEngine.advanceTo(p, AMatterOfTrolls, AMatterOfTrolls.READY)
            return
        }
        battle.begin(qi)
    }

    private fun defOf(world: World, npcKey: String): NpcCombatDef =
        runCatching { world.plugins.npcCombatDefs.getOrDefault(getRSCM(npcKey), null) }.getOrNull() ?: NpcCombatDef.DEFAULT

    private fun dist(a: Tile, b: Tile): Int = max(abs(a.x - b.x), abs(a.z - b.z))

    private class Ally(val npc: Npc, val goal: Tile?)

    private class Battle(val owner: Player) {
        lateinit var qi: QuestInstance
        val world: World get() = owner.world

        var ticks = 0
        var phase = 0
        var ended = false
        var victoryAt = -1

        val hostiles = ArrayList<Npc>()
        var hostilesSpawned = 0
        val allies = ArrayList<Ally>()
        var chief: Npc? = null
        var chiefCalls = 0
        var nextCallAt = 0
        var myArm: Npc? = null
        var snowflake: Npc? = null

        /** Scheduled beats (overhead lines, spawns) — (due tick, action). */
        private val pending = ArrayList<Pair<Int, () -> Unit>>()

        private fun at(delay: Int, action: () -> Unit) { pending += (ticks + delay) to action }

        private lateinit var trollDef: NpcCombatDef
        private lateinit var guardLine: Tile

        /** Warband npcs aggro the instance owner and nobody else; allies never aggro a player. */
        private val aggroOwnerOnly: (Npc, Player) -> Boolean = { _, pl -> pl === owner }
        private val aggroNone: (Npc, Player) -> Boolean = { _, _ -> false }

        fun begin(qi: QuestInstance) {
            this.qi = qi
            trollDef = defOf(world, AMatterOfTrolls.MOUNTAIN_TROLL)
            guardLine = qi.translate(GUARD_LINE)
            spawnGuards()
            owner.message("<col=801700>The Battle of the Pass.</col> The Imperial Guard holds the gap — the only way down to Burthorpe. The warband is coming.")
            owner.message("Hold with the Guard until the trolls arrive. Any warband troll you have hit counts when it falls.")
        }

        // ---- spawning ------------------------------------------------------------------------------

        private fun arm(npc: Npc, base: NpcCombatDef, atk: Int, str: Int, def: Int, hp: Int, hostile: Boolean) {
            npc.combatDef = if (hostile) {
                base.copy(attack = atk, strength = str, defence = def, hitpoints = hp, aggressiveRadius = 10, aggroTargetDelay = 4, aggressiveTimer = 500)
            } else {
                base.copy(attack = atk, strength = str, defence = def, hitpoints = hp, aggressiveRadius = 0)
            }
            npc.stats.setMaxLevel(NpcSkills.ATTACK, atk); npc.stats.setCurrentLevel(NpcSkills.ATTACK, atk)
            npc.stats.setMaxLevel(NpcSkills.STRENGTH, str); npc.stats.setCurrentLevel(NpcSkills.STRENGTH, str)
            npc.stats.setMaxLevel(NpcSkills.DEFENCE, def); npc.stats.setCurrentLevel(NpcSkills.DEFENCE, def)
            npc.setCurrentHp(hp)
            npc.routeLogic = 1
            npc.walkRadius = 0
            // Dead-simple aggro checks: the engine's aggro path is not guarded, so nothing here may throw.
            npc.aggroCheck = if (hostile) aggroOwnerOnly else aggroNone
        }

        private fun spawnHostile(src: Tile, name: String = "Warband troll"): Npc? {
            val npc = qi.spawnNpc(AMatterOfTrolls.MOUNTAIN_TROLL, src, name = name) ?: return null
            arm(npc, trollDef, atk = 55, str = 62, def = 42, hp = 85, hostile = true)
            npc.attr[HOSTILE] = true
            npc.attr[OWNER] = WeakReference(owner)
            hostiles += npc
            hostilesSpawned++
            return npc
        }

        private fun spawnAlly(key: String, src: Tile, name: String, base: NpcCombatDef, atk: Int, str: Int, def: Int, hp: Int, goal: Tile?): Npc? {
            val npc = qi.spawnNpc(key, src, name = name) ?: return null
            arm(npc, base, atk, str, def, hp, hostile = false)
            allies += Ally(npc, goal?.let { qi.translate(it) })
            return npc
        }

        private fun spawnGuards() {
            val base = NpcCombatDef.DEFAULT.copy(attackAnimation = 407)
            for (post in GUARD_POSTS) {
                spawnAlly(AMatterOfTrolls.SOLDIER, post, "Imperial Guard", base, atk = 55, str = 50, def = 55, hp = 60, goal = post)
            }
        }

        private fun wave(tiles: List<Tile>) {
            for (t in tiles) spawnHostile(t)
        }

        private fun arrivalMyArm() {
            owner.message("<col=801700>Trolls come up over the southern cliffs — My Arm's Stronghold fighters hit the warband's flank!</col>")
            myArm = spawnAlly(AMatterOfTrolls.MY_ARM, FLANK[0], "My Arm", trollDef, atk = 80, str = 85, def = 70, hp = 160, goal = FLANK_GOAL)
            for (i in 1 until FLANK.size) {
                spawnAlly(AMatterOfTrolls.MOUNTAIN_TROLL, FLANK[i], "Stronghold troll", trollDef, atk = 60, str = 65, def = 50, hp = 90, goal = FLANK_GOAL)
            }
            at(1) { myArm?.forceChat("My Arm here!") }
            at(3) { owner.forceChat("Excellent timing!") }
            at(5) { myArm?.forceChat("My Arm was thinking!") }
            at(7) { owner.forceChat("Of course you were!") }
        }

        private fun arrivalSnowflake() {
            owner.message("<col=801700>Snowflake and the Weiss fighters come down from the north-western heights — the warband is surrounded!</col>")
            val female = defOf(world, AMatterOfTrolls.ICE_TROLL_FEMALE)
            val male = defOf(world, AMatterOfTrolls.ICE_TROLL_MALE)
            snowflake = spawnAlly(AMatterOfTrolls.SNOWFLAKE, NORTH[0], "Snowflake", female, atk = 80, str = 85, def = 70, hp = 160, goal = NORTH_GOAL)
            for (i in 1 until NORTH.size) {
                val (key, base) = if (i % 2 == 0) AMatterOfTrolls.ICE_TROLL_FEMALE to female else AMatterOfTrolls.ICE_TROLL_MALE to male
                spawnAlly(key, NORTH[i], "Weiss troll", base, atk = 65, str = 70, def = 45, hp = 85, goal = NORTH_GOAL)
            }
            at(1) { snowflake?.forceChat("Push them down!") }
            // Some of the warband break rather than die — the story outcome is a collapse, not an extermination.
            at(4) {
                val fleeing = hostiles.filter { alive(it) && it.attr[CHIEF] != true }
                    .sortedByDescending { dist(it.tile, owner.tile) }
                    .take(2)
                if (fleeing.isNotEmpty()) owner.message("Some of the warband turn and run for the rocks.")
                for (n in fleeing) n.forceChat("Run!")
                at(3) { for (n in fleeing) discard(n) }
            }
        }

        private fun chiefArrives() {
            owner.message("<col=801700>The War-chief comes onto the plateau, and what is left of the warband rallies to him.</col>")
            val c = qi.spawnNpc(AMatterOfTrolls.WAR_CHIEF_MODEL, CHIEF_SPAWN, name = "Troll War-chief")
            if (c != null) {
                arm(c, trollDef.copy(attackSpeed = 5), atk = 105, str = 100, def = 85, hp = 320, hostile = true)
                c.attr[HOSTILE] = true
                c.attr[CHIEF] = true
                c.attr[OWNER] = WeakReference(owner)
                hostiles += c
                hostilesSpawned++
                chief = c
            }
            for (t in CHIEF_GUARD) spawnHostile(t)
            nextCallAt = ticks + CHIEF_CALL_EVERY
            at(1) { chief?.forceChat("Humans make trolls weak!") }
            at(3) { myArm?.forceChat("No.") }
            at(5) { myArm?.forceChat("Stupid make trolls weak.") }
            at(7) { owner.forceChat("He has a point.") }
        }

        private fun chiefCalls() {
            val c = chief ?: return
            if (!alive(c) || chiefCalls >= CHIEF_CALLS || ticks < nextCallAt) return
            chiefCalls++
            nextCallAt = ticks + CHIEF_CALL_EVERY
            c.forceChat("TROLLS! TO ME!")
            for (t in REINFORCEMENTS) spawnHostile(t)
        }

        // ---- the tick -----------------------------------------------------------------------------

        fun tick() {
            if (ended) return
            ticks++
            runPending()
            if (victoryAt >= 0) {
                if (ticks - victoryAt >= AFTER_VICTORY_TICKS) qi.end(EndReason.COMPLETE)
                return
            }
            when (phase) {
                0 -> if (ticks >= 4) {
                    phase = 1
                    wave(WAVE_1)
                    owner.message("<col=801700>The warband comes across the plateau!</col>")
                    at(1) { allies.firstOrNull()?.npc?.forceChat("Hold the line!") }
                }
                1 -> if (deaths() >= 4 || ticks >= 250) {
                    phase = 2
                    wave(WAVE_2)
                    arrivalMyArm()
                }
                2 -> if (deaths() >= 9 || ticks >= 450) {
                    phase = 3
                    wave(WAVE_3)
                    arrivalSnowflake()
                }
                3 -> if ((deaths() >= 12 && playerObjectiveMet()) || ticks >= 700) {
                    phase = 4
                    chiefArrives()
                }
                4 -> {
                    chiefCalls()
                    val c = chief
                    if (c == null || !alive(c)) victory()
                }
            }
            if (ticks % 2 == 0) skirmish()
        }

        private fun runPending() {
            if (pending.isEmpty()) return
            val due = pending.filter { it.first <= ticks }
            if (due.isEmpty()) return
            pending.removeAll(due)
            for ((_, action) in due) runCatching(action).onFailure { logger.error(it) { "Battle of the Pass beat failed (${owner.username})" } }
        }

        private fun deaths(): Int = hostilesSpawned - hostiles.count { alive(it) }

        /** The player's own five: satisfied once they are past the BATTLE step (or its counter is full). */
        private fun playerObjectiveMet(): Boolean {
            val step = QuestEngine.stepId(owner, AMatterOfTrolls) ?: return true
            if (step != AMatterOfTrolls.BATTLE) return true
            return QuestEngine.counter(owner, AMatterOfTrolls) >= PLAYER_KILLS
        }

        private fun alive(npc: Npc): Boolean = npc.index >= 0 && world.npcs.contains(npc) && !npc.isDead()

        private fun alivePawn(pawn: Pawn): Boolean = when (pawn) {
            is Npc -> alive(pawn)
            is Player -> pawn.index >= 0 && !pawn.isDead()
            else -> false
        }

        private fun discard(npc: Npc) {
            if (npc.index >= 0 && world.npcs.contains(npc)) { npc.setCurrentHp(0); world.remove(npc) }
        }

        private fun nearest(from: Tile, candidates: List<Npc>, range: Int): Npc? {
            var best: Npc? = null
            var bestDist = Int.MAX_VALUE
            for (c in candidates) {
                val d = dist(from, c.tile)
                if (d <= range && d < bestDist) { bestDist = d; best = c }
            }
            return best
        }

        /** Enemies fight back any ally in reach else press the gap; allies engage in reach else hold their goal. */
        private fun skirmish() {
            val liveH = hostiles.filter { alive(it) }
            val liveA = allies.filter { alive(it.npc) }
            val allyNpcs = liveA.map { it.npc }
            for (h in liveH) {
                if (h.isAttacking() && h.getCombatTarget()?.let { alivePawn(it) } == true) continue
                val foe = nearest(h.tile, allyNpcs, HOSTILE_ENGAGE)
                if (foe != null) { h.attack(foe); continue }
                if (h.isAttacking()) { h.removeCombatTarget(); h.resetFacePawn() }
                if (dist(h.tile, guardLine) > 3) h.walkTo(guardLine)
            }
            for (a in liveA) {
                val k = a.npc
                if (k.isAttacking() && k.getCombatTarget()?.let { alivePawn(it) } == true) continue
                val foe = nearest(k.tile, liveH, ALLY_ENGAGE)
                if (foe != null) { k.attack(foe); continue }
                if (k.isAttacking()) { k.removeCombatTarget(); k.resetFacePawn() }
                val g = a.goal ?: continue
                if (dist(k.tile, g) > 1) k.walkTo(g)
            }
        }

        // ---- victory + epilogue -------------------------------------------------------------------

        private fun victory() {
            victoryAt = ticks
            owner.message("<col=801700>The splinter warband breaks.</col>")
            owner.message("<col=801700>With the organized raids defeated, the northern pass can be held by a much smaller Asgarnian force.</col>")
            val rest = hostiles.filter { alive(it) }
            for (n in rest) { n.removeCombatTarget(); n.resetFacePawn(); n.forceChat("Run!") }
            for (a in allies) { if (alive(a.npc) && a.npc.isAttacking()) { a.npc.removeCombatTarget(); a.npc.resetFacePawn() } }
            at(3) { for (n in rest) discard(n) }
            at(2) { allies.firstOrNull()?.npc?.forceChat("Burthorpe!") }
            at(4) { myArm?.forceChat("Stupid trolls run.") }
            at(6) { snowflake?.forceChat("It is done.") }

            // Mutate before the epilogue narrates: the quest moves on whether or not the chat is read.
            val step = QuestEngine.stepId(owner, AMatterOfTrolls)
            if (step == AMatterOfTrolls.WAR_CHIEF) {
                QuestEngine.satisfy(owner, AMatterOfTrolls, AMatterOfTrolls.WAR_CHIEF)
            } else if (step == AMatterOfTrolls.BATTLE) {
                QuestEngine.advanceTo(owner, AMatterOfTrolls, AMatterOfTrolls.REPORT)
            }

            // Denulth comes up through the gap with the reserve for the after-battle scene.
            val d = qi.spawnNpc(AMatterOfTrolls.DENULTH, DENULTH_ARRIVES)
            if (d != null) {
                d.aggroCheck = aggroNone
                d.routeLogic = 1
                at(2) { d.walkTo(qi.translate(DENULTH_STANDS)) }
            }
            owner.queue {
                wait(8)
                epilogue(owner)
                if (!ended) qi.end(EndReason.COMPLETE)
            }
        }

        /** After the battle (spec §42-43): Denulth and the trolls, then the thing Snowflake won't explain. */
        private suspend fun QueueTask.epilogue(p: Player) {
            val denId = runCatching { getRSCM(AMatterOfTrolls.DENULTH) }.getOrDefault(-1)
            val armId = runCatching { getRSCM(AMatterOfTrolls.MY_ARM) }.getOrDefault(-1)
            val snowId = runCatching { getRSCM(AMatterOfTrolls.SNOWFLAKE) }.getOrDefault(-1)
            p.message("Denulth looks over the allied trolls.")
            chatNpc(p, "I never thought I'd be grateful to see that many trolls coming down a mountain.", npc = denId, title = "Denulth")
            chatNpc(p, "You welcome.", npc = armId, title = "My Arm")
            chatNpc(p, "I didn't say welcome.", npc = denId, title = "Denulth")
            chatNpc(p, "You mean it.", npc = armId, title = "My Arm")
            p.message("Denulth chooses not to argue.")
            chatNpc(p, "You fought well.", npc = snowId, title = "Snowflake")
            chatPlayer(p, "Thank you.")
            p.message("My Arm looks at you again.")
            chatNpc(p, "Adventurer fight like that too.", npc = armId, title = "My Arm")
            p.message("Snowflake looks at him.")
            chatNpc(p, "My Arm.", npc = snowId, title = "Snowflake")
            chatNpc(p, "What?", npc = armId, title = "My Arm")
            chatNpc(p, "Later.", npc = snowId, title = "Snowflake")
            p.message("You notice. Nobody explains.")
            chatNpc(p, "Right. Back down to Burthorpe. Sir Amik hears this from you — not from my report.", npc = denId, title = "Denulth")
            p.message("<col=801700>You come down off the plateau with the Imperial Guard.</col>")
        }

        // ---- end ------------------------------------------------------------------------------------

        fun end(reason: EndReason) {
            if (ended) return
            ended = true
            pending.clear()
            if (reason == EndReason.COMPLETE || reason == EndReason.ADMIN) return
            if (!owner.isOnline) return
            val step = QuestEngine.stepId(owner, AMatterOfTrolls)
            if (step == AMatterOfTrolls.BATTLE || step == AMatterOfTrolls.WAR_CHIEF) {
                QuestEngine.advanceTo(owner, AMatterOfTrolls, AMatterOfTrolls.READY)
                owner.message("<col=801700>The pass is lost for now.</col> Denulth will send you back up when you're ready — nothing else is lost.")
            }
        }
    }
}
