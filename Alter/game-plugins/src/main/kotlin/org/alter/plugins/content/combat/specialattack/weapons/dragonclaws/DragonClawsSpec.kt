package org.alter.plugins.content.combat.specialattack.weapons.dragonclaws

/**
 * The exact OSRS dragon claws damage table (OSRS Wiki, Dragon claws — special attack).
 * Four accuracy rolls; the FIRST successful roll picks the pattern:
 *
 *  roll 1: X ∈ [max/2, max-1]        → hits X, X/2, X/4, X/4 (+1 into the last)
 *  roll 2: X ∈ [3max/8, 7max/8]      → hits 0, X, X/2, X/2 (+1 into the last)
 *  roll 3: X ∈ [max/4, 3max/4]       → hits 0, 0, X, X + 1
 *  roll 4: X ∈ [max/4, 5max/4]       → hits 0, 0, 0, X
 *  none:   25% chance of 0, 0, 1, 1; otherwise four zeros
 *
 * Pure so it can be golden-tested: [roll] receives an inclusive range and returns a
 * uniform pick from it; [chance] is a 0..1 uniform for the consolation branch.
 */
object DragonClawsSpec {
    fun rollDamages(
        maxHit: Int,
        rolls: BooleanArray,
        roll: (IntRange) -> Int,
        chance: () -> Double,
    ): IntArray {
        require(rolls.size == 4)
        val damages = IntArray(4)
        val max = maxHit.coerceAtLeast(1)
        when {
            rolls[0] -> {
                val x = roll(max / 2..(max - 1).coerceAtLeast(max / 2))
                damages[0] = x
                damages[1] = x / 2
                damages[2] = x / 4
                damages[3] = x / 4 + 1
            }
            rolls[1] -> {
                val x = roll(max * 3 / 8..max * 7 / 8)
                damages[1] = x
                damages[2] = x / 2
                damages[3] = x / 2 + 1
            }
            rolls[2] -> {
                val x = roll(max / 4..max * 3 / 4)
                damages[2] = x
                damages[3] = x + 1
            }
            rolls[3] -> {
                val x = roll(max / 4..max * 5 / 4)
                damages[3] = x
            }
            else -> {
                if (chance() < 0.25) {
                    damages[2] = 1
                    damages[3] = 1
                }
            }
        }
        return damages
    }
}
