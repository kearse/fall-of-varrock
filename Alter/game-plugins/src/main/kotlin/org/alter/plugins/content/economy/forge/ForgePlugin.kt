package org.alter.plugins.content.economy.forge

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **The Forge** (economy roadmap Phase 3) — the marquee gp + materials sink and upgrade
 * chase. At the Forge (an experimental anvil south of the Lumbridge home) you combine
 * **rune gear + runite bars + a big gp fee → dragon gear**, draining gp from the economy
 * and giving Smithing's runite bars a high-end use. Use a rune piece on the Forge.
 *
 * (A deluxe "(i)" imbue with set effects + boss-material inputs needs custom cache items
 * and Phase-4 bosses; the recipe model here extends to those when they land.)
 */
class ForgePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val forge = "object.an_experimental_anvil"
    private val forgeTile = Tile(3242, 3196, 0) // relocated into the Mire working yard (graveyard hub, smithing corner)
    private val coins = "item.coins_995"
    private val bar = "item.runite_bar"

    private data class Upgrade(val base: String, val result: String, val name: String, val bars: Int, val gp: Int, val level: Int)

    private val upgrades = listOf(
        Upgrade("item.rune_scimitar", "item.dragon_scimitar", "dragon scimitar", 30, 500_000, 60),
        Upgrade("item.rune_med_helm", "item.dragon_med_helm", "dragon med helm", 30, 400_000, 60),
        Upgrade("item.rune_full_helm", "item.dragon_full_helm", "dragon full helm", 50, 800_000, 65),
        Upgrade("item.rune_kiteshield", "item.dragon_kiteshield", "dragon kiteshield", 70, 1_500_000, 70),
        Upgrade("item.rune_platelegs", "item.dragon_platelegs", "dragon platelegs", 80, 1_500_000, 70),
        Upgrade("item.rune_platebody", "item.dragon_platebody", "dragon platebody", 100, 2_500_000, 75),
    ).filter { res(it.base) && res(it.result) }

    init {
        if (res(forge) && res(bar) && upgrades.isNotEmpty()) {
            // Forge relocated to the Mire working yard (graveyard hub), smithing corner. Spawn in
            // onWorldInit so region 12849's collision is loaded first. Tile verified clear of walls/
            // water via the mapDump region scan.
            onWorldInit { world.spawn(DynamicObject(getRSCM(forge), OBJ_TYPE, 0, forgeTile)) }
            upgrades.forEach { u ->
                onItemOnObj(obj = forge, item = u.base) { forge(player, u) }
            }
        } else {
            logger.warn { "forge: '$forge' or '$bar' missing in cache; Forge disabled." }
        }
    }

    private fun forge(player: Player, u: Upgrade) {
        if (!player.inventory.contains(getRSCM("item.hammer"))) {
            player.message("You need a hammer to work the Forge.")
            return
        }
        if (player.getSkills().getCurrentLevel(Skills.SMITHING) < u.level) {
            player.message("You need a Smithing level of ${u.level} to forge a ${u.name}.")
            return
        }
        val barId = getRSCM(bar)
        val coinId = getRSCM(coins)
        if (player.inventory.getItemCount(barId) < u.bars) {
            player.message("You need ${u.bars} runite bars to forge a ${u.name}.")
            return
        }
        if (player.inventory.getItemCount(coinId) < u.gp) {
            player.message("You need ${"%,d".format(u.gp)} coins to forge a ${u.name}.")
            return
        }
        // Consume the base piece, the bars, and the gp fee; mint the upgraded item.
        if (player.inventory.remove(item = getRSCM(u.base), amount = 1).completed == 0) return
        if (player.inventory.remove(item = barId, amount = u.bars).completed < u.bars) {
            player.inventory.add(item = getRSCM(u.base), amount = 1) // refund base
            return
        }
        if (player.inventory.remove(item = coinId, amount = u.gp).completed < u.gp) {
            player.inventory.add(item = getRSCM(u.base), amount = 1)
            player.inventory.add(item = barId, amount = u.bars)
            return
        }
        player.inventory.add(item = getRSCM(u.result), amount = 1)
        player.addXp(Skills.SMITHING, u.bars * FORGE_XP_PER_BAR)
        player.animate(FORGE_ANIM)
        player.message("<col=801700>The Forge roars — you forge a ${u.name}!</col>")
    }

    private fun res(key: String): Boolean = try { getRSCM(key); true } catch (e: Exception) { false }

    private companion object {
        const val OBJ_TYPE = 10
        const val FORGE_ANIM = 898
        const val FORGE_XP_PER_BAR = 50.0
    }
}
