package org.alter.plugins.content.combat

import org.alter.api.ext.hit
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bots.PkBot
import org.alter.plugins.content.economy.pk.LootKeys
import org.alter.plugins.content.economy.pk.PkKillGuard
import org.alter.plugins.service.marketvalue.ItemMarketValueService

/**
 * OSRS item-on-death — EVERYWHERE (docs/osrs-death-system.md).
 *
 * On any player death the victim keeps their N most valuable items and loses the rest:
 *  - unskulled: keep 3 (4 with Protect Item)
 *  - skulled:   keep 0 (1 with Protect Item)
 * Untradeables are always kept and don't use up a keep slot (OSRS keeps them on top of the 3).
 *
 * Where the lost items go depends on who killed you and where:
 *  - **A real-player killer (anywhere):** the loot is sealed into a loot key for the killer
 *    ([LootKeys.tryAward]) — wilderness or not. Overflow/no-key remainders follow the zone
 *    rules below.
 *  - **Wilderness:** ground loot — a real-player killer owns the private window; bot/no
 *    killer → public immediately.
 *  - **Anywhere else (PvE, roads, towns):** a reclaim pile owned by the VICTIM on the death
 *    tile, private for [RECLAIM_PRIVATE_CYCLES] (~15 min) so they can walk back from respawn,
 *    then public briefly before despawning.
 *
 * Runs on `onPlayerPreDeath` — before the death sequence restores/respawns the player, while
 * `KILLER_ATTR`, the containers, and `PROTECT_ITEM_ATTR` are all still intact. Bots (and their
 * `Companion` subclass) are skipped (their kit-drop is handled in `BotCombatPlugin`), as are
 * designed-safe deaths ([SafeDeaths]: minigames, boss arenas, instances).
 */
class PvpDeathDropPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val priceService = world.getService(ItemMarketValueService::class.java)

    init {
        onPlayerPreDeath {
            val victim = player
            if (victim is PkBot) return@onPlayerPreDeath          // bots + companions drop their kit elsewhere
            if (SafeDeaths.isSafeDeath(victim)) return@onPlayerPreDeath
            // Price the risk BEFORE the containers are stripped — the PK kill guard (Blood Money /
            // Elo legitimacy) reads it whichever pre-death hook runs first.
            PkKillGuard.captureRisk(world, victim)
            dropOnDeath(victim)
        }

        onCommand("testdeath", Privilege.DEV_POWER, description = "Kill yourself to test the death-drop rules at this tile") {
            player.hit(damage = player.getCurrentHp())
        }
    }

    private fun dropOnDeath(victim: Player) {
        // The keep-N split is computed by [DeathRisk] (read-only, key handles excluded) so the PK
        // kill guard can price the same risk BEFORE anything below mutates the containers.
        // Untradeables never drop; the [DeathRisk.Plan.keep] most valuable ITEMS (units, not
        // stacks) stay — a stack of blood runes keeps [keep] units and drops the remainder.
        val plan = DeathRisk.plan(victim, priceService)

        // Unclaimed loot keys are ALWAYS lost on death (OSRS) — no keep slot, no Protect Item:
        // the handles are destroyed and the sealed contents join the rest of the lost loot below.
        val keyLoot = LootKeys.confiscate(victim)

        val lostLoot = ArrayList<Item>()
        for (s in plan.slots) {
            if (s.lost <= 0) continue
            lostLoot += Item(s.item, s.lost)
            s.container[s.slot] = if (s.kept > 0) Item(s.item, s.kept) else null
        }
        if (lostLoot.isEmpty() && keyLoot.isEmpty()) return

        val loot = lostLoot + keyLoot

        val tile = victim.tile
        // ANY real-player kill seals the loot into a key for the killer — wilderness or not.
        // What's left (the key's 28-stack overflow, or everything when no key could be minted:
        // at the cap, full inventory, bot/no killer) follows the zone's normal drop rules.
        val killer = victim.attr[KILLER_ATTR]?.get() as? Player
        val owner = killer?.takeIf { it !is PkBot }
        val overflow = if (owner != null) LootKeys.tryAward(owner, victim.username, loot) else null
        val remainder = overflow ?: loot
        if (PvpZones.isWilderness(tile)) {
            remainder.forEach { world.spawn(GroundItem(it.id, it.amount, tile, owner)) }
        } else if (remainder.isNotEmpty()) {
            // Safe-zone death: victim-owned reclaim pile with a long private window.
            remainder.forEach { item ->
                val drop = GroundItem(item.id, item.amount, tile, victim)
                drop.publicDelayOverride = RECLAIM_PRIVATE_CYCLES
                drop.despawnDelayOverride = RECLAIM_PRIVATE_CYCLES + RECLAIM_PUBLIC_CYCLES
                world.spawn(drop)
            }
            victim.message("Your items lie where you fell. You have ~15 minutes to return and reclaim them.")
        }
    }

    companion object {
        /** How long a safe-death reclaim pile stays victim-only (~15 min at 600ms cycles). */
        private const val RECLAIM_PRIVATE_CYCLES = 1500

        /** Once public, how much longer the pile survives before despawning. */
        private const val RECLAIM_PUBLIC_CYCLES = 300
    }
}
