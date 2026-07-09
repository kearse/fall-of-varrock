package org.alter.plugins.content.minigames.inferno

import dev.openrune.cache.CacheManager.getNpc
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.*
import org.alter.api.dsl.setCombatDef
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Area
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.INFERNO_BEST_WAVE_ATTR
import org.alter.game.model.attr.INFERNO_UNLOCKED_ATTR
import org.alter.game.model.attr.NO_LOOT_ATTR
import org.alter.game.model.combat.AttackStyle
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.combat.CombatStyle
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.model.queue.QueueTask
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.CollectionLog
import org.alter.plugins.content.bosses.bossMelee
import org.alter.plugins.content.bosses.bossProjectile
import org.alter.plugins.content.bosses.deathAnimFor
import org.alter.plugins.content.bosses.isProtectedFrom
import org.alter.plugins.content.combat.*
import org.alter.plugins.content.combat.formula.MagicCombatFormula
import org.alter.plugins.content.combat.formula.RangedCombatFormula
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.awardTickets
import org.alter.plugins.content.raids.RaidInstance
import org.alter.rscm.RSCM.getRSCM
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

/**
 * **The Inferno — TzKal-Zuk** (the infernal-cape minigame; the prestige tier above the Fight Cave).
 *
 * Same hybrid formula as the Fight Cave: **OSRS-authentic mechanics, compressed length** —
 * [waves] waves of the real Jal- roster in a private instance of the real Inferno arena
 * (region 9043), ending against **TzKal-Zuk**:
 *  - **Jal-Ak** blobs split into three mini-casters on death; **Jal-ImKot** burrows to you if you
 *    kite it too long; **Jal-Zek** mages can *resurrect* monsters that died this wave;
 *  - wave 12 is a full **JalTok-Jad** (telegraphed prayer-switch fight + healers at half HP);
 *  - **Zuk** never moves: the **Ancestral Glyph** shield slides along the lava edge and every Zuk
 *    blast is UNBLOCKABLE (~[ZUK_MAX]) unless you're in its shadow — the fight is footwork, with
 *    ranger+mage adds, a mid-fight JalTok-Jad, and healers at set health thresholds.
 *
 * Entry is gated by **sacrificing a Fire cape** to TzHaar-Ket-Keh (one-time, permanent) — the
 * economy sink that pairs with tradeable fire capes. Commands: `::inferno` (run), `::zuk`
 * (practice Zuk, no rewards), `::leaveinferno` (flee). Deaths are safe (instanced).
 *
 * Every clear: **Infernal cape** (untradeable — THE prestige item) + Boss tickets + a TzRek-Zuk
 * pet roll. Best wave tracked in [INFERNO_BEST_WAVE_ATTR].
 */
class InfernoPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private data class WaveSpawn(val npc: String, val count: Int)

    private val waves = listOf(
        listOf(WaveSpawn(NIB, 3)),
        listOf(WaveSpawn(MEJRAH, 1)),
        listOf(WaveSpawn(MEJRAH, 1), WaveSpawn(NIB, 2)),
        listOf(WaveSpawn(AK, 1)),
        listOf(WaveSpawn(AK, 1), WaveSpawn(MEJRAH, 1)),
        listOf(WaveSpawn(IMKOT, 1)),
        listOf(WaveSpawn(IMKOT, 1), WaveSpawn(AK, 1)),
        listOf(WaveSpawn(XIL, 1)),
        listOf(WaveSpawn(XIL, 1), WaveSpawn(IMKOT, 1)),
        listOf(WaveSpawn(ZEK, 1)),
        listOf(WaveSpawn(ZEK, 1), WaveSpawn(XIL, 1)),
        listOf(WaveSpawn(JALJAD, 1)),
        listOf(WaveSpawn(ZUK, 1)),
    )

    private class Session(val owner: Player, val instance: RaidInstance, val practice: Boolean) {
        var wave = 0
        var breather = ENTRY_DELAY
        val alive = mutableListOf<Npc>()

        // Corpses of this wave (npc id + death tile) — Jal-Zek resurrection fodder.
        val corpses = mutableListOf<Pair<Int, Tile>>()
        var resurrectsLeft = 0

        // Healer machinery (JalTok-Jad wave AND Zuk wave — healers heal [healTarget]).
        var healTarget: Npc? = null
        var healersSpawned = false
        val healers = mutableListOf<Npc>()
        var healPulse = 0

        // Zuk fight state. Glyph patrol bounds are precomputed in INSTANCE coords.
        var zuk: Npc? = null
        var glyph: Npc? = null
        var glyphDir = 1
        var glyphPulse = 0
        var glyphMinX = 0
        var glyphMaxX = 0
        var zukPulse = 0
        var zukSetSpawned = false
        var zukJadSpawned = false
    }

    private val sessions = mutableListOf<Session>()
    private val infernoTimer = TimerKey()
    private val fireCape = getRSCM("item.fire_cape")
    private val infernalCape = getRSCM("item.infernal_cape")

    init {
        registerCombatDefs()

        onWorldInit {
            world.timers[infernoTimer] = 1
            // Ket-Keh stands beside Mej-Jal at the live Fight Cave entrance (one TzHaar hub, one
            // portal landing). Spawn DIRECTLY — the spawnNpc queue is consumed pre-onWorldInit.
            runCatching { world.definitions.loadRegions(world, world.chunks, intArrayOf(ENTRANCE_REGION)) }
                .onFailure { logger.error(it) { "inferno: entrance region force-load failed" } }
            runCatching {
                val n = Npc(getRSCM(KET_KEH), GATEKEEPER_TILE, world)
                n.walkRadius = 0
                n.lastFacingDirection = Direction.NORTH
                world.spawn(n)
                n.setActive(true)
            }.onFailure { logger.warn { "inferno: gatekeeper '$KET_KEH' not spawned: ${it.message}" } }
        }
        onTimer(infernoTimer) {
            sessions.toList().forEach { s ->
                runCatching { tick(s) }.onFailure { e -> logger.error(e) { "inferno session tick failed" } }
            }
            world.timers[infernoTimer] = 1
        }

        bindGatekeeper()
        onCommand("inferno", description = "Enter the Inferno (requires a sacrificed Fire cape)") { start(player, practice = false) }
        onCommand("zuk", description = "Practice TzKal-Zuk (no rewards)") { start(player, practice = true) }
        onCommand("leaveinferno", description = "Flee the Inferno") { leave(player) }

        // Scripted attackers. Jal-Nib and the melee blob-split use the default melee AI.
        onNpcCombat(MEJRAH) { npc.queue { npc.rangedCombat(this, MEJRAH_MAX, MEJRAH_ANIM, RANGE_GFX) } }
        onNpcCombat(AK) { npc.queue { npc.blobCombat(this) } }
        onNpcCombat(AK_MEJ) { npc.queue { npc.magicCombat(this, AKLET_MAX, -1, MAGIC_GFX) } }
        onNpcCombat(AK_XIL) { npc.queue { npc.rangedCombat(this, AKLET_MAX, -1, RANGE_GFX) } }
        onNpcCombat(IMKOT) { npc.queue { npc.imKotCombat(this) } }
        onNpcCombat(XIL) { npc.queue { npc.xilCombat(this) } }
        onNpcCombat(XIL_ZUKWAVE) { npc.queue { npc.xilCombat(this) } }
        onNpcCombat(ZEK) { npc.queue { npc.zekCombat(this) } }
        onNpcCombat(ZEK_ZUKWAVE) { npc.queue { npc.zekCombat(this) } }
        onNpcCombat(JALJAD) { npc.queue { npc.jadCombat(this) } }
        onNpcCombat(JALJAD_ZUKWAVE) { npc.queue { npc.jadCombat(this) } }

        // Jal-Ak splits into its three mini-casters where it fell.
        onNpcDeath(AK) {
            val s = sessions.firstOrNull { it.instance.contains(npc.tile) } ?: return@onNpcDeath
            listOf(AK_MEJ, AK_XIL, AK_KET).forEach { spawnWaveNpc(s, it, npc.tile) }
        }

        onPlayerPreDeath {
            sessionOf(player)?.let { s ->
                player.message("<col=ff0000>The Inferno claims you on wave ${s.wave}.</col>")
                recordBest(s, s.wave - 1)
                consolation(s)
                cleanup(s, teleport = false)
            }
        }
        onLogout { sessionOf(player)?.let { cleanup(it, teleport = false) } }
    }

    // ───────────────────────────── session lifecycle ─────────────────────────────

    private fun sessionOf(p: Player): Session? = sessions.firstOrNull { it.owner === p }

    private fun start(player: Player, practice: Boolean) {
        if (player.attr[INFERNO_UNLOCKED_ATTR] != true) {
            player.message("<col=ff0000>The Inferno is sealed to you.</col> Bring TzHaar-Ket-Keh a Fire cape to sacrifice.")
            return
        }
        if (sessionOf(player) != null) { player.message("You're already in the Inferno."); return }
        val instance = RaidInstance.allocate(world, ARENA_AREA, exitTile = world.gameContext.home, owner = player.uid)
        if (instance == null) { player.message("The instance space is full right now — try again shortly."); return }
        val s = Session(player, instance, practice)
        if (practice) s.wave = waves.size - 1 // straight to Zuk
        sessions += s
        player.moveTo(instance.translate(ENTRY_TILE))
        if (practice) {
            player.message("<col=ff0000>Practice mode:</col> TzKal-Zuk only — no cape, no tickets. Stay behind the glyph!")
        } else {
            player.message("<col=ff0000>You descend into the Inferno.</col> ${waves.size} waves. Use ::leaveinferno to flee.")
        }
    }

    private fun leave(player: Player) {
        val s = sessionOf(player) ?: run { player.message("You're not in the Inferno."); return }
        player.message("You flee the Inferno.")
        recordBest(s, s.wave - 1)
        consolation(s)
        cleanup(s, teleport = true)
    }

    /** Failed-run payout — deeper attempts burn more supplies, so they pay more tickets. */
    private fun consolation(s: Session) {
        if (s.practice) return
        val cleared = s.wave - 1
        if (cleared <= 0) return
        val tickets = cleared * WAVE_CONSOLATION
        s.owner.awardTickets(PointKind.BOSS, tickets)
        s.owner.message("<col=ffae00>$cleared wave${if (cleared == 1) "" else "s"} survived: +$tickets Boss Tickets.</col>")
    }

    private fun tick(s: Session) {
        val p = s.owner
        if (p.index < 0) { cleanup(s, teleport = false); return }
        if (!s.instance.contains(p.tile)) { recordBest(s, s.wave - 1); consolation(s); cleanup(s, teleport = false); return }

        healerLogic(s)
        zukLogic(s)

        // Zuk's death wins the run immediately — leftover adds/healers don't hold it hostage.
        if (s.wave >= waves.size) {
            val zuk = s.zuk
            if (zuk != null && (zuk.index < 0 || zuk.isDead() || !world.npcs.contains(zuk))) { victory(s); return }
        }

        // JalTok-Jad wave completes on HIS death even if healers linger.
        if (s.wave == waves.size - 1) {
            val jad = s.healTarget
            if (jad != null && (jad.index < 0 || jad.isDead() || !world.npcs.contains(jad))) {
                s.alive.forEach { if (it.index >= 0 && world.npcs.contains(it)) { it.setCurrentHp(0); world.remove(it) } }
                s.alive.clear()
            }
        }

        val wasFighting = s.alive.isNotEmpty()
        s.alive.removeAll { n ->
            val dead = n.index < 0 || n.isDead() || !world.npcs.contains(n)
            if (dead) s.corpses += n.id to n.tile
            dead
        }
        if (s.alive.isNotEmpty()) return

        if (wasFighting) { // wave cleared
            if (s.wave >= waves.size) { victory(s); return }
            p.message("<col=007f00>Wave ${s.wave} defeated!</col>")
            s.breather = WAVE_DELAY
            return
        }
        if (s.breather > 0) { s.breather--; if (s.breather > 0) return }

        s.wave++
        s.corpses.clear()
        s.resurrectsLeft = RESURRECTS_PER_WAVE
        s.healTarget = null
        s.healersSpawned = false
        val def = waves[s.wave - 1]
        when (s.wave) {
            waves.size -> startZukWave(s)
            waves.size - 1 -> {
                p.message("<col=ff0000>JalTok-Jad emerges! Slam is Ranged, fire-breath is Magic — just like his little brother.</col>")
                s.healTarget = spawnWaveNpc(s, JALJAD, CENTER)
            }
            else -> {
                p.message("<col=ff0000>Wave ${s.wave}/${waves.size}...</col>")
                def.forEach { ws -> repeat(ws.count) { spawnWaveNpc(s, ws.npc, SPAWN_POINTS.random()) } }
            }
        }
    }

    private fun spawnWaveNpc(s: Session, key: String, sourceTile: Tile, engage: Boolean = true): Npc =
        spawnWaveNpc(s, getRSCM(key), sourceTile, engage)

    private fun spawnWaveNpc(s: Session, id: Int, sourceTile: Tile, engage: Boolean = true): Npc {
        val base = if (s.instance.contains(sourceTile)) sourceTile else s.instance.translate(sourceTile)
        val tile = world.findRandomTileAround(base, radius = 2) ?: base
        val npc = Npc(id, tile, world)
        npc.walkRadius = 15
        world.spawn(npc)
        npc.respawns = false // AFTER world.spawn — setNpcDefaults would clobber it
        npc.setActive(true)
        npc.attr[NO_LOOT_ATTR] = true
        if (engage) npc.attack(s.owner)
        s.alive += npc
        return npc
    }

    /** Healers rush [Session.healTarget] (JalTok-Jad, then Zuk) and restore it unless provoked. */
    private fun healerLogic(s: Session) {
        val target = s.healTarget ?: return
        if (target.index < 0 || target.isDead() || !world.npcs.contains(target)) return
        if (!s.healersSpawned && target.getCurrentHp() <= target.getMaxHp() / 2) {
            s.healersSpawned = true
            val key = if (s.wave >= waves.size) ZUK_HEALER else JAD_HEALER
            val count = if (s.wave >= waves.size) ZUK_HEALER_COUNT else JAD_HEALER_COUNT
            s.owner.message("<col=8f0000>Healers swarm to its aid — drag them off!</col>")
            repeat(count) { s.healers += spawnWaveNpc(s, key, target.tile, engage = false) }
        }
        if (s.healersSpawned) {
            s.healPulse++
            if (s.healPulse % HEAL_INTERVAL == 0) {
                s.healers.removeAll { it.index < 0 || it.isDead() || !world.npcs.contains(it) }
                s.healers.forEach { h ->
                    if (h.getCombatTarget() == null && target.getCurrentHp() < target.getMaxHp()) {
                        target.setCurrentHp(minOf(target.getMaxHp(), target.getCurrentHp() + HEAL_PER_PULSE))
                    }
                }
            }
        }
    }

    // ───────────────────────────── the Zuk fight ─────────────────────────────

    private fun startZukWave(s: Session) {
        s.owner.message("<col=ff0000>TzKal-Zuk rises from the lava. STAY BEHIND THE GLYPH — nothing blocks his fury.</col>")
        val zuk = spawnWaveNpc(s, ZUK, ZUK_TILE, engage = false)
        zuk.walkRadius = 0
        s.zuk = zuk
        s.glyphMinX = s.instance.translate(Tile(GLYPH_MIN_X, GLYPH_Z, 0)).x
        s.glyphMaxX = s.instance.translate(Tile(GLYPH_MAX_X, GLYPH_Z, 0)).x
        val glyph = Npc(getRSCM(GLYPH), s.instance.translate(Tile(GLYPH_MIN_X + (GLYPH_MAX_X - GLYPH_MIN_X) / 2, GLYPH_Z, 0)), world)
        glyph.walkRadius = 0
        world.spawn(glyph)
        glyph.respawns = false
        glyph.setActive(true)
        s.glyph = glyph
    }

    private fun zukLogic(s: Session) {
        val zuk = s.zuk ?: return
        if (zuk.index < 0 || zuk.isDead() || !world.npcs.contains(zuk)) return
        val p = s.owner

        // The glyph slides along the lava edge, bouncing at the ends.
        s.glyph?.let { g ->
            if (g.index >= 0 && world.npcs.contains(g)) {
                s.glyphPulse++
                if (s.glyphPulse % GLYPH_SPEED == 0) {
                    if (g.tile.x >= s.glyphMaxX) s.glyphDir = -1
                    if (g.tile.x <= s.glyphMinX) s.glyphDir = 1
                    g.moveTo(Tile(g.tile.x + s.glyphDir, g.tile.z, g.tile.height))
                }
            }
        }

        // Threshold adds: ranger+mage set, then a full JalTok-Jad, then healers (healerLogic).
        if (!s.zukSetSpawned && zuk.getCurrentHp() <= ZUK_HP * 3 / 4) {
            s.zukSetSpawned = true
            p.message("<col=8f0000>Zuk calls forth his guard!</col>")
            spawnWaveNpc(s, XIL_ZUKWAVE, SPAWN_POINTS.random())
            spawnWaveNpc(s, ZEK_ZUKWAVE, SPAWN_POINTS.random())
        }
        if (!s.zukJadSpawned && zuk.getCurrentHp() <= ZUK_HP / 2) {
            s.zukJadSpawned = true
            p.message("<col=8f0000>A JalTok-Jad answers Zuk's roar!</col>")
            spawnWaveNpc(s, JALJAD_ZUKWAVE, CENTER)
        }
        if (s.healTarget !== zuk && zuk.getCurrentHp() <= ZUK_HP / 4) {
            // Re-arm the healer machinery for Zuk (it may have been used by the Jad wave).
            s.healTarget = zuk
            s.healersSpawned = false
            s.healers.clear()
        }

        // Zuk's blast: every cycle, unblockable unless the player is in the glyph's shadow.
        s.zukPulse++
        if (s.zukPulse % ZUK_SPEED == 0 && p.index >= 0 && !p.isDead()) {
            val g = s.glyph
            val safe = g != null && g.index >= 0 &&
                abs(p.tile.x - (g.tile.x + 1)) <= 1 && p.tile.z < g.tile.z
            zuk.facePawn(p)
            if (!safe) {
                zuk.animate(ZUK_ANIM)
                world.spawn(zuk.createProjectile(p, gfx = ZUK_PROJ, startHeight = 160, endHeight = 31, delay = 41, angle = 15, steepness = 20))
                p.hit(damage = world.random(ZUK_MAX), type = HitType.HIT, delay = 2)
                p.message("<col=ff0000>Zuk's blast sears you — get behind the glyph!</col>")
            }
        }
    }

    private fun victory(s: Session) {
        val p = s.owner
        if (s.practice) {
            p.message("<col=ffae00>TzKal-Zuk falls! A fine rehearsal — now do it with everything on the line.</col>")
            cleanup(s, teleport = true)
            return
        }
        recordBest(s, waves.size)
        p.awardTickets(PointKind.BOSS, CLEAR_BOSS_POINTS)
        val add = p.inventory.add(item = infernalCape, amount = 1, assureFullInsertion = false)
        if (add.completed == 0) p.bank.add(infernalCape, 1)
        p.message("<col=ffae00>You are awarded the Infernal cape!</col>")
        if (CollectionLog.record(p, infernalCape)) {
            world.players.forEach { it.message("<col=ff0000>News: ${p.username} has defeated TzKal-Zuk and earned the <col=ffae00>INFERNAL CAPE</col>!</col>") }
        }
        if (world.chance(1, PET_ODDS)) {
            val pet = getRSCM("item.tzrekzuk")
            val padd = p.inventory.add(item = pet, amount = 1, assureFullInsertion = false)
            if (padd.completed == 0) p.bank.add(pet, 1)
            world.players.forEach { it.message("<col=ff0000>News: ${p.username} just received <col=ffae00>TzRek-Zuk</col> from the Inferno!</col>") }
            if (CollectionLog.record(p, pet)) p.message("<col=ffae00>New Collection Log slot: TzRek-Zuk!</col>")
        }
        p.message("<col=ffae00>You have conquered the Inferno!</col> +$CLEAR_BOSS_POINTS Boss Tickets.")
        logger.info { "INFERNO ${p.username} defeated TzKal-Zuk" }
        cleanup(s, teleport = true)
    }

    private fun recordBest(s: Session, reached: Int) {
        if (s.practice) return
        if (reached > (s.owner.attr[INFERNO_BEST_WAVE_ATTR] ?: 0)) s.owner.attr[INFERNO_BEST_WAVE_ATTR] = reached
    }

    private fun cleanup(s: Session, teleport: Boolean) {
        (s.alive + s.healers + listOfNotNull(s.glyph)).forEach {
            if (it.index >= 0 && world.npcs.contains(it)) { it.setCurrentHp(0); world.remove(it) }
        }
        s.alive.clear()
        s.healers.clear()
        s.zuk = null
        s.glyph = null
        s.healTarget = null
        sessions.remove(s)
        if (teleport && s.owner.index >= 0) s.owner.moveTo(world.gameContext.home)
    }

    // ───────────────────────────── scripted combat ─────────────────────────────

    /** Generic single-style projectile attacker (Jal-MejRah, the blob's mini-casters). */
    private suspend fun Npc.rangedCombat(task: QueueTask, maxHit: Int, anim: Int, gfx: Int) =
        projectileLoop(task, CombatClass.RANGED, maxHit, anim, gfx)

    private suspend fun Npc.magicCombat(task: QueueTask, maxHit: Int, anim: Int, gfx: Int) =
        projectileLoop(task, CombatClass.MAGIC, maxHit, anim, gfx)

    private suspend fun Npc.projectileLoop(task: QueueTask, cls: CombatClass, maxHit: Int, anim: Int, gfx: Int) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 8, projectile = true) && isAttackDelayReady()) {
                if (anim > 0) animate(anim)
                bossProjectile(target, cls, maxHit, gfx = gfx)
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    /** Jal-Ak: lobs a random style each attack — keep one protection up and pray for the rest. */
    private suspend fun Npc.blobCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 8, projectile = true) && isAttackDelayReady()) {
                when (world.random(2)) {
                    0 -> if (tile.isWithinRadius(target.tile, 2)) bossMelee(target, AK_MAX) else bossProjectile(target, CombatClass.RANGED, AK_MAX, gfx = RANGE_GFX)
                    1 -> bossProjectile(target, CombatClass.RANGED, AK_MAX, gfx = RANGE_GFX)
                    else -> bossProjectile(target, CombatClass.MAGIC, AK_MAX, gfx = MAGIC_GFX)
                }
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    /** Jal-ImKot: crushing melee; if you kite it too long it BURROWS and surfaces beside you. */
    private suspend fun Npc.imKotCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        var stuck = 0
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 1, projectile = false) && isAttackDelayReady()) {
                stuck = 0
                animate(IMKOT_ANIM)
                bossMelee(target, IMKOT_MAX)
                postAttackLogic(target)
            } else if (++stuck >= IMKOT_DIG_TICKS) {
                stuck = 0
                val dest = world.findRandomTileAround(target.tile, radius = 1)
                if (dest != null) {
                    moveTo(dest)
                    if (target is Player) target.message("<col=8f0000>Jal-ImKot burrows and surfaces beside you!</col>")
                }
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    /** Jal-Xil: heavy ranger, bites in reach. */
    private suspend fun Npc.xilCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 8, projectile = true) && isAttackDelayReady()) {
                animate(XIL_ANIM)
                if (tile.isWithinRadius(target.tile, 2) && world.random(1) == 0) {
                    bossMelee(target, XIL_MAX)
                } else {
                    bossProjectile(target, CombatClass.RANGED, XIL_MAX, gfx = RANGE_GFX)
                }
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    /** Jal-Zek: massive magic — and it RESURRECTS monsters that died this wave (capped). */
    private suspend fun Npc.zekCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 8, projectile = true) && isAttackDelayReady()) {
                val s = sessions.firstOrNull { it.instance.contains(tile) }
                if (s != null && s.resurrectsLeft > 0 && s.corpses.isNotEmpty() && world.chance(1, 4)) {
                    s.resurrectsLeft--
                    val (deadId, deadTile) = s.corpses.removeAt(world.random(s.corpses.size - 1))
                    val revived = spawnWaveNpc(s, deadId, deadTile)
                    revived.setCurrentHp(maxOf(1, revived.getMaxHp() / 2))
                    if (target is Player) target.message("<col=8f0000>Jal-Zek drags a fallen monster back to its feet!</col>")
                } else {
                    animate(ZEK_ANIM)
                    bossProjectile(target, CombatClass.MAGIC, ZEK_MAX, gfx = MAGIC_GFX)
                }
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    /** JalTok-Jad — the Fight Cave telegraph fight, bigger numbers. Protection checked at IMPACT. */
    private suspend fun Npc.jadCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 15, projectile = true) && isAttackDelayReady()) {
                postAttackLogic(target)
                if (tile.isWithinRadius(target.tile, 2) && world.random(2) == 0) {
                    animate(JALJAD_MELEE_ANIM)
                    bossMelee(target, JALJAD_MAX)
                } else {
                    val magic = world.random(1) == 0
                    animate(if (magic) JALJAD_MAGIC_ANIM else JALJAD_RANGE_ANIM)
                    val cls = if (magic) CombatClass.MAGIC else CombatClass.RANGED
                    prepareAttack(cls, if (magic) CombatStyle.MAGIC else CombatStyle.RANGED, AttackStyle.ACCURATE)
                    if (magic) world.spawn(createProjectile(target, gfx = JALJAD_MAGIC_PROJ, startHeight = 120, endHeight = 31, delay = 41, angle = 15, steepness = 20))
                    task.wait(JAD_TELEGRAPH)
                    if (target.index >= 0 && !target.isDead()) {
                        target.graphic(if (magic) JALJAD_MAGIC_HIT_GFX else JALJAD_RANGE_HIT_GFX)
                        if (target.isProtectedFrom(cls)) {
                            target.hit(damage = 0, type = HitType.BLOCK, delay = 0)
                        } else {
                            // Style-matched formula — MeleeCombatFormula throws on RANGED/MAGIC.
                            val formula = if (magic) MagicCombatFormula else RangedCombatFormula
                            val landed = formula.getAccuracy(this, target) >= world.randomDouble()
                            target.hit(damage = if (landed) world.random(JALJAD_MAX) else 0, type = if (landed) HitType.HIT else HitType.BLOCK, delay = 0)
                        }
                    }
                }
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    // ───────────────────────────── gatekeeper ─────────────────────────────

    private fun bindGatekeeper() {
        val acts = runCatching { getNpc(getRSCM(KET_KEH)).actions.filterNotNull().filter { it.isNotBlank() } }
            .getOrDefault(emptyList())
        acts.filter { it.equals("talk-to", true) }.forEach { act ->
            onNpcOption(KET_KEH, option = act) { player.queue { ketKehDialog(player) } }
        }
        if (acts.none { it.equals("talk-to", true) }) {
            logger.warn { "inferno: '$KET_KEH' cache def has no Talk-to (actions=$acts) — use ::inferno instead." }
        }
    }

    private suspend fun QueueTask.ketKehDialog(p: Player) {
        val id = runCatching { getRSCM(KET_KEH) }.getOrDefault(-1)
        if (p.attr[INFERNO_UNLOCKED_ATTR] != true) {
            chatNpc(p, "Beyond me lies the Inferno, JalYt — where TzKal-Zuk waits beneath the lava. The cave of fire was a nursery. This is the furnace itself.", npc = id, title = "TzHaar-Ket-Keh")
            chatNpc(p, "Entry has a price: a cape of fire, cast into the lava. Prove you conquered the cave, and burn the proof. Then the Inferno is open to you forever.", npc = id, title = "TzHaar-Ket-Keh")
            when (options(p, "Sacrifice a Fire cape. (permanent access)", "Not yet.")) {
                1 -> {
                    if (p.inventory.getItemCount(fireCape) < 1) {
                        chatNpc(p, "You carry no cape of fire, JalYt. Earn one in the cave — or buy one from a warrior who did.", npc = id, title = "TzHaar-Ket-Keh")
                        return
                    }
                    if (p.inventory.remove(item = fireCape, amount = 1).completed == 1) {
                        p.attr[INFERNO_UNLOCKED_ATTR] = true
                        p.message("<col=ffae00>Your Fire cape crumbles to ash. The Inferno is open to you — forever.</col>")
                        chatNpc(p, "It burns well. Enter when you are ready... and bring everything you have.", npc = id, title = "TzHaar-Ket-Keh")
                    }
                }
                2 -> chatPlayer(p, "Not yet.")
            }
            return
        }
        chatNpc(p, "The Inferno remembers you, JalYt. ${waves.size} waves, and TzKal-Zuk at the last — stay in the glyph's shadow or be unmade.", npc = id, title = "TzHaar-Ket-Keh")
        when (options(p, "Enter the Inferno.", "Let me practice against TzKal-Zuk. (no rewards)", "What do I get?", "Not now.")) {
            1 -> start(p, practice = false)
            2 -> start(p, practice = true)
            3 -> {
                chatNpc(p, "Slay TzKal-Zuk and the INFERNAL cape is yours — it cannot be bought, sold or faked. Boss tickets besides, and perhaps a little TzRek-Zuk will crawl out of the lava after you.", npc = id, title = "TzHaar-Ket-Keh")
                chatNpc(p, "Death here costs you nothing but the attempt. Your deepest wave is remembered.", npc = id, title = "TzHaar-Ket-Keh")
            }
            4 -> chatPlayer(p, "Maybe later.")
        }
    }

    // ───────────────────────────── combat defs ─────────────────────────────

    /** OSRS-flavoured stats, hardcoded (npc_combat.json has no rows for the Jal- ids). Every def
     *  needs hitpoints/attackSpeed/respawnDelay AND a death anim or the plugin drops at boot. */
    private fun registerCombatDefs() {
        infernoDef(NIB, hp = 10, att = 20, str = 25, def = 15)
        infernoDef(MEJRAH, hp = 25, att = 40, str = 50, def = 40, rng = 60)
        infernoDef(AK, hp = 40, att = 80, str = 90, def = 90, mag = 90, rng = 90)
        infernoDef(AK_MEJ, hp = 15, att = 40, str = 40, def = 30, mag = 60)
        infernoDef(AK_XIL, hp = 15, att = 40, str = 40, def = 30, rng = 60)
        infernoDef(AK_KET, hp = 15, att = 60, str = 60, def = 30)
        infernoDef(IMKOT, hp = 75, att = 210, str = 290, def = 120)
        infernoDef(XIL, hp = 125, att = 140, str = 180, def = 150, rng = 250)
        infernoDef(ZEK, hp = 220, att = 140, str = 180, def = 180, mag = 300)
        infernoDef(JALJAD, hp = 350, att = 750, str = 1000, def = 480, mag = 500, rng = 1000, speed = 8)
        infernoDef(JALJAD_ZUKWAVE, hp = 350, att = 750, str = 1000, def = 480, mag = 500, rng = 1000, speed = 8)
        infernoDef(JAD_HEALER, hp = 90, att = 160, str = 120, def = 90)
        infernoDef(ZUK_HEALER, hp = 90, att = 160, str = 120, def = 90)
        infernoDef(XIL_ZUKWAVE, hp = 125, att = 140, str = 180, def = 150, rng = 250)
        infernoDef(ZEK_ZUKWAVE, hp = 220, att = 140, str = 180, def = 180, mag = 300)
        infernoDef(ZUK, hp = ZUK_HP, att = 350, str = 400, def = 260, mag = 400, rng = 400, speed = 10)
    }

    private fun infernoDef(key: String, hp: Int, att: Int, str: Int, def: Int, mag: Int = 1, rng: Int = 1, speed: Int = 4) {
        runCatching {
            setCombatDef(key) {
                configs {
                    attackSpeed = speed
                    respawnDelay = 0
                }
                stats {
                    hitpoints = hp
                    attack = att
                    strength = str
                    defence = def
                    magic = mag
                    ranged = rng
                }
                bonuses {
                    attackMagic = 80
                    attackRanged = 80
                    strengthBonus = 40
                    defenceStab = 40
                    defenceSlash = 40
                    defenceCrush = 40
                    defenceMagic = 40
                    defenceRanged = 40
                }
                anims {
                    death = deathAnimFor(key)
                }
            }
        }.onFailure { logger.warn { "inferno: combat def for '$key' failed: ${it.message}" } }
    }

    private companion object {
        // The Jal- roster — rev-228 cache ids via RSCM (7691..7707).
        const val NIB = "npc.jalnib"
        const val MEJRAH = "npc.jalmejrah"
        const val AK = "npc.jalak"
        const val AK_MEJ = "npc.jalakrekmej"
        const val AK_XIL = "npc.jalakrekxil"
        const val AK_KET = "npc.jalakrekket"
        const val IMKOT = "npc.jalimkot"
        const val XIL = "npc.jalxil"
        const val ZEK = "npc.jalzek"
        const val JALJAD = "npc.jaltokjad"
        const val JAD_HEALER = "npc.ythurkot_7701"
        const val XIL_ZUKWAVE = "npc.jalxil_7702"
        const val ZEK_ZUKWAVE = "npc.jalzek_7703"
        const val JALJAD_ZUKWAVE = "npc.jaltokjad_7704"
        const val ZUK_HEALER = "npc.ythurkot_7705"
        const val ZUK = "npc.tzkalzuk"
        const val GLYPH = "npc.ancestral_glyph"
        const val KET_KEH = "npc.tzhaarketkeh"

        // The real Inferno arena (region 9043, mapdump-verified: open x2257-2285, z5329-5358).
        val ARENA_AREA = Area(2240, 5312, 2303, 5375)
        val ENTRY_TILE = Tile(2271, 5331, 0)
        val CENTER = Tile(2271, 5344, 0)
        val SPAWN_POINTS = listOf(
            Tile(2261, 5335, 0), Tile(2281, 5335, 0), Tile(2261, 5352, 0), Tile(2281, 5352, 0),
        )
        // Zuk anchors at the north edge (SW tile walkable; his bulk hangs over the lava).
        val ZUK_TILE = Tile(2265, 5357, 0)
        const val GLYPH_Z = 5355     // the shield's patrol row (source coords)
        const val GLYPH_MIN_X = 2259
        const val GLYPH_MAX_X = 2283

        // The gatekeeper stands beside Mej-Jal at the live Fight Cave entrance (region 9551).
        const val ENTRANCE_REGION = 9551
        val GATEKEEPER_TILE = Tile(2416, 5115, 0)

        const val ENTRY_DELAY = 8
        const val WAVE_DELAY = 5

        // Max hits / tuning — OSRS-flavoured, compressed.
        const val MEJRAH_MAX = 7
        const val AK_MAX = 14
        const val AKLET_MAX = 6
        const val IMKOT_MAX = 49
        const val IMKOT_DIG_TICKS = 12
        const val XIL_MAX = 46
        const val ZEK_MAX = 70
        const val JALJAD_MAX = 113
        const val JAD_TELEGRAPH = 3
        const val RESURRECTS_PER_WAVE = 2

        const val ZUK_HP = 1200
        const val ZUK_MAX = 110      // UNBLOCKABLE — the glyph is the only defence
        const val ZUK_SPEED = 10     // ticks per blast
        const val GLYPH_SPEED = 2    // ticks per glyph step

        const val JAD_HEALER_COUNT = 3
        const val ZUK_HEALER_COUNT = 4
        const val HEAL_INTERVAL = 2
        const val HEAL_PER_PULSE = 3

        // The hardest content pays the best ticket rate: ~4x a Fight Cave clear for a longer,
        // far more supply-hungry run (Boss-shop prices: shark 4, prayer pot 8, brew 12).
        const val CLEAR_BOSS_POINTS = 400
        const val WAVE_CONSOLATION = 10 // per wave cleared on a failed run (max 120 dying at Zuk)
        const val PET_ODDS = 1000

        // Best-known OSRS anim/gfx ids — cosmetic if wrong; TUNE in-game.
        const val JALJAD_MELEE_ANIM = 7590
        const val JALJAD_RANGE_ANIM = 7593
        const val JALJAD_MAGIC_ANIM = 7592
        const val JALJAD_MAGIC_PROJ = 448
        const val JALJAD_MAGIC_HIT_GFX = 157
        const val JALJAD_RANGE_HIT_GFX = 451
        const val MEJRAH_ANIM = 7578
        const val IMKOT_ANIM = 7597
        const val XIL_ANIM = 7605
        const val ZEK_ANIM = 7610
        const val ZUK_ANIM = 7566
        const val ZUK_PROJ = 1375
        const val RANGE_GFX = 443
        const val MAGIC_GFX = 445
    }
}
