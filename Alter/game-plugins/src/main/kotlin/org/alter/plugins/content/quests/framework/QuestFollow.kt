package org.alter.plugins.content.quests.framework

import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player

/**
 * **The followed quest** (design authority 03 §7: "only the followed quest drives objective
 * arrows"). A player may have several quests in progress; the one they FOLLOW is the one the
 * server guidance arrow ([QuestArrows]) points at. With nothing followed (or the followed quest
 * finished / not started), the arrow falls back to the deepest in-progress quest as before.
 *
 * Persisted as the quest key on [FOLLOWED_ATTR] (`quest_followed`); `::quests follow <key>` /
 * `::quests follow` (clear) on the player side, `QuestApi.follow` for content.
 */
object QuestFollow {

    val FOLLOWED_ATTR = AttributeKey<String>("quest_followed")

    /** The followed quest key, or null. */
    fun followed(p: Player): String? = p.attr[FOLLOWED_ATTR]?.takeIf { it.isNotBlank() }

    /** Follow [questKey] (any registered quest — legacy or framework). False if no such quest. */
    fun follow(p: Player, questKey: String): Boolean {
        val chain = QuestRegistry.byKey(questKey.trim().lowercase()) ?: return false
        p.attr[FOLLOWED_ATTR] = chain.key
        return true
    }

    fun clear(p: Player) {
        p.attr.remove(FOLLOWED_ATTR)
    }

    /** The followed FRAMEWORK quest while it is in progress, else null (legacy chains draw their own arrows). */
    fun followedDefinition(p: Player): QuestDefinition? {
        val key = followed(p) ?: return null
        val def = QuestRegistry.definition(key) ?: return null
        return def.takeIf { QuestEngine.step(p, it) != null }
    }
}
