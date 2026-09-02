package org.alter.plugins.content.war

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.CITY_ID_ATTR
import org.alter.game.model.collision.isClipped
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.model.priv.Privilege
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * Title-gated **war command** entry points and the single timer that ticks all active
 * operations ([CampaignRegistry]). The boss-summon command lives in [WorldBossPlugin]; the
 * troop dispatch it triggers ([WarTroops]) starts a [CampaignTier.RAID] here.
 *
 *  - `::sendtroops` (Lord+)   — pay to field your own escort squad; you earn a share of all your
 *                               men slay (their damage to the boss buys you a cut of its loot).
 *  - `::operation` (Lord+)    — sponsor a small public offensive on a [MarchTargets] target:
 *                               coin-funded, supply-free, anyone may join; the sponsor takes the
 *                               commander's tithe ([CapturePayout]).
 *  - `::campaign` (Minister+) — push the city's frontier; wins by breaking the garrison. Spends
 *                               Realm Supplies.
 *  - `::conquest` (King)      — a full army seizes the city and its spoils. Spends Realm Supplies.
 *
 * Participation is never rank-gated — only STARTING an op is (design authority §5). Several Lords
 * can field squads at once (a boss fight draws many), but each Lord may only have ONE squad out
 * at a time.
 */
class CampaignCommandPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val coins = getRSCM("item.coins_995")

    init {
        // Build collision for every march corridor and open its river bridges, ONCE at boot, so a
        // cross-country campaign army is pathable the whole way with no player nearby.
        onWorldInit { prepareMarchCorridors(world) }

        val timer = TimerKey()
        onWorldInit { world.timers[timer] = TICK }
        onTimer(timer) {
            CampaignRegistry.tick(world)
            world.timers[timer] = TICK
        }

        // ::sendtroops — any Lord+ pays to send a squad of their own men to the BOSS around home.
        // Their men march to it and the Lord earns a share of everything the squad slays.
        onCommand("sendtroops", description = "Send your own men to fight the boss (Lord+)") {
            if (!player.canCommand(CommandTier.RAID)) {
                player.message("<col=801700>Only a Lord or higher may field troops.</col>")
                return@onCommand
            }
            if (CampaignRegistry.hasSquad(player)) {
                player.message("<col=801700>You already have men in the field.</col>"); return@onCommand
            }
            val have = player.inventory.getItemCount(coins)
            if (have < SEND_TROOPS_COST) {
                player.message("<col=801700>Fielding a squad costs ${fmt(SEND_TROOPS_COST)} coins; you carry only ${fmt(have)}.</col>")
                return@onCommand
            }
            player.inventory.remove(coins, SEND_TROOPS_COST)
            if (!CampaignRegistry.start(world, Campaigns.home(), CampaignTier.RAID, player)) {
                player.inventory.add(coins, SEND_TROOPS_COST) // race lost — refund
                player.message("<col=801700>Your men could not muster.</col>")
            } else {
                player.message("<col=4f9b4f>Your men march on the boss — you earn a share of all they slay. Use <col=ffae00>::troops follow</col><col=4f9b4f> to recall them to your side.</col>")
            }
        }

        // ::troops <advance|follow> — set your squad's orders. ADVANCE (default) sends them to fight
        // the boss/objective on their own; FOLLOW recalls them to guard you.
        onCommand("troops", description = "Set your squad's orders: ::troops <advance|follow>") {
            val squad = CampaignRegistry.squadOf(player)
            if (squad == null) { player.message("<col=801700>You have no squad in the field.</col>"); return@onCommand }
            when (player.getCommandArgs().getOrNull(0)?.lowercase()) {
                "advance" -> { squad.mode = SquadMode.ADVANCE; player.message("<col=4f9b4f>Your men advance on the enemy.</col>") }
                "follow" -> { squad.mode = SquadMode.FOLLOW; player.message("<col=4f9b4f>Your men fall in to guard you.</col>") }
                else -> player.message("Usage: ::troops <advance|follow> (currently ${squad.mode}).")
            }
        }

        // ::operation <target> — a Lord's own small offensive on a march target. Public (anyone
        // rallies with ::march), coin-funded (the fee is the Lord's contribution — not refunded),
        // supply-free. Runs as a MARCH-tier column with the Lord as sponsor.
        onCommand("operation", description = "Sponsor a small offensive on a march target (Lord+): ::operation <target>") {
            if (!player.canCommand(CommandTier.RAID)) {
                player.message("<col=801700>Only a Lord or higher may sponsor an operation. Any soldier may still join one with ::march.</col>")
                return@onCommand
            }
            val targets = MarchTargets.pool.joinToString(", ") { it.key }
            val key = player.getCommandArgs().getOrNull(0)
            val t = key?.let { MarchTargets.byKey(it) }
            if (t == null) {
                player.message("<col=801700>Usage: ::operation <target>. Targets: $targets.</col>")
                return@onCommand
            }
            if (CampaignRegistry.hasSquad(player)) {
                player.message("<col=801700>You already have a squad in the field.</col>"); return@onCommand
            }
            if (CampaignRegistry.activeMarch() != null || CampaignRegistry.isAttacking(t.key) || CampaignRegistry.overlapsActive(t.op.battleArea)) {
                player.message("<col=801700>A column is already in the field on that ground — wait for it to return.</col>"); return@onCommand
            }
            val have = player.inventory.getItemCount(coins)
            if (have < OPERATION_COST) {
                player.message("<col=801700>Sponsoring an operation costs ${fmt(OPERATION_COST)} coins; you carry only ${fmt(have)}.</col>")
                return@onCommand
            }
            player.inventory.remove(coins, OPERATION_COST)
            if (!CampaignRegistry.start(world, t.op, CampaignTier.MARCH, player)) {
                player.inventory.add(coins, OPERATION_COST) // race lost — refund
                player.message("<col=801700>The operation could not set out.</col>")
                return@onCommand
            }
            val m = t.op.route.first()
            player.message("<col=4f9b4f>Your operation on ${t.display} musters at (${m.x}, ${m.z}) — any soldier may rally to it with <col=ffae00>::march</col><col=4f9b4f>. Win it and the commander's tithe is yours (::claim).</col>")
        }

        onCommand("campaign", description = "March a campaign on a hostile city (Minister+): ::campaign [city]") {
            launch(player, CampaignTier.CAMPAIGN, CommandTier.CAMPAIGN)
        }
        onCommand("conquest", description = "Send an army to conquer a hostile city (King): ::conquest [city]") {
            launch(player, CampaignTier.CONQUEST, CommandTier.CONQUEST)
        }

        // ::supply — anyone can check the Realm Supplies stockpile (filled by skilling the Mire +
        // handing supplies to a Quartermaster; campaigns/conquests can only launch when it's high
        // enough — marches and Lord operations never touch it).
        onCommand("supply", description = "Check the realm's war-supply stores") {
            player.message(RealmSupply.status())
        }
        // ::setsupply — admin: seed the meter to test the campaign gate without grinding supplies.
        onCommand("setsupply", Privilege.ADMIN_POWER, description = "Set the realm war-supply meter (test): ::setsupply <n>") {
            val n = player.getCommandArgs().getOrNull(0)?.toIntOrNull() ?: 0
            WarState.setSupplyMeter(n)
            player.message("<col=4f9b4f>[test] Realm war-stores set to ${RealmSupply.meter()}/${RealmSupply.max()}.</col>")
        }

        // ::marchdebug — admin: toggle per-tick troop-position logging ("MARCHDBG" lines) so the
        // column's movement can be read from the server log.
        onCommand("marchdebug", Privilege.ADMIN_POWER, description = "Toggle campaign march debug logging") {
            CampaignDirector.debug = !CampaignDirector.debug
            player.message("<col=4f9b4f>March debug logging: ${if (CampaignDirector.debug) "ON" else "OFF"}.</col>")
        }

        // ::wintest — admin: force your active campaign to a victory NOW with a seeded war-chest, to
        // verify the payout (tithe → auto-bank → donor points) without grinding the kill quota.
        onCommand("wintest", Privilege.ADMIN_POWER, description = "Force-win your active campaign (test): ::wintest [poolGp]") {
            val seed = player.getCommandArgs().getOrNull(0)?.toIntOrNull() ?: 1_000_000
            if (!CampaignRegistry.forceWin(world, player, seed)) {
                player.message("<col=801700>You have no campaign in the field. Launch one with ::campaign first.</col>")
            } else {
                player.message("<col=4f9b4f>[test] Forced a victory with a ${fmt(seed)} gp war-chest.</col>")
            }
        }

        // ::wartp — admin: jump to the active boss in your city if one is up, else the troop
        // staging point. So after ::summonboss it drops you right on the boss.
        onCommand("wartp", Privilege.ADMIN_POWER, description = "Teleport to the home boss / war front") {
            val home = Campaigns.home()
            val boss = org.alter.plugins.content.war.boss.BossScheduler.bossesIn(home.cityId).firstOrNull()
            val dest = boss?.tile ?: home.stagingTile
            player.moveTo(dest)
            if (boss != null) player.message("Teleported to the boss near ${home.displayName} (${dest.x}, ${dest.z}).")
            else player.message("No boss up — teleported to the ${home.displayName} muster point (${dest.x}, ${dest.z}). The front is north.")
        }
    }

    /** Force-load every routed op's march-route corridor and open its bridge decks (once, at boot) —
     *  the commanders' hostile targets plus every scheduled-march target ([Campaigns.ROUTED]). */
    private fun prepareMarchCorridors(world: World) {
        val regions = sortedSetOf<Int>()
        for (op in Campaigns.ROUTED) {
            if (op.route.isEmpty()) continue
            val xs = op.route.map { it.x }; val zs = op.route.map { it.z }
            val xMin = xs.min() - CORRIDOR_PAD; val xMax = xs.max() + CORRIDOR_PAD
            val zMin = zs.min() - CORRIDOR_PAD; val zMax = zs.max() + CORRIDOR_PAD
            for (rx in (xMin shr 6)..(xMax shr 6)) for (rz in (zMin shr 6)..(zMax shr 6)) regions += (rx shl 8) or rz
        }
        if (regions.isNotEmpty()) {
            runCatching { world.definitions.loadRegions(world, world.chunks, regions.toIntArray()) }
                .onFailure { logger.error(it) { "[CAMPAIGN] march-corridor force-load failed" } }
        }
        // Open bridge decks AFTER collision is built: clear the level-0 block on tiles that are blocked
        // on L0 but walkable on L1 (the deck), leaving real banks/walls (blocked on both) untouched.
        var opened = 0
        for (op in Campaigns.ROUTED) for (span in op.bridgeSpans) {
            for (x in span.bottomLeftX..span.topRightX) for (z in span.bottomLeftY..span.topRightY) {
                if (world.collision.isClipped(Tile(x, z, 0)) && !world.collision.isClipped(Tile(x, z, 1))) {
                    world.collision.set(x, z, 0, 0); opened++
                }
            }
        }
        logger.info { "[CAMPAIGN] march corridors: force-loaded ${regions.size} region(s), opened $opened bridge tile(s)." }
    }

    private fun launch(player: Player, tier: CampaignTier, gate: CommandTier) {
        if (!player.canCommand(gate)) {
            player.message("<col=801700>Only a ${gate.minTitle.display} or higher may command a ${tier.display}.</col>")
            return
        }
        // Campaigns/conquests march on a HOSTILE city (e.g. Varrock) — never the home capital. An
        // optional city arg picks the target by name; with one target it's the same as no arg.
        val key = player.getCommandArgs().getOrNull(0)
        val op = if (key != null) Campaigns.hostileByKey(key) else Campaigns.hostileTarget()
        if (op == null) {
            val targets = Campaigns.HOSTILE.joinToString(", ") { it.cityKey }.ifEmpty { "none yet" }
            player.message("<col=801700>No such war target. Cities open for war: $targets.</col>")
            return
        }
        if (CampaignRegistry.hasSquad(player)) {
            player.message("<col=801700>You already have a squad in the field.</col>")
            return
        }
        val have = player.inventory.getItemCount(coins)
        if (have < tier.cost) {
            player.message("<col=801700>A ${tier.display} costs ${fmt(tier.cost)} coins; you carry only ${fmt(have)}.</col>")
            return
        }
        // The realm must be supplied before it can march — the whole point of the Mire skilling loop.
        if (!RealmSupply.canAfford(tier.supplyCost)) {
            player.message("<col=801700>The realm's war-stores are too low to march a ${tier.display} — the people must supply the war first (${RealmSupply.meter()}/${tier.supplyCost} needed). Skill the Mire and hand supplies to a Quartermaster.</col>")
            return
        }
        player.inventory.remove(coins, tier.cost)
        if (!CampaignRegistry.start(world, op, tier, player)) {
            player.inventory.add(coins, tier.cost) // race lost — refund
            player.message("<col=801700>The ${tier.display} could not set out.</col>")
            return
        }
        RealmSupply.consume(world, tier, player.username, op.displayName) // launching drains the realm stores
        Conquest.onLaunched(player, tier) // advances the "King of Lumbridge" quest on a conquest launch
        // The commander is NOT teleported — they stay where they are and rally to the army themselves.
        if (op.route.isNotEmpty()) {
            val m = op.route.first()
            player.message("<col=4f9b4f>Your ${tier.display} musters at (${m.x}, ${m.z}) and marches on ${op.displayName}! Get to the field to earn your share — or use <col=ffae00>::troops follow</col><col=4f9b4f> to bring them to you.</col>")
        } else {
            player.message("<col=4f9b4f>You lead your ${tier.display} on ${op.displayName} — your men muster at the front.</col>")
        }
    }

    private fun fmt(n: Int): String = "%,d".format(n)

    private companion object {
        const val TICK = 2 // ~1.2s operation cadence (matches the frontier upkeep)
        const val SEND_TROOPS_COST = 1_000_000 // a Lord's standalone escort-squad deployment fee
        const val OPERATION_COST = 500_000 // a Lord's sponsored public operation (not refunded). TUNE.
        const val CORRIDOR_PAD = 16 // tiles of margin around a march route when force-loading its regions
    }
}
