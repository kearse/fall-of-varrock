package org.alter.plugins.content.war

import dev.openrune.cache.CacheManager
import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.action.EquipAction
import org.alter.game.model.World
import org.alter.game.model.attr.PLAYER_TITLE_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.economy.PointKind
import org.alter.rscm.RSCM.getRSCM

/**
 * Feudal rank: the `::title` lookup, the **armour-tier equip gate**, and admin test
 * commands. Ranks are raised by Duke Horacio ([DukeHoracioPlugin]) once [RankEligibility]
 * is met; this enforces what each rank may wear.
 */
class TitlePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        // The legacy quest chains' rank hooks subscribe to RankEvents here (order-free, once).
        LegacyRankHooks.install()

        // Stamp the color-coded noble name (Squire+) onto the appearance on login.
        onLogin { player.refreshTitledName() }

        // Block wearing armour above your rank: checked right after it equips, then
        // stripped back off with a message (no per-item cache registration needed).
        // Rank is an ADDITIONAL armour requirement — the classic skill-level reqs
        // (ItemMetadataService) are enforced first by EquipAction, then this gate.
        for (slot in RANK_GATED_ARMOUR_SLOTS) {
            onEquipToSlot(slot) {
                val item = player.equipment[slot] ?: return@onEquipToSlot
                val name = CacheManager.getItem(item.id).name
                val tier = armourTier(name) ?: return@onEquipToSlot
                if (!player.canWear(tier)) {
                    EquipAction.unequip(player, slot)
                    // Name the item, not the tier — the tier labels are metal words ("rune"),
                    // which read wrong for its ranged/magic equivalents (d'hide, mystic, ...).
                    player.message("You must be a ${Title.forTier(tier).display} to wear $name.")
                }
            }
        }

        // ::title — show rank, what you can wear, and the next rank's price.
        onCommand("title", description = "Show your rank, armour tier, and next rank cost") {
            val t = player.title
            player.message("Rank: <col=ffae00>${t.display}</col> — you may wear up to <col=ff9040>${t.maxTier.display}</col> armour. You must also meet the armour's usual level requirements.")
            if (t.companions > 0) {
                player.message("Your rank may field <col=ffae00>${t.companions}</col> soldier companion${if (t.companions > 1) "s" else ""} (General Zo).")
            }
            val next = player.nextTitle
            if (next != null) {
                val compPerk = if (next.companions > t.companions) " + ${next.companions} companion${if (next.companions > 1) "s" else ""}" else ""
                player.message("Next: <col=ffae00>${next.display}</col> — unlocks ${next.maxTier.display} armour$compPerk. See Duke Horacio in Lumbridge Castle.")
                val ready = RankEligibility.isEligible(player, next)
                player.message("Requires: ${RankEligibility.summary(player, next)} — ${if (ready) "<col=4f9b4f>you qualify</col>" else "<col=801700>not yet</col>"}.")
            } else {
                player.message("You hold the highest rank in the land.")
            }
            player.message("Rank gates what you may wear and which wars you may START; joining any war is open to every citizen.")
        }

        // ::settitle <name> — admin: jump to a rank for testing (grants the cape + fires the
        // rank-bought hooks for that rank, like a real purchase would).
        onCommand("settitle", Privilege.ADMIN_POWER, description = "Set your rank by name (test)") {
            val name = player.getCommandArgs().getOrNull(0)
            val target = name?.let { arg -> Title.values().firstOrNull { it.name.equals(arg, ignoreCase = true) } }
            if (target == null) {
                player.message("Usage: ::settitle <${Title.values().joinToString("|") { it.name.lowercase() }}>")
                return@onCommand
            }
            player.attr[PLAYER_TITLE_ATTR] = target.ordinal
            player.refreshTitledName()
            RankCapes.grant(player, target)
            RankEvents.fire(player, target)
            player.message("Rank set to ${target.display}.")
        }

        // ::setpoints <kind> <n> — admin: set a point counter (e.g. War Effort) to test eligibility.
        onCommand("setpoints", Privilege.ADMIN_POWER, description = "Set a point counter (test): ::setpoints <kind> <n>") {
            val a = player.getCommandArgs()
            val kind = a.getOrNull(0)?.let { k -> PointKind.values().firstOrNull { it.name.equals(k, ignoreCase = true) } }
            val n = a.getOrNull(1)?.toIntOrNull()
            if (kind == null || n == null || n < 0) {
                player.message("Usage: ::setpoints <${PointKind.values().joinToString("|") { it.name.lowercase() }}> <n>")
                return@onCommand
            }
            player.attr[kind.attr] = n
            player.message("${kind.display} set to ${coins(n)}.")
        }

        // ::givecoins [n] — admin: grant coins for testing the shop (default 100k).
        onCommand("givecoins", Privilege.ADMIN_POWER, description = "Give yourself coins (test)") {
            val amount = player.getCommandArgs().getOrNull(0)?.toIntOrNull() ?: 100_000
            player.inventory.add(getRSCM("item.coins_995"), amount)
            player.message("Added ${coins(amount)} coins.")
        }
    }

    private fun coins(n: Int): String = "%,d".format(n)
}
