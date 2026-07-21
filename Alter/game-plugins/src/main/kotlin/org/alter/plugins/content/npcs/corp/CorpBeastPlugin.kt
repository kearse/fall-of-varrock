package org.alter.plugins.content.npcs.corp

import dev.openrune.cache.CacheManager.getItem
import org.alter.api.*
import org.alter.api.dsl.*
import org.alter.api.ext.*
import org.alter.game.*
import org.alter.game.model.*
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.combat.AttackStyle
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.combat.CombatStyle
import org.alter.game.model.entity.*
import org.alter.game.model.queue.*
import org.alter.game.plugin.*
import org.alter.plugins.content.announce.Announce
import org.alter.plugins.content.bosses.CollectionLog
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable
import org.alter.plugins.content.combat.*
import org.alter.plugins.content.combat.formula.DragonfireFormula
import org.alter.plugins.content.combat.formula.MagicCombatFormula
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.strategy.RangedCombatStrategy
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.awardTickets
import org.alter.plugins.content.war.boss.BossScheduler
import org.alter.rscm.RSCM.getRSCM

/**
 * Bespoke combat AI **and lifecycle** for the **Corporeal Beast**, which exists in two forms
 * sharing one npc id:
 *  - a **permanent lair spawn** at its cave (always available, respawns on a timer, drops its own
 *    table on the corpse — see [LAIR_LOOT]); and
 *  - an occasional **Lumbridge world-boss event** spawned by
 *    [org.alter.plugins.content.war.boss.BossScheduler], whose spoils are split by damage
 *    contribution ([org.alter.plugins.content.war.boss.BossLoot]) and whose avatar-tier uniques
 *    roll at boosted odds ([org.alter.plugins.content.war.boss.BossRegistry.eventUniqueMultiplier]).
 *
 * This plugin registers the shared combat definition (attack speed / bonuses / aggro / anims) and
 * attack AI, spawns the permanent lair copy, and owns the **single** death handler for the npc id
 * (only one may bind) — dispatching a tracked event kill back to [BossScheduler.onBossDeath] and
 * otherwise dropping the lair table itself.
 *
 * `onNpcCombat` OVERRIDES the global combat logic for this npc id (see
 * `PluginRepository.executeNpcCombat`), so this fully replaces the default melee-only behaviour
 * the old boss stand-ins used.
 *
 * The fight has two phases driven by HP:
 *  - **Normal** — a mix of crushing melee (when a target is adjacent) and dark-energy magic
 *    bolts at range, with the occasional **dark-energy nova** (its signature point-blank AoE
 *    that hits everyone around the target and saps their combat stats).
 *  - **Enrage** (below [ENRAGE_PCT]% HP) — a one-time server roar, then harder hits and far
 *    more frequent novas.
 *
 * NOTE: the animation/projectile ids below are the Corporeal Beast's cache ids to the best of
 * our knowledge — a wrong id only means a missing visual, never a crash, so they're safe to
 * tune in-game. All damage numbers are gathered as `*_MAX` constants for easy balancing.
 */
class CorpBeastPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        // Combat def. BossScheduler.applyStats later copies this with the BossDef's atk/str/def/hp
        // overrides, preserving everything set here (attack speed, bonuses, aggro, anims).
        setCombatDef("npc.corporeal_beast") {
            configs {
                attackSpeed = 5             // slow, heavy hitter
                // Positive delay => the permanent LAIR spawn respawns after each kill (World
                // .setNpcDefaults reads this). The world-boss event instance sets respawns=false on
                // itself after spawn (BossScheduler), so the scheduler still owns ITS lifecycle.
                respawnDelay = LAIR_RESPAWN
            }
            aggro {
                radius = 10
                searchDelay = 1
            }
            stats {
                hitpoints = 1200
                attack = 180
                strength = 200
                defence = 160
                magic = 300
            }
            bonuses {
                attackBonus = 150    // melee accuracy
                attackMagic = 200    // magic accuracy
                defenceStab = 50
                defenceSlash = 55
                defenceCrush = 55
                defenceMagic = 30    // mage is the soft spot (keeps it soloable)
                defenceRanged = 200  // brutal vs ranged — discourages safe-spotting from afar
            }
            anims {
                attack = ATTACK_ANIM
                block = BLOCK_ANIM
                death = DEATH_ANIM
            }
        }

        onNpcCombat("npc.corporeal_beast") {
            npc.queue { npc.combat(this) }
        }

        // The permanent lair copy — ALWAYS present at its cave (respawns on death). This is the
        // normal farm; the Lumbridge world boss is the separate, occasional event.
        // NOTE (TUNE): CAVE is best-known OSRS Corporeal Beast's Cave. Verify in-game that it lands
        // on solid floor at this plane — a wrong plane leaves the boss/loot floating (as Nex did).
        spawnNpc("npc.corporeal_beast", CAVE.x, CAVE.z, CAVE.height, walkRadius = 5)

        // The ONE death handler allowed per npc id, shared by both forms. A tracked event kill is
        // owned by the scheduler's contribution split; otherwise it's the lair copy → drop its own
        // table on the corpse so a normal kill actually yields loot.
        onNpcDeath("npc.corporeal_beast") {
            if (BossScheduler.onBossDeath(world, npc)) return@onNpcDeath
            val killer = npc.attr[KILLER_ATTR]?.get() as? Player ?: return@onNpcDeath
            dropLairLoot(npc, killer)
        }
    }

    /** Roll [LAIR_LOOT] onto the corpse for the permanent cave kill (single-killer, like other PvM
     *  bosses). Unknown cache ids are skipped so a kill never crashes on a missing item. */
    private fun dropLairLoot(npc: Npc, killer: Player) {
        killer.awardTickets(PointKind.BOSS, LAIR_BOSS_POINTS)
        LAIR_LOOT.roll(world).forEach { drop ->
            val id = runCatching { getRSCM(drop.item) }.getOrNull() ?: return@forEach
            world.spawn(GroundItem(id, drop.amount, npc.tile, killer))
            val name = runCatching { getItem(id).name }.getOrNull() ?: return@forEach
            if (drop.announce) {
                world.players.forEach {
                    it.message("<col=ff0000>News: ${killer.username} just received <col=ffae00>$name</col> from the Corporeal Beast!</col>")
                }
            }
            if (drop.log && CollectionLog.record(killer, id)) {
                killer.message("<col=ffae00>New Collection Log slot: $name!</col>")
            }
        }
        killer.message("<col=ff0000>You have slain the Corporeal Beast.</col> (+$LAIR_BOSS_POINTS Boss Tickets)")
    }

    private suspend fun Npc.combat(it: QueueTask) {
        // npc skill levels are NOT auto-populated from the combat def (mirrors BossScheduler /
        // HostileZone), so set Magic explicitly or the boss's spells would never land.
        stats.setMaxLevel(NpcSkills.MAGIC, MAGIC_LEVEL)
        stats.setCurrentLevel(NpcSkills.MAGIC, MAGIC_LEVEL)

        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            val enraged = checkEnrage()
            if (moveToAttackRange(it, target, distance = 8, projectile = true) && isAttackDelayReady()) {
                val roll = world.random(9) // 0..9
                when {
                    // Signature AoE: 1-in-5 normally, ~2-in-5 enraged.
                    roll <= (if (enraged) 3 else 1) -> darkEnergyNova(target, enraged)
                    // Crush them flat when they're in close.
                    world.chance(1, 2) && canAttackMelee(it, target, moveIfNeeded = false) -> meleeAttack(target, enraged)
                    // Otherwise a dark-energy bolt at range.
                    else -> magicAttack(target, enraged)
                }
                postAttackLogic(target)
            }
            it.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    /** True if HP has crossed the enrage threshold; broadcasts the roar exactly once. */
    private fun Npc.checkEnrage(): Boolean {
        val max = getMaxHp().coerceAtLeast(1)
        val enraged = getCurrentHp() <= max * ENRAGE_PCT / 100
        if (enraged && attr[ENRAGED_ATTR] != true) {
            attr[ENRAGED_ATTR] = true
            Announce.broadcast(world, "<col=ff0000>The Corporeal Beast lets out a deafening roar — its fury swells!</col>")
        }
        return enraged
    }

    private fun Npc.meleeAttack(target: Pawn, enraged: Boolean) {
        prepareAttack(CombatClass.MELEE, CombatStyle.CRUSH, AttackStyle.AGGRESSIVE)
        animate(ATTACK_ANIM)
        val max = if (enraged) MELEE_MAX_ENRAGED else MELEE_MAX
        if (MeleeCombatFormula.getAccuracy(this, target) >= world.randomDouble()) {
            target.hit(world.random(max), type = HitType.HIT, delay = 1)
        } else {
            target.hit(damage = 0, type = HitType.BLOCK, delay = 1)
        }
    }

    private fun Npc.magicAttack(target: Pawn, enraged: Boolean) {
        val projectile = createProjectile(target, gfx = MAGIC_PROJECTILE_GFX, startHeight = 43, endHeight = 31, delay = 51, angle = 15, steepness = 127)
        prepareAttack(CombatClass.MAGIC, CombatStyle.MAGIC, AttackStyle.ACCURATE)
        animate(ATTACK_ANIM)
        world.spawn(projectile)
        val max = if (enraged) MAGIC_MAX_ENRAGED else MAGIC_MAX
        dealHit(
            target = target,
            formula = DragonfireFormula(maxHit = max),
            delay = RangedCombatStrategy.getHitDelay(getFrontFacingTile(target), target.getCentreTile()) - 1,
        )
    }

    /**
     * The Corp Beast's signature: a point-blank nova of dark energy. Every player within
     * [NOVA_RADIUS] (or +1 enraged) tiles of the boss's current target takes magic damage
     * (halved by Protect from Magic) and risks having a combat stat drained.
     */
    private fun Npc.darkEnergyNova(target: Pawn, enraged: Boolean) {
        prepareAttack(CombatClass.MAGIC, CombatStyle.MAGIC, AttackStyle.ACCURATE)
        animate(ATTACK_ANIM)
        val centre = target.getCentreTile()
        val radius = NOVA_RADIUS + if (enraged) 1 else 0
        val max = if (enraged) NOVA_MAX_ENRAGED else NOVA_MAX
        val drainable = arrayOf(Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.MAGIC, Skills.RANGED)
        // PawnList has no filter() — iterate and gate inline.
        world.players.forEach { p ->
            if (p.index < 0 || p.isDead() || p.tile.getChebyshevDistance(centre) > radius) return@forEach
            p.graphic(id = NOVA_GFX, height = 124, delay = 2)
            if (MagicCombatFormula.getAccuracy(this, p) >= world.randomDouble()) {
                var dmg = world.random(NOVA_MIN..max)
                if (p.hasPrayerIcon(PrayerIcon.PROTECT_FROM_MAGIC)) dmg = (dmg * 0.6).toInt()
                p.hit(dmg, type = HitType.HIT, delay = 2)
                if (world.chance(1, 2)) {
                    val skill = drainable[world.random(drainable.size - 1)]
                    p.getSkills().alterCurrentLevel(skill, -3)
                    p.message("<col=8f00ff>The dark energy saps your strength!</col>")
                }
            } else {
                p.hit(damage = 0, type = HitType.BLOCK, delay = 2)
            }
        }
    }

    companion object {
        /** Shared npc id key — referenced by WorldBossPlugin to hand this plugin sole ownership
         *  of the death handler (see the dispatch in [init]). */
        const val NPC = "npc.corporeal_beast"

        // Per-npc flag so the enrage roar only fires once.
        private val ENRAGED_ATTR = AttributeKey<Boolean>()

        // Cache visual ids (best-effort; a wrong id is only a missing visual, never a crash).
        private const val ATTACK_ANIM = 10058
        private const val BLOCK_ANIM = 10056
        private const val DEATH_ANIM = 10059
        private const val MAGIC_PROJECTILE_GFX = 316
        private const val NOVA_GFX = 316

        private const val MAGIC_LEVEL = 300
        private const val ENRAGE_PCT = 40

        // Damage tuning — "hard but soloable". Adjust freely.
        private const val MELEE_MAX = 26
        private const val MELEE_MAX_ENRAGED = 33
        private const val MAGIC_MAX = 28
        private const val MAGIC_MAX_ENRAGED = 35
        private const val NOVA_MIN = 6
        private const val NOVA_MAX = 22
        private const val NOVA_MAX_ENRAGED = 30
        private const val NOVA_RADIUS = 1

        // ── Permanent lair (always-on cave farm) ────────────────────────────────────────────
        /** Best-known OSRS Corporeal Beast's Cave tile. TUNE: verify in-game it renders on solid
         *  floor at this plane (a wrong plane leaves it floating in the sky, as Nex's did). */
        private val CAVE = Tile(2966, 4383, 2)
        private const val LAIR_RESPAWN = 50   // ~30s between lair respawns
        private const val LAIR_BOSS_POINTS = 25

        /**
         * Corpse drop table for the permanent CAVE kill (single killer). Mirrors the world boss's
         * value but rolls its avatar-tier uniques at BASE odds — the Lumbridge event doubles them
         * (see [org.alter.plugins.content.war.boss.BossRegistry.eventUniqueMultiplier]). The cave is
         * still the only faucet for the spirit-shield sigils, so a solo grind pays off.
         */
        private val LAIR_LOOT = DropTable(
            // Every kill: PK supplies (always useful).
            always = listOf(
                DropEntry("item.blood_rune", 80, 160),
                DropEntry("item.death_rune", 80, 160),
                DropEntry("item.super_restore4", 2, 4),
            ),
            // One weighted pick: coins + solid rune/dragon gear and resources.
            main = listOf(
                DropEntry("item.coins_995", 20_000, 45_000, weight = 26),
                DropEntry("item.rune_platebody", weight = 20),
                DropEntry("item.rune_platelegs", weight = 20),
                DropEntry("item.runite_ore", 2, 4, weight = 18),
                DropEntry("item.dragon_bones", 10, 20, weight = 18),
                DropEntry("item.magic_logs", 20, 40, weight = 14),
                DropEntry("item.dragon_dagger", weight = 8),
                DropEntry("item.dragon_platelegs", weight = 4),
            ),
            // Independent chase rolls — dragon pieces plus the avatar-tier uniques (sigils, blessed
            // spirit shield, holy elixir, visage, pet) at BASE odds.
            rare = listOf(
                DropEntry("item.dragon_boots", 1, 1, oneInN = 25),
                DropEntry("item.dragon_2h_sword", 1, 1, oneInN = 45),
                DropEntry("item.dragon_full_helm", 1, 1, oneInN = 70, announce = true, log = true),
                DropEntry("item.dragon_pickaxe", 1, 1, oneInN = 90, announce = true, log = true),
                DropEntry("item.holy_elixir", 1, 1, oneInN = 50, log = true),
                DropEntry("item.spirit_shield", 1, 1, oneInN = 64, log = true),
                DropEntry("item.blessed_spirit_shield", 1, 1, oneInN = 120, announce = true, log = true),
                DropEntry("item.arcane_sigil", 1, 1, oneInN = 150, announce = true, log = true),
                DropEntry("item.spectral_sigil", 1, 1, oneInN = 150, announce = true, log = true),
                DropEntry("item.elysian_sigil", 1, 1, oneInN = 150, announce = true, log = true),
                DropEntry("item.draconic_visage", 1, 1, oneInN = 200, announce = true, log = true),
                DropEntry("item.pet_corporeal_critter", 1, 1, oneInN = 700, announce = true, log = true),
            ),
        )
    }
}
