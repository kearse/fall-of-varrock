package org.alter.plugins.content.companion

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ChatMessageType
import org.alter.api.EquipmentType
import org.alter.api.WeaponType
import org.alter.api.ext.calculateAndSetCombatLevel
import org.alter.api.ext.getVarp
import org.alter.api.ext.hasWeaponType
import org.alter.api.ext.message
import org.alter.api.ext.setVarbit
import org.alter.api.ext.setVarp
import org.alter.game.info.PlayerInfo
import org.alter.plugins.content.combat.strategy.magic.CombatSpell
import org.alter.plugins.content.interfaces.attack.AttackTab
import org.alter.game.model.PlayerUID
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.COMPANIONS_ATTR
import org.alter.game.model.attr.RESPAWN_TILE_ATTR
import org.alter.game.model.attr.TITLED_NAME_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.plugins.content.bots.BotManager
import org.alter.plugins.content.war.title

private val logger = KotlinLogging.logger {}

/**
 * Tracks every player's live [Companion]s (max [MAX]) and owns their lifecycle: recruit, login
 * respawn, logout store + despawn, and the per-tick brain drive. Persistence rides on a JSON blob
 * stored on the owner's `COMPANIONS_ATTR` (rebuilt on change + periodically so autosave catches it).
 */
object CompanionRegistry {
    const val MAX = 3
    private const val SAVE_EVERY = 30 // brain ticks between blob refreshes (~36s)
    /** Varps the server writes each tick with the owner's companion world-indices (+1; 0 = empty),
     *  so the "Fall of Varrock Companions" RuneLite plugin can find exactly THIS player's companions by index
     *  (name-independent). One varp per slot: 4610, 4611, 4612. */
    private const val COMP_VARP_BASE = 4610
    /** Status varps (4613, 4614, 4615) — a packed snapshot per companion so the panel shows them
     *  **always**, even when far away/un-rendered (the index varp only works when rendered).
     *  Layout: bit0 present | 1-6 nameIdx | 7-14 combat | 15-21 hp% | 22-24 orders | 25-26 style. */
    private const val COMP_STATUS_BASE = 4613

    /**
     * Rich-state channel for the RuneLite panel. Varps are range-limited (and were dropping for this
     * id range) AND far too small to carry gear, so the panel's real data rides a tagged **chat
     * message** instead: the client's companion plugin intercepts lines starting with [MSG_PREFIX],
     * parses the full per-companion state (stats + HP + orders + world-index + every equipment slot),
     * and suppresses them from the chatbox. Range-independent and unlimited — exactly what a gear UI needs.
     *
     * Wire format (one message per owner) — `~LOFCMP~<comp>~<comp>...` where each `<comp>` is four
     * `|`-delimited sections:
     *   1. META   `idx,name,arch,combat,curHp,maxHp,orders,styleVarp,autocast,autoLoot,retaliate,ammoId,ammoQty`
     *   2. SKILLS `atk,str,def,hp,range,mage,pray` (base/unboosted levels)
     *   3. EQUIP  `slot:itemId:qty;...` (may be empty)
     *   4. STYLES `label/label/...` — attack-style button labels for the companion's class (empty for mage)
     * `name` is the bare ≤12-char alphanumeric username; `autocast` is the effective spell enum name or `-`.
     */
    private const val MSG_PREFIX = "~LOFCMP~"
    private const val PUSH_EVERY = 3 // registry ticks between state pushes (~3.6s)
    private var pushTick = 0

    /** owner uid value -> their live companions. */
    private val byOwner = HashMap<Any, MutableList<Companion>>()
    /** owner uid value -> their tile last tick + their current facing (dx,dz), for the follow formation. */
    private val ownerLastTile = HashMap<Any, Tile>()
    private val ownerFacing = HashMap<Any, Pair<Int, Int>>()
    private var counter = 0

    fun ofOwner(player: Player): List<Companion> = byOwner[player.uid.value] ?: emptyList()
    fun count(player: Player): Int = ofOwner(player).size

    /** This companion's index (0..MAX-1) among its owner's roster, for its formation slot. */
    fun slotOf(comp: Companion): Int = (byOwner[comp.ownerUid.value]?.indexOf(comp) ?: 0).coerceAtLeast(0)

    /** How many companions the owner fields (drives the formation shape). */
    fun countOf(comp: Companion): Int = byOwner[comp.ownerUid.value]?.size ?: 1

    /** The owner's facing unit (dx,dz) — opposite is "behind", where the formation forms. */
    fun facingOf(comp: Companion): Pair<Int, Int> = ownerFacing[comp.ownerUid.value] ?: (0 to 1)

    /** The online owner of [comp], if present. */
    fun ownerOf(world: World, comp: Companion): Player? =
        world.players.firstOrNull { it.uid.value == comp.ownerUid.value }

    /**
     * How many companions [owner] may field — their feudal rank's allowance (Knight 1, Lord 2,
     * Minister/King 3; below Knight none), clamped to the hard [MAX]. Rank is bought from Duke
     * Horacio; existing rosters above a (never-decreasing) rank's allowance are kept, this only
     * gates NEW recruits.
     */
    fun companionCap(owner: Player): Int = minOf(MAX, owner.title.companions)

    /** Recruit a fresh (naked, level-1) companion of [archetype]. Returns null if at the rank cap. */
    fun recruit(world: World, owner: Player, archetype: CompanionStyle): Companion? {
        if (count(owner) >= companionCap(owner)) return null
        val taken = ofOwner(owner).map { it.username }
        val comp = spawn(world, owner, CompanionData.fresh(archetype, taken), owner.tile) ?: return null
        byOwner.getOrPut(owner.uid.value) { ArrayList() } += comp
        persist(owner)
        return comp
    }

    /**
     * Despawn EVERY companion currently registered in the world that belongs to [ownerValue]
     * (a `player.uid.value`). This is the **ghost-buster**: companions are tracked in [byOwner]
     * keyed by username, and a disconnect/relog race (or any logout where [storeAndDespawn] didn't
     * cleanly run) can leave the previous session's companions still registered in the world but no
     * longer ticked — frozen "ghosts" that stack up. Sweeping by [Companion.ownerUid] finds and
     * removes them no matter how they were orphaned. Returns the count cleared.
     */
    private fun despawnOwned(world: World, ownerValue: Any): Int {
        val stale = ArrayList<Companion>()
        world.players.forEach { p -> if (p is Companion && p.ownerUid.value == ownerValue) stale += p }
        stale.forEach { CompanionLoot.forget(it); BotManager.despawn(world, it, force = true) }
        return stale.size
    }

    /** Login: respawn the owner's roster beside them (dead companions wait at General Zo — Step 5). */
    fun spawnFor(world: World, player: Player) {
        // Ghost-buster: clear any companions this owner already has registered BEFORE respawning.
        // Overwriting byOwner below would otherwise orphan a lingering previous session's companions
        // (from a disconnect/relog race) into un-ticked ghosts. Sweep the world by ownerUid so they
        // can't survive — and drop any stale byOwner tracking entry too.
        byOwner.remove(player.uid.value)
        val ghosts = despawnOwned(world, player.uid.value)
        if (ghosts > 0) logger.warn { "spawnFor ${player.username}: cleared $ghosts stale companion ghost(s) before respawn." }

        val blob = player.attr[COMPANIONS_ATTR]
        val roster = CompanionData.rosterFromBlob(blob)
        logger.info { "spawnFor ${player.username} uid=${player.uid.value}: blobPresent=${blob != null}, rosterSize=${roster.size}" }
        if (roster.isEmpty()) return
        val list = ArrayList<Companion>()
        roster.forEach { data ->
            if (data.dead) return@forEach
            spawn(world, player, data, player.tile)?.let { list += it }
        }
        byOwner[player.uid.value] = list
        logger.info { "spawnFor ${player.username}: spawned ${list.size} companion(s), indices=${list.map { it.index }}, names=${list.map { it.username }}" }
    }

    /** Logout: write the roster blob (so it persists with the save), then despawn the companions. */
    fun storeAndDespawn(world: World, player: Player) {
        val list = byOwner.remove(player.uid.value)
        // Persist the roster blob FIRST, but never let a snapshot/encode failure skip the despawn
        // below — an orphaned-but-registered companion becomes a frozen ghost on the next login.
        if (list != null) {
            try {
                player.attr[COMPANIONS_ATTR] = CompanionData.rosterToBlob(list.map { snapshot(it) })
            } catch (e: Throwable) {
                logger.error(e) { "Companion roster snapshot failed on logout for ${player.username} (despawning anyway)" }
            }
        }
        // Despawn by ownerUid sweep (not just the tracked list) so nothing this player owns is left
        // registered in the world after they log out — the definitive ghost cleanup.
        despawnOwned(world, player.uid.value)
        val max = player.varps.maxVarps
        for (slot in 0 until MAX) { // clear the index + status varps (bounds-checked)
            if (COMP_VARP_BASE + slot < max) player.setVarp(COMP_VARP_BASE + slot, 0)
            if (COMP_STATUS_BASE + slot < max) player.setVarp(COMP_STATUS_BASE + slot, 0)
        }
    }

    private var publishLogTick = 0

    /** Publish [player]'s companions to the RuneLite panel: world-index (rendered menu-hide) +
     *  a packed status snapshot (always-display, distance-independent). Every setVarp is BOUNDS-CHECKED
     *  (`< maxVarps`) — `VarpSet.setState` indexes a fixed list, so an out-of-range id throws, and the
     *  whole thing is guarded so it can NEVER kill the companion tick (which would freeze all companions). */
    private fun publishToClient(player: Player) {
        val max = player.varps.maxVarps
        val list = byOwner[player.uid.value]
        val log = (++publishLogTick % 25) == 0
        if (log) logger.info { "publish ${player.username} uid=${player.uid.value}: max=$max listSize=${list?.size} byOwnerKeys=${byOwner.keys}" }
        try {
            for (slot in 0 until MAX) {
                val comp = list?.getOrNull(slot)
                val iv = COMP_VARP_BASE + slot
                val sv = COMP_STATUS_BASE + slot
                val ival = if (comp != null && comp.index >= 0) comp.index + 1 else 0
                val sval = if (comp != null) statusPack(comp) else 0
                if (iv < max) player.setVarp(iv, ival)
                if (sv < max) player.setVarp(sv, sval)
                if (log && comp != null) logger.info { "  slot $slot -> idxVarp[$iv]=$ival statusVarp[$sv]=$sval (comp=${comp.username} idx=${comp.index} cb=${comp.combatLevel} hp=${comp.getCurrentHp()}/${comp.getMaxHp()})" }
            }
        } catch (e: Throwable) {
            logger.error(e) { "Companion varp publish failed for ${player.username}" }
        }
    }

    /**
     * Push [player]'s full companion roster to the RuneLite panel over the [MSG_PREFIX] message
     * channel. **One message PER companion** (not one big concatenated line): a single line carrying
     * the whole roster + gear runs ~350+ chars and the rev-228 client silently DROPS chat messages
     * over its length cap — which is exactly why the panel went blank once the skill/gear sections were
     * added. Each per-companion line is short and safe.
     *
     * Wire (one message per companion): `~LOFCMP~<i>/<n>|META|SKILLS|EQUIP|STYLES`
     *   where i = roster index (0-based), n = roster size. An empty roster sends `~LOFCMP~0/0` so the
     *   panel clears. The client accumulates i=0..n-1 (sent together each push) into the card list.
     */
    private fun pushState(player: Player) {
        try {
            val list = byOwner[player.uid.value] ?: emptyList()
            if (list.isEmpty()) { player.message("${MSG_PREFIX}0/0", ChatMessageType.CONSOLE); return }
            val n = list.size
            list.forEachIndexed { i, comp ->
                val s = comp.getSkills()
                val ammo = comp.equipment[EquipmentType.AMMO.id]
                val ammoId = ammo?.id ?: comp.lastAmmoId
                val ammoQty = ammo?.amount ?: 0
                val autocast = if (comp.archetype == CompanionStyle.MAGE) CompanionMagic.effectiveSpell(comp).name else "-"
                val sb = StringBuilder(MSG_PREFIX).append(i).append('/').append(n).append('|')
                // META
                sb.append(comp.index).append(',')
                    .append(comp.username).append(',')
                    .append(comp.archetype.ordinal).append(',')
                    .append(comp.combatLevel).append(',')
                    .append(comp.getCurrentHp()).append(',')
                    .append(comp.getMaxHp()).append(',')
                    .append(comp.orders.ordinal).append(',')
                    .append(comp.getVarp(AttackTab.ATTACK_STYLE_VARP)).append(',')
                    .append(autocast).append(',')
                    .append(if (comp.autoLoot) 1 else 0).append(',')
                    .append(if (comp.getVarp(AttackTab.DISABLE_AUTO_RETALIATE_VARP) == 0) 1 else 0).append(',')
                    .append(ammoId).append(',')
                    .append(ammoQty).append('|')
                // SKILLS (base levels): atk,str,def,hp,range,mage,pray
                sb.append(s.getBaseLevel(0)).append(',')
                    .append(s.getBaseLevel(2)).append(',')
                    .append(s.getBaseLevel(1)).append(',')
                    .append(s.getBaseLevel(3)).append(',')
                    .append(s.getBaseLevel(4)).append(',')
                    .append(s.getBaseLevel(6)).append(',')
                    .append(s.getBaseLevel(5)).append('|')
                // EQUIP
                for (slot in 0 until comp.equipment.capacity) {
                    val item = comp.equipment[slot] ?: continue
                    sb.append(slot).append(':').append(item.id).append(':').append(item.amount).append(';')
                }
                sb.append('|')
                // STYLES (attack-style button labels for this companion's class)
                sb.append(styleLabels(comp).joinToString("/"))
                player.message(sb.toString(), ChatMessageType.CONSOLE)
            }
        } catch (e: Throwable) {
            logger.error(e) { "Companion state push failed for ${player.username}" }
        }
    }

    /** Force an immediate state push to [player]'s panel (call right after a gear/order change). */
    fun forcePush(player: Player) = pushState(player)

    /** Tag for the gear-picker reply channel (bank items that fit a slot). Mirrors the client parser. */
    private const val GEAR_PREFIX = "~LOFGEAR~"
    /** Max bank items per gear-list message — keeps each chat line short enough that the client accepts it. */
    private const val GEAR_CHUNK = 10

    /** The attack-style button labels the panel should show for [comp], by its combat class. */
    private fun styleLabels(comp: Companion): List<String> = when {
        comp.archetype == CompanionStyle.MAGE -> emptyList() // mage uses the autocast selector instead
        comp.hasWeaponType(WeaponType.BOW, WeaponType.CROSSBOW, WeaponType.THROWN) || comp.archetype == CompanionStyle.RANGE ->
            listOf("Accurate", "Rapid", "Longrange")
        else -> listOf("Accurate", "Aggressive", "Controlled", "Defensive")
    }

    /** Set a companion's attack-style varp (0-3) from the panel. */
    fun setStyle(player: Player, slot: Int, style: Int) {
        val comp = ofOwner(player).getOrNull(slot) ?: return
        comp.setVarp(AttackTab.ATTACK_STYLE_VARP, style.coerceIn(0, 3))
        persist(player); forcePush(player)
    }

    /** Set (or clear) a mage companion's autocast spell from the panel; "auto"/null restores level-scaling. */
    fun setSpell(player: Player, slot: Int, spellName: String?) {
        val comp = ofOwner(player).getOrNull(slot) ?: return
        if (comp.archetype != CompanionStyle.MAGE) { player.message("<col=801700>Only mage companions autocast.</col>"); return }
        comp.autocastSpell = spellName?.let { runCatching { CombatSpell.valueOf(it) }.getOrNull() }
        CompanionMagic.ensureSpell(comp)
        persist(player); forcePush(player)
        val label = CompanionMagic.effectiveSpell(comp).name.lowercase().replace('_', ' ')
        player.message("<col=4f9b4f>Sir ${comp.username} now autocasts $label${if (comp.autocastSpell == null) " (auto)" else ""}.</col>")
    }

    /** Toggle a companion's auto-retaliate from the panel. */
    fun toggleRetaliate(player: Player, slot: Int) {
        val comp = ofOwner(player).getOrNull(slot) ?: return
        val on = comp.getVarp(AttackTab.DISABLE_AUTO_RETALIATE_VARP) != 0 // currently disabled → turning on
        comp.setVarp(AttackTab.DISABLE_AUTO_RETALIATE_VARP, if (on) 0 else 1)
        persist(player); forcePush(player)
        player.message("<col=4f9b4f>Sir ${comp.username} will ${if (on) "now" else "no longer"} auto-retaliate.</col>")
    }

    /** Toggle a companion's auto-loot-to-bank perk from the panel. */
    fun toggleLoot(player: Player, slot: Int) {
        val comp = ofOwner(player).getOrNull(slot) ?: return
        comp.autoLoot = !comp.autoLoot
        persist(player); forcePush(player)
        player.message("<col=4f9b4f>Sir ${comp.username} will ${if (comp.autoLoot) "now auto-bank loot" else "no longer auto-bank loot"}.</col>")
    }

    /** Rename a companion (≤12 chars, letters/digits/spaces) from the panel. */
    fun rename(player: Player, slot: Int, raw: String) {
        val comp = ofOwner(player).getOrNull(slot) ?: return
        val clean = raw.trim().filter { it.isLetterOrDigit() || it == ' ' }.take(12)
        if (clean.isBlank()) { player.message("<col=801700>That name isn't allowed.</col>"); return }
        comp.username = clean
        comp.attr[org.alter.game.model.attr.TITLED_NAME_ATTR] = "<col=ff981f>Sir</col> $clean"
        PlayerInfo(comp).syncAppearance()
        persist(player); forcePush(player)
        player.message("<col=4f9b4f>Companion renamed to Sir $clean.</col>")
    }

    /**
     * Reply to the panel's gear-picker request: push every item in the owner's bank that fits
     * equipment [equipSlot] for the companion in [slot]. **Chunked** (≤[GEAR_CHUNK] items per message)
     * for the same reason [pushState] is per-companion — long chat lines are dropped by the client.
     * Wire: `~LOFGEAR~<slot>,<equipSlot>,<chunkIdx>,<last>~itemId:qty:wearable;...`. The client resets
     * its picker buffer on chunkIdx 0 and renders when last=1 (an empty result still sends one last=1).
     */
    fun pushGearList(player: Player, slot: Int, equipSlot: Int) {
        val comp = ofOwner(player).getOrNull(slot) ?: return
        try {
            val options = CompanionRange.gearOptions(player, comp, equipSlot)
            val chunks = if (options.isEmpty()) listOf(emptyList()) else options.chunked(GEAR_CHUNK)
            chunks.forEachIndexed { ci, chunk ->
                val last = if (ci == chunks.lastIndex) 1 else 0
                val sb = StringBuilder(GEAR_PREFIX)
                    .append(slot).append(',').append(equipSlot).append(',').append(ci).append(',').append(last).append('~')
                chunk.forEach { o -> sb.append(o.itemId).append(':').append(o.qty).append(':').append(if (o.wearable) 1 else 0).append(';') }
                player.message(sb.toString(), ChatMessageType.CONSOLE)
            }
        } catch (e: Throwable) {
            logger.error(e) { "Companion gear-list push failed for ${player.username}" }
        }
    }

    /** Update the owner's facing from their movement this tick (dominant cardinal axis). */
    private fun updateFacing(key: Any, owner: Player) {
        val last = ownerLastTile[key]
        if (last != null) {
            val dx = owner.tile.x - last.x
            val dz = owner.tile.z - last.z
            if (dx != 0 || dz != 0) {
                ownerFacing[key] = if (kotlin.math.abs(dx) >= kotlin.math.abs(dz)) {
                    (if (dx > 0) 1 else -1) to 0
                } else {
                    0 to (if (dz > 0) 1 else -1)
                }
            }
        }
        ownerLastTile[key] = owner.tile
    }

    private fun statusPack(comp: Companion): Int {
        val nameIdx = CompanionNames.indexOf(comp.username).coerceIn(0, 63)
        val level = comp.combatLevel.coerceIn(0, 255)
        val maxHp = comp.getMaxHp().coerceAtLeast(1)
        val hp = (comp.getCurrentHp() * 100 / maxHp).coerceIn(0, 100)
        val orders = comp.orders.ordinal and 0x7
        val style = comp.archetype.ordinal and 0x3
        return 1 or (nameIdx shl 1) or (level shl 7) or (hp shl 15) or (orders shl 22) or (style shl 25)
    }

    /** Rebuild + store the owner's blob from their live companions (call after any change). */
    fun persist(player: Player) {
        val list = byOwner[player.uid.value] ?: return
        player.attr[COMPANIONS_ATTR] = CompanionData.rosterToBlob(list.map { snapshot(it) })
    }

    private var sinceSave = 0

    /** Per-tick drive: prune the gone, track owner facing, run each companion's brain, refresh blobs. */
    fun tick(world: World) {
        byOwner.entries.forEach { (key, list) ->
            val before = list.size
            list.removeAll { if (it.index < 0) { CompanionLoot.forget(it); true } else false } // despawned (unregister sets index = -1); death handling = Step 5
            if (list.size != before) logger.warn { "Pruned ${before - list.size} despawned companion(s) for owner key=$key (now ${list.size})" }
            val owner = world.players.firstOrNull { it.uid.value == key }
            if (owner != null) {
                updateFacing(key, owner) // so the formation forms behind their movement
                // Die → respawn RIGHT BESIDE the owner (not the far global home tile, which left them
                // trudging back for minutes). PlayerDeathAction reads RESPAWN_TILE_ATTR; refresh it each
                // tick to the owner's current tile so a death warps them straight back to your side.
                val rt = owner.tile.coordinate
                list.forEach { it.attr[RESPAWN_TILE_ATTR] = rt }
            }
            list.toList().forEach { comp ->
                try {
                    CompanionBrain.tick(world, comp)
                } catch (e: Throwable) {
                    // never let a companion brain reach the game loop
                    logger.error(e) { "Companion brain tick failed (skipped)" }
                }
            }
        }
        // Publish each owner's companion roster (index + status) to their varps for the RuneLite panel.
        world.players.forEach { p -> if (byOwner.containsKey(p.uid.value)) publishToClient(p) }

        // Push the RICH state (stats + gear) over the message channel — throttled, the panel's real feed.
        if (++pushTick >= PUSH_EVERY) {
            pushTick = 0
            world.players.forEach { p -> if (byOwner.containsKey(p.uid.value)) pushState(p) }
        }

        // Refresh persistence blobs periodically so the 60s autosave / a crash keeps recent progress.
        if (++sinceSave >= SAVE_EVERY) {
            sinceSave = 0
            world.players.forEach { p -> if (byOwner.containsKey(p.uid.value)) persist(p) }
        }
    }

    /** Spawn one companion from its [data] at [tile], applying its real skills + gear. */
    private fun spawn(world: World, owner: Player, data: CompanionData, tile: Tile): Companion? {
        val comp = Companion(world, CompanionLoadouts.forArchetype(data.archetype), owner.uid, data.archetype)
        comp.username = data.name.take(12)
        // "Sir <Name>" with the rank in OSRS title-orange (the <col> tag rides the appearance name,
        // same trick the war Title system uses). The orange "Sir" is the tell that this is a companion.
        comp.attr[TITLED_NAME_ATTR] = "<col=ff981f>Sir</col> ${data.name}"
        comp.uid = PlayerUID("companion-${++counter}")
        if (!BotManager.bringIntoWorld(world, comp, tile)) {
            logger.warn { "Companion spawn for ${owner.username} failed (world full?)." }
            return null
        }
        // Real, persistent skills overwrite the vessel loadout's stub stats.
        data.skillXp.forEach { (skill, xp) -> comp.getSkills().setBaseXp(skill, xp) }
        applyContainers(comp, data)
        org.alter.plugins.content.bots.BotBrain.configureStyle(comp) // autocast/attack style for range/mage
        comp.setVarbit(org.alter.plugins.content.magic.MagicSpells.INF_RUNES_VARBIT, 1) // free spellcasting: no runes, level gate still applies
        // Re-apply the owner's saved preferences (panel-set), on top of the style defaults.
        comp.autocastSpell = data.autocast?.let { name -> runCatching { CombatSpell.valueOf(name) }.getOrNull() }
        comp.lastAmmoId = data.lastAmmoId
        if (data.attackStyle in 0..3) comp.setVarp(AttackTab.ATTACK_STYLE_VARP, data.attackStyle)
        comp.setVarp(AttackTab.DISABLE_AUTO_RETALIATE_VARP, if (data.autoRetaliate) 0 else 1)
        CompanionGear.restock(comp) // free starting food supply
        // Bonuses + appearance + the weapon-type varbit (357). Without the varbit a restored ranger/
        // mage reads as unarmed and getCombatClass() falls back to MELEE every relog/respawn.
        CompanionGear.refreshGear(comp)
        comp.calculateAndSetCombatLevel()
        comp.dead = data.dead
        comp.autoLoot = data.autoLoot
        comp.initiated = true
        return comp
    }

    private fun applyContainers(comp: Companion, data: CompanionData) {
        for (i in 0 until comp.equipment.capacity) comp.equipment[i] = null
        data.equipment.forEach { (slot, it) -> if (slot < comp.equipment.capacity) comp.equipment[slot] = Item(it.id, it.amount) }
        for (i in 0 until comp.inventory.capacity) comp.inventory[i] = null
        data.inventory.forEach { (slot, it) -> if (slot < comp.inventory.capacity) comp.inventory[slot] = Item(it.id, it.amount) }
    }

    /** Capture a live companion's current state for persistence. */
    private fun snapshot(comp: Companion): CompanionData {
        val xp = HashMap<Int, Double>()
        for (s in 0..6) xp[s] = comp.getSkills().getCurrentXp(s)
        val equip = HashMap<Int, CompItem>()
        for (i in 0 until comp.equipment.capacity) comp.equipment[i]?.let { equip[i] = CompItem(it.id, it.amount) }
        val inv = HashMap<Int, CompItem>()
        for (i in 0 until comp.inventory.capacity) comp.inventory[i]?.let { inv[i] = CompItem(it.id, it.amount) }
        return CompanionData(
            comp.archetype, comp.username, xp, equip, inv, comp.dead, comp.autoLoot,
            attackStyle = comp.getVarp(AttackTab.ATTACK_STYLE_VARP),
            autoRetaliate = comp.getVarp(AttackTab.DISABLE_AUTO_RETALIATE_VARP) == 0,
            autocast = comp.autocastSpell?.name,
            lastAmmoId = comp.lastAmmoId,
        )
    }
}
