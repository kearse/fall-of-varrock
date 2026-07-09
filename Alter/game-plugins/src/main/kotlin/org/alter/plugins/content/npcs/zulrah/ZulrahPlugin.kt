package org.alter.plugins.content.npcs.zulrah

import dev.openrune.cache.CacheManager.getItem
import dev.openrune.cache.CacheManager.getNpc
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.dsl.setCombatDef
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.CollectionLog
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable
import org.alter.plugins.content.bosses.bossMelee
import org.alter.plugins.content.bosses.bossProjectile
import org.alter.plugins.content.bosses.bossSummon
import org.alter.plugins.content.combat.*
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints
import org.alter.plugins.content.economy.awardTickets
import org.alter.plugins.content.mechanics.poison.poison
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Zulrah — the rotating phase boss** (Phase E).
 *
 * A **single-occupancy session** (one fighter at a time, robust at this population — the same
 * call the Fight Cave makes; true per-player instancing is a future upgrade). One persistent
 * Zulrah npc keeps **one HP bar** and is morphed between its three forms with [setTransmogId],
 * hopping between four arena positions on a fixed rotation:
 *  - **Green** (ranged) — spawns **snakelings** and toxic venom;
 *  - **Blue** (magic);
 *  - **Red** (melee).
 *
 * Pray the form's style to survive. The session tick drives the phase rotation (morph + hop +
 * snakelings/venom); the npc's `onNpcCombat` loop drives the attacks against the occupant.
 *
 * **Scope note:** the *exact* OSRS rotation patterns, the submerge/resurface animation timing,
 * and the Jad-style phase are simplified/TUNE — this is the faithful core (forms + prayer
 * switching + position hops + snakelings + venom + one HP bar), not a tick-perfect clone.
 */
class ZulrahPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private enum class Form(val transmog: Int, val label: String, val style: CombatClass, val anim: Int) {
        // anim = the form's attack animation (best-known OSRS ids; TUNE in-game).
        GREEN(2042, "green", CombatClass.RANGED, anim = 5069),
        RED(2043, "red", CombatClass.MELEE, anim = 5806),
        BLUE(2044, "blue", CombatClass.MAGIC, anim = 5071),
    }

    private data class Phase(val form: Form, val pos: Int)

    // A simple, readable rotation (form + which of the 4 positions). Not the exact OSRS pattern.
    private val rotation = listOf(
        Phase(Form.GREEN, 0), Phase(Form.RED, 1), Phase(Form.BLUE, 2),
        Phase(Form.GREEN, 3), Phase(Form.BLUE, 1), Phase(Form.RED, 0),
    )

    private val sessionTimer = TimerKey()

    // Session state (single occupancy).
    private var occupant: Player? = null
    private var zulrah: Npc? = null
    private var phaseIdx = 0
    private var phaseTicks = 0
    private var currentForm = Form.GREEN
    private val snakelings = mutableListOf<Npc>()

    init {
        setCombatDef("npc.zulrah") {
            configs {
                attackSpeed = 3
                respawnDelay = 0
            }
            stats {
                hitpoints = 500
                ranged = 300
                magic = 300
                attack = 200
                strength = 200
                defence = 150
            }
            bonuses {
                attackRanged = 150
                attackMagic = 150
                strengthBonus = 100
                defenceStab = 60
                defenceMagic = 60
                defenceRanged = 60
            }
            anims {
                // DSL requires a death anim; OSRS npc defs carry none. Use Zulrah's own idle as a
                // model-appropriate placeholder (it submerges/dies via the session end anyway).
                val standAnim = runCatching { getNpc(getRSCM("npc.zulrah")).standAnim }.getOrNull() ?: -1
                death = if (standAnim > 0) standAnim else 836
            }
        }

        onWorldInit { world.timers[sessionTimer] = 1 }
        onTimer(sessionTimer) { tick() }

        onCommand("zulrah", description = "Enter the Zulrah shrine") { start(player) }
        onCommand("leavezulrah", description = "Leave the Zulrah shrine") { if (occupant === player) endSession(teleport = true) }

        onNpcCombat("npc.zulrah") { npc.queue { npc.combat(this) } }

        onNpcDeath("npc.zulrah") {
            val killer = npc.attr[KILLER_ATTR]?.get() as? Player ?: occupant
            if (killer != null) reward(killer)
            endSession(teleport = true)
        }

        onPlayerPreDeath { if (player === occupant) endSession(teleport = false) }
        onLogout { if (player === occupant) endSession(teleport = false) }
    }

    private fun start(player: Player) {
        when {
            occupant === player -> { player.message("You're already at the Zulrah shrine."); return }
            occupant != null -> { player.message("The shrine is busy. Try again shortly."); return }
        }
        occupant = player
        phaseIdx = 0
        phaseTicks = 0
        snakelings.clear()
        player.moveTo(PLAYER_TILE)

        val z = Npc(getRSCM("npc.zulrah"), positions[rotation[0].pos], world)
        z.respawns = false
        world.spawn(z)
        z.setActive(true)
        zulrah = z
        applyPhase(0)
        player.message("<col=007f00>Zulrah rises from the swamp. Pray against its form!</col>")
    }

    private fun tick() {
        world.timers[sessionTimer] = 1
        val p = occupant ?: return
        val z = zulrah
        if (p.index < 0 || z == null || z.index < 0 || z.isDead() || !world.npcs.contains(z)) return

        phaseTicks++
        if (phaseTicks >= PHASE_DURATION) {
            phaseTicks = 0
            phaseIdx = (phaseIdx + 1) % rotation.size
            applyPhase(phaseIdx)
        }
    }

    private fun applyPhase(idx: Int) {
        val ph = rotation[idx]
        val z = zulrah ?: return
        currentForm = ph.form
        z.moveTo(positions[ph.pos])
        z.setTransmogId(ph.form.transmog)
        occupant?.message("<col=8f0000>Zulrah surfaces — ${ph.form.label} form!</col>")

        if (ph.form == Form.GREEN) {
            snakelings.removeAll { it.index < 0 || it.isDead() || !world.npcs.contains(it) }
            if (snakelings.size < 3) snakelings += z.bossSummon("npc.snakeling", 2)
            occupant?.let { if (world.chance(1, 2)) it.poison(initialDamage = 6) { it.message("Zulrah's venom courses through you.") } }
        }
    }

    private suspend fun Npc.combat(it: org.alter.game.model.queue.QueueTask) {
        // Driven against the session occupant (a scripted boss, not the aggro system).
        while (occupant != null && index >= 0 && !isDead()) {
            val target = occupant ?: break
            if (target.index < 0) break
            facePawn(target)
            if (isAttackDelayReady()) {
                if (currentForm.anim > 0) animate(currentForm.anim)
                when (currentForm) {
                    Form.GREEN -> bossProjectile(target, CombatClass.RANGED, ZULRAH_MAX, gfx = RANGE_GFX)
                    Form.BLUE -> bossProjectile(target, CombatClass.MAGIC, ZULRAH_MAX, gfx = MAGIC_GFX)
                    Form.RED -> bossMelee(target, ZULRAH_MAX)
                }
                postAttackLogic(target)
            }
            it.wait(1)
        }
        resetFacePawn()
    }

    private fun reward(p: Player) {
        p.awardTickets(PointKind.BOSS, ZULRAH_POINTS)
        LOOT.roll(world).forEach { drop ->
            val id = runCatching { getRSCM(drop.item) }.getOrNull() ?: return@forEach
            world.spawn(GroundItem(id, drop.amount, p.tile, p))
            val name = runCatching { getItem(id).name }.getOrNull() ?: return@forEach
            if (drop.announce) world.players.forEach { it.message("<col=ff0000>News: ${p.username} just received <col=ffae00>$name</col> from Zulrah!</col>") }
            if (drop.log && CollectionLog.record(p, id)) p.message("<col=ffae00>New Collection Log slot: $name!</col>")
        }
        p.message("<col=007f00>You have defeated Zulrah!</col> (+$ZULRAH_POINTS Boss Tickets)")
    }

    private fun endSession(teleport: Boolean) {
        val p = occupant
        zulrah?.let { if (it.index >= 0 && world.npcs.contains(it)) { it.setCurrentHp(0); world.remove(it) } }
        snakelings.forEach { if (it.index >= 0 && world.npcs.contains(it)) { it.setCurrentHp(0); world.remove(it) } }
        snakelings.clear()
        zulrah = null
        occupant = null
        phaseIdx = 0
        phaseTicks = 0
        if (teleport && p != null && p.index >= 0) p.moveTo(world.gameContext.home)
    }

    private companion object {
        const val PHASE_DURATION = 8 // ticks per form before it submerges + rotates
        const val ZULRAH_MAX = 41
        const val ZULRAH_POINTS = 40
        const val RANGE_GFX = 1044
        const val MAGIC_GFX = 1046

        // Zul-Andra shrine — approximate; TUNE in-game (force-loaded via BossLairs "zulrah").
        val PLAYER_TILE = Tile(2200, 3070, 0)
        val positions = arrayOf(
            Tile(2200, 3074, 0), Tile(2204, 3070, 0), Tile(2200, 3066, 0), Tile(2196, 3070, 0),
        )

        val LOOT = DropTable(
            main = listOf(
                DropEntry("item.coins_995", 30_000, 90_000, weight = 30),
                DropEntry("item.zulrahs_scales", 100, 300, weight = 25),
                DropEntry("item.death_rune", 100, 300, weight = 16),
                DropEntry("item.snakeskin", 10, 30, weight = 14),
            ),
            // Signature: tanzanite fang (→ toxic blowpipe), magic fang (→ toxic trident), serpentine
            // visage (→ serpentine helm), uncut onyx; the mutagen recolours + jar + Pet snakeling.
            rare = listOf(
                DropEntry("item.tanzanite_fang", 1, 1, oneInN = 512, announce = true, log = true),
                DropEntry("item.magic_fang", 1, 1, oneInN = 512, announce = true, log = true),
                DropEntry("item.serpentine_visage", 1, 1, oneInN = 512, announce = true, log = true),
                DropEntry("item.uncut_onyx", 1, 1, oneInN = 750, announce = true, log = true),
                DropEntry("item.tanzanite_mutagen", 1, 1, oneInN = 5000, announce = true, log = true),
                DropEntry("item.magma_mutagen", 1, 1, oneInN = 5000, announce = true, log = true),
                DropEntry("item.jar_of_swamp", 1, 1, oneInN = 3000, announce = true, log = true),
                DropEntry("item.pet_snakeling", 1, 1, oneInN = 4000, announce = true, log = true),
            ),
        )
    }
}
