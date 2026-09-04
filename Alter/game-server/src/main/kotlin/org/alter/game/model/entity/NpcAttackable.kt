package org.alter.game.model.entity

/**
 * Can a player raise a weapon against this npc?
 *
 * The cache answer (`NpcType.isAttackable()` = combat level > 0 AND an `Attack` action) is
 * right for every ordinary monster, but a handful of scripted npcs carry an `Attack` action at
 * combat level 0 - Skotizo's awakened altars, for one - and the plugin that owns them opts in
 * through [org.alter.game.model.combat.NpcCombatDef.forceAttackable]. `combatDef` is assigned
 * by `World.spawn` before the npc is reachable, so it is safe to read here.
 */
fun Npc.isPlayerAttackable(): Boolean =
    def.isAttackable() || (combatDef.forceAttackable && combatDef.hitpoints > 0)
