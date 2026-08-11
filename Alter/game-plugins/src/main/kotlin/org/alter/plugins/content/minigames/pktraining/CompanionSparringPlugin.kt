package org.alter.plugins.content.minigames.pktraining

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.PK_ARENA_STASH_ATTR
import org.alter.game.model.attr.RESPAWN_TILE_ATTR
import org.alter.game.model.attr.SPAR_BOOST_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.game.model.move.moveTo
import org.alter.game.model.priv.Privilege
import org.alter.game.model.queue.QueueTask
import org.alter.game.model.timer.FROZEN_TIMER
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bots.BotBrain
import org.alter.plugins.content.bots.BotManager
import org.alter.plugins.content.combat.Combat
import org.alter.plugins.content.combat.PvpZones
import org.alter.plugins.content.combat.getCombatTarget
import org.alter.plugins.content.companion.CompItem
import org.alter.plugins.content.companion.CompanionGear
import org.alter.plugins.content.companion.CompanionMagic
import org.alter.plugins.content.companion.CompanionOrders
import org.alter.plugins.content.companion.CompanionRegistry
import org.alter.plugins.content.interfaces.attack.AttackTab
import org.alter.plugins.content.kits.KitEditor
import org.alter.plugins.content.kits.KitSetup
import org.alter.plugins.content.mechanics.prayer.Prayers
import org.alter.plugins.content.raids.RaidInstance
import org.bson.Document
// MANDATORY alias — see the shadowing warning in CompanionSparring.kt.
import org.alter.plugins.content.companion.Companion as CompanionPawn

private val logger = KotlinLogging.logger {}

/**
 * **Companion Sparring** — your own companion as your PK training partner.
 *
 * Right-click **Challenge** on a companion you own (routed here from the duel plugin) → configure
 * the bout (how it fights, how hard, rules, loadouts) → both of you teleport into a private
 * instanced copy of the training pit (the [PkTrainingArenaPlugin] duel-style bout flow) → countdown
 * → it fights you with the full NH [BotBrain]: prayer switching, gear switching off your overhead,
 * spec timing. Deaths are safe, all XP is kept — by BOTH of you (the companion's skills are real).
 *
 * Safety model (same guarantees as the loaner-kit arena):
 *  - The companion's REAL gear is stashed into the session BEFORE any loaner item touches it, and
 *    `CompanionRegistry.snapshot` reads that stash mid-bout — so every persist path (periodic save,
 *    logout, crash recovery) writes real gear. The loaner kit exists only on the transient entity.
 *  - The owner's loaner kit (if any) rides the [PK_ARENA_STASH_ATTR] blob ([LoanerKits]); a
 *    Max Stats boost in OWN gear rides [SPAR_BOOST_ATTR]. Both restore on login after a crash.
 *  - Nothing enters the economy: no drops (companions are death-protected; the trainee's death is
 *    a [org.alter.plugins.content.combat.SafeDeaths] arena/instance death), and loaner gear is
 *    handed back the moment the bout ends.
 */
class CompanionSparringPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    /** Drives [BotBrain.tick] for sparring companions — cadence 1, the brain's designed rate
     *  (prayer flicks, spec combos). Deliberately NOT via BotManager.active: that list's sweeps
     *  (despawnAll, death handlers) must never own a companion's lifecycle. */
    private val brainTimer = TimerKey()

    /** Session upkeep (countdown, left-the-pit checks, re-aggro) at the arena's usual cadence. */
    private val upkeepTimer = TimerKey()

    init {
        // Themed client overlay (default) with the chatbox dialogue as the everyone-else fallback —
        // the duel rules screen's split, one flag.
        CompanionSparring.setupOpener = { owner, comp ->
            // available() guards the overlay's varp being writable at all — an out-of-range varp id
            // used to throw mid-publish and eat the whole Challenge click silently. The chatbox
            // dialogue is the universal fallback and never depends on the varp.
            if (SparringClientMenu.enabled && SparringClientMenu.available(owner)) openOverlay(owner, comp)
            else owner.queue { setupDialog(owner, comp) }
        }

        onWorldInit {
            world.timers[brainTimer] = 1
            world.timers[upkeepTimer] = TrainingArena.ARENA_TICK
        }

        onTimer(brainTimer) {
            CompanionSparring.all().forEach { s ->
                if (s.fighting && s.comp.index >= 0) {
                    runCatching {
                        BotBrain.tick(world, s.comp)
                        // No-specials binds the companion too: the brain's ranged path arms the
                        // spec varp unconditionally, so clear it right back every tick.
                        if (s.settings.rules.noSpec) s.comp.setVarp(AttackTab.SPECIAL_ATTACK_VARP, 0)
                    }.onFailure { logger.error(it) { "spar: brain tick failed for ${s.comp.username}" } }
                }
            }
            world.timers[brainTimer] = 1
        }

        onTimer(upkeepTimer) {
            CompanionSparring.all().forEach { s ->
                runCatching { tick(s) }.onFailure { logger.error(it) { "spar: session tick failed" } }
            }
            world.timers[upkeepTimer] = TrainingArena.ARENA_TICK
        }

        // Clean logout ends the bout; ordering vs CompanionPlugin's storeAndDespawn is free —
        // the snapshot-stash hook makes both orders write real gear.
        onLogout {
            SparringClientMenu.close(player)
            CompanionSparring.sessionOf(player)?.let { teardown(it, ownerWon = null) }
        }

        onLogin { SparringClientMenu.clearVarps(player) } // transient UI state never survives a login

        // Crash recovery: a Max Stats boost taken in OWN gear left its level snapshot on disk.
        // (Loaner-kit fighters recover through PkTrainingArenaPlugin's PK_ARENA_STASH restore.)
        onLogin {
            player.attr[SPAR_BOOST_ATTR]?.let { blob ->
                runCatching {
                    val doc = Document.parse(blob)
                    LoanerKits.COMBAT_SKILLS.forEach { skill ->
                        doc.getInteger(skill.toString())?.let { player.getSkills().setCurrentLevel(skill, it) }
                    }
                }.onFailure { logger.error(it) { "spar: bad boost blob for ${player.username}" } }
                player.attr.remove(SPAR_BOOST_ATTR)
                player.message("<col=801700>Your stats return to normal after the sparring bout.</col>")
            }
        }

        // A death ends the round, either direction. The engine has already handled the death
        // itself (companions are death-protected and respawn; the trainee's death is safe).
        onPlayerDeath {
            val comp = player as? CompanionPawn
            if (comp != null) {
                CompanionSparring.sessionOfComp(comp)?.let { teardown(it, ownerWon = true) }
                return@onPlayerDeath
            }
            CompanionSparring.sessionOf(player)?.let { s -> if (s.fighting) teardown(s, ownerWon = false) }
        }

        // Escape hatch + quick test entry.
        onCommand("sparend", description = "End your companion sparring bout") {
            val s = CompanionSparring.sessionOf(player)
            if (s == null) player.message("You're not sparring right now.")
            else teardown(s, ownerWon = null)
        }
        onCommand("sparcompanion", Privilege.ADMIN_POWER, description = "Quick-start a sparring bout: ::sparcompanion [easy|med|hard]") {
            val comp = CompanionRegistry.ofOwner(player).firstOrNull()
                ?: run { player.message("You field no companions."); return@onCommand }
            val settings = SparSettings()
            settings.difficulty = when (player.getCommandArgs().getOrNull(0)?.lowercase()) {
                "easy" -> SparDifficulty.EASY
                "hard" -> SparDifficulty.HARD
                else -> SparDifficulty.MEDIUM
            }
            startSpar(player, comp, settings)
        }
    }

    // ───────────────────────────── setup via the themed overlay ─────────────────────────────

    private fun openOverlay(
        owner: Player,
        comp: CompanionPawn,
        carried: SparSettings? = null,
        phase: SparringClientMenu.Phase = SparringClientMenu.Phase.SETTINGS,
    ) {
        if (!validateChallenge(owner, comp)) return
        val settings = carried ?: CompanionSparring.lastSettingsOf(owner) ?: SparSettings()
        SparringClientMenu.open(
            owner, comp, settings,
            onStart = { startSpar(owner, comp, it) },
            onEditKit = { st ->
                // Hand off to the kit locker for the COMPANION's custom kit; finishing (or closing)
                // re-opens the settings screen.
                if (!KitEditor.isOpen(owner)) {
                    KitEditor.open(
                        owner, KitEditor.Mode.TRAINING,
                        seed = st.companionKit, seedName = "Sir ${comp.username}'s kit",
                        onStart = { kit, _ ->
                            st.companionKit = kit
                            st.companionKitMode = CompanionKitMode.CUSTOM
                            owner.message("<col=4f9b4f>Sir ${comp.username} will fight in that kit.</col>")
                            resume(owner) { openOverlay(owner, comp, st) }
                        },
                        onClosed = { resume(owner) { openOverlay(owner, comp, st) } },
                    )
                }
            },
            onKitStep = { st -> openKitStep(owner, comp, st) },
            phase = phase,
        )
    }

    /**
     * The duel-arena kit step between settings and confirm: the kit locker opens on the OWNER's
     * bout loadout. "Start bout" locks the built kit in as their loaner; closing with X keeps
     * their own gear. Either way the flow lands on the CONFIRM overview.
     */
    private fun openKitStep(owner: Player, comp: CompanionPawn, settings: SparSettings) {
        if (KitEditor.isOpen(owner)) return
        owner.message("Confirm your loadout — or close the locker to fight in your own gear.")
        KitEditor.open(
            owner, KitEditor.Mode.TRAINING,
            seed = settings.playerKit, seedName = "Spar loadout",
            onStart = { kit, _ ->
                settings.playerKit = kit
                owner.message("<col=4f9b4f>You'll be loaned that kit for the bout.</col>")
                resume(owner) { openOverlay(owner, comp, settings, phase = SparringClientMenu.Phase.CONFIRM) }
            },
            onClosed = {
                settings.playerKit = null // X = fight in my own gear (Back on the overview re-enters)
                resume(owner) { openOverlay(owner, comp, settings, phase = SparringClientMenu.Phase.CONFIRM) }
            },
        )
    }

    /** Run [action] next tick via the player's queue — a queue never fires for a player who logged
     *  out in between, which is exactly the guard these resume hops need (KitEditor.close also
     *  runs on logout, and its onClosed must not re-open a screen for a leaving player). */
    private fun resume(owner: Player, action: () -> Unit) {
        if (owner.index < 0) return
        owner.queue { action() }
    }

    // ───────────────────────────── setup dialogue (chatbox fallback) ─────────────────────────────

    private suspend fun QueueTask.setupDialog(owner: Player, comp: CompanionPawn) {
        if (!validateChallenge(owner, comp)) return
        val settings = CompanionSparring.lastSettingsOf(owner) ?: SparSettings()
        while (true) {
            val myKit = if (settings.playerKit != null) "kit" else "own gear"
            when (options(
                owner,
                "Begin the bout!",
                "Partner: ${settings.fightStyle.label} · ${settings.difficulty.label}.",
                "Loadouts: them — ${settings.companionKitMode.label}; me — $myKit.",
                "Rules: ${settings.rules.summary()}.",
                "Never mind.",
                title = "Spar against Sir ${comp.username}?",
            )) {
                1 -> {
                    // The duel-arena final confirm: one read-back of the whole arrangement.
                    when (options(
                        owner,
                        "Fight!",
                        "Them: ${settings.fightStyle.label} · ${settings.difficulty.label} · ${settings.companionKitMode.label}; me: $myKit.",
                        "Back.",
                        title = "Confirm — rules: ${settings.rules.summary()}",
                    )) {
                        1 -> { startSpar(owner, comp, settings); return }
                        else -> {} // back to the main menu loop
                    }
                }
                2 -> partnerMenu(owner, settings)
                3 -> if (!loadoutMenu(owner, comp, settings)) return // handed off to the kit editor
                4 -> rulesMenu(owner, settings)
                else -> return
            }
        }
    }

    private suspend fun QueueTask.partnerMenu(owner: Player, settings: SparSettings) {
        when (options(owner, "Full NH — switches, prayers, specs.", "Melee only.", "Ranged only.", "Magic only.", title = "How should they fight?")) {
            1 -> settings.fightStyle = SparStyle.FULL_NH
            2 -> settings.fightStyle = SparStyle.MELEE
            3 -> settings.fightStyle = SparStyle.RANGED
            4 -> settings.fightStyle = SparStyle.MAGIC
        }
        when (options(owner, "Easy — slow switches, no prayer.", "Medium — a solid PKer.", "Hard — 1-tick switches.", title = "How hard?")) {
            1 -> settings.difficulty = SparDifficulty.EASY
            2 -> settings.difficulty = SparDifficulty.MEDIUM
            3 -> settings.difficulty = SparDifficulty.HARD
        }
    }

    /** Returns false when the flow handed off to the kit editor (the dialogue must end). */
    private suspend fun QueueTask.loadoutMenu(owner: Player, comp: CompanionPawn, settings: SparSettings): Boolean {
        when (options(
            owner,
            "Them: current gear.",
            "Them: mirror my loadout.",
            "Them: custom kit... (kit locker)",
            "Me: " + (if (settings.playerKit == null) "pick a loaner kit... (kit locker)" else "fight in my own gear."),
            "Back.",
            title = "Loadouts",
        )) {
            1 -> { settings.companionKitMode = CompanionKitMode.CURRENT; settings.companionKit = null }
            2 -> { settings.companionKitMode = CompanionKitMode.MIRROR; settings.companionKit = null }
            3 -> {
                if (KitEditor.isOpen(owner)) return false
                KitEditor.open(owner, KitEditor.Mode.TRAINING, onStart = { kit, _ ->
                    settings.companionKit = kit
                    settings.companionKitMode = CompanionKitMode.CUSTOM
                    owner.message("<col=4f9b4f>Sir ${comp.username} will fight in that kit.</col>")
                    owner.queue { setupDialog(owner, comp) }
                })
                return false
            }
            4 -> {
                if (settings.playerKit != null) { settings.playerKit = null; return true }
                if (KitEditor.isOpen(owner)) return false
                KitEditor.open(owner, KitEditor.Mode.TRAINING, onStart = { kit, _ ->
                    settings.playerKit = kit
                    owner.message("<col=4f9b4f>You'll be loaned that kit for the bout.</col>")
                    owner.queue { setupDialog(owner, comp) }
                })
                return false
            }
        }
        return true
    }

    private suspend fun QueueTask.rulesMenu(owner: Player, settings: SparSettings) {
        val r = settings.rules
        fun state(on: Boolean) = if (on) "ON" else "off"
        while (true) {
            when (options(
                owner,
                "No food: ${state(r.noFood)} · No potions: ${state(r.noDrinks)}.",
                "No prayer: ${state(r.noPrayer)} · No movement: ${state(r.noMovement)}.",
                "No specials: ${state(r.noSpec)} · Style bans...",
                "Max stats (all 99s for the bout): ${state(r.maxStats)}.",
                "Done.",
                title = "Rules: ${r.summary()}",
            )) {
                1 -> when (options(owner, "Toggle no food.", "Toggle no potions.", "Back.")) {
                    1 -> r.noFood = !r.noFood
                    2 -> r.noDrinks = !r.noDrinks
                }
                2 -> when (options(owner, "Toggle no prayer.", "Toggle no movement.", "Back.")) {
                    1 -> r.noPrayer = !r.noPrayer
                    2 -> r.noMovement = !r.noMovement
                }
                3 -> when (options(owner, "Toggle no specials.", "Toggle no melee.", "Toggle no ranged.", "Toggle no magic.", "Back.")) {
                    1 -> r.noSpec = !r.noSpec
                    2 -> r.noMelee = !r.noMelee
                    3 -> r.noRanged = !r.noRanged
                    4 -> r.noMagic = !r.noMagic
                }
                4 -> r.maxStats = !r.maxStats
                else -> return
            }
        }
    }

    // ───────────────────────────── bout lifecycle ─────────────────────────────

    private fun validateChallenge(owner: Player, comp: CompanionPawn): Boolean {
        // Every rejection MUST speak — a Challenge click that visibly does nothing is
        // indistinguishable from a broken feature.
        if (!CompanionRegistry.owns(owner, comp)) {
            owner.message("You can only challenge your own companions to a sparring bout."); return false
        }
        if (CompanionSparring.sessionOf(owner) != null || CompanionSparring.isSparring(comp)) {
            owner.message("You're already mid-bout."); return false
        }
        if (comp.index < 0 || comp.dead) { owner.message("Sir ${comp.username} isn't fit to spar right now."); return false }
        // The duel challenge conventions: safe zones only, and never from inside other content's
        // instances (the bout teleport would rip you out of that content mid-run).
        if (!PvpZones.isSafe(owner.tile)) { owner.message("You can't arrange a spar in the wilderness."); return false }
        if (world.instanceAllocator.getMap(owner.tile) != null) { owner.message("You can't arrange a spar from in here."); return false }
        if (org.alter.plugins.content.minigames.duel.DuelArena.duelOf(owner) != null) {
            owner.message("You can't arrange a spar mid-duel."); return false
        }
        if (KitEditor.isOpen(owner)) {
            owner.message("Close the kit locker first, then challenge Sir ${comp.username}."); return false
        }
        return true
    }

    private fun startSpar(owner: Player, comp: CompanionPawn, settings: SparSettings) {
        if (!validateChallenge(owner, comp)) return
        val instance = RaidInstance.allocate(
            world, TrainingArena.PIT_SOURCE,
            exitTile = TrainingArena.EXIT_TILE, owner = owner.uid, autoDeallocate = false,
        ) ?: run {
            owner.message("<col=801700>Every training pit is in use — give it a moment.</col>")
            return
        }
        CompanionSparring.rememberSettings(owner, settings)
        val s = CompanionSparring.SparSession(owner, comp, instance, settings)

        // ── companion: STASH FIRST, then register, then persist — the roster blob provably holds
        // real gear before any loaner item exists (crash-safety keystone). ──
        s.realEquip = snapshotContainer(comp.equipment)
        s.realInv = snapshotContainer(comp.inventory)
        s.realOrders = comp.orders
        s.realHuntAnchor = comp.huntAnchor
        s.realAttackStyle = comp.getVarp(AttackTab.ATTACK_STYLE_VARP)
        s.realHomeTile = comp.homeTile
        s.realLeash = comp.leashRadius
        s.realRoam = comp.roamRadius
        CompanionSparring.register(s)
        CompanionRegistry.persist(owner)

        // ── owner side ──
        settings.playerKit?.let { LoanerKits.applyLoaner(owner, it) }
        if (owner.attr[PK_ARENA_STASH_ATTR] == null && !s.respawnSaved) {
            s.prevRespawn = owner.attr[RESPAWN_TILE_ATTR]
            s.respawnSaved = true
        }
        owner.attr[RESPAWN_TILE_ATTR] = TrainingArena.EXIT_TILE.coordinate
        if (settings.rules.maxStats) boostOwner(owner)
        owner.setCurrentHp(maxOf(owner.getMaxHp(), owner.getCurrentHp()))
        AttackTab.setEnergy(owner, 100)
        owner.moveTo(instance.translate(TrainingArena.BOUT_PLAYER_TILE))

        // ── companion loadout ──
        val kit = when (settings.companionKitMode) {
            CompanionKitMode.CURRENT -> SparKits.kitFromStash(s)
            CompanionKitMode.MIRROR -> SparKits.snapshotKit(owner)
            CompanionKitMode.CUSTOM -> settings.companionKit ?: SparKits.kitFromStash(s)
        }
        val built = SparKits.toLoadout(kit, comp, settings)
        s.skipped = built.skipped
        comp.loadoutOverride = built.loadout
        Combat.reset(comp)
        comp.resetFacePawn()
        comp.orders = CompanionOrders.FOLLOW
        comp.huntAnchor = null
        LoanerKits.wipeContainers(comp)
        BotManager.equipStyle(comp, built.loadout.baseStyle) // dresses the whole base block (fightLoadout)
        var slot = 0
        built.invItems.forEach { if (slot < comp.inventory.capacity) comp.inventory[slot++] = Item(it.id, it.amount) }
        comp.boundHunter = owner.uid
        comp.ambushEverywhere = false // armed at FIGHT! — passive through the countdown
        comp.provokedBy = null
        comp.homeTile = instance.translate(TrainingArena.BOUT_BOT_TILE)
        comp.leashRadius = TrainingArena.ARENA_LEASH
        comp.roamRadius = 0
        comp.reactionTicksRange = settings.difficulty.reaction
        comp.eatAtOverride = settings.difficulty.eatAt
        if (settings.rules.maxStats) boostCompanion(comp)
        comp.setCurrentHp(maxOf(comp.getMaxHp(), comp.getCurrentHp()))
        AttackTab.setEnergy(comp, 100)
        BotBrain.configureStyle(comp)
        comp.moveTo(instance.translate(TrainingArena.BOUT_BOT_TILE))
        comp.facePawn(owner)

        TrainingArena.setInBout(owner, true) // the owner's OTHER companions stand down
        // Tell the client which companion is the live opponent, so its menu tidy-up (which hides
        // "Attack" on your own companions) leaves this one attackable for the bout.
        SparringClientMenu.setOpponent(owner, comp.index)
        s.fighting = false
        s.countdown = TrainingArena.COUNTDOWN_STEPS

        if (built.skipped.isNotEmpty()) {
            owner.message("<col=801700>Sir ${comp.username} isn't a high enough level for: ${built.skipped.distinct().joinToString(", ")} — fighting without.</col>")
        }
        built.notes.forEach { owner.message("<col=801700>$it</col>") }
        owner.message("<col=801700>Sir ${comp.username} steps onto the sand...</col> Deaths here are safe; you both keep the XP.")
    }

    private fun tick(s: CompanionSparring.SparSession) {
        val owner = s.owner
        if (owner.index < 0) { teardown(s, ownerWon = null); return } // vanished without a clean logout
        val inPit = s.instance.contains(owner.tile)
        if (!inPit && !TrainingArena.ARENA.contains(owner.tile)) { teardown(s, ownerWon = null); return }
        val comp = s.comp
        if (comp.index < 0 || comp.dead) { teardown(s, ownerWon = true); return } // vanished mid-bout

        if (!s.fighting) {
            s.countdown--
            when {
                s.countdown > 0 -> {
                    // Duel-arena style: both fighters call the count overhead, plus the chat line.
                    owner.forceChat("${s.countdown}")
                    comp.forceChat("${s.countdown}")
                    owner.message("<col=ff0000>${s.countdown}...</col>")
                }
                s.countdown == 0 -> {
                    s.fighting = true
                    owner.forceChat("FIGHT!")
                    comp.forceChat("FIGHT!")
                    owner.message("<col=ff0000>FIGHT!</col>")
                    comp.ambushEverywhere = true // arm the brain (the pit isn't wilderness)
                    comp.attack(owner)
                }
            }
            return
        }
        if (comp.getCombatTarget() == null) comp.attack(owner) // re-aggro if it lost its target
        if (s.settings.rules.noMovement) {
            // "No movement" — keep both rooted by refreshing the freeze timer (the duel pattern).
            owner.timers[FROZEN_TIMER] = 4
            comp.timers[FROZEN_TIMER] = 4
        }
    }

    /**
     * End the bout and put EVERYTHING back: the companion's real gear/state (then persist), the
     * owner's kit/levels/respawn. [ownerWon] — true: companion fell; false: owner fell; null:
     * walked away / logout / cleanup. Safe to call in any state; unregisters the session LAST
     * (the snapshot-stash hook must stay live until the real containers are back).
     */
    private fun teardown(s: CompanionSparring.SparSession, ownerWon: Boolean?) {
        if (CompanionSparring.sessionOf(s.owner) !== s && CompanionSparring.sessionOfComp(s.comp) !== s) return // already torn down
        val owner = s.owner
        val comp = s.comp

        // Bout end sends the owner back to the tile they challenged from (the staked duel's
        // return-tile pattern) — the arena lobby is wilderness, so nobody may be left standing
        // there. A death has already respawned them at the instance's exit tile ON the arena
        // grounds, hence the ARENA check; an owner who left the grounds themselves stays put.
        val returnOwner = owner.index >= 0 &&
            (s.instance.contains(owner.tile) || TrainingArena.ARENA.contains(owner.tile))

        // ── companion restore ──
        if (comp.index >= 0) {
            Combat.reset(comp)
            comp.resetFacePawn()
            runCatching { Prayers.deactivateAll(comp) }
            comp.attr.remove(Combat.CASTING_SPELL)
            comp.pendingPray = null
            comp.prayedAgainst = null
            comp.baitedFrom = null
            comp.comboFollowup = null
            comp.nextMeleeSpec = 0
            comp.pidOpponent = null
            comp.koWaitSince = null
            comp.swapping = false
            LoanerKits.wipeContainers(comp)
            s.realEquip.forEach { (slot, it) -> if (slot < comp.equipment.capacity) comp.equipment[slot] = Item(it.id, it.amount) }
            s.realInv.forEach { (slot, it) -> if (slot < comp.inventory.capacity) comp.inventory[slot] = Item(it.id, it.amount) }
            comp.loadoutOverride = null
            comp.currentStyle = comp.archetype.botStyle
            comp.boundHunter = null
            comp.ambushEverywhere = false
            comp.reactionTicksRange = null
            comp.eatAtOverride = null
            comp.homeTile = s.realHomeTile
            comp.leashRadius = s.realLeash
            comp.roamRadius = s.realRoam
            if (s.settings.rules.maxStats) restoreCompanionLevels(comp)
            CompanionGear.refreshGear(comp) // MANDATORY: bonuses + weapon-type varbit + appearance
            BotBrain.configureStyle(comp)
            comp.setVarp(AttackTab.ATTACK_STYLE_VARP, s.realAttackStyle)
            CompanionMagic.ensureSpell(comp)
            comp.setCurrentHp(comp.getMaxHp())
            comp.orders = CompanionOrders.FOLLOW
            comp.huntAnchor = null
            if (owner.index >= 0) {
                val beside = if (returnOwner) s.returnTile else owner.tile
                comp.moveTo(Tile(beside.x + 1, beside.z, beside.height))
            }
        }

        // ── owner restore ──
        TrainingArena.setInBout(owner, false)
        if (owner.index >= 0) SparringClientMenu.setOpponent(owner, null) // "Attack" hides again
        if (owner.index >= 0) {
            if (returnOwner) owner.moveTo(s.returnTile)
            if (owner.attr[PK_ARENA_STASH_ATTR] != null) {
                LoanerKits.restoreLoaner(owner) // gear + levels + respawn from the stash
                owner.attr.remove(SPAR_BOOST_ATTR) // levels came back with the stash
            } else {
                restoreOwnerLevels(owner)
                if (s.respawnSaved) {
                    s.prevRespawn?.let { owner.attr[RESPAWN_TILE_ATTR] = it } ?: owner.attr.remove(RESPAWN_TILE_ATTR)
                }
            }
            owner.setCurrentHp(owner.getMaxHp())
            when (ownerWon) {
                true -> owner.message("<col=007f00>Sir ${comp.username} yields — the bout is yours!</col> Challenge them again to go another round.")
                false -> owner.message("<col=ff0000>Sir ${comp.username} bests you.</col> A good lesson — challenge them again to go another round.")
                null -> owner.message("The sparring bout ends. Sir ${comp.username}'s gear is restored.")
            }
        }

        // Unregister LAST, then persist the restored state.
        CompanionSparring.unregister(s)
        if (owner.index >= 0) {
            CompanionRegistry.persist(owner)
            CompanionRegistry.forcePush(owner)
        }
    }

    // ───────────────────────────── max stats (the LMS model: current levels only) ─────────────────────────────

    private fun boostOwner(p: Player) {
        // Own-gear fighters get the crash-recovery snapshot; loaner-kit fighters' levels already
        // ride the PK_ARENA_STASH blob (written pre-boost by applyLoaner's stash).
        if (p.attr[PK_ARENA_STASH_ATTR] == null && p.attr[SPAR_BOOST_ATTR] == null) {
            val doc = Document()
            LoanerKits.COMBAT_SKILLS.forEach { doc.append(it.toString(), p.getSkills().getCurrentLevel(it)) }
            p.attr[SPAR_BOOST_ATTR] = doc.toJson()
        }
        LoanerKits.COMBAT_SKILLS.forEach { p.getSkills().setCurrentLevel(it, 99) }
        p.message("<col=801700>Max stats:</col> you fight this bout at all 99s.")
    }

    private fun restoreOwnerLevels(p: Player) {
        val blob = p.attr[SPAR_BOOST_ATTR] ?: return
        runCatching {
            val doc = Document.parse(blob)
            LoanerKits.COMBAT_SKILLS.forEach { skill ->
                doc.getInteger(skill.toString())?.let { p.getSkills().setCurrentLevel(skill, it) }
            }
        }.onFailure {
            logger.error(it) { "spar: bad boost blob for ${p.username} — restoring to base" }
            LoanerKits.COMBAT_SKILLS.forEach { skill -> p.getSkills().setCurrentLevel(skill, p.getSkills().getBaseLevel(skill)) }
        }
        p.attr.remove(SPAR_BOOST_ATTR)
    }

    private fun boostCompanion(comp: CompanionPawn) {
        // Current levels only — XP (which snapshot() persists) is untouched, so periodic saves
        // mid-bout stay correct. Restore recomputes current = base (its clean steady state).
        LoanerKits.COMBAT_SKILLS.forEach { comp.getSkills().setCurrentLevel(it, 99) }
    }

    private fun restoreCompanionLevels(comp: CompanionPawn) {
        val s = comp.getSkills()
        LoanerKits.COMBAT_SKILLS.forEach { skill -> s.setCurrentLevel(skill, s.getBaseLevel(skill)) }
    }

    private fun snapshotContainer(c: org.alter.game.model.container.ItemContainer): Map<Int, CompItem> {
        val out = HashMap<Int, CompItem>()
        for (i in 0 until c.capacity) c[i]?.let { out[i] = CompItem(it.id, it.amount) }
        return out
    }
}
