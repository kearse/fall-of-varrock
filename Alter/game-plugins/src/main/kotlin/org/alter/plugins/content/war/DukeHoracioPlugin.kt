package org.alter.plugins.content.war

import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.DUKE_INTRO_DONE_ATTR
import org.alter.game.model.entity.Player
import org.alter.plugins.content.quests.framework.NpcTalk
import org.alter.plugins.content.quests.framework.bindTalk
import org.alter.plugins.content.war.recruit.RecruitTrials
import org.alter.plugins.content.war.roguehunt.RogueProblem
import org.alter.plugins.content.war.warprep.WarPrepChain
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * **Duke Horacio** in Lumbridge Castle raises feudal ranks — for coins AND proven service
 * ([RankEligibility]). Each rank raises the armour tier the player may wear (enforced by
 * [TitlePlugin]) and the wars they may start. He is already spawned by the Lumbridge chat
 * spawns, so we only bind his dialogue.
 */
class DukeHoracioPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        // Talk-to routes through NpcTalk so story quests can claim his conversation on their own
        // steps (QuestDefinition.talk); the rank dialogue below is the default branch.
        bindTalk("npc.duke_horacio")
        NpcTalk.register("npc.duke_horacio", NpcTalk.PRIORITY_DEFAULT) { _ -> { p -> duke(p) } }
    }

    private suspend fun QueueTask.duke(player: Player) {
        val firstMeeting = player.attr[DUKE_INTRO_DONE_ATTR] != true
        val inTutorial = RecruitTrials.step(player) == RecruitTrials.Step.RANK
        ensureIntro(player) // full first-meeting introduction, once
        val next = player.nextTitle

        // The Last Free City, RANK step: the first rank is recognition for standing at the east camp
        // when the line broke — flow straight from the introduction into claiming it with the coin
        // the Sergeant paid out. No second greeting.
        if (inTutorial && next != null) {
            chatNpc(player, "You stood at the eastern camp when the line<br>broke. That is service, and service is what rank<br>is FOR. Shall I raise you to ${next.display}? It costs<br>${fmt(next.cost)} coins — the Sergeant's pay covers it.")
            when (options(player, "Yes — make me a ${next.display}.", "Not just yet.")) {
                1 -> {
                    buy(player, next)
                    if (player.title == next) {
                        chatNpc(player, "Well met, ${next.display}. Now — the goblins that<br>scattered from the camp are still loose in the<br>countryside. Vannaka, south of the market, holds<br>the contract for them. Take it.")
                    }
                }
                2 -> chatPlayer(player, "Not just yet.")
            }
            return
        }

        // War-Prep finale (the Wizard Tower quest): Vannaka's payout covers the next rank, so flow
        // straight into claiming it — same shape as the intro-quest path above.
        if (WarPrepChain.step(player) == WarPrepChain.Step.RANK && next != null) {
            chatNpc(player, "Word from Vannaka — the Wizards' Tower taken,<br>and by you, ${player.title.display}. Deeds like that are what<br>rank is FOR. Shall I raise you to ${next.display}?<br>It costs ${fmt(next.cost)} coins — his purse covers it.")
            when (options(player, "Yes — make me a ${next.display}.", "Not just yet.")) {
                1 -> {
                    buy(player, next)
                    if (player.title == next) {
                        chatNpc(player, "Wear your new ${next.maxTier.display} armour with pride,<br>${next.display} — the war's raids will demand all of it.")
                    }
                }
                2 -> chatPlayer(player, "Not just yet.")
            }
            return
        }

        // "The Rogue Problem" (Act II): the hunt bounty covers Soldier and the ladder's spoils earn
        // Knight, so while the ladder is live and the player is still below Knight, flow straight
        // into claiming the next rung. Ranks never close the quest — only the broken ladder does.
        val rogueLadderLive = RogueProblem.step(player).ordinal >= RogueProblem.Step.KNIGHT.ordinal &&
            !RogueProblem.complete(player)
        if (rogueLadderLive && next != null && next.ordinal <= Title.KNIGHT.ordinal) {
            // Pre-wrapped: each pitch is the tail of row 2 plus all of row 3 of the box below.
            val pitch = if (next == Title.KNIGHT) {
                "the Knight's rune,<br>a companion of your own, and the wilderness."
            } else {
                "${next.display}'s<br>${next.maxTier.display} armour — and Knight beyond it."
            }
            chatNpc(player, "The Sergeant sends word of your work on the<br>rogues' ladder — and every coin of your purse<br>your own earning.")
            chatNpc(player, "Shall I raise you to ${next.display}? It costs<br>${fmt(next.cost)} coins, and earns you $pitch")
            when (options(player, "Yes — make me a ${next.display}.", "Not just yet.")) {
                1 -> {
                    buy(player, next)
                    if (player.title == Title.KNIGHT) {
                        chatNpc(player, "Arise, Knight. You've earned a companion of your<br>own — General Zo in the courtyard will muster<br>them — and the wilderness is yours to hunt. Now<br>finish the job: the rogues' ladder still stands.")
                    }
                }
                2 -> chatPlayer(player, "Not just yet.")
            }
            return
        }

        // Returning visitors get the concise greeting — skipped right after a first-meeting intro so
        // he doesn't re-introduce himself.
        if (!firstMeeting) {
            chatNpc(player, "Greetings, ${player.title.display}. For the right coin, I can<br>raise your standing in the realm.")
        }
        // Outside the quest beats, the ladder itself is the client-drawn Feudal Ranks window
        // (lofranks): the whole ladder, costs and unlocks at a glance — no options() menu.
        RankMenu.open(player)
    }

    /** First-meeting introduction: who the Duke is and how the feudal rank ladder works. Runs once.
     *  A recruit arriving on The Last Free City's RANK step is greeted as the defender Damien sent
     *  word about; anyone else (an older account meeting him late) gets the plain introduction. */
    private suspend fun QueueTask.ensureIntro(player: Player) {
        if (player.attr[DUKE_INTRO_DONE_ATTR] == true) return
        player.attr[DUKE_INTRO_DONE_ATTR] = true
        if (RecruitTrials.step(player) == RecruitTrials.Step.RANK) {
            chatNpc(player, "Damien sent word ahead. He says you stood with<br>the defenders at the eastern camp.")
            chatPlayer(player, "I did what I could.")
            chatNpc(player, "And Lumbridge survives because enough people<br>still do. I am Duke Horacio, lord of Lumbridge.")
        } else {
            chatNpc(player, "Ah — a citizen come to see about their standing.<br>I am Duke Horacio, lord of Lumbridge. Welcome to<br>the realm's service.")
            chatPlayer(player, "How do I earn rank, my lord?")
        }
        chatNpc(player, "Every citizen begins a Peasant. Service to the<br>realm earns something greater: fighting at the<br>frontier, slaying Vannaka's contracts, supplying<br>the army — that earns coin and standing.")
        chatNpc(player, "Bring that coin — and a record of real service —<br>to me and I shall raise you up the feudal ranks:<br>Commoner, Squire, Soldier, Knight, Lord... and,<br>for the truly great, beyond.")
        chatNpc(player, "Each rank lets you bear heavier armour, and<br>grants greater authority in the war: any citizen<br>may fight in a march, but only the ranked may<br>START one.")
        chatNpc(player, "Rise high enough and the rabble at the frontier<br>won't even dare raise a blade to you.")
        chatNpc(player, "Mark this well: rank is <col=801700>earned</col>. No mere donation<br>buys a title here — coin won AND deeds done,<br>your War Effort. Now, let us see to your<br>standing.")
    }

    /** Dialogue wrapper over the shared [RankPurchase] transaction (used by the quest beats). */
    private suspend fun QueueTask.buy(player: Player, next: Title) {
        val prev = player.title
        when (val r = RankPurchase.buy(player, next)) {
            is RankPurchase.Result.Insufficient ->
                chatNpc(player, "The rank of ${next.display} costs ${fmt(next.cost)} coins, but<br>you carry only ${fmt(r.have)}. Come back when<br>your purse is heavier.")
            is RankPurchase.Result.Blocked -> {
                chatNpc(player, "Coin alone does not make a ${next.display},<br>${player.address}. Rank is standing, and you still<br>owe the realm:")
                // One shortfall per row (at most coins, War Effort and a milestone flag — the quest
                // beats pass player.nextTitle, so NotNext/Maxed never reach here), then the send-off.
                chatNpc(player, r.unmet.joinToString("<br>") { RankEligibility.describe(it) } + "<br>Serve, and return.")
            }
            is RankPurchase.Result.Success -> {
                chatNpc(player, "Then it is done. Arise, ${next.display}! You may now<br>wear ${next.maxTier.display} armour.")
                if (next.roster > prev.roster) {
                    if (next.roster > 1) {
                        chatNpc(player, "Your new station also entitles you to a roster<br>of ${next.roster} soldier companions — all at your side<br>at once. General Zo in the castle courtyard<br>will muster them.")
                    } else {
                        chatNpc(player, "Your new station also entitles you to a soldier<br>companion of your own. General Zo in the castle<br>courtyard will muster them.")
                    }
                }
            }
            else -> {} // NotNext/Maxed can't happen from the quest beats (they pass player.nextTitle)
        }
    }

    private fun fmt(n: Int): String = "%,d".format(n)
}
