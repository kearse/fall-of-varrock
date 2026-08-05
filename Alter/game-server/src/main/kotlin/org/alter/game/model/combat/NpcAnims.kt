package org.alter.game.model.combat

import dev.openrune.cache.CacheManager

/**
 * Resolves **animations an npc's model can actually play**.
 *
 * A sequence's frame ids pack the *frame archive* — the skeleton its frames were authored
 * against — into their high 16 bits. An npc rigged to one skeleton cannot play an animation
 * authored for another: the client tries anyway and the npc contorts, freezes, or T-poses.
 *
 * This kept happening because [NpcCombatDef.DEFAULT] falls back to animation 422, the **human
 * unarmed punch**. Every world-spawn monster inherits that default, so goblins (archive 1576,
 * animations 6180-6190) were swinging with a human skeleton. Hand-written defs drifted the same
 * way — the frontier hobgoblins were given the goblin set (6189/6190/6191) when the hobgoblin
 * lives in archive 425 (animations 162-167).
 *
 * So combat asks here before animating: an explicitly configured animation is honoured whenever
 * it's playable, and anything foreign is swapped for the npc's own equivalent.
 *
 * ### Picking the animation
 * Sharing an archive isn't enough on its own — archive 1576 covers goblins *and* every other
 * creature on that skeleton, so "the longest animation in the archive" lands on some unrelated
 * cutscene. The creature's own animations sit in a contiguous run of sequence ids around its
 * stand/walk pair, so the run is the search space:
 *
 *  - **death** = the longest-held animation in the run. Death animations end on a frame with a
 *    huge delay, because the corpse pose has to persist (hobgoblin 167 holds for 20027 ticks).
 *  - **attack** = the shortest. Attacks are snappy enough to fit a weapon-speed cycle.
 *  - **block** = the shortest of what's left — a flinch, longer than a swing, shorter than a death.
 *
 * Verified against the two creatures whose real ids are known independently: goblin (archive 1576,
 * run 6180-6190) resolves death 6182 / attack 6185, and hobgoblin (archive 425, run 162-167)
 * resolves death 167 / attack 164 / block 165 — the documented hobgoblin ids.
 *
 * Inspect any npc's playable set with:
 *   `gradlew :game-server:npcDef -PnpcArgs="anims <npcId>"`
 */
object NpcAnims {
    /** The animations resolved for one npc; any field is -1 when the cache offers no candidate. */
    data class Set(val attack: Int, val block: Int, val death: Int)

    private val EMPTY = Set(-1, -1, -1)

    /**
     * Creatures whose real animation ids are known, keyed by frame archive (every variant id of a
     * creature shares its archive, so one entry covers all of them). The heuristic below is good
     * but approximate; these are exact. Add to this as ids are confirmed in-game.
     */
    private val KNOWN = mapOf(
        1576 to Set(attack = 6184, block = 6183, death = 6182), // goblin
        425 to Set(attack = 164, block = 165, death = 167),     // hobgoblin
    )

    /** [NpcCombatDef.DEFAULT]'s unarmed human fallbacks — the anims [ARMED] replaces. */
    private const val HUMAN_PUNCH = 422
    private const val HUMAN_UNARMED_BLOCK = 424

    /**
     * Armed humanoids stuck with the DEFAULT unarmed 422/424, keyed by npc id. A black knight
     * model swings a black sword and carries a kiteshield, but its defs inherit the human PUNCH
     * and bare-arms block — and because both are playable on the human skeleton, the [playable]
     * check can never flag them. The replacements are the sword slash (390) and shield block
     * (1156) the player combat tables already use on the same skeleton. Only the two unarmed
     * defaults are ever replaced — a def that configures its own animation is honoured. Add
     * armed humanoids here as they're reported. (death stays -1: the human death 836 is right.)
     */
    private val ARMED = mapOf(
        // The black knight family (sword + kiteshield): ambient/warden/port-raider 516-517,
        // the Falador frontier line 4959-4960, and the captain 4777.
        516 to Set(attack = 390, block = 1156, death = -1),
        517 to Set(attack = 390, block = 1156, death = -1),
        4777 to Set(attack = 390, block = 1156, death = -1),
        4959 to Set(attack = 390, block = 1156, death = -1),
        4960 to Set(attack = 390, block = 1156, death = -1),
    )

    /** npcId -> its resolved set. */
    private val byNpc = HashMap<Int, Set>()

    /** Every stand/walk animation in the cache — idles, never attacks. Built once, on first use. */
    private val idleAnims: kotlin.collections.Set<Int> by lazy {
        val idles = HashSet<Int>()
        CacheManager.getNpcs().values.forEach { npc ->
            if (npc.standAnim > 0) idles.add(npc.standAnim)
            if (npc.walkAnim > 0) idles.add(npc.walkAnim)
        }
        idles
    }

    /** npcId -> the frame archives its own animations are built on. */
    private val archivesByNpc = HashMap<Int, kotlin.collections.Set<Int>>()

    /** The frame archive [seq] is authored against, or null if it has no frames. */
    private fun archiveOf(seq: Int): Int? =
        CacheManager.getAnims()[seq]?.frameIDs?.firstOrNull()?.ushr(16)

    /** Total held duration of [seq] in client ticks — the signal that separates the three kinds. */
    private fun durationOf(seq: Int): Int =
        CacheManager.getAnims()[seq]?.frameDelays?.sum() ?: 0

    private fun archivesFor(npcId: Int): kotlin.collections.Set<Int> =
        archivesByNpc.getOrPut(npcId) {
            val def = CacheManager.getNpcOrDefault(npcId)
            listOfNotNull(archiveOf(def.standAnim), archiveOf(def.walkAnim)).toSet()
        }

    /**
     * Can [npcId]'s model play [anim] without deforming? True for -1 (no animation) and for any
     * animation on the npc's own skeleton. Npcs with no animations of their own (statues, most
     * scenery-like npcs) claim nothing is playable, so callers fall through to -1.
     */
    fun playable(npcId: Int, anim: Int): Boolean {
        if (anim == -1) return true
        val archives = archivesFor(npcId)
        if (archives.isEmpty()) return false
        return archiveOf(anim) in archives
    }

    /**
     * [configured] if that animation is playable on [npcId], otherwise the npc's own equivalent.
     *
     * Deliberately conservative: a *replacement* is only made when one was actually found. Some
     * npcs (Knight of Saradomin, archive 207) ship almost no animations of their own, and blanking
     * their configured animation would be a worse regression than leaving a questionable one in
     * place. So this only ever swaps one animation for another — it never removes one.
     */
    private fun coerce(npcId: Int, configured: Int, own: (Set) -> Int): Int {
        if (playable(npcId, configured)) return configured
        val replacement = own(resolve(npcId))
        return if (replacement != -1) replacement else configured
    }

    fun coerceAttack(npcId: Int, configured: Int): Int {
        // Armed-humanoid fix ([ARMED]): swap the unarmed default for the weapon swing. Guarded by
        // playable() like everything else so a bad table entry can never deform the model.
        ARMED[npcId]?.let { if (configured == HUMAN_PUNCH && playable(npcId, it.attack)) return it.attack }
        return coerce(npcId, configured) { it.attack }
    }

    fun coerceBlock(npcId: Int, configured: Int): Int {
        ARMED[npcId]?.let { if (configured == HUMAN_UNARMED_BLOCK && playable(npcId, it.block)) return it.block }
        return coerce(npcId, configured) { it.block }
    }

    fun coerceDeath(npcId: Int, configured: Int): Int = coerce(npcId, configured) { it.death }

    /** The attack/block/death animations belonging to [npcId] itself. Cached; safe to call hot. */
    fun resolve(npcId: Int): Set = byNpc.getOrPut(npcId) { compute(npcId) }

    private fun compute(npcId: Int): Set {
        val def = CacheManager.getNpcOrDefault(npcId)
        val archives = archivesFor(npcId)
        if (archives.isEmpty()) return EMPTY

        // Walk outwards from the npc's own stand/walk anims for as long as the ids stay on its
        // skeleton. That contiguous run is the creature's animation block; everything else in the
        // archive belongs to other creatures rigged the same way.
        KNOWN[archives.first()]?.let { return it }

        val anchors = listOf(def.standAnim, def.walkAnim).filter { it > 0 }
        if (anchors.isEmpty()) return EMPTY
        val run = HashSet<Int>()
        for (anchor in anchors) {
            var i = anchor
            while (archiveOf(i) in archives) run.add(i--)
            i = anchor + 1
            while (archiveOf(i) in archives) run.add(i++)
        }

        // Drop every idle in the run, not just this npc's own: the run spans all the creatures on
        // this skeleton, and a sibling variant's walk animation is no more an attack than ours is.
        val candidates = run.filter { it !in idleAnims }.sorted()
        if (candidates.isEmpty()) return EMPTY

        // Longest hold = death (the corpse pose has to persist). Ties go to the lowest id.
        val death = candidates.maxByOrNull { durationOf(it).toLong() * 100_000 - it }!!
        val rest = candidates.filter { it != death }
        // Shortest = attack (it has to fit inside a weapon-speed cycle); next shortest = block.
        val attack = rest.minByOrNull { durationOf(it).toLong() * 100_000 + it } ?: -1
        val block = rest.filter { it != attack }.minByOrNull { durationOf(it).toLong() * 100_000 + it } ?: attack
        return Set(attack, block, death)
    }
}
