package org.alter.plugins.content.minigames.fightcave

import dev.openrune.cache.CacheManager.getNpc
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.*
import org.alter.api.dsl.setCombatDef
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Area
import org.alter.game.model.Direction
import org.alter.game.model.PlayerUID
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.ARENA_BEST_WAVE_ATTR
import org.alter.game.model.attr.NO_LOOT_ATTR
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.model.move.walkTo
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

private val logger = KotlinLogging.logger {}

/**
 * **Fight Cave — TzTok-Jad** (the OSRS fire-cape minigame, RSPS-compressed).
 *
 * Design: **the real cave, the donor's wave engine** — the Kronos rev-184 `FightCaves.java`
 * wave table (greedy decomposition + the doubled unique waves at 14/30/62) and its 15-slot
 * spawn-point rotation, entering at wave [START_WAVE] (the donor's default; one constant flips
 * the full 1-63 grind). Waves of the real TzHaar cave monsters end in a faithful **TzTok-Jad**:
 *  - Jad telegraphs every attack — front-leg slam = RANGED, rearing fire-breath = MAGIC — and the
 *    protection check happens at **impact**, ~3 ticks after the animation, so prayer switching on
 *    the telegraph works exactly like OSRS. Max hit [JAD_MAX]: unprayed hits are lethal.
 *  - At half HP four **Yt-HurKot** healers spawn and heal him unless dragged off (attack them).
 *  - **Tz-Kih** drain prayer on every attack; big **Tz-Kek** split in two on death; **Tok-Xil**
 *    ranges you; **Ket-Zek** mages you.
 * Bring your own supplies (the cave is a consumable sink); wave mobs drop no loot.
 *
 * Each fighter gets a **private instance** of the real cave (region 9551) via [RaidInstance] —
 * any number of concurrent runs, and instanced deaths are automatically safe (SafeDeaths).
 * Entry: **TzHaar-Mej-Jal** at the live cave entrance (the teleport portal lands beside him),
 * or `::arena`. `::jad` = practice mode (Jad only, no rewards). `::leave` bails out.
 *
 * First full clear = **Fire cape**; every clear = Boss tickets + a **TzRek-Jad** pet roll.
 */
class FightCavePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    /**
     * The REAL cave's wave table, from the Kronos rev-184 donor (`FightCaves.beginWave`):
     * unique doubled waves at 14/30/62 and Jad at 63; every other wave is the greedy
     * decomposition (31 → Ket-Zek, 15 → Yt-MejKot, 7 → Tok-Xil, 3 → Tz-Kek, 1 → Tz-Kih)
     * that produces the authentic escalation. A full run enters at [START_WAVE]
     * (the donor's default) — flip that one constant for the full 1-63 grind.
     */
    private fun waveSpawnKeys(wave: Int): List<String> =
        when (wave) {
            63 -> listOf(JAD)
            62 -> listOf(KETZEK, KETZEK2)
            30 -> listOf(YTMEJKOT, YTMEJKOT2)
            14 -> listOf(TOKXIL, TOKXIL2)
            else ->
                buildList {
                    var w = wave
                    while (w >= 31) { w -= 31; add(KETZEK) }
                    while (w >= 15) { w -= 15; add(YTMEJKOT) }
                    while (w >= 7) { w -= 7; add(TOKXIL) }
                    while (w >= 3) { w -= 3; add(TZKEK) }
                    while (w > 0) { w--; add(TZKIH) }
                }
        }

    /** One live run: a player alone in their private copy of the cave. */
    private class Session(
        val owner: Player,
        val instance: RaidInstance,
        val practice: Boolean,
        val startWave: Int,
        rotationSeed: Int,
    ) {
        var wave = startWave - 1
        var breather = ENTRY_DELAY
        val alive = mutableListOf<Npc>()
        var jad: Npc? = null
        var healersSpawned = false
        val healers = mutableListOf<Npc>()
        var healPulse = 0

        /** The donor's spawn-point rotation: each wave starts from the wave offset, each
         *  spawn advances the spawn offset, and a cleared wave bumps the wave offset. */
        var waveRotationOffset = rotationSeed
        var spawnRotationOffset = rotationSeed

        fun nextSpawnTile(): Tile {
            val t = ROTATION_POINTS[spawnRotationOffset]
            spawnRotationOffset = (spawnRotationOffset + 1) % ROTATION_POINTS.size
            return t
        }

        fun advanceWaveRotation() {
            waveRotationOffset = (waveRotationOffset + 1) % ROTATION_POINTS.size
            spawnRotationOffset = waveRotationOffset
        }
    }

    private val sessions = mutableListOf<Session>()
    private val caveTimer = TimerKey()
    private val fireCape = getRSCM("item.fire_cape")
    private val tokkulItem = getRSCM("item.tokkul")

    init {
        registerCombatDefs()

        onWorldInit {
            world.timers[caveTimer] = 1
            // Force-load the live cave region so Mej-Jal spawns and the instance allocator has
            // collision/objects to copy even before any player visits. Spawn DIRECTLY — the
            // KotlinPlugin.spawnNpc queue is consumed before onWorldInit runs (Wizard Tower lesson).
            runCatching { world.definitions.loadRegions(world, world.chunks, intArrayOf(CAVE_REGION)) }
                .onFailure { logger.error(it) { "fight-cave: region force-load failed" } }
            runCatching {
                val n = Npc(getRSCM(MEJ_JAL), GAME_MASTER_TILE, world)
                n.walkRadius = 0
                n.lastFacingDirection = Direction.NORTH
                world.spawn(n)
                n.setActive(true)
            }.onFailure { logger.warn { "fight-cave: game-master '$MEJ_JAL' not spawned: ${it.message}" } }
        }
        onTimer(caveTimer) {
            sessions.toList().forEach { s ->
                runCatching { tick(s) }.onFailure { e -> logger.error(e) { "fight-cave session tick failed" } }
            }
            world.timers[caveTimer] = 1
        }

        bindGameMaster()
        onCommand("arena", description = "Enter the Fight Cave (waves $START_WAVE-$FINAL_WAVE, Fire cape)") { start(player, practice = false) }
        onCommand("jad", description = "Practice TzTok-Jad (no rewards)") { start(player, practice = true, practiceWave = FINAL_WAVE) }
        onCommand("leave", description = "Flee the Fight Cave") { leave(player) }

        // Scripted attackers. ALL wave mobs are scripted so they (a) honour Protect prayers — the
        // default NPC melee ignores overheads entirely, so an unscripted mob "hits through prayer" —
        // and (b) actually walk to the player (the shared moveToAttackRange is a range-check only, so
        // an unscripted-approach scripted mob would idle until walked onto).
        onNpcCombat(TZKIH) { npc.queue { npc.tzKihCombat(this) } }
        onNpcCombat(TOKXIL) { npc.queue { npc.tokXilCombat(this) } }
        onNpcCombat(TOKXIL2) { npc.queue { npc.tokXilCombat(this) } }
        onNpcCombat(KETZEK) { npc.queue { npc.ketZekCombat(this) } }
        onNpcCombat(KETZEK2) { npc.queue { npc.ketZekCombat(this) } }
        onNpcCombat(JAD) { npc.queue { npc.jadCombat(this) } }
        onNpcCombat(TZKEK) { npc.queue { npc.caveMeleeCombat(this, TZKEK_MAX) } }
        onNpcCombat(TZKEK_SMALL) { npc.queue { npc.caveMeleeCombat(this, TZKEK_SMALL_MAX) } }
        onNpcCombat(YTMEJKOT) { npc.queue { npc.caveMeleeCombat(this, YTMEJKOT_MAX) } }
        onNpcCombat(YTMEJKOT2) { npc.queue { npc.caveMeleeCombat(this, YTMEJKOT_MAX) } }
        onNpcCombat(YTHURKOT) { npc.queue { npc.caveMeleeCombat(this, YTHURKOT_MAX) } }

        // Big Tz-Kek splits into two small ones where it fell (OSRS behaviour).
        onNpcDeath(TZKEK) {
            val s = sessions.firstOrNull { it.instance.contains(npc.tile) } ?: return@onNpcDeath
            repeat(2) { spawnWaveNpc(s, TZKEK_SMALL, npc.tile) }
        }

        onPlayerPreDeath {
            sessionOf(player)?.let { s ->
                player.message("<col=ff0000>You were defeated in the Fight Cave on wave ${s.wave}.</col>")
                recordBest(s, s.wave - 1)
                consolation(s)
                cleanup(s, teleport = false) // death sequence + instance DEALLOCATE_ON_DEATH handle the rest
            }
        }
        onLogout { sessionOf(player)?.let { cleanup(it, teleport = false) } }
    }

    // ───────────────────────────── session lifecycle ─────────────────────────────

    private fun sessionOf(p: Player): Session? = sessions.firstOrNull { it.owner === p }

    private fun start(player: Player, practice: Boolean, practiceWave: Int = FINAL_WAVE) {
        if (sessionOf(player) != null) { player.message("You're already in the Fight Cave."); return }
        val instance = RaidInstance.allocate(world, CAVE_AREA, exitTile = world.gameContext.home, owner = player.uid)
        if (instance == null) { player.message("The instance space is full right now — try again shortly."); return }
        val startWave = if (practice) practiceWave.coerceIn(1, FINAL_WAVE) else START_WAVE
        val s = Session(player, instance, practice, startWave, rotationSeed = world.random(ROTATION_POINTS.size - 1))
        sessions += s
        activeOwners += player.uid // bench the player's companions for the run (see [inCave])
        player.moveTo(instance.translate(ENTRY_TILE))
        if (practice) {
            player.message("<col=ff0000>Practice mode:</col> wave $startWave only — no cape, no tickets, no tokkul.")
        } else {
            player.message("<col=ff0000>Welcome to the Fight Cave.</col> Waves $START_WAVE-$FINAL_WAVE with your own supplies. Use ::leave to flee.")
        }
    }

    private fun leave(player: Player) {
        val s = sessionOf(player) ?: run { player.message("You're not in the Fight Cave."); return }
        player.message("You flee the Fight Cave.")
        recordBest(s, s.wave - 1)
        consolation(s)
        cleanup(s, teleport = true)
    }

    /** Failed-run payout: partial runs still burn supplies, so they pay partial tickets plus
     *  the donor's TokKul consolation. Victory pays [CLEAR_BOSS_POINTS] instead; practice pays nothing. */
    private fun consolation(s: Session) {
        if (s.practice) return
        val cleared = s.wave - 1
        val clearedThisRun = cleared - (s.startWave - 1)
        if (clearedThisRun <= 0) return
        val tickets = clearedThisRun * WAVE_CONSOLATION
        s.owner.awardTickets(PointKind.BOSS, tickets)
        val tokkul = tokkulFor(cleared)
        if (tokkul > 0) grant(s.owner, tokkulItem, tokkul)
        s.owner.message("<col=ffae00>$clearedThisRun wave${if (clearedThisRun == 1) "" else "s"} conquered: +$tickets Boss Tickets, +$tokkul TokKul.</col>")
    }

    /** The donor's TokKul curve (`FightCaves.stop`): 2 + (lastWave - 50) × (3 + lastWave). */
    private fun tokkulFor(lastWave: Int): Int = maxOf(0, 2 + (lastWave - 50) * (3 + lastWave))

    private fun grant(p: Player, item: Int, amount: Int) {
        val add = p.inventory.add(item = item, amount = amount, assureFullInsertion = false)
        if (add.completed < amount) p.bank.add(item, amount - add.completed)
    }

    private fun tick(s: Session) {
        val p = s.owner
        if (p.index < 0) { cleanup(s, teleport = false); return }
        if (!s.instance.contains(p.tile)) { recordBest(s, s.wave - 1); consolation(s); cleanup(s, teleport = false); return } // teleported out

        healerLogic(s)

        // Jad's death wins the run immediately — surviving healers don't hold it hostage (OSRS).
        if (s.wave >= FINAL_WAVE) {
            val jad = s.jad
            if (jad != null && (jad.index < 0 || jad.isDead() || !world.npcs.contains(jad))) { victory(s); return }
        }

        val wasFighting = s.alive.isNotEmpty()
        s.alive.removeAll { it.index < 0 || it.isDead() || !world.npcs.contains(it) }
        if (s.alive.isNotEmpty()) return

        if (wasFighting) { // wave just cleared
            if (s.wave >= FINAL_WAVE) { victory(s); return }
            if (s.practice) { practiceFinish(s); return } // one chosen wave, then out (donor behaviour)
            p.message("<col=007f00>Wave ${s.wave} defeated!</col>")
            s.advanceWaveRotation() // donor: each cleared wave bumps the spawn rotation
            s.breather = WAVE_DELAY
            return
        }
        if (s.breather > 0) { s.breather--; if (s.breather > 0) return }

        s.wave++
        if (s.wave == FINAL_WAVE) {
            p.message("<col=ff0000>Final Challenge! TzTok-Jad emerges — slam is Ranged, fire-breath is Magic!</col>")
            val jad = spawnWaveNpc(s, JAD, s.nextSpawnTile())
            s.jad = jad
        } else {
            p.message("<col=ff0000>Wave ${s.wave}...</col>")
            waveSpawnKeys(s.wave).forEach { key -> spawnWaveNpc(s, key, s.nextSpawnTile()) }
        }
    }

    private fun practiceFinish(s: Session) {
        s.owner.message("<col=ffae00>Practice wave ${s.wave} defeated! Now do it for real.</col>")
        cleanup(s, teleport = true)
    }

    /** Spawn one wave npc at [sourceTile] (source-cave coords) inside the session's instance.
     *  [engage] = false for healers, who come for Jad, not the player. */
    private fun spawnWaveNpc(s: Session, key: String, sourceTile: Tile, engage: Boolean = true): Npc {
        // [sourceTile] is normally a source-cave coord that we translate into instance space — but the
        // Tz-Kek split passes the dying npc's tile, which is ALREADY instance-space. Translating that
        // again lands the smalls off-map (they never appear AND never clear, hanging the wave). Guard
        // like the Inferno does: only translate a tile that isn't already inside the instance.
        val base = if (s.instance.contains(sourceTile)) sourceTile else s.instance.translate(sourceTile)
        val tile = world.findRandomTileAround(base, radius = 2) ?: base
        val npc = Npc(getRSCM(key), tile, world)
        npc.walkRadius = 15
        world.spawn(npc)
        npc.respawns = false // AFTER world.spawn — setNpcDefaults would clobber it
        npc.setActive(true)
        npc.attr[NO_LOOT_ATTR] = true // the cape is the reward — no per-kill loot in the cave
        if (engage) npc.attack(s.owner)
        s.alive += npc
        return npc
    }

    /** Half-HP healer spawn + their heal pulse. Healers stand by Jad restoring him until the
     *  player attacks them (combat target set → they stop healing and fight back). */
    private fun healerLogic(s: Session) {
        val jad = s.jad ?: return
        if (jad.index < 0 || jad.isDead() || !world.npcs.contains(jad)) return
        if (!s.healersSpawned && jad.getCurrentHp() <= jad.getMaxHp() / 2) {
            s.healersSpawned = true
            s.owner.message("<col=8f0000>Yt-HurKot swarm to TzTok-Jad's aid — drag them off him!</col>")
            repeat(HEALER_COUNT) {
                s.healers += spawnWaveNpc(s, YTHURKOT, s.nextSpawnTile(), engage = false) // heal, don't fight — until provoked
            }
        }
        if (s.healersSpawned) {
            s.healPulse++
            if (s.healPulse % HEAL_INTERVAL == 0) {
                s.healers.removeAll { it.index < 0 || it.isDead() || !world.npcs.contains(it) }
                s.healers.forEach { h ->
                    if (h.getCombatTarget() == null && jad.getCurrentHp() < jad.getMaxHp()) {
                        jad.setCurrentHp(minOf(jad.getMaxHp(), jad.getCurrentHp() + HEAL_PER_PULSE))
                    }
                }
            }
        }
    }

    private fun victory(s: Session) {
        val p = s.owner
        if (s.practice) {
            p.message("<col=ffae00>TzTok-Jad falls! A fine practice bout — now do it for real.</col>")
            cleanup(s, teleport = true)
            return
        }
        recordBest(s, FINAL_WAVE)
        p.awardTickets(PointKind.BOSS, CLEAR_BOSS_POINTS)
        // The donor's TokKul payout for the full clear (FightCaves.stop, killedJad branch).
        val tokkul = tokkulFor(FINAL_WAVE - 1) + CLEAR_TOKKUL_BONUS
        grant(p, tokkulItem, tokkul)
        p.message("<col=ffae00>TzHaar-Mej-Jal pays you $tokkul TokKul.</col>")
        // A cape EVERY clear (OSRS behaviour): capes are tradeable here — clears supply the
        // player market, and the Inferno's entry sacrifice burns them back out of it.
        val add = p.inventory.add(item = fireCape, amount = 1, assureFullInsertion = false)
        if (add.completed == 0) p.bank.add(fireCape, 1)
        p.message("<col=ffae00>You are awarded a Fire cape!</col>")
        if (CollectionLog.record(p, fireCape)) {
            world.players.forEach { it.message("<col=ff0000>News: ${p.username} has conquered the Fight Cave and earned their first <col=ffae00>Fire cape</col>!</col>") }
        }
        if (world.chance(1, PET_ODDS)) {
            val pet = getRSCM("item.tzrekjad")
            val add = p.inventory.add(item = pet, amount = 1, assureFullInsertion = false)
            if (add.completed == 0) p.bank.add(pet, 1)
            world.players.forEach { it.message("<col=ff0000>News: ${p.username} just received <col=ffae00>TzRek-Jad</col> from the Fight Cave!</col>") }
            if (CollectionLog.record(p, pet)) p.message("<col=ffae00>New Collection Log slot: TzRek-Jad!</col>")
        }
        p.message("<col=ffae00>You have conquered the Fight Cave!</col> +$CLEAR_BOSS_POINTS Boss Tickets.")
        logger.info { "FIGHTCAVE ${p.username} cleared waves ${s.startWave}-$FINAL_WAVE" }
        cleanup(s, teleport = true)
    }

    private fun recordBest(s: Session, reached: Int) {
        if (s.practice) return
        if (reached > (s.owner.attr[ARENA_BEST_WAVE_ATTR] ?: 0)) s.owner.attr[ARENA_BEST_WAVE_ATTR] = reached
    }

    private fun cleanup(s: Session, teleport: Boolean) {
        (s.alive + s.healers).forEach { if (it.index >= 0 && world.npcs.contains(it)) { it.setCurrentHp(0); world.remove(it) } }
        s.alive.clear()
        s.healers.clear()
        s.jad = null
        sessions.remove(s)
        activeOwners.remove(s.owner.uid) // un-bench the player's companions
        if (teleport && s.owner.index >= 0) s.owner.moveTo(world.gameContext.home)
    }

    // ───────────────────────────── scripted combat ─────────────────────────────

    /** Generic scripted melee for the cave's plain melee mobs (Tz-Kek + smalls, Yt-MejKot, Yt-HurKot):
     *  walk into range, animate a model-appropriate swing, and hit through [bossMelee] so Protect from
     *  Melee is honoured (the default NPC AI ignores overheads). */
    private suspend fun Npc.caveMeleeCombat(task: QueueTask, maxHit: Int) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 1, projectile = false)) {
                if (isAttackDelayReady()) {
                    animate(CombatConfigs.getAttackAnimation(this))
                    bossMelee(target, maxHit)
                    postAttackLogic(target)
                }
            } else {
                walkTo(target.tile) // the shared range check never moves us — approach explicitly
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    /** Tz-Kih: weak melee bat that saps prayer on every attack (drains more when it connects). */
    private suspend fun Npc.tzKihCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 1, projectile = false)) {
                if (isAttackDelayReady()) {
                    animate(CombatConfigs.getAttackAnimation(this)) // Tz-Kih used to swing invisibly (no animate)
                    val landed = bossMelee(target, TZKIH_MAX)
                    if (target is Player) {
                        target.getSkills().alterCurrentLevel(Skills.PRAYER, if (landed) -3 else -1, capValue = 0)
                        target.message("Tz-Kih drains your Prayer!")
                    }
                    postAttackLogic(target)
                }
            } else {
                walkTo(target.tile)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    /** Tok-Xil: ranger — spikes from afar, bites up close. Pray Missiles. */
    private suspend fun Npc.tokXilCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 8, projectile = true)) {
                if (isAttackDelayReady()) {
                    animate(TOKXIL_ANIM)
                    if (tile.isWithinRadius(target.tile, 2)) {
                        bossMelee(target, TOKXIL_MAX)
                    } else {
                        bossProjectile(target, CombatClass.RANGED, TOKXIL_MAX, gfx = TOKXIL_GFX)
                    }
                    postAttackLogic(target)
                }
            } else {
                walkTo(target.tile) // approach — the shared range check never walks us in
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    /** Ket-Zek: the orange fire-mage — big magic bolts, crushing melee in reach. Pray Magic. */
    private suspend fun Npc.ketZekCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 8, projectile = true)) {
                if (isAttackDelayReady()) {
                    animate(KETZEK_ANIM)
                    if (tile.isWithinRadius(target.tile, 2) && world.random(1) == 0) {
                        bossMelee(target, KETZEK_MAX)
                    } else {
                        bossProjectile(target, CombatClass.MAGIC, KETZEK_MAX, gfx = KETZEK_GFX)
                    }
                    postAttackLogic(target)
                }
            } else {
                walkTo(target.tile) // approach — the shared range check never walks us in
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    /**
     * TzTok-Jad — the whole point of the minigame. The style is TELEGRAPHED by the animation and
     * the protection check happens at IMPACT ([JAD_TELEGRAPH] ticks later), so switching prayer
     * after the animation starts protects you, exactly like OSRS. No hint text — read the stance.
     */
    private suspend fun Npc.jadCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (!moveToAttackRange(task, target, distance = 15, projectile = true)) {
                walkTo(target.tile) // approach — the shared range check never walks us in
            } else if (isAttackDelayReady()) {
                postAttackLogic(target) // 8-tick cycle starts at the telegraph, like OSRS
                if (tile.isWithinRadius(target.tile, 2) && world.random(2) == 0) {
                    animate(JAD_MELEE_ANIM)
                    bossMelee(target, JAD_MAX)
                } else {
                    val magic = world.random(1) == 0
                    animate(if (magic) JAD_MAGIC_ANIM else JAD_RANGE_ANIM)
                    val cls = if (magic) CombatClass.MAGIC else CombatClass.RANGED
                    prepareAttack(cls, if (magic) org.alter.game.model.combat.CombatStyle.MAGIC else org.alter.game.model.combat.CombatStyle.RANGED, org.alter.game.model.combat.AttackStyle.ACCURATE)
                    if (magic) world.spawn(createProjectile(target, gfx = JAD_MAGIC_PROJ, startHeight = 120, endHeight = 31, delay = 41, angle = 15, steepness = 20))
                    task.wait(JAD_TELEGRAPH)
                    if (target.index >= 0 && !target.isDead()) {
                        target.graphic(if (magic) JAD_MAGIC_HIT_GFX else JAD_RANGE_HIT_GFX)
                        if (target.isProtectedFrom(cls)) {
                            target.hit(damage = 0, type = HitType.BLOCK, delay = 0)
                        } else {
                            // Style-matched formula — MeleeCombatFormula throws on RANGED/MAGIC.
                            val formula = if (magic) MagicCombatFormula else RangedCombatFormula
                            val landed = formula.getAccuracy(this, target) >= world.randomDouble()
                            target.hit(damage = if (landed) world.random(JAD_MAX) else 0, type = if (landed) HitType.HIT else HitType.BLOCK, delay = 0)
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

    // ───────────────────────────── game master ─────────────────────────────

    /** Guarded like the Wizard Tower's knight: bind only actions the cache def actually has,
     *  so a missing option degrades gracefully instead of dropping the plugin at boot. */
    private fun bindGameMaster() {
        val acts = runCatching { getNpc(getRSCM(MEJ_JAL)).actions.filterNotNull().filter { it.isNotBlank() } }
            .getOrDefault(emptyList())
        acts.filter { it.equals("talk-to", true) }.forEach { act ->
            onNpcOption(MEJ_JAL, option = act) { player.queue { mejJalDialog(player) } }
        }
        if (acts.none { it.equals("talk-to", true) }) {
            logger.warn { "fight-cave: '$MEJ_JAL' cache def has no Talk-to (actions=$acts) — use ::arena / ::jad instead." }
        }
    }

    private suspend fun QueueTask.mejJalDialog(p: Player) {
        val id = runCatching { getRSCM(MEJ_JAL) }.getOrDefault(-1)
        chatNpc(p, "You want the cape of fire, JalYt? Then prove yourself in the cave. Waves $START_WAVE to $FINAL_WAVE — my kin, each stronger than the last — and at the end, TzTok-Jad himself.", npc = id, title = "TzHaar-Mej-Jal")
        chatNpc(p, "Bring your own food and prayers. The Tz-Kih sap your faith, the Tz-Kek split when broken, and Jad... watch his stance. Slam means arrows, fire-breath means magic. Pray wrong and you die.", npc = id, title = "TzHaar-Mej-Jal")
        when (options(p, "I'm ready. (enter the Fight Cave)", "Let me practice against TzTok-Jad. (no rewards)", "Let me practice a wave of my choosing.", "What do I get?", "Not now.")) {
            1 -> start(p, practice = false)
            2 -> start(p, practice = true, practiceWave = FINAL_WAVE)
            3 -> {
                val wave = inputInt(p, "Practice which wave? (1-$FINAL_WAVE)")
                if (wave in 1..FINAL_WAVE) start(p, practice = true, practiceWave = wave)
                else p.message("There is no wave $wave, JalYt.")
            }
            4 -> {
                chatNpc(p, "Survive to the end and the cape of fire is yours, JalYt — with TokKul and Boss tickets for the clear, and TokKul for every wave even if you fall. Impress the cave enough and a little TzRek-Jad may follow you home.", npc = id, title = "TzHaar-Mej-Jal")
                chatNpc(p, "Die and you lose nothing but your pride — the cave keeps no corpses. Your best wave is remembered.", npc = id, title = "TzHaar-Mej-Jal")
            }
            5 -> chatPlayer(p, "Maybe later.")
        }
    }

    // ───────────────────────────── combat defs ─────────────────────────────

    /** OSRS-exact stats (osrsreboxed) for every cave npc. All defs need hitpoints/attackSpeed/
     *  respawnDelay AND a death anim or the plugin is silently dropped at boot. */
    private fun registerCombatDefs() {
        caveDef(TZKIH, hp = 10, att = 20, str = 30, def = 15)
        caveDef(TZKEK, hp = 20, att = 40, str = 60, def = 30)
        caveDef(TZKEK_SMALL, hp = 10, att = 20, str = 30, def = 15)
        caveDef(TOKXIL, hp = 40, att = 80, str = 120, def = 60, rng = 120)
        caveDef(TOKXIL2, hp = 40, att = 80, str = 120, def = 60, rng = 120)
        caveDef(YTMEJKOT, hp = 80, att = 160, str = 240, def = 120)
        caveDef(YTMEJKOT2, hp = 80, att = 160, str = 240, def = 120)
        caveDef(KETZEK, hp = 160, att = 320, str = 480, def = 240, mag = 480)
        caveDef(KETZEK2, hp = 160, att = 320, str = 480, def = 240, mag = 480)
        caveDef(JAD, hp = 250, att = 640, str = 960, def = 480, mag = 480, rng = 960, speed = 8)
        caveDef(YTHURKOT, hp = 60, att = 140, str = 100, def = 60)
    }

    private fun caveDef(key: String, hp: Int, att: Int, str: Int, def: Int, mag: Int = 1, rng: Int = 1, speed: Int = 4) {
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
                    attackMagic = 60
                    attackRanged = 60
                    strengthBonus = 30
                    defenceStab = 30
                    defenceSlash = 30
                    defenceCrush = 30
                    defenceMagic = 30
                    defenceRanged = 30
                }
                anims {
                    death = deathAnimFor(key)
                }
            }
        }.onFailure { logger.warn { "fight-cave: combat def for '$key' failed: ${it.message}" } }
    }

    companion object {
        /** Owners currently inside a Fight Cave instance. Companions stand down while their owner is
         *  here (a companion follows into the instance, tanks Jad and the waves, and steals all the
         *  aggro — "Jad not attacking me"). Updated in [start]/[cleanup]. */
        private val activeOwners = HashSet<PlayerUID>()

        /** True while [p] is fighting in the cave — read by CompanionBrain to bench companions. */
        fun inCave(p: Player): Boolean = activeOwners.contains(p.uid)

        // The fight-cave wave roster — rev-228 cache ids via RSCM (3116..3128).
        const val TZKIH = "npc.tzkih_3116"
        const val TZKEK = "npc.tzkek_3118"
        const val TZKEK_SMALL = "npc.tzkek_3120"
        const val TOKXIL = "npc.tokxil_3121"
        const val TOKXIL2 = "npc.tokxil_3122"    // the doubled unique-wave partner (Kronos id+1)
        const val YTMEJKOT = "npc.ytmejkot"
        const val YTMEJKOT2 = "npc.ytmejkot_3124"
        const val KETZEK = "npc.ketzek"
        const val KETZEK2 = "npc.ketzek_3126"
        const val JAD = "npc.tztokjad"
        const val YTHURKOT = "npc.ythurkot"
        const val MEJ_JAL = "npc.tzhaarmejjal"

        // The real TzHaar Fight Cave (region 9551, mapdump-verified). The whole region is
        // instanced per run; tiles below are SOURCE coords (translate() maps them in).
        const val CAVE_REGION = 9551
        val CAVE_AREA = Area(2368, 5056, 2431, 5119)
        val ENTRY_TILE = Tile(2411, 5114, 0)      // NE entrance strip
        val GAME_MASTER_TILE = Tile(2413, 5115, 0) // LIVE map — beside the portal landing (2413,5117)
        // The donor's five spawn anchors (Kronos FightCaves: C/S/NW/SW/SE) and its 15-slot
        // rotation — the wave offset seeds each wave's spawn sequence and advances per wave.
        private val C = Tile(2400, 5088, 0)
        private val S = Tile(2400, 5070, 0)
        private val NW = Tile(2382, 5106, 0)
        private val SW = Tile(2380, 5071, 0)
        private val SE = Tile(2418, 5082, 0)
        val ROTATION_POINTS = listOf(SE, SW, C, NW, SW, SE, S, NW, C, SE, SW, S, NW, C, S)

        /** The run enters here (the donor's no-rank default); flip to 1 for the full grind. */
        const val START_WAVE = 50
        const val FINAL_WAVE = 63
        const val CLEAR_TOKKUL_BONUS = 4000 // donor: killedJad pays wave tokkul + 4000

        const val ENTRY_DELAY = 8  // ticks before wave 1 (get your bearings)
        const val WAVE_DELAY = 5   // breather between waves

        // Max hits — OSRS-exact.
        const val TZKIH_MAX = 4
        const val TZKEK_MAX = 11
        const val TZKEK_SMALL_MAX = 3
        const val YTMEJKOT_MAX = 19
        const val YTHURKOT_MAX = 13
        const val TOKXIL_MAX = 14
        const val KETZEK_MAX = 49
        const val JAD_MAX = 97
        const val JAD_TELEGRAPH = 3 // ticks between animation and impact (the prayer-switch window)

        const val HEALER_COUNT = 4
        const val HEAL_INTERVAL = 2   // session ticks per heal pulse
        const val HEAL_PER_PULSE = 2  // hp per un-provoked healer per pulse

        // A run burns ~90 tickets of shop supplies (15 sharks + 4 prayer pots at Boss-shop
        // prices), so the clear must beat that with margin; 150 ≈ boss-farming rates per hour.
        const val CLEAR_BOSS_POINTS = 150
        const val WAVE_CONSOLATION = 3 // per wave cleared on a failed run (max 30 dying at Jad)
        const val PET_ODDS = 1000 // TzRek-Jad per full clear — a genuine chase item

        // Best-known OSRS anim/gfx ids; a wrong id is a cosmetic miss, never a throw. TUNE in-game.
        const val JAD_MELEE_ANIM = 2655
        const val JAD_RANGE_ANIM = 2652 // slams front legs down
        const val JAD_MAGIC_ANIM = 2656 // rears up, fire-breath
        const val JAD_MAGIC_PROJ = 448
        const val JAD_MAGIC_HIT_GFX = 157
        const val JAD_RANGE_HIT_GFX = 451
        const val TOKXIL_ANIM = 2633
        const val TOKXIL_GFX = 443
        const val KETZEK_ANIM = 2647
        const val KETZEK_GFX = 445
    }
}
