package org.alter.plugins.content.areas.lumbridge.npcs

import org.alter.api.ext.chatNpc
import org.alter.api.ext.chatPlayer
import org.alter.api.ext.message
import org.alter.api.ext.options
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.companion.CompanionRegistry
import org.alter.plugins.content.companion.CompanionStyle
import org.alter.plugins.content.war.Sieges
import org.alter.plugins.content.war.Title
import org.alter.plugins.content.war.WarState
import org.alter.plugins.content.war.address
import org.alter.plugins.content.war.nextTitle
import org.alter.plugins.content.war.title
import org.alter.rscm.RSCM.getRSCM

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
            3 -> gatedRecruit(player)
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

    private suspend fun QueueTask.gatedRecruit(player: Player) {
        chatPlayer(player, "I'd like to recruit troops under my banner.")
        // The feudal rank sets the allowance: Knight fields 1 companion, Lord 2, Minister/King 3.
        val cap = CompanionRegistry.companionCap(player)
        if (cap == 0) {
            val firstRank = Title.values().first { it.companions > 0 }.display
            chatNpc(player,
                "Coin can raise a company of your own — once you<br>hold rank enough to lead one. Rise to $firstRank first,<br>and we'll talk recruitment.", title = ZO)
            return
        }
        if (CompanionRegistry.count(player) >= cap) {
            val next = player.nextTitle
            val hint = if (next != null && next.companions > player.title.companions) {
                " Rise to ${next.display} and I'll<br>muster you another."
            } else ""
            chatNpc(player,
                "Your banner is full, ${player.address} — a ${player.title.display} may field<br>$cap. Lead them well.$hint", title = ZO)
            return
        }
        chatNpc(player,
            "Aye, ${player.address}. A trained soldier costs ${fmt(RECRUIT_COST)}<br>coin. What discipline do you need under your banner?", title = ZO)
        val style = when (options(player, "A melee soldier.", "A ranged soldier.", "A mage soldier.", "Never mind.", title = "Recruit a soldier")) {
            1 -> CompanionStyle.MELEE
            2 -> CompanionStyle.RANGE
            3 -> CompanionStyle.MAGE
            else -> { chatPlayer(player, "Never mind."); return }
        }
        val coins = getRSCM("item.coins_995")
        if (player.inventory.getItemCount(coins) < RECRUIT_COST) {
            chatNpc(player, "Come back when you can pay the ${fmt(RECRUIT_COST)} coin,<br>${player.address}. Soldiers don't march for free.", title = ZO)
            return
        }
        player.inventory.remove(coins, RECRUIT_COST)
        val comp = CompanionRegistry.recruit(world, player, style)
        if (comp == null) {
            player.inventory.add(coins, RECRUIT_COST) // refund if the muster failed
            chatNpc(player, "No soldier could be raised just now. Try again<br>shortly.", title = ZO)
        } else {
            chatNpc(player,
                "It's done. A ${style.display} fighter joins your banner —<br>you command ${CompanionRegistry.count(player)} of $cap now. Use<br><col=801700>::companion deploy</col> to send them to war.", title = ZO)
        }
    }

    private fun fmt(n: Int): String = "%,d".format(n)

    private companion object {
        const val ZO = "General Zo"
        const val RECRUIT_COST = 1_000_000 // coin to raise one companion soldier (TUNABLE)
    }
}
