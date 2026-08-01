package org.alter.plugins.content.mechanics.onboarding

import org.alter.api.ChatMessageType
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.LockState
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.attr.ONBOARD_STEP_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.model.priv.Privilege
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.mechanics.appearance.StyleMenu
import org.alter.plugins.content.mechanics.introvideo.IntroVideoPlugin
import org.alter.plugins.content.war.recruit.RecruitTrials

/**
 * **First-login flow** — the one deterministic new-player sequence the design calls for:
 *
 *   VIDEO  → lock + stage the player, play the intro over a game that loads behind it
 *   STYLE  → the mandatory Character Style window (the custom `lofstyle` window — forge your
 *            look, no ✕), the player's own model framed in the window as the live preview
 *   DONE   → unlock, restore position, hand off to the Recruiting Sergeant's first-forces script
 *
 * The order is deliberately **video → customization → Sergeant**: the recruit shapes their
 * character the moment the intro ends, and only then does the Sergeant brief them and muster
 * them into the Recruit Trials (FIGHT).
 *
 * Why an orchestrator: the intro-video / recruit-sergeant / starter-kit login hooks fire in
 * arbitrary order (PluginRepository runs login hooks unordered), so nothing may depend on hook
 * order. This object owns the ordering as a **persisted state machine**: the step lives in
 * [ONBOARD_STEP_ATTR] (seeded to 0 = VIDEO for brand-new accounts in
 * PlayerSaving.configureNewPlayer), so any plugin can ask [isOnboarding] and get a correct answer
 * regardless of which hook ran first, and the flow survives a disconnect (resume where you left
 * off — see [resumeOnLogin]). An ABSENT step means [Step.DONE]: every account created before
 * onboarding is untouched and can never be re-onboarded.
 *
 * The client→server "video finished" signal arrives as `::lofintro done`
 * (MessagePublicHandler → introclick → [onVideoFinished]). A server-side fallback timer covers a
 * client that never reports back (old client, no video natives), so a player is never stranded.
 */
object FirstLoginFlow {

    enum class Step { VIDEO, STYLE, DONE }

    /** Where the player is staged for the video + style window (open Lumbridge courtyard = home).
     *  Sits just east of the rejuvenation pool (3x3 footprint 3216..3218 — see
     *  [org.alter.plugins.content.areas.lumbridge.objs.RejuvenationPoolPlugin]) so the recruit
     *  isn't standing in the water. */
    private val STAGE_TILE = Tile(3220, 3218, 0)

    /** Face south toward the Lumbridge gate so the model reads well in the framed portrait. */
    private val STAGE_FACE = Tile(3220, 3210, 0)

    /** Session-only stash of where the player was before staging, restored on confirm. */
    private val RETURN_TILE_ATTR = AttributeKey<Tile>()

    /** Server-side safety net: if the client never reports the video finished, advance anyway. */
    val FALLBACK_TIMER = TimerKey()
    private const val FALLBACK_TICKS = 400 // ~4 minutes @ 600ms/tick — longer than any intro
    private const val TRIGGER_DELAY = 5    // cycles to clear the login handshake before the trigger lands

    // ------------------------------------------------------------------ state

    fun step(p: Player): Step {
        val ord = p.attr[ONBOARD_STEP_ATTR] ?: return Step.DONE
        return Step.values().getOrElse(ord) { Step.DONE }
    }

    /** True while the player is still inside the first-login sequence (video or style). */
    fun isOnboarding(p: Player): Boolean = step(p) != Step.DONE

    private fun advanceTo(p: Player, next: Step) {
        p.attr[ONBOARD_STEP_ATTR] = next.ordinal
    }

    // ------------------------------------------------------------------ steps

    /**
     * Login entry point — the ONLY login hook in the flow. Resumes whatever step the player is on,
     * so a client that dropped mid-video replays the video, and one that dropped with the style
     * window open re-opens it (still locked). Existing accounts are DONE and do nothing.
     */
    fun resumeOnLogin(p: Player) {
        when (step(p)) {
            Step.VIDEO -> beginVideo(p)
            Step.STYLE -> {
                // Re-lock and re-open the window after the login handshake settles.
                p.lock = LockState.FULL_WITH_LOGOUT
                p.world.queue { wait(TRIGGER_DELAY); if (p.isOnline) beginStyle(p) }
            }
            Step.DONE -> {}
        }
    }

    private fun beginVideo(p: Player) {
        p.lock = LockState.FULL_WITH_LOGOUT // blocks movement, combat AND logout for the whole intro
        p.attr[RETURN_TILE_ATTR] = p.tile
        p.moveTo(STAGE_TILE)
        p.faceTile(STAGE_FACE)
        p.timers[FALLBACK_TIMER] = FALLBACK_TICKS
        // Delay past the login handshake (a chat line fired inside onLogin is sent before the client
        // is listening), on a WORLD queue: a player queue would be terminated by the next plugin that
        // queues on this player. The client's lofintro plugin catches this magic BROADCAST line.
        p.world.queue {
            wait(TRIGGER_DELAY)
            if (p.isOnline) p.message(IntroVideoPlugin.TRIGGER_MESSAGE, ChatMessageType.BROADCAST)
        }
    }

    /**
     * Client reports the intro finished (or couldn't play). Idempotent: guarded on step VIDEO so a
     * duplicate `::lofintro done` and the fallback timer can never both fire the transition.
     */
    fun onVideoFinished(p: Player) {
        if (step(p) != Step.VIDEO) return
        p.timers.remove(FALLBACK_TIMER)
        advanceTo(p, Step.STYLE)
        beginStyle(p)
    }

    private fun beginStyle(p: Player) {
        p.message("<col=dc2626>Forge your likeness, recruit.</col> The realm will know you by it — press <col=ffb000>ENTER THE WAR</col> when you're ready.")
        StyleMenu.open(p, mandatory = true)
    }

    /**
     * The player pressed DONE ("ENTER THE WAR") in the mandatory style window. Guarded on step STYLE.
     * Unlock, restore their position, and hand off to the Recruiting Sergeant's first-forces script.
     */
    fun onStyleConfirmed(p: Player) {
        if (step(p) != Step.STYLE) return
        advanceTo(p, Step.DONE)
        p.unlock()
        p.attr[RETURN_TILE_ATTR]?.let { p.moveTo(it) }
        RecruitTrials.greet?.invoke(p)
    }

    /** Admin: reset self to the top of the flow and re-run it (::onboardtest). */
    fun restart(p: Player) {
        p.attr[ONBOARD_STEP_ATTR] = Step.VIDEO.ordinal
        beginVideo(p)
    }
}

class FirstLoginFlowPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onLogin { FirstLoginFlow.resumeOnLogin(player) }

        // Safety net: the client never reported the video finished — advance the flow anyway.
        onTimer(FirstLoginFlow.FALLBACK_TIMER) { FirstLoginFlow.onVideoFinished(player) }

        onCommand("onboardtest", Privilege.ADMIN_POWER, description = "Re-run the first-login flow (video → style → sergeant)") {
            FirstLoginFlow.restart(player)
        }
    }
}
