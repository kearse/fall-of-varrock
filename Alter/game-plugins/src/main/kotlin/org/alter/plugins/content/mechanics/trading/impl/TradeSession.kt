package org.alter.plugins.content.mechanics.trading.impl

import org.alter.api.ClientScript
import org.alter.api.InterfaceDestination
import org.alter.api.ext.*
import org.alter.api.ext.getInterfaceHash
import org.alter.game.model.container.ContainerStackType
import org.alter.game.model.container.ItemContainer
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.plugins.content.mechanics.trading.*
import org.alter.plugins.content.war.warprep.WarPrepChain
import org.alter.plugins.service.marketvalue.ItemMarketValueService

/**
 * @author Triston Plummer ("Dread")
 *
 * Represents a trading session between two players
 *
 * @param player    The player this trade session belongs to
 * @param partner   The partner of this trade session
 */
class TradeSession(
    private val player: Player,
    private val partner: Player,
    /**
     * If non-null this session is a **stake** (Duel Arena), not a trade: on completion the two
     * containers are handed to this hook (which escrows them and launches the duel) instead of being
     * swapped between the players. The whole two-screen UI is otherwise identical, so the classic
     * "put up items → confirm" stake screen is the trade screen, re-labelled.
     */
    private val stake: StakeHook? = null,
    /**
     * Stake-mode veto run when the pair advances to the confirm screen: given this session's
     * player and the free backpack slots they'll have once the stake is committed, return a
     * refusal message to bounce the stake (both sides declined, items returned), or null to let
     * it proceed. The duel plugin uses this for the classic "inventory space must be verified at
     * accept" rule — gear the duel's rules will strip at start has to fit in the backpack.
     */
    private val stakeVet: ((Player, Int) -> String?)? = null,
    /**
     * Stake-mode stage signal: fired with `open = true` when this session's player lands on the
     * confirm (second) screen, and `open = false` when the session ends for them (completed or
     * declined). The duel plugin drives the themed confirmation overlay (varp + opponent stats)
     * from exactly these two edges.
     */
    private val stakeConfirm: ((Player, Boolean) -> Unit)? = null,
) {
    /** True for a Duel-Arena stake session (vs a plain trade). Read by the themed stake overlay driver. */
    val isStake = stake != null

    /**
     * A copy of this player's inventory, so we don't interfere with the player's real inventory unless necessary
     */
    val inventory = ItemContainer(player.inventory)

    /**
     * The trade container for this trade session, in the current player's context
     */
    val container = ItemContainer(player.inventory.capacity, ContainerStackType.NORMAL)

    /**
     * The [ItemMarketValueService] instance for this trade session
     */
    private val priceService = player.world.getService(ItemMarketValueService::class.java)

    /**
     * The current 'stage' of the trade session. Read by [TradingPlugin]'s interface-close hooks:
     * the confirm screen replaces the trade screen on MAIN_SCREEN, and the engine fires the trade
     * screen's close hook for that replacement — the stage is how the hook tells the trade
     * advancing apart from the player actually closing it.
     */
    var stage: TradeStage = TradeStage.TRADE_SCREEN
        private set

    /**
     * Stake-mode anti-scam lockout (the 2015 Duel Arena Rework behaviour): the world tick until
     * which Accept is refused — armed for ~3 s by every stake change AND on entering the confirm
     * screen, on BOTH sides. The themed overlays show "Wait…" by watching the containers; this
     * field is what a spoofed `::lofstake a` runs into.
     */
    private var acceptLockedUntil = 0

    /** Ticks Accept stays locked after a stake change / on the confirm screen opening (~3 s). */
    private val changeLockTicks = 5

    /** Arm the accept lockout on THIS session (see [acceptLockedUntil]). */
    fun lockAccept() {
        acceptLockedUntil = player.world.currentCycle + changeLockTicks
    }

    /** A stake changed: revoke-notice to both sides (if an accept stood) + lockout on both. */
    private fun onStakeMutated(hadAccept: Boolean) {
        if (stake == null) return
        lockAccept()
        partner.getTradeSession()?.lockAccept()
        if (hadAccept) {
            player.message("An option or stake has changed - check before accepting!")
            partner.message("An option or stake has changed - check before accepting!")
        }
    }

    /**
     * An extension function for retrieving the value of each item in an [ItemContainer]]
     */
    private fun ItemContainer.getItemValues(): Array<Int> =
        rawItems.map {
            if (it == null) 0 else ((priceService?.get(it.id) ?: it.getDef().cost ?: 0).toLong() * it.amount)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }.toTypedArray()

    /**
     * An extension function for retrieving the sum of each item's value in an [ItemContainer].
     * Accumulates as Long: a full inventory of mid-value stacks overflowed Int and showed a
     * negative "Value: ... coins".
     */
    private fun ItemContainer.getValue(): Long =
        rawItems.sumOf { if (it == null) 0L else (priceService?.get(it.id) ?: it.getDef().cost ?: 0).toLong() * it.amount }

    /**
     * Opens the trade session, and configures the interfaces
     */
    fun open() {
        // Ensure the player isn't still marked as having accepted the trade
        player.attr[TRADE_ACCEPTED_ATTR] = false

        // Reset the trade modified varbit
        player.setVarbit(PLAYER_TRADE_MODIFIED_VARBIT, 0)
        player.setVarbit(PARTNER_TRADE_MODIFIED_VARBIT, 0)

        // Configure the trade text
        player.setComponentText(TRADE_INTERFACE, 31, "${if (isStake) "Staking with" else "Trading with"}: ${partner.username}")

        // Open the inventory overlay. Dispatch is by op INDEX (TradingPlugin), so only the
        // player-facing verb changes between a trade and a duel stake.
        val verb = if (isStake) "Stake" else "Offer"
        player.sendItemContainer(key = PLAYER_INVENTORY_KEY, container = inventory)
        player.runClientScript(
            INTERFACE_INV_INIT_BIG,
            OVERLAY_INTERFACE.getInterfaceHash(),
            PLAYER_INVENTORY_KEY,
            4,
            7,
            0,
            -1,
            verb,
            "$verb-5",
            "$verb-10",
            "$verb-All",
            "$verb-X",
        )
        player.setInterfaceEvents(interfaceId = OVERLAY_INTERFACE, component = 0, range = 0..container.capacity, setting = 1086)
        player.openInterface(OVERLAY_INTERFACE, InterfaceDestination.TAB_AREA)

        // Open the trade screen interface
        player.openInterface(TRADE_INTERFACE, InterfaceDestination.MAIN_SCREEN)

        // Initialise the trade containers
        initTradeContainers()
    }

    /**
     * Refreshes the item containers for both players
     */
    private fun refresh() {
        // Send the item containers
        player.sendItemContainer(PLAYER_INVENTORY_KEY, inventory)
        player.sendItemContainer(PLAYER_CONTAINER_KEY, container)

        // Send this player's container data to their partner
        partner.sendItemContainerOther(PLAYER_CONTAINER_KEY, container)
        partner.setComponentText(TRADE_INTERFACE, 9, "${player.username} has ${inventory.freeSlotCount} free inventory slots.")

        // Send the tooltip values
        val values = container.getItemValues()
        player.runClientScript(UPDATE_PLAYER_ITEM_PRICE_SCRIPT, *values)
        partner.runClientScript(UPDATE_PARTNER_ITEM_PRICE_SCRIPT, *values)

        // Calculate the trade value (Long — a full inventory of stacks overflows Int)
        val containerValue = container.getValue()
        val partnerValue = partner.getTradeSession()?.container?.getValue() ?: 0L

        // The prefix of each line
        val playerPrefix = if (partnerValue > containerValue) "<col=FF0000>" else ""
        val partnerPrefix = if (containerValue > partnerValue) "<col=FF0000>" else ""

        // The value text displayed on the partner's side
        val valueText = "%s%s offers:<br>%s(Value: <col=FFFFFF>%s</col>%s coins)"

        // Set the value text
        player.setComponentText(TRADE_INTERFACE, 24, "Your offer:<br>(Value: <col=FFFFFF>${containerValue.decimalFormat()}</col> coins)")
        player.setComponentText(
            TRADE_INTERFACE,
            27,
            valueText.format(partnerPrefix, partner.username, partnerPrefix, partnerValue.decimalFormat(), partnerPrefix),
        )
        partner.setComponentText(
            TRADE_INTERFACE,
            27,
            valueText.format(playerPrefix, player.username, playerPrefix, containerValue.decimalFormat(), playerPrefix),
        )
    }

    /**
     * Initialises the trade containers and enables the item container components for the player
     */
    private fun initTradeContainers() {
        player.setInterfaceEvents(
            interfaceId = TRADE_INTERFACE,
            component = PLAYER_TRADE_HASH,
            range = 0..container.capacity,
            setting = 1086,
        )
        player.setInterfaceEvents(
            interfaceId = TRADE_INTERFACE,
            component = PARTNER_TRADE_HASH,
            range = 0..container.capacity,
            setting = 1024,
        )

        refresh()
    }

    /**
     * Declines the trade session for both players.
     *
     * Ends BOTH sides in one call, so whichever side triggers it (Decline button, window close,
     * walk-away, logout, death, a space refusal) the pair is cleaned up together. Idempotent: a
     * session that has already ended (the partner's decline ran first) is a no-op, and the
     * partner's session is only torn down when it is the other half of THIS trade.
     */
    fun decline(forced: Boolean = false) {
        // Already ended (the other half's decline reached us first, or the trade completed).
        if (player.getTradeSession() !== this) return
        val partnerSession = partner.getTradeSession()?.takeIf { it.partner === player }

        // Lower the duel confirmation overlay for both sides (no-op when it never rose).
        if (stake != null) {
            stakeConfirm?.invoke(player, false)
            stakeConfirm?.invoke(partner, false)
        }
        // Remove the trade sessions from both players FIRST: closing the screens below fires the
        // interface-close hooks, which must find no session (otherwise they'd re-enter here).
        player.removeTradeSession()
        if (partnerSession != null) partner.removeTradeSession()

        // Inform the player that they've declined, and close the trade window (duel-worded
        // for a stake session — the classic decline lines).
        if (!forced) player.message(if (isStake) "You decline the duel." else "You declined the trade")
        player.closeInterface(InterfaceDestination.MAIN_SCREEN)
        player.closeInterface(OVERLAY_INTERFACE)

        // Inform the partner that the player has declined, and close their window
        if (partnerSession != null) {
            if (!forced) partner.message(if (isStake) "Other player declined the duel." else TRADE_DECLINED_MESSAGE)
            partner.closeInterface(InterfaceDestination.MAIN_SCREEN)
            partner.closeInterface(OVERLAY_INTERFACE)
        }

        // Re-sync the real backpacks. The trade screen showed each side a TEMP copy of their
        // inventory (with the offered items taken out) on container 93 — the real inventory's
        // own id — and nothing else re-sends the real one after a decline, so the client kept
        // showing the offered items as gone until a relog. Dirtying pushes the real containers
        // on the next cycle flush.
        resyncInventories()
    }

    /** Flag both real inventories dirty so the cycle flush overwrites the trade's temp view on the client. */
    private fun resyncInventories() {
        player.inventory.dirty = true
        partner.inventory.dirty = true
    }

    /**
     * Whether this player's backpack, as it will be once their own offer is committed out of it
     * ([inventory], the temp copy), can take every item in the partner's offer. Stackable items
     * (noted ones included) the backpack already holds need no slot — the Kronos/OSRS rule; the
     * old whole-slot count refused e.g. a coin trade into a full backpack that already had coins.
     * A stack that would overflow Int.MAX_VALUE does not fit either: the commit's add() would
     * fail and the item would silently vanish.
     */
    private fun partnerOfferFits(): Boolean {
        val offer = partner.getTradeSession()?.container ?: return true
        var slotsNeeded = 0
        for (item in offer) {
            if (item == null) continue
            val held = inventory.getItemCount(item.id)
            if (item.getDef().stackable && held > 0) {
                if (held.toLong() + item.amount > Int.MAX_VALUE) return false
            } else {
                slotsNeeded++
            }
        }
        return slotsNeeded <= inventory.freeSlotCount
    }

    /** Both sides' "no space" lines, then a forced decline. [short] is the side that can't take the offer. */
    private fun declineForSpace(short: Player) {
        val other = if (short === player) partner else player
        short.message("You don't have enough inventory space for this trade.")
        other.message("Other player doesn't have enough inventory space for this trade.")
        decline(forced = true)
    }

    /**
     * Offers an item to this [Player]'s trade [ItemContainer]
     *
     * @param slot      The slot in the temporary inventory
     * @param amount    The amount to offer in trade
     */
    fun offer(
        slot: Int,
        amount: Int,
    ) {
        if (stage != TradeStage.TRADE_SCREEN) return

        val item = inventory[slot] ?: return
        // Quest-locked items can't be offered (trade OR duel stake) — see [WarPrepChain.bonesLocked].
        if (WarPrepChain.bonesLocked(player, item.id)) {
            WarPrepChain.warnBonesLocked(player)
            return
        }
        val count = Math.min(amount, inventory.getItemCount(item.id))

        val hadAccept = player.hasAcceptedTrade() || partner.hasAcceptedTrade()
        val transaction = inventory.remove(item.id, count, assureFullRemoval = true, beginSlot = slot)
        if (transaction.hasSucceeded()) {
            container.add(item.id, count)
            onStakeMutated(hadAccept)
        }

        refresh()
        progress(false)
    }

    /**
     * Removes an item from this [Player]'s trade [ItemContainer]
     *
     * @param slot      The slot in the trade container
     * @param amount    The amount to remove from the trade container
     */
    fun remove(
        slot: Int,
        amount: Int,
    ) {
        if (stage != TradeStage.TRADE_SCREEN) return

        val item = container[slot] ?: return
        val count = Math.min(amount, container.getItemCount(item.id))

        val hadAccept = player.hasAcceptedTrade() || partner.hasAcceptedTrade()
        val transaction = container.remove(item.id, count, assureFullRemoval = true)
        if (transaction.hasSucceeded()) {
            inventory.add(item.id, count)
            container.shift()
            onStakeMutated(hadAccept)

            player.setVarbit(PLAYER_TRADE_MODIFIED_VARBIT, 1)
            partner.setVarbit(PARTNER_TRADE_MODIFIED_VARBIT, 1)

            // Loop over the remove items
            transaction.items.forEach {
                player.runClientScript(TRADE_MODIFIED_SCRIPT, 0, it.slot)
                partner.runClientScript(TRADE_MODIFIED_SCRIPT, 1, it.slot)
            }
        }

        refresh()
        progress(false)
    }

    /**
     * Progresses this [TradeSession] instance. If both players accept the trade, it will either
     * progress to the accept screen, or complete the trade and give each player the traded items.
     *
     * @param accepted  If the player accepted this trade session
     */
    fun progress(accepted: Boolean = true) {
        // Stake-mode accept lockout: within ~3 s of any change (or of the confirm screen opening)
        // an Accept is refused outright — the change must be seen before it can be agreed to.
        if (accepted && stake != null && player.world.currentCycle < acceptLockedUntil) {
            player.message("An option or stake has changed - check before accepting!")
            return
        }
        // Space is verified when the player ACCEPTS the first screen (the Kronos/OSRS rule): an
        // accept that can't be honoured is refused with a message and the trade stays open, so
        // the pair can free space or trim the offer instead of the whole trade being thrown out.
        // Any later change to either offer clears both accepts, so this re-runs on re-accept.
        if (accepted && stage == TradeStage.TRADE_SCREEN && !partnerOfferFits()) {
            player.message("You don't have enough inventory space to accept this trade.")
            partner.message("Other player doesn't have enough inventory space to accept this trade.")
            return
        }
        player.attr[TRADE_ACCEPTED_ATTR] = accepted

        // If the current trade session is on the trade screen
        if (stage == TradeStage.TRADE_SCREEN) {
            // If the player revoked their acceptation of the trade offer
            if (!player.hasAcceptedTrade()) {
                // Set the partner's option to revoked also
                partner.attr[TRADE_ACCEPTED_ATTR] = false

                // Reset the component text
                player.setComponentText(TRADE_INTERFACE, 30, "")
                partner.setComponentText(TRADE_INTERFACE, 30, "")
                return
            }

            // If the other player has not accepted, send the confirmation text
            if (player.hasAcceptedTrade() && !partner.hasAcceptedTrade()) {
                player.setComponentText(TRADE_INTERFACE, 30, "Waiting for other player...")
                partner.setComponentText(TRADE_INTERFACE, 30, "Other player has accepted.")
            } else if (player.hasAcceptedTrade() && partner.hasAcceptedTrade()) {
                // Open the accept screen
                openAcceptScreen()
                partner.getTradeSession()?.openAcceptScreen()
            }
        }

        // If the current trade session is on the progress screen
        if (stage == TradeStage.ACCEPT_SCREEN) {
            // If the player revoked their acceptation of the trade offer
            if (!player.hasAcceptedTrade()) {
                // Set the partner's option to revoked also
                partner.attr[TRADE_ACCEPTED_ATTR] = false

                // Reset the component text
                player.setComponentText(ACCEPT_INTERFACE, 4, "Are you sure you want to make this trade?")
                partner.setComponentText(ACCEPT_INTERFACE, 4, "Are you sure you want to make this trade?")
                return
            }

            // If the other player has not accepted, send the confirmation text
            if (player.hasAcceptedTrade() && !partner.hasAcceptedTrade()) {
                player.setComponentText(ACCEPT_INTERFACE, 4, "Waiting for other player...")
                partner.setComponentText(ACCEPT_INTERFACE, 4, "Other player has accepted.")
            } else if (player.hasAcceptedTrade() && partner.hasAcceptedTrade()) {
                // Complete the trade
                complete()
            }
        }
    }

    /**
     * Opens the accept screen for each player
     */
    private fun openAcceptScreen() {
        // Defensive re-check of what progress() verified at accept time (nothing can change the
        // offers between the two, but a stale accept must never reach complete()). Reads the TEMP
        // container (`inventory`, what complete() commits) — the real player.inventory still shows
        // the offered items as occupying slots, so it would under-count free space.
        if (!partnerOfferFits()) {
            declineForSpace(player)
            return
        }

        // Stake-mode veto (e.g. the duel's rules-strip space check) — [inventory] is the
        // player's backpack as it will be once the staked items are committed out of it.
        stakeVet?.invoke(player, inventory.freeSlotCount)?.let { refusal ->
            player.message(refusal)
            partner.message("The duel can't proceed — the other player's gear doesn't fit their backpack.")
            decline(forced = true)
            return
        }

        // Set the trade stage
        stage = TradeStage.ACCEPT_SCREEN

        // Landing on the confirm screen re-arms the lockout (fresh screen, fresh look before an
        // accept can land) and signals the duel side to raise the themed confirmation overlay.
        if (stake != null) {
            lockAccept()
            stakeConfirm?.invoke(player, true)
        }

        // Send the default component text values
        player.setComponentText(ACCEPT_INTERFACE, 4, if (isStake) "Are you sure you want to stake these items?" else "Are you sure you want to make this trade?")
        player.setComponentText(ACCEPT_INTERFACE, 30, "${if (isStake) "Staking with" else "Trading with"}:<br>${partner.username}")
        player.setComponentText(ACCEPT_INTERFACE, 23, "${if (isStake) "You are about to stake" else "You are about to give"}:<br>(Value: <col=FFFFFF>${container.getValue()}</col> coins)")
        partner.setComponentText(
            ACCEPT_INTERFACE,
            24,
            "${if (isStake) "Your opponent stakes" else "In return you will receive"}:<br>(Value: <col=FFFFFF>${container.getValue()}</col> coins)",
        )

        // Send the item containers
        player.sendItemContainer(ACCEPT_CONTAINER_KEY, container)
        partner.getTradeSession()?.let { player.sendItemContainerOther(ACCEPT_CONTAINER_KEY, it.container) }

        // Reset the accept state: the confirm screen needs a fresh accept from both sides.
        player.attr[TRADE_ACCEPTED_ATTR] = false

        // Open the accept screen interface. Both screens live on MAIN_SCREEN, so this REPLACES the
        // trade screen (335) and the engine fires 335's interface-close hook mid-open — which is
        // why [stage] is moved to ACCEPT_SCREEN above, before this call: the hook only declines a
        // session still on the first screen. (Without that, every accepted trade declined itself
        // right here: one side got "Other player declined trade." and the other was left on a
        // confirm screen with no session behind it.)
        player.openInterface(ACCEPT_INTERFACE, InterfaceDestination.MAIN_SCREEN)
    }

    /**
     * Completes the trade session, which swaps the player's trade containers, and
     * sets their inventory to the temporary one operated on during the trade.
     */
    private fun complete() {
        if (stage != TradeStage.ACCEPT_SCREEN) return
        // Last line of defence before anything is committed: if either side can no longer take
        // the other's offer, nothing moves — a failed add() in the commit below would silently
        // drop the item that didn't fit.
        if (stake == null) {
            if (!partnerOfferFits()) {
                declineForSpace(player)
                return
            }
            val partnerSession = partner.getTradeSession()
            if (partnerSession != null && !partnerSession.partnerOfferFits()) {
                declineForSpace(partner)
                return
            }
        }
        stage = TradeStage.COMPLETED

        // STAKE MODE (Duel Arena): the two stakes are NOT swapped between the players. Each player's
        // real inventory is committed MINUS the items they staked (which sit in `container`); those
        // staked items are captured and handed to the [stake] hook as escrow, which launches the duel.
        // The winner receives both escrows there.
        if (stake != null) {
            val partnerSession = partner.getTradeSession()
            // Commit the temp inventories (staked items already moved out of them into `container`).
            val playerInv = player.inventory
            inventory.forEachIndexed { index, item -> playerInv[index] = item }
            partnerSession?.let { ps ->
                val partnerInv = partner.inventory
                ps.inventory.forEachIndexed { index, item -> partnerInv[index] = item }
            }
            // Capture both escrows BEFORE finalise clears the containers.
            val playerStake = container.filterNotNull().map { it as Item }
            val partnerStake = partnerSession?.container?.filterNotNull()?.map { it as Item } ?: emptyList()
            finaliseStake(player)
            finaliseStake(partner)
            stake.onStaked(player, playerStake, partner, partnerStake)
            return
        }

        // Assign the trade containers for this player
        val playerInv = player.inventory
        inventory.forEachIndexed { index, item -> playerInv[index] = item }
        partner.getTradeSession()?.container?.filterNotNull()?.forEach { playerInv.add(it) }

        // Assign the trade containers for the partner
        val partnerInv = partner.inventory
        partner.getTradeSession()?.inventory?.forEachIndexed { index, item -> partnerInv[index] = item }
        container.filterNotNull().forEach { partnerInv.add(it) }

        // Finalise the trade session
        finalise(player)
        finalise(partner)
    }

    /**
     * Finalises the trade session by clearing the item containers, removing
     * the session attribute, and closing the trade screen interface
     *
     * @param player    The player to finalise the trade session for
     */
    private fun finalise(player: Player) {
        // Clear the containers
        container.removeAll()
        inventory.removeAll()

        // Remove the trade session
        player.removeTradeSession()

        // Close the trade interface
        player.closeInterface(InterfaceDestination.MAIN_SCREEN)
        player.closeInterface(OVERLAY_INTERFACE)

        // The commit already dirtied the real inventory; make it explicit so the client's
        // container 93 (which showed the trade's temp copy) is guaranteed to be refreshed.
        player.inventory.dirty = true

        // Inform the player that the trade has been accepted
        player.message("Accepted trade.")
    }

    /**
     * Close down a STAKE session's UI for [target] and drop its session, WITHOUT restoring any items —
     * the staked items have already been captured as escrow by [complete]. (The plain [finalise] would
     * work too, but this keeps the stake path explicit and its message duel-appropriate.)
     */
    private fun finaliseStake(target: Player) {
        stakeConfirm?.invoke(target, false)
        target.getTradeSession()?.let { s ->
            s.container.removeAll()
            s.inventory.removeAll()
        }
        target.removeTradeSession()
        target.closeInterface(InterfaceDestination.MAIN_SCREEN)
        target.closeInterface(OVERLAY_INTERFACE)
        target.message("Stake locked in.")
    }

    companion object {
        /**
         * The inventory overlay interface
         */
        const val OVERLAY_INTERFACE = 336

        /**
         * The primary trade screen interface
         */
        const val TRADE_INTERFACE = 335

        /**
         * The child id of this player's trade offer
         */
        const val PLAYER_TRADE_CHILD = 25

        /**
         * The child id of the partner's trade offer
         */
        private const val PARTNER_TRADE_CHILD = 28

        /**
         * The script id used to update the item price of each item in the container
         */
        private val UPDATE_PLAYER_ITEM_PRICE_SCRIPT = ClientScript(id = 1216)

        /**
         * The script id used to update the item price of each item in the partner's container
         */
        private val UPDATE_PARTNER_ITEM_PRICE_SCRIPT = ClientScript(id = 1217)

        /**
         * The hash of this player's trade offer component
         */
        val PLAYER_TRADE_HASH = TRADE_INTERFACE.getInterfaceHash(PLAYER_TRADE_CHILD)

        /**
         * The hash of the partner's trade offer component
         */
        val PARTNER_TRADE_HASH = TRADE_INTERFACE.getInterfaceHash(PARTNER_TRADE_CHILD)

        /**
         * The progress trade interface
         */
        const val ACCEPT_INTERFACE = 334

        /**
         * The message that is shown to the partner when a player declined a trade
         */
        const val TRADE_DECLINED_MESSAGE = "Other player declined trade."

        /**
         * The container key for the trade accept screen
         */
        const val ACCEPT_CONTAINER_KEY = 90

        /**
         * The container key for the player's inventory overlay
         */
        const val PLAYER_INVENTORY_KEY = 93

        /**
         * The container key for this player's trade offer
         */
        const val PLAYER_CONTAINER_KEY = 90

        /**
         * The varbit that handles the 'Trade modified' text for the player's trade container
         */
        const val PLAYER_TRADE_MODIFIED_VARBIT = 4374

        /**
         * The varbit that handles the 'Trade modified' text for the partner's trade container
         */
        const val PARTNER_TRADE_MODIFIED_VARBIT = 4375

        /**
         * The id of the ClientScript used to display the red exclamation marks when
         * an item has been removed from the trade container
         */
        val TRADE_MODIFIED_SCRIPT = ClientScript("trade_slot_changed")
    }
}

/**
 * Callback fired when a **stake** (Duel Arena) session completes: both players have locked in their
 * stakes on the confirm screen. Keeps the trading module decoupled from the duel module — the duel
 * plugin supplies the implementation (escrow the two stakes, teleport the pair in, start the fight).
 */
fun interface StakeHook {
    fun onStaked(player: Player, playerStake: List<Item>, partner: Player, partnerStake: List<Item>)
}
