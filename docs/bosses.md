# Boss Roster — Master Plan

> Goal: every boss reachable from the teleport portal's **Bosses** tab spawns at its
> **real OSRS lair**, and fights with **OSRS-exact combat** — the same attack styles,
> rotations, max hits, projectiles, animations, special mechanics and protection
> behaviour as live Old School RuneScape.
>
> This doc is the single source of truth for that work. **Adding a boss = following the
> build pattern below + ticking it off the roster.** Companion code lives in
> `game-plugins/.../content/npcs/<boss>/` (combat) and the loot/points/clog hooks in
> `content/bosses/`.

---

## 1. The build pattern (the KBD template)

Every boss is **two plugins** plus **two registry edits**. King Black Dragon
(`content/npcs/kbd/`) is the reference implementation — copy its shape.

### 1a. `<Boss>ConfigsPlugin` — spawn + stats
Spawns the NPC at its real lair and declares its combat def via the `setCombatDef` DSL
(`api/dsl/NpcCombatDsl.kt`). Mirrors `KbdConfigsPlugin`:

```kotlin
setMultiCombatRegion(region = <regionId>)      // only where OSRS is multi-way
spawnNpc("npc.<key>", x, z, level, walkRadius)
setCombatDef("npc.<key>") {
    configs { attackSpeed = <ticks>; respawnDelay = <ticks> }
    aggro   { radius = <n>; searchDelay = 1 }   // omit for non-aggressive
    stats   { hitpoints; attack; strength; defence; magic; ranged }
    bonuses { defenceStab/Slash/Crush/Magic/Ranged; attackBonus; strengthBonus; ... }
    anims   { block; death }                     // attack anim usually per-style in CombatPlugin
    species { +NpcSpecies.X }                     // dragon/demon/undead → gear effects, slayer
    slayerData { levelRequirement; xp }           // slayer-gated bosses
    immunities { poison; venom }                  // most bosses immune to venom
}
```

Stats use OSRS **levels** (the 240/240 KBD numbers), bonuses use OSRS **equipment-style
bonus integers**. Both are looked up per boss in the spec tables below.

### 1b. `<Boss>CombatPlugin` — the OSRS attack rotation
Owns `onNpcCombat("npc.<key>")` and runs the boss's attack loop in a coroutine queue —
the structure from `KbdCombatPlugin`:

```kotlin
onNpcCombat("npc.<key>") { npc.queue { npc.combat(this) } }

suspend fun Npc.combat(it: QueueTask) {
    var target = getCombatTarget() ?: return
    while (canEngageCombat(target)) {
        facePawn(target)
        if (moveToAttackRange(it, target, distance, projectile) && isAttackDelayReady()) {
            // ── pick attack per OSRS rotation/weights, then ──
            postAttackLogic(target)
        }
        it.wait(1)
        target = getCombatTarget() ?: break
    }
    resetFacePawn(); removeCombatTarget()
}
```

Per-attack helpers available on `Npc` (see `KbdCombatPlugin`, `content/combat/PawnExt.kt`,
`api/ext/NpcExt.kt`):
- `prepareAttack(CombatClass, CombatStyle, AttackStyle)` — sets the active style for the formula.
- `animate(id)` — attack animation.
- `createProjectile(target, gfx, startHeight, endHeight, delay, angle, steepness)` + `world.spawn(proj)`.
- `dealHit(target, formula, delay) { onHit }` — applies a hit through a damage formula
  (`MeleeCombatFormula`, `RangedCombatFormula`, `MagicCombatFormula`, `DragonfireFormula`).
- `target.hit(dmg, type, delay)` — raw hit (for fixed-mechanic damage).
- `target.freeze(cycles)`, `target.poison(initialDamage)`, `getSkills().alterCurrentLevel(skill, n)`.
- `world.chance(1, n)`, `world.random(n)`, `world.randomDouble()` for rotation rolls.

**Fidelity rule:** match OSRS attack *order/weights*, *projectile + gfx ids*, *animation
ids*, *max hits*, *attack speed*, and *protection-prayer interaction* (e.g. attacks that
ignore protection, or styles you must pray against). Where the cache lacks a gfx/anim, log
it and pick the nearest — never let a missing id throw (it drops the plugin; see
[[rsps-plugin-load-diagnostic]]).

### 1c. `<Boss>BossPlugin` — loot, points, collection log (shared `content/bosses/`)
Layer the economy hooks on death, exactly like `KbdBossPlugin`: a weighted `DropTable`
(`always`/`main`/`rare`), `addPoints(PointKind.BOSS, n)`, `CollectionLog.record`, and a
server-wide broadcast for uniques/pets. For the simple roster, one shared
`SimpleBossDeathPlugin` driven by a per-boss `DropTable` map removes most of this
boilerplate (see §4 Infra).

### 1d. Registry edits
1. `TeleportRegistry.kt` — flip the boss from `soon(...)` to `built(..., realLairTile, danger, wild?)`.
2. Client mirror `LofTeleportsData` (RuneLite overlay) — keep category-ordinal + row order
   in sync, set the real item icon id (see [[rsps-teleport-portal]]).

---

## 2. Cross-cutting infrastructure (Phase A — do first)

These are shared prerequisites; building them once unblocks every boss.

| Item | Why | Approach |
|------|-----|----------|
| **Lair region force-load** | Far lairs (KBD 9033, Wildy, Zulrah, Kraken cave) have **no collision** until a player stands there → NPCs freeze at spawn (the war-troop bug, [[rsps-war-collision-loading]]). | A `BossLairs` boot step that force-loads every lair region via `world.definitions.loadRegions(...)`, like `AttackDirector.forceLoadBattlefieldRegions`. Register each boss's region id. |
| **Multi-combat flags** | Many lairs are multi-way (GWD, Wildy bosses, Corp). | `setMultiCombatRegion(region)` per lair (already used by KBD). |
| **Shared boss-death** | Avoid copy-pasting `KbdBossPlugin` 25×. | `SimpleBossDeathPlugin` reads a `Map<npcKey, DropTable>` + per-boss `bossPoints`, runs the KBD death logic generically. KBD/phased bosses keep bespoke plugins. |
| **Slayer gating** | Slayer bosses (Kraken, Cerberus, Sire, Hydra, Smoke Devil) are task/level locked. | `slayerData{}` exists but has an aggro bug noted in `KbdConfigsPlugin`; wire a block-attack check. Until Slayer shop is built, gate on **level only**. |
| **Instancing** | Zulrah, raids, Inferno, Barrows tunnel, single-occupancy bosses. | Reuse the Fight Cave session pattern ([[rsps-teleport-portal]] mini-games note) — per-player instanced region. Needed from Phase E on. |
| **Teleport landing force-load** | Teleporting into an unloaded lair = same freeze. | Landing tiles in the registry must be covered by the Phase-A force-load set. |

---

## 3. The roster, phased

Build order matches the user's priority: **Barrows → Wilderness → Slayer/PvM → Zulrah → the rest.**
Status: ✅ done · 🔨 in progress · ⬜ todo · 🅿️ needs instancing/raid framework (deferred).

### Phase B — Barrows (full minigame)  ⬜
Currently the 6 brothers are spawned in the **open overworld** at the mounds with basic
melee defs and no mechanics/loot. OSRS-exact means the **crypt minigame**:
- Dig a mound → descend into that brother's crypt → fight him 1-v-1.
- Enter the **tunnel maze** (random layout), kill tunnel monsters, find the **puzzle door**.
- The **6th brother** (the one whose mound you didn't dig) ambushes in the tunnel.
- Loot the **reward chest**: reward potential = `(brothers killed × N) + tunnel kills`,
  rolling brother equipment, coins, runes, bolt racks, keys, half-keys, dragon med (rare).
- Per-brother **set effects** (the headline fidelity work):

| Brother | HP | Style | Max | Effect (OSRS-exact) |
|---------|----|----|-----|--------|
| Ahrim the Blighted | 100 | Magic | 25 | Sometimes lowers your **Strength**; ranged-tank-protectable. |
| Dharok the Wretched | 115 | Melee (axe) | scales | Max hit **scales with missing HP**: `dmg ≈ base·(1+(maxHp−hp)/maxHp)`. |
| Guthan the Infested | 115 | Melee (spear) | 24 | On hit, **heals himself** by the damage dealt. |
| Karil the Tainted | 100 | Ranged (xbow) | 18 | Tainted shot can **halve your Agility** level. |
| Torag the Corrupted | 115 | Melee (hammers) | 23 | Hit **drains your run energy**. |
| Verac the Defiled | 115 | Melee (flail) | 23 | Can hit **through Protect-from-Melee & ignore defence** (guaranteed min 1). |

Lair: Barrows, ~`(3565, 3289)` mounds; crypts are instanced regions. Multi-session build.

### Phase C — Wilderness single bosses  ⬜
All multi-way; all in the Wilderness (skull + danger). Real lair coords below.

| Boss | npc key | Lair tile (approx) | HP | Styles / mechanic |
|------|---------|--------------------|----|----|
| Callisto | `npc.callisto` | (3300, 3840) lvl 41 wild | 510 | Melee + **roar** (clears your protection prayer); reflects melee. |
| Vet'ion | `npc.vetion` | (3239, 3779) lvl 27 wild | 255×2 | **2 phases** (re-spawns at half HP); melee + **lightning** AoE you must step out of; summons **skeleton hellhounds**. |
| Venenatis | `npc.venenatis` | (3315, 3743) lvl 35 wild | 510 | Melee + ranged **web** (binds); **prayer-disabling** spider attack. |
| Scorpia | `npc.scorpia` | (3232, 10337) Scorpia cave | 200 | Melee; spawns **guardians** that heal her; venom. |
| Chaos Elemental | `npc.chaos_elemental` | (3275, 3916) lvl 48 wild | 200 | Tri-style; **teleports you** a few tiles + **unequips** a random item. |
| Chaos Fanatic | `npc.chaos_fanatic` | (2980, 3851) lvl 38 wild | 210 | Magic + **green orb AoE**; "drains" your prayer; unequip-spell flavour. |
| Crazy Archaeologist | `npc.crazy_archaeologist` | (2980, 3692) lvl 18 wild | 224 | Ranged + **"Rain of Knowledge"** AoE book-bombs (step away); "Reading is good for you!" |

(OSRS revamped Callisto/Vet'ion/Venenatis with **Artio/Calvar'ion/Spindel** singles-variants;
build the multi originals first, add the singles variants as a follow-up if desired.)

### Phase D — Slayer / PvM bosses  ⬜
Single-NPC instanced or lair fights. Build in roughly this order:

| Boss | npc key | Lair | HP | Mechanic |
|------|---------|------|----|----|
| Kraken | `npc.kraken` | Kraken Cove instanced (2280, 10024) | 255 | Disturb whirlpool to wake; single **magic** splash you pray mage; tentacles. Slayer 87. |
| Corporeal Beast | `npc.corporeal_beast` | (2965, 4382) cave | 2000 | Magic + melee; spawns **dark energy core**; halves non-spear damage; drains stats. |
| Demonic Gorillas | `npc.demonic_gorilla` | Crash Site cavern (2410, 9800) | 187 | **Cycles protection prayers**, switches melee/range/magic every few hits; boulder AoE. |
| Skotizo | `npc.skotizo` | Catacombs altar instance | 450 | Summons **demonic/awakened altars** that buff him until destroyed; dark fonts. |
| Cerberus | `npc.cerberus` | (1240, 1232) lair | 600 | Cycles prayer-switch; **Summoned Souls** (3 ghosts = the 3 styles); lava pools; triple attack at low HP. Slayer 91. |
| Giant Mole | `npc.giant_mole` | Falador mole lair (1760, 5164) | 255 | Melee; **burrows + relocates** in the tunnel network. |
| Dagannoth Kings | `npc.dagannoth_rex/prime/supreme` | Waterbirth (2900, 4449) | 255 ea | 3 kings, one per style (Rex melee, Prime mage, Supreme range); multi. |
| Kalphite Queen | `npc.kalphite_queen` | (3508, 9494) lair | 255×2 | **2 phases**: phase 1 range/mage flying, phase 2 melee crawler; pray the active style. |
| Abyssal Sire | `npc.abyssal_sire` | (2970, 4384) | 400 | Stun phase, **respiratory tentacles**, miasma pools, spawns. Slayer 85. Complex — later in phase. |
| Thermonuclear Smoke Devil | `npc.thermonuclear_smoke_devil` | (2380, 9452) | 240 | Ranged smoke; AoE; Slayer 93. |
| Sarachnis | `npc.sarachnis` | (1923, 9921) | 460 | Melee + ranged web; spiderlings. |

### Phase E — Zulrah  ⬜🅿️
The flagship phased boss; needs **instancing** (per-player). Full fidelity:
- 3 forms via the cache phase NPCs (`zulrah` 2042 green, `zulrah_2043` 2043 red, `zulrah_2044` 2044 blue).
- **Green** = ranged + spawns **snakelings**; **Blue** = magic; **Red** = melee/ranged Jad-style + venom clouds.
- Rotates **position** (4 spawn points) on a fixed **rotation pattern**; pray-switch + move
  off venom clouds. Phase changes on a timer/HP, not just damage.
- Lair: Zul-Andra instanced shrine (2200, 3052) boat → instance.

### Phase F — God Wars Dungeon + multi-NPC lairs  ⬜
Graardor / K'ril / Kree'arra / Commander Zilyana + their **3 bodyguards** each; killcount
door mechanic; multi-combat. The DKs (Phase D) share this multi-NPC shape.

### Phase G — Raids, Inferno, Nightmare, Vorkath, Hydra, Nex, Muspah  🅿️
Large instanced encounters requiring the full instancing/wave framework. Spec separately
once Phases B–E land. Roster placeholders already greyed in the teleport (Nex, ToB, CoX,
Rev Caves). Build candidates after raids framework: **Vorkath**, **Alchemical Hydra**,
**Grotesque Guardians**, **Phantom Muspah**, **Nightmare**, **Nex**.
✅ **Fight Caves (Jad) + Inferno (TzKal-Zuk) BOTH BUILT 2026-07-06** — see checklist below.

---

## 4. Execution checklist (live)

- [~] **A. Infra** — ✅ `BossLairs` + `BossLairsPlugin` (lair-region collision force-load + multi-combat flags) · ✅ `BossProtection.isProtectedFrom()` (OSRS-exact NPC protection-prayer blocking, reused by every boss combat plugin) · ⬜ `SimpleBossDeathPlugin` + per-boss `DropTable` map (deferred to Phase C — Barrows uses a reward chest, not death drops) · ⬜ slayer level-gate (deferred to Phase D).
- [~] **B. Barrows** — ✅ **B1**: `BarrowsCombatPlugin` gives all 6 brothers OSRS-exact styles/max-hits/anims + protection-prayer blocking + signature set effects (Dharok HP-scale, Guthan heal, Karil agility-drain, Torag run-drain, Verac prayer-ignore, Ahrim str-drain); the 6 config files corrected from copy-paste stubs to OSRS stats (HP 100/115, undead, poison-immune, per-brother attack anim).
  - ✅ **B2** (`BarrowsMinigamePlugin` + real region-14231 cache coords): **dig** a mound (delegated from `SpadePlugin`) → descend into that brother's **crypt** (L3) → **search the sarcophagus** to make him climb out and fight → **climb the staircase** to exit. One random crypt per run is the **tunnel** → searching it drops you in the chest chamber. **Open the chest** (obj 20973) → OSRS-style loot scaled by reward potential (brothers slain) + the un-slain **6th brother ambush** → escape on his death. **Prayer drains** while underground. `::barrows` teleport added. Brothers now spawn ONLY in crypts (overworld spawns removed).
  - ⬜ **B2b** (deferred): full random **tunnel-maze door puzzle** (navigate the 20730 doors room-to-room instead of dropping straight to the chest); tunnel monsters (crypt rats/spiders/skeletons); persistent (cross-relog) run state; exact reward-potential formula + collection-log/pet wiring.
  - *Compiles clean (`game-plugins:compileKotlin` BUILD SUCCESSFUL). Not yet runtime-verified in-game; mound dig tiles, crypt landing/spawn tiles, object option labels (Search/Climb-up/Open), and some barrows-item rscm keys are best-known and marked TUNE — verify in-game.*
- [x] **C. Wilderness** — ✅ all 7 built at real lairs (`content/npcs/wilderness/`): `WildernessBosses` registry (lairs/stats/loot) + `WildernessBossPlugin` (spawns + combat defs + death→loot/points/clog) + `WildernessBossCombatPlugin` (OSRS rotations + mechanics: Callisto roar clears prayers, Vet'ion phase-2 hellhound summon + lightning, Venenatis web-freeze + prayer-sap, Scorpia guardian-heal + venom, Chaos Elemental tri-style + teleport-disarm, Chaos Fanatic weapon-knock + magic, Crazy Arch ranged + AoE). Lairs added to `BossLairs` (multi-combat + force-load); teleport entries flipped `soon`→`built`. *Compiles clean. Not runtime-verified; lair coords, projectile gfx, and a few unique-item rscm keys are TUNE.*
- [x] **D. Slayer/PvM** — ✅ built (`content/npcs/pvm/` + shared hit helpers extracted to `content/bosses/BossCombat.kt`): `PvmBosses` registry (lairs/stats/slayerReq/loot) + `PvmBossPlugin` (spawns + defs + slayerData + death→loot) + `PvmBossCombatPlugin` (rotations). Bosses: Kraken (magic), Cerberus (style-cycle + half-HP Summoned Souls), Giant Mole, Kalphite Queen (HP-phase flyer→crawler), Sarachnis, Thermonuclear Smoke Devil (+AoE), Demonic Gorillas (style-switch), Skotizo, Dagannoth Rex/Prime/Supreme (fixed melee/magic/ranged). Lairs in `BossLairs`; teleport entries built (+ `barrows` flipped built). **Corporeal Beast deliberately excluded** — already a built city world boss (`CorpBeastPlugin` + war/boss/BossScheduler); re-registering would crash. **Abyssal Sire deferred** (complex stun/tentacle/miasma → its own pass). Simplified vs OSRS (documented in-file): Corp damage-halving, Cerberus lava/ghost-styles, gorilla prayer-adaptation, Skotizo altars, mole relocation, true two-NPC KQ. *Compiles clean. Not runtime-verified; lair coords/gfx/some unique-item keys TUNE.*
- [x] **E. Zulrah** — ✅ `content/npcs/zulrah/ZulrahPlugin.kt`: single-occupancy session (Fight-Cave pattern), one persistent npc morphed through green/red/blue via `setTransmogId` (ONE HP bar), hops 4 positions on a fixed rotation, per-form prayer-switching (ranged/melee/magic), snakelings + venom on green, loot/points/clog on death. Entry `::zulrah`. *Deferred (TUNE): exact OSRS rotation patterns, submerge timing, Jad phase, real instancing, Zul-Andra coords, portal→session binding (entry is `::zulrah`, like Fight Cave).*
- [x] **F. GWD** — ✅ `content/npcs/godwars/`: `GodWarsBosses` registry (4 generals + 3 bodyguards each + loot) + `GodWarsPlugin` (spawns general + bodyguards, combat defs, death/loot) + `GodWarsCombatPlugin` (dual-style rotations: Graardor melee/range, K'ril melee/magic, Kree'arra range/magic, Zilyana melee/magic). Lairs in `BossLairs` (multi + force-load); 4 teleport entries built. *Deferred: the killcount door (you spawn straight in), bodyguards' individual styles (all melee for now), Nex.*
- [x] **G-tier elite single bosses** — ✅ built (`content/npcs/elite/`): `EliteBosses` registry + `EliteBossPlugin` (spawns/defs/slayer/death — uses shared `deathAnimFor`) + `EliteBossCombatPlugin` (HP-phased rotations). **Vorkath** (dragonfire/ranged/melee + ice-freeze + zombified spawn), **Alchemical Hydra** (4-phase ranged↔magic prayer-switch + acid, Slayer 95), **Phantom Muspah** (ranged/magic/melee rotation), **Abyssal Sire** (melee/magic + scion spawns + miasma, Slayer 85), **Nex** (4 elemental phases each summoning Fumus/Umbra/Cruor/Glacies + ice-freeze), **Grotesque Guardians** (Dusk melee + Dawn ranged, Slayer 75). Lairs in `BossLairs`; teleport entries built (Nex flipped from wild→GWD). *Compiles clean. Simplified vs OSRS (in-file): exact phase scripts, acid/lightning pools, Sire stun-vent, Hydra flame wall, Nex full specials. Not runtime-verified.*
- [x] **Fight Caves (TzTok-Jad)** — ✅ BUILT 2026-07-06 (`content/minigames/fightcave/FightCavePlugin.kt`, full rewrite of the goblin-wave placeholder): **hybrid design — OSRS-authentic mechanics, RSPS-compressed length**. 11 waves of the real cave roster (Tz-Kih prayer-drain, big Tz-Kek death-split → 2 small, Tok-Xil ranged, Yt-MejKot, Ket-Zek magic, classic 2×Ket-Zek pre-Jad wave) with OSRS-exact stats (ids 3116–3128), ending in **TzTok-Jad** (max 97 all three styles, 8-tick cycle, **telegraphed attacks with the protection check at IMPACT** — 3-tick window after the anim, so prayer-switching works like OSRS; 4 Yt-HurKot healers at half HP, heal unless dragged off). **Per-player instance** of real region 9551 via `RaidInstance.allocate` (concurrent runs, auto-safe deaths). Entry: portal → TzHaar-Mej-Jal game-master dialogue, `::arena`, or `::jad` (practice: Jad only, no rewards). First clear = Fire cape (+world announce +clog); every clear = 50 Boss tickets + 1/100 TzRek-Jad pet. Best-wave attr kept. *Compiles clean; STAGED pending restart. TUNE: anim/gfx ids (2652/2655/2656, proj 448/443/445, hit 157/451), healer heal-rate, Ket-Zek/Tok-Xil melee radii.*
- [x] **The Inferno (TzKal-Zuk)** — ✅ BUILT 2026-07-06 (`content/minigames/inferno/InfernoPlugin.kt`, Fight-Cave architecture reused): 13 waves of the Jal- roster in a per-player instance of real region 9043 (mapdump-verified arena x2257-2285/z5329-5358). Mechanics: Jal-Ak splits into 3 mini-casters, Jal-ImKot burrows to kiters, **Jal-Zek resurrects this-wave corpses** (session corpse ledger, cap 2/wave), wave-12 full JalTok-Jad (impact-time prayer telegraph reused, 3 healers @50%), **Zuk finale**: stationary at north edge (SW anchor on walkable tile, bulk over lava), **Ancestral Glyph npc (7707) patrols x2259-2283 @1 tile/2 ticks**, every 10 ticks an UNBLOCKABLE ~110 hit unless |player.x − glyph.center| ≤ 1 and south of it; adds at 75% (Xil+Zek set), 50% (JalTok-Jad 7704), 25% (4 healers heal Zuk — shared healTarget machinery). **Entry = sacrifice a Fire cape to TzHaar-Ket-Keh** (11247, spawned beside Mej-Jal; INFERNO_UNLOCKED_ATTR persistent) — pairs with fire capes now TRADEABLE (itemOverrides/TradeableCapes.yml, cost 0 to avoid shop-buy gold faucet) + Fight Cave awarding a cape EVERY clear. Rewards: Infernal cape (untradeable, every clear) + 150 Boss tickets + 1/1,000 TzRek-Zuk. `::inferno`/`::zuk` (practice)/`::leaveinferno`; teleport entry added (same landing as fight_cave). *Compiles clean; STAGED pending restart; anim/gfx ids TUNE.*
- [~] **Still deferred** (need the wave/room/instancing framework): ToB, CoX, Nightmare, Revenant Caves; plus polish (Barrows maze doors, portal→Zulrah session binding, true multi-NPC phase bosses, slayer-task enforcement).

**Per boss, definition of done:** spawns at real lair (collision loaded), OSRS-exact attack
rotation + max hits + protection behaviour, death drops OSRS-style loot + boss points + clog,
teleport entry flipped to `built` with real icon, boot log clean (no dropped plugin).

> Coordinates marked "approx" are from memory and **must be verified in-game** (use the
> [[rsps-map-dump-tool]] dump or an `::aboutpos` probe) before the spawn is committed.
> HP/max-hit values are OSRS-live and should be cross-checked against the wiki per boss.
</content>
</invoke>
