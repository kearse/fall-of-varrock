package org.alter.plugins.content.items.amuletofglory

import org.alter.api.*
import org.alter.api.cfg.*
import org.alter.api.dsl.*
import org.alter.api.ext.*
import org.alter.game.*
import org.alter.game.model.*
import org.alter.game.model.attr.*
import org.alter.game.model.container.*
import org.alter.game.model.container.key.*
import org.alter.game.model.entity.*
import org.alter.game.model.item.*
import org.alter.game.model.queue.*
import org.alter.game.model.shop.*
import org.alter.game.model.timer.*
import org.alter.game.plugin.*
import org.alter.plugins.content.magic.TeleportType
import org.alter.plugins.content.magic.canTeleport
import org.alter.plugins.content.magic.teleport
import org.alter.rscm.RSCM.getRSCM

/**
 * **Amulet of Glory** — equipment-option teleports with charge consumption, rewritten
 * in the current RSCM idiom (mirrors [org.alter.plugins.content.items.ringofwealth.RingOfWealthPlugin]).
 * Each teleport drops the amulet one charge tier (glory(6)..glory(1) -> uncharged glory).
 * Also supports the inventory "Rub" option to pick a destination from a menu.
 */
class AmuletOfGloryPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    private val GLORY =
        arrayOf(
            "item.amulet_of_glory1",
            "item.amulet_of_glory2",
            "item.amulet_of_glory3",
            "item.amulet_of_glory4",
            "item.amulet_of_glory5",
            "item.amulet_of_glory6",
        )

    private val SOUNDAREA_ID = 200
    private val SOUNDAREA_RADIUS = 5
    private val SOUNDAREA_VOLUME = 1

    private val LOCATIONS =
        mapOf(
            "Edgeville" to Tile(3087, 3496),
            "Karamja" to Tile(2918, 3176),
            "Draynor Village" to Tile(3105, 3251),
            "Al Kharid" to Tile(3293, 3163),
        )

    init {
        GLORY.forEach { glory ->
            LOCATIONS.forEach { location, tile ->
                onEquipmentOption(glory, option = location) {
                    player.queue(TaskPriority.STRONG) {
                        player.gloryTeleport(tile)
                    }
                }
            }
            // Inventory "Rub" -> destination menu. Guarded so a cache that lacks the "Rub"
            // verb on this item doesn't drop the whole plugin (the worn teleports still bind).
            try {
                onItemOption(glory, option = "Rub") {
                    player.queue(TaskPriority.STRONG) {
                        when (options(player, "Edgeville", "Karamja", "Draynor Village", "Al Kharid", title = "Where would you like to teleport to?")) {
                            1 -> player.gloryTeleport(LOCATIONS["Edgeville"]!!)
                            2 -> player.gloryTeleport(LOCATIONS["Karamja"]!!)
                            3 -> player.gloryTeleport(LOCATIONS["Draynor Village"]!!)
                            4 -> player.gloryTeleport(LOCATIONS["Al Kharid"]!!)
                        }
                    }
                }
            } catch (e: Exception) { /* this glory tier has no "Rub" inventory option in the cache */ }
        }
    }

    private fun Player.gloryTeleport(endTile: Tile) {
        if (canTeleport(TeleportType.GLORY) && hasEquipped(EquipmentType.AMULET, *GLORY)) {
            world.spawn(AreaSound(tile, SOUNDAREA_ID, SOUNDAREA_RADIUS, SOUNDAREA_VOLUME))
            equipment[EquipmentType.AMULET.id] = getGloryReplacement()
            message(getGloryChargeMessage())
            teleport(endTile, TeleportType.GLORY)
        }
    }

    private fun Player.getGloryReplacement(): Item? {
        return when {
            hasEquipped(EquipmentType.AMULET, "item.amulet_of_glory6") -> Item(getRSCM("item.amulet_of_glory5"))
            hasEquipped(EquipmentType.AMULET, "item.amulet_of_glory5") -> Item(getRSCM("item.amulet_of_glory4"))
            hasEquipped(EquipmentType.AMULET, "item.amulet_of_glory4") -> Item(getRSCM("item.amulet_of_glory3"))
            hasEquipped(EquipmentType.AMULET, "item.amulet_of_glory3") -> Item(getRSCM("item.amulet_of_glory2"))
            hasEquipped(EquipmentType.AMULET, "item.amulet_of_glory2") -> Item(getRSCM("item.amulet_of_glory1"))
            hasEquipped(EquipmentType.AMULET, "item.amulet_of_glory1") -> Item(getRSCM("item.amulet_of_glory"))
            else -> null
        }
    }

    private fun Player.getGloryChargeMessage(): String {
        return when {
            hasEquipped(EquipmentType.AMULET, "item.amulet_of_glory6") -> "<col=7f007f>Your amulet has five charges left.</col>"
            hasEquipped(EquipmentType.AMULET, "item.amulet_of_glory5") -> "<col=7f007f>Your amulet has four charges left.</col>"
            hasEquipped(EquipmentType.AMULET, "item.amulet_of_glory4") -> "<col=7f007f>Your amulet has three charges left.</col>"
            hasEquipped(EquipmentType.AMULET, "item.amulet_of_glory3") -> "<col=7f007f>Your amulet has two charges left.</col>"
            hasEquipped(EquipmentType.AMULET, "item.amulet_of_glory2") -> "<col=7f007f>Your amulet has one charge left.</col>"
            else -> "<col=7f007f>You use your amulet's last charge.</col>"
        }
    }
}
