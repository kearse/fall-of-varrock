package org.alter.plugins.content.areas.lumbridge.spawns

import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository


class ChatSpawnsPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        // Will slowly remove stuff from here and move it to the respective NPC plugins

        // Outside
        // Donie removed (decluttered). Hatius Cosaintus (diary) + Lumbridge Guide (tutorial)
        // removed — those systems aren't live. Father Aereck kept for church flavour.
        spawnNpc("npc.father_aereck", 3243, 3206, 0, 3)

        // Castle - outside
        spawnNpc("npc.perdu", 3230, 3215, 0, 10, Direction.SOUTH)

        // Castle - inside
        spawnNpc("npc.banker_2897", 3209, 3222, 2, 0, Direction.SOUTH)
        // Duke Horacio — the feudal rank vendor. On the GROUND floor in the market near the other
        // vendors, pinned (walkRadius 0) so he never wanders off, facing EAST.
        spawnNpc("npc.duke_horacio", 3218, 3220, 0, 0, Direction.NORTH)

        // Combat/woodsman tutors removed to declutter the Lumbridge surface.
        // NB: do NOT re-add npc.melee_combat_tutor here — that id is repurposed as General Zo,
        // who is spawned and owned by the war's AttackDirector (see GeneralZoPlugin).

        // Smithing Apprentice — the metal-armour gear vendor, stationed right at the Lumbridge
        // furnace (furnace object @3227,3257). His shop logic + a cellar copy live in
        // SmithingApprenticePlugin; this is the surface vendor. Pinned (walkRadius 0) so he stays
        // at the furnace.
        spawnNpc("npc.smithing_apprentice", 3228, 3256, 0, 0, Direction.WEST)

        // Count Check (xp tutorial) + Nigel (tutorial) removed — decluttered.
    }
}
