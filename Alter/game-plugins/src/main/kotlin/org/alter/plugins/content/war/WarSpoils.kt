package org.alter.plugins.content.war

import dev.openrune.cache.CacheManager.getItem
import org.alter.api.ChatMessageType
import org.alter.api.ext.message
import org.alter.game.model.World
import org.alter.game.model.attr.WAR_SPOILS_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.rscm.RSCM.getRSCM
import org.bson.Document

/**
 * **A commander's unclaimed war spoils.** A Minister/King who WINS a campaign/conquest no longer has
 * their cut auto-banked — instead the war-stake return + the commander's tithe (and, on a
 * [ThirdAge.rollLeaderGift], a random 3rd age relic) are held here and collected with `::claim`,
 * which opens the custom **Spoils of War** window drawn by the client's `lofwarspoils` plugin.
 *
 * The rank-and-file contribution split ([CapturePayout]) still auto-banks for everyone (the sponsor
 * included, as a fighter); ONLY the commander's cut routes through this claim box.
 *
 * **Persistence:** the pending items are a JSON blob on [WAR_SPOILS_ATTR] (same pattern as loot keys),
 * so a commander can claim after a relog. Coins (995) merge into one stack; relics append.
 *
 * **Transport** (mirrors the command window — see BookOfCommandsPlugin/lofcommands): the item list is
 * streamed as [ChatMessageType.CONSOLE] lines prefixed [PREFIX]. CONSOLE is eviction-safe (its own
 * line buffer) and hidden from the chatbox by the client's block-only chatFilterCheck. The client
 * sends actions back as public-chat tokens `::spoils <action> [index]` that [MessagePublicHandler]
 * routes to the `spoilsclick` command (see [WarSpoilsPlugin]).
 *
 * Wire format (one line each):
 *   `FOV_SPOILS:open`                       (batch start; client resets its buffer)
 *   `FOV_SPOILS:item|<index>|<itemId>|<qty>`(repeated)
 *   `FOV_SPOILS:end`                        (commit + open the window)
 *   `FOV_SPOILS:toast|<text>`               (timed on-screen banner)
 *   `FOV_SPOILS:close`                      (server-driven close — the stash emptied)
 */
object WarSpoils {
    const val PREFIX = "FOV_SPOILS:"

    private val coinId by lazy { runCatching { getRSCM("item.coins_995") }.getOrDefault(995) }

    // ----------------------------- persistence -----------------------------

    fun load(p: Player): MutableList<Item> {
        val blob = p.attr[WAR_SPOILS_ATTR] ?: return mutableListOf()
        return runCatching {
            Document.parse(blob).getList("items", Document::class.java)
                .map { Item(it.getInteger("id"), it.getInteger("amount", 1)) }
                .toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun save(p: Player, items: List<Item>) {
        p.attr[WAR_SPOILS_ATTR] = Document(
            "items",
            items.filter { it.amount > 0 }.map { Document("id", it.id).append("amount", it.amount) },
        ).toJson()
    }

    fun isEmpty(p: Player): Boolean = load(p).isEmpty()

    /** Append [grant] to the stash; coins (995) merge into a single stack, everything else appends. */
    fun add(p: Player, grant: List<Item>) {
        if (grant.isEmpty()) return
        val items = load(p)
        var coins = items.filter { it.id == coinId }.sumOf { it.amount.toLong() }
        val others = items.filter { it.id != coinId }.toMutableList()
        for (it in grant) {
            if (it.amount <= 0) continue
            if (it.id == coinId) coins += it.amount else others.add(Item(it.id, it.amount))
        }
        val result = mutableListOf<Item>()
        if (coins > 0) result.add(Item(coinId, coins.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
        result.addAll(others)
        save(p, result)
    }

    // ------------------------------- claiming ------------------------------

    /** Move every spoil to the bank (or inventory). Items that don't fit stay sealed in the stash. */
    fun claimAll(p: Player, toBank: Boolean) {
        val items = load(p)
        if (items.isEmpty()) {
            p.message("You have no war spoils to claim.")
            return
        }
        val remaining = mutableListOf<Item>()
        var moved = 0
        for (it in items) {
            val tx = if (toBank) p.bank.add(item = it.id, amount = it.amount, assureFullInsertion = false)
            else p.inventory.add(item = it.id, amount = it.amount, assureFullInsertion = false)
            moved += tx.completed
            val left = it.amount - tx.completed
            if (left > 0) remaining.add(Item(it.id, left))
        }
        save(p, remaining)
        when {
            moved <= 0 -> p.message(if (toBank) "You need free bank space to claim your spoils." else "You need free inventory space to claim your spoils.")
            remaining.isNotEmpty() -> p.message("<col=ffd700>Some spoils wouldn't fit — they stay awaiting ::claim.</col>")
            else -> p.message("<col=ffd700>Your war spoils are ${if (toBank) "banked" else "claimed"}.</col>")
        }
    }

    /** Take ONE spoil (by current index) to the inventory (or bank). */
    fun claimOne(p: Player, index: Int, toBank: Boolean) {
        val items = load(p)
        val it = items.getOrNull(index) ?: return
        val tx = if (toBank) p.bank.add(item = it.id, amount = it.amount, assureFullInsertion = false)
        else p.inventory.add(item = it.id, amount = it.amount, assureFullInsertion = false)
        if (tx.completed <= 0) {
            p.message(if (toBank) "You need free bank space for that." else "You need free inventory space for that.")
            return
        }
        val left = it.amount - tx.completed
        if (left > 0) items[index] = Item(it.id, left) else items.removeAt(index)
        save(p, items)
    }

    // ------------------------------- transport -----------------------------

    /** Stream the pending spoils to the client and open the Spoils of War window. */
    fun open(p: Player) {
        val items = load(p)
        p.message("${PREFIX}open", ChatMessageType.CONSOLE)
        items.forEachIndexed { i, it -> p.message("$PREFIX" + "item|$i|${it.id}|${it.amount}", ChatMessageType.CONSOLE) }
        p.message("${PREFIX}end", ChatMessageType.CONSOLE)
    }

    /** Tell the client to close the window (the stash is now empty). */
    fun pushClose(p: Player) = p.message("${PREFIX}close", ChatMessageType.CONSOLE)

    /** Fire a timed on-screen banner ([text]) via the client overlay. */
    fun toast(p: Player, text: String) = p.message("$PREFIX" + "toast|$text", ChatMessageType.CONSOLE)

    /** After a claim action, refresh the open window — or close it if nothing remains. */
    fun refresh(p: Player) {
        if (isEmpty(p)) pushClose(p) else open(p)
    }

    // ---------------------------- war integration --------------------------

    /**
     * Grant a won war's commanding Minister/King their spoils: [coins] (war-stake return + tithe) plus,
     * when [relic] is true, a random 3rd age piece. Held for `::claim`; the commander is toasted and
     * told in chat. Announces the relic realm-wide (like the battlefield drop). No-op if nothing is owed.
     */
    fun deliverCommanderLoot(world: World, sponsor: Player, opName: String, coins: Long, relic: Boolean) {
        val grant = mutableListOf<Item>()
        if (coins > 0) grant.add(Item(coinId, coins.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
        var relicId = -1
        if (relic) {
            relicId = ThirdAge.randomPieceId()
            if (relicId >= 0) grant.add(Item(relicId, 1))
        }
        if (grant.isEmpty()) return
        add(sponsor, grant)
        if (relicId >= 0) ThirdAge.recordAndAnnounce(world, sponsor, relicId, "among the commander's spoils of $opName")

        val coinStr = if (coins > 0) "${"%,d".format(coins)} coins" else ""
        val relicStr = when {
            relicId < 0 -> ""
            coinStr.isEmpty() -> "a relic of the Third Age"
            else -> " and a relic of the Third Age"
        }
        sponsor.message("<col=ffd700>The spoils of $opName await you — $coinStr$relicStr. Type <col=0000ff>::claim</col><col=ffd700> to collect them.</col>")
        toast(sponsor, "Spoils of $opName await — ::claim")
    }
}
