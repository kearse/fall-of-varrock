package org.alter.plugins.content.minigames.moons

import dev.openrune.cache.CacheManager.getItem
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.INTERACTING_OBJ_ATTR
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.BossKills
import org.alter.plugins.content.bosses.CollectionLog
import org.alter.plugins.content.companion.CompanionPolicy
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.awardTickets
import org.alter.plugins.content.raids.RaidInstance
import org.alter.plugins.content.war.WarNpcNames
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * Wires [Moons] into Neypotzli:
 *  - **Doorways**: a heartbeat watches the three chamber doorways. Step in on the shared map →
 *    a private copy of the chamber is allocated, the Moon (or its **enraged** self if you've
 *    already subdued it this run) rises at the centre and you appear inside. Step back through
 *    the doorway inside the copy → you're back on the shared map (the allocator frees the copy
 *    once it's empty).
 *  - **Subduing** a Moon marks it for the run, counts the kill and, a few ticks later, carries
 *    you out to the door. Moons drop nothing themselves.
 *  - **Lunar Chest** (`Claim`): unlocks once every Moon has been subdued at least once; then
 *    pays for the Moons subdued this run and re-locks the run.
 *  - **Camps**: supply crates hand out a bream kit on a cooldown (the gathering loop is v2);
 *    the cooking stove's *Make-cuppa* restores run energy.
 */
class MoonsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        Moons.Moon.values().forEach { BossKills.register(it.key, it.displayName) }
        BossKills.register("lunar_chest", "Lunar Chests")
        Moons.CHAMBERS.forEach { CompanionPolicy.denyInstanceOf(it.source, "The Moons of Peril are fought alone") }

        val beat = TimerKey()
        onWorldInit {
            world.timers[beat] = 2
            runCatching { world.definitions.loadRegions(world, world.chunks, intArrayOf(5781, 5782, 6037, 5525, 5527, 6039)) }
                .onFailure { logger.warn(it) { "moons: antechamber/camp region force-load failed" } }
        }
        onTimer(beat) {
            sweepDoorways()
            world.timers[beat] = 2
        }

        Moons.Moon.values().forEach { m ->
            onNpcDeath(m.npcKey) {
                val dead = npc
                if (dead.attr[MoonsCombatPlugin.CLONE] == true) return@onNpcDeath
                dead.attr[MoonsCombatPlugin.FIGHT]?.glyphObj?.let { world.remove(it) }
                val killer = dead.attr[KILLER_ATTR]?.get() as? Player ?: return@onNpcDeath
                subdue(killer, m, enraged = dead.attr[MoonsCombatPlugin.ENRAGED] == true)
            }
        }

        onObjOption(obj = Moons.CHEST_KEY, option = "claim") { claim(player) }
        runCatching {
            onObjOption(obj = Moons.BLUE_BRAZIER_KEY, option = "feed") {
                val obj = player.attr[INTERACTING_OBJ_ATTR]?.get() as? DynamicObject ?: return@onObjOption
                MoonsCombatPlugin.feedBrazier(world, player, obj)
            }
        }.onFailure { logger.warn { "moons: blue moon brazier has no 'feed' option (${it.message})" } }
        runCatching {
            onObjOption(obj = Moons.SUPPLY_CRATES_KEY, option = "take-from") { takeSupplies(player) }
        }.onFailure { logger.warn { "moons: supply crates have no 'take-from' option (${it.message})" } }
        runCatching {
            onObjOption(obj = Moons.COOKING_STOVE_KEY, option = "make-cuppa") {
                player.runEnergy = 10000.0
                player.sendRunEnergy(10000)
                player.animate(832)
                player.message("You brew a cup of tea and feel your energy return.")
            }
        }.onFailure { logger.warn { "moons: cooking stove has no 'make-cuppa' option (${it.message})" } }

        onCommand("moons", description = "Teleport to Neypotzli's antechamber (Moons of Peril)") {
            player.moveTo(Moons.ANTECHAMBER)
            player.message("You enter Neypotzli. The three Moons wait beyond the sigils; the Lunar Chest lies in the Ancient Shrine to the east.")
        }
    }

    // ───────────────────────────── doorways ─────────────────────────────

    private fun sweepDoorways() {
        world.players.forEach { p ->
            if (p.isDead()) return@forEach
            val inInstance = world.instanceAllocator.getMap(p.tile) != null
            if (!inInstance) {
                val c = Moons.CHAMBERS.firstOrNull { it.entryTrigger.contains(p.tile) } ?: return@forEach
                enter(p, c)
            } else {
                val source = RaidInstance.sourceOf(world, p.tile) ?: return@forEach
                val c = Moons.CHAMBERS.firstOrNull { it.source == source } ?: return@forEach
                val map = world.instanceAllocator.getMap(p.tile) ?: return@forEach
                val rel = Tile(p.tile.x - map.area.bottomLeftX + c.source.bottomLeftX, p.tile.z - map.area.bottomLeftY + c.source.bottomLeftY, p.tile.height)
                if (c.exitTrigger.contains(rel)) {
                    p.moveTo(c.outside)
                    p.message("You step back out of the ${c.moon.displayName}'s chamber.")
                }
            }
        }
    }

    private fun enter(p: Player, c: Moons.Chamber) {
        val instance = RaidInstance.allocate(world = world, sourceArea = c.source, exitTile = c.outside, owner = p.uid)
        if (instance == null) {
            p.moveTo(c.outside)
            p.message("The sigil flickers — the chamber will not open. Try again in a moment.")
            return
        }
        val run = Moons.run(p)
        val enraged = run.has(c.moon)
        val boss = Npc(getRSCM(c.moon.npcKey), instance.translate(c.centre), world)
        boss.respawns = false
        boss.walkRadius = 0
        boss.attr[MoonsCombatPlugin.FIGHT] = MoonsCombatPlugin.Fight(c, instance.translate(c.centre))
        if (enraged) {
            boss.attr[MoonsCombatPlugin.ENRAGED] = true
            WarNpcNames.rename(boss, "Enraged ${c.moon.displayName}")
        }
        world.spawn(boss)
        boss.setActive(true)
        p.moveTo(instance.translate(c.insideEntry))
        p.message(
            if (enraged) "<col=ff0000>You have already subdued the ${c.moon.displayName} this run — it rises ENRAGED, and the chest gains nothing from it.</col>"
            else "<col=801700>The ${c.moon.displayName} stirs at the heart of the chamber. Stand on Eyatlalli's glyph!</col>",
        )
        world.queue {
            wait(2)
            if (!boss.isDead() && boss.index >= 0 && !p.isDead()) boss.attack(p)
        }
    }

    // ───────────────────────────── subduing + the chest ─────────────────────────────

    private fun subdue(p: Player, m: Moons.Moon, enraged: Boolean) {
        val run = Moons.run(p)
        val kc = BossKills.record(p, m.key)
        if (!enraged && !run.has(m)) {
            run.subdued = run.subdued or m.bit
            Moons.save(p, run)
            p.message("<col=801700>You have subdued the ${m.displayName}.</col> Kill count: $kc. Moons subdued this run: ${run.count()}/3.")
        } else {
            p.message("<col=801700>The enraged ${m.displayName} falls.</col> Kill count: $kc (no chest credit).")
        }
        val c = Moons.chamber(m)
        world.queue {
            wait(6)
            if (world.instanceAllocator.getMap(p.tile) != null) p.moveTo(c.outside)
        }
    }

    private fun claim(p: Player) {
        val run = Moons.run(p)
        if (!run.unlocked && run.subdued != 7) {
            p.message("The Lunar Chest is sealed. Subdue all three Moons — Blood, Blue and Eclipse — to unlock it.")
            return
        }
        if (run.subdued == 0) {
            p.message("The Lunar Chest is empty. Subdue a Moon first.")
            return
        }
        val subduedMoons = Moons.Moon.values().filter { run.has(it) }
        val rolls = Moons.commonRolls(subduedMoons.size)
        p.animate(832)
        var uniques = 0
        subduedMoons.forEach { m ->
            if (world.chance(1, Moons.UNIQUE_ONE_IN)) {
                val piece = m.pieces[world.random(m.pieces.size - 1)]
                give(p, piece, 1)
                uniques++
                val id = runCatching { getRSCM(piece) }.getOrNull()
                if (id != null) {
                    val name = getItem(id).name
                    world.players.forEach { it.message("<col=ff0000>News: ${p.username} just received <col=ffae00>$name</col> from the Lunar Chest!</col>") }
                    if (CollectionLog.record(p, id)) p.message("<col=ffae00>New Collection Log slot: $name!</col>")
                }
            }
        }
        repeat(rolls) { Moons.COMMON.roll(world).forEach { give(p, it.item, it.amount) } }
        val tickets = Moons.TICKETS_PER_MOON * subduedMoons.size
        p.awardTickets(PointKind.BOSS, tickets)
        val chests = (p.attr[Moons.CHESTS_ATTR] ?: 0) + 1
        p.attr[Moons.CHESTS_ATTR] = chests
        BossKills.record(p, "lunar_chest")
        p.message(
            "<col=801700>You claim the Lunar Chest.</col> ${subduedMoons.size} Moon${if (subduedMoons.size > 1) "s" else ""}, $rolls roll${if (rolls > 1) "s" else ""}" +
                (if (uniques > 0) ", $uniques unique${if (uniques > 1) "s" else ""}" else "") + ", +$tickets Boss Tickets. Chest count: $chests.",
        )
        run.subdued = 0
        run.unlocked = true
        Moons.save(p, run)
    }

    private fun takeSupplies(p: Player) {
        val now = world.currentCycle
        val next = p.attr[Moons.CRATE_ATTR] ?: 0
        if (next > now) {
            p.message("You've already taken from the crates recently. Come back in ${(next - now) * 6 / 10} seconds.")
            return
        }
        p.attr[Moons.CRATE_ATTR] = now + Moons.CRATE_COOLDOWN_TICKS
        give(p, "item.cooked_bream", Moons.CRATE_BREAM)
        p.message("You take $CRATE_TEXT from the supply crates.")
    }

    private fun give(p: Player, key: String, amount: Int) {
        if (amount <= 0) return
        val id = runCatching { getRSCM(key) }.getOrNull()
        if (id == null) {
            logger.warn { "moons: unknown item key $key" }
            return
        }
        val added = p.inventory.add(item = id, amount = amount, assureFullInsertion = false)
        val leftover = amount - added.completed
        if (leftover > 0) world.spawn(GroundItem(id, leftover, p.tile, p))
    }

    companion object {
        const val CRATE_TEXT = "a bundle of cooked bream"
    }
}
