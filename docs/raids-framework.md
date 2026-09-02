# Raid / Wave Framework — Scoping

> Scopes the **deferred boss tier** — the encounters that don't fit the single-NPC pattern:
> the **Inferno**, **Theatre of Blood**, **Chambers of Xeric**, **The Nightmare**, and the
> **Revenant Caves**. These need *instancing*, a *wave/room state machine*, and (for some) a
> *party*. This doc surveys what the engine already gives us, defines the framework, and phases
> the build. It is a plan, not yet code.

---

## 1. What already exists (the good news)

The single hardest piece — **instancing — is already in the engine** and unused by content:

- **`InstancedMapAllocator`** (`game-server/.../model/instance/`) — `allocate(world, chunkSet, config)`
  copies a set of source chunks into an isolated instance in the reserved area **x 6400–9600,
  y 0–6400** (supports ~thousands of 64×64 instances), and **auto-deallocates** a map when it's
  empty (25-cycle scan), on the owner's **logout** (`DEALLOCATE_ON_LOGOUT`), or on **death**
  (`DEALLOCATE_ON_DEATH`). It moves stragglers to the configured **exit tile**. So instance
  lifecycle is *handled for us*.
- **`instancedChunks { set(localChunkX, localChunkZ, height, rot, sourceTile) }`** DSL
  (`api/dsl/InstancedMapDsl.kt`) + `InstancedMapConfiguration.Builder` (exit tile, owner uid,
  attributes) — the high-level way to declare "copy these source chunks into a fresh instance."
- **`world.instanceAllocator`** is the live accessor; `GameService`/`PlayerDeathAction`/logout
  already call its `cycle`/`death`/`logout` hooks. **No engine work needed to instance.**

Also reusable, already built this project:
- **`FightCavePlugin`** — a working **single-occupancy wave loop** (tick → spawn wave → detect
  clear → next wave → reward). The direct ancestor of the wave engine; today it uses a *shared*
  arena (not instanced) and generic mobs.
- **`BossCombat.kt`** primitives (`bossMelee`/`bossProjectile`/`bossSummon`/`deathAnimFor`),
  `DropTable`, `CollectionLog`, `BossLairs`, `addPoints(BOSS)` — all reusable for encounter
  mechanics, spawns and rewards.

**What's missing:** (1) a **party/group** system (the war "party" matches are campaign troops,
not players), and (2) a **generalized wave/room controller** (FightCave's loop, lifted out and
made instance-aware + reusable).

---

## 2. The deferred tier splits into four shapes

| Encounter | Shape | Party | Notes |
|-----------|-------|-------|-------|
| **Inferno** | Wave survival, one arena | solo | Big wave table + pillars + healers + Jad/triple-Jad + **Zuk**. Closest to FightCave. |
| **The Nightmare** | Single instanced arena boss | small group (soloable) | Phases + husks + **totems** + sleepwalkers. Really a *hard instanced boss*, not a raid. |
| **Theatre of Blood** | Linear room sequence | party | 6 rooms, each a distinct boss (Maiden, Bloat, Nylocas, Sotetseg, Xarpus, Verzik). Death = spectate. |
| **Chambers of Xeric** | Procedural rooms + resources | party | Randomized room layout, scavenging/skilling, points→loot, final **Olm**. Largest. |
| **Revenant Caves** | *Not a raid* — multi-NPC PvP cave | n/a | Just spawn revenants in the wildy cave with the existing roster pattern. Quick win, no framework. |

---

## 3. Framework architecture (the shared core to build)

Five components. Build them once; every raid is then "data + per-encounter mechanics."

### 3a. `RaidInstance` — instancing wrapper
Thin helper over `InstancedMapAllocator`:
- `allocate(sourceArea, exitTile, owner, attrs)` → builds the `InstancedChunkSet` from the
  source arena's chunks, allocates, returns the live `InstancedMap` (its `.area` base gives the
  offset).
- `translate(sourceTile) → instanceTile` — maps an arena-relative tile into the allocated copy
  (so encounter spawns/objects land in the right place regardless of where the instance allocated).
- `enter(player)` / teardown is mostly automatic (the allocator deallocates on empty/logout/death).
- **Risk:** each raid needs its **source arena chunk coords** from the cache — found with the
  `mapDump` tool (`rsps-map-dump-tool`). Confirm ToB/CoX/Inferno/Nightmare regions exist in the
  rev-228 cache first (they should — all post-date 228... *verify*).

### 3b. `RaidController` — the wave/room state machine
Generalize FightCave's tick loop into a reusable per-instance driver:
- Holds the instance, the participant(s), the current **stage index**, and a `RaidDefinition`.
- Each tick: spawn the current stage's NPCs (once), watch for **clear** (all stage NPCs dead),
  then **advance** (next wave / unlock next room / teleport party onward), or **complete**
  (reward + teardown).
- Pluggable **stage types**: `WaveStage` (spawn N mobs, clear-to-advance — Inferno),
  `RoomStage` (a boss encounter behind a door — ToB), `ResourceStage` (CoX scavenging).
- Reuses `BossCombat` + per-encounter `onNpcCombat` for the actual mechanics.

### 3c. `RaidParty` — minimal grouping (deferred to R3)
- v1 is **solo** (each player owns a private instance — `DEALLOCATE_ON_DEATH/LOGOUT` + owner uid).
  Inferno/Nightmare are commonly soloed; ToB/CoX are soloable too.
- Real parties (leader, invite, shared instance, shared loot, spectate-on-death) land **before
  ToB** (R3) since that's where they matter most. Keep the controller party-aware from the start
  (a `List<Player>` of participants) so solo is just "party of one."

### 3d. `RaidRewards` — completion payout
- A `RaidDefinition.rewardTable` (`DropTable`) rolled at completion into a **reward chest** (or
  direct), + Boss/raid points + `CollectionLog` uniques + KC tracking + the rare-drop broadcast.
  All of this already exists; just wire it to "raid complete."

### 3e. `RaidDefinition` — the per-raid data
Registry entry (like `EliteBosses`): source arena, entry/exit tiles, stage list (waves/rooms),
reward table, party size, requirements. **Adding a raid = adding a definition + its per-stage
mechanics.**

---

## 4. Build phases (recommended order)

| Phase | Deliverable | Why this order |
|-------|-------------|----------------|
| **R0** | Framework core: `RaidInstance` + `RaidController` + solo-party + `RaidRewards`. Validate by porting **FightCave** onto it (per-player instanced) or a 2-wave smoke-test raid. | De-risks the whole tier with the smallest scope; proves the instancing path end-to-end. |
| **Rev Caves** | *Parallel quick win* — revenant roster in the wildy cave via the existing `WildernessBosses`-style pattern. **No framework needed.** | Knocks one item off the deferred list cheaply; good momentum. |
| **R1** | **Inferno** — wave engine on R0: the wave table, pillars, healers, Jad + triple-Jad, **Zuk** + the moving shield. Solo. | Reuses R0 + FightCave shape; highest value, lowest *new-mechanic* risk. The Fight Cave (Jad) can be upgraded to the real TzHaar waves alongside. |
| **R2** | **The Nightmare** — single instanced arena: phases + totems + husks + sleepwalkers + parasites. Solo/duo. | Bridges single-boss → instanced multi-mechanic; smaller than a full room-raid. |
| **R3** | **Party system** + **Theatre of Blood** — the 6-room linear raid; each room a distinct boss encounter; death→spectate; reward chest. | Parties land here because ToB needs them; each room reuses the boss-combat patterns. Large. |
| **R4** | **Chambers of Xeric** — procedural room generation, resource/skilling rooms, points→loot, **Great Olm** (3-phase, hands). | Largest; depends on everything above (instancing, parties, controller, rewards). |

---

## 5. Open questions / decisions to make before R0

1. **Cache coverage** — do ToB / CoX / Inferno / Nightmare arenas exist in this rev-228 cache?
   (Run `mapDump` on their regions to confirm geometry before committing to R1+.)
2. **Solo vs party first** — confirm solo-first is acceptable (it is for Inferno/Nightmare;
   ToB/CoX are soloable but party is the intended experience).
3. **Death policy** — `DEALLOCATE_ON_DEATH` (lose the run, OSRS-faithful for Inferno) vs a
   spectate/continue model (ToB). Likely per-raid.
4. **Scale** — concurrent instances × NPCs each. The allocator supports thousands of *maps*, but
   NPC/AI load is the real ceiling; gate by max concurrent raids if needed.
5. **Fidelity bar** — same call as the single bosses: faithful core mechanics + `TUNE` ids, or
   tick-perfect? (Recommend faithful-core, given the single-boss precedent.)

> **Recommended first move:** build **R0** (framework core, validated by porting FightCave) and
> knock out **Rev Caves** in parallel. That proves the instancing path and clears one deferred
> item before committing to the big encounters (Inferno → Nightmare → ToB → CoX).

---

## 6. Build status

- ✅ **Rev Caves** — `content/npcs/revenants/RevenantCavesPlugin.kt`: the 12 revenant types
  spawned through the cave with style-switching combat + prayer-sap + the shared revenant drop
  table; `::revcaves` + teleport entry built; cave region force-loaded. (Not a raid — done.)
- 🔶 **R0 framework core** — CORRECTION (2026-09-02): only **`RaidInstance`** exists in
  `content/raids/` (allocate a chunk-aligned arena copy via `world.instanceAllocator` + force-load
  source regions + `translate()` source→instance tiles + `allocateFloors` islands + auto-teardown,
  and since Block 1 a live map→source registry, `RaidInstance.sourceOf`, for `CompanionPolicy`).
  The `RaidDefinition`/`RaidController`/`Raids`/`RaidsPlugin`/`::testraid` pieces this section
  used to claim were never committed — the 2026-08-28 purge removed the boss/minigame framework
  and the Kronos ports (Vorkath, Zulrah, Hydra, Jad, GWD) allocate `RaidInstance` directly. The
  quest framework's `QuestInstances` (`content/quests/framework/QuestInstances.kt`) is the
  reusable per-player instance lifecycle (enter / tagged spawns / end on leave, death, logout,
  timeout) and the natural base for a wave controller when the deferred tier is picked up.
- ⬜ **R1 Inferno → R2 Nightmare → R3 parties+ToB → R4 CoX** — need the wave/room controller
  above first. Next: confirm the Inferno arena exists in-cache (`mapDump`), then build the wave
  table + Jad/Zuk on `QuestInstances`/`RaidInstance`.
</content>
