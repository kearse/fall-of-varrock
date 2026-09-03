package org.alter.plugins.content.minigames.blastfurnace

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.EquipmentType
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.interfaces.bank.openBank
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * The Keldagrim Blast Furnace. One shared machine; every player's ore, coal and finished
 * bars are their own (the official-world feel). Put ore on the belt, the dwarves smelt one
 * bar every two ticks, take the bars from the dispenser — cooled by ice gloves / smiths gloves
 * or a bucket of water. The coffer draws 1,200 coins a minute while your ore is in the machine.
 */
class BlastFurnacePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private class Machine(val owner: Player) {
        /** Ores queued, in belt order (bar def + count). */
        val queue = ArrayList<Pair<BlastFurnace.Bar, Int>>()
        var coal = 0
        val ready = LinkedHashMap<BlastFurnace.Bar, Int>()
        var running = false
        var minuteTicks = 0
        fun oreCount() = queue.sumOf { it.second }
    }

    private val machines = HashMap<Player, Machine>()

    init {
        onWorldInit {
            runCatching {
                world.definitions.loadRegions(world, world.chunks, intArrayOf(BlastFurnace.REGION))
                world.spawn(DynamicObject(BlastFurnace.COFFER_ID, 10, 0, BlastFurnace.COFFER_TILE))
                BlastFurnace.DWARVES.forEach { (key, tile) ->
                    val n = Npc(getRSCM(key), world.snapToWalkable(tile, maxRadius = 2), world)
                    n.respawns = true
                    n.walkRadius = 2
                    world.spawn(n)
                }
                val foreman = Npc(getRSCM(BlastFurnace.FOREMAN_KEY), world.snapToWalkable(org.alter.game.model.Tile(1942, 4958, 0), maxRadius = 2), world)
                foreman.respawns = true
                foreman.walkRadius = 0
                world.spawn(foreman)
                logger.info { "blast-furnace: room armed — coffer at ${BlastFurnace.COFFER_TILE}, ${BlastFurnace.DWARVES.size} dwarves + the Foreman posted." }
            }.onFailure { logger.error(it) { "blast-furnace: world-init failed" } }
        }

        onObjOption(obj = BlastFurnace.BELT_KEY, option = "put-ore-on") { player.queue { putOre(player) } }
        onObjOption(obj = BlastFurnace.MELTING_POT_KEY, option = "check") { status(player) }
        // The dispenser is a multi-loc: the client sends the base id with the child's option slot (1 = Take, 2 = Check).
        runCatching { onObjOption(obj = BlastFurnace.DISPENSER_BASE_KEY, option = 1) { takeBars(player) } }.onFailure { logger.warn { "blast-furnace: dispenser slot 1 bind failed: ${it.message}" } }
        runCatching { onObjOption(obj = BlastFurnace.DISPENSER_BASE_KEY, option = 2) { status(player) } }.onFailure { logger.warn { "blast-furnace: dispenser slot 2 bind failed: ${it.message}" } }
        BlastFurnace.STOVE_KEYS.forEach { key -> runCatching { onObjOption(obj = key, option = "refuel") { player.message("Numpty keeps the stove fed — the dwarves run the machine.") } } }
        onObjOption(obj = BlastFurnace.PUMP_KEY, option = "operate") { player.message("Pumpy has the pump. The dwarves run the machine.") }
        onObjOption(obj = BlastFurnace.PEDALS_KEY, option = "pedal") { player.message("Thumpy's on the pedals. The dwarves run the machine.") }
        onObjOption(obj = BlastFurnace.GAUGE_KEY, option = "read") { player.message("The furnace is at working temperature — the dwarves keep it there.") }
        onObjOption(obj = BlastFurnace.COFFER_KEY, option = "use") { player.queue { coffer(player) } }
        onObjOption(obj = BlastFurnace.BANK_CHEST_KEY, option = "use") { player.openBank() }
        onNpcOption(npc = BlastFurnace.FOREMAN_KEY, option = "talk-to") { player.queue { foreman(player) } }
        onNpcOption(npc = BlastFurnace.FOREMAN_KEY, option = "pay") { player.queue { coffer(player) } }

        // coal bag
        listOf(BlastFurnace.COAL_BAG, BlastFurnace.OPEN_COAL_BAG).forEach { bag ->
            onItemOption(item = bag, option = "fill") { fillBag(player) }
            onItemOption(item = bag, option = "empty") { emptyBag(player) }
            onItemOption(item = bag, option = "check") { player.message("The coal bag holds ${player.attr[BlastFurnace.COAL_IN_BAG] ?: 0} coal.") }
        }
        onItemOption(item = BlastFurnace.COAL_BAG, option = "open") { swapBag(player, BlastFurnace.COAL_BAG, BlastFurnace.OPEN_COAL_BAG) }
        onItemOption(item = BlastFurnace.OPEN_COAL_BAG, option = "close") { swapBag(player, BlastFurnace.OPEN_COAL_BAG, BlastFurnace.COAL_BAG) }

        onCommand("bf", description = "Blast Furnace status") { status(player) }
        onLogout { machines.remove(player)?.let { m -> m.running = false; refundQueue(m) } }
    }

    // ───────────────────────────── belt ─────────────────────────────

    private suspend fun QueueTask.putOre(p: Player) {
        val coffer = p.attr[BlastFurnace.COFFER] ?: 0
        if (coffer < BlastFurnace.COFFER_PER_MINUTE) { p.message("The coffer is empty — deposit at least ${BlastFurnace.COFFER_MIN_DEPOSIT} coins (Use the coffer or Pay the Foreman) before the dwarves will run your ore."); return }
        val m = machines.getOrPut(p) { Machine(p) }
        if (m.oreCount() >= BlastFurnace.MAX_ORE) { p.message("The machine already holds ${BlastFurnace.MAX_ORE} of your ores. Collect some bars first."); return }
        val lvl = p.getSkills().getBaseLevel(Skills.SMITHING)
        val choices = BlastFurnace.BARS.filter { bar -> bar.ores.all { (ore, n) -> p.inventory.getItemCount(getRSCM(ore)) >= n } }
        if (choices.isEmpty()) { p.message("You have no ore to put on the belt."); return }
        val pick = if (choices.size == 1) choices[0] else {
            val page = choices.take(5)
            val i = options(p, *page.map { "${it.name} bar${if (it.coal > 0) " (${it.coal} coal each)" else ""}" }.toTypedArray(), title = "Put which ore on the belt?")
            page.getOrNull(i - 1) ?: return
        }
        if (lvl < pick.level) { p.message("You need a Smithing level of ${pick.level} to smelt ${pick.name.lowercase()} bars."); return }
        // How many can we load: limited by ore in pack, machine space, and coal available (pack + bag + machine store).
        val oreMax = pick.ores.minOf { (ore, n) -> p.inventory.getItemCount(getRSCM(ore)) / n }
        val space = BlastFurnace.MAX_ORE - m.oreCount()
        val coalId = getRSCM(BlastFurnace.COAL)
        val coalAvail = m.coal + p.inventory.getItemCount(coalId) + (p.attr[BlastFurnace.COAL_IN_BAG] ?: 0)
        val coalMax = if (pick.coal == 0) Int.MAX_VALUE else coalAvail / pick.coal
        val count = minOf(oreMax, space, coalMax)
        if (count <= 0) { p.message(if (pick.coal > 0) "You need ${pick.coal} coal for every ${pick.name.lowercase()} ore — use the coal bag or bring coal." else "Nothing to load."); return }
        pick.ores.forEach { (ore, n) -> p.inventory.remove(getRSCM(ore), n * count) }
        var coalNeeded = pick.coal * count
        // Draw coal: machine store first, then pack, then bag.
        val fromStore = minOf(m.coal, coalNeeded); m.coal -= fromStore; coalNeeded -= fromStore
        val fromPack = minOf(p.inventory.getItemCount(coalId), coalNeeded); if (fromPack > 0) { p.inventory.remove(coalId, fromPack); coalNeeded -= fromPack }
        val bag = p.attr[BlastFurnace.COAL_IN_BAG] ?: 0
        val fromBag = minOf(bag, coalNeeded); if (fromBag > 0) { p.attr[BlastFurnace.COAL_IN_BAG] = bag - fromBag; coalNeeded -= fromBag }
        // Any spare coal in the pack tops up the machine store (up to 254), as on the real belt.
        val spare = minOf(p.inventory.getItemCount(coalId), BlastFurnace.MAX_COAL - m.coal)
        if (spare > 0 && pick.coal > 0) { p.inventory.remove(coalId, spare); m.coal += spare }
        m.queue += pick to count
        p.animate(832)
        p.message("You put $count ${pick.name.lowercase()} ore on the belt${if (pick.coal > 0) " with ${pick.coal * count} coal" else ""}. The dwarves get to work.")
        if (!m.running) { m.running = true; world.queue { run(this, m) } }
    }

    private suspend fun run(task: QueueTask, m: Machine) {
        val p = m.owner
        while (m.running && m.queue.isNotEmpty()) {
            task.wait(BlastFurnace.TICKS_PER_BAR)
            if (p.index < 0) break
            m.minuteTicks += BlastFurnace.TICKS_PER_BAR
            if (m.minuteTicks >= 100) {
                m.minuteTicks = 0
                val coffer = p.attr[BlastFurnace.COFFER] ?: 0
                if (coffer < BlastFurnace.COFFER_PER_MINUTE) { p.message("<col=ff0000>The coffer has run dry — the dwarves down tools until you pay.</col>"); break }
                p.attr[BlastFurnace.COFFER] = coffer - BlastFurnace.COFFER_PER_MINUTE
            }
            val (bar, left) = m.queue[0]
            val readyNow = m.ready[bar] ?: 0
            if (readyNow >= BlastFurnace.MAX_BARS_PER_TYPE) { p.message("The dispenser is full of ${bar.name.lowercase()} bars — take them to keep the machine moving."); task.wait(20); continue }
            m.ready[bar] = readyNow + 1
            if (left <= 1) m.queue.removeAt(0) else m.queue[0] = bar to left - 1
            p.setVarbit(BlastFurnace.DISPENSER_VARBIT, BlastFurnace.DISPENSER_READY)
        }
        m.running = false
    }

    // ───────────────────────────── dispenser ─────────────────────────────

    private fun takeBars(p: Player) {
        val m = machines[p]
        if (m == null || m.ready.isEmpty()) { p.message("There are no bars of yours in the dispenser${if (m != null && m.queue.isNotEmpty()) " yet — the dwarves are still smelting" else ""}."); return }
        val cooled = p.equipment[EquipmentType.GLOVES.id]?.let { it.id == getRSCM(BlastFurnace.ICE_GLOVES) || it.id == runCatching { getRSCM(BlastFurnace.SMITHS_GLOVES_I) }.getOrNull() } ?: false
        if (!cooled) {
            val bucket = getRSCM(BlastFurnace.BUCKET_OF_WATER)
            if (p.inventory.contains(bucket)) {
                p.inventory.remove(bucket, 1)
                runCatching { p.inventory.add(getRSCM(BlastFurnace.BUCKET), 1) }
                p.message("You pour the water over the bars to cool them.")
            } else {
                p.message("<col=ff0000>The bars are far too hot to touch! Wear ice gloves or bring a bucket of water.</col>")
                p.hit(damage = 5, delay = 0)
                return
            }
        }
        val gauntlets = p.equipment[EquipmentType.GLOVES.id]?.id == getRSCM(BlastFurnace.GOLDSMITH_GAUNTLETS)
        var taken = 0
        var xp = 0.0
        val it = m.ready.entries.iterator()
        while (it.hasNext()) {
            val (bar, n) = it.next()
            val free = p.inventory.freeSlotCount
            if (free <= 0) break
            val take = minOf(n, free)
            p.inventory.add(getRSCM(bar.bar), take)
            xp += take * (if (gauntlets && bar.goldsmithXp > 0) bar.goldsmithXp else bar.xp)
            taken += take
            if (take >= n) it.remove() else m.ready[bar] = n - take
        }
        if (taken == 0) { p.message("Your pack is full."); return }
        p.addXp(Skills.SMITHING, xp)
        p.attr[BlastFurnace.BARS_MADE] = (p.attr[BlastFurnace.BARS_MADE] ?: 0) + taken
        p.animate(832)
        p.message("You take $taken bar${if (taken > 1) "s" else ""} from the dispenser.")
        if (m.ready.isEmpty()) p.setVarbit(BlastFurnace.DISPENSER_VARBIT, BlastFurnace.DISPENSER_EMPTY)
    }

    // ───────────────────────────── coffer / foreman ─────────────────────────────

    private suspend fun QueueTask.coffer(p: Player) {
        val have = p.attr[BlastFurnace.COFFER] ?: 0
        val coins = getRSCM("item.coins_995")
        chatNpc(p, "The coffer holds ${have} coins of yours. The furnace draws ${BlastFurnace.COFFER_PER_MINUTE} a minute<br>while your ore is in the machine (${BlastFurnace.COFFER_PER_MINUTE * 60} an hour). Minimum deposit ${BlastFurnace.COFFER_MIN_DEPOSIT}.", npc = getRSCM(BlastFurnace.FOREMAN_KEY), title = "Blast Furnace Foreman")
        when (options(p, "Deposit ${BlastFurnace.COFFER_MIN_DEPOSIT}", "Deposit 100,000", "Deposit 500,000", "Withdraw everything", "Nothing", title = "Coffer")) {
            1 -> deposit(p, BlastFurnace.COFFER_MIN_DEPOSIT)
            2 -> deposit(p, 100_000)
            3 -> deposit(p, 500_000)
            4 -> {
                if (have <= 0) { p.message("The coffer holds nothing of yours."); return }
                val added = p.inventory.add(item = coins, amount = have, assureFullInsertion = false)
                p.attr[BlastFurnace.COFFER] = have - added.completed
                p.message("You withdraw ${added.completed} coins.")
            }
        }
    }

    private fun deposit(p: Player, amount: Int) {
        val coins = getRSCM("item.coins_995")
        val have = p.attr[BlastFurnace.COFFER] ?: 0
        if (have + amount > BlastFurnace.COFFER_MAX) { p.message("The coffer can't hold more than ${BlastFurnace.COFFER_MAX} coins."); return }
        if (p.inventory.getItemCount(coins) < amount) { p.message("You don't have $amount coins."); return }
        p.inventory.remove(coins, amount)
        p.attr[BlastFurnace.COFFER] = have + amount
        p.message("You deposit $amount coins. The coffer now holds ${have + amount}.")
    }

    private suspend fun QueueTask.foreman(p: Player) {
        val id = getRSCM(BlastFurnace.FOREMAN_KEY)
        chatNpc(p, "Welcome to the Blast Furnace. My dwarves run the machine —<br>you run the coffer: ${BlastFurnace.COFFER_PER_MINUTE * 60} coins an hour while your ore is in.<br>Ore on the belt, bars from the dispenser. Mind the heat.", npc = id, title = "Blast Furnace Foreman")
        chatNpc(p, "Half the coal of a normal furnace: steel 1, mithril 2, adamant 3, rune 4.<br>${BlastFurnace.MAX_ORE} ores and ${BlastFurnace.MAX_COAL} coal in the machine at once, ${BlastFurnace.MAX_BARS_PER_TYPE} bars<br>of a kind in the dispenser. Ice gloves or a bucket of water for the bars.", npc = id, title = "Blast Furnace Foreman")
    }

    // ───────────────────────────── coal bag ─────────────────────────────

    private fun fillBag(p: Player) {
        val coalId = getRSCM(BlastFurnace.COAL)
        val inBag = p.attr[BlastFurnace.COAL_IN_BAG] ?: 0
        val n = minOf(p.inventory.getItemCount(coalId), BlastFurnace.COAL_BAG_CAP - inBag)
        if (n <= 0) { p.message(if (inBag >= BlastFurnace.COAL_BAG_CAP) "The coal bag is full." else "You have no coal to put in the bag."); return }
        p.inventory.remove(coalId, n)
        p.attr[BlastFurnace.COAL_IN_BAG] = inBag + n
        p.message("You put $n coal in the bag (${inBag + n}/${BlastFurnace.COAL_BAG_CAP}).")
    }

    private fun emptyBag(p: Player) {
        val coalId = getRSCM(BlastFurnace.COAL)
        val inBag = p.attr[BlastFurnace.COAL_IN_BAG] ?: 0
        if (inBag <= 0) { p.message("The coal bag is empty."); return }
        val n = minOf(inBag, p.inventory.freeSlotCount)
        if (n <= 0) { p.message("Your pack is full."); return }
        p.inventory.add(coalId, n)
        p.attr[BlastFurnace.COAL_IN_BAG] = inBag - n
        p.message("You take $n coal from the bag (${inBag - n} left).")
    }

    private fun swapBag(p: Player, from: String, to: String) {
        val f = getRSCM(from); val t = getRSCM(to)
        if (p.inventory.remove(f, 1).hasSucceeded()) p.inventory.add(t, 1)
    }

    // ───────────────────────────── misc ─────────────────────────────

    private fun refundQueue(m: Machine) {
        // Ore still on the belt at logout goes to the bank so nothing is lost.
        m.queue.forEach { (bar, n) -> bar.ores.forEach { (ore, k) -> m.owner.bank.add(getRSCM(ore), n * k) } }
        m.ready.forEach { (bar, n) -> m.owner.bank.add(getRSCM(bar.bar), n) }
        if (m.coal > 0) m.owner.bank.add(getRSCM(BlastFurnace.COAL), m.coal)
        m.queue.clear(); m.ready.clear(); m.coal = 0
    }

    private fun status(p: Player) {
        val m = machines[p]
        val queued = m?.queue?.joinToString(", ") { "${it.second} ${it.first.name.lowercase()}" }?.ifEmpty { "nothing" } ?: "nothing"
        val ready = m?.ready?.entries?.joinToString(", ") { "${it.value} ${it.key.name.lowercase()}" }?.ifEmpty { "none" } ?: "none"
        p.message("<col=0000ff>Blast Furnace:</col> coffer ${p.attr[BlastFurnace.COFFER] ?: 0} coins | on the belt: $queued | coal in machine ${m?.coal ?: 0} | bars ready: $ready | coal bag ${p.attr[BlastFurnace.COAL_IN_BAG] ?: 0} | bars made ${p.attr[BlastFurnace.BARS_MADE] ?: 0}.")
    }
}
