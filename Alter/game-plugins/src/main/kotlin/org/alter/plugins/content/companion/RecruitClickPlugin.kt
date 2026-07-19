package org.alter.plugins.content.companion

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.openShop
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.war.Sieges
import org.alter.plugins.content.war.Title
import org.alter.plugins.content.war.title
import org.alter.rscm.RSCM.getRSCM

/**
 * Handles actions from the Muster Companions overlay. The client sends
 * `::zo recruit <melee|range|mage>` as public chat; `MessagePublicHandler` routes it here as
 * the `zoclick` command. The transaction mirrors what General Zo's dialogue used to do:
 * rank-cap gate, coin price ([RecruitMenu.RECRUIT_COST]), refund if the muster fails.
 *
 * Also testable directly by typing `::zoclick recruit melee` in chat.
 */
class RecruitClickPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("zoclick", description = "Muster Companions window action (client overlay channel)") {
            // The token arrives from raw public chat anywhere — keep the General's storefront at
            // his courtyard post (radius allows for his movement as the war's attackable VIP).
            if (!player.tile.isWithinRadius(Sieges.LUMBRIDGE.zoTile, ZO_RADIUS)) {
                player.message("General Zo musters troops at his courtyard post in Lumbridge castle.")
                return@onCommand
            }
            val a = player.getCommandArgs()
            when (a.getOrNull(0)?.lowercase()) {
                "recruit" -> recruit(player, a.getOrNull(1))
                // The window's Regalia tab: the native points shop draws the real sanguine
                // torva sprites — no custom rendering needed.
                "regalia" -> player.openShop("Commander's Regalia")
            }
        }
    }

    private fun recruit(p: Player, styleName: String?) {
        val style = when (styleName?.lowercase()) {
            "melee" -> CompanionStyle.MELEE
            "range", "ranged" -> CompanionStyle.RANGE
            "mage", "magic" -> CompanionStyle.MAGE
            else -> return
        }
        val cap = CompanionRegistry.companionCap(p)
        if (cap == 0) {
            val firstRank = Title.values().first { it.companions > 0 }.display
            p.message("Only those of rank may lead soldiers — rise to $firstRank and the General will muster your first.")
            return
        }
        if (CompanionRegistry.count(p) >= cap) {
            p.message("Your banner is full — a ${p.title.display} may field $cap.")
            return
        }
        val coins = getRSCM("item.coins_995")
        if (p.inventory.getItemCount(coins) < RecruitMenu.RECRUIT_COST) {
            p.message("A trained soldier costs ${fmt(RecruitMenu.RECRUIT_COST)} coins — your purse is short.")
            return
        }
        p.inventory.remove(coins, RecruitMenu.RECRUIT_COST)
        val comp = CompanionRegistry.recruit(world, p, style)
        if (comp == null) {
            p.inventory.add(coins, RecruitMenu.RECRUIT_COST) // refund if the muster failed
            p.message("No soldier could be raised just now. Try again shortly.")
            return
        }
        p.message("<col=4f9b4f>A ${style.display} soldier joins your banner — Sir ${comp.username}. You field ${CompanionRegistry.count(p)} of $cap.</col>")
        RecruitMenu.refresh(p) // update the open window's banner strip in place
    }

    private fun fmt(n: Int): String = "%,d".format(n)

    private companion object {
        const val ZO_RADIUS = 15 // muster range around the General's courtyard post
    }
}
