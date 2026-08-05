package org.alter.plugins.content.areas.lumbridge.npcs

import org.alter.api.ext.chatNpc
import org.alter.api.ext.chatPlayer
import org.alter.api.ext.options
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.companion.RecruitMenu
import org.alter.plugins.content.war.Sieges
import org.alter.plugins.content.war.WarState
import org.alter.plugins.content.war.address
import org.alter.plugins.content.war.warprep.WarPrepSurvival

/**
 * **General Zo** — commander of Lumbridge's defense, standing in the castle courtyard.
 *
 * He is the goblins' objective: their raids drive for the castle goal where he commands,
 * and a breach (the city falls) is narratively the horde reaching him. For now he runs the
 * defense automatically and reports its live status. The "take command" / "recruit troops"
 * options are wired but gated behind a future feudal rank (lord/minister/king) — the hooks
 * the player-controlled war + troop purchasing will drop into.
 *
 * NB: this repurposes the old **Melee combat tutor** (npc id 3216). The "General Zo"
 * display name is applied at spawn via [WarNpcNames] (extended-info, no cache edit); the
 * rscm key stays `npc.melee_combat_tutor`.
 */
class GeneralZoPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        // NB: General Zo is spawned + owned by the war's AttackDirector (he's the attackable VIP
        // with combat stats, death = city falls, respawn on recovery), so this plugin only wires
        // his conversation — it must NOT spawn a second copy.
        onNpcOption(npc = "npc.melee_combat_tutor", option = "talk-to") {
            player.queue { dialog(player) }
        }
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
        chatNpc(player, "Well met, citizen. I am General Zo, commander of the<br>Lumbridge defense. The goblin horde tests our walls.", title = ZO)
        when (options(
            player,
            "How goes the war, General?",
            "Let me take command of the defense.",
            "I'd like to recruit troops under my banner.",
            "Nothing for now.",
            title = ZO,
        )) {
            1 -> reportStatus(player)
            2 -> gatedCommand(player)
            // Recruiting is the client-drawn Muster Companions window (lofrecruit): discipline
            // cards + banner strip, with the rank gate / full-banner states drawn, not spoken.
            3 -> RecruitMenu.open(player)
            4 -> chatPlayer(player, "Nothing for now.")
        }
    }

    private suspend fun QueueTask.reportStatus(player: Player) {
        val front = Sieges.LUMBRIDGE.frontId
        val pool = WarState.getKnightPool(front)
        val max = WarState.knightPoolMax(front)
        when (WarState.phaseOf(front)) {
            WarState.Phase.PEACE -> chatNpc(player,
                "The line holds and the city is at peace. $pool of<br>$max knights stand ready. Stay sharp — the horde<br>always returns.", title = ZO)
            WarState.Phase.UNDER_RAID -> {
                val s = WarState.raidStatus(front)
                chatNpc(player,
                    "We are UNDER ATTACK — a ${s.tierName.lowercase()} of ${s.goblinsAlive}<br>goblins still stands. I have committed the knights;<br>get to the fields and help us throw them back!", title = ZO)
            }
            WarState.Phase.CITY_FALLEN -> chatNpc(player,
                "The castle was overrun and I was forced to fall back.<br>We are regrouping to retake the city. Dark days,<br>citizen — but we WILL rebuild.", title = ZO)
        }
    }

    private suspend fun QueueTask.gatedCommand(player: Player) {
        chatPlayer(player, "Let me take command of the defense.")
        chatNpc(player,
            "Bold! But only a Lord of Lumbridge may command my<br>knights. Earn that title and I'll hand you the field —<br>you'll move troops where you will, even march on<br>other cities.", title = ZO)
    }

    // ───────────────────────────── War-Prep III — Survival ─────────────────────────────

    /** DRILL step: raise Hitpoints to the target. Hitpoints trains through combat, so there's no item
     *  to hand out — but a bounded [WarPrepSurvival.toughenUp] escape drills the rest in past the cap. */
    private suspend fun QueueTask.survivalDrillNudge(player: Player) {
        when (WarPrepSurvival.toughenUp(player)) {
            WarPrepSurvival.TopUp.DRILLED -> {
                chatNpc(player, "Still soft? No more excuses. On the training yard —<br>we'll toughen you the hard way.", title = ZO)
                chatNpc(player, "General Zo runs you through a brutal conditioning drill.<br>Your Hitpoints reach ${WarPrepSurvival.HP_TARGET}.", title = ZO)
            }
            WarPrepSurvival.TopUp.NOT_NEEDED ->
                chatNpc(player, "A commander who can't take a hit gets his men killed.<br>Toughen up — raise your Hitpoints to ${WarPrepSurvival.HP_TARGET},<br>then come back and I'll kit you for the real trial.", title = ZO)
        }
    }

    /** GEAR step: hand over the survival kit and send the soldier into the Fight Cave. */
    private suspend fun QueueTask.survivalArm(player: Player) {
        chatNpc(player, "Tough enough now. When the front collapses, ${player.address},<br>it's the soldier who outlasts the rout who lives. Time to<br>prove you can.", title = ZO)
        // Advance immediately with the handout — a chatNpc between them let an early chat-close
        // strand the step on GEAR and re-claim the kit (same dupe as Vannaka's tower kit).
        WarPrepSurvival.armForTrial(player) // armour + food + brews + restores
        WarPrepSurvival.onArmedForTrial(player) // GEAR → FIELD
        chatNpc(player, "Take this kit — armour, food, brews and restores. Go to<br>the <col=801700>Fight Cave</col> and <col=801700>survive to wave ${WarPrepSurvival.FIELD_WAVE}</col>. Manage<br>your health, don't panic, and endure.", title = ZO)
        chatNpc(player, "Come back to me when you've made that wave. Follow the<br>marker.", title = ZO)
    }

    /** FIELD step: reach the target Fight Cave wave. A vouch escape (once they've proven an honest
     *  attempt) prevents an unlucky cave run from soft-locking the quest. */
    private suspend fun QueueTask.survivalFieldNudge(player: Player) {
        chatNpc(player, "The cave isn't beaten yet — best wave ${WarPrepSurvival.bestWave(player)} of<br>${WarPrepSurvival.FIELD_WAVE}. Get back in there and endure.", title = ZO)
        // Only offer the vouch once they've genuinely tried (reached the lower bar).
        if (WarPrepSurvival.bestWave(player) >= WarPrepSurvival.FIELD_VOUCH_WAVE) {
            when (options(player, "I'll head back in.", "I keep dying short of it — pass me, General.", title = ZO)) {
                2 -> {
                    if (WarPrepSurvival.vouchField(player)) {
                        chatNpc(player, "You've bled enough in there for me to know your<br>mettle. I'll vouch for you. Report to me proper — the<br>trial's behind you.", title = ZO)
                    }
                }
                else -> chatPlayer(player, "I'll head back in.")
            }
        }
    }

    /** REPORT step: debrief — pay the survival BOUNTY; the Ministry itself is earned in command. */
    private suspend fun QueueTask.survivalDebrief(player: Player) {
        chatNpc(player, "You held out when lesser men would have broken. That's<br>the making of a commander, ${player.address}.", title = ZO)
        chatNpc(player, "The realm pays for the trial: <col=801700>${"%,d".format(WarPrepSurvival.SURVIVAL_BOUNTY)} coins</col>, a survival<br>bounty. The <col=801700>Ministry</col> you'll EARN in command — lead marches<br>and raids, farm the ladder's elite for their rares.", title = ZO)
        WarPrepSurvival.onReportedToZo(player) // REPORT → RANK: pays the bounty
        chatNpc(player, "When your purse reaches ${"%,d".format(org.alter.plugins.content.war.Title.MINISTER.cost)}, the Duke will raise you.<br>A Minister stands within reach of the crown itself —<br>and the King's endgame.", title = ZO)
    }

    private companion object {
        const val ZO = "General Zo"
    }
}
