package org.alter.plugins.content.objects.crates

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.model.entity.GameObject
import org.alter.game.model.entity.Player

private val logger = KotlinLogging.logger {}

/**
 * **Searchable-crate hooks.** [SearchCratesPlugin] owns the *Search* bind on the stock crate ids
 * (a second `onObjOption` on the same id + option throws at construction and drops the whole
 * plugin), so content that wants ONE particular crate to hold something registers a [Hook] here
 * instead: on a search every hook is asked in registration order and the first that claims the
 * click (returns true — typically after checking the crate's tile and the player's quest state)
 * replaces the default "nothing" line. Mirrors `NpcTalk` for objects, at the smallest size that
 * works. Old Wounds (Asgarnia Quest 4) is the first user — the Kinshra orders in the Dark
 * Warriors' Fortress hall.
 */
object CrateSearch {

    fun interface Hook {
        /** True if this hook handled the search of [obj] for [p]. Must not throw. */
        fun search(p: Player, obj: GameObject): Boolean
    }

    private val hooks = ArrayList<Hook>()

    fun register(hook: Hook) {
        hooks += hook
    }

    /** Offer the search to every hook; true if one claimed it. */
    fun handle(p: Player, obj: GameObject?): Boolean {
        if (obj == null || hooks.isEmpty()) return false
        for (h in hooks) {
            val claimed = runCatching { h.search(p, obj) }
                .onFailure { logger.error(it) { "CrateSearch hook threw for ${p.username} at ${obj.tile}" } }
                .getOrDefault(false)
            if (claimed) return true
        }
        return false
    }
}
