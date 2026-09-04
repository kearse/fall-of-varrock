package org.alter.plugins.content.mechanics.prayer

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.model.timer.BURY_BONE_DELAY
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Prayer altar + bone burying.** Closes the prayer-recharge gap: the drain logic already
 * tells players to "recharge at an altar" ([Prayers]), but nothing handled the altar object
 * or the bones' "Bury" option. This binds the classic prayer altar (object 409, "Pray-at")
 * to restore prayer to the player's base level, and the common bones to grant Prayer xp.
 *
 * Object/item ids resolved from the cache via RSCM; each binding is guarded so a missing
 * cache verb surfaces in the boot failure report without dropping the rest of the plugin.
 */
class PrayerAltarPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    // OSRS prayer xp per bone — the shared table (Bones.kt). Only bones whose RSCM key resolves are bound.
    private val bones = Bones.ALL

    // The God Wars Dungeon altars (one per throne room; rev-228 ids). "Pray-at" restores prayer once
    // per ten minutes (OSRS) and takes no bone offerings. None of them were bound ("altar in GWD
    // doesn't work", 2026-09-03).
    private val godwarsAltars = listOf(
        "object.armadyl_altar",
        "object.bandos_altar",
        "object.saradomin_altar",
        "object.zamorak_altar",
    )
    private val GWD_ALTAR_COOLDOWN = TimerKey(persistenceKey = "gwd_altar", tickOffline = true)
    private val GWD_ALTAR_COOLDOWN_TICKS = 1000

    // Classic OSRS prayer altars. "altar_409" is the standard Pray-at altar. The wilderness
    // Chaos altars (player report 2026-09-02: "Chaos Altars don't work + cannot use bones on it")
    // are separate object ids and additionally keep the bone half the time (OSRS).
    private val altars = listOf(
        "object.altar_409",
        "object.chaos_altar",
        "object.chaos_altar_411",
        "object.chaos_altar_412",
        "object.chaos_altar_26258",
    )

    /** Altars with the OSRS Chaos-altar rite: a 50% chance each offered bone is not consumed. */
    private val boneSavingAltars = setOf(
        "object.chaos_altar",
        "object.chaos_altar_411",
        "object.chaos_altar_412",
        "object.chaos_altar_26258",
    )

    // Offering bones ON the altar (the church-altar rite) is far faster than burying — the gilded-altar
    // rate of 3.5x. This is what the War-Prep Magic quest sends recruits to the Lumbridge church altar
    // for (train Prayer to 37 → Protect from Magic). TUNABLE.
    private val altarMultiplier = 3.5

    // Ticks between each bone when auto-offering a full inventory on the altar. TUNABLE.
    private val offerDelayTicks = 3

    // No courtyard altar: the church's map-baked altar (3243,3207) is right next to the home hub,
    // so we don't spawn a convenience prayer altar in the market courtyard. The "Pray-at" and
    // bone-offering bindings below are keyed by object type (altar_409), so they cover the church
    // altar (and any other altar_409) automatically. The Altar of the Occult on the south
    // services line (3215,3211, SpellbookSwapPlugin) is unrelated and stays; the old fountain
    // tile (3221,3210) hosts the Grand Exchange stand (GrandExchangeClickPlugin).

    init {
        altars.forEach { altar ->
            // Try both common verbs; some altar variants use "Pray" rather than "Pray-at".
            var bound = false
            listOf("Pray-at", "Pray").forEach { verb ->
                try {
                    onObjOption(obj = altar, option = verb) { recharge(player) }
                    bound = true
                } catch (e: Exception) { /* this altar lacks this verb in the cache */ }
            }
            if (!bound) {
                logger.warn { "prayer: altar '$altar' has neither 'Pray-at' nor 'Pray' in the cache — recharge will be unclickable." }
            }
        }

        godwarsAltars.forEach { altar ->
            var bound = false
            listOf("Pray-at", "Pray").forEach { verb ->
                try {
                    onObjOption(obj = altar, option = verb) { rechargeGodwars(player) }
                    bound = true
                } catch (e: Exception) { /* this altar lacks this verb in the cache */ }
            }
            if (!bound) {
                logger.warn { "prayer: GWD altar '$altar' has neither 'Pray-at' nor 'Pray' in the cache — recharge will be unclickable." }
            }
        }

        bones.forEach { b ->
            try {
                onItemOption(b.key, option = "Bury") { bury(player, b) }
            } catch (e: Exception) {
                logger.info { "prayer: bone '${b.key}' has no Bury option in cache; skipped." }
            }
        }

        // Offering bones on any altar → boosted Prayer xp (the church-altar rite).
        // One use-on-altar offers the whole inventory: the queue keeps offering that bone
        // type until none remain, and breaks if the player walks off or does something else.
        altars.forEach { altar ->
            val saveBones = altar in boneSavingAltars
            bones.forEach { b ->
                try {
                    onItemOnObj(obj = altar, item = b.key) { player.queue { offer(this, player, b, saveBones) } }
                } catch (e: Exception) { /* this altar/bone pairing isn't in the cache */ }
            }
        }
    }

    private suspend fun offer(task: QueueTask, player: Player, bone: Bones.Bone, saveBones: Boolean) {
        while (player.inventory.remove(item = getRSCM(bone.key), amount = 1).hasSucceeded()) {
            player.animate(3705) // kneel-and-offer at the altar
            player.addXp(Skills.PRAYER, bone.xp * altarMultiplier)
            if (saveBones && player.world.chance(1, 2)) {
                // Chaos altar: the bone is offered for full xp but not consumed.
                player.inventory.add(item = getRSCM(bone.key), amount = 1)
                player.message("The gods accept your offering, and the bones remain.")
            } else {
                player.message("You offer the bones on the altar. The gods are pleased.")
            }
            if (!player.inventory.contains(getRSCM(bone.key))) {
                break
            }
            task.wait(offerDelayTicks)
        }
    }

    private fun recharge(player: Player): Boolean {
        val skills = player.getSkills()
        val base = skills.getBaseLevel(Skills.PRAYER)
        if (skills.getCurrentLevel(Skills.PRAYER) >= base) {
            player.message("You already have full prayer points.")
            return false
        }
        skills.setCurrentLevel(Skills.PRAYER, base)
        player.animate(645)
        player.message("You recharge your prayer points.")
        return true
    }

    /** GWD altars: one recharge per [GWD_ALTAR_COOLDOWN_TICKS] (shared across the four altars, survives relog). */
    private fun rechargeGodwars(player: Player) {
        if (player.timers.has(GWD_ALTAR_COOLDOWN)) {
            val seconds = player.timers[GWD_ALTAR_COOLDOWN] * 6 / 10
            player.message("You have already prayed at an altar recently. You can pray again in about ${maxOf(1, seconds / 60)} minute${if (seconds >= 120) "s" else ""}.")
            return
        }
        if (recharge(player)) {
            player.timers[GWD_ALTAR_COOLDOWN] = GWD_ALTAR_COOLDOWN_TICKS
        }
    }

    private fun bury(player: Player, bone: Bones.Bone) {
        // ~2-tick action (OSRS): the old handler had no cooldown, so spam-clicking buried
        // far faster than the game allows.
        if (player.timers.has(BURY_BONE_DELAY)) return
        val slot = player.getInteractingSlot()
        if (player.inventory.remove(item = getRSCM(bone.key), amount = 1, beginSlot = slot).hasSucceeded()) {
            player.animate(827)
            player.addXp(Skills.PRAYER, bone.xp)
            player.timers[BURY_BONE_DELAY] = 2
            player.message("You dig a hole in the ground and bury the bones.")
        }
    }
}
