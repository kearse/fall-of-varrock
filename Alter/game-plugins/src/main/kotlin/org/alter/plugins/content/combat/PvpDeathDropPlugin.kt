package org.alter.plugins.content.combat

import org.alter.api.SkullIcon
import org.alter.api.ext.hasSkullIcon
import org.alter.api.ext.hit
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.attr.PROTECT_ITEM_ATTR
import org.alter.game.model.container.ItemContainer
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bots.PkBot
import org.alter.plugins.content.economy.pk.LootKeys
import org.alter.plugins.service.marketvalue.ItemMarketValueService

/**
 * OSRS item-on-death — EVERYWHERE (docs/osrs-death-system.md).
 *
 * On any player death the victim keeps their N most valuable items and loses the rest:
 *  - unskulled: keep 3 (4 with Protect Item)
 *  - skulled:   keep 0 (1 with Protect Item)
 * Untradeables are always kept and don't use up a keep slot (OSRS keeps them on top of the 3).
 *
 * Where the lost items go depends on where you died:
 *  - **Wilderness:** PvP loot — a real-player killer owns the private window; bot/no killer →
 *    public immediately. (Unchanged behavior.)
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
            dropOnDeath(victim)
        }

        onCommand("testdeath", Privilege.DEV_POWER, description = "Kill yourself to test the death-drop rules at this tile") {
            player.hit(damage = player.getCurrentHp())
        }
    }

    private fun dropOnDeath(victim: Player) {
        // Unclaimed loot keys are ALWAYS lost on death (OSRS) — no keep slot, no Protect Item:
        // the handles are destroyed and the sealed contents join the rest of the lost loot below.
        val keyLoot = LootKeys.confiscate(victim)

        val protectItem = victim.attr[PROTECT_ITEM_ATTR] == true
        val skulled = victim.hasSkullIcon(SkullIcon.WHITE) || victim.hasSkullIcon(SkullIcon.RED)
        val keep = when {
            skulled && protectItem -> 1
            skulled -> 0
            protectItem -> 4
            else -> 3
        }

        // Everything worn + carried, as (container, slot, item). Key handles are already gone.
        val held = ArrayList<Triple<ItemContainer, Int, Item>>()
        for (i in 0 until victim.equipment.capacity) victim.equipment[i]?.let { held += Triple(victim.equipment, i, it) }
        for (i in 0 until victim.inventory.capacity) victim.inventory[i]?.let { held += Triple(victim.inventory, i, it) }

        // Untradeables never drop; the [keep] most valuable tradeables stay too. The rest is lost.
        val lost = held.filter { it.third.getDef().isTradeable }
            .sortedByDescending { unitValue(it.third) }
            .drop(keep)
        if (lost.isEmpty() && keyLoot.isEmpty()) return

        val loot = lost.map { it.third } + keyLoot
        lost.forEach { (container, slot, _) -> container[slot] = null }

        val tile = victim.tile
        if (PvpZones.isWilderness(tile)) {
            // PvP loot: a real-player killer gets it sealed in a loot key — anything past the
            // key's 28-stack cap (the overflow) still hits the ground. Null = no key (cap/full
            // inventory/bot killer) → everything ground-drops (public when the killer is a bot).
            val killer = victim.attr[KILLER_ATTR]?.get() as? Player
            val owner = killer?.takeIf { it !is PkBot }
            val overflow = if (owner != null) LootKeys.tryAward(owner, victim.username, loot) else null
            (overflow ?: loot).forEach { world.spawn(GroundItem(it.id, it.amount, tile, owner)) }
        } else {
            // Safe-zone death: victim-owned reclaim pile with a long private window.
            loot.forEach { item ->
                val drop = GroundItem(item.id, item.amount, tile, victim)
                drop.publicDelayOverride = RECLAIM_PRIVATE_CYCLES
                drop.despawnDelayOverride = RECLAIM_PRIVATE_CYCLES + RECLAIM_PUBLIC_CYCLES
                world.spawn(drop)
            }
            victim.message("Your items lie where you fell. You have ~15 minutes to return and reclaim them.")
        }
    }

    /** Per-item market value, falling back to the cache cost when the price service has none. */
    private fun unitValue(item: Item): Int {
        val price = priceService?.get(item.id) ?: 0
        return if (price > 0) price else (item.getDef().cost ?: 0)
    }

    companion object {
        /** How long a safe-death reclaim pile stays victim-only (~15 min at 600ms cycles). */
        private const val RECLAIM_PRIVATE_CYCLES = 1500

        /** Once public, how much longer the pile survives before despawning. */
        private const val RECLAIM_PUBLIC_CYCLES = 300
    }
}
