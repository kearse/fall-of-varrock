package org.alter.plugins.content.items.charging

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Charging the Zulrah gear with Zulrah's scales** â€” the toxic blowpipe and the serpentine
 * (and magma / tanzanite) helms.
 *
 * Player report 2026-09-18: "not able to charge uncharged items (blowpipe and serp helm)". The
 * uncharged forms are not merely weaker â€” in the rev-228 cache they carry no Wear/Wield option at
 * all (`inv=[null, null, null, Dismantle, Drop]`), so an uncharged serpentine helm or an empty
 * blowpipe was a completely dead item taking up a bank slot, and Zulrah's scales had no use
 * anywhere in the game despite dropping 100-299 at a time.
 *
 * Using scales on the uncharged item converts it to the charged one and banks the scales as a
 * persisted charge count; "Uncharge" reverses it and hands the remaining scales back.
 *
 * **Charges are not CONSUMED by combat, deliberately.** The charged blowpipe and helms already work
 * on this server, and adding per-attack drain would take gear that functions today and start
 * degrading it â€” a nerf to every current owner, which is a balance decision for the operator rather
 * than part of a bug fix. The counter is stored and reported so the drain can be switched on later
 * without another migration: see [CHARGE_ATTR] and the `TUNE` note on [Chargeable.capacity].
 */
class ScaleChargingPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        for (c in CHARGEABLES) {
            val uncharged = resolve(c.uncharged) ?: continue
            val charged = resolve(c.charged) ?: continue

            // Scales onto the uncharged item, either way round â€” onItemOnItem binds both orders.
            runCatching {
                onItemOnItem(SCALES, c.uncharged) { charge(player, c, uncharged, charged) }
            }.onFailure { logger.warn { "scale-charging: could not bind scales onto ${c.uncharged}" } }

            // "Uncharge" on the charged item gives the scales back.
            runCatching {
                onItemOption(item = c.charged, option = "uncharge") { uncharge(player, c, uncharged, charged) }
            }.onFailure { logger.warn { "scale-charging: ${c.charged} has no Uncharge option" } }

            runCatching {
                onItemOption(item = c.charged, option = "check") { check(player, c) }
            }
        }
    }

    private fun charge(player: Player, c: Chargeable, uncharged: Int, charged: Int) {
        val scalesId = resolve(SCALES) ?: return
        if (!player.inventory.contains(uncharged)) return
        val held = player.inventory.getItemCount(scalesId)
        if (held <= 0) {
            player.message("You need Zulrah's scales to charge that.")
            return
        }
        val take = minOf(held, c.capacity)
        if (player.inventory.remove(item = scalesId, amount = take).completed != take) return
        if (player.inventory.remove(item = uncharged, amount = 1).completed == 0) {
            // Put the scales back rather than eating them on a failed swap.
            player.inventory.add(item = scalesId, amount = take)
            return
        }
        player.inventory.add(item = charged, amount = 1)
        player.attr[c.chargeAttr] = (player.attr[c.chargeAttr] ?: 0) + take
        player.message("You charge the ${c.display} with $take of Zulrah's scales.")
    }

    private fun uncharge(player: Player, c: Chargeable, uncharged: Int, charged: Int) {
        if (player.inventory.remove(item = charged, amount = 1).completed == 0) return
        player.inventory.add(item = uncharged, amount = 1)
        val stored = player.attr[c.chargeAttr] ?: 0
        if (stored > 0) {
            // assureFullInsertion = false: a full pack keeps what fits, the rest is announced
            // rather than destroyed.
            val scalesId = resolve(SCALES)
            val added = if (scalesId == null) 0 else
                player.inventory.add(item = scalesId, amount = stored, assureFullInsertion = false).completed
            player.attr[c.chargeAttr] = stored - added
            if (added < stored) {
                player.message("You have no room for the rest of the scales; they stay in the ${c.display}.")
            }
        }
        player.message("You uncharge the ${c.display}.")
    }

    private fun check(player: Player, c: Chargeable) {
        val stored = player.attr[c.chargeAttr] ?: 0
        player.message("Your ${c.display} has <col=801700>$stored</col> of Zulrah's scales stored.")
    }

    private fun resolve(key: String): Int? = runCatching { getRSCM(key) }.getOrNull()

    private companion object {
        const val SCALES = "item.zulrahs_scales"

        /**
         * One chargeable pair. [capacity] is the OSRS maximum the item holds â€” TUNE, and the number
         * that matters once charge consumption is switched on.
         */
        data class Chargeable(
            val uncharged: String,
            val charged: String,
            val display: String,
            val capacity: Int,
            val chargeAttr: org.alter.game.model.attr.AttributeKey<Int>,
        )

        // Persisted per item family, so a charged item keeps its scales across a logout.
        val BLOWPIPE_CHARGES = org.alter.game.model.attr.AttributeKey<Int>(persistenceKey = "blowpipe_scales")
        val SERP_CHARGES = org.alter.game.model.attr.AttributeKey<Int>(persistenceKey = "serpentine_helm_scales")
        val TANZ_CHARGES = org.alter.game.model.attr.AttributeKey<Int>(persistenceKey = "tanzanite_helm_scales")
        val MAGMA_CHARGES = org.alter.game.model.attr.AttributeKey<Int>(persistenceKey = "magma_helm_scales")

        val CHARGEABLES = listOf(
            Chargeable("item.toxic_blowpipe_empty", "item.toxic_blowpipe", "toxic blowpipe", 16_383, BLOWPIPE_CHARGES),
            Chargeable("item.serpentine_helm_uncharged", "item.serpentine_helm", "serpentine helm", 11_000, SERP_CHARGES),
            Chargeable("item.tanzanite_helm_uncharged", "item.tanzanite_helm", "tanzanite helm", 11_000, TANZ_CHARGES),
            Chargeable("item.magma_helm_uncharged", "item.magma_helm", "magma helm", 11_000, MAGMA_CHARGES),
        )
    }
}
