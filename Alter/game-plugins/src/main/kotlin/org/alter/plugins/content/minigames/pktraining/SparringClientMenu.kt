package org.alter.plugins.content.minigames.pktraining

import org.alter.api.ext.message
import org.alter.api.ext.setVarp
import org.alter.game.model.entity.Player
import org.alter.plugins.content.companion.Companion as CompanionPawn

/**
 * Server driver for the **themed client-overlay** companion-sparring settings screen
 * (`net.runelite.client.plugins.lofspar`) — the Duel Arena Options screen's layout with the
 * paper-doll side replaced by the sparring partner's setup (style / difficulty / loadout radios).
 * Single-player: no accept handshake — **Start Bout** replaces Accept.
 *
 * Same transport contract as the duel rules screen — no custom packets:
 *  - State flows DOWN as one packed varp ([STATE_VARP]).
 *  - Clicks flow UP as `::lofspar <action>` public chat (→ the `sparclick` command).
 *
 * Packed [STATE_VARP]:
 *   bit 0        open
 *   bits 1-2     fight style ([SparStyle] ordinal)
 *   bits 3-4     difficulty ([SparDifficulty] ordinal)
 *   bits 5-13    the 9 rule toggles ([TrainingRules] — order in [packRules])
 *   bits 14-15   companion loadout mode ([CompanionKitMode] ordinal)
 */
object SparringClientMenu {
    /** Must match the client overlay (LofSparOverlay). 4680 is free: 4600-4639 are allocated to
     *  the HUD/duel/companion varps and 4640-4679 to the kit editor — see the master varp map in
     *  docs/overlay-design-system.md §8 (updated together with this claim). */
    const val STATE_VARP = 4680
    const val RULE_COUNT = 9

    /** Overlay on custom clients; the chatbox dialogue remains the fallback for everyone else. */
    var enabled = true

    /**
     * Whether [STATE_VARP] can actually be written for [p] — the varp table is sized from the
     * cache's varp count plus the server's custom ceiling, and `VarpSet.setState` THROWS on an
     * out-of-range id (the failure mode that silently killed the Challenge flow). When this is
     * false the caller must use the chatbox dialogue instead.
     */
    fun available(p: Player): Boolean = STATE_VARP < p.varps.maxVarps

    private class Session(
        val owner: Player,
        val comp: CompanionPawn,
        var settings: SparSettings,
        val onStart: (SparSettings) -> Unit,
        val onEditKit: (SparSettings) -> Unit,
    )

    private val sessions = HashMap<Int, Session>()

    fun isOpen(p: Player): Boolean = sessions.containsKey(p.index)

    fun open(
        owner: Player,
        comp: CompanionPawn,
        settings: SparSettings,
        onStart: (SparSettings) -> Unit,
        onEditKit: (SparSettings) -> Unit,
    ) {
        val s = Session(owner, comp, settings, onStart, onEditKit)
        sessions[owner.index] = s
        publish(s)
    }

    fun close(p: Player) {
        sessions.remove(p.index) ?: return
        if (p.index >= 0) clearVarps(p)
    }

    /** Wipe the overlay varp (login hygiene — transient UI state must never persist). */
    fun clearVarps(p: Player) {
        if (available(p)) p.setVarp(STATE_VARP, 0)
    }

    // ── actions (routed from ::lofspar via SparClickPlugin) ──

    fun setStyle(p: Player, i: Int) {
        val s = sessions[p.index] ?: return
        SparStyle.values().getOrNull(i)?.let { s.settings.fightStyle = it }
        publish(s)
    }

    fun setDiff(p: Player, i: Int) {
        val s = sessions[p.index] ?: return
        SparDifficulty.values().getOrNull(i)?.let { s.settings.difficulty = it }
        publish(s)
    }

    fun toggleRule(p: Player, i: Int) {
        val s = sessions[p.index] ?: return
        val r = s.settings.rules
        when (i) {
            0 -> r.noMelee = !r.noMelee
            1 -> r.noRanged = !r.noRanged
            2 -> r.noMagic = !r.noMagic
            3 -> r.noPrayer = !r.noPrayer
            4 -> r.noFood = !r.noFood
            5 -> r.noDrinks = !r.noDrinks
            6 -> r.noMovement = !r.noMovement
            7 -> r.noSpec = !r.noSpec
            8 -> r.maxStats = !r.maxStats
            else -> return
        }
        publish(s)
    }

    fun setKitMode(p: Player, i: Int) {
        val s = sessions[p.index] ?: return
        val mode = CompanionKitMode.values().getOrNull(i) ?: return
        if (mode == CompanionKitMode.CUSTOM) {
            // Hand off to the kit locker: the settings screen closes; the editor's finish re-opens it.
            val settings = s.settings
            close(p)
            s.onEditKit(settings)
            return
        }
        s.settings.companionKitMode = mode
        s.settings.companionKit = null
        publish(s)
    }

    /** "Load Last" — copy the owner's remembered setup into the live session. */
    fun loadLast(p: Player) {
        val s = sessions[p.index] ?: return
        val last = CompanionSparring.lastSettingsOf(p) ?: run {
            p.message("You have no previous sparring setup yet.")
            return
        }
        s.settings = last
        publish(s)
    }

    fun start(p: Player) {
        val s = sessions[p.index] ?: return
        if (s.settings.rules.noMelee && s.settings.rules.noRanged && s.settings.rules.noMagic) {
            p.message("You must leave at least one combat style available.")
            return
        }
        val settings = s.settings
        close(p)
        s.onStart(settings)
    }

    fun cancel(p: Player) {
        sessions[p.index] ?: return
        close(p)
        p.message("You decide not to spar right now.")
    }

    // ── state sync ──

    private fun publish(s: Session) {
        val p = s.owner
        if (p.index < 0 || !available(p)) return
        var v = 1
        v = v or ((s.settings.fightStyle.ordinal and 0x3) shl 1)
        v = v or ((s.settings.difficulty.ordinal and 0x3) shl 3)
        v = v or (packRules(s.settings.rules) shl 5)
        v = v or ((s.settings.companionKitMode.ordinal and 0x3) shl 14)
        p.setVarp(STATE_VARP, v)
    }

    /** Rule bit order — MUST match the client overlay's RULES order. */
    private fun packRules(r: TrainingRules): Int {
        var v = 0
        if (r.noMelee) v = v or (1 shl 0)
        if (r.noRanged) v = v or (1 shl 1)
        if (r.noMagic) v = v or (1 shl 2)
        if (r.noPrayer) v = v or (1 shl 3)
        if (r.noFood) v = v or (1 shl 4)
        if (r.noDrinks) v = v or (1 shl 5)
        if (r.noMovement) v = v or (1 shl 6)
        if (r.noSpec) v = v or (1 shl 7)
        if (r.maxStats) v = v or (1 shl 8)
        return v
    }
}
