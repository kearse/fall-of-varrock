package org.alter.plugins.content.bots

import io.github.oshai.kotlinlogging.KotlinLogging
import net.rsprot.protocol.common.client.OldSchoolClientType
import org.alter.api.EquipmentType
import org.alter.api.SkullIcon
import org.alter.api.Spellbook
import org.alter.api.cfg.Varbit
import org.alter.api.ext.setSpellbook
import org.alter.api.ext.setVarbit
import org.alter.plugins.content.combat.strategy.ranged.weapon.BowType
import org.alter.plugins.content.combat.strategy.ranged.weapon.CrossbowType
import org.alter.plugins.content.magic.MagicSpells
import org.alter.game.info.PlayerInfo
import org.alter.game.model.PlayerUID
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.item.Item
import org.alter.game.model.move.moveTo
import org.alter.plugins.content.interfaces.attack.AttackTab
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * Spawns, dresses and tears down [PkBot] fake-players.
 *
 * The spawn sequence mirrors a real login (see `LoginWorker` / `Player.login`) but skips every
 * client-only step:
 *   1. construct + name the bot, register it to the world (assigns its player index),
 *   2. allocate the GPI / NPC-info / world-entity-info avatars for that index — REQUIRED, because
 *      `SequentialSynchronizationTask` updates these for every registered player each tick,
 *   3. push real skill levels + the loadout's worn gear (which feeds the appearance block and the
 *      combat formulae), then position it and sync its appearance so real clients render it.
 *
 * No socket, no `write()`, no login plugins — a base [org.alter.game.model.entity.Player] no-ops
 * all of those.
 */
object BotManager {

    // Skill indices (mirrors Player.getCurrentHp(), which reads skill 3 for hitpoints).
    private const val ATTACK = 0
    private const val DEFENCE = 1
    private const val STRENGTH = 2
    private const val HITPOINTS = 3
    private const val RANGED = 4
    private const val PRAYER = 5
    private const val MAGIC = 6

    /** Every live bot, for tick-driving the brain and for admin teardown. */
    val active = mutableListOf<PkBot>()

    private var nameCounter = 0

    /** Every PKer bot shows the same display name (the uid below stays unique for lookup). */
    private const val ROGUE_NAME = "Rogue Knight"

    /**
     * A tile near [centre] that a pawn standing at [from] could actually WALK to, route-finder
     * verified. [World.findRandomTileAround] only checks single-tile clipping, so an enclosed but
     * unclipped pocket (e.g. the teller corridor behind the home bank booths) passes it — a bot
     * spawned there is permanently unreachable by players and companions. Null when no reachable
     * candidate is found in [attempts] tries; callers fall back like they would on a null spawn tile.
     */
    fun reachableTileAround(world: World, from: Tile, centre: Tile, radius: Int, attempts: Int = 5): Tile? {
        repeat(attempts) {
            val candidate = world.findRandomTileAround(centre, radius) ?: return@repeat
            if (candidate.height != from.height) return@repeat
            val route = world.smartRouteFinder.findRoute(
                level = from.height,
                srcX = from.x,
                srcZ = from.z,
                destX = candidate.x,
                destZ = candidate.z,
            )
            if (route.success && !route.alternative) return candidate
        }
        return null
    }

    fun spawn(world: World, loadout: BotLoadout, tile: Tile): PkBot? {
        val bot = PkBot(world, loadout)
        nameCounter++ // bump once per spawn so the uid is always unique (the name is shared)
        bot.username = ROGUE_NAME
        bot.uid = PlayerUID("pkbot-${nameCounter}-${loadout.key}")
        bot.combatLevel = loadout.combatLevel

        // Assigns the player index; nothing here needs a session.
        if (!world.register(bot)) return null

        // Allocate the avatars exactly like LoginWorker does — must happen before the next sync tick.
        bot.playerInfo = world.network.playerInfoProtocol.alloc(bot.index, OldSchoolClientType.DESKTOP)
        bot.npcInfo = world.network.npcInfoProtocol.alloc(bot.index, OldSchoolClientType.DESKTOP)
        bot.worldEntityInfo = world.network.worldEntityInfoProtocol.alloc(bot.index, OldSchoolClientType.DESKTOP)

        applyStats(bot, loadout.stats)
        applyInventory(bot, loadout.inventory)
        AttackTab.setEnergy(bot, 100) // full special-attack energy to open with
        // Spellcasting prerequisites MagicSpells.canCast checks on every cast: the right spellbook
        // (every bot spell is an ancient) and no rune cost (companions already get the same varbit).
        // Without these a mage bot's cast was refused before it ever fired.
        if (loadout.spell.isNotEmpty()) {
            bot.setSpellbook(Spellbook.ANCIENTS)
            bot.setVarbit(MagicSpells.INF_RUNES_VARBIT, 1)
        }

        // moveTo touches playerInfo.avatar, so it must run after the alloc above.
        bot.homeTile = tile
        bot.moveTo(tile)
        bot.playerInfo.updateCoord(tile.height, tile.x, tile.z)
        bot.npcInfo.updateCoord(-1, tile.height, tile.x, tile.z)
        bot.worldEntityInfo.updateCoord(-1, tile.height, tile.x, tile.z)

        // PKer bots are skulled by default — they look like real risk-it-all PKers, and it's set on
        // the player.skullIcon field (NOT a timed skull(), so SkullRemovalPlugin never clears it) so
        // the very next syncAppearance below broadcasts it. Combat.applySkull leaves bots alone, and
        // every gear-swap re-sync re-applies it from this field, so the skull persists for the bot's life.
        bot.skullIcon = SkullIcon.WHITE.id

        validateLoadout(loadout)
        // Dress it in its base style; this also syncs the appearance block so clients see the gear.
        equipStyle(bot, loadout.baseStyle)
        // Configure casting-spell / attack-style for the base style (so MAGIC-base bots autocast).
        BotBrain.configureStyle(bot)
        bot.initiated = true

        active += bot
        return bot
    }

    /**
     * Register an already-constructed bot/companion into the world: assign its player index, allocate
     * the GPI / NPC-info / world-entity-info avatars (REQUIRED before the next sync tick), and position
     * it. Shared by [spawn] and the companion system (which subclasses [PkBot]). Returns false if the
     * world is full. Does NOT add to [active] — the caller owns the tick lifecycle.
     */
    fun bringIntoWorld(world: World, bot: PkBot, tile: Tile): Boolean {
        if (!world.register(bot)) return false
        bot.playerInfo = world.network.playerInfoProtocol.alloc(bot.index, OldSchoolClientType.DESKTOP)
        bot.npcInfo = world.network.npcInfoProtocol.alloc(bot.index, OldSchoolClientType.DESKTOP)
        bot.worldEntityInfo = world.network.worldEntityInfoProtocol.alloc(bot.index, OldSchoolClientType.DESKTOP)
        bot.homeTile = tile
        bot.moveTo(tile)
        bot.playerInfo.updateCoord(tile.height, tile.x, tile.z)
        bot.npcInfo.updateCoord(-1, tile.height, tile.x, tile.z)
        bot.worldEntityInfo.updateCoord(-1, tile.height, tile.x, tile.z)
        return true
    }

    private val validatedLoadouts = HashSet<String>()

    /**
     * Once per loadout key: every RANGED gear block's bow/crossbow must accept the ammo it wears,
     * or the ranged strategy refuses the attack forever and the bot just stands there — a chat
     * message a clientless bot can never read ("some rangers do not range", 2026-09-03: four
     * loadouts paired a magic shortbow (i) with dragon arrows).
     */
    private fun validateLoadout(loadout: BotLoadout) {
        if (!validatedLoadouts.add(loadout.key)) return
        loadout.gear.forEach { (style, gear) ->
            val weaponKey = gear[EquipmentType.WEAPON] ?: return@forEach
            val ammoKey = gear[EquipmentType.AMMO]
            val weapon = runCatching { getRSCM(weaponKey) }.getOrNull() ?: return@forEach
            val ammo = ammoKey?.let { runCatching { getRSCM(it) }.getOrNull() }
            val bow = BowType.values.firstOrNull { it.item == weapon }
            val crossbow = CrossbowType.values.firstOrNull { it.item == weapon }
            val bad = when {
                bow != null && bow.ammo.isNotEmpty() && ammo !in bow.ammo -> true
                crossbow != null && ammo !in crossbow.ammo -> true
                else -> false
            }
            if (bad) {
                logger.error { "bot loadout '${loadout.key}' [$style]: $weaponKey cannot fire ${ammoKey ?: "an empty quiver"} — this bot will never attack in that style." }
            }
        }
    }

    /** Replace the bot's worn equipment with a different style's full set and re-render it. */
    fun equipStyle(bot: PkBot, style: BotStyle) {
        val gear = bot.fightLoadout.gear[style] ?: return
        applyGear(bot, gear)
        bot.currentStyle = style
    }

    /** Overwrite the worn equipment with [gear], recompute bonuses, and re-sync appearance. */
    fun applyGear(bot: PkBot, gear: GearBlock) {
        for (i in 0 until bot.equipment.capacity) bot.equipment[i] = null
        gear.forEach { (slot, name) -> bot.equipment[slot.id] = Item(getRSCM(name)) }
        bot.calculateBonuses()
        // The weapon-type varbit (357) is normally set by the CLIENT on wield; a clientless bot never
        // gets it, so getCombatClass()/isWieldingMagicWeapon() would read every bot as MELEE — mages
        // would never cast and rangers never shoot. Set it server-side from the equipped weapon's def.
        val weapon = bot.equipment[EquipmentType.WEAPON.id]
        bot.setVarbit(Varbit.WEAPON_TYPE_VARBIT, if (weapon != null) weapon.getDef().weaponType else 0)
        PlayerInfo(bot).syncAppearance()
    }

    private fun applyStats(bot: PkBot, s: BotStats) {
        val skills = bot.getSkills()
        fun set(skill: Int, level: Int) {
            skills.setBaseLevel(skill, level)
            skills.setCurrentLevel(skill, level)
        }
        set(ATTACK, s.attack)
        set(STRENGTH, s.strength)
        set(DEFENCE, s.defence)
        set(HITPOINTS, s.hitpoints)
        set(RANGED, s.ranged)
        set(MAGIC, s.magic)
        set(PRAYER, s.prayer)
    }

    private fun applyInventory(bot: PkBot, items: List<BotItem>) {
        var slot = 0
        for (line in items) {
            if (slot >= bot.inventory.capacity) break
            bot.inventory[slot++] = Item(getRSCM(line.name), line.amount)
        }
    }

    /**
     * Remove a bot from the world. Companions are PROTECTED: a [org.alter.plugins.content.companion.Companion]
     * is only despawned when [force] is true (the legitimate logout/store path). Any other caller trying to
     * despawn a companion (e.g. a death handler) is BLOCKED and a stack trace is logged — losing a companion
     * on death is a bug (they must auto-respawn). This guards against the accidental-unregister that was
     * permanently deleting companions when they died.
     */
    fun despawn(world: World, bot: PkBot, force: Boolean = false) {
        if (bot is org.alter.plugins.content.companion.Companion && !force) {
            logger.warn(Throwable("despawn call site")) { "BLOCKED despawn of companion '${bot.username}' (idx=${bot.index}) — not a forced/logout despawn; companions must survive death." }
            return
        }
        // Anyone locked onto this bot must let go NOW. world.unregister leaves each attacker's combat
        // target a stale WeakReference that still resolves to the bot object — which the player death
        // sequence has already restored to full HP and teleported to the home tile (Lumbridge). The
        // combat loop only gives up when the target's HP hits 0, so it keeps pathing toward that ghost:
        // the "run back to spawn after killing the goblin bot" report. Clearing attackers stops it dead.
        bot.clearAttackers()
        if (bot.index >= 0) world.unregister(bot)
        active.remove(bot)
    }

    fun despawnAll(world: World) {
        active.toList().forEach { despawn(world, it) }
    }
}
