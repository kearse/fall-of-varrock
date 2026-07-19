package org.alter.plugins.content.minigames.pktraining

import dev.openrune.cache.CacheManager.getNpc
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.EquipmentType
import org.alter.api.Skills
import org.alter.api.Spellbook
import org.alter.api.cfg.Varbit
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.info.PlayerInfo
import org.alter.game.model.Area
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.PK_ARENA_STASH_ATTR
import org.alter.game.model.attr.RESPAWN_TILE_ATTR
import org.alter.game.model.attr.SPAR_BOT_ATTR
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.game.model.move.moveTo
import org.alter.game.model.queue.QueueTask
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bots.BotLoadout
import org.alter.plugins.content.bots.BotLoadouts
import org.alter.plugins.content.bots.BotManager
import org.alter.plugins.content.bots.PkBot
import org.alter.plugins.content.combat.SafeDeaths
import org.alter.plugins.content.combat.getCombatTarget
import org.alter.plugins.content.interfaces.attack.AttackTab
import org.alter.plugins.content.kits.KitArmoury
import org.alter.plugins.content.kits.KitEditor
import org.alter.plugins.content.kits.KitSetup
import org.alter.plugins.content.war.WarNpcNames
import org.alter.rscm.RSCM.getRSCM
import org.bson.Document

private val logger = KotlinLogging.logger {}

/**
 * **PK Training Arena** (Duel Arena) — a *sparring ground*, not Last Man Standing.
 *
 * A battle-scarred mercenary trainer stands at the Duel Arena and teaches the fundamentals of PKing
 * (1-ticking, spec timing, PID, prayer/gear switching, vengeance). Talk to him to open the **kit
 * locker** — the LMS-style [KitEditor]: start from a **Dharok's** or **NH tribrid** preset, edit
 * any slot from the armoury, save up to three custom kits — and be **loaned** the built kit; or
 * **bring your own gear**. Then he summons a **matching sparring bot** (a real [PkBot] fake-player
 * with the full NH brain) at your chosen difficulty so there is *always* someone to fight, even at
 * zero players online.
 *
 * Design guarantees:
 *  - **You keep nothing.** Loaner gear is handed back the instant you leave the arena / log out / pick
 *    a different kit. Your real inventory + equipment + spellbook are stashed to a persistent blob
 *    ([PK_ARENA_STASH_ATTR]) first, so even a server crash mid-session restores your real gear on the
 *    next login (the companion-save pattern). No item can be duped or walked out.
 *  - **No item loss, full XP.** The whole arena is a [SafeDeaths] zone (also covers the bots), so a
 *    training death drops nothing; combat XP accrues normally against the bot.
 *  - **No gear faucet.** Sparring bots carry [SPAR_BOT_ATTR]; the bot-combat plugin skips its usual
 *    "drop the whole kit to the killer" death handling for them, so you can farm a bot for practice
 *    forever without minting a single item.
 *  - **You keep your real levels** while kitted (so you gain XP), with one training affordance: Magic
 *    is boosted so anyone can practise Vengeance regardless of level, and the Lunar book is set for you.
 *
 * Entry: talk to the trainer, or `::pktrain` (teleports you to him).
 */
class PkTrainingArenaPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    /** Difficulty band the trainee chose for the sparring bot. */
    private enum class Diff { EASY, MEDIUM, HARD }

    /** One trainee's live session — their chosen kit/difficulty and their current sparring bot. */
    private class Session(val player: Player) {
        /** The loaner kit built in the kit editor, or null = bring-your-own gear. */
        var custom: KitSetup? = null
        var diff: Diff = Diff.MEDIUM
        var loadout: BotLoadout = BotLoadouts.CLASSIC_HYBRID
        var bot: PkBot? = null
        var fighting = false // false during the countdown; true once "FIGHT!" is called
        var countdown = 0    // arena-ticks left before the fight starts
        // The player's own respawn override, saved so the arena's EXIT-respawn can be undone for
        // BRING_OWN fighters (loaner kits restore theirs from the persistent stash instead).
        var respawnSaved = false
        var prevRespawn: Int? = null
    }

    private val sessions = mutableListOf<Session>()
    private val arenaTimer = TimerKey()

    init {
        // Deaths anywhere in the arena are safe by design (covers trainees AND sparring bots).
        SafeDeaths.register(ARENA)

        onWorldInit {
            // Force-load the trainer's region so he spawns at boot, before any player visits — and
            // spawn him DIRECTLY (the KotlinPlugin.spawnNpc queue is drained before onWorldInit runs;
            // the Fight Cave / Wizard Tower lesson). A wrong id degrades gracefully, never a boot drop.
            runCatching { world.definitions.loadRegions(world, world.chunks, intArrayOf(TRAINER_REGION)) }
                .onFailure { logger.warn { "pktrain: region force-load failed: ${it.message}" } }
            runCatching {
                val n = Npc(getRSCM(TRAINER_NPC), TRAINER_TILE, world)
                n.walkRadius = 0
                n.lastFacingDirection = Direction.WEST
                world.spawn(n)
                n.setActive(true)
                WarNpcNames.rename(n, TRAINER_NAME) // client-side display name; base model unchanged
            }.onFailure { logger.warn { "pktrain: trainer '$TRAINER_NPC' not spawned: ${it.message}" } }

            world.timers[arenaTimer] = ARENA_TICK
        }

        // Bind the trainer's Talk-to only if his cache def actually has it (else ::pktrain is the entry).
        bindTrainer()

        onCommand("pktrain", description = "Teleport to the PK Training Arena trainer") {
            player.moveTo(Tile(3368, 3269, 0))
            player.message("<col=801700>The mercenary eyes you.</col> Talk to him to start training.")
        }
        // Escape hatch: hand the loaner kit back and end the bout without walking out.
        onCommand("unkit", description = "Hand back the training kit and end the bout") {
            if (sessionOf(player) == null) player.message("You're not training right now.")
            else { endBout(player); player.message("You hand your training kit back.") }
        }

        // ── crash recovery: a stash blob still present at login means we owe a real-gear restore ──
        onLogin {
            if (player.attr[PK_ARENA_STASH_ATTR] != null) {
                restoreLoaner(player)
                player.moveTo(world.gameContext.home)
                player.message("<col=801700>Your gear was returned from the training arena.</col>")
            }
        }

        // Clean logout: hand the kit back (so the save writes real gear) and remove the bot. The
        // persistent stash is the backstop for the un-clean case (crash/kill) handled by onLogin.
        onLogout { sessionOf(player)?.let { endBout(player) } }

        // A death ends the round, exactly like a real duel: the trainee's death is safe (kept items)
        // and RESPAWN_TILE_ATTR lands them back at the trainer; a sparring bot's death is owned here
        // (no drop) and sends the trainee back too. Either way — talk to the trainer to go again.
        onPlayerDeath {
            val bot = player as? PkBot
            if (bot != null && bot.attr[SPAR_BOT_ATTR] == true) {
                sessionOfBot(bot)?.let { endRound(it, won = true) } ?: BotManager.despawn(world, bot)
                return@onPlayerDeath
            }
            sessionOf(player)?.let { s -> if (s.fighting || s.bot != null) endRound(s, won = false) }
        }

        onTimer(arenaTimer) {
            sessions.toList().forEach { s ->
                runCatching { tick(s) }.onFailure { e -> logger.error(e) { "pktrain session tick failed" } }
            }
            world.timers[arenaTimer] = ARENA_TICK
        }
    }

    // ───────────────────────────── session upkeep ─────────────────────────────

    private fun sessionOf(p: Player): Session? = sessions.firstOrNull { it.player === p }
    private fun sessionOfBot(b: PkBot): Session? = sessions.firstOrNull { it.bot === b }

    private fun tick(s: Session) {
        val p = s.player
        if (p.index < 0) { hardCleanup(s); return } // vanished without a clean logout
        // Walked/teleported out of the arena → hand the kit back and drop the bot.
        if (!ARENA.contains(p.tile)) { endBout(p); return }
        val bot = s.bot ?: return // between rounds — waiting at the trainer

        // Countdown phase: the bot stands across from you, passive, until "FIGHT!".
        if (!s.fighting) {
            if (bot.index < 0 || !bot.isAlive()) { endRound(s, won = true); return }
            s.countdown--
            when {
                s.countdown > 0 -> p.message("<col=ff0000>${s.countdown}...</col>")
                s.countdown == 0 -> {
                    s.fighting = true
                    p.message("<col=ff0000>FIGHT!</col>")
                    bot.ambushEverywhere = true // arm the brain (safe-tile aggro) only now
                    bot.attack(p)
                }
            }
            return
        }

        // Fight phase: if the bot vanished without a death event, count it as a win; else keep it engaged.
        if (bot.index < 0 || !bot.isAlive()) { endRound(s, won = true); return }
        if (bot.getCombatTarget() == null) bot.attack(p) // re-aggro if it lost its target
    }

    // ───────────────────────────── entry / dialogue ─────────────────────────────

    private fun bindTrainer() {
        val acts = runCatching { getNpc(getRSCM(TRAINER_NPC)).actions.filterNotNull().filter { it.isNotBlank() } }
            .getOrDefault(emptyList())
        acts.filter { it.equals("talk-to", true) || it.equals("talk", true) }.forEach { act ->
            onNpcOption(TRAINER_NPC, option = act) { player.queue { trainerDialog(player) } }
        }
        if (acts.none { it.equals("talk-to", true) || it.equals("talk", true) }) {
            logger.warn { "pktrain: trainer '$TRAINER_NPC' cache def has no Talk-to (actions=$acts) — use ::pktrain." }
        }
    }

    private suspend fun QueueTask.trainerDialog(p: Player) {
        val id = runCatching { getRSCM(TRAINER_NPC) }.getOrDefault(-1)

        // Between rounds: one click back onto the sand (same kit & difficulty), like a rematch.
        // The kit was already returned at round end — the rematch goes through startTraining so a
        // fresh kit is applied (and the real gear re-stashed) for the new round.
        val s = sessionOf(p)
        if (s != null && s.bot == null) {
            chatNpc(p, "Back for more? Good. Same setup, or something new?", npc = id, title = TRAINER_NAME)
            when (options(p, "Another round — same setup.", "Change my setup.", "I'm done.", "Nothing.")) {
                1 -> { startTraining(p, s.custom, s.diff); return }
                2 -> { /* fall through to the full menu below */ }
                3 -> { endBout(p); chatNpc(p, "Wise to know when to stop. Come back when your blood's up.", npc = id, title = TRAINER_NAME); return }
                else -> return
            }
        } else {
            chatNpc(p, "So you want to learn to PK. Good. In here nothing you touch is yours to keep, and dying costs you nothing — so stop being precious and fight.", npc = id, title = TRAINER_NAME)
        }
        when (options(p,
            "Open the kit locker.",
            "I'll bring my own gear.",
            "How does this work?",
            "Not now.",
        )) {
            1 -> openKitLocker(p)
            2 -> chooseDifficulty(p)
            3 -> {
                chatNpc(p, "My kit locker has the lot — Dharok's, full NH switch sets, or build your own loadout piece by piece and save it. I hand it to you and set a sparring partner on you. Beat him, die to him, doesn't matter. Learn the tempo.", npc = id, title = TRAINER_NAME)
                chatNpc(p, "One-tick your combos. Switch off his prayer. Time your spec. Vengeance when he specs you. When you're done, walk out — the gear stays with me.", npc = id, title = TRAINER_NAME)
            }
            4 -> chatPlayer(p, "Maybe later.")
        }
    }

    /** Open the kit editor in TRAINING mode — "Start bout" loans the built kit and starts the fight. */
    private fun openKitLocker(p: Player) {
        if (KitEditor.isOpen(p)) return
        KitEditor.open(p, KitEditor.Mode.TRAINING, onStart = { kit, diffIndex ->
            // The editor is an overlay, so the player can wander while it's open — a bout only
            // starts if they're still on the arena grounds (no teleport-from-anywhere entry).
            if (!ARENA.contains(p.tile)) {
                p.message("Come back to the arena when you're ready — $TRAINER_NAME keeps your kit warm.")
            } else {
                val diff = when (diffIndex) { 0 -> Diff.EASY; 2 -> Diff.HARD; else -> Diff.MEDIUM }
                startTraining(p, kit, diff)
            }
        })
    }

    /** Bring-your-own path — no kit screen, just pick the sparring partner's difficulty. */
    private suspend fun QueueTask.chooseDifficulty(p: Player) {
        val diff = when (options(p, "Easy — a beginner (won't pray, panics early).", "Medium — a solid PKer.", "Hard — a maxed sweat.", "Back.")) {
            1 -> Diff.EASY
            2 -> Diff.MEDIUM
            3 -> Diff.HARD
            else -> return
        }
        startTraining(p, custom = null, diff = diff)
    }

    // ───────────────────────────── training lifecycle ─────────────────────────────

    private fun startTraining(p: Player, custom: KitSetup?, diff: Diff) {
        // Undo any session (BYO) respawn override BEFORE stashing a kit, so the stash captures the
        // player's REAL respawn — not the arena's exit tile from an earlier bring-your-own round.
        sessionOf(p)?.let { restoreSessionRespawn(p, it) }
        if (custom == null) {
            // "Bring your own" fights in real gear — if they were previously loaned a kit, give their
            // real gear back first so they aren't sparring in borrowed armour.
            if (p.attr[PK_ARENA_STASH_ATTR] != null) restoreLoaner(p)
        } else {
            applyLoaner(p, custom)
        }
        startBout(p, custom, diff)
        p.message("<col=801700>Train hard.</col> Your kit is loaned — you keep nothing. Leave the arena or ::unkit to hand it back.")
    }

    /** Start one bout, duel-style: teleport in, face your opponent across the floor, countdown, fight. */
    private fun startBout(p: Player, custom: KitSetup?, diff: Diff) {
        val s = sessionOf(p) ?: Session(p).also { sessions += it }
        s.custom = custom
        s.diff = diff
        s.loadout = botLoadout(custom, diff)
        s.bot?.let { BotManager.despawn(world, it) } // clear any existing partner before the new one

        // Respawn at the trainer while training (a death mid-bout lands you back at the exit, not
        // your home city). Loaner kits already stashed the real override; save it here for BYO.
        if (p.attr[PK_ARENA_STASH_ATTR] == null && !s.respawnSaved) {
            s.prevRespawn = p.attr[RESPAWN_TILE_ATTR]
            s.respawnSaved = true
        }
        p.attr[RESPAWN_TILE_ATTR] = EXIT_TILE.coordinate

        // Into the arena, opponent across the floor — just like a real duel.
        p.moveTo(BOUT_PLAYER_TILE)
        p.setCurrentHp(p.getMaxHp())
        AttackTab.setEnergy(p, 100)
        s.bot = spawnSparBot(p, s.loadout, BOUT_BOT_TILE)
        s.fighting = false
        s.countdown = COUNTDOWN_STEPS
        TrainingArena.setInBout(p, true) // companions stand down while the bout runs
        p.message("<col=801700>Your opponent steps onto the sand...</col>")
    }

    /** Spawn the sparring bot at its corner, PASSIVE — it sizes you up until the countdown ends. */
    private fun spawnSparBot(p: Player, loadout: BotLoadout, tile: Tile): PkBot? {
        val bot = BotManager.spawn(world, loadout, tile) ?: run {
            logger.warn { "pktrain: sparring bot spawn failed for ${p.username}" }; return null
        }
        bot.attr[SPAR_BOT_ATTR] = true
        bot.ambushEverywhere = false // passive during the countdown; armed at "FIGHT!" (see tick)
        bot.homeTile = tile
        bot.leashRadius = ARENA_LEASH // don't let it chase a fleeing trainee out of the arena
        bot.roamRadius = 0
        // A bot is a real Player, so its display name is its username (not an NPC name-change).
        bot.username = SPAR_BOT_NAME
        PlayerInfo(bot).syncAppearance()
        bot.facePawn(p)
        return bot
    }

    /**
     * The round is over (someone died) — despawn the bot, send the trainee back to the trainer
     * healed, and **take the loaner kit back immediately** (LMS-style). A kit only ever exists
     * mid-fight inside the pit: between rounds the trainee stands in their REAL gear, so there is
     * no window to walk off with (or stash away) borrowed wealth. "Another round" re-kits fresh.
     * The session itself is kept so the rematch remembers the setup.
     */
    private fun endRound(s: Session, won: Boolean) {
        s.bot?.let { if (it.index >= 0) BotManager.despawn(world, it) }
        s.bot = null
        s.fighting = false
        val p = s.player
        TrainingArena.setInBout(p, false)
        if (p.index < 0) return
        p.moveTo(EXIT_TILE)
        if (p.attr[PK_ARENA_STASH_ATTR] != null) restoreLoaner(p) // the kit goes straight back
        p.setCurrentHp(p.getMaxHp())
        p.message(
            if (won) "<col=007f00>Your opponent falls — the bout is yours!</col> The kit returns to $TRAINER_NAME; talk to him to go again."
            else "<col=ff0000>You were defeated.</col> The kit returns to $TRAINER_NAME; talk to him to go again.",
        )
    }

    private fun endBout(p: Player) {
        val s = sessionOf(p) ?: return
        s.bot?.let { BotManager.despawn(world, it) }
        sessions.remove(s)
        TrainingArena.setInBout(p, false)
        if (p.attr[PK_ARENA_STASH_ATTR] != null) {
            restoreLoaner(p) // restores the real respawn override from the stash too
        } else {
            restoreSessionRespawn(p, s)
        }
    }

    /** Undo the arena's EXIT-respawn override for a BRING_OWN fighter (loaner kits use the stash). */
    private fun restoreSessionRespawn(p: Player, s: Session) {
        if (!s.respawnSaved) return
        s.prevRespawn?.let { p.attr[RESPAWN_TILE_ATTR] = it } ?: p.attr.remove(RESPAWN_TILE_ATTR)
        s.respawnSaved = false
        s.prevRespawn = null
    }

    /** Player logged out / vanished mid-bout — drop the bot; gear restore is handled by onLogout/onLogin. */
    private fun hardCleanup(s: Session) {
        s.bot?.let { BotManager.despawn(world, it) }
        sessions.remove(s)
        TrainingArena.setInBout(s.player, false)
    }

    // ───────────────────────────── loaner kit (stash / apply / restore) ─────────────────────────────

    /**
     * Dress the player in a loaner [kit] (built in the kit editor). Their REAL inventory/equipment/
     * spellbook/respawn/Magic-level are stashed FIRST (only if not already — so switching kits
     * mid-session doesn't stash loaner gear), so nothing is lost. The kit's chosen spellbook is set;
     * Lunar boosts Magic so anyone can practise Vengeance.
     */
    private fun applyLoaner(p: Player, kit: KitSetup) {
        stashIfNeeded(p)
        wipeContainers(p)
        kit.gear.forEach { (slotId, item) ->
            if (slotId < p.equipment.capacity) p.equipment[slotId] = Item(item.id, item.amount)
        }
        kit.inv.forEach { (slot, item) ->
            if (slot < p.inventory.capacity) p.inventory[slot] = Item(item.id, item.amount)
        }
        p.calculateBonuses()
        val weapon = p.equipment[EquipmentType.WEAPON.id]
        p.setVarbit(Varbit.WEAPON_TYPE_VARBIT, if (weapon != null) weapon.getDef().weaponType else 0)
        PlayerInfo(p).syncAppearance()
        val book = when (kit.book) {
            KitSetup.BOOK_ANCIENTS -> Spellbook.ANCIENTS
            KitSetup.BOOK_LUNAR -> Spellbook.LUNAR
            else -> Spellbook.NORMAL
        }
        p.setSpellbook(book)
        if (book == Spellbook.LUNAR && p.getSkills().getCurrentLevel(Skills.MAGIC) < VENG_MAGIC) {
            p.getSkills().setCurrentLevel(Skills.MAGIC, VENG_MAGIC) // training affordance: veng at any level
        }
        // (The EXIT-tile respawn override is set per-bout in startBout; the stash above already
        // captured the player's real override for restore.)
        p.setCurrentHp(p.getMaxHp())
        AttackTab.setEnergy(p, 100)
    }

    private fun stashIfNeeded(p: Player) {
        if (p.attr[PK_ARENA_STASH_ATTR] != null) return
        val doc = Document()
        doc.append("inv", itemsDoc(p.inventory.rawItemsSnapshot()))
        doc.append("equip", itemsDoc(p.equipment.rawItemsSnapshot()))
        doc.append("book", p.getSpellbook().id)
        doc.append("magic", p.getSkills().getCurrentLevel(Skills.MAGIC))
        p.attr[RESPAWN_TILE_ATTR]?.let { doc.append("respawn", it) }
        p.attr[PK_ARENA_STASH_ATTR] = doc.toJson()
    }

    /** Restore the player's real gear/spellbook/respawn from the stash blob and clear it. No-op if none. */
    private fun restoreLoaner(p: Player) {
        val blob = p.attr[PK_ARENA_STASH_ATTR] ?: return
        wipeContainers(p)
        runCatching {
            val doc = Document.parse(blob)
            itemsFrom(doc, "inv").forEach { (idx, it) -> if (idx < p.inventory.capacity) p.inventory[idx] = it }
            itemsFrom(doc, "equip").forEach { (idx, it) -> if (idx < p.equipment.capacity) p.equipment[idx] = it }
            val book = Spellbook.values.firstOrNull { it.id == doc.getInteger("book", 0) } ?: Spellbook.NORMAL
            p.setSpellbook(book)
            p.getSkills().setCurrentLevel(Skills.MAGIC, doc.getInteger("magic", p.getSkills().getBaseLevel(Skills.MAGIC)))
            if (doc.containsKey("respawn")) p.attr[RESPAWN_TILE_ATTR] = doc.getInteger("respawn")
            else p.attr.remove(RESPAWN_TILE_ATTR)
        }.onFailure { logger.error(it) { "pktrain: failed to restore stash for ${p.username} — blob kept for retry" }; return }
        p.calculateBonuses()
        val weapon = p.equipment[EquipmentType.WEAPON.id]
        p.setVarbit(Varbit.WEAPON_TYPE_VARBIT, if (weapon != null) weapon.getDef().weaponType else 0)
        PlayerInfo(p).syncAppearance()
        p.attr.remove(PK_ARENA_STASH_ATTR)
    }

    private fun wipeContainers(p: Player) {
        for (i in 0 until p.inventory.capacity) p.inventory[i] = null
        for (i in 0 until p.equipment.capacity) p.equipment[i] = null
    }

    // ─── bson item (de)serialization — mirrors CompanionData so a bad blob never throws on login ───

    private fun itemsDoc(items: Map<Int, Item>): Document = Document().also { d ->
        items.forEach { (slot, it) -> d.append(slot.toString(), Document("id", it.id).append("amount", it.amount)) }
    }

    private fun itemsFrom(doc: Document, key: String): Map<Int, Item> {
        val out = HashMap<Int, Item>()
        doc.get(key, Document::class.java)?.forEach { (slot, v) ->
            val d = v as Document
            out[slot.toInt()] = Item(d.getInteger("id"), d.getInteger("amount", 1))
        }
        return out
    }

    /** Snapshot a container's non-empty slots as slot→Item (an [Item] copy per occupied slot). */
    private fun org.alter.game.model.container.ItemContainer.rawItemsSnapshot(): Map<Int, Item> {
        val out = HashMap<Int, Item>()
        for (i in 0 until capacity) this[i]?.let { out[i] = Item(it.id, it.amount) }
        return out
    }

    // ───────────────────────────── difficulty → sparring loadout ─────────────────────────────

    /** Map the trainee's kit + difficulty to a bot loadout (reusing the tuned bot presets). */
    private fun botLoadout(custom: KitSetup?, diff: Diff): BotLoadout {
        // A Dharok's-axe kit gets a Dharok's opponent; everything else (NH kits, bring-your-own)
        // faces an authentic NH opponent.
        val dharok = custom?.gear?.get(EquipmentType.WEAPON.id)?.id ==
            runCatching { getRSCM("item.dharoks_greataxe") }.getOrNull()
        return if (dharok) {
            when (diff) {
                Diff.EASY -> BotLoadouts.DHAROK_MID.copy(usesPrayer = false, eatAt = 0.6)
                Diff.MEDIUM -> BotLoadouts.DHAROK_MID
                Diff.HARD -> BotLoadouts.DHAROK_DHER
            }
        } else {
            when (diff) {
                Diff.EASY -> BotLoadouts.CLASSIC_HYBRID.copy(usesPrayer = false, eatAt = 0.7)
                Diff.MEDIUM -> BotLoadouts.CLASSIC_HYBRID
                Diff.HARD -> BotLoadouts.ELITE_NH
            }
        }
    }

    private companion object {
        // ── The trainer ──
        // npc.combat_instructor (id 3307): a purpose-built TALKING tutor (Talk-to guaranteed) with an
        // armed, armoured fighter look — renamed client-side to TRAINER_NAME so players read it as the
        // merc. Deliberately NOT npc.melee_combat_tutor (that id's Talk-to is already bound by General Zo,
        // which would collide). For a grittier "battle-scarred mercenary" look, npc.mercenary_captain
        // (4635) is the thematic pick IF it also has a Talk-to — confirm via the npcDef scan, then swap
        // here. The binding is cache-guarded either way, so a Talk-to-less id degrades to ::pktrain.
        const val TRAINER_NPC = "npc.combat_instructor"
        const val TRAINER_NAME = "Ardan the Ripper"
        val TRAINER_TILE = Tile(3367, 3269, 0)
        const val TRAINER_REGION = 13363 // (3367 shr 6 shl 8) or (3269 shr 6)

        // Duel-style bout in the NORTH-WEST fight pit (mapdump-verified: region 13362, pit interior
        // x3334..3351 z3246..3258, row z3251 fully clear of walls/objects). The staked duels use the
        // NE pit, so training and stakes never share a floor. Whoever dies sends the trainee back to
        // EXIT_TILE beside the trainer (mapdump-verified walkable, region 13363 lobby).
        val BOUT_PLAYER_TILE = Tile(3339, 3251, 0)
        val BOUT_BOT_TILE = Tile(3347, 3251, 0)
        val EXIT_TILE = Tile(3368, 3269, 0) // beside the trainer — round-end + death respawn

        // The whole Duel-Arena grounds: SafeDeath zone + "you left, hand the kit back" boundary.
        // x-min 3330 so the NW pit's full interior (x3334+) is inside — a fighter hugging the pit's
        // west wall must NOT trip the "left the arena" kit-return check mid-bout.
        val ARENA = Area(3330, 3242, 3396, 3296)
        const val ARENA_LEASH = 40 // bot won't chase past this from its spawn (keeps it in the arena)

        const val SPAR_BOT_NAME = "Sparring Partner"

        const val ARENA_TICK = 2       // session upkeep cadence (~1.2s)
        const val COUNTDOWN_STEPS = 4  // arena-ticks to FIGHT! (prints 3... 2... 1... at ~1.2s each)
        const val VENG_MAGIC = 94      // Vengeance's Magic requirement — boosted to here while kitted

        // The loaner presets + curated armoury live in [KitArmoury] (content/kits), shared with the
        // kit editor: the trainer's "kit locker" IS the editor, so what it stocks is defined there.
    }
}
