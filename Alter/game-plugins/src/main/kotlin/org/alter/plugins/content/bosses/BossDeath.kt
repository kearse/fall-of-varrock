package org.alter.plugins.content.bosses

import dev.openrune.cache.CacheManager.getItem
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.awardTickets
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * The **shared boss payout** — the KBD/Vorkath death pattern in one place so every ported
 * boss pays the same way: Boss Tickets, the kill ledger, a [DropTable] roll spawned as
 * owned ground items (rare broadcast + Collection Log), and an optional pet roll that goes
 * to inventory or bank. Unknown item keys are logged and skipped rather than thrown, so a
 * typo in a table can never kill a death handler.
 */
object BossDeath {

    fun payout(
        world: World,
        killer: Player,
        at: Tile,
        key: String,
        name: String,
        drops: DropTable,
        tickets: Int,
        pet: String? = null,
        petOneIn: Int = 0,
        mainRolls: Int = 1,
    ) {
        killer.awardTickets(PointKind.BOSS, tickets)
        val kc = BossKills.record(killer, key)

        drops.roll(world, mainRolls = mainRolls).forEach { drop ->
            val id = runCatching { getRSCM(drop.item) }.getOrNull()
            if (id == null) {
                logger.warn { "boss-death: unknown drop key ${drop.item} on $key" }
                return@forEach
            }
            world.spawn(GroundItem(id, drop.amount, at, killer))
            val itemName = getItem(id).name
            if (drop.announce) {
                world.players.forEach {
                    it.message("<col=ff0000>News: ${killer.username} just received <col=ffae00>$itemName</col> from $name!</col>")
                }
            }
            if (drop.log && CollectionLog.record(killer, id)) {
                killer.message("<col=ffae00>New Collection Log slot: $itemName!</col>")
            }
        }

        if (pet != null && petOneIn > 0 && world.chance(1, petOneIn)) {
            val petId = runCatching { getRSCM(pet) }.getOrNull()
            if (petId != null) {
                val add = killer.inventory.add(item = petId, amount = 1, assureFullInsertion = false)
                if (add.completed == 0) killer.bank.add(petId, 1)
                val petName = getItem(petId).name
                world.players.forEach {
                    it.message("<col=ff0000>News: ${killer.username} just received a <col=ffae00>$petName</col> from $name!</col>")
                }
                if (CollectionLog.record(killer, petId)) {
                    killer.message("<col=ffae00>New Collection Log slot: $petName!</col>")
                }
            }
        }

        killer.message("<col=ff0000>You have slain $name.</col> Kill count: $kc (+$tickets Boss Tickets)")
    }
}
