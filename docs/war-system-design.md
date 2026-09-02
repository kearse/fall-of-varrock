# Fall of Varrock — The War: Siege & Citizenship Design

> **LEGACY — SUPERSEDED (Development Block 1, September 2026).** This document describes the
> *defensive* siege (General Zo, siege pressure, the AI War Brain). That product loop is retired:
> the design authority is now the September-2026 docs (`FoV_Current_Design_Authority_Dev_Handoff`,
> `FoV_Development_Block_1_Scope`, `Fall_of_Varrock_Master_Story_and_Quest_Plan`) — the war is
> **offensive** (public Marches/Grand Marches, Lord operations, Campaigns, Conquests) and Lumbridge
> is never besieged. The engine files described below (`AttackDirector`, `Commander`, `WarFront`,
> `TacticalMap`, `TargetSelector`, `Sieges`, …) were **deleted**; recover them from git tag
> `pre-block1-siege-engine` if the flow-field / targeting AI is ever wanted again. Only §10
> (engine-fit findings: instancing, `WarState` persistence) still describes live code.

> **Concept:** A dystopian RuneScape. Civilization has collapsed to a handful of
> walled cities. Everything outside is overrun. Humanity is *losing*. Players are
> citizens who fight at the front to hold the line, level up, and eventually
> push back — or raid rival cities once their own front is stable.
>
> **Design north star:** The world should feel like an ongoing war that exists
> whether or not any single player logs in. Every player's effort visibly moves
> the line. The fantasy is *survival under siege*, not *spawn farming*.

---

## 1. Core loop

1. A player is a **citizen** of one city (chosen at signup, changeable rarely).
2. Their city has one or more **fronts** — directions the monsters attack from.
3. Each front has a persistent, server-wide **siege pressure** meter.
4. Players queue into **instanced battle sorties** at a front to fight waves.
5. Sortie outcomes feed back into the persistent front meter:
   - Win → pressure drops, perimeter pushes outward, better content unlocks.
   - Ignore/lose → pressure rises, monsters breach, the *city itself* degrades.
6. As pressure stays low, the front stabilizes → travel between allied cities
   opens → eventually the meter can be **repointed** from PvE defense to
   PvP/raid against a rival city (Deadman-style, later phase).

The instanced sortie keeps combat balanceable and self-contained. The
**persistent front meter** is what makes it feel like a continuous war instead
of a minigame queue.

---

## 2. The two-layer model

This is the key architectural decision. There are **two layers**:

### Layer A — Persistent World War State (always on, lightweight)
- Lives in the shared game world. No combat sim here — just numbers + visuals.
- Per front: a `siegePressure` value (0–100) that decays toward "losing" slowly
  over real time and is reduced by sortie wins.
- Drives **open-world cosmetic + functional state** of each city:
  - Perimeter guard NPCs spawned outward (low pressure) or pulled back to the
    gate (high pressure).
  - City interior state: vendors open/closed, banker available, ambient
    damage/fires when breached.
  - A visible **war board** in each city showing every front's status.
- Ticks cheaply on a slow timer (e.g. every few game ticks / minutes), not
  per-combat. Safe at low population.

### Layer B — Instanced Battle Sorties (on demand, heavy combat)
- A player (or squad) queues a front → enters a private instance of that front.
- Directional **waves** advance along 1–2 lanes toward a defended gate object.
- Friendly **NPC guards** fight alongside the player and can die.
- Escalation: longer survival → grunts → brutes → a named **warlord** boss.
- Outcome (waves cleared, warlord killed, gate HP remaining) is scored and
  applied to Layer A's `siegePressure` for that front.

Players experience Layer B; they *feel* Layer A in the city between sorties.

---

## 3. Siege pressure — the one number that runs everything

`siegePressure[front]` ∈ [0, 100]. Higher = closer to falling.

| Band | State | Open-world effect |
|------|-------|-------------------|
| 0–25 | **Pushing back** | Perimeter expands outward; rare/high-tier spawns reachable; travel lanes to allies open. |
| 26–60 | **Holding** | Stable perimeter at default radius; normal vendors; standard sortie difficulty. |
| 61–85 | **Under siege** | Guards pulled back to the gate; sortie difficulty up; some vendors close. |
| 86–100 | **Breached** | Monsters spawn *inside* the city; ambient damage; bank/vendors down until pushed back below 85. |

**Drivers:**
- `+` Passive decay over real time (the war never stops; neglect = loss).
- `+` Larger when escalation tiers reach the gate in failed/abandoned sorties.
- `−` Sortie wins, scaled by performance (waves cleared, warlord kill, gate HP saved).
- Tuning levers: decay rate, win value, breach threshold, decay floor per city.

Low population is handled by **decay rate**: with few players, decay is gentle so
fronts don't auto-collapse; the war degrades on a timescale that a handful of
active citizens can hold.

---

## 4. The instanced sortie (Layer B detail)

**Entry:** Talk to a Front Marshal NPC at a city gate → choose front → solo or
squad (party) → load instance.

**Layout:** A corridor/arena from the open wilderness edge to the city **gate
object** (has HP). 1–2 advance **lanes**.

**Waves:**
- Timed/cleared waves spawn at the wilderness edge and path toward the gate.
- Friendly guard NPCs hold the lanes and can be killed.
- If monsters reach the gate, they damage the gate object.
- Gate destroyed → sortie fails (counts as pressure increase).

**Escalation tiers** (per wave count / time survived):
1. Grunts (chaff, easy)
2. Brutes (tanky, hit harder)
3. Specialists (ranged/casters that punish standing still)
4. **Warlord** (named boss; reaching the gate does heavy pressure damage)

**Scoring → applied to `siegePressure`:**
- Waves cleared, warlord killed, gate HP remaining → a pressure-reduction score.
- Drops/XP scale with tier reached, encouraging pushing deeper (risk vs reward).

**Why instanced:** balanceable, no contested spawns, squads get clean runs,
and it scales from 1 player to a party without open-world crowding issues.

---

## 5. Citizenship

- Chosen at character creation; defines **home city** = respawn + bank location.
- A player's sortie results feed **their city's** front meters.
- Switching cities: rare/costly (cooldown or fee) to keep identity meaningful.
- Foundation for the later **city-vs-city** phase (allegiance = PvP team).

Implementation is cheap: a `cityId` flag on the player save. Huge identity
payoff for low cost — worth doing early even if most systems come later.

---

## 6. Travel & the deep wilderness

You don't design "danger" — it emerges. The space *between* cities is where
no front is defended, so pressure is effectively always max and escalation
never resets.

- Low levels physically can't cross; they must grind their home front first.
- Inter-city **travel lanes open only when both endpoints' fronts are calm**
  (pressure low), tying exploration to the war's success.
- Rewards for crossing: rare resources, other cities' vendors, future raids.

---

## 7. Phased build plan

### Phase 1 — Vertical slice (prove the feel)
- One city, one gate, **one front**.
- `siegePressure` value (persistent on the front) with the 4 bands.
- Open-world: guards spawn outward vs. pulled-back based on band; breach state
  spawns a couple of monsters inside.
- One instanced sortie: 1 lane, 3 waves + a simple warlord, gate object w/ HP.
- Sortie outcome adjusts `siegePressure`.
- `cityId` on player save + respawn at that city.
- **Success test:** Does the front *feel* like it's losing when you walk away
  and winning when you fight? If yes, the concept is proven.

### Phase 2 — Breadth
- All cities, multiple fronts each, the in-city **war board** UI.
- Escalation tiers fully built out; named warlords per front with unique drops.
- Passive decay tuning pass for the real player count.

### Phase 3 — Travel
- Inter-city lanes gated on dual front-calm; deep-wilderness rare content.

### Phase 4 — City vs. City (Deadman-style)
- Once fronts are stable, repoint the meter to PvP/raid pressure vs. a rival
  city. Same siege system, aimed at players instead of monsters.

---

## 8. Open data model (first pass)

```
WarState (persistent, server-wide)
  cities: Map<cityId, City>

City
  id
  fronts: List<Front>
  interiorState: { vendorsOpen, bankOpen, ambientDamage }

Front
  id, cityId
  direction               // for lane/spawn placement
  siegePressure: 0..100   // THE number
  band (derived)          // PUSHING / HOLDING / SIEGE / BREACHED
  lastTickTime            // for passive decay

Player (save)
  cityId                  // citizenship
  // respawn + bank resolve from cityId

Sortie (transient instance)
  frontId, party
  wavesCleared, warlordKilled, gateHpRemaining
  -> scoreToPressureDelta() applied to Front on completion
```

---

## 9. Risks / things to watch

- **Instanced ≠ lifeless:** the persistent meter + open-world city changes are
  what sell "ongoing war." Don't let the instance become a detached minigame —
  the city must visibly react to front state.
- **Low-pop death spiral:** if decay outpaces a small playerbase, fronts fall
  permanently. Decay rate is the safety valve; tune conservatively, add a floor.
- **Grind fatigue:** waves must escalate and drop meaningfully or it's a spawn
  field. Escalation tiers + push-deeper risk/reward are the antidote.
- **Engine fit (rev-228 alter):** confirm instance support and persistent
  global state hooks before Phase 1; the meter must survive restarts.

---

## 10. Engine-fit findings (verified against the alter codebase)

Both Phase-1 dependencies were checked directly. Verdict: feasible, with **one
new building block required**.

### Instances — ✅ fully supported
- Dynamic-region system exists. Allocate via `world.instanceAllocator.allocate(chunks, config)`.
- Key files: `Alter/game-server/.../model/instance/` —
  `InstancedMapAllocator.kt` (factory), `InstancedMapConfiguration.kt`
  (builder: exit tile, owner, `DEALLOCATE_ON_LOGOUT/DEATH`),
  `InstancedMapDsl.kt` (`instancedChunks { set(...) }` builder).
- The sortie (Layer B) maps cleanly onto this: copy the front's map chunks into
  an instance, set owner = party leader uid, `DEALLOCATE_ON_LOGOUT`.
- **Gotchas:** instances must occupy the high-coord area (6400,0)–(9600,6400)
  (auto-handled, ~5000 concurrent max); rotating multi-tile objects can throw on
  chunk bounds (use `bypassObjectChunkBounds` if needed); **no existing instance
  content in this codebase** — the sortie is the first, so expect more upfront
  wiring and no example to copy.

### Per-player `cityId` (citizenship) — ✅ trivial
- Add a persistent attribute key in
  `Alter/game-server/.../model/attr/Attributes.kt`:
  `val CITY_ID_ATTR = AttributeKey<Int>("city_id")`.
  The first constructor arg IS the `persistenceKey` — a non-null key means it
  auto-saves. (No-arg `AttributeKey<Int>()` is runtime-only.)
- Auto-serializes into the player save (JSON at `Alter/data/saves/details/<name>`,
  under `attributes.attribute`). Resolve respawn + bank from it on login.

### Server-wide `siegePressure` surviving restart — ❌ no built-in support (THE gap)
- `world.attr` exists but is **runtime-only — never written to disk**, and there
  is **no shutdown/save hook** in `Server.kt`. Nothing global persists today.
- **Required new building block:** a small world-persistence helper —
  - storage: `Alter/data/saves/world/war_state.json` (front id → pressure, etc.)
  - load: in `World.postLoad()` (called from `Server.startGame()`)
  - save: **on a timer (e.g. every few min) AND on each sortie completion** —
    NOT on shutdown, since a crash would lose the meter.
- **Type rule:** persisted attributes must not be Double/Float (JSON int/float
  ambiguity on load). Store `siegePressure` as a scaled **Int** (e.g. 0–1000
  representing 0.0–100.0) everywhere.

### Net effect on the plan
Phase 1 gains a small prerequisite task: **build the world-state JSON
persistence helper** (load on boot, save on timer + sortie end). Everything
else in Phase 1 sits on existing engine support.
```

---

## 11. Step 4 — the instanced sortie (implementation plan)

The loop-closer: a playable battle a player enters from the city; waves advance
on a gate; the **outcome feeds `WarState` pressure**, which the open world (step 3)
then reacts to. Steps 1–3 (persistence, citizenship, band reactions) are done.

### Grounded engine facts (verified)
- `world.instanceAllocator.allocate(world, chunks, config)` → `InstancedMap?`,
  whose `.area` sits in the high-coord region (x ≥ 6400). `chunks` is built with
  `instancedChunks { set(destChunkX, destChunkZ, height, rot, copyTile) }.build()`
  — it **clones real map chunks** (terrain, objects, collision) into the instance.
- Config (Builder): `setExitTile`, `setOwner(uid)`, `addAttribute(DEALLOCATE_ON_LOGOUT/DEATH)`.
- Instances **auto-deallocate** when empty (25-cycle scan) or on owner logout/death;
  dealloc runs `world.removeAll(area)` so **instance NPCs are cleaned up for free**.
- Monsters: `npc.walkTo(tile)`, `npc.aggroCheck = { npc, player -> ... }` (the
  global aggro plugin then makes them attack), `npc.attack(target)`.

### Design choice — the arena IS the front, instanced
The sortie clones the front's *own* open-world chunks (the Lumbridge-north patch
from step 3). The instance is literally a private copy of the front you can see
in the world — reuses the zone tiles and reads thematically right.

### Sub-steps (each independently testable)

**4a — Instance scaffold + entry/exit.**
`SortieArena` clones the front's chunks; `::sortie` (dev) allocates an instance,
sets owner + `DEALLOCATE_ON_LOGOUT`/`ON_DEATH`, moves the player to the entry
tile; leaving/logout/death exits to the city and deallocates.
*Test:* `::sortie` drops you into a private copy; logout/leave cleans it up.

**4b — Gate + one wave.**
Gate modelled as sortie state (`gateHp: Int` + a gate-line of tiles at the city
end — NOT an entity). Spawn one wave at the wilderness end; monsters `walkTo` the
gate and are aggressive. A per-sortie tick (one world timer iterating active
`Sortie` objects, mirroring `WarFront`) damages `gateHp` from monsters on the gate
line and removes them; wave cleared → win, `gateHp ≤ 0` → loss.
*Test:* enter, kill the wave (win) vs. let them through (loss).

**4c — Waves + escalation tiers.**
Sequential waves with escalating rosters (grunts → brutes → specialists →
warlord); wave N+1 spawns after N clears. Warlord = tougher named NPC; reaching
the gate does heavy `gateHp` damage.
*Test:* survive all waves vs. gate falls.

**4d — Scoring → `WarState` (closes the loop).**
On sortie end, derive a pressure delta from performance (waves cleared, warlord
killed, gate HP remaining): win → `WarState.addPressure(frontId, -delta)`,
loss/abandon → small `+`; then `WarState.save(force=true)`. A big win can flip the
band, so step 3 re-spawns the open-world front (guards push out).
*Test:* `::war` before/after a winning sortie → pressure dropped, front reacts.

**4e — Player-facing entry + polish.**
Replace `::sortie` with a Front Marshal NPC (dialogue → start) at the city gate;
party support (owner + nearby party); reward/XP scaling by tier reached.

### Open questions to resolve in 4a/4b
1. Exact source chunks to clone + instance-local entry/gate/spawn tile math
   (offsets from `map.area.bottomLeft`).
2. Confirm the global aggro plugin fires for NPCs spawned inside an instance.
3. Gate-damage cadence (per-tick adjacency check vs. a slower drain).

### Order & payoff
4a → 4b → 4c → 4d → 4e. **4d is the milestone** — the moment player effort moves
server-wide pressure and the world visibly answers. 4e is polish.

---

## 12. The Force / Commander system (backend re-architecture)

> **Goal:** turn the war from hand-tuned per-zone troop counts into a living
> conflict where each faction is a **Force** with a troop budget and a commander
> AI that *watches the battlefield and reinforces where it's needed*. This is the
> realization of the §2 "ongoing war" vision and the foundation for city-vs-city.

### The model — three layers
1. **BattleZone** (what `WarFront` becomes): purely a *location* — staging tiles,
   per-band objectives, spawn/despawn, the combat/movement drive, door-opening.
   It no longer decides its own troop counts; it is *told* a target per side and
   fills to it. (Most of today's `WarFront` already is this.)
2. **Force**: a faction (Lumbridge knights, East goblin camp, West goblin camp;
   later, each city). A Force owns:
   - a **troop budget** (e.g. 20–60) that scales with the siege stage/pressure,
   - the set of BattleZones it fights in,
   - a **commander tick** that divides its budget across those zones.
3. **War** (top): holds the Forces + the shared `WarState` pressure, ticks them.

### The commander rule (keep it a heuristic, not a brain)
Each commander tick, per Force:
1. **Read the battlefield** — for each of its zones, count enemy strength present
   (reuse the existing in-zone NPC scan) and own strength present.
2. **Score need** — `need[zone] = max(0, enemyStrength[zone] − ownStrength[zone])`
   (a zone where the enemy outnumbers us needs reinforcement). Plus a small base
   so every zone keeps a garrison.
3. **Allocate** — `target[zone] = round(budget × need[zone] / Σ need)`. Hand each
   zone its target for this Force's side; the BattleZone spawns/withdraws to match.

That single rule produces the behaviour described: if the East camp masses 30
goblins on the north bridge and 5 on the south, Lumbridge's `need` is high north,
low south → knights shift north automatically. "Watching the other forces" is just
reading enemy counts per zone — no special messaging needed.

### Anti-oscillation (required)
A twitchy commander ships troops north↔south every tick. Mitigations:
- **Hysteresis:** only re-allocate when a zone's need changes by more than a
  threshold, or on a slower cadence (e.g. every ~5s, not every tick).
- **Commitment:** troops already committed to a zone stay until clearly not needed
  (don't yank a winning push).
- **Smoothing:** move targets toward the desired allocation by a step, not instantly.

### Mapping onto today's code
- `WarFront` → split: keep its spawn/drive/objective/door logic as **BattleZone**,
  but replace `attackerTargetByBand` / `defenderTargetByBand` with a target *set
  by the owning Force each tick*.
- The per-band objective shift (`objectivesByBand`, `pushLine`, `lineAtZ`) stays on
  the BattleZone (geometry is local).
- `WarState` pressure stays the single shared level; a Force's budget is a function
  of it (`budget = lerp(min, max, pressure)`).
- Reuse `goblinsInZone` / the NPC scan for the commander's battlefield read.

### Phased build
1. **Phase 1 — one Force, one rule.** Introduce `Force` + budget. Give Lumbridge a
   knight budget split across the *two bridges* by goblin threat. Prove "knights
   reinforce the busy bridge." (Smallest verifiable slice.)
2. **Phase 2 — goblin Forces.** East + West goblin camps become Forces with budgets
   + push logic across their zones.
3. **Phase 3 — reaction + smoothing.** Forces react to each other; add hysteresis/
   commitment so it doesn't oscillate.
4. **Phase 4 — cities.** Generalize Force to any city; two cities = two Forces with
   opposing objectives over shared zones → **city-vs-city**.

### Risks (named, from experience building §3)
- **Emergent debugging** — adaptive allocation adds emergent surface on top of an
  already edge-case-prone combat layer. Build incrementally, log allocations.
- **Oscillation** — see anti-oscillation above; treat it as a first-class concern.
- **Tuning** — budgets, thresholds, base garrison need iteration (like troop counts did).
- **Refactor churn** — additive, but it restructures the organically-grown `WarFront`.

### Not solved by this layer
NPC-vs-NPC tile collision (stacking at chokepoints) and pathing quirks are below
this layer — the commander allocates; the engine still moves/fights. Those remain
separate concerns.

---

## 13. DESIGN PIVOT — the war is OPEN-WORLD (supersedes §4 & §11, the instanced sortie)

> **The war happens in the real world, not in instances.** §4 (instanced sortie)
> and §11 (sortie implementation plan) are **retired**. They came from the early
> "instanced battle events" pick; the build pivoted to open-world, which is the
> true realization of the §1/§2 dystopia vision.

### The model
- **Cities are the only safe ground.** No hostile spawns inside; defended at the
  edges by a knight **Force** (§12) + the players themselves.
- **Everywhere outside is hostile.** Real monsters/goblins live in the world and
  aggro players; the concentrated battles are the **fronts at a city's edge**
  (Lumbridge = the two River Lum bridges + the castle approaches).
- **You venture out by getting stronger / partying up** — gated by *danger*, not
  walls: low-level players get jumped (ambient aggro is combat-level gated), and
  the war masses are lethal to the under-levelled. Deeper/further = deadlier.
- **Citizenship (§5)** ties you to a home city (respawn, allegiance).

### Why open-world beats the instance
The instance made it a queueable minigame detached from the world — the opposite
of "civilization is losing and you can *see* it." Open-world means the siege is
always there, visible, and player effort (killing attackers, holding a front)
moves it for everyone — the §2 living-war goal.

### What this means for the code
- The open-world war (§3 `WarFront`/`BattleZone` + Force commander §12) is the
  canonical war. Keep building that.
- **The 4a sortie scaffold (`SortiePlugin`, `SortieArena`, `Sortie.kt`, the
  `::sortie`/`::leavesortie` commands) is now dead code** — remove it, or keep the
  instance *primitive* dormant only if we later want optional deep-wilderness
  dungeons or instanced city-vs-city raids. It is NOT the war.
- The instance engine support documented in §10 stays valid as a *capability*; we
  just aren't using it for the core war.

---

## 14. CURRENT STATE AUDIT (2026-06 — pre-standardization)

> Honest snapshot of what's actually running vs. what the docs describe, written
> before the standardization refactor (§15+). The battle *works* but has grown
> organically; this section is the cleanup map.

### What's live and load-bearing
- **`WarState`** — the one good abstraction. Per-front scaled-int pressure + peace
  timers + `SiegeBand` + JSON persistence. Already city-agnostic. Keep as-is.
- **`WarFront`** — the battle engine: spawn/maintain, waypoint drive, engage/swarm,
  and the new **stuck/unstick** safety net. The real workhorse. Reusable but
  carries dead hooks.
- **`WarFrontPlugin`** — instantiates **4 hardcoded Lumbridge fields**, ticks them,
  derives pressure from goblin **advancement** toward the castle. All geography
  baked in here (not reusable for another city).
- **Consumers:** `WarEffortPlugin` (coins/broadcasts/peace/login warning),
  `WarServices` (band→shops/bank/store-doors), `WarHudPlugin` (the bar),
  `CitizenshipPlugin`/`City` (home/respawn).

### What's DEAD (confirmed by grep — never instantiated/called)
- **`Force.kt` — the entire commander AI.** Never instantiated anywhere. The
  doctrine allocator from §12 was written but never wired. **This is the "AI that
  watches the battle" the design wanted — it exists on paper and in this file, but
  not in the running game.** Reviving it is the centerpiece of §16.
- **`WarFront.dynamicAttackerTarget` / `dynamicDefenderTarget`** — only `Force`
  sets them → always null → fall through to fixed per-band counts.
- **`WarFront.manualAttackerOverride` + `::warthreat`** — the command doesn't exist;
  the field is never set.
- **Ambient goblin system** (`ambientTarget`/`ambientNpcs`/`ambientPool`/
  `AMBIENT_AGGRO`) — `ambientTarget` is hardcoded `0`, so it never spawns.
- **`attackersHoldingObjective()` + `OBJECTIVE_REACH`** — never called.
- **`openZoneDoors()` / `openDoors` flag** — `openDoors=false` on every field, so
  the whole door-opening path is inert.
- **`isBeingAttacked` import** — unused.

### What's BROKEN / muddled
- **Two writers fight over pressure.** `WarFrontPlugin` writes an *absolute*
  advancement value every 3 ticks (`setPressure`); `WarEffortPlugin` does
  `addPressure` for drift / knight deaths / player kills. WarFront runs more often
  and overwrites → **WarEffort's siege-clock model is effectively dead.** The bar
  is purely "how far goblins walked." Player kills still give coins + push the line
  physically, but the intended "self-driving siege clock" does nothing.
- **Two parallel battle frameworks.** `WarFront` (the siege) and `HostileZone`
  (Goblin Warren + World Bosses) overlap in concept. Keep them separate but draw
  the boundary explicitly (§15).
- **`City.fronts` is decorative.** `WarFrontPlugin` hardcodes the front and ignores
  the `City` registry, so citizenship and the war aren't actually linked.

---

## 15. STANDARDIZED MULTI-CITY SIEGE (target architecture)

> **Goal:** one reusable siege system. Adding a new city's war = adding one
> `SiegeConfig` data object to a registry. No new plugin, no copied code.

### Four layers (clean separation)
1. **`BattleField`** (what `WarFront` becomes): a *location only*. Owns spawn/
   despawn, the waypoint drive, engage/swarm/stuck-unstick. It is **told a per-side
   troop target each tick and fills to it** — it does not decide its own counts.
   (Strip the dead ambient/door/override code here.)
2. **`Commander`** (revived `Force`): one per side per city. Owns a **troop budget**
   (scales with the siege band) and allocates it across that city's fields by a
   **doctrine** each tick. *This is the AI* (§16). Writes each field's per-side target.
3. **`Siege`** (per city): owns the city's `BattleField`s + two `Commander`s (attack/
   defend) + the shared `WarState` pressure for that front. Ticks them; derives
   pressure from the physical line; runs victory/peace.
4. **`SiegeConfig` + `Sieges` registry**: all geography + tuning data for one city.
   A single generic **`SiegePlugin`** loops the registry and runs every siege.

### `SiegeConfig` (the only place geography lives)
```
data class FieldDef(
    zone: Area,                  // bounding box (kill credit, knight prey scan)
    goblinSpawns: List<Tile>,    // outer edge
    attackerWaypoints: List<Tile>,  // spawn -> ... -> goalTile
    defenderWaypoints: List<Tile>,  // muster -> ... -> spawn
    weight: Int = 1,             // commander allocation bias (e.g. main bridge = 2)
)

data class SiegeConfig(
    frontId: String,             // WarState key, e.g. "lumbridge_siege"
    cityId: Int,                 // ties to City registry
    goalTile: Tile,             // the breach point (city centre)
    knightMuster: List<Tile>,   // OPEN tiles outside the keep (no door-trap)
    fields: List<FieldDef>,
    goblinBudgetByBand: Map<SiegeBand, Int>,
    knightBudgetByBand: Map<SiegeBand, Int>,
    warArea: Area,              // covers all fields (coins/broadcast scope)
    serviceAreas: ServiceAreas, // store-door boxes, bank box (WarServices data)
)

object Sieges {
    val LUMBRIDGE = SiegeConfig( ... today's 4 fields, moved verbatim ... )
    val all = listOf(LUMBRIDGE)   // add VARROCK here later — that's the whole job
}
```

### What each existing file becomes
- `WarFront.kt` → `BattleField.kt` (engine; dead code removed).
- `WarFrontPlugin.kt` → split: Lumbridge geography → `Sieges.LUMBRIDGE`; the tick
  loop/commands → generic `SiegePlugin` driving `Sieges.all`.
- `Force.kt` → `Commander.kt` (revived, wired, improved per §16).
- `WarServices` → reads `SiegeConfig.serviceAreas` per city instead of hardcoded
  Lumbridge boxes.
- `WarEffortPlugin` → pure consumer (§17): coins, broadcasts, peace, login warning.
  Stops writing pressure.
- `City.fronts` → becomes real: a city's `SiegeConfig`(s) are looked up by `cityId`.

### Boundary with `HostileZone`
`HostileZone` stays the **PvE-pocket** framework (Goblin Warren, World Bosses): a
zone that keeps N hostile NPCs alive and aggressive, no line/pressure/commander.
The **`Siege`** system is the *organized war* (two sides, a line, a brain). Different
problems — don't merge. Document which to use: "ambient danger zone" → `HostileZone`;
"a city is under attack" → `Siege`.

---

## 16. THE AI COMMANDER, REVIVED & IMPROVED

> §12 described a weighted-split allocator. It was correct but plain, and it was
> never turned on. Reviving it is the goal — and since we're rebuilding it, make it
> a *legible, reactive enemy* the player can read and outplay, not just a hidden
> number that shuffles spawns.

### 16.1 The base rule (keep — it's good)
Each commander tick (slow cadence, ~every few seconds), per side:
1. **Read** each field: enemy strength, own strength, **players present**, and
   **line position** (how far the attackers have pushed).
2. **Score need** per doctrine:
   - **Knights — `REINFORCE_THREAT`:** `need = goblinStrength − knightStrength`,
     boosted by how close that field is to breaching. *Defend where you're losing.*
   - **Goblins — `EXPLOIT_GAP`:** `need = (maxKnights − knightsHere) + advancement`.
     *Push where they're thin and where you're already winning.*
3. **Allocate** the budget proportionally to need (+ a base garrison per field),
   then **smooth** toward it (max step/tick) with **hysteresis + commitment** so it
   reads as a slow pendulum, not a flicker. (All three anti-oscillation tools from
   §12 are mandatory — we *will* hit oscillation otherwise.)

This alone produces "knights mass at the hot bridge, goblins slide to the gap."

### 16.2 Budget = the self-driving trend (replaces artificial drift)
The siege "trending to breach on its own" should be **emergent from troop
economy**, not a magic `+1/tick`:
- **Goblin budget grows with the band** (Pushing→Breached), and **knight
  reinforcement stays capped** (slow). Left alone, goblins out-fund the line →
  advance → pressure rises → escalates → bigger goblin budget. A flywheel toward
  breach.
- **Players are the brake:** killing goblins drops goblin strength in a field, so
  knights win locally and the line recedes → pressure falls → goblin budget shrinks.
- Net: no hand-tuned drift; the trend and the comeback both fall out of the same
  allocation loop. (This is also what fixes the "east needs more goblins" balance —
  the goblin commander naturally floods the least-defended field.)

### 16.3 The Warlord — give the AI a body (NEW)
A named **Goblin Warlord** NPC leads each siege. He is the AI's avatar:
- **While alive:** the goblin commander runs at full budget and can declare a
  **main thrust** (§16.4). The horde fights at full morale.
- **Kill him:** the goblin budget is **slashed** and allocation goes **passive/
  random** for a cooldown (the horde is leaderless), and surviving goblins take a
  **morale penalty** (worse stats) briefly. A real, visible reward for a hard target.
- **Respawn:** he returns when the siege re-escalates (e.g. re-enters SIEGE), so the
  fight has a recurring "decapitate the horde" beat.

This anchors the abstract AI to something players can *attack*. Very RS, very legible.

### 16.4 Feints & focused thrusts (NEW)
Instead of always-uniform allocation, the goblin commander periodically commits a
**main thrust**: over-allocate one field for a window, then shift. Broadcast it:
> *"The Warlord turns the horde toward the EAST bridge!"*

Players race to reinforce; the AI then probes elsewhere. This turns flat pressure
into **moving hotspots** — the single biggest "it feels alive" lever.

### 16.5 Counter-player allocation (NEW)
Make player positioning matter to the brain:
- **Goblins** bias toward the field with the **fewest players** (exploit where
  humans aren't).
- **Knights** bias reserves toward fields with **no players** (the AI covers what
  players don't, instead of doubling up a gate players already hold).

So a lone defender can't trivially hold one bridge while the rest fall — and a
coordinated group can bait the AI. Emergent strategy from two simple biases.

### 16.6 Shock troops (NEW)
A commander may spend part of its budget on a few **elite units** (hobgoblins /
goblin champions; knight champions) seeded into its main-thrust field, instead of
only chaff. Adds difficulty spikes and visual variety to a focused push.

### 16.7 Observability (so the AI is readable, not random)
- **`::warmap`** — per field: goblins/knights alive, players present, this tick's
  commander target, and which field is the current main thrust.
- **Broadcasts** for thrust shifts and Warlord life/death.
- Optional later: the HUD highlights the current hotspot gate.

> Design rule: *every* AI decision should be **inferable from what the player can
> see** (where the horde masses, where the Warlord is, the broadcasts). A brain the
> player can read is fun; a brain that teleports advantage is not.

### 16.8 DECISIONS LOCKED (2026-06-16)
- **All four personality features are IN:** Warlord NPC (16.3), feints/main thrust
  (16.4), counter-player allocation (16.5), shock troops (16.6).
- **The AI fully replaces fixed per-field troop counts.** No hardcoded counts, no
  per-field min/max floor — the commander's budget + doctrine decide everything.
  Today's `EAST_GOBLINS`/`EAST_KNIGHTS` overrides and the per-band count maps are
  removed in the refactor; the east-balance problem self-corrects via EXPLOIT_GAP
  (goblins flood the least-defended field).

---

## 17. PRESSURE — ONE OWNER (fixes the §14 conflict)

- **`Siege` is the sole writer** of `WarState` pressure for its front.
- Pressure = a **smoothed line-position metric** across the city's fields — not the
  noisy "single closest goblin." Use e.g. the **mean** of each field's attacker
  advancement (or the fraction of fields where goblins hold their forward
  objective), low-pass filtered so the bar glides.
- The **trend** comes from the commander budgets (§16.2), not a drift constant.
- **`WarEffortPlugin` stops calling `addPressure`** entirely → becomes a pure
  *consumer*: coins on kill, band-change broadcasts, login warnings, and the
  victory/peace cycle (triggered by `Siege` when the line is fully pushed back).
- Result: one number, one writer, no clobbering; the HUD, services, and broadcasts
  all read the same coherent value.

---

## 18. REFACTOR BUILD PLAN (phased, each independently shippable)

> The battle finally behaves — refactor in safe slices, verify in-game between each.

- **P0 — Delete dead code (zero behavior change).** Remove `Force.kt` *(temporarily —
  it returns rebuilt in P3)*, the dynamic-target / manual-override fields, the
  ambient system, `openZoneDoors`/`openDoors`, `attackersHoldingObjective`, unused
  imports. Pure subtraction; confirm the war is unchanged.
- **P1 — Unify pressure (§17).** Make `Siege`/`WarFrontPlugin` the only writer; gut
  WarEffort's `addPressure` calls; switch pressure to the smoothed line metric.
  *Test:* bar reflects the physical line; player kills move it; one writer.
- **P2 — Extract `SiegeConfig` + registry + generic `SiegePlugin`.** Move Lumbridge
  geography into `Sieges.LUMBRIDGE`; `WarFront`→`BattleField`; one plugin loops the
  registry. *Test:* identical Lumbridge behavior, but now data-driven.
- **P3 — Revive the Commander (§16.1–16.2).** Wire two commanders per siege; budgets
  scale with band; allocation by doctrine with anti-oscillation. Replace fixed
  per-field counts. *Test:* knights reinforce the hot field; goblins flood the gap;
  the east-balance problem self-corrects.
- **P4 — AI personality (§16.3–16.7).** Warlord NPC, main-thrust feints, broadcasts,
  `::warmap`. *Test:* hotspots move; killing the Warlord visibly disrupts the horde.
- **P5 — Wire `City` (§15).** `cityId`→`SiegeConfig`; citizenship selects the war.
  *Test:* a second `SiegeConfig` (even a stub) runs with no new code.
- **P6 — Counter-player biases + shock troops (§16.5–16.6).** Polish/depth.

**Milestone = P3** (the AI is alive again). P0–P2 are the standardization;
P3–P6 are the brain and the flavor.

### Progress
- **P0 ✅ (2026-06-16)** — `Force.kt` deleted; `WarFront.kt` rewritten clean (ambient
  system, dynamic/manual overrides, `openZoneDoors`, `attackersHoldingObjective`, dead
  imports all gone). Unstick now hops units FORWARD to their next waypoint (bridges
  river gaps; fixes the "run ahead then vanish" glitch). Knights muster at the castle
  home base with the gate doors held open. Siege store-door lock removed.
- **P1 ✅ (2026-06-16)** — `SiegePlugin` is the sole pressure writer: smoothed
  advancement (glides, no snap). `WarEffortPlugin` demoted to consumer — no more
  `addPressure` (drift/knight-death/kill deltas deleted); it now only loots coins,
  broadcasts band changes, warns on login, and runs victory/peace (victory reads
  pressure ≤ MIN). NOTE: with fixed respawning counts the line can't be pushed to 0
  (nearest camp floors it ~HOLDING), so natural victory awaits the AI budget in P3;
  `::warwin` still forces it for testing.
- **P2 ✅ (2026-06-16)** — `SiegeConfig` + `FieldDef` data classes; `Sieges.LUMBRIDGE`
  holds ALL geography/counts; generic `SiegePlugin` runs `Sieges.all` (builds a
  `WarFront` per field, ticks, owns pressure, holds gate doors). `WarFrontPlugin`
  deleted. **Adding a city = adding a `SiegeConfig`** — no code changes.
- **P3 (core) ✅ (2026-06-16)** — `Commander` AI revived & wired. Two per siege:
  goblin (EXPLOIT_GAP — floods the least-defended field) + knight (REINFORCE_THREAT —
  masses where goblins are), each owning a band-scaling budget split across fields
  (`baseGarrison` + reserve × weight, smoothed `MAX_STEP`/tick). `WarFront` now takes a
  settable `attackerTarget`/`defenderTarget` (set by the commander) + per-side reinforce
  RATE (so player kills can thin a field). Fixed per-field counts deleted from
  `FieldDef`/`Sieges`; budgets live on `SiegeConfig` (`GOBLIN_BUDGET`/`KNIGHT_BUDGET`).
  Verified in-game: band climbed Holding→Under siege unattended (the flywheel), per-field
  goblin counts diverge (AI distributing), 0 errors. Tuning lives in `Sieges.kt`.
- **P4 ✅ (2026-06-16)** — AI personality, all four: **Warlord** (`general_wartface`
  leads the thrust field at ≥ Under siege; killing it disrupts the goblin AI —
  budget×40%, leaderless/uniform — for ~90s, then it returns); **feints** (rotating
  main-thrust field, ×3 weight, broadcast "the Warlord turns the horde toward the X!");
  **counter-player** (both sides bias toward fields with fewer real players);
  **shock troops** (thrust field's goblins surge to `attackerEliteDef` via `eliteMode`).
  North geography fixed with the user's coords (spawn 3224,3321 → bridge ~3235,3261-3263
  → castle); the broken far-NW field removed (now 3 fields: EAST / NORTH bridge / WEST).
  Verified: ~398 NPCs @ 8-22ms, armies ramp to budget, Warlord spawns, 0 errors.
  Shock troops are a stat-buff v1 (not visually distinct hobgoblins yet).
- **P5 ✅ (2026-06-16)** — `City` wired to the war. `SiegeConfig.cityId` links each
  siege to a `City`; lookups `Sieges.byFront`/`Sieges.forCity` + `Cities.byFront`.
  `::city` now shows the citizen's city AND its live war status; `::cities` lists every
  city + war state. `SiegePlugin` logs a consistency check at boot ("Siege X defends
  Y"). The battle layer already loops `Sieges.all`, so a second city = add a `City` +
  a `SiegeConfig` with matching ids — no code changes.
- **P6 ✅ (2026-06-16)** — Polish. **Visual shock troops**: the thrust field now spawns
  real `npc.hobgoblin` elites (own `elitePool` in `WarFront`, `eliteTarget` set by the
  commander; `aliveAttackerCount`/`closestAttackerDist`/`clear` include them; driven &
  spread with the rabble). Elite drops now key off the hobgoblin NPC (cleaner than the
  old stat check). **`::warmap`** shows per-field alive/target per side, players present,
  the `[THRUST]` field, and Warlord status. **`WarEffortPlugin` generalized per-front**:
  iterates `Sieges.all`, credits kills via `Sieges.frontAt(tile)`, per-front
  `lastBand`/victory/peace, broadcasts use the siege's display name, login warning +
  `::warwin`/`::warresume` are city-aware (player's `City.fronts`). So a 2nd city's
  consumer layer works with no new code. Verified: 0 errors, thrust field deploys elites.
  REMAINING (optional): real city names/clans (user's call); the spun-off
  WanderingGoblinsPlugin init fix.
- **P6.1 ✅ (2026-06-16)** — Unstick robustness fix. The NORTH-bridge horde wedged on
  the river's west bank (~x3237-3243, z3279-3297) and never crossed. Root cause: the
  anti-stuck timer counted "did the unit's tile change" as progress, but `spreadOut`'s
  lateral de-stack nudges (and the oscillation of a clump packed against an obstacle)
  change the tile every tick WITHOUT advancing — so the stuck counter kept resetting and
  the `UNSTICK_LIMIT` hop never fired. Now progress = "got CLOSER to the next waypoint
  than ever before on this leg" (`bestDistByNpc`, min-distance per leg, reset when the
  waypoint advances); only a genuine new-closest resets the timer, so a wedged unit
  reliably reaches `UNSTICK_LIMIT` and teleport-hops onto the bridge waypoint. Verified:
  `moveTo` is a true teleport (bridges any unwalkable gap). NOTE: the Warlord
  (`SiegePlugin.manageWarlord`) still uses a plain `walkTo(goalTile)` with NO unstick —
  latent risk it wedges where the rabble now flow; revisit if it stalls in testing.
- **P6.2 ✅ (2026-06-16)** — NORTH field geography corrected (the unstick fix exposed it:
  goblins stopped milling and promptly hopped into the river). The "north bridge" was a
  red herring. User's map-clicks pinned the bridge DECK at z3261, x3229-3241 — it runs
  **east-west**, so it only links the west bank to the EAST bank. Spawn `(3224,3321)` and
  the castle `(3222,3219)` are BOTH at x~3222-3224, west of the deck's west end (x3229) —
  i.e. the same bank — so a north→south march never needs the bridge. The old waypoints
  steered onto `(3235,3263)` (open river just north of the deck), which is where they
  drowned. Rerouted NORTH straight down the west bank: spawn → `(3224,3288)` →
  `(3224,3258)` → `(3223,3236)` → castle (defenders reversed). Field renamed `NORTH bridge`
  → `NORTH` (broadcasts no longer say "bridge"). No logic change — pure waypoint data.

**The full arc P0–P6 is complete** — the war is standardized, AI-driven, with
personality, loot, city-wired, and polished. Net new files: `SiegeConfig`, `Sieges`,
`SiegePlugin`, `Commander`, `WarDrops`. Adding a city = add a `City` + a `SiegeConfig`.

---

## 19. ENHANCEMENT MENU (optional, post-refactor — pick what excites you)

- **Capturable objectives:** a watchtower/banner mid-field; whoever holds it buffs
  their side. Gives players a micro-objective beyond "kill goblins."
- **Reinforcement caravans:** knight reserves physically march from the castle; if
  goblins intercept the column, those knights never arrive. Makes the supply line a
  target.
- **Feudal rank → war command:** a high-`Title` player can **rally** — temporarily
  boost the knight budget/morale in their field. Ties the Duke Horacio rank economy
  *into* the war (synergy with existing progression).
- **War machines:** goblin catapults that bombard a field; a knight ballista. Set
  pieces for the big battles.
- **Morale / last stand:** when a field's knights drop below N, survivors get a
  desperate attack/defence boost before the line breaks.
- **Contribution scoring:** track per-player war contribution → rank/reward; a
  kill-streak that pulls players toward the hottest gate.
- **Death/carnage FX:** gore GFX + brief corpses so a 100-vs-40 clash *looks* like a
  battle, not units blinking out.

---

## 20. PIVOT — AI-COMMANDED RAIDS + "THE WAR BRAIN" (2026-06-16, supersedes the constant-siege model of §3/§16/§17)

> **Why the pivot.** With the open-world **roaming goblins** now holding the city
> perimeter (`WanderingGoblinsPlugin` / `CityFrontierPlugin`), a 24/7 castle assault
> is redundant. The castle battle becomes a **discrete, deeply AI-commanded event** —
> a *game of war* that is the server's headline feature — instead of a permanent
> grind. The 0–100 pressure / four-band model (`SiegeBand`, smoothed advancement) is
> **retired**; status is now discrete (At peace / Under raid — tier / City fallen).

### The model
- **Raids are events.** PEACE (quiet; the 100-knight pool replenishes; a random
  countdown) → UNDER_RAID → resolve → PEACE. Driven by `AttackDirector` (one per
  `SiegeConfig`).
- **Tiered finite roster.** A raid rolls a `RaidTier` (Probe ~50 / Raid ~150 / Siege
  ~300) — a *finite* goblin reserve the AI maneuvers; the dead don't respawn, so a
  raid actually ends. Only the Siege tier is Warlord-led.
- **Finite forces, AI-distributed.** The goblin `Commander` (EXPLOIT_GAP) maneuvers
  the shared reserve across all 3 fields; the knight `Commander` (REINFORCE_THREAT)
  distributes the finite **100-knight pool** to meet it (pool persists losses, slowly
  replenishes in peace). Both draw from a shared supply owned by the director.
- **Victory objectives (the win conditions).** Goblins hunt **General Zo** at the
  castle goal — reaching it (a breach) makes the **city FALL** (lasting penalty:
  `WarState.cityFallen`, vendors/bank shut via `WarServices`, login warnings, slow
  recovery, then Zo "returns"). Knights hunt the **Warlord** (`general_wartface`) —
  killing it routs the horde (disrupt + half the un-spawned reserve flees). Winning
  the defense pays **rare loot** to contributors (`RaidRewards` + `WarParticipation`),
  guaranteed for the MVP / Warlord-killer.

### The War Brain — layered AI (the selling point)
- **L0 `BattleAssessment`** — perception: per-field alive counts, **players present
  with combat level + HP**, summed combat power, momentum, Warlord/Zo status. One
  consistent snapshot per tick.
- **L1 `WarPostures` / `WarBrain`** — strategy: each side picks a posture from the
  assessment. Goblin THRUST / PROBE / RALLY; knight HOLD / HUNT / GUARD_ZO (collapse
  back to defend Zo when goblins close in).
- **L2 `Commander`** — allocation: divides the live finite supply across fields by
  doctrine + posture focus field + counter-player bias. (Budget source is the live
  reserve/pool, not a band table.)
- **L3 `TargetSelector`** — squad targeting: scored focus-fire
  (`prox + threat + lowHp + focus + objective`). High `wThreat` = **target the
  highest-combat-level players first**; a focus cap converges a squad on one target so
  it actually dies; goblins deliberately gank players, not just blunt-aggro them.
- **L4 objective hunters** — the Warlord (knights' kill target) + Zo (goblins' breach
  target) as real win conditions in `AttackDirector`.

### Files
New: `AttackDirector`, `BattleAssessment`, `TargetSelector`, `WarPostures`,
`RaidRewards` (+ `WarParticipation`), `GeneralZoPlugin`. Rewritten: `WarState` (retire
pressure → persist knight pool + fallen ticks + runtime `RaidStatus`), `Commander`
(pure allocator, posture-driven), `WarFront` (bounded `spawnGoblins/Elites/Knights` +
`withdrawDefenders`, no auto-respawn; `combat()` uses `TargetSelector`; goblins target
players), `SiegePlugin` (slim driver; `::warraid [tier]`, `::warfall`, repointed
`::warmap`/`::warreset`/`::warfront`), `SiegeConfig`/`Sieges` (raid tiers + pool +
intervals), `WarHudPlugin` (discrete status on interface 1001 — no cache change),
`WarEffortPlugin` (loot + participation + login warning), `WarServices` (fallen→shops),
`WarStatePlugin`/`CitizenshipPlugin` (status readouts).

### Renames (cache edits, by the user via Displee — rscm keys unchanged)
- `knight_of_saradomin` (id 2213) → **"Knight of Lumbridge"**.
- `melee_combat_tutor` (id 3216) → **"General Zo"** (now the castle's defense
  commander NPC: live-status dialogue + rank-gated stubs for the future
  player-controlled war & troop purchasing).

### `// WAR BRAIN ROADMAP` → the deep version (next horizon, anchored in code)
The first pass is "Foundation + light postures." The intended deep build (search the
codebase for `WAR BRAIN ROADMAP`):
- **Perception:** equipment/prayer threat (not just combat level), damage-dealt
  history, line-of-sight maps.
- **Strategy:** multi-step plans, deception/baiting, supply-timing, morale-driven
  retreat/regroup.
- **Allocation:** held reserves for counter-pushes, feint allocations, coordinated
  multi-front maneuvers.
- **Targeting:** assist/hand-off calls, threat decay, and **ranged & mage unit roles**
  with kiting / LoS (NPCs planned later).
- **Squad:** formations, per-unit morale & retreat.
- **Strategic:** multi-city command — the bridge to the future player-controlled war
  (lord/minister/king ranks redirect troops, conquer cities, purchase companies via
  General Zo). See §19 "Feudal rank → war command".

### Stakes & depth decisions (2026-06-16)
- **High stakes locked:** Zo dies → city falls (lasting); winning the defense rewards
  rare loot. **Depth:** ship Foundation + light postures now; **the deep version (#3:
  formations, morale/retreat, hand-off, baiting, ranged/mage) is the planned next
  step** — pursue as far as feasible.

---

## 21. SPATIAL AI + ATTACKABLE VIP (2026-06-16, builds on §20)

> *"Build it all."* The War Brain gains a **spatial reasoning layer** over the tile grid,
> a **learning datasheet**, **predictive targeting**, a **pluggable Strategist** (LLM-ready),
> and General Zo becomes a **real attackable VIP**.

### The spatial brain — `TacticalMap`
The game world is already an exact tile grid, so we read it into the standard RTS-AI fields,
all bounded to the battle box (~120×120) and O(area):
- **Walkability** sampled once from `world.collision.isClipped`.
- **Flow field** — a BFS distance field FROM General Zo over walkable tiles. Goblins follow
  the downhill gradient (`nextStepToward`, with a player-threat penalty so they skirt
  kill-zones), so **300 units path toward the castle for the cost of one search**, funnelling
  through the gate/bridges because the grid forces it. Wired into `WarFront.drive` (goblins;
  flag `SiegeConfig.useFlowField`, waypoint fallback when off-grid). Knights stay on waypoints.
- **Influence / threat maps** — goblin/knight strength + player threat (combat level) stamped
  with radial falloff each tick; the Strategist reads them ("where is it hot / weak").

### Learning + datasheet
- **`WarRecorder`** (`::warlog`) — opt-in per-tick NDJSON of every unit/player tile
  (`{t,g,k,p}`) to `data/saves/world/war_replay_<front>.ndjson`. The literal coordinate
  datasheet: a replay/heatmap substrate for debugging and (later) training. Off by default.
- **`WarMemory`** (persisted) — per-field EMA of player presence across raids; the goblin
  Strategist **feints toward the field players historically neglect**. v1 is per-field; the
  deep version mines `WarRecorder` for a per-tile habit map.

### Predictive targeting
`MovementTracker` records per-pawn velocity; `TargetSelector` scores proximity to the
target's **predicted** tile (lead/intercept) so the horde cuts off a fleeing player.

### Pluggable Strategist + the LIVE LLM commander
Strategy (postures + each side's focus field) is a `Strategist` interface producing a
`StrategicIntent` the `AttackDirector` executes. `HeuristicStrategist` is the deterministic
default (reads `WarMemory` + `TacticalMap`). **`LlmStrategist` (live)** wraps it and, while
enabled, lets **Claude command the war in English**:
- **Off the game thread.** `decide()` snapshots a compact battle brief on the game thread, hands
  it to a single daemon worker, and returns the last *fresh* cached `StrategicIntent` (or the
  heuristic). The 600 ms loop is never blocked on HTTP.
- **Slow cadence + freshness.** One call in flight at a time, ~every 11s during a raid; a cached
  plan older than ~36s is dropped back to the heuristic so a stalled call can't freeze the AI.
- **Config.** `WarLlm` reads `ANTHROPIC_API_KEY` (required) and `WAR_LLM_MODEL` (default
  `claude-opus-4-8`; set `claude-haiku-4-5` for lowest latency/cost). Toggle at runtime with
  `::warllm`. Raw HTTP via the JDK `HttpClient` + `org.bson` JSON (no new Gradle deps); no
  `temperature`/`thinking` (removed on Opus 4.8); JSON‑only output, defensively parsed + clamped;
  any failure falls back to the heuristic. The model sets `{goblinPosture, knightPosture,
  goblinFocus, knightFocus}`; the deterministic L2/L3 layers carry it out.

### General Zo — attackable VIP
- Spawned + owned by the `AttackDirector` (not the dialogue plugin) at `SiegeConfig.zoTile`
  (inside Lumbridge castle — **placeholder tile, tune in-game**), with `zoDef` combat stats.
- **Players can't attack him** (his cache NPC has no Attack option); **goblins call
  `attack()` on him directly** (he's the goblin objective in `TargetSelector` + their flow-field
  destination), so he's huntable by the horde but un-griefable. Knights `GUARD_ZO` to defend him.
- **His death is the loss condition** → city falls; he respawns when the city recovers.

### `// WAR BRAIN ROADMAP` (now seeded in `TacticalMap`, `WarMemory`, `Strategist`)
Danger-avoiding flow fields, chokepoint detection (scan walkability for 1-wide gaps),
encirclement (multi-objective fields), fog-of-war (a per-tile seen mask + scouts), per-tile
learned kill-zones, morale waves, ranged/mage roles with kiting/LoS, and the live LLM commander.
