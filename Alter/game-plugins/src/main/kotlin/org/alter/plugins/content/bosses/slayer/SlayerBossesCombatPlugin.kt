package org.alter.plugins.content.bosses.slayer

import dev.openrune.cache.CacheManager.getItem
import org.alter.api.EquipmentType
import org.alter.api.PrayerIcon
import org.alter.api.ProjectileType
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.TileGraphic
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.combat.AttackStyle
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.combat.CombatStyle
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.move.walkTo
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.bossMelee
import org.alter.plugins.content.bosses.bossProjectile
import org.alter.plugins.content.combat.*
import org.alter.plugins.content.combat.strategy.RangedCombatStrategy
import org.alter.rscm.RSCM.getRSCM
import kotlin.math.max

/**
 * The Slayer bosses' fights — the Kronos scripts translated onto the shared boss primitives.
 *
 *  - **Kraken**: magic bolt (anim 3992, gfx 156 → 157, max 28) from range 10; the four
 *    **tentacles** spit for 2 (gfx 162). Whirlpools never attack. Life cycle in [SlayerBossesPlugin].
 *  - **Cerberus**: adjacent 25% melee (max 23) else 50/50 magic (gfx 1242 → 1243) / ranged
 *    (gfx 1245 → 1244); a **triple attack** (anim 4490: magic, ranged, melee two ticks apart)
 *    every 66 ticks; below 400 hp **summoned souls** every 45 ticks (three ghosts walk in and
 *    each fires its style — pray it or take 30 unblockable; praying costs 30 prayer, 15 with a
 *    spectral shield); below 200 hp **lava** every 36 ticks on three tiles (10-15 standing on
 *    it, 7 beside it, then a final 15-18 / 10).
 *  - **Thermonuclear smoke devil**: 2-tick ranged spit (gfx 643, max 8); without a facemask or
 *    Slayer helmet every attack is instead 18 unblockable **smoke**.
 *  - **Skotizo**: adjacent 2-in-3 melee (max 38) else magic (anim 69, gfx 1242 → 197, max 38);
 *    every awakened **altar** hardens him (damage taken × 1/(1 + 0.25·awake)).
 *  - **Demonic gorilla**: one style at a time (melee needs adjacency, ranged gfx 1302 → 1303,
 *    magic gfx 1304 → 1305, max 31); after three misses it **switches style**; after 50 damage
 *    taken it **switches prayer** to the attacker's style (a form swap, see the plugin); a
 *    **boulder** (gfx 856 → 305) on your tile every ≥30 ticks that takes a third of your health
 *    if you don't step off.
 */
class SlayerBossesCombatPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onNpcCombat(SlayerBosses.KRAKEN) { npc.queue { npc.krakenCombat(this) } }
        onNpcCombat(SlayerBosses.TENTACLE) { npc.queue { npc.tentacleCombat(this) } }
        onNpcCombat(SlayerBosses.KRAKEN_WHIRLPOOL, SlayerBosses.TENTACLE_WHIRLPOOL) { npc.removeCombatTarget() }
        onNpcCombat(SlayerBosses.CERBERUS) { npc.queue { npc.cerberusCombat(this) } }
        onNpcCombat(SlayerBosses.THERMY) { npc.queue { npc.thermyCombat(this) } }
        onNpcCombat(SlayerBosses.SKOTIZO) { npc.queue { npc.skotizoCombat(this) } }
        SlayerBosses.ALTARS.forEach { a -> onNpcCombat(a.dormantKey, a.awakenedKey) { npc.removeCombatTarget() } }
        SlayerBosses.GORILLA_FORMS.forEach { key -> onNpcCombat(key) { npc.queue { npc.gorillaCombat(this) } } }
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private fun Npc.playersWithin(radius: Int, of: Tile = tile): List<Player> {
        val out = mutableListOf<Player>()
        world.players.forEach { p ->
            if (p.tile.height == of.height && p.tile.isWithinRadius(of, radius) && !p.isDead() && !p.invisible) out += p
        }
        return out
    }

    private fun Npc.tickDelayTo(target: Pawn): Int =
        max(1, RangedCombatStrategy.getHitDelay(getFrontFacingTile(target), target.getCentreTile()) - 1)

    // ───────────────────────────── Kraken ─────────────────────────────

    private suspend fun Npc.krakenCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (!tile.isWithinRadius(target.tile, 10)) break // the Kraken can't leave its pool
            if (isAttackDelayReady()) {
                animate(3992)
                val landed = bossProjectile(target, CombatClass.MAGIC, maxHit = 28, gfx = 156)
                if (landed) target.graphic(157, 124, tickDelayTo(target) * 30)
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    private suspend fun Npc.tentacleCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (!tile.isWithinRadius(target.tile, 10)) break
            if (isAttackDelayReady()) {
                animate(3618)
                bossProjectile(target, CombatClass.MAGIC, maxHit = 2, gfx = 162)
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    // ───────────────────────────── Cerberus ─────────────────────────────

    private suspend fun Npc.cerberusCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 10, projectile = true) && isAttackDelayReady()) {
                val now = world.currentCycle
                when {
                    (attr[CERB_COMBO_READY] ?: 0) <= now -> {
                        attr[CERB_COMBO_READY] = now + 66
                        comboAttack(target)
                    }
                    getCurrentHp() <= 400 && (attr[CERB_SOULS_READY] ?: 0) <= now -> {
                        attr[CERB_SOULS_READY] = now + 45
                        summonSouls(target)
                    }
                    getCurrentHp() <= 200 && (attr[CERB_LAVA_READY] ?: 0) <= now -> {
                        attr[CERB_LAVA_READY] = now + 36
                        spreadLava(target)
                    }
                    tile.isWithinRadius(target.tile, 1) && world.chance(1, 4) -> {
                        animate(4491)
                        bossMelee(target, maxHit = 23, style = CombatStyle.SLASH)
                    }
                    world.chance(1, 2) -> cerbMagic(target)
                    else -> cerbRanged(target)
                }
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    private fun Npc.cerbMagic(target: Pawn) {
        animate(4492)
        val landed = bossProjectile(target, CombatClass.MAGIC, maxHit = 23, gfx = 1242)
        if (landed) target.graphic(1243, 100, tickDelayTo(target) * 30)
    }

    private fun Npc.cerbRanged(target: Pawn) {
        animate(4492)
        bossProjectile(target, CombatClass.RANGED, maxHit = 23, gfx = 1245)
        target.graphic(1244, 100, tickDelayTo(target) * 30)
    }

    /** Donor `comboAttack()`: magic now, ranged two ticks later, melee two after that. */
    private fun Npc.comboAttack(target: Pawn) {
        animate(4490)
        cerbMagic(target)
        val boss = this
        world.queue {
            wait(2)
            if (boss.isDead() || boss.index < 0 || boss.getCombatTarget() !== target) return@queue
            boss.cerbRanged(target)
            wait(2)
            if (boss.isDead() || boss.index < 0 || boss.getCombatTarget() !== target) return@queue
            boss.bossMelee(target, maxHit = 23, style = CombatStyle.SLASH)
        }
    }

    /** Donor `summonSouls()`: three ghosts walk in from the north, each attacks once, then leave. */
    private fun Npc.summonSouls(target: Pawn) {
        forceChat("Aaarrrooooooo")
        animate(4494)
        val spawn = attr[SlayerBossesPlugin.SPAWN_TILE] ?: tile
        val souls = listOf(SlayerBosses.SOUL_RANGED, SlayerBosses.SOUL_MAGIC, SlayerBosses.SOUL_MELEE).shuffled()
        val boss = this
        souls.forEachIndexed { i, key ->
            val at = world.snapToWalkable(Tile(spawn.x + 1 + i, spawn.z + 18, spawn.height), maxRadius = 3)
            val soul = Npc(getRSCM(key), at, world)
            soul.respawns = false
            world.spawn(soul)
            soul.setActive(true)
            world.queue {
                soul.walkTo(world.snapToWalkable(Tile(at.x, at.z - 12, at.height), maxRadius = 3))
                wait(6 + i * 2)
                if (boss.isDead() || boss.index < 0 || target.isDead()) { world.remove(soul); return@queue }
                soul.facePawn(target)
                soulAttack(soul, key, target)
                wait(3)
                soul.walkTo(world.snapToWalkable(Tile(at.x, at.z - 2, at.height), maxRadius = 3))
                wait(6)
                if (soul.index >= 0) world.remove(soul)
            }
        }
    }

    private fun soulAttack(soul: Npc, key: String, target: Pawn) {
        val p = target as? Player ?: return
        if (!soul.tile.isWithinRadius(p.tile, 20)) return
        val icon = when (key) {
            SlayerBosses.SOUL_RANGED -> PrayerIcon.PROTECT_FROM_MISSILES
            SlayerBosses.SOUL_MAGIC -> PrayerIcon.PROTECT_FROM_MAGIC
            else -> PrayerIcon.PROTECT_FROM_MELEE
        }
        when (key) {
            SlayerBosses.SOUL_RANGED -> soul.animate(4503)
            SlayerBosses.SOUL_MAGIC -> {
                soul.animate(4504)
                world.spawn(soul.createProjectile(p, gfx = 100, type = ProjectileType.MAGIC))
            }
            else -> world.spawn(soul.createProjectile(p, gfx = 1248, type = ProjectileType.MAGIC))
        }
        if (p.hasPrayerIcon(icon)) {
            val spectral = p.equipment[EquipmentType.SHIELD.id]?.id == SPECTRAL_SPIRIT_SHIELD
            p.getSkills().alterCurrentLevel(Skills.PRAYER, -(if (spectral) 15 else 30))
            p.message("The soul's attack is turned aside by your prayer, draining it.")
        } else {
            p.hit(damage = 30, delay = 2)
        }
    }

    /** Donor `spreadLava()`: three pools — your tile plus two around the boss — that burn for six ticks. */
    private fun Npc.spreadLava(target: Pawn) {
        animate(4493)
        forceChat("Grrrrrrrrrr")
        val boss = this
        val tiles = listOf(
            Tile(target.tile.x, target.tile.z, target.tile.height),
            Tile(tile.x - 4 + world.random(getSize() + 8), tile.z - 4 + world.random(getSize() + 8), tile.height),
            Tile(tile.x - 4 + world.random(getSize() + 8), tile.z - 4 + world.random(getSize() + 8), tile.height),
        )
        tiles.forEach { t ->
            world.spawn(createProjectile(t, gfx = 1247, type = ProjectileType.MAGIC))
            world.queue {
                world.spawn(TileGraphic(t, 1246, 0))
                wait(2)
                repeat(6) {
                    val v = boss.getCombatTarget() ?: return@queue
                    if (v.tile.sameAs(t)) v.hit(damage = 10 + world.random(5), delay = 0)
                    else if (v.tile.isWithinRadius(t, 1)) v.hit(damage = 7, delay = 0)
                    wait(2)
                }
                world.spawn(TileGraphic(t, 1247, 0))
                wait(1)
                val v = boss.getCombatTarget() ?: return@queue
                if (v.tile.sameAs(t)) v.hit(damage = 15 + world.random(3), delay = 0)
                else if (v.tile.isWithinRadius(t, 1)) v.hit(damage = 10, delay = 0)
            }
        }
    }

    // ───────────────────────────── Thermonuclear smoke devil ─────────────────────────────

    private suspend fun Npc.thermyCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 8, projectile = true) && isAttackDelayReady()) {
                if (target is Player && !hasSmokeProtection(target)) {
                    target.hit(damage = 18, delay = 0)
                    target.message("<col=ff0000>The devil's smoke blinds and damages you!</col>")
                    target.message("<col=ff0000>A facemask can protect you from this attack.</col>")
                } else {
                    animate(3847)
                    bossProjectile(target, CombatClass.RANGED, maxHit = 8, gfx = 643)
                }
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    private fun hasSmokeProtection(p: Player): Boolean {
        val head = p.equipment[EquipmentType.HEAD.id]?.id ?: return false
        if (head == FACEMASK) return true
        val name = runCatching { getItem(head).name }.getOrDefault("")
        return name.contains("slayer helm", ignoreCase = true)
    }

    // ───────────────────────────── Skotizo ─────────────────────────────

    private suspend fun Npc.skotizoCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            // Every awakened altar hardens him (donor: +25% defence each).
            val awake = attr[SlayerBossesPlugin.SKOTIZO_AWAKE] ?: 0
            if (awake > 0) attr[Combat.DAMAGE_TAKE_MULTIPLIER] = 1.0 / (1.0 + 0.25 * awake) else attr.remove(Combat.DAMAGE_TAKE_MULTIPLIER)
            if (moveToAttackRange(task, target, distance = 8, projectile = true) && isAttackDelayReady()) {
                if (tile.isWithinRadius(target.tile, 1) && world.chance(2, 3)) {
                    animate(4680)
                    bossMelee(target, maxHit = 38, style = CombatStyle.SLASH)
                } else {
                    animate(69)
                    bossProjectile(target, CombatClass.MAGIC, maxHit = 38, gfx = 1242)
                    target.graphic(197, 0, tickDelayTo(target) * 30)
                }
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    // ───────────────────────────── Demonic gorilla ─────────────────────────────

    class GorillaState(
        var style: Int = 0, // 0 melee, 1 ranged, 2 magic
        var damageSinceSwitch: Int = 0,
        var misses: Int = 0,
        var boulderReady: Int = 0,
        var lastHp: Int = -1,
    )

    private suspend fun Npc.gorillaCombat(task: QueueTask) {
        val state = attr[GORILLA_STATE] ?: GorillaState(style = world.random(2)).also { attr[GORILLA_STATE] = it }
        if (state.lastHp < 0) state.lastHp = getCurrentHp()
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            // Prayer switch: 50 damage of a style → swap to the form that blocks the attacker's style.
            val hp = getCurrentHp()
            if (hp < state.lastHp) state.damageSinceSwitch += state.lastHp - hp
            state.lastHp = hp
            if (state.damageSinceSwitch >= 50) {
                state.damageSinceSwitch = 0
                val wanted = when (CombatConfigs.getCombatClass(target)) {
                    CombatClass.MELEE -> SlayerBosses.GORILLA_MELEE
                    CombatClass.RANGED -> SlayerBosses.GORILLA_RANGED
                    else -> SlayerBosses.GORILLA_MAGIC
                }
                if (getRSCM(wanted) != id) {
                    SlayerBossesPlugin.swapGorilla(world, this, wanted, state, target)
                    return
                }
            }
            if (state.style == 0 && !tile.isWithinRadius(target.tile, 1)) {
                // Melee style needs adjacency — donor switches away rather than chase forever.
                if (!moveToAttackRange(task, target, distance = 1, projectile = false)) {
                    switchStyle(state)
                    task.wait(1)
                    target = getCombatTarget() ?: break
                    continue
                }
            }
            val inRange = if (state.style == 0) tile.isWithinRadius(target.tile, 1) else moveToAttackRange(task, target, distance = 8, projectile = true)
            if (inRange && isAttackDelayReady()) {
                if (state.boulderReady <= world.currentCycle && world.chance(1, 4)) {
                    state.boulderReady = world.currentCycle + 30
                    boulder(target)
                } else {
                    val landed = when (state.style) {
                        0 -> { animate(7226); bossMelee(target, maxHit = 31, style = CombatStyle.CRUSH) }
                        1 -> {
                            animate(7227)
                            val l = bossProjectile(target, CombatClass.RANGED, maxHit = 31, gfx = 1302)
                            if (l) target.graphic(1303, 0, tickDelayTo(target) * 30)
                            l
                        }
                        else -> {
                            animate(7225)
                            val l = bossProjectile(target, CombatClass.MAGIC, maxHit = 31, gfx = 1304)
                            if (l) target.graphic(1305, 0, tickDelayTo(target) * 30)
                            l
                        }
                    }
                    // Three misses in a row (or three prayed hits) → switch style (donor `attacked()`).
                    val blocked = target is Player && target.hasPrayerIcon(
                        when (state.style) { 0 -> PrayerIcon.PROTECT_FROM_MELEE; 1 -> PrayerIcon.PROTECT_FROM_MISSILES; else -> PrayerIcon.PROTECT_FROM_MAGIC },
                    )
                    if (!landed || blocked) {
                        state.misses++
                        if (state.misses >= 3) switchStyle(state)
                    } else {
                        state.misses = 0
                    }
                }
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    private fun switchStyle(state: GorillaState) {
        state.style = when (state.style) {
            0 -> if (world.chance(1, 2)) 2 else 1
            2 -> if (world.chance(1, 2)) 0 else 1
            else -> if (world.chance(1, 2)) 2 else 0
        }
        state.misses = 0
    }

    /** Donor `boulderAttack()`: a rock falls on the tile you stand on; a third of your health if you're still there. */
    private fun Npc.boulder(target: Pawn) {
        animate(7228)
        val marked = Tile(target.tile.x, target.tile.z, target.tile.height)
        world.spawn(createProjectile(marked, gfx = 856, type = ProjectileType.MAGIC))
        world.spawn(TileGraphic(marked, 305, 35, delay = 90))
        val boss = this
        world.queue {
            wait(4)
            if (boss.isDead() || boss.index < 0) return@queue
            val victim = boss.getCombatTarget() ?: return@queue
            if (victim.tile.sameAs(marked)) victim.hit(damage = victim.getCurrentHp() / 3, delay = 0)
        }
    }

    companion object {
        val CERB_COMBO_READY = AttributeKey<Int>()
        val CERB_SOULS_READY = AttributeKey<Int>()
        val CERB_LAVA_READY = AttributeKey<Int>()
        val GORILLA_STATE = AttributeKey<GorillaState>()
        const val FACEMASK = 4164
        const val SPECTRAL_SPIRIT_SHIELD = 12821
    }
}
