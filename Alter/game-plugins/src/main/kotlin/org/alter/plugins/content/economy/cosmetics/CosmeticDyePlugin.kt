package org.alter.plugins.content.economy.cosmetics

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.api.ext.npc
import org.alter.api.ext.openShop
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.model.shop.PurchasePolicy
import org.alter.game.model.shop.ShopItem
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.mechanics.shops.CoinCurrency
import org.alter.plugins.content.mechanics.shops.bindVendorOptions
import org.alter.plugins.content.war.WarNpcNames
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Cosmetic dyes** — the on-brand item sink (item-sink-plan §2D). Pure cosmetic, no power, so it
 * sits squarely inside the "cosmetic + convenience only" pledge while draining wealth:
 *  - **gp sink:** dyes are bought for gp (1m–2.5m each) — a late-game drain that scales with wealth.
 *  - **item sink:** the dye is consumed on use, and re-dyeing **destroys the previous cape**.
 *
 * A dye used on a Champion's Cape (the base cosmetic, or any already-dyed colour) recolours it into
 * the matching variant (statless overrides authored in CustomLaunch.yml). Reuses existing dye items
 * and coloured-cape models — no new currency, no core-combat/death changes.
 */
class CosmeticDyePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private data class Dye(val color: String, val dyeKey: String, val variantKey: String, val gp: Int)

    private val dyes = listOf(
        Dye("red", "item.red_dye", "item.champions_cape_red", 1_000_000),
        Dye("blue", "item.blue_dye", "item.champions_cape_blue", 1_000_000),
        Dye("green", "item.green_dye", "item.champions_cape_green", 1_000_000),
        Dye("purple", "item.purple_dye", "item.champions_cape_purple", 2_500_000),
    )

    init {
        // gp sink: a coin shop selling the dyes. BUY_NONE = sell-only at our price (the drain).
        val stock = dyes.mapNotNull { d -> res(d.dyeKey)?.let { ShopItem(it, Int.MAX_VALUE, d.gp) } }
        createShop(SHOP, CoinCurrency(), purchasePolicy = PurchasePolicy.BUY_NONE, stockSize = maxOf(stock.size, 1)) {
            stock.forEachIndexed { i, item -> items[i] = item }
        }
        // Optional vendor (the OSRS dye witch); ::dyes is the guaranteed opener regardless.
        if (res("npc.aggie") != null) {
            spawnNpc("npc.aggie", x = 3211, z = 3211, height = 0, walkRadius = 0, direction = Direction.NORTH)
            // Shown as "Cosmetics" (store-type name, like the shop hub's vendors) via
            // extended-info — no cache edit; re-applied on respawn by the npc-spawn hook.
            onNpcSpawn("npc.aggie") { WarNpcNames.rename(npc, "Cosmetics") }
            bind("npc.aggie")
        }
        onCommand("dyes", description = "Open the Cosmetic Dyes shop") { player.openShop(SHOP) }

        // The sink: dye on a Champion's Cape (base OR another colour) -> the matching variant. Re-dyeing
        // consumes the dye and destroys the previous cape. Bind every (dye x source-cape) pair that isn't
        // a no-op (same colour) and whose items all resolve.
        val sources = listOf(BASE) + dyes.map { it.variantKey }
        dyes.forEach { d ->
            sources.filter { it != d.variantKey }.forEach { src ->
                if (res(d.dyeKey) != null && res(src) != null && res(d.variantKey) != null) {
                    onItemOnItem(d.dyeKey, src) { player.queue { applyDye(player, d, src) } }
                }
            }
        }
    }

    private fun QueueTask.applyDye(p: Player, d: Dye, srcKey: String) {
        val dyeId = getRSCM(d.dyeKey)
        val capeId = getRSCM(srcKey)
        val outId = getRSCM(d.variantKey)
        // Remove the dye first; then the cape. If the cape removal somehow fails, refund the dye (no loss).
        if (p.inventory.remove(item = dyeId, amount = 1).completed == 0) return
        if (p.inventory.remove(item = capeId, amount = 1).completed == 0) {
            p.inventory.add(dyeId, 1)
            return
        }
        p.inventory.add(outId, 1)
        p.message("You work the ${d.color} dye into the cape. The dye and the old cape are used up.")
    }

    /** Bind EVERY vendor option (Talk-to AND Trade) so neither is a dead click on the vendor. */
    private fun bind(npc: String) {
        if (!bindVendorOptions(npc) { player.openShop(SHOP) }) {
            logger.warn { "cosmetic-dyes: '$npc' has no click options; use ::dyes." }
        }
    }

    private fun res(key: String): Int? = try { getRSCM(key) } catch (e: Exception) { null }

    private companion object {
        const val SHOP = "Cosmetic Dyes"
        const val BASE = "item.champions_cape"
    }
}
