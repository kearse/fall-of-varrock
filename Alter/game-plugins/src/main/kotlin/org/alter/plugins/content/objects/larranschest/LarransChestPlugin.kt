package org.alter.plugins.content.objects.larranschest

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Larran's chests** — the sink for Larran's key (player report 2026-09-13: "larrans key has no
 * chest").
 *
 * The key (item 23490, examine "Opens Larran's chests in the Wilderness.") is one of the most
 * widely dropped items in the game — the wilderness-slayer dump puts it on **322 npc rows** — and
 * it had nowhere to go: nothing in the plugin tree referenced the key or either chest object, so
 * every key ever dropped was dead weight in someone's bank.
 *
 * Two chests, both real cache objects:
 *  - **Small** (33343, plus its 34828 twin) — the mid-Wilderness chest.
 *  - **Big** (34829, plus its 34830 twin) — the deep-Wilderness chest, a distinctly better table.
 *
 * Bindings are by object **id**, so any Larran's chest the rev-228 map already carries at its own
 * OSRS location works the moment this loads. On top of that the plugin spawns one of each on the
 * server's own PK corridor ([SMALL_TILE] / [BIG_TILE], beside the Raider Fields and Deep
 * Wilderness teleport landings) so the content is reachable through the portal rows players
 * actually use rather than only at coordinates nobody here teleports to. **TUNE the two stand-on
 * tiles in-game** — the same caveat the teleport registry carries for its landings.
 *
 * Opening costs one key and pays the matching table straight to the inventory (overflow drops
 * killer-owned at the player's feet, the `Barrows.give` pattern). The chest is NOT consumed: it is
 * scenery, and the key is the limiter.
 */
class LarransChestPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onWorldInit {
            spawnChest(SMALL_KEYS.first(), SMALL_TILE)
            spawnChest(BIG_KEYS.first(), BIG_TILE)
        }
        SMALL_KEYS.forEach { bindChest(it, SMALL_TABLE, "small") }
        BIG_KEYS.forEach { bindChest(it, BIG_TABLE, "big") }
    }

    /** Bind every plausible open verb on [objKey]; log once if the cache object carries none. */
    private fun bindChest(objKey: String, table: DropTable, label: String) {
        if (runCatching { getRSCM(objKey) }.isFailure) return
        val bound = OPEN_VERBS.any { verb ->
            runCatching { onObjOption(obj = objKey, option = verb) { open(player, table) } }.isSuccess
        }
        if (!bound) logger.warn { "larrans-chest: $label chest '$objKey' has no open verb in the cache." }
    }

    private fun open(p: Player, table: DropTable) {
        val key = runCatching { getRSCM(KEY) }.getOrNull() ?: return
        if (p.inventory.remove(item = key, amount = 1).completed == 0) {
            p.message("You need a <col=801700>Larran's key</col> to open this chest.")
            return
        }
        // Take the key first, then pay — a full inventory must never eat the key for nothing, so
        // the freed slot from the key itself guarantees at least one item can land.
        p.message("You unlock the chest with your key.")
        table.roll(world).forEach { drop -> give(p, drop.item, drop.amount) }
    }

    private fun give(p: Player, key: String, amount: Int) {
        if (amount <= 0) return
        val id = runCatching { getRSCM(key) }.getOrNull() ?: run {
            logger.warn { "larrans-chest: unknown item key $key" }; return
        }
        val added = p.inventory.add(item = id, amount = amount, assureFullInsertion = false)
        val leftover = amount - added.completed
        if (leftover > 0) world.spawn(GroundItem(id, leftover, p.tile, p))
    }

    private fun spawnChest(objKey: String, tile: Tile) {
        val id = runCatching { getRSCM(objKey) }.getOrNull() ?: run {
            logger.warn { "larrans-chest: object '$objKey' not in cache; not spawned." }; return
        }
        world.spawn(DynamicObject(id = id, type = OBJ_TYPE, rot = 0, tile = tile))
        logger.info { "larrans-chest: spawned '$objKey' at (${tile.x},${tile.z})." }
    }

    private companion object {
        const val KEY = "item.larrans_key"
        const val OBJ_TYPE = 10

        val SMALL_KEYS = listOf("object.larrans_small_chest", "object.larrans_small_chest_34828")
        val BIG_KEYS = listOf("object.larrans_big_chest", "object.larrans_big_chest_34830")

        /** Verbs an OSRS chest might carry, best first. */
        val OPEN_VERBS = listOf("Open", "Search", "Unlock", "Loot")

        /** Beside the Raider Fields landing (3232,3423 — wilderness 20). TUNE in-game. */
        val SMALL_TILE = Tile(3234, 3423, 0)

        /** Beside the Deep Wilderness PKers landing (3170,3700 — wilderness 55). TUNE in-game. */
        val BIG_TILE = Tile(3172, 3700, 0)

        /**
         * **Small chest** — the mid-Wilderness table: a supply drop with a real but modest chance
         * at something worth the trip. Priced as a consumable: a key is a common drop, so the table
         * pays like a good slayer kill, not like a boss.
         */
        val SMALL_TABLE = DropTable(
            always = listOf(DropEntry("item.coins_995", 2_000, 8_000)),
            main = listOf(
                DropEntry("item.death_rune", 30, 80, weight = 12),
                DropEntry("item.blood_rune", 20, 60, weight = 10),
                DropEntry("item.chaos_rune", 60, 150, weight = 10),
                DropEntry("item.law_rune", 30, 80, weight = 8),
                DropEntry("item.shark", 3, 8, weight = 12),
                DropEntry("item.prayer_potion4", 1, 2, weight = 10),
                DropEntry("item.super_restore4", 1, 2, weight = 8),
                DropEntry("item.grimy_ranarr_weed", 2, 5, weight = 8),
                DropEntry("item.grimy_snapdragon", 1, 3, weight = 5),
                DropEntry("item.coal_noted", 50, 150, weight = 8),
                DropEntry("item.adamantite_bar_noted", 5, 15, weight = 6),
                DropEntry("item.runite_bar_noted", 1, 4, weight = 4),
                DropEntry("item.uncut_diamond", 2, 6, weight = 6),
                DropEntry("item.uncut_dragonstone", 1, 2, weight = 3),
                DropEntry("item.dragon_arrow", 25, 75, weight = 5),
                DropEntry("item.pure_essence_noted", 200, 500, weight = 8),
            ),
            rare = listOf(
                DropEntry("item.dragon_dagger", oneInN = 40),
                DropEntry("item.dragon_scimitar", oneInN = 80),
                DropEntry("item.amulet_of_glory", oneInN = 50),
                DropEntry("item.dragon_med_helm", oneInN = 120),
                DropEntry("item.rune_crossbow", oneInN = 100),
            ),
        )

        /**
         * **Big chest** — the deep-Wilderness table. Same shape, roughly triple the supply payout,
         * and the uniques are the wilderness chase items rather than mid-tier gear. Deliberately
         * kept under the PK-set pools ([org.alter.plugins.content.bots.PkLootPools]) so a chest is
         * a bonus on a trip north, never a replacement for fighting anything.
         */
        val BIG_TABLE = DropTable(
            always = listOf(
                DropEntry("item.coins_995", 15_000, 40_000),
                DropEntry("item.blood_money", 250, 750),
            ),
            main = listOf(
                DropEntry("item.death_rune", 150, 400, weight = 12),
                DropEntry("item.blood_rune", 150, 400, weight = 12),
                DropEntry("item.saradomin_brew4", 3, 8, weight = 10),
                DropEntry("item.super_restore4", 3, 8, weight = 10),
                DropEntry("item.grimy_snapdragon_noted", 10, 25, weight = 8),
                DropEntry("item.grimy_torstol_noted", 8, 20, weight = 6),
                DropEntry("item.runite_bar_noted", 10, 25, weight = 8),
                DropEntry("item.uncut_dragonstone_noted", 5, 12, weight = 6),
                DropEntry("item.dragon_arrow", 150, 400, weight = 8),
                DropEntry("item.dragon_javelin", 150, 400, weight = 6),
                DropEntry("item.onyx_bolt_tips", 20, 50, weight = 5),
                DropEntry("item.pure_essence_noted", 1_000, 2_500, weight = 8),
            ),
            rare = listOf(
                DropEntry("item.amulet_of_avarice", oneInN = 60),
                DropEntry("item.dragon_boots", oneInN = 50),
                DropEntry("item.abyssal_whip", oneInN = 90, announce = true),
                DropEntry("item.craws_bow", oneInN = 200, announce = true),
                DropEntry("item.viggoras_chainmace", oneInN = 200, announce = true),
                DropEntry("item.thammarons_sceptre", oneInN = 200, announce = true),
                DropEntry("item.dragon_pickaxe", oneInN = 150, announce = true),
            ),
        )
    }
}
