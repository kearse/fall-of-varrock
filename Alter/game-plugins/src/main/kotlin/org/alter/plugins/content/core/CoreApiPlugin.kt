package org.alter.plugins.content.core

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.war.WarType

private val logger = KotlinLogging.logger {}

/**
 * Boot witness + admin self-test for the core facade package (`docs/core-api.md`): logs one
 * `[core] api ready` line and answers `::coreapi` with every facade's read-side for the caller —
 * the living smoke test that the seams other teams call still resolve.
 */
class CoreApiPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onWorldInit {
            logger.info {
                "[core] api ready: WarEffortApi, RealmSuppliesApi, WarApi, RankApi, QuestApi, VeteranApi, CompanionApi (ACTIVE_MAX=${CompanionApi.ACTIVE_MAX}), StateApi — see docs/core-api.md"
            }
        }

        onCommand("coreapi", Privilege.ADMIN_POWER, description = "Core API self-test: every facade's read-side for you") {
            val p = player
            p.message("<col=801700>Core API — ${p.username}:</col>")
            p.message("  WarEffortApi.get = <col=4f9b4f>${WarEffortApi.get(p)}</col> · RealmSuppliesApi.get = <col=4f9b4f>${RealmSuppliesApi.get()}/${RealmSuppliesApi.max()}</col>")
            val next = RankApi.nextRank(p)
            p.message("  RankApi.rank = <col=4f9b4f>${RankApi.rank(p).display}</col>" +
                (next?.let { " · next ${it.display}: ${if (RankApi.isEligible(p, it)) "eligible" else RankApi.describe(RankApi.eligibility(p, it))}" } ?: " · maxed"))
            p.message("  WarApi: campaign ${yesNo(WarApi.canStartCampaign(p))}, conquest ${yesNo(WarApi.canStartConquest(p))}, running=${WarApi.isRunning()} · why not campaign: ${WarApi.whyNot(p, WarType.CAMPAIGN).joinToString(" | ").ifEmpty { "-" }}")
            p.message("  VeteranApi.has = <col=4f9b4f>${VeteranApi.has(p)}</col> · CompanionApi.canDeploy = <col=4f9b4f>${CompanionApi.canDeploy(p)}</col> (active ${CompanionApi.activeCount(p)}, roster ${CompanionApi.rosterSize(p)}/${CompanionApi.rosterCap(p)})")
            p.message("  QuestApi.followed = ${QuestApi.followed(p) ?: "-"} · demo_quest = ${QuestApi.state(p, "demo_quest") ?: "not started"} · flags: ${StateApi.allFlags(p).joinToString(", ").ifEmpty { "-" }}")
        }
    }

    private fun yesNo(b: Boolean) = if (b) "<col=4f9b4f>yes</col>" else "<col=801700>no</col>"
}
