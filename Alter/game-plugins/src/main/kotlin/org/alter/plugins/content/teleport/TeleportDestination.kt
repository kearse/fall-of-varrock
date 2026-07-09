package org.alter.plugins.content.teleport

import org.alter.game.model.Tile
import org.alter.plugins.content.magic.TeleportType

/**
 * The teleport-portal content model (Phase 0 — see `docs/teleport-portal.md`).
 *
 * A [TeleportDestination] is one row in the portal interface: a name, the tab it
 * lives under, where it sends you, and how dangerous it is. The catalog lives in
 * [TeleportRegistry]; execution + usage tracking in [TeleportService]. This is the
 * pure-Kotlin core — it has no dependency on the (later) cache interface, so it can
 * be driven by the temporary `::portal` command today and the if3 UI in Phase 2.
 */

/** A tab in the portal interface. POPULAR/RECENT are virtual (computed, not stored). */
enum class TeleportCategory(val display: String) {
    BASICS("Basics"),
    SKILLING("Skilling"),
    WAR("The War"),
    BOSSES("Bosses"),
    WILDERNESS("Wilderness"),
    SLAYER("Slayer"),
    MINIGAMES("Mini-Games"),
    EVENTS("Events"),
    DONATOR("Donator"),
}

/**
 * Colour-coded safety label shown in the Danger column (mirrors Roak). The green
 * "safe" tags display verbatim; [WILDERNESS] appends the destination's wild level.
 */
enum class DangerTag(val display: String, val safe: Boolean) {
    SAFE_ZONE("Safe Zone", true),
    SAFE_BANK("Safe Bank", true),
    SAFE_PVP("Safe PvP", true),
    HOSTILE("Hostile", false),
    WILD("Wilderness", false),
}

/** BUILT = real, clickable destination. COMING_SOON = greyed placeholder (roadmap). */
enum class DestState { BUILT, COMING_SOON }

/**
 * One portal destination.
 *
 * @param key stable handle (commands, persistence, popularity, UI button mapping).
 * @param dest where the player lands ([DestState.COMING_SOON] entries use a placeholder).
 * @param wildLevel wilderness level shown next to [DangerTag.WILDERNESS] (null otherwise).
 * @param teleType animation/gfx + wild-level restriction used when teleporting.
 * @param icon sprite id for the row (assigned when the cache UI is authored; -1 = none yet).
 */
data class TeleportDestination(
    val key: String,
    val displayName: String,
    val category: TeleportCategory,
    val dest: Tile,
    val danger: DangerTag,
    val wildLevel: Int? = null,
    val state: DestState = DestState.BUILT,
    val teleType: TeleportType = TeleportType.MODERN,
    val icon: Int = -1,
) {
    val isBuilt: Boolean get() = state == DestState.BUILT

    /** v1: every teleport is free (currencies reserved for future premium teleports). */
    val priceText: String get() = "FREE"

    /** Rendered Danger column text, e.g. "Safe Zone" or "Wilderness Lvl 40". */
    val dangerText: String
        get() = if (danger == DangerTag.WILD && wildLevel != null) {
            "Wilderness Lvl $wildLevel"
        } else {
            danger.display
        }
}
