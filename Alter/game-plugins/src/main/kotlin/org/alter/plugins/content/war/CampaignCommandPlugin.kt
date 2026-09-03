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
import org.alter.plugins.content.war.events.WarEvents
import org.alter.plugins.content.war.events.WarHooks
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
 *  - `::conquest` (King)      — a full army's deepest assault on the city; a temporary battlefield
 *                               victory and its spoils, never ownership. Spends Realm Supplies.
 *
 * The three player-commanded ops are thin callers of [WarAuthority] (the one check + launch path
 * every other surface — quests, council NPCs, windows — shares); this plugin only narrates.
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
                player.message("<col=4f9b4f>Your men march on the boss — you earn a share of all they slay. Use <col=0000ff>::troops follow</col><col=4f9b4f> to recall them to your side.</col>")
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
            command(player, WarType.LORD_OPERATION)
        }

        onCommand("campaign", description = "March a campaign on a hostile city (Minister+): ::campaign [city]") {
            command(player, WarType.CAMPAIGN)
        }
        onCommand("conquest", description = "Send a full army against a hostile city (King): ::conquest [city]") {
            command(player, WarType.CONQUEST)
        }

        // ::publicwar — admin: launch a PUBLIC, sponsor-less op of any tier (what a story event
        // does through WarEvents) — free, supply-free, no commander. The test hook for the
        // "first major assault as a generic public operation" path and the WarHooks result.
        onCommand("publicwar", Privilege.ADMIN_POWER, description = "Launch a public sponsor-less war op (test): ::publicwar <march|grand|campaign|conquest> <target>") {
            val a = player.getCommandArgs()
            val type = when (a.getOrNull(0)?.lowercase()) {
                "march" -> WarType.MARCH
                "grand" -> WarType.GRAND_MARCH
                "campaign" -> WarType.CAMPAIGN
                "conquest" -> WarType.CONQUEST
                else -> null
            }
            val target = a.getOrNull(1)
            if (type == null || target == null) {
                player.message("Usage: ::publicwar <march|grand|campaign|conquest> <target>. Targets: ${MarchTargets.pool.joinToString { it.key }}; cities: ${Campaigns.HOSTILE.joinToString { it.cityKey }}.")
                return@onCommand
            }
            when (val r = WarEvents.startPublicOperation(world, type, target)) {
                is WarEvents.StartResult.Started -> player.message("<col=4f9b4f>[test] Public ${type.display} launched on ${r.display} — ledger key ${r.opKey}; free, supply-free, no sponsor.</col>")
                is WarEvents.StartResult.NoSuchTarget -> player.message("<col=801700>No such target '${r.key}'.</col>")
                is WarEvents.StartResult.Busy -> player.message("<col=801700>Refused: ${r.reason}.</col>")
                is WarEvents.StartResult.NotPublic -> player.message("<col=801700>A ${r.type.display} needs a sponsor.</col>")
                WarEvents.StartResult.Failed -> player.message("<col=801700>The ${type.display} could not set out.</col>")
            }
        }

        // Every war result reaches the log — the always-on witness for the WarHooks seam (quests,
        // achievements and leaderboards subscribe the same way).
        WarHooks.onOperationEnded(priority = Int.MAX_VALUE) { r ->
            logger.info { "[WAR RESULT] ${r.type.display} on ${r.targetKey} ${if (r.won) "WON" else "LOST"} (op ${r.opKey}, sponsor=${r.sponsor ?: "-"}, pool=${r.lootPool}, shares=${r.shares})" }
        }

        // ::supply — anyone can check the Realm Supplies stockpile (filled by skilling the Mire +
        // handing supplies to a Quartermaster; campaigns/conquests can only launch when it's high
        // enough — marches and Lord operations never touch it).
        onCommand("supply", description = "Check the Realm Supplies stockpile") {
            player.message(RealmSupply.status())
        }
        // ::setsupply — admin: seed the stockpile to test the campaign gate without grinding supplies.
        onCommand("setsupply", Privilege.ADMIN_POWER, description = "Set the Realm Supplies stockpile (test): ::setsupply <n>") {
            val n = player.getCommandArgs().getOrNull(0)?.toIntOrNull() ?: 0
            WarState.setSupplyMeter(n)
            player.message("<col=4f9b4f>[test] ${RealmSupply.NAME} set to ${RealmSupply.meter()}/${RealmSupply.max()}.</col>")
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

    /**
     * The shared command body for every player-commanded op: [WarAuthority] decides and launches
     * (rank → target → squad → ground → coins → supplies; charge, start, refund on a race, drain the
     * stockpile, King quest); this only narrates. Campaigns/conquests march on a HOSTILE city (never
     * the home capital) — an optional city arg picks the target; operations name a march target.
     * The commander is NOT teleported — they rally to the army themselves.
     */
    private fun command(player: Player, type: WarType) {
        val key = player.getCommandArgs().getOrNull(0)
        when (val r = WarAuthority.launch(world, player, type, key)) {
            is WarAuthority.LaunchResult.Refused ->
                WarAuthority.describeAll(type, r.denials).forEach { player.message("<col=801700>$it</col>") }
            WarAuthority.LaunchResult.Failed ->
                player.message("<col=801700>The ${type.display} could not set out.</col>")
            is WarAuthority.LaunchResult.Started -> {
                val op = r.op
                val m = r.muster
                when {
                    type == WarType.LORD_OPERATION && m != null ->
                        player.message("<col=4f9b4f>Your operation on ${op.displayName} musters at (${m.x}, ${m.z}) — any soldier may rally to it with <col=0000ff>::march</col><col=4f9b4f>. Win it and the commander's tithe is yours (::claim).</col>")
                    m != null ->
                        player.message("<col=4f9b4f>Your ${type.display} musters at (${m.x}, ${m.z}) and marches on ${op.displayName}! Get to the field to earn your share — or use <col=0000ff>::troops follow</col><col=4f9b4f> to bring them to you.</col>")
                    else ->
                        player.message("<col=4f9b4f>You lead your ${type.display} on ${op.displayName} — your men muster at the front.</col>")
                }
            }
        }
    }

    private fun fmt(n: Int): String = "%,d".format(n)

    private companion object {
        const val TICK = 2 // ~1.2s operation cadence (matches the frontier upkeep)
        const val SEND_TROOPS_COST = 1_000_000 // a Lord's standalone escort-squad deployment fee
        // A Lord's sponsored public operation is priced by WarType.LORD_OPERATION.coinCost.
        const val CORRIDOR_PAD = 16 // tiles of margin around a march route when force-loading its regions
    }
}
