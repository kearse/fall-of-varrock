package org.alter.plugins.content.war.boss

import org.alter.api.ext.message
import org.alter.game.model.World
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.plugins.content.announce.Announce
import org.alter.plugins.content.bosses.CollectionLog
import org.alter.plugins.content.bosses.RolledDrop
import org.alter.plugins.content.bots.PkBot
import org.alter.plugins.content.companion.Companion
import org.alter.plugins.content.companion.CompanionRegistry
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints
import org.alter.plugins.content.economy.awardTickets
import org.alter.plugins.content.war.CampaignRegistry
import org.alter.rscm.RSCM.getRSCM

/**
 * Splits a slain boss's spoils by **damage contribution** ([org.alter.game.model.combat.DamageMap]):
 * everyone who meaningfully fought gets a coin share (proportional to damage) and a shot at the
 * boss's rare table; the **MVP** (top damager) gets a guaranteed rare. A sponsor who summoned the
 * boss takes a tithe off the coin pool first, earns a personal bonus rare roll, and is awarded
 * prestige.
 *
 * **Lords who fielded troops** also earn from the kill: the boss tracks the damage each Lord's men
 * dealt (NPC attackers are recorded in the DamageMap too), so every Lord gets a coin share
 * proportional to their squad's damage — the pool is divided across players AND troops by total
 * damage. Loot drops to the ground owned by each recipient; points go to the persistent counters.
 *
 * Unknown rare ids are skipped (resolved + guarded), so a kill never crashes on a missing cache id.
 */
object BossLoot {
    /** Minimum damage to share in the spoils (filters AFK/tag leeching). TUNE. */
    private const val ELIGIBLE_DMG = 10

    private val coinId by lazy { getRSCM("item.coins_995") }

    fun award(world: World, npc: Npc, raid: ActiveRaid) {
        val def = raid.def
        // Fold each companion's damage back into its human owner, and drop every other fake player
        // (PK bots) from the split entirely. A companion is a Player subclass, so the raw damage map
        // counts it as a separate reward-sharing participant — that's why a solo player fighting
        // alongside a companion only got a partial share (~45%) instead of the full pool. Crediting
        // the owner keeps the companion's damage "counting" for eligibility/MVP without splitting the
        // spoils away from the human. (Check Companion before PkBot: Companion is a PkBot subclass.)
        val creditedDamage = HashMap<Player, Int>()
        npc.damageMap.playerDamage().forEach { (p, dmg) ->
            val owner = when (p) {
                is Companion -> CompanionRegistry.ownerOf(world, p) // offline owner → damage is dropped
                is PkBot -> null // other fake players never share the spoils
                else -> p
            } ?: return@forEach
            creditedDamage.merge(owner, dmg) { a, b -> a + b }
        }
        // Only living players who dealt at least the eligibility threshold share the spoils.
        val contrib = creditedDamage
            .filterKeys { it.index >= 0 && !it.isDead() }
            .filterValues { it >= ELIGIBLE_DMG }
        // Each Lord whose men damaged the boss earns a cut proportional to that damage.
        val troopContrib = CampaignRegistry.troopDamageByOwner(raid.cityId, npc)

        val sponsor = raid.sponsor?.takeIf { it.index >= 0 && !it.isDead() }
        // Sponsor always earns prestige for funding the operation, even if it flopped.
        sponsor?.addPoints(PointKind.PRESTIGE, def.prestigeAward)

        if (contrib.isEmpty() && troopContrib.isEmpty()) {
            sponsor?.message("<col=801700>Your ${def.displayName} fell, but no worthy hand struck it. No spoils were divided.</col>")
            return
        }

        // The pool is divided across BOTH players and troops by their share of the total damage.
        val total = (contrib.values.sum() + troopContrib.values.sum()).coerceAtLeast(1)
        val mvp = contrib.maxByOrNull { it.value }?.key

        // Coin pool: sponsor tithe off the top, the rest split by damage share.
        val pool = world.random(def.coinDrop)
        val tithe = if (sponsor != null) (pool * def.titheCut).toInt().coerceAtLeast(0) else 0
        if (tithe > 0 && sponsor != null) {
            world.spawn(GroundItem(coinId, tithe, sponsor.tile, sponsor))
            sponsor.message("<col=ffae00>Your sponsor's tithe from the ${def.displayName}: ${fmt(tithe)} coins.</col>")
        }
        val splitPool = pool - tithe

        // Players: coin share by their own damage + a shot at the rare table.
        contrib.forEach { (player, dmg) ->
            val coins = (splitPool.toLong() * dmg / total).toInt().coerceAtLeast(1)
            world.spawn(GroundItem(coinId, coins, player.tile, player))
            // (Boss Tickets retired 2026-09 — the coin share and the drop table are the payout.)
            player.message("<col=4f9b4f>You slew the ${def.displayName}! Your share: ${fmt(coins)} coins.</col>")

            // The HIGH-VALUE tiered table — every contributor rolls it (always supplies + a weighted
            // gear/resource pick + independent chase rolls). The MVP rolls it TWICE for the kill blow.
            def.dropTable.roll(world).forEach { grantDrop(world, player, it, def) }
            if (player === mvp) {
                player.message("<col=ffcc00>For landing the telling blows you claim the lion's share!</col>")
                def.dropTable.roll(world).forEach { grantDrop(world, player, it, def) }
            }

            // The avatar-tier mega-rares: each unique rolls independently for every eligible
            // contributor. The world-boss (event) spawn boosts these to [eventUniqueMultiplier]×
            // the base odds — e.g. a 1-in-150 sigil becomes 1-in-75 — the reward for turning out
            // to the public event rather than farming the always-on cave lair.
            def.uniqueTable.forEach { uniq ->
                val odds = (uniq.oneInN / def.eventUniqueMultiplier.coerceAtLeast(1)).coerceAtLeast(1)
                if (uniq.oneInN > 0 && world.random(odds - 1) == 0) {
                    grantUnique(world, player, uniq, def)
                }
            }
        }

        // Lords: coin share by the damage THEIR MEN dealt — "a cut of all your soldiers slay".
        troopContrib.forEach { (lord, dmg) ->
            if (lord.index < 0 || lord.isDead()) return@forEach
            val coins = (splitPool.toLong() * dmg / total).toInt().coerceAtLeast(1)
            world.spawn(GroundItem(coinId, coins, lord.tile, lord))
            lord.message("<col=4f9b4f>Your men's valour against the ${def.displayName} earns you ${fmt(coins)} coins.</col>")
        }

        // Sponsor's personal bonus: an extra roll of the table for funding the kill.
        if (sponsor != null && def.bonusRollOneIn > 0 && world.random(def.bonusRollOneIn - 1) == 0) {
            def.dropTable.roll(world).forEach { grantDrop(world, sponsor, it, def) }
            sponsor.message("<col=ffcc00>A sponsor's fortune smiles on you — bonus spoils!</col>")
        }
    }

    /** Spawn one rolled drop for [player], honouring its collection-log + server-announce flags. */
    private fun grantDrop(world: World, player: Player, drop: RolledDrop, def: BossDef) {
        val id = runCatching { getRSCM(drop.item) }.getOrNull() ?: return // unknown cache id — skip
        world.spawn(GroundItem(id, drop.amount, player.tile, player))
        if (drop.log) {
            val fresh = CollectionLog.record(player, id)
            if (fresh) player.message("<col=ffae00>New Collection Log slot: ${pretty(drop.item)}!</col>")
        }
        if (drop.announce) {
            player.message("<col=ffcc00>RARE DROP! The ${def.displayName} yields a ${pretty(drop.item)}!</col>")
            Announce.broadcast(
                world,
                "<col=ff0000>News: ${player.username} just received a <col=ffae00>${pretty(drop.item)}</col> from the ${def.displayName}!</col>",
            )
        }
    }

    /** Drop a mega-rare to [player], record the collection-log slot, and headline it server-wide. */
    private fun grantUnique(world: World, player: Player, uniq: BossUnique, def: BossDef) {
        val id = runCatching { getRSCM(uniq.item) }.getOrNull() ?: return // unknown cache id — skip
        world.spawn(GroundItem(id, 1, player.tile, player))
        val name = pretty(uniq.item)
        val fresh = CollectionLog.record(player, id)
        player.message("<col=ffcc00>AVATAR DROP! The ${def.displayName} yields a $name!</col>")
        if (fresh) player.message("<col=ffae00>New Collection Log slot: $name!</col>")
        if (uniq.announce) {
            Announce.broadcast(
                world,
                "<col=ff0000>News: ${player.username} just received a <col=ffae00>$name</col> from the ${def.displayName}!</col>",
            )
        }
    }

    /** "item.arcane_sigil" -> "Arcane Sigil" (no item-def lookup needed for an announce string). */
    private fun pretty(rscmKey: String): String =
        rscmKey.substringAfter("item.").substringAfter("npc.").split('_')
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    private fun fmt(n: Int): String = "%,d".format(n)
}
