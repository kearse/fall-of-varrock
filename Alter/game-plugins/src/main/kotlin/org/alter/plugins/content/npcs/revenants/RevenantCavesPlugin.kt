package org.alter.plugins.content.npcs.revenants

import dev.openrune.cache.CacheManager.getItem
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.*
import org.alter.api.dsl.setCombatDef
import org.alter.api.ext.*
import org.alter.game.*
import org.alter.game.model.*
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.entity.*
import org.alter.game.model.move.moveTo
import org.alter.game.model.queue.*
import org.alter.game.plugin.*
import org.alter.plugins.content.bosses.*
import org.alter.plugins.content.combat.*
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints
import org.alter.plugins.content.economy.awardTickets
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Revenant Caves** — the wilderness multi-NPC cave (Phase G "quick win"; *not* a raid, so no
 * instancing/framework). Spawns the revenant roster scattered through the cave; each switches
 * attack style each turn (melee/ranged/magic, the revenants' signature) and rolls the shared
 * revenant drop table on death, scaled by the revenant's tier.
 *
 * Combat reuses the shared `BossCombat.kt` primitives. Cave coords + projectile gfx are TUNE
 * (verify in-game); the cave region is force-loaded via [org.alter.plugins.content.bosses.BossLairs].
 */
class RevenantCavesPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private data class Rev(val key: String, val name: String, val hp: Int, val combat: Int, val maxHit: Int, val count: Int)

    // Tier scales hp / stats / max hit / loot rolls. Stats derived from hp.
    private val revs = listOf(
        Rev("npc.revenant_imp", "Revenant imp", 10, 7, 4, 4),
        Rev("npc.revenant_goblin", "Revenant goblin", 10, 15, 4, 4),
        Rev("npc.revenant_pyrefiend", "Revenant pyrefiend", 20, 52, 6, 3),
        Rev("npc.revenant_hobgoblin", "Revenant hobgoblin", 30, 60, 7, 3),
        Rev("npc.revenant_cyclops", "Revenant cyclops", 40, 82, 9, 3),
        Rev("npc.revenant_hellhound", "Revenant hellhound", 50, 90, 10, 3),
        Rev("npc.revenant_demon", "Revenant demon", 60, 98, 12, 3),
        Rev("npc.revenant_ork", "Revenant ork", 70, 105, 14, 2),
        Rev("npc.revenant_dark_beast", "Revenant dark beast", 90, 120, 16, 2),
        Rev("npc.revenant_knight", "Revenant knight", 105, 126, 22, 2),
        Rev("npc.revenant_dragon", "Revenant dragon", 105, 135, 25, 2),
        Rev("npc.revenant_maledictus", "Revenant Maledictus", 300, 280, 30, 1),
    )

    init {
        var i = 0
        for (rev in revs) {
            val stat = (rev.hp + 20).coerceAtMost(255)
            repeat(rev.count) {
                val t = scatterTile(i++)
                spawnNpc(rev.key, t.x, t.z, t.height, walkRadius = 5)
            }

            setCombatDef(rev.key) {
                configs { attackSpeed = 4; respawnDelay = 40 }
                aggro { radius = 10; searchDelay = 1 }
                stats { hitpoints = rev.hp; attack = stat; strength = stat; defence = stat; magic = stat; ranged = stat }
                bonuses {
                    attackBonus = 80; strengthBonus = 70
                    defenceStab = 80; defenceSlash = 80; defenceCrush = 80; defenceMagic = 80; defenceRanged = 80
                }
                anims { death = deathAnimFor(rev.key) }
            }

            onNpcCombat(rev.key) { npc.queue { npc.revCombat(this, rev.maxHit) } }

            onNpcDeath(rev.key) {
                val killer = npc.attr[KILLER_ATTR]?.get() as? Player ?: return@onNpcDeath
                loot(npc, killer, rev)
            }
        }

        onCommand("revcaves", description = "Teleport to the Revenant Caves") {
            player.moveTo(CAVE_CENTRE)
            player.message("<col=8f0000>You enter the Revenant Caves. Beware — multi-way and PvP.</col>")
        }
    }

    private suspend fun Npc.revCombat(it: QueueTask, maxHit: Int) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(it, target, distance = 6, projectile = true) && isAttackDelayReady()) {
                // Revenants switch style each attack; a landed hit also saps a little prayer.
                val landed = when (world.random(2)) {
                    0 -> bossMelee(target, maxHit)
                    1 -> bossProjectile(target, CombatClass.RANGED, maxHit, gfx = RANGE_GFX)
                    else -> bossProjectile(target, CombatClass.MAGIC, maxHit, gfx = MAGIC_GFX)
                }
                if (landed && target is Player && world.chance(1, 4)) {
                    val pr = target.getSkills().getCurrentLevel(Skills.PRAYER)
                    if (pr > 0) target.getSkills().alterCurrentLevel(Skills.PRAYER, -1)
                }
                postAttackLogic(target)
            }
            it.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    private fun loot(npc: Npc, killer: Player, rev: Rev) {
        killer.awardTickets(PointKind.BOSS, (rev.maxHit / 5).coerceAtLeast(1))
        // Reward rolls scale with tier; the shared revenant rares are gated by oneInN inside the table.
        val rolls = 1 + rev.hp / 60
        repeat(rolls) {
            TABLE.roll(world).forEach { drop ->
                val id = runCatching { getRSCM(drop.item) }.getOrNull() ?: return@forEach
                world.spawn(GroundItem(id, drop.amount, npc.tile, killer))
                val name = runCatching { getItem(id).name }.getOrNull() ?: return@forEach
                if (drop.announce) world.players.forEach { it.message("<col=ff0000>News: ${killer.username} just received <col=ffae00>$name</col> in the Revenant Caves!</col>") }
                if (drop.log && CollectionLog.record(killer, id)) killer.message("<col=ffae00>New Collection Log slot: $name!</col>")
            }
        }
    }

    /** Scatter spawns through the cave around [CAVE_CENTRE]. */
    private fun scatterTile(i: Int): Tile {
        val dx = (i * 7) % 40 - 20
        val dz = (i * 5) % 40 - 20
        return Tile(CAVE_CENTRE.x + dx, CAVE_CENTRE.z + dz, CAVE_CENTRE.height)
    }

    private companion object {
        const val MAGIC_GFX = 159
        const val RANGE_GFX = 1466
        val CAVE_CENTRE = Tile(3220, 10140, 0) // TUNE: Revenant Caves interior

        /** Shared revenant drop table (rares gated by oneInN; missing keys skipped). */
        val TABLE = DropTable(
            main = listOf(
                DropEntry("item.coins_995", 5_000, 30_000, weight = 40),
                DropEntry("item.death_rune", 30, 120, weight = 18),
                DropEntry("item.blood_rune", 30, 100, weight = 14),
                DropEntry("item.adamant_arrow", 50, 200, weight = 12),
            ),
            // Signature: the three revenant-exclusive weapons (Craw's bow / Viggora's chainmace /
            // Thammaron's sceptre), the Amulet of avarice, the ancient relic/medallion + emblems.
            rare = listOf(
                DropEntry("item.craws_bow", 1, 1, oneInN = 1200, announce = true, log = true),
                DropEntry("item.viggoras_chainmace", 1, 1, oneInN = 1200, announce = true, log = true),
                DropEntry("item.thammarons_sceptre", 1, 1, oneInN = 1200, announce = true, log = true),
                DropEntry("item.amulet_of_avarice", 1, 1, oneInN = 1000, announce = true, log = true),
                DropEntry("item.ancient_relic", 1, 1, oneInN = 800, announce = true, log = true),
                DropEntry("item.ancient_medallion", 1, 1, oneInN = 800, announce = true, log = true),
                DropEntry("item.ancient_emblem", 1, 1, oneInN = 200, announce = true, log = true),
                DropEntry("item.ancient_totem", 1, 1, oneInN = 250, announce = true, log = true),
                DropEntry("item.ancient_statuette", 1, 1, oneInN = 300, announce = true, log = true),
                DropEntry("item.bracelet_of_ethereum", 1, 1, oneInN = 250, announce = true, log = true),
                DropEntry("item.revenant_cave_teleport", 1, 3, oneInN = 50),
            ),
        )
    }
}
