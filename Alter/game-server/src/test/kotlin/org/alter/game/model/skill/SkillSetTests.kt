package org.alter.game.model.skill

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * @author Tom <rspsmods@gmail.com>
 */
class SkillSetTests {
    private fun createSkills(): SkillSet = SkillSet(SKILL_COUNT)

    @Test
    fun `set xp for skills`() {
        val skills = createSkills()

        val xp = 6_300_000.0
        skills.setXp(ATTACK_SKILL, xp)
        assertEquals(xp, skills.getCurrentXp(ATTACK_SKILL))
        assertEquals(xp, skills.calculateTotalXp)

        val lvl = SkillSet.getLevelForXp(xp)
        assertEquals(lvl, skills.getBaseLevel(ATTACK_SKILL))
    }

    @Test
    fun `alter levels (capped)`() {
        val skills = createSkills()

        val baseLevel = 50
        skills.setBaseLevel(ATTACK_SKILL, baseLevel)
        assertEquals(skills.getCurrentLevel(ATTACK_SKILL), baseLevel)
        assertEquals(skills.getBaseLevel(ATTACK_SKILL), baseLevel)

        val decrement = 3
        skills.decrementCurrentLevel(ATTACK_SKILL, decrement, capped = true)
        assertEquals(baseLevel - decrement, skills.getCurrentLevel(ATTACK_SKILL))
        assertEquals(baseLevel, skills.getBaseLevel(ATTACK_SKILL))

        skills.decrementCurrentLevel(ATTACK_SKILL, decrement, capped = true)
        assertEquals(baseLevel - decrement, skills.getCurrentLevel(ATTACK_SKILL))

        skills.restore(ATTACK_SKILL)
        assertEquals(baseLevel, skills.getCurrentLevel(ATTACK_SKILL))

        val increment = 3
        skills.incrementCurrentLevel(ATTACK_SKILL, increment, capped = false)
        assertEquals(baseLevel + increment, skills.getCurrentLevel(ATTACK_SKILL))
        assertEquals(baseLevel, skills.getBaseLevel(ATTACK_SKILL))

        skills.incrementCurrentLevel(ATTACK_SKILL, increment, capped = false)
        assertEquals(baseLevel + increment, skills.getCurrentLevel(ATTACK_SKILL))
    }

    @Test
    fun `alter levels (uncapped)`() {
        val skills = createSkills()

        val baseLevel = 50
        skills.setBaseLevel(ATTACK_SKILL, baseLevel)
        assertEquals(skills.getCurrentLevel(ATTACK_SKILL), baseLevel)
        assertEquals(skills.getBaseLevel(ATTACK_SKILL), baseLevel)

        val decrement = 3
        skills.decrementCurrentLevel(ATTACK_SKILL, decrement, capped = false)
        assertEquals(skills.getCurrentLevel(ATTACK_SKILL), baseLevel - decrement)
        assertEquals(skills.getBaseLevel(ATTACK_SKILL), baseLevel)

        skills.decrementCurrentLevel(ATTACK_SKILL, decrement, capped = false)
        assertEquals(skills.getCurrentLevel(ATTACK_SKILL), baseLevel - (decrement * 2))

        skills.restore(ATTACK_SKILL)
        assertEquals(skills.getCurrentLevel(ATTACK_SKILL), baseLevel)

        val increment = 3
        skills.incrementCurrentLevel(ATTACK_SKILL, increment, capped = false)
        assertEquals(skills.getCurrentLevel(ATTACK_SKILL), baseLevel + increment)
        assertEquals(skills.getBaseLevel(ATTACK_SKILL), baseLevel)

        skills.alterCurrentLevel(ATTACK_SKILL, increment, capValue = increment * 2)
        assertEquals(skills.getCurrentLevel(ATTACK_SKILL), baseLevel + (increment * 2))
    }

    @Test
    fun `restore does not lower a boosted stat`() {
        val skills = createSkills()
        skills.setBaseLevel(ATTACK_SKILL, 99)

        // Super combat at 99: +5 + 15% = +19 -> 118/99.
        skills.alterCurrentLevel(ATTACK_SKILL, 19, capValue = 19)
        assertEquals(118, skills.getCurrentLevel(ATTACK_SKILL))

        // Super restore at 99: +8 + 25% = +32, capped at base — must not touch the boost.
        skills.alterCurrentLevel(ATTACK_SKILL, 32, capValue = 0)
        assertEquals(118, skills.getCurrentLevel(ATTACK_SKILL))
    }

    @Test
    fun `restore raises a lowered stat up to base only`() {
        val skills = createSkills()
        skills.setBaseLevel(PRAYER_SKILL, 99)
        skills.decrementCurrentLevel(PRAYER_SKILL, 39, capped = true)
        assertEquals(60, skills.getCurrentLevel(PRAYER_SKILL))

        skills.alterCurrentLevel(PRAYER_SKILL, 32, capValue = 0)
        assertEquals(92, skills.getCurrentLevel(PRAYER_SKILL))

        skills.alterCurrentLevel(PRAYER_SKILL, 32, capValue = 0)
        assertEquals(99, skills.getCurrentLevel(PRAYER_SKILL))
    }

    @Test
    fun `weaker boost does not lower a stronger boost`() {
        val skills = createSkills()
        skills.setBaseLevel(ATTACK_SKILL, 99)

        // Super attack: +19 -> 118/99.
        skills.alterCurrentLevel(ATTACK_SKILL, 19, capValue = 19)
        assertEquals(118, skills.getCurrentLevel(ATTACK_SKILL))

        // Regular attack potion at 99: +3 + 10% = +12 (cap 111) — no effect while above its cap.
        skills.alterCurrentLevel(ATTACK_SKILL, 12, capValue = 12)
        assertEquals(118, skills.getCurrentLevel(ATTACK_SKILL))
    }

    @Test
    fun `re-drinking the same boost does not stack`() {
        val skills = createSkills()
        skills.setBaseLevel(STRENGTH_SKILL, 50)

        skills.alterCurrentLevel(STRENGTH_SKILL, 10, capValue = 10)
        assertEquals(60, skills.getCurrentLevel(STRENGTH_SKILL))

        skills.alterCurrentLevel(STRENGTH_SKILL, 10, capValue = 10)
        assertEquals(60, skills.getCurrentLevel(STRENGTH_SKILL))
    }

    @Test
    fun `drains still lower boosted stats`() {
        val skills = createSkills()
        skills.setBaseLevel(DEFENCE_SKILL, 50)

        skills.alterCurrentLevel(DEFENCE_SKILL, 10, capValue = 10)
        assertEquals(60, skills.getCurrentLevel(DEFENCE_SKILL))

        skills.alterCurrentLevel(DEFENCE_SKILL, -5, capValue = -50)
        assertEquals(55, skills.getCurrentLevel(DEFENCE_SKILL))
    }

    @Test(expected = IllegalStateException::class)
    fun `alter level illegally with different cap signum`() {
        val skills = createSkills()

        val baseLevel = 50
        val decrement = 3
        skills.setBaseLevel(ATTACK_SKILL, baseLevel)
        skills.alterCurrentLevel(ATTACK_SKILL, -decrement, decrement)
        assertEquals(skills.getCurrentLevel(ATTACK_SKILL), baseLevel)
    }

    @Suppress("unused")
    companion object {
        private const val SKILL_COUNT = 24

        private const val ATTACK_SKILL = 0
        private const val DEFENCE_SKILL = 1
        private const val STRENGTH_SKILL = 2
        private const val HITPOINTS_SKILL = 3
        private const val RANGED_SKILL = 4
        private const val PRAYER_SKILL = 5
        private const val MAGIC_SKILL = 6
        private const val COOKING_SKILL = 7
        private const val WOODCUTTING_SKILL = 8
        private const val FLETCHING_SKILL = 9
        private const val FISHING_SKILL = 10
        private const val FIREMAKING_SKILL = 11
        private const val CRAFTING_SKILL = 12
        private const val SMITHING_SKILL = 13
        private const val MINING_SKILL = 14
        private const val HERBLORE_SKILL = 15
        private const val AGILITY_SKILL = 16
        private const val THIEVING_SKILL = 17
        private const val SLAYER_SKILL = 18
        private const val FARMING_SKILL = 19
        private const val RUNECRAFTING_SKILL = 20
        private const val HUNTER_SKILL = 21
        private const val CONSTRUCTION_SKILL = 22
    }
}
