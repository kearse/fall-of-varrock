package org.alter.plugins.content.war

import org.alter.api.ext.message
import org.alter.game.model.World
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.rscm.RSCM.getRSCM

/**
 * War loot (The War). Killing the AI's elite units pays off in the gear the
 * Smithing Apprentice deliberately WON'T sell — **platebodies, full helms, and
 * kiteshields of your current feudal class** ([Player.title] → [ArmourTier]) — so the
 * battle is the only way to complete a set above the lesser pieces.
 *
 *  - **Shock troops** (the commander's main-thrust elite goblins): extra coins + an
 *    occasional class piece.
 *  - **The Warlord**: a big coin hoard + a guaranteed class piece + a rare shot at a
 *    piece of the NEXT class up (a taste of gear you can't wear yet).
 *
 * Loot drops to the ground at the kill, owned by the killer (RS-style).
 */
object WarDrops {
    private val PIECES = listOf("platebody", "full_helm", "kiteshield")
    private val coinId by lazy { getRSCM("item.coins_995") }

    fun onShockTroopKill(world: World, player: Player, npc: Npc) {
        drop(world, player, npc, coinId, world.random(ELITE_COINS_MIN..ELITE_COINS_MAX))
        if (world.random(ELITE_PIECE_DENOM - 1) == 0) {
            rollPiece(player.title.maxTier)?.let { (id, label) ->
                drop(world, player, npc, id, 1)
                player.message("<col=801700>The shock trooper was carrying a $label!</col>")
            }
        }
    }

    fun onWarlordKill(world: World, player: Player, npc: Npc) {
        drop(world, player, npc, coinId, world.random(WARLORD_COINS_MIN..WARLORD_COINS_MAX))
        player.message("<col=4f9b4f>The Goblin Warlord falls — you plunder its hoard!</col>")
        // Guaranteed piece of your current class.
        rollPiece(player.title.maxTier)?.let { (id, label) ->
            drop(world, player, npc, id, 1)
            player.message("<col=801700>The Warlord drops a $label!</col>")
        }
        // Rare: a piece of the NEXT class up — gear beyond your station.
        if (world.random(WARLORD_RARE_DENOM - 1) == 0) {
            nextTier(player.title.maxTier)?.let { nt ->
                rollPiece(nt)?.let { (id, label) ->
                    drop(world, player, npc, id, 1)
                    player.message("<col=ffcc00>RARE DROP! A $label spills from the hoard.</col>")
                }
            }
        }
    }

    /** A random platebody/full-helm/kiteshield of [tier] (id + readable label), or null. */
    private fun rollPiece(tier: ArmourTier): Pair<Int, String>? {
        val available = PIECES.mapNotNull { piece ->
            val id = try { getRSCM("item.${tier.display}_$piece") } catch (e: Exception) { null }
            if (id != null) id to "${tier.display} ${piece.replace('_', ' ')}" else null
        }
        return if (available.isEmpty()) null else available.random()
    }

    private fun nextTier(tier: ArmourTier): ArmourTier? = ArmourTier.values().getOrNull(tier.ordinal + 1)

    private fun drop(world: World, player: Player, npc: Npc, item: Int, amount: Int) {
        if (amount <= 0) return
        world.spawn(GroundItem(item, amount, npc.tile, player))
    }

    private const val ELITE_COINS_MIN = 120
    private const val ELITE_COINS_MAX = 280
    private const val ELITE_PIECE_DENOM = 20 // ~1 in 20 shock troops drops a class piece
    private const val WARLORD_COINS_MIN = 2_000
    private const val WARLORD_COINS_MAX = 5_000
    private const val WARLORD_RARE_DENOM = 5 // ~1 in 5 Warlords drops a next-class piece
}
