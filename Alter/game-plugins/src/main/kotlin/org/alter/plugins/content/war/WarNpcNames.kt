package org.alter.plugins.content.war

import org.alter.game.info.NpcInfo
import org.alter.game.model.entity.Npc

/**
 * Display-name overrides for repurposed war NPCs, pushed to clients at spawn via
 * extended-info ([NpcInfo.setTempName] → rsprot `setNameChange`) instead of editing the
 * game cache in Displee. The name-change block is persistent extended info, so it is
 * (re)sent to every player who observes the NPC, including those who log in later.
 *
 * The rscm keys stay the original stock names (code looks NPCs up by key); only the
 * client-facing display string is overridden. Add a city's repurposed NPCs here.
 */
object WarNpcNames {
    private val byKey = mapOf(
        "npc.knight_of_saradomin" to "Knight of Lumbridge",
        "npc.melee_combat_tutor" to "General Zo",
    )

    /** Apply the display-name override (if any) for [rscmKey]. Call AFTER `world.spawn(npc)`
     *  — the npc's avatar is `lateinit` and only assigned during spawn. */
    fun apply(npc: Npc, rscmKey: String) {
        byKey[rscmKey]?.let { NpcInfo(npc).setTempName(it) }
    }

    /** Force an explicit display [name] onto [npc] (for content that names a stock npc at
     *  runtime, e.g. a repurposed boss). Call AFTER `world.spawn(npc)`. */
    fun rename(npc: Npc, name: String) {
        NpcInfo(npc).setTempName(name)
    }
}
