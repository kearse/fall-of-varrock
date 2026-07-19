package org.alter.game.message.handler

import net.rsprot.protocol.game.incoming.messaging.MessagePublic
import org.alter.game.message.MessageHandler
import org.alter.game.model.entity.Client
import org.alter.game.saving.PlayerModeration
import org.alter.game.service.log.LoggerService

/**
 * @author Tom <rspsmods@gmail.com>
 */
class MessagePublicHandler : MessageHandler<MessagePublic> {

    private companion object {
        /**
         * The vanilla OSRS client refuses to forward "scam-bait" :: commands (::bank, etc.)
         * and instead makes the player PUBLIC-CHAT this fixed line. We can't stop that
         * client-side (it happens in the gamepack before any RuneLite hook), so we catch it
         * here: suppress the embarrassing broadcast and run the intended command. The line is
         * generic across scam words, so we assume the overwhelmingly-common one — ::bank.
         */
        const val SCAM_BAIT_MESSAGE = "Hey, everyone, I just tried to do something very silly!"
    }

    override fun consume(
        client: Client,
        message: MessagePublic,
    ) {
        if (message.message == SCAM_BAIT_MESSAGE) {
            // Don't broadcast the troll; open the bank as if "::bank" had worked.
            client.world.plugins.executeCommand(client, "pbank")
            return
        }

        // The "Fall of Varrock Companions" RuneLite panel drives orders via the chat-send script, which posts
        // the text as PUBLIC CHAT (not a routed command). Catch the companion command here, run it,
        // and suppress the chat so it doesn't show. Extend the allow-list as the panel grows.
        val text = message.message.trim()
        if (text.startsWith("::")) {
            val parts = text.removePrefix("::").trim().split(Regex("\\s+"))
            // NB: the vanilla gamepack SILENTLY blocks a hardcoded list of "scam-bait" :: words
            // client-side (::bank proven; ::dice broke the Gambler's Table the same way — the
            // client substitutes the "very silly!" line and the token never arrives). Overlay
            // channels therefore use lof-prefixed tokens, which can never collide with Jagex's
            // list; the short forms stay as aliases for manual testing on the custom client.
            when (parts.firstOrNull()?.lowercase()) {
                "lofrank" -> {
                    client.world.plugins.executeCommand(client, "rankclick", parts.drop(1).toTypedArray())
                    return
                }
                "lofzo" -> {
                    client.world.plugins.executeCommand(client, "zoclick", parts.drop(1).toTypedArray())
                    return
                }
                "lofsup" -> {
                    client.world.plugins.executeCommand(client, "supclick", parts.drop(1).toTypedArray())
                    return
                }
                "lofmake" -> {
                    client.world.plugins.executeCommand(client, "makeclick", parts.drop(1).toTypedArray())
                    return
                }
                "lofcon" -> {
                    client.world.plugins.executeCommand(client, "conclick", parts.drop(1).toTypedArray())
                    return
                }
                "lofforge" -> {
                    client.world.plugins.executeCommand(client, "forgeclick", parts.drop(1).toTypedArray())
                    return
                }
                "lofdice" -> {
                    client.world.plugins.executeCommand(client, "diceclick", parts.drop(1).toTypedArray())
                    return
                }
                "lofbond" -> {
                    client.world.plugins.executeCommand(client, "bondclick", parts.drop(1).toTypedArray())
                    return
                }
                "lofstyle" -> {
                    client.world.plugins.executeCommand(client, "styleclick", parts.drop(1).toTypedArray())
                    return
                }
                "companion" -> {
                    client.world.plugins.executeCommand(client, "companion", parts.drop(1).toTypedArray())
                    return
                }
                // Teleport portal overlay (lofteleports): "::tp <cat> <row>" → teleport, suppress chat.
                "tp" -> {
                    client.world.plugins.executeCommand(client, "tpclick", parts.drop(1).toTypedArray())
                    return
                }
                // Feudal Ranks overlay (lofranks): "::rank buy <ordinal>" → purchase, suppress chat.
                "rank" -> {
                    client.world.plugins.executeCommand(client, "rankclick", parts.drop(1).toTypedArray())
                    return
                }
                // Muster Companions overlay (lofrecruit): "::zo recruit <style>" → recruit, suppress chat.
                "zo" -> {
                    client.world.plugins.executeCommand(client, "zoclick", parts.drop(1).toTypedArray())
                    return
                }
                // Supply Depot overlay (lofsupply): "::sup <cat|item|all> ..." → deposit, suppress chat.
                "sup" -> {
                    client.world.plugins.executeCommand(client, "supclick", parts.drop(1).toTypedArray())
                    return
                }
                // Making window (lofmake): "::make <resultId> <qty>" → smelt/smith, suppress chat.
                "make" -> {
                    client.world.plugins.executeCommand(client, "makeclick", parts.drop(1).toTypedArray())
                    return
                }
                // War Contracts overlay (lofcontracts): "::con <action>" → contracts, suppress chat.
                "con" -> {
                    client.world.plugins.executeCommand(client, "conclick", parts.drop(1).toTypedArray())
                    return
                }
                // War Forge overlay (lofforge): "::forge make <i>" → forging, suppress chat.
                "forge" -> {
                    client.world.plugins.executeCommand(client, "forgeclick", parts.drop(1).toTypedArray())
                    return
                }
                // Gambler's Table overlay (lofdice): "::dice roll <amount>" → bet, suppress chat.
                "dice" -> {
                    client.world.plugins.executeCommand(client, "diceclick", parts.drop(1).toTypedArray())
                    return
                }
                // Bond Exchange overlay (lofbonds): "::bond <action>" → claim/redeem, suppress chat.
                "bond" -> {
                    client.world.plugins.executeCommand(client, "bondclick", parts.drop(1).toTypedArray())
                    return
                }
                // Mire Dispenser overlay (lofmire): "::mire <attune|claim>" → dispenser action, suppress chat.
                "mire" -> {
                    client.world.plugins.executeCommand(client, "mireclick", parts.drop(1).toTypedArray())
                    return
                }
                // Duel rules overlay (lofduel): "::duel <action>" → rules interaction, suppress chat.
                "duel" -> {
                    client.world.plugins.executeCommand(client, "duelclick", parts.drop(1).toTypedArray())
                    return
                }
                // Stake overlay (lofstake): "::stake <action> [slot]" → stake interaction, suppress chat.
                "stake" -> {
                    client.world.plugins.executeCommand(client, "stakeclick", parts.drop(1).toTypedArray())
                    return
                }
                // Kit editor overlay (lofkit): "::kit <action> [arg]" → kit interaction, suppress chat.
                "kit" -> {
                    client.world.plugins.executeCommand(client, "kitclick", parts.drop(1).toTypedArray())
                    return
                }
                // Custom shop window (lofshop): switch tab / buy an item / close, suppress chat.
                "shoptab" -> {
                    client.world.plugins.executeCommand(client, "shoptabclick", parts.drop(1).toTypedArray())
                    return
                }
                "shopbuy" -> {
                    client.world.plugins.executeCommand(client, "shopbuyclick", parts.drop(1).toTypedArray())
                    return
                }
                "shopval" -> {
                    client.world.plugins.executeCommand(client, "shopvalclick", parts.drop(1).toTypedArray())
                    return
                }
                "shopexamine" -> {
                    client.world.plugins.executeCommand(client, "shopexamineclick", parts.drop(1).toTypedArray())
                    return
                }
                "shopclose" -> {
                    client.world.plugins.executeCommand(client, "shopcloseclick", parts.drop(1).toTypedArray())
                    return
                }
                // War spoils window (lofwarspoils): "::spoils <action> [index]" → claim, suppress chat.
                "spoils" -> {
                    client.world.plugins.executeCommand(client, "spoilsclick", parts.drop(1).toTypedArray())
                    return
                }
            }
        }

        if (PlayerModeration.isMuted(client.loginUsername)) {
            client.writeMessage("You are muted and cannot talk.")
            return
        }

        client.avatar.extendedInfo.setChat(
            message.colour,
            message.effect,
            client.privilege.icon,
            message.type == 1,
            message.message,
            pattern = message.pattern?.asByteArray(),
        )
        client.world.getService(LoggerService::class.java, searchSubclasses = true)?.logPublicChat(client, message.message)
    }
}
