package org.alter.plugins.content.war

import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.DUKE_INTRO_DONE_ATTR
import org.alter.game.model.entity.Player
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
        onNpcOption("npc.duke_horacio", option = "talk-to") {
            player.queue { duke(player) }
        }
    }

    private suspend fun QueueTask.duke(player: Player) {
        val firstMeeting = player.attr[DUKE_INTRO_DONE_ATTR] != true
        val inTutorial = RecruitTrials.step(player) == RecruitTrials.Step.RANK
        ensureIntro(player) // full first-meeting introduction, once
        val next = player.nextTitle

        // Intro-quest path (master design brief §1): flow straight from the introduction into
        // claiming the first rank with the coin the Sergeant paid out — no second greeting.
        if (inTutorial && next != null) {
            chatNpc(player, "And you've coin enough from the Sergeant to claim your first rank this very moment. Shall I raise you to ${next.display}? It costs ${fmt(next.cost)} coins.")
            when (options(player, "Yes — make me a ${next.display}.", "Not just yet.")) {
                1 -> {
                    buy(player, next)
                    if (player.title == next) {
                        chatNpc(player, "Well met, ${next.display}. The war needs slayers now — seek out Vannaka, south of the market, and take a contract.")
                    }
                }
                2 -> chatPlayer(player, "Not just yet.")
            }
            return
        }

        // War-Prep finale (the Wizard Tower quest): Vannaka's payout covers the next rank, so flow
        // straight into claiming it — same shape as the intro-quest path above.
        if (WarPrepChain.step(player) == WarPrepChain.Step.RANK && next != null) {
            chatNpc(player, "Word from Vannaka — the Wizards' Tower taken, and by you, ${player.title.display}. Deeds like that are what rank is FOR, and his purse covers the next rung. Shall I raise you to ${next.display}? It costs ${fmt(next.cost)} coins.")
            when (options(player, "Yes — make me a ${next.display}.", "Not just yet.")) {
                1 -> {
                    buy(player, next)
                    if (player.title == next) {
                        chatNpc(player, "Wear your new ${next.maxTier.display} armour with pride, ${next.display} — the war's raids will ask everything of it.")
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
            val pitch = if (next == Title.KNIGHT) {
                "the Knight's rune, a companion of your own, and the wilderness"
            } else {
                "${next.display}'s ${next.maxTier.display} armour — and Knight beyond it"
            }
            chatNpc(player, "The Sergeant sends word of your work on the rogues' ladder — and every coin of your purse your own earning. Shall I raise you to ${next.display}? It costs ${fmt(next.cost)} coins, and earns you $pitch.")
            when (options(player, "Yes — make me a ${next.display}.", "Not just yet.")) {
                1 -> {
                    buy(player, next)
                    if (player.title == Title.KNIGHT) {
                        chatNpc(player, "Arise, Knight. You've earned a companion of your own — General Zo in the courtyard will muster them — and the wilderness is yours to hunt. Now finish what you started: the rogues' ladder still stands.")
                    }
                }
                2 -> chatPlayer(player, "Not just yet.")
            }
            return
        }

        // Returning visitors get the concise greeting — skipped right after a first-meeting intro so
        // he doesn't re-introduce himself.
        if (!firstMeeting) {
            chatNpc(player, "Greetings, ${player.title.display}. For the right coin, I can raise your standing in the realm.")
        }
        // Outside the quest beats, the ladder itself is the client-drawn Feudal Ranks window
        // (lofranks): the whole ladder, costs and unlocks at a glance — no options() menu.
        RankMenu.open(player)
    }

    /** First-meeting introduction: who the Duke is and how the feudal rank ladder works. Runs once. */
    private suspend fun QueueTask.ensureIntro(player: Player) {
        if (player.attr[DUKE_INTRO_DONE_ATTR] == true) return
        player.attr[DUKE_INTRO_DONE_ATTR] = true
        chatNpc(player, "Ah — a fresh recruit, sent up by the Sergeant. I am Duke Horacio, lord of Lumbridge. Welcome to the realm's service.")
        chatPlayer(player, "How do I earn rank, my lord?")
        chatNpc(player, "Every citizen begins a Peasant. By serving the war — fighting at the frontier, slaying Vannaka's contracts, supplying the army — you earn coin and standing.")
        chatNpc(player, "Bring that coin — and a record of real service — to me and I shall raise you through the feudal ranks: Commoner, Squire, Soldier, Knight, Lord... and, for the truly great, beyond.")
        chatNpc(player, "Each rank lets you bear heavier armour and grants greater authority in the war: any citizen may fight in a march, but only the ranked may START one. Rise high enough and the rabble at the frontier won't even dare raise a blade to you.")
        chatNpc(player, "Mark this well: rank is <col=801700>earned</col>. No mere donation buys a title here — coin won AND deeds done, your War Effort. Now, let us see to your standing.")
    }

    /** Dialogue wrapper over the shared [RankPurchase] transaction (used by the quest beats). */
    private suspend fun QueueTask.buy(player: Player, next: Title) {
        val prev = player.title
        when (val r = RankPurchase.buy(player, next)) {
            is RankPurchase.Result.Insufficient ->
                chatNpc(player, "The rank of ${next.display} costs ${fmt(next.cost)} coins, but you carry only ${fmt(r.have)}. Come back when your purse is heavier.")
            is RankPurchase.Result.Blocked ->
                chatNpc(player, "Coin alone does not make a ${next.display}, ${player.address} — rank is standing. You still owe the realm: ${RankEligibility.describeAll(r.unmet)}. Serve, and return.")
            is RankPurchase.Result.Success -> {
                chatNpc(player, "Then it is done. Arise, ${next.display}! You may now wear ${next.maxTier.display} armour.")
                if (next.roster > prev.roster) {
                    chatNpc(player, "Your new station also entitles you to a roster of ${next.roster} soldier companion${if (next.roster > 1) "s" else ""} — one at your side at a time. General Zo in the castle courtyard will muster them.")
                }
            }
            else -> {} // NotNext/Maxed can't happen from the quest beats (they pass player.nextTitle)
        }
    }

    private fun fmt(n: Int): String = "%,d".format(n)
}
