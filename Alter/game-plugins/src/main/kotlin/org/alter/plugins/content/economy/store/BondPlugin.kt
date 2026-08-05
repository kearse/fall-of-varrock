package org.alter.plugins.content.economy.store

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.World
import org.alter.game.model.entity.Client
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.game.saving.formats.impl.DatabaseManager
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints
import org.alter.plugins.content.mechanics.shops.bindVendorOptions
import org.alter.plugins.content.war.WarNpcNames
import org.alter.rscm.RSCM.getRSCM
import org.bson.Document

private val logger = KotlinLogging.logger {}

/**
 * Server half of the client-drawn **Bond Exchange** window (`lofbonds`): the wallet (tradeable vs
 * claimed bonds) and the two redemptions as cards, replacing the Bond Merchant's chat menu.
 *
 * State is one packed pulse varp: bit 0 open flag · bits 1-10 tradeable count · bits 11-20
 * claimed count (counts clamped to 1023). Actions come back as `::bond <claim|member|donor>`
 * → `bondclick` (handled in [BondPlugin], which owns the claim confirm + redemption invariants).
 */
object BondMenu {
    /** Overlay-open varp (docs/overlay-design-system.md §8) — pulsed to 0, never persisted. */
    const val OPEN_VARP = 4629

    fun open(p: Player, tradeable: Int, claimed: Int) {
        val v = 1 or (tradeable.coerceIn(0, 1023) shl 1) or (claimed.coerceIn(0, 1023) shl 11)
        p.setVarp(OPEN_VARP, v)
        p.queue { wait(2); p.setVarp(OPEN_VARP, 0) }
    }
}

/**
 * **Bonds** — the tradeable premium currency (bond-system-spec.md, Track C #1 "build first").
 *
 * The loop: a payer buys Bonds for cash on the website (delivered as `item.bond` by
 * [RewardDeliveryPlugin.applyItems] — zero new delivery code); they either trade them to other
 * players for gold, or **Claim** one (right-click "Redeem" on the tradeable bond) which converts
 * it — irreversibly, confirmation-gated — into `item.bond_untradeable`. The untradeable bond is
 * then **redeemed** (inventory "Redeem", the Bond Merchant at the shop hub, or `::bonds`) for
 * membership time or Donor Points. Non-payers reach the exact same perks by buying a tradeable
 * bond off another player with earned gold — the "never have to pay to be a member" promise.
 *
 * Economic invariants (spec §2.3 — a bond must NEVER mint gold):
 *  - Bonds are never NPC-sold for gold and never redeemed for gold. Cache cost on 13190 is 2m and
 *    can't be overridden, so every NPC gp-conversion path denies bonds: alch (AlchemyPlugin),
 *    Trading Post 85% buy (TradingPostCurrency), general store 70% buy (GeneralStoreCurrency's
 *    cost>5k deny). Gold only ever moves player -> player when a bond trades hands.
 *  - Claiming is one-way: there is no untradeable -> tradeable code path (anti wash-trading).
 *  - Redemption is remove-first / refund-on-grant-fail (mirrors ItemCurrency ordering), and every
 *    redemption writes a `bond_redemptions` audit doc when the store DB is up.
 */
class BondPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val bond: Int? = resolveOrNull("item.bond")
    private val bondU: Int? = resolveOrNull("item.bond_untradeable")

    init {
        // ---- inventory options (both defs carry a native cache "Redeem"; 13192 also "Convert").
        // Guarded binds like PotionsPlugin — a missing option/def logs instead of dropping the plugin.
        bindItemOption("item.bond", "redeem") { p -> claimDialog(p) }
        bindItemOption("item.bond_untradeable", "redeem") { p -> openMenu(p) }
        bindItemOption("item.bond_untradeable", "convert") { p ->
            p.message("Claimed bonds are yours alone — they can never be made tradeable again.")
        }

        // ---- the Bond Merchant, on the east shop row just south of the Reward Exchange.
        spawnNpc(MERCHANT, 3209, 3211, 0, 0, Direction.NORTH)
        // The merchant opens the client-drawn Bond Exchange window (lofbonds) — wallet + the two
        // redemption cards; the permanent-claim warning is drawn, not spoken.
        bindVendorOptions(MERCHANT) { openMenu(player) }
        onNpcSpawn(MERCHANT) { WarNpcNames.rename(npc, "Bond Merchant") }

        // Guaranteed-reachable fallback, same pattern as ::market / ::pkshop.
        onCommand("bonds", description = "Redeem your bonds (membership / Donor Points)") {
            openMenu(player)
        }

        // The window's action channel ("::bond <action>" → bondclick). Also testable directly.
        onCommand("bondclick", description = "Bond Exchange window action (client overlay channel)") {
            when (player.getCommandArgs().getOrNull(0)?.lowercase()) {
                // Claiming is permanent — it keeps its native confirm dialog before converting.
                "claim" -> {
                    val t = bond ?: return@onCommand
                    if (player.inventory.getItemCount(t) <= 0) {
                        player.message("You don't have a tradeable bond to claim.")
                    } else {
                        claimFromMenu(player)
                    }
                }
                "member" -> {
                    redeem(player, cost = 1, description = "30 days bronze membership") { c ->
                        grantMembership(c, days = 30)
                    }
                    openMenu(player)
                }
                "donor" -> {
                    redeem(player, cost = 1, description = "450 Donor Points") { c ->
                        c.addPoints(PointKind.DONOR, DONOR_POINTS_PER_BOND)
                        c.message("You received <col=801700>$DONOR_POINTS_PER_BOND Donor Points</col>.")
                        true
                    }
                    openMenu(player)
                }
            }
        }
    }

    /** Open (or refresh) the Bond Exchange window with the player's current wallet. */
    private fun openMenu(p: Player) {
        val tradeable = bond?.let { p.inventory.getItemCount(it) } ?: 0
        val claimed = bondU?.let { p.inventory.getItemCount(it) } ?: 0
        BondMenu.open(p, tradeable, claimed)
    }

    /** The window's claim path: native confirm (permanent!), then convert + refresh the window. */
    private fun claimFromMenu(player: Player) {
        player.queue {
            when (options(
                player,
                "Claim it — I understand it becomes untradeable.",
                "Keep it tradeable.",
                title = "Claiming a bond is permanent!",
            )) {
                1 -> {
                    claim(player, slot = 0)
                    openMenu(player)
                }
            }
        }
    }

    /** Bind one inventory item-option defensively (cache defs vary; never crash plugin init). */
    private fun bindItemOption(key: String, option: String, logic: (Player) -> Unit) {
        try {
            onItemOption(item = key, option = option) { logic(player) }
        } catch (e: Exception) {
            logger.warn { "bonds: couldn't bind '$option' on $key — ${e.message}" }
        }
    }

    // ------------------------------- claim (tradeable -> untradeable) -------------------------------

    private fun claimDialog(player: Player) {
        // Capture the clicked slot NOW (interacting-slot gotcha) — used as the remove/add hint.
        val slot = player.getInteractingSlot()
        player.queue {
            when (options(
                player,
                "Claim it — I understand it becomes untradeable.",
                "Keep it tradeable.",
                title = "Claiming a bond is permanent!",
            )) {
                1 -> claim(player, slot)
            }
        }
    }

    private fun claim(player: Player, slot: Int) {
        val t = bond ?: return
        val u = bondU ?: return
        // Remove-first; beginSlot is a search hint so this still works if the bond moved mid-dialog.
        if (player.inventory.remove(item = t, amount = 1, beginSlot = slot, assureFullRemoval = true).hasFailed()) {
            player.message("You don't seem to have a tradeable bond.")
            return
        }
        val add = player.inventory.add(item = u, amount = 1, beginSlot = slot)
        if (add.completed == 0) {
            player.inventory.add(item = t, amount = 1, beginSlot = slot) // slot was just freed — refund
            player.message("You don't have room to claim the bond.")
            return
        }
        player.message("<col=801700>Bond claimed.</col> It is now untradeable — redeem it for membership or Donor Points.")
    }

    // ------------------------------- redemption -------------------------------

    // NB: 1 bond = 1 MONTH of membership (owner's call 2026-07-04 — "I hate that OSRS does
    // 14-day bonds"). Clean parity: $4.99 bond = 30d bronze = the $4.99/30d web price. The
    // "what is a bond" story is printed on the Bond Exchange window itself now.

    /** Membership grant via the shared service; false (refund) when the store DB is down. */
    private fun grantMembership(p: Player, days: Int): Boolean {
        val client = p as? Client ?: return false
        if (!MembershipService.storeAvailable()) {
            p.message("Membership redemption is unavailable right now — your bond was not consumed.")
            return false
        }
        return MembershipService.grant(client, tier = "bronze", days = days)
    }

    /**
     * Spend [cost] untradeable bonds on [grant]. Remove-first; if the grant fails the bonds are
     * refunded — never grant twice, never consume without granting (spec §2.4.3).
     */
    private fun redeem(p: Player, cost: Int, description: String, grant: (Player) -> Boolean) {
        val u = bondU ?: return
        if (p.inventory.getItemCount(u) < cost) {
            p.message("You need $cost <col=801700>claimed</col> bond(s) for that. (Right-click a tradeable bond and Redeem to claim it.)")
            return
        }
        if (p.inventory.remove(item = u, amount = cost, assureFullRemoval = true).hasFailed()) return
        val ok = try {
            grant(p)
        } catch (e: Exception) {
            logger.warn(e) { "bond redemption grant failed for ${p.username} ($description)" }
            false
        }
        if (!ok) {
            p.inventory.add(item = u, amount = cost)
            return
        }
        audit(p, description, cost)
        p.message("<col=801700>Redeemed $cost bond(s):</col> $description.")
        logger.info { "BOND ${p.username} redeemed $cost bond(s) -> $description" }
    }

    /** Best-effort audit row (spec §3.4); skipped silently on JSON saves / DB down. */
    private fun audit(p: Player, kind: String, bonds: Int) {
        try {
            val client = p as? Client ?: return
            DatabaseManager.connect()
            DatabaseManager.getCollection("bond_redemptions").insertOne(
                Document()
                    .append("loginUsername", client.loginUsername)
                    .append("kind", kind)
                    .append("bonds", bonds)
                    .append("grantedAt", System.currentTimeMillis()),
            )
        } catch (e: Exception) {
            logger.warn { "bond audit write skipped: ${e.message}" }
        }
    }

    private fun resolveOrNull(key: String): Int? = try { getRSCM(key) } catch (e: Exception) { null }

    private companion object {
        const val MERCHANT = "npc.banknote_exchange_merchant"

        /** Pegged just below the cash rate (web: 500 DP = $5) so direct purchase is always
         *  marginally better value — protects the bond's job as the membership vehicle. */
        const val DONOR_POINTS_PER_BOND = 450
    }
}
