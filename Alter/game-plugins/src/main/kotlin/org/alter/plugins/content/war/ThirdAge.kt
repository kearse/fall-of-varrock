package org.alter.plugins.content.war

import dev.openrune.cache.CacheManager.getItem
import org.alter.api.ext.message
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.plugins.content.announce.Announce
import org.alter.plugins.content.bosses.CollectionLog
import org.alter.rscm.RSCM.getRSCM

/**
 * **3rd age — the realm's antique prestige line.** No longer just a shop purchase: the way to pull a
 * *fresh* piece is the war. Two sources, both wired into the war system:
 *
 *  - **Battlefield drop** ([awardDrop]) — any enemy cut down on a LIVE campaign/conquest battlefield
 *    (i.e. Varrock, while it's being fought over) has a [WAR_DROP_ONE_IN] chance to drop a random
 *    piece straight to the killer ([CityFrontierPlugin.registerLoot]). Server-sized rare: a piece
 *    surfaces only every few wars realm-wide, so 3rd age reads as "earned in the war".
 *  - **Leadership relic** — the Minister/King who WINS the war has a [LEADER_GIFT_ONE_IN] chance to
 *    have a piece added to their commander spoils, on top of the coin ([CapturePayout] rolls it into
 *    the [WarSpoils] claim box). A rank-only reward, collected with `::claim`.
 *
 * The Quartermaster's Relics storefront still sells 3rd age (tripled pity prices) and now buys it back
 * from any player at a third of shelf value through the shop window (WarlordsArmouryPlugin) — but the
 * drop is now the prestige story, and the buy-back is deliberately below player-trade value so a
 * duplicate is worth more in trade.
 *
 * [PIECES] is the shared source of truth for both drop paths (a random pick); the shop keeps its own
 * priced `Ware` list in sync by key.
 */
object ThirdAge {
    /** Every obtainable 3rd age piece (rscm keys). Keep in sync with the Armoury Relics stock. */
    val PIECES: List<String> = listOf(
        // Melee
        "item._3rd_age_full_helmet", "item._3rd_age_platebody", "item._3rd_age_platelegs", "item._3rd_age_kiteshield",
        // Range
        "item._3rd_age_range_coif", "item._3rd_age_range_top", "item._3rd_age_range_legs", "item._3rd_age_vambraces",
        // Mage
        "item._3rd_age_mage_hat", "item._3rd_age_robe_top", "item._3rd_age_robe", "item._3rd_age_amulet",
        // Weapons & cloak
        "item._3rd_age_longsword", "item._3rd_age_bow", "item._3rd_age_wand", "item._3rd_age_cloak",
        // Druidic — the apex flex
        "item._3rd_age_druidic_robe_top", "item._3rd_age_druidic_robe_bottoms", "item._3rd_age_druidic_staff", "item._3rd_age_druidic_cloak",
    )

    /** ~1-in-N enemy kills on a LIVE war battlefield drop a random 3rd age piece (server-sized rare). TUNE. */
    const val WAR_DROP_ONE_IN = 5_000

    /** ~1-in-N won campaigns/conquests add a piece to the commanding Minister/King's spoils. TUNE. */
    const val LEADER_GIFT_ONE_IN = 1_000

    /** A random resolvable piece's item id, or -1 if none resolve (cache mismatch — never crash a kill). */
    fun randomPieceId(): Int =
        PIECES.shuffled().firstNotNullOfOrNull { runCatching { getRSCM(it) }.getOrNull() } ?: -1

    /** Roll [WAR_DROP_ONE_IN]: true if this kill wins a battlefield relic (call only on a live war tile). */
    fun rollWarDrop(): Boolean = (1..WAR_DROP_ONE_IN).random() == 1

    /** Roll [LEADER_GIFT_ONE_IN]: true if the victorious commander earns a relic this war. */
    fun rollLeaderGift(): Boolean = (1..LEADER_GIFT_ONE_IN).random() == 1

    /** Drop a random piece at [tile], owned by [killer] (battlefield source). Announced realm-wide + logged. */
    fun awardDrop(world: World, killer: Player, tile: Tile) {
        val id = randomPieceId()
        if (id < 0) return
        world.spawn(GroundItem(id, 1, tile, killer))
        recordAndAnnounce(world, killer, id, "amid the spoils of the war")
    }

    /** Announce a recovered piece realm-wide and slot it in the killer's collection log. Shared by the
     *  battlefield drop and the commander leadership relic ([WarSpoils]) so both read identically. */
    fun recordAndAnnounce(world: World, p: Player, id: Int, how: String) {
        val name = runCatching { getItem(id).name }.getOrNull() ?: "a Third Age relic"
        Announce.broadcast(world, "<col=ffae00>News: ${p.username} has recovered <col=ff0000>$name</col> $how!</col>")
        if (CollectionLog.record(p, id)) {
            p.message("<col=ffae00>New Collection Log slot: $name!</col>")
        }
    }
}
