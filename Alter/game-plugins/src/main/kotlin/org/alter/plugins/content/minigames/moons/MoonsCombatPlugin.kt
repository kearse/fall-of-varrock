package org.alter.plugins.content.minigames.moons

import org.alter.api.EquipmentType
import org.alter.api.ProjectileType
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.action.EquipAction
import org.alter.game.model.Tile
import org.alter.game.model.TileGraphic
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.collision.isClipped
import org.alter.game.model.combat.AttackStyle
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.combat.CombatStyle
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.bossMelee
import org.alter.plugins.content.combat.*
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.raids.RaidInstance
import org.alter.rscm.RSCM.getRSCM
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The three Moon fights — the OSRS-wiki mechanics on the shared boss primitives. Each fight is
 * the same frame ([moonCombat]): a 6-tick **three-strike** combo (4 / 8 / 20, a miss ends it),
 * **Eyatlalli's glyph** rotating clockwise every two standard attacks (off it you take rapid
 * damage), and after six standard attacks one of two alternating specials:
 *
 *  - **Blood Moon** — every landed strike heals it (1× / 2× / 3×, the third at least 30 and at
 *    most 50). *Blood rain*: it vanishes and blood pools spatter the floor — stand clear.
 *    *Blood jaguar*: a jaguar rises by the glyph inside a square of pools; the Moon is immune
 *    until the jaguar dies, and every pool tick you suffer heals it threefold.
 *  - **Blue Moon** — *Weapon freeze*: your weapon is torn off into a block of ice at the glyph;
 *    stand there to shatter it (icy spikes fall meanwhile). *Frost storm*: both braziers at the
 *    chamber's ends go out — **Feed** them while two storms sweep the floor; each unlit brazier
 *    heals the Moon 10 every six ticks (five heals at most).
 *  - **Eclipse Moon** — *Searing rays*: a moon shield appears and shoves you behind it, then
 *    orbits; rays hit anyone not in its shadow. *Clones*: you're bound at the centre while five
 *    waves of three clones strike; **attack a clone before it strikes** to parry — the parry
 *    lands on the Moon.
 *
 * Enraged Moons (re-fought before claiming the chest) hit for ×1.5.
 */
class MoonsCombatPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        Moons.Moon.values().forEach { m ->
            onNpcCombat(m.npcKey) {
                if (npc.attr[CLONE] == true) npc.removeCombatTarget() else npc.queue { npc.moonCombat(this, m) }
            }
        }
        onNpcCombat(Moons.JAGUAR_KEY) { npc.queue { npc.jaguarCombat(this) } }
        onNpcCombat(Moons.SHIELD_KEY) { npc.removeCombatTarget() }
    }

    class Fight(
        val chamber: Moons.Chamber,
        val centre: Tile,
        var standard: Int = 0,
        var glyph: Int = 0,
        var special: Int = 0,
        var busy: Boolean = false,
        var glyphObj: DynamicObject? = null,
        var offGlyphTick: Int = 0,
        var frostHeals: Int = 0,
    ) {
        fun glyphTile(index: Int): Tile {
            val a = index * Math.PI / 4.0
            return Tile(centre.x + (Moons.GLYPH_RADIUS * cos(a)).roundToInt(), centre.z + (Moons.GLYPH_RADIUS * sin(a)).roundToInt(), centre.height)
        }
    }

    // ───────────────────────────── the shared frame ─────────────────────────────

    private suspend fun Npc.moonCombat(task: QueueTask, m: Moons.Moon) {
        val fight = attr[FIGHT] ?: return
        var target = getCombatTarget() ?: return
        if (fight.glyphObj == null) placeGlyph(fight)
        while (canEngageCombat(target)) {
            facePawn(target)
            if (fight.busy) {
                task.wait(1)
                target = getCombatTarget() ?: break
                continue
            }
            // Off Eyatlalli's glyph during standard phases: rapid damage.
            if (target is Player) {
                fight.offGlyphTick++
                val g = fight.glyphTile(fight.glyph)
                if (fight.offGlyphTick % 2 == 0 && !target.tile.isWithinRadius(g, 1)) {
                    target.hit(damage = Moons.OFF_GLYPH_DAMAGE, delay = 0)
                }
            }
            if (isAttackDelayReady()) {
                if (fight.standard >= Moons.STANDARD_PER_SPECIAL) {
                    fight.standard = 0
                    val second = fight.special % 2 == 1
                    fight.special++
                    when (m) {
                        Moons.Moon.BLOOD -> if (second) bloodJaguar(fight, target) else bloodRain(fight, target)
                        Moons.Moon.BLUE -> if (second) frostStorm(fight, target) else weaponFreeze(fight, target)
                        Moons.Moon.ECLIPSE -> if (second) clones(fight, target) else searingRays(fight, target)
                    }
                } else {
                    tripleStrike(target, healOnHit = m == Moons.Moon.BLOOD)
                    fight.standard++
                    if (fight.standard % Moons.GLYPH_ROTATE_EVERY == 0) {
                        fight.glyph = (fight.glyph + 1) % 8
                        placeGlyph(fight)
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

    private fun Npc.placeGlyph(fight: Fight) {
        fight.glyphObj?.let { world.remove(it) }
        val obj = DynamicObject(getRSCM(Moons.GLYPH_KEY), 10, 0, fight.glyphTile(fight.glyph))
        world.spawn(obj)
        fight.glyphObj = obj
    }

    /** Three strikes 4 / 8 / 20; each rolls accuracy; a miss ends the combo. */
    private fun Npc.tripleStrike(target: Pawn, healOnHit: Boolean) {
        val mult = if (attr[ENRAGED] == true) Moons.ENRAGED_MULT else 1.0
        val boss = this
        world.queue {
            for (i in Moons.STRIKES.indices) {
                if (boss.isDead() || boss.index < 0 || boss.getCombatTarget() !== target) return@queue
                boss.prepareAttack(CombatClass.MELEE, CombatStyle.SLASH, AttackStyle.AGGRESSIVE)
                val landed = MeleeCombatFormula.getAccuracy(boss, target) >= world.randomDouble()
                boss.dealHit(target = target, maxHit = (Moons.STRIKES[i] * mult).roundToInt(), landHit = landed, delay = 0) { ph ->
                    if (healOnHit) {
                        val dmg = ph.hit.hitmarks.sumOf { it.damage }
                        if (dmg > 0) {
                            val heal = if (i == 2) (dmg * 3).coerceIn(30, 50) else dmg * (i + 1)
                            boss.setCurrentHp(minOf(boss.getMaxHp(), boss.getCurrentHp() + heal))
                        }
                    }
                }
                if (!landed) return@queue
                wait(1)
            }
        }
    }

    private fun Npc.arenaTiles(fight: Fight): List<Tile> {
        val out = mutableListOf<Tile>()
        val c = fight.centre
        for (dx in -fight.chamber.radius..fight.chamber.radius) for (dz in -fight.chamber.radius..fight.chamber.radius) {
            if (dx * dx + dz * dz > fight.chamber.radius * fight.chamber.radius) continue
            val t = Tile(c.x + dx, c.z + dz, c.height)
            if (!world.collision.isClipped(t)) out += t
        }
        return out
    }

    /** Hazard tiles hurt anyone standing on them for [ticks], re-drawn every 2 ticks. */
    private fun Npc.hazard(tiles: Collection<Tile>, ticks: Int, gfx: Int, damage: Int, onHurt: ((Int) -> Unit)? = null) {
        val boss = this
        world.queue {
            var left = ticks
            while (left > 0 && !boss.isDead() && boss.index >= 0) {
                tiles.forEach { world.spawn(TileGraphic(it, gfx, 0)) }
                val v = boss.getCombatTarget()
                if (v != null && tiles.any { it.sameAs(v.tile) }) {
                    v.hit(damage = damage, delay = 0)
                    onHurt?.invoke(damage)
                }
                wait(2)
                left -= 2
            }
        }
    }

    private fun Npc.endSpecial(fight: Fight, after: Int) {
        val boss = this
        world.queue {
            wait(after)
            boss.attr.remove(Combat.DAMAGE_TAKE_MULTIPLIER)
            boss.invisible = false
            fight.busy = false
        }
    }

    // ───────────────────────────── Blood Moon ─────────────────────────────

    private fun Npc.bloodRain(fight: Fight, target: Pawn) {
        fight.busy = true
        invisible = true
        attr[Combat.DAMAGE_TAKE_MULTIPLIER] = 0.0
        (target as? Player)?.message("<col=ff0000>The Blood Moon vanishes — blood rains from above! Keep out of the pools.</col>")
        val pools = arenaTiles(fight).shuffled().take(45)
        hazard(pools, ticks = 14, gfx = GFX_BLOOD_POOL, damage = 8)
        endSpecial(fight, after = 16)
    }

    private fun Npc.bloodJaguar(fight: Fight, target: Pawn) {
        fight.busy = true
        attr[Combat.DAMAGE_TAKE_MULTIPLIER] = 0.0
        val g = fight.glyphTile(fight.glyph)
        val at = world.snapToWalkable(Tile(g.x + 2, g.z, g.height), maxRadius = 3)
        val jaguar = Npc(getRSCM(Moons.JAGUAR_KEY), at, world)
        jaguar.respawns = false
        world.spawn(jaguar)
        jaguar.setActive(true)
        jaguar.attack(target)
        (target as? Player)?.message("<col=ff0000>A blood jaguar rises beside the glyph! Kill it from the glyph — its pools feed the Moon.</col>")
        val pools = mutableListOf<Tile>()
        for (dx in -1..2) for (dz in -1..2) {
            val t = Tile(at.x + dx, at.z + dz, at.height)
            if (!t.sameAs(g) && !t.isWithinRadius(at, 0)) pools += t
        }
        val boss = this
        hazard(pools, ticks = 60, gfx = GFX_BLOOD_POOL, damage = 6) { dmg ->
            boss.setCurrentHp(minOf(boss.getMaxHp(), boss.getCurrentHp() + dmg * 3))
        }
        world.queue {
            var left = 60
            while (left > 0 && !jaguar.isDead() && jaguar.index >= 0 && !boss.isDead()) { wait(1); left-- }
            if (!jaguar.isDead() && jaguar.index >= 0) world.remove(jaguar)
            boss.attr.remove(Combat.DAMAGE_TAKE_MULTIPLIER)
            fight.busy = false
        }
    }

    private suspend fun Npc.jaguarCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 1, projectile = false) && isAttackDelayReady()) {
                animate(10958)
                bossMelee(target, maxHit = 10, style = CombatStyle.SLASH)
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    // ───────────────────────────── Blue Moon ─────────────────────────────

    private fun Npc.weaponFreeze(fight: Fight, target: Pawn) {
        val p = target as? Player ?: return
        fight.busy = true
        val g = fight.glyphTile(fight.glyph)
        val weapon = p.equipment[EquipmentType.WEAPON.id]
        if (weapon != null) {
            EquipAction.unequip(p, EquipmentType.WEAPON.id)
            p.message("<col=ff0000>The Blue Moon tears your weapon away and seals it in ice at the glyph — stand there to shatter it!</col>")
        } else {
            p.message("<col=ff0000>Icy spikes rain down — keep to the glyph!</col>")
        }
        val ice = DynamicObject(getRSCM(ICE_BLOCK_KEY), 10, 0, g)
        world.spawn(ice)
        val spikes = arenaTiles(fight).filter { !it.isWithinRadius(g, 1) }.shuffled().take(30)
        hazard(spikes, ticks = 16, gfx = GFX_ICE_SPIKE, damage = 7)
        val boss = this
        world.queue {
            var onGlyph = 0
            var left = 24
            while (left > 0 && !boss.isDead() && boss.index >= 0) {
                if (p.tile.isWithinRadius(g, 1)) onGlyph++ else onGlyph = 0
                if (onGlyph >= 3) {
                    p.message("<col=4f9b4f>You shatter the ice and recover your weapon.</col>")
                    break
                }
                wait(1)
                left--
            }
            world.remove(ice)
            fight.busy = false
        }
    }

    private fun Npc.frostStorm(fight: Fight, target: Pawn) {
        val p = target as? Player ?: return
        fight.busy = true
        val boss = this
        val braziers = Moons.BLUE_BRAZIERS_SRC.map { src -> instanceTile(src) }.map { t ->
            DynamicObject(getRSCM(Moons.BLUE_BRAZIER_KEY), 10, 0, t).also { world.spawn(it) }
        }.toMutableList()
        val lit = HashSet<DynamicObject>()
        attr[FROST_BRAZIERS] = braziers
        attr[FROST_LIT] = lit
        fight.frostHeals = 0
        p.message("<col=ff0000>The Blue Moon snuffs both braziers — Feed them before it heals, and dodge the storms!</col>")
        // Two storms sweep the floor.
        val tiles = arenaTiles(fight)
        world.queue {
            var s1 = tiles.random()
            var s2 = tiles.random()
            var left = 36
            var healTick = 0
            while (left > 0 && !boss.isDead() && boss.index >= 0) {
                s1 = tiles.filter { it.isWithinRadius(s1, 2) }.randomOrNull() ?: s1
                s2 = tiles.filter { it.isWithinRadius(s2, 2) }.randomOrNull() ?: s2
                world.spawn(TileGraphic(s1, GFX_STORM, 0))
                world.spawn(TileGraphic(s2, GFX_STORM, 0))
                if (p.tile.isWithinRadius(s1, 1) || p.tile.isWithinRadius(s2, 1)) {
                    p.hit(damage = 10, delay = 0)
                    p.stun(2) { p.message("The storm batters you.") }
                }
                healTick++
                val unlit = braziers.count { it !in lit }
                if (unlit > 0 && healTick % 6 == 0 && fight.frostHeals < 5) {
                    fight.frostHeals++
                    boss.setCurrentHp(minOf(boss.getMaxHp(), boss.getCurrentHp() + 10 * unlit))
                }
                if (unlit == 0) break
                wait(1)
                left--
            }
            braziers.forEach { if (it !in lit) world.remove(it) }
            boss.attr.remove(FROST_BRAZIERS)
            boss.attr.remove(FROST_LIT)
            fight.busy = false
        }
    }


    // ───────────────────────────── Eclipse Moon ─────────────────────────────

    private fun Npc.searingRays(fight: Fight, target: Pawn) {
        val p = target as? Player ?: return
        fight.busy = true
        attr[Combat.DAMAGE_TAKE_MULTIPLIER] = 0.0
        var idx = fight.glyph
        val shield = Npc(getRSCM(Moons.SHIELD_KEY), fight.glyphTile(idx), world)
        shield.respawns = false
        world.spawn(shield)
        shield.setActive(true)
        p.moveTo(world.snapToWalkable(Tile(shield.tile.x + 1, shield.tile.z, shield.tile.height), maxRadius = 2))
        p.message("<col=ff0000>The Eclipse Moon shoves you behind its shield — stay in its shadow as it circles!</col>")
        val boss = this
        world.queue {
            var left = 24
            while (left > 0 && !boss.isDead() && boss.index >= 0) {
                wait(3)
                left -= 3
                idx = (idx + 1) % 8
                shield.moveTo(fight.glyphTile(idx))
                if (!p.tile.isWithinRadius(shield.tile, 1)) {
                    p.hit(damage = 6, delay = 0)
                    p.graphic(GFX_RAY, 0)
                    p.message("<col=ff0000>The searing rays burn you — get behind the shield!</col>")
                }
            }
            if (shield.index >= 0) world.remove(shield)
            boss.attr.remove(Combat.DAMAGE_TAKE_MULTIPLIER)
            fight.busy = false
        }
    }

    private fun Npc.clones(fight: Fight, target: Pawn) {
        val p = target as? Player ?: return
        fight.busy = true
        attr[Combat.DAMAGE_TAKE_MULTIPLIER] = 0.0
        p.moveTo(world.snapToWalkable(fight.centre, maxRadius = 2))
        p.freeze(cycles = 34) { p.message("<col=ff0000>The Eclipse Moon binds you — attack each clone before it strikes to parry it!</col>") }
        val boss = this
        world.queue {
            repeat(5) {
                val wave = mutableListOf<Npc>()
                repeat(3) {
                    val at = fight.glyphTile(world.random(7))
                    val clone = Npc(boss.id, world.snapToWalkable(at, maxRadius = 2), world)
                    clone.respawns = false
                    clone.attr[CLONE] = true
                    world.spawn(clone)
                    clone.setActive(true)
                    clone.facePawn(p)
                    wave += clone
                    wait(1)
                }
                wait(1)
                wave.forEach { clone ->
                    if (clone.index < 0) return@forEach
                    if (p.getCombatTarget() === clone) {
                        boss.hit(damage = 20, delay = 0)
                        p.message("<col=4f9b4f>You parry the clone — the Moon staggers!</col>")
                    } else {
                        p.hit(damage = 12, delay = 0)
                    }
                    world.remove(clone)
                }
                wait(2)
            }
            boss.attr.remove(Combat.DAMAGE_TAKE_MULTIPLIER)
            fight.busy = false
        }
    }

    // ───────────────────────────── helpers ─────────────────────────────

    /** Source-coordinate → this npc's instance (the chamber copy it lives in). */
    private fun Npc.instanceTile(src: Tile): Tile {
        val map = world.instanceAllocator.getMap(tile) ?: return src
        val source = RaidInstance.sourceOf(world, tile) ?: return src
        return Tile(map.area.bottomLeftX + (src.x - source.bottomLeftX), map.area.bottomLeftY + (src.z - source.bottomLeftY), src.height)
    }

    companion object {
        /** Feed a snuffed brazier (bound in [MoonsPlugin]): it burns again and the Moon stops healing from it. */
        fun feedBrazier(world: World, p: Player, obj: DynamicObject) {
            var owner: Npc? = null
            world.npcs.forEach { n ->
                if (owner == null && n.attr[FROST_BRAZIERS]?.contains(obj) == true) owner = n
            }
            val boss = owner ?: return
            val lit = boss.attr[FROST_LIT] ?: return
            if (obj in lit) return
            lit += obj
            p.animate(832)
            p.message("You feed the brazier and it roars back to life.")
            world.remove(obj)
        }

        val FIGHT = AttributeKey<Fight>()
        val ENRAGED = AttributeKey<Boolean>()
        val CLONE = AttributeKey<Boolean>()
        val FROST_BRAZIERS = AttributeKey<MutableList<DynamicObject>>()
        val FROST_LIT = AttributeKey<MutableSet<DynamicObject>>()

        const val ICE_BLOCK_KEY = "object.ice_block_49144"

        // Spot-anim placeholders — rev 228's Moons gfx ids are not catalogued here; unknown ids
        // render nothing, so these are safe TUNE targets.
        const val GFX_BLOOD_POOL = 1231
        const val GFX_ICE_SPIKE = 369
        const val GFX_STORM = 1276
        const val GFX_RAY = 157
    }
}
