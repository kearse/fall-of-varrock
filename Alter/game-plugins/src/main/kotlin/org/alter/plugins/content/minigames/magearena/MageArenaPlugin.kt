package org.alter.plugins.content.minigames.magearena

import dev.openrune.cache.CacheManager.getNpc
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.EquipmentType
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.model.queue.TaskPriority
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.interfaces.bank.openBank
import org.alter.plugins.content.magic.TeleportType
import org.alter.plugins.content.magic.teleport
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **The Mage Arena** — Kolodion's trial, the god-cape statues and the Mage Arena II pilgrimage.
 * See [MageArena] for the design and for which community reports this answers.
 *
 * Layout owned here: the Mage Bank floor (bank booth + Kolodion + the three statues), the arena
 * cage south of it, and the three wilderness shrines. Every spawn is defensive — a missing cache
 * key is logged and skipped rather than taking the plugin down at boot.
 */
class MageArenaPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onWorldInit {
            spawnObj("object.bank_booth", MageArena.BANK_BOOTH_TILE, rot = 1)
            // The claim statues at the bank, one per god, west→east in enum order.
            MageArena.God.values().forEachIndexed { i, god ->
                spawnObj(god.statue, Tile(MageArena.BANK.x - 4 + i * 4, MageArena.BANK.z + 4, 0))
            }
            // The Mage Arena II shrines, out in the deep wild.
            MageArena.SHRINES.forEach { god -> spawnObj(god.statue, god.shrine) }
        }

        spawnNpc(MageArena.KOLODION, MageArena.KOLODION_TILE.x, MageArena.KOLODION_TILE.z, 0, 0, Direction.EAST)

        bindKolodion()
        bindStatues()

        // Each fallen form spawns the next; the fifth completes Mage Arena I.
        onAnyNpcDeath { onFormDeath(npc) }
    }

    /** Live forms by challenger username, so one player's trial can never advance another's. */
    private val fights = HashMap<String, Npc>()

    // ───────────────────────────── Kolodion ─────────────────────────────

    private fun bindKolodion() {
        val bound = TALK_VERBS.any { verb ->
            runCatching {
                onNpcOption(npc = MageArena.KOLODION, option = verb) { player.queue { kolodionTalk(this, player) } }
            }.isSuccess
        }
        if (!bound) logger.warn { "mage-arena: Kolodion has no talk option in the cache; use the statues only." }
    }

    private suspend fun kolodionTalk(task: QueueTask, p: Player) {
        val id = runCatching { getRSCM(MageArena.KOLODION) }.getOrNull() ?: return
        when {
            // ---- Mage Arena II: the imbue, once the pilgrimage is done ----
            MageArena.hasArenaII(p) -> {
                task.chatNpc(p, "Your cape carries all the power I can give it. Wear it<br>well.", npc = id, title = KOL)
            }

            MageArena.hasArenaI(p) && MageArena.godOf(p) != null && MageArena.pilgrimageDone(p) -> {
                imbueCape(task, p, id)
            }

            MageArena.hasArenaI(p) && MageArena.godOf(p) != null -> {
                val left = MageArena.shrinesLeft(p)
                task.chatNpc(p, "You've taken a god's cape, but it is only cloth until<br>you carry it where that god's power still lingers.", npc = id, title = KOL)
                task.chatNpc(
                    p,
                    "Wear it and stand before the shrines out in the<br>Wilderness. " +
                        "Still to visit: <col=801700>${left.joinToString(", ") { it.shrineName }}</col>.",
                    npc = id, title = KOL,
                )
            }

            MageArena.hasArenaI(p) -> {
                task.chatNpc(p, "You beat me, and the gods were watching. Take a cape<br>from one of the three statues behind me — choose well,<br>the choice is final.", npc = id, title = KOL)
            }

            p.getSkills().getBaseLevel(org.alter.api.Skills.MAGIC) < MageArena.MAGIC_REQ -> {
                task.chatNpc(p, "You're not ready. Come back with <col=801700>${MageArena.MAGIC_REQ} Magic</col><br>and I'll test you properly.", npc = id, title = KOL)
            }

            else -> {
                task.chatNpc(p, "So you want a god's favour. I am Kolodion, and I decide<br>who is worth it.", npc = id, title = KOL)
                task.chatNpc(p, "I'll meet you in the arena and I'll change my shape five<br>times. Kill every one of me and the gods will offer you<br>a cape. Your things are yours — this is no death trap.", npc = id, title = KOL)
                if (task.options(p, "I'm ready. Send me in.", "Not yet.", title = "Enter the arena?") == 1) {
                    beginTrial(p)
                }
            }
        }
    }

    private suspend fun imbueCape(task: QueueTask, p: Player, kolodionId: Int) {
        val god = MageArena.godOf(p) ?: return
        val plain = resolveOrNull(god.cape)
        val imbued = resolveOrNull(god.imbued)
        if (plain == null || imbued == null) {
            task.chatNpc(p, "Something is wrong with the magic here. Come back later.", npc = kolodionId, title = KOL)
            logger.warn { "mage-arena: ${god.name} cape/imbued key missing; imbue refused." }
            return
        }
        if (!p.inventory.contains(plain) && !p.equipment.contains(plain)) {
            task.chatNpc(p, "You walked the whole Wilderness and left the cape at<br>home? Bring me your ${god.display} cape.", npc = kolodionId, title = KOL)
            return
        }
        task.chatNpc(p, "You stood before all three and came back breathing.<br>${god.display} has seen enough. Give me the cape.", npc = kolodionId, title = KOL)
        // Take it from wherever it is — worn capes are the common case after a pilgrimage.
        val takenFromEquipment = p.equipment.contains(plain) &&
            p.equipment.remove(item = plain, amount = 1).completed > 0
        if (!takenFromEquipment && p.inventory.remove(item = plain, amount = 1).completed == 0) return

        p.attr[MageArena.ARENA_II_ATTR] = true
        give(p, god.imbued, 1)
        task.chatNpc(p, "It's done. That cape will answer to ${god.display} now.", npc = kolodionId, title = KOL)
        p.message("<col=8f00ff>Mage Arena II complete — your ${god.display} cape has been imbued.</col>")
        logger.info { "mage-arena: ${p.username} imbued the ${god.name} cape." }
    }

    // ───────────────────────────── the trial ─────────────────────────────

    /** Put the challenger in the cage and spawn form 1. */
    private fun beginTrial(p: Player) {
        p.attr[MageArena.FORM_ATTR] = 0
        p.queue(TaskPriority.STRONG) {
            p.teleport(MageArena.ARENA_ENTRY, TeleportType.MODERN)
        }
        spawnForm(p, 0)
        p.message("<col=8f00ff>Kolodion shifts before you.</col>")
    }

    /**
     * Spawn stage [index] for [p]. The form is spawned directly (not via `spawnNpc`, which only
     * queues into the repository's boot-time spawn list) and tagged with the challenger so a second
     * player's fight can never claim it.
     */
    private fun spawnForm(p: Player, index: Int) {
        val key = MageArena.FORMS.getOrNull(index) ?: return
        val id = resolveOrNull(key) ?: run {
            logger.warn { "mage-arena: form '$key' not in cache; trial cannot continue." }
            p.message("Kolodion falters — the trial cannot continue. Speak to him again.")
            p.attr.remove(MageArena.FORM_ATTR)
            return
        }
        val npc = Npc(id, MageArena.ARENA_SPAWN, world)
        world.spawn(npc)
        // AFTER world.spawn: setNpcDefaults() derives `respawns` from respawnDelay and clobbers a
        // value set before the call (the WizardTower gotcha).
        npc.respawns = false
        npc.setActive(true)
        npc.attr[CHALLENGER] = p.username
        fights[p.username] = npc
    }

    /** The engine's death event for any npc — advance the trial if it was someone's form. */
    private fun onFormDeath(dead: Npc) {
        val owner = dead.attr[CHALLENGER] ?: return
        fights.remove(owner)
        val p = dead.attr[KILLER_ATTR]?.get() as? Player
            ?: world.players.firstOrNull { it.username == owner }
            ?: return
        if (p.username != owner) return // someone else's form; leave their fight alone

        val next = (p.attr[MageArena.FORM_ATTR] ?: 0) + 1
        if (next < MageArena.FORMS.size) {
            p.attr[MageArena.FORM_ATTR] = next
            p.message("<col=8f00ff>Kolodion shifts again — ${MageArena.FORMS.size - next} to go.</col>")
            spawnForm(p, next)
            return
        }

        // Fifth form down: Mage Arena I complete.
        p.attr.remove(MageArena.FORM_ATTR)
        p.attr[MageArena.ARENA_I_ATTR] = true
        p.queue(TaskPriority.STRONG) { p.teleport(MageArena.BANK, TeleportType.MODERN) }
        p.message("<col=8f00ff>You have defeated Kolodion. The gods are watching.</col>")
        p.message("Claim your cape from one of the three statues.")
        logger.info { "mage-arena: ${p.username} completed Mage Arena I." }
    }

    // ───────────────────────────── statues ─────────────────────────────

    /**
     * One binding per god statue, serving BOTH roles. The bank claim statues and the wilderness
     * shrines are the same cache object ids, so the click is routed by where the player is
     * standing: inside [BANK_RADIUS] of the Mage Bank it is a claim, anywhere else it is a shrine
     * visit. (Binding the id twice would just overwrite the first handler.)
     */
    private fun bindStatues() {
        MageArena.God.values().forEach { god ->
            bindObj(god.statue) { p ->
                if (p.tile.getDistance(MageArena.BANK) <= BANK_RADIUS) claimCape(p, god) else visitShrine(p, god)
            }
        }
    }

    private fun claimCape(p: Player, god: God_) {
        when {
            !MageArena.hasArenaI(p) ->
                p.message("The statue is silent. Prove yourself to Kolodion first.")
            MageArena.godOf(p) != null -> {
                val chosen = MageArena.godOf(p)!!
                p.message("You have already pledged to <col=801700>${chosen.display}</col>. The choice was final.")
            }
            else -> {
                if (resolveOrNull(god.cape) == null) {
                    logger.warn { "mage-arena: '${god.cape}' not in cache; claim refused." }
                    return
                }
                p.attr[MageArena.GOD_ATTR] = god.name
                give(p, god.cape, 1)
                p.message("<col=8f00ff>You pledge yourself to ${god.display} and take their cape.</col>")
                p.message("Kolodion can make it more, if you can reach all three wilderness shrines.")
                logger.info { "mage-arena: ${p.username} pledged to ${god.name}." }
            }
        }
    }

    // ───────────────────────────── shrines ─────────────────────────────

    private fun visitShrine(p: Player, god: God_) {
        val mine = MageArena.godOf(p)
        if (mine == null) {
            p.message("The shrine ignores you. Earn a god's cape first.")
            return
        }
        if (MageArena.hasArenaII(p)) {
            p.message("Your cape is already imbued. The shrine has nothing more for you.")
            return
        }
        val cape = resolveOrNull(mine.cape) ?: return
        if (!p.hasEquipped(EquipmentType.CAPE, mine.cape) && !p.equipment.contains(cape)) {
            p.message("You must be <col=801700>wearing</col> your ${mine.display} cape for the shrine to see you.")
            return
        }
        if (!MageArena.markVisited(p, god)) {
            p.message("You have already stood before ${god.display}'s shrine.")
            return
        }
        val left = MageArena.shrinesLeft(p)
        if (left.isEmpty()) {
            p.message("<col=8f00ff>The last shrine answers. Return to Kolodion at the Mage Bank.</col>")
        } else {
            p.message(
                "<col=8f00ff>${god.display}'s shrine answers.</col> Still to visit: " +
                    "<col=801700>${left.joinToString(", ") { it.shrineName }}</col>.",
            )
        }
    }

    // ───────────────────────────── plumbing ─────────────────────────────

    /** Bind every plausible verb on a statue; the handler routes by distance (bank vs shrine). */
    private fun bindObj(objKey: String, logic: (Player) -> Unit) {
        if (runCatching { getRSCM(objKey) }.isFailure) {
            logger.warn { "mage-arena: object '$objKey' not in cache; not bound." }
            return
        }
        val bound = STATUE_VERBS.any { verb ->
            runCatching { onObjOption(obj = objKey, option = verb) { logic(player) } }.isSuccess
        }
        if (!bound) logger.warn { "mage-arena: statue '$objKey' has no clickable verb in the cache." }
    }

    private fun spawnObj(key: String, tile: Tile, rot: Int = 0) {
        val id = resolveOrNull(key) ?: run {
            logger.warn { "mage-arena: object '$key' not in cache; skipped." }; return
        }
        world.spawn(DynamicObject(id = id, type = OBJ_TYPE, rot = rot, tile = tile))
    }

    private fun give(p: Player, key: String, amount: Int) {
        val id = resolveOrNull(key) ?: return
        val added = p.inventory.add(item = id, amount = amount, assureFullInsertion = false)
        val leftover = amount - added.completed
        if (leftover > 0) world.spawn(GroundItem(id, leftover, p.tile, p))
    }

    private fun resolveOrNull(key: String): Int? = try { getRSCM(key) } catch (e: Exception) { null }

    private companion object {
        const val KOL = "Kolodion"
        const val OBJ_TYPE = 10

        /** Stamped on a spawned form with the challenger's username — see [fights]. */
        val CHALLENGER = org.alter.game.model.attr.AttributeKey<String>()

        /** How close to [MageArena.BANK] a statue click counts as a CLAIM rather than a shrine visit. */
        const val BANK_RADIUS = 16

        val TALK_VERBS = listOf("Talk-to", "Talk to", "Talk")
        val STATUE_VERBS = listOf("Pray-at", "Pray", "Study", "Search", "Worship")
    }
}

/** Alias so the long enum path doesn't wrap every signature in this file. */
private typealias God_ = MageArena.God
