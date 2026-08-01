package org.alter.plugins.content.commands.commands.admin

import dev.openrune.cache.CacheManager
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.container.ItemContainer
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.interfaces.bank.Bank
import org.alter.plugins.content.interfaces.bank.BankTabs

/**
 * `::bankdiag [name]` — one-shot diagnosis of a player's banking state.
 *
 * Built for "player X can't bank" reports: it surfaces every known way a bank can silently
 * break — corrupt container entries (non-positive stacks that render as empty slots, item ids
 * missing from the mounted cache), tab varbit counts that disagree with the container (the
 * bank-open wedge signature), and whether the bank/deposit-side interfaces are actually
 * registered as visible for the client.
 */
class BankDiagPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("bankdiag", Privilege.ADMIN_POWER, description = "Diagnose a player's bank/container state") {
            val args = player.getCommandArgs()
            val target =
                if (args.isEmpty()) {
                    player
                } else {
                    val name = args.joinToString(" ")
                    world.getPlayerForName(name) ?: run {
                        player.message("No online player named '$name'.")
                        return@onCommand
                    }
                }
            report(player, target)
        }
    }

    private fun report(admin: Player, target: Player) {
        val bank = target.bank
        val placeholders = bank.count { it?.amount == Item.PLACEHOLDER_AMOUNT }
        admin.message("<col=178000>Bank diag for ${target.username}:</col>")
        admin.message(
            "Bank: capacity=${bank.capacity} occupied=${bank.occupiedSlotCount} " +
                "free=${bank.freeSlotCount} placeholders=$placeholders",
        )

        val findings = mutableListOf<String>()
        collectCorrupt(findings, "bank", bank)
        collectCorrupt(findings, "inventory", target.inventory)
        collectCorrupt(findings, "equipment", target.equipment)
        if (findings.isEmpty()) {
            admin.message("Corrupt entries: <col=178000>none</col>")
        } else {
            admin.message("Corrupt entries: <col=801700>${findings.size}</col>")
            findings.take(MAX_FINDING_LINES).forEach { admin.message("  $it") }
            if (findings.size > MAX_FINDING_LINES) {
                admin.message("  ... +${findings.size - MAX_FINDING_LINES} more (see server log)")
            }
            findings.forEach { logger.error { "bankdiag ${target.username}: $it" } }
        }

        val tabSizes = (1..9).map { target.getVarbit(BankTabs.BANK_TAB_ROOT_VARBIT + it) }
        val tabSum = tabSizes.sum()
        val tabVerdict =
            if (tabSum <= bank.occupiedSlotCount) "<col=178000>OK</col>" else "<col=801700>MISMATCH (wedge risk)</col>"
        admin.message("Tabs: selected=${target.getVarbit(BankTabs.SELECTED_TAB_VARBIT)} sizes=$tabSizes")
        admin.message("Tab sum=$tabSum vs occupied=${bank.occupiedSlotCount}: $tabVerdict")

        admin.message(
            "Interfaces: bank(${Bank.BANK_INTERFACE_ID})=${target.interfaces.isVisible(Bank.BANK_INTERFACE_ID)} " +
                "depositInv(${Bank.INV_INTERFACE_ID})=${target.interfaces.isVisible(Bank.INV_INTERFACE_ID)} " +
                "displayMode=${target.interfaces.displayMode}",
        )
    }

    private fun collectCorrupt(
        findings: MutableList<String>,
        name: String,
        container: ItemContainer,
    ) {
        container.rawItems.forEachIndexed { slot, item ->
            if (item == null) {
                return@forEachIndexed
            }
            if (item.amount <= 0 && item.amount != Item.PLACEHOLDER_AMOUNT) {
                findings.add("$name slot=$slot id=${item.id} amount=${item.amount} (non-positive stack)")
            } else if (!CacheManager.itemExists(item.id)) {
                findings.add("$name slot=$slot id=${item.id} amount=${item.amount} (id not in cache)")
            }
        }
    }

    companion object {
        private const val MAX_FINDING_LINES = 10
        private val logger = KotlinLogging.logger {}
    }
}
