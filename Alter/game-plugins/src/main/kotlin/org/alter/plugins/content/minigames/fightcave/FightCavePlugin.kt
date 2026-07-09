package org.alter.plugins.content.minigames.fightcave

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
import org.alter.game.model.attr.ARENA_BEST_WAVE_ATTR
import org.alter.game.model.attr.NO_LOOT_ATTR
import org.alter.game.model.combat.CombatClass
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

private val logger = KotlinLogging.logger {}

/**
 * **Fight Cave — TzTok-Jad** (the OSRS fire-cape minigame, RSPS-compressed).
 *
 * Design: **OSRS-authentic mechanics, compressed length** — [waves] escalating waves of the real
 * TzHaar cave monsters (real ids, OSRS-exact stats) ending in a faithful **TzTok-Jad**:
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

    private data class WaveSpawn(val npc: String, val count: Int)

    /** The compressed OSRS escalation — same monsters, same order of introduction, same
     *  classic "2× Ket-Zek then Jad" finale, ~15-20 min instead of 63 waves. */
    private val waves = listOf(
        listOf(WaveSpawn(TZKIH, 2)),
        listOf(WaveSpawn(TZKEK, 1)),
        listOf(WaveSpawn(TZKEK, 2)),
        listOf(WaveSpawn(TOKXIL, 1)),
        listOf(WaveSpawn(TOKXIL, 1), WaveSpawn(TZKIH, 2)),
        listOf(WaveSpawn(YTMEJKOT, 1)),
        listOf(WaveSpawn(YTMEJKOT, 1), WaveSpawn(TOKXIL, 1)),
        listOf(WaveSpawn(KETZEK, 1)),
        listOf(WaveSpawn(KETZEK, 1), WaveSpawn(YTMEJKOT, 1)),
        listOf(WaveSpawn(KETZEK, 2)),
        listOf(WaveSpawn(JAD, 1)),
    )

    /** One live run: a player alone in their private copy of the cave. */
    private class Session(val owner: Player, val instance: RaidInstance, val practice: Boolean) {
        var wave = 0
        var breather = ENTRY_DELAY
        val alive = mutableListOf<Npc>()
        var jad: Npc? = null
        var healersSpawned = false
        val healers = mutableListOf<Npc>()
        var healPulse = 0
    }

    private val sessions = mutableListOf<Session>()
    private val caveTimer = TimerKey()
    private val fireCape = getRSCM("item.fire_cape")

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
        onCommand("arena", description = "Enter the Fight Cave (11 waves, Fire cape)") { start(player, practice = false) }
        onCommand("jad", description = "Practice TzTok-Jad (no rewards)") { start(player, practice = true) }
        onCommand("leave", description = "Flee the Fight Cave") { leave(player) }

        // Scripted attackers. The rest (Tz-Kek, Yt-MejKot, Yt-HurKot) melee via the default AI.
        onNpcCombat(TZKIH) { npc.queue { npc.tzKihCombat(this) } }
        onNpcCombat(TOKXIL) { npc.queue { npc.tokXilCombat(this) } }
        onNpcCombat(KETZEK) { npc.queue { npc.ketZekCombat(this) } }
        onNpcCombat(JAD) { npc.queue { npc.jadCombat(this) } }

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

    private fun start(player: Player, practice: Boolean) {
        if (sessionOf(player) != null) { player.message("You're already in the Fight Cave."); return }
        val instance = RaidInstance.allocate(world, CAVE_AREA, exitTile = world.gameContext.home, owner = player.uid)
        if (instance == null) { player.message("The instance space is full right now — try again shortly."); return }
        val s = Session(player, instance, practice)
        if (practice) s.wave = waves.size - 1 // straight to the Jad wave
        sessions += s
        player.moveTo(instance.translate(ENTRY_TILE))
        if (practice) {
            player.message("<col=ff0000>Practice mode:</col> TzTok-Jad only — no cape, no tickets. Watch his attacks!")
        } else {
            player.message("<col=ff0000>Welcome to the Fight Cave.</col> Survive ${waves.size} waves with your own supplies. Use ::leave to flee.")
        }
    }

    private fun leave(player: Player) {
        val s = sessionOf(player) ?: run { player.message("You're not in the Fight Cave."); return }
        player.message("You flee the Fight Cave.")
        recordBest(s, s.wave - 1)
        consolation(s)
        cleanup(s, teleport = true)
    }

    /** Failed-run payout (the OSRS tokkul-per-wave idea): partial runs still burn supplies, so
     *  they pay partial tickets. Victory pays [CLEAR_BOSS_POINTS] instead, practice pays nothing. */
    private fun consolation(s: Session) {
        if (s.practice) return
        val cleared = s.wave - 1
        if (cleared <= 0) return
        val tickets = cleared * WAVE_CONSOLATION
        s.owner.awardTickets(PointKind.BOSS, tickets)
        s.owner.message("<col=ffae00>$cleared wave${if (cleared == 1) "" else "s"} conquered: +$tickets Boss Tickets.</col>")
    }

    private fun tick(s: Session) {
        val p = s.owner
        if (p.index < 0) { cleanup(s, teleport = false); return }
        if (!s.instance.contains(p.tile)) { recordBest(s, s.wave - 1); consolation(s); cleanup(s, teleport = false); return } // teleported out

        healerLogic(s)

        // Jad's death wins the run immediately — surviving healers don't hold it hostage (OSRS).
        if (s.wave >= waves.size) {
            val jad = s.jad
            if (jad != null && (jad.index < 0 || jad.isDead() || !world.npcs.contains(jad))) { victory(s); return }
        }

        val wasFighting = s.alive.isNotEmpty()
        s.alive.removeAll { it.index < 0 || it.isDead() || !world.npcs.contains(it) }
        if (s.alive.isNotEmpty()) return

        if (wasFighting) { // wave just cleared
            if (s.wave >= waves.size) { victory(s); return }
            p.message("<col=007f00>Wave ${s.wave} defeated!</col>")
            s.breather = WAVE_DELAY
            return
        }
        if (s.breather > 0) { s.breather--; if (s.breather > 0) return }

        s.wave++
        val def = waves[s.wave - 1]
        if (s.wave == waves.size) {
            p.message("<col=ff0000>TzTok-Jad emerges! Watch his stance — slam is Ranged, fire-breath is Magic!</col>")
            val jad = spawnWaveNpc(s, JAD, JAD_SPAWN)
            s.jad = jad
        } else {
            p.message("<col=ff0000>Wave ${s.wave}/${waves.size}...</col>")
            def.forEach { ws -> repeat(ws.count) { spawnWaveNpc(s, ws.npc, SPAWN_POINTS.random()) } }
        }
    }

    /** Spawn one wave npc at [sourceTile] (source-cave coords) inside the session's instance.
     *  [engage] = false for healers, who come for Jad, not the player. */
    private fun spawnWaveNpc(s: Session, key: String, sourceTile: Tile, engage: Boolean = true): Npc {
        val base = s.instance.translate(sourceTile)
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
                s.healers += spawnWaveNpc(s, YTHURKOT, JAD_SPAWN, engage = false) // heal, don't fight — until provoked
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
        recordBest(s, waves.size)
        p.awardTickets(PointKind.BOSS, CLEAR_BOSS_POINTS)
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
        logger.info { "FIGHTCAVE ${p.username} cleared all ${waves.size} waves" }
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
        if (teleport && s.owner.index >= 0) s.owner.moveTo(world.gameContext.home)
    }

    // ───────────────────────────── scripted combat ─────────────────────────────

    /** Tz-Kih: weak melee bat that saps prayer on every attack (drains more when it connects). */
    private suspend fun Npc.tzKihCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 1, projectile = false) && isAttackDelayReady()) {
                val landed = bossMelee(target, TZKIH_MAX)
                if (target is Player) {
                    target.getSkills().alterCurrentLevel(Skills.PRAYER, if (landed) -3 else -1, capValue = 0)
                    target.message("Tz-Kih drains your Prayer!")
                }
                postAttackLogic(target)
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
            if (moveToAttackRange(task, target, distance = 8, projectile = true) && isAttackDelayReady()) {
                animate(TOKXIL_ANIM)
                if (tile.isWithinRadius(target.tile, 2)) {
                    bossMelee(target, TOKXIL_MAX)
                } else {
                    bossProjectile(target, CombatClass.RANGED, TOKXIL_MAX, gfx = TOKXIL_GFX)
                }
                postAttackLogic(target)
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
            if (moveToAttackRange(task, target, distance = 8, projectile = true) && isAttackDelayReady()) {
                animate(KETZEK_ANIM)
                if (tile.isWithinRadius(target.tile, 2) && world.random(1) == 0) {
                    bossMelee(target, KETZEK_MAX)
                } else {
                    bossProjectile(target, CombatClass.MAGIC, KETZEK_MAX, gfx = KETZEK_GFX)
                }
                postAttackLogic(target)
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
            if (moveToAttackRange(task, target, distance = 15, projectile = true) && isAttackDelayReady()) {
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
        chatNpc(p, "You want the cape of fire, JalYt? Then prove yourself in the cave. ${waves.size} waves — my kin, each stronger than the last — and at the end, TzTok-Jad himself.", npc = id, title = "TzHaar-Mej-Jal")
        chatNpc(p, "Bring your own food and prayers. The Tz-Kih sap your faith, the Tz-Kek split when broken, and Jad... watch his stance. Slam means arrows, fire-breath means magic. Pray wrong and you die.", npc = id, title = "TzHaar-Mej-Jal")
        when (options(p, "I'm ready. (enter the Fight Cave)", "Let me practice against TzTok-Jad. (no rewards)", "What do I get?", "Not now.")) {
            1 -> start(p, practice = false)
            2 -> start(p, practice = true)
            3 -> {
                chatNpc(p, "Survive all ${waves.size} waves and the cape of fire is yours, JalYt — and Boss tickets for every clear. Impress the cave enough and a little TzRek-Jad may follow you home.", npc = id, title = "TzHaar-Mej-Jal")
                chatNpc(p, "Die and you lose nothing but your pride — the cave keeps no corpses. Your best wave is remembered.", npc = id, title = "TzHaar-Mej-Jal")
            }
            4 -> chatPlayer(p, "Maybe later.")
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
        caveDef(YTMEJKOT, hp = 80, att = 160, str = 240, def = 120)
        caveDef(KETZEK, hp = 160, att = 320, str = 480, def = 240, mag = 480)
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

    private companion object {
        // The fight-cave wave roster — rev-228 cache ids via RSCM (3116..3128).
        const val TZKIH = "npc.tzkih_3116"
        const val TZKEK = "npc.tzkek_3118"
        const val TZKEK_SMALL = "npc.tzkek_3120"
        const val TOKXIL = "npc.tokxil_3121"
        const val YTMEJKOT = "npc.ytmejkot"
        const val KETZEK = "npc.ketzek"
        const val JAD = "npc.tztokjad"
        const val YTHURKOT = "npc.ythurkot"
        const val MEJ_JAL = "npc.tzhaarmejjal"

        // The real TzHaar Fight Cave (region 9551, mapdump-verified). The whole region is
        // instanced per run; tiles below are SOURCE coords (translate() maps them in).
        const val CAVE_REGION = 9551
        val CAVE_AREA = Area(2368, 5056, 2431, 5119)
        val ENTRY_TILE = Tile(2411, 5114, 0)      // NE entrance strip
        val GAME_MASTER_TILE = Tile(2413, 5115, 0) // LIVE map — beside the portal landing (2413,5117)
        val JAD_SPAWN = Tile(2400, 5088, 0)        // heart of the main chamber
        val SPAWN_POINTS = listOf(                  // mapdump-verified walkable corners
            Tile(2408, 5097, 0), Tile(2392, 5077, 0), Tile(2387, 5095, 0), Tile(2403, 5082, 0),
        )

        const val ENTRY_DELAY = 8  // ticks before wave 1 (get your bearings)
        const val WAVE_DELAY = 5   // breather between waves

        // Max hits — OSRS-exact.
        const val TZKIH_MAX = 4
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
