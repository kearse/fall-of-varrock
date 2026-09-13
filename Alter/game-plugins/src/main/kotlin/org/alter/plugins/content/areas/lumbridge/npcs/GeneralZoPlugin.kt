package org.alter.plugins.content.areas.lumbridge.npcs

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.NpcSkills
import org.alter.api.ext.chatNpc
import org.alter.api.ext.chatPlayer
import org.alter.api.ext.options
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.combat.NpcCombatDef
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.companion.RecruitMenu
import org.alter.plugins.content.quests.framework.NpcTalk
import org.alter.plugins.content.quests.framework.bindTalk
import org.alter.plugins.content.teleport.TeleportService
import org.alter.plugins.content.war.CampaignRegistry
import org.alter.plugins.content.war.WarNpcNames
import org.alter.plugins.content.war.address
import org.alter.plugins.content.war.outposts.SouthernWatch
import org.alter.plugins.content.war.warprep.WarPrepSurvival
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **General Zo** — Lumbridge's garrison commander, standing at his post in the castle.
 *
 * The war is fought OUT of Lumbridge (marches, operations, campaigns, conquests — see
 * `war/MarchPlugin` and `war/CampaignCommandPlugin`); the old defensive siege he used to
 * command is retired. He now: reports the live offensive war, musters companions
 * ([RecruitMenu]), and gives/drives the War-Prep III (Survival) quest.
 *
 * NB: this repurposes the old **Melee combat tutor** (npc id 3216). The "General Zo"
 * display name is applied at spawn via [WarNpcNames] (extended-info, no cache edit); the
 * rscm key stays `npc.melee_combat_tutor`. This plugin owns his spawn.
 *
 * His Talk-to is routed through [NpcTalk] (`bindTalk`): the menu below is the DEFAULT branch, and
 * the story quests (The North, First Reclamation, A Kingdom Alone …) claim the conversation on
 * their own steps via `QuestDefinition.talk` without editing this file. On their non-Zo steps they
 * say one pointer line and then run this branch through `NpcTalk.runDefault`, so the War-Prep III
 * nudge at the top of [dialog] waits its turn behind a live story step rather than pre-empting it.
 */
class GeneralZoPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private var zo: Npc? = null

    init {
        onWorldInit { spawnZo(world) }

        bindTalk(ZO_NPC)
        NpcTalk.register(ZO_NPC, NpcTalk.PRIORITY_DEFAULT) { _ -> { p -> dialog(p) } }
    }

    /** Post Zo at [ZO_TILE] with his tanky stats (players can't attack him — his cache NPC has no
     *  Attack option — but the stats keep him standing if anything ever swings at him). */
    private fun spawnZo(world: World) {
        runCatching {
            val npc = Npc(getRSCM(ZO_NPC), ZO_TILE, world)
            npc.routeLogic = 1
            // MUST be before world.spawn: the client avatar takes its facing at alloc time, so a
            // direction set after spawn never renders. Faces his west desks in the hub's ring,
            // same as Duke Horacio one tile north — the pair stand facing the same way.
            npc.lastFacingDirection = Direction.WEST
            world.spawn(npc)
            // MUST be after world.spawn: setNpcDefaults() resets combatDef + HP to the cache default
            // on spawn, so applying his stats earlier would be silently clobbered.
            npc.combatDef = ZO_DEF
            npc.stats.setMaxLevel(NpcSkills.ATTACK, ZO_DEF.attack); npc.stats.setCurrentLevel(NpcSkills.ATTACK, ZO_DEF.attack)
            npc.stats.setMaxLevel(NpcSkills.STRENGTH, ZO_DEF.strength); npc.stats.setCurrentLevel(NpcSkills.STRENGTH, ZO_DEF.strength)
            npc.stats.setMaxLevel(NpcSkills.DEFENCE, ZO_DEF.defence); npc.stats.setCurrentLevel(NpcSkills.DEFENCE, ZO_DEF.defence)
            npc.setCurrentHp(ZO_DEF.hitpoints)
            WarNpcNames.apply(npc, ZO_NPC) // display "General Zo" without a cache edit
            npc.respawns = false
            npc.setActive(true)
            zo = npc
            logger.info { "General Zo posted at $ZO_TILE." }
        }.onFailure { logger.error(it) { "General Zo failed to spawn at $ZO_TILE." } }
    }

    private suspend fun QueueTask.dialog(player: Player) {
        // War-Prep III (Survival): General Zo gives + drives this quest. GEAR (hand the survival kit)
        // and REPORT (the debrief that closes it) must act and return; DRILL/FIELD get a nudge (with a
        // vouch escape on FIELD) before the normal command menu.
        when (WarPrepSurvival.step(player)) {
            WarPrepSurvival.Step.DRILL -> { survivalDrillNudge(player); return }
            WarPrepSurvival.Step.GEAR -> { survivalArm(player); return }
            WarPrepSurvival.Step.FIELD -> { survivalFieldNudge(player); return }
            WarPrepSurvival.Step.REPORT -> { survivalDebrief(player); return }
            else -> {}
        }
        // Chat-box lines are hand-wrapped at ≤ ~48 visible characters, four rows at most: the
        // npc-dialogue text component is ~380px of bold-12 text, and anything past that is cut
        // off the bottom of the box. (Same rule as the story quests' Zo lines.)
        chatNpc(player, "Well met, ${player.address}. I am General Zo, commander<br>of the Lumbridge garrison. The realm's war is<br>fought out there — on the roads and in the ruins.", title = ZO)
        // The menu is built by name so an unlock can add a row without shifting the others' indices.
        val choices = arrayListOf(OPT_STATUS, OPT_COMMAND, OPT_RECRUIT)
        if (SouthernWatch.isUnlocked(player)) choices += OPT_WATCH // First Reclamation: the forward post
        choices += OPT_NOTHING
        when (choices.getOrNull(options(player, *choices.toTypedArray(), title = ZO) - 1)) {
            OPT_STATUS -> reportStatus(player)
            OPT_COMMAND -> commandLadder(player)
            // Recruiting is the client-drawn Muster Companions window (lofrecruit): discipline
            // cards + banner strip, with the rank gate / full-banner states drawn, not spoken.
            OPT_RECRUIT -> RecruitMenu.open(player)
            OPT_WATCH -> sendToSouthernWatch(player)
            else -> chatPlayer(player, "Nothing for now.")
        }
    }

    /** Post-First-Reclamation: Zo sends the player to the Southern Watch (the route the quest unlocked). */
    private suspend fun QueueTask.sendToSouthernWatch(player: Player) {
        chatPlayer(player, "Send me to the Southern Watch.")
        chatNpc(player, "The circle's ours, ${player.address} — the roads around it<br>are not. Keep your eyes open up there.", title = ZO)
        TeleportService.teleport(player, SouthernWatch.ROUTE)
    }

    /** The live offensive war: the realm's march in the field, or the commanders' campaign. */
    private suspend fun QueueTask.reportStatus(player: Player) {
        val march = CampaignRegistry.activeMarch()
        val campaign = CampaignRegistry.isAttacking("varrock")
        when {
            campaign -> chatNpc(player,
                "A commander has the army in <col=801700>Fallen Varrock</col> this<br>very hour. Every sword counts — get to the front!", title = ZO)
            march != null -> chatNpc(player,
                "The Knight-Captain's ${march.tier.display} is in the field —<br>${march.progressPct(player.world)}% of the way to its objective. Rally to the<br>column with <col=801700>::march</col>; the realm pays its<br>soldiers from the spoils.", title = ZO)
            else -> chatNpc(player,
                "The garrison stands ready and no column is out<br>just now. The Knight-Captain musters a march every<br>half hour — watch for the call, and answer it<br>with <col=801700>::march</col>.", title = ZO)
        }
    }

    private suspend fun QueueTask.commandLadder(player: Player) {
        chatPlayer(player, "How do I take command?")
        chatNpc(player,
            "Any citizen may fight in a march — rank gates<br>who may START a war, never who may join one.", title = ZO)
        chatNpc(player,
            "A Lord may sponsor a squad; a Minister launches<br>campaigns; only the King calls a conquest.<br>Earn your standing, ${player.address}.", title = ZO)
    }

    // ───────────────────────────── War-Prep III — Survival ─────────────────────────────

    /** DRILL step: raise Hitpoints to the target. Hitpoints trains through combat, so there's no item
     *  to hand out — but a bounded [WarPrepSurvival.toughenUp] escape drills the rest in past the cap. */
    private suspend fun QueueTask.survivalDrillNudge(player: Player) {
        when (WarPrepSurvival.toughenUp(player)) {
            WarPrepSurvival.TopUp.DRILLED -> {
                chatNpc(player, "Still soft? No more excuses. On the training<br>yard — we'll toughen you the hard way.", title = ZO)
                chatNpc(player, "General Zo runs you through a brutal<br>conditioning drill. Your Hitpoints reach ${WarPrepSurvival.HP_TARGET}.", title = ZO)
            }
            WarPrepSurvival.TopUp.NOT_NEEDED ->
                chatNpc(player, "A commander who can't take a hit gets his<br>men killed. Toughen up — raise your Hitpoints<br>to ${WarPrepSurvival.HP_TARGET}, then come back and I'll kit you<br>for the real trial.", title = ZO)
        }
    }

    /** GEAR step: hand over the survival kit and send the soldier into the Fight Cave. */
    private suspend fun QueueTask.survivalArm(player: Player) {
        chatNpc(player, "Tough enough now. When the front collapses,<br>${player.address}, it's the soldier who outlasts the rout<br>who lives. Time to prove you can.", title = ZO)
        // Advance immediately with the handout — a chatNpc between them let an early chat-close
        // strand the step on GEAR and re-claim the kit (same dupe as Vannaka's tower kit).
        WarPrepSurvival.armForTrial(player) // armour + food + brews + restores
        WarPrepSurvival.onArmedForTrial(player) // GEAR → FIELD
        chatNpc(player, "Take this kit — armour, food, brews and restores.<br>Go to the <col=801700>Fight Cave</col> and <col=801700>survive to wave ${WarPrepSurvival.FIELD_WAVE}</col>.<br>Manage your health, don't panic, and endure.", title = ZO)
        chatNpc(player, "Come back to me when you've made that wave.<br>Follow the marker.", title = ZO)
    }

    /** FIELD step: reach the target Fight Cave wave. A vouch escape (once they've proven an honest
     *  attempt) prevents an unlucky cave run from soft-locking the quest. */
    private suspend fun QueueTask.survivalFieldNudge(player: Player) {
        chatNpc(player, "The cave isn't beaten yet — best wave ${WarPrepSurvival.bestWave(player)}<br>of ${WarPrepSurvival.FIELD_WAVE}. Get back in there and endure.", title = ZO)
        // Only offer the vouch once they've genuinely tried (reached the lower bar).
        if (WarPrepSurvival.bestWave(player) >= WarPrepSurvival.FIELD_VOUCH_WAVE) {
            when (options(player, "I'll head back in.", "I keep dying short of it — pass me, General.", title = ZO)) {
                2 -> {
                    if (WarPrepSurvival.vouchField(player)) {
                        chatNpc(player, "You've bled enough in there for me to know your<br>mettle. I'll vouch for you. Report to me proper —<br>the trial's behind you.", title = ZO)
                    }
                }
                else -> chatPlayer(player, "I'll head back in.")
            }
        }
    }

    /** REPORT step: debrief — pay the survival BOUNTY; the Ministry itself is earned in command. */
    private suspend fun QueueTask.survivalDebrief(player: Player) {
        chatNpc(player, "You held out when lesser men would have broken.<br>That's the making of a commander, ${player.address}.", title = ZO)
        chatNpc(player, "The realm pays for the trial: <col=801700>${"%,d".format(WarPrepSurvival.SURVIVAL_BOUNTY)} coins</col>,<br>a survival bounty. The <col=801700>Ministry</col> you'll EARN<br>in command — lead marches and raids, farm the<br>ladder's elite for their rares.", title = ZO)
        WarPrepSurvival.onReportedToZo(player) // REPORT → RANK: pays the bounty
        chatNpc(player, "When your purse reaches ${"%,d".format(org.alter.plugins.content.war.Title.MINISTER.cost)}, the Duke will<br>raise you. A Minister stands within reach of the<br>crown itself — and the King's endgame.", title = ZO)
    }

    companion object {
        const val ZO = "General Zo"
        const val ZO_NPC = "npc.melee_combat_tutor"

        private const val OPT_STATUS = "How goes the war, General?"
        private const val OPT_COMMAND = "How do I take command?"
        private const val OPT_RECRUIT = "I'd like to recruit soldiers under my banner."
        private const val OPT_WATCH = "Send me to the Southern Watch."
        private const val OPT_NOTHING = "Nothing for now."

        /** His post: inside the hub's desk ring, west column — one tile south of Duke Horacio
         *  (3220,3211), against the 2x2 pillar (3221-3222 x 3210-3211). TUNE in-game. */
        val ZO_TILE = Tile(3220, 3210, 0)

        /** Tanky garrison-commander stats (he is not attackable by players; kept so nothing that
         *  ever swings at him one-shots the realm's general). TUNE. */
        val ZO_DEF: NpcCombatDef = NpcCombatDef.DEFAULT.copy(
            attack = 110, strength = 110, defence = 120, hitpoints = 400,
            attackAnimation = 407,
        )
    }
}
