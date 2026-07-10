package org.alter.plugins.content.war

import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.DUKE_INTRO_DONE_ATTR
import org.alter.game.model.attr.PLAYER_TITLE_ATTR
import org.alter.game.model.entity.Player
import org.alter.plugins.content.war.recruit.RecruitTrials
import org.alter.plugins.content.war.warprep.WarPrepChain
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

/**
 * **Duke Horacio** in Lumbridge Castle sells feudal ranks for coins (The War /
 * progression — `docs/long-term-vision.md`). Each rank bought raises the armour
 * tier the player may wear (enforced by [TitlePlugin]). He is already spawned by
 * the Lumbridge chat spawns, so we only bind his dialogue.
 */
class DukeHoracioPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val coins = getRSCM("item.coins_995")

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

        // Returning visitors get the concise greeting — skipped right after a first-meeting intro so
        // he doesn't re-introduce himself.
        if (!firstMeeting) {
            chatNpc(player, "Greetings, ${player.title.display}. For the right coin, I can raise your standing in the realm.")
        }
        if (next == null) {
            chatNpc(player, "You already hold the highest rank in the land. There is nothing more I can grant you.")
            return
        }
        when (options(player, "Become a ${next.display} (${fmt(next.cost)} coins).", "What can each rank wear?", "Not just now.")) {
            1 -> buy(player, next)
            2 -> ranksInfo(player)
            3 -> chatPlayer(player, "Not just now.")
        }
    }

    /** First-meeting introduction: who the Duke is and how the feudal rank ladder works. Runs once. */
    private suspend fun QueueTask.ensureIntro(player: Player) {
        if (player.attr[DUKE_INTRO_DONE_ATTR] == true) return
        player.attr[DUKE_INTRO_DONE_ATTR] = true
        chatNpc(player, "Ah — a fresh recruit, sent up by the Sergeant. I am Duke Horacio, lord of Lumbridge. Welcome to the realm's service.")
        chatPlayer(player, "How do I earn rank, my lord?")
        chatNpc(player, "Every citizen begins a Peasant. By serving the war — fighting at the frontier, slaying Vannaka's contracts, supplying the army — you earn coin and standing.")
        chatNpc(player, "Bring that coin to me and I shall raise you through the feudal ranks: Commoner, Squire, Soldier, Knight, Lord... and, for the truly great, beyond.")
        chatNpc(player, "Each rank lets you bear heavier armour and grants greater privilege in the war. Rise high enough and the rabble at the frontier won't even dare raise a blade to you.")
        chatNpc(player, "Mark this well: rank is <col=801700>earned</col>. No mere donation buys a title here — only coin won and deeds done. Now, let us see to your standing.")
    }

    private suspend fun QueueTask.buy(player: Player, next: Title) {
        val have = player.inventory.getItemCount(coins)
        if (have < next.cost) {
            chatNpc(player, "The rank of ${next.display} costs ${fmt(next.cost)} coins, but you carry only ${fmt(have)}. Come back when your purse is heavier.")
            return
        }
        val prev = player.title
        player.inventory.remove(coins, next.cost)
        player.attr[PLAYER_TITLE_ATTR] = next.ordinal
        player.refreshTitledName() // stamp the new (colored, for nobles) name onto the appearance
        chatNpc(player, "Then it is done. Arise, ${next.display}! You may now wear ${next.maxTier.display} armour.")
        if (next.companions > prev.companions) {
            chatNpc(player, "Your new station also entitles you to ${next.companions} soldier companion${if (next.companions > 1) "s" else ""} — General Zo in the castle courtyard will muster them.")
        }
        RecruitTrials.onBuyRank(player) // advances the intro quest's RANK step, if active
        WarPrepChain.onRankBought(player) // closes the War-Prep chain's RANK step, if active
    }

    private suspend fun QueueTask.ranksInfo(player: Player) {
        chatNpc(player, "A Peasant wears bronze and iron; a Commoner, steel. A Squire wears black — and their name is spoken in colour across the realm.")
        chatNpc(player, "A Soldier bears mithril and adamant. A Knight bears rune, and may keep a soldier companion of their own.")
        chatNpc(player, "A Lord wears ALL armour, fields my troops, summons bosses — and commands two companions. A Minister launches war campaigns with three.")
        chatNpc(player, "And the King launches conquests and leads the realm itself.")
    }

    private fun fmt(n: Int): String = "%,d".format(n)
}
