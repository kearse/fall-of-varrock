# THE GUNS OF ASGARNIA

**Asgarnia — BREACH, quest 3 · Campaign thread: Artillery · Primary NPCs: Sir Amik Varze, Nulodion, the
Blast Furnace Foreman · Locations: Falador → Dwarven Mine (Ice Mountain entrance) → Keldagrim's Blast
Furnace → the Dwarven Mine**
**Quest type: industry / diplomacy / skilling / combat · Development cost: Low–Medium** (dialogue + quest
state; one temporary personal spawn; one instanced yard; the Blast Furnace restored as a general system;
one small reusable artillery piece — no new map, NPC, item, ore, metal or industrial system)
**Status: BUILT 2026-09-12** (branch `claude/guns-asgarnia-quest-395791`, on main 087b0fb1 = A Kingdom Alone)

**Primary purpose:** Asgarnia has not forgotten how to build dwarf multicannons — Nulodion still knows
exactly how. Twelve years of war destroyed, wore out and cannibalised the guns, and the dependable supply
of military steel from Keldagrim disappeared when the roads failed. The player restores a *limited*
Asgarnia–Keldagrim military steel relationship, makes the first symbolic shipment on the **real Blast
Furnace**, and returns it to Nulodion for the first replacement multicannon — which gets its field test the
same hour. Existing RuneScape systems become consequences of the Fall; nothing is replaced by a custom
foundry, precision-tooling system or quest-only cannon.

## Core implementation decisions

**A framework quest** (`content/quests/asgarnia/GunsOfAsgarnia.kt`, `QuestDefinition` key
`guns_of_asgarnia`, chain slot 16, journal varp 4695, native row Dwarf Cannon). It **auto-begins** the
moment its gate opens — the design's ARTILLERY journal lead.

**Gate.** *At the White Wall* (Asgarnia quest 1) once a quest is registered under `at_the_white_wall`;
until that PR lands, the deepest registered main-story quest (`a_kingdom_alone` → `first_reclamation` →
`the_north` → `recruit_trials`). Does **not** require *A Matter of Trolls*; the two may be done in either
order and Sir Amik's debrief recognises whichever is complete (`QuestRegistry.isComplete(p,
"a_matter_of_trolls")` / the flag `asgarnia.northern_front_secured`).

**Implementation check (the design's rule) — what the build actually had.**

| System | Found on `main` 2026-09-12 | Decision |
|---|---|---|
| Blast Furnace | **Absent.** PR #341 (2026-09-03) built it, but its base branch was `pvm/12-guardians-of-the-rift`; both it and GotR (#340) merged into that stacked branch and never reached `main`. The Keldagrim room (region 7757) stood in the cache with nothing behind it. | **Restored as the normal reusable system**: `content/minigames/blastfurnace/` from commit b89369ae, with three fixes — the dispenser multi-loc is bound on its CHILD ids (9093/9095/9096; the base 9092 never receives the click), the Foreman is on the shared `NpcTalk` router, and two seams (`feeWaivers`, `barListeners`). Portal row **Blast Furnace** (Mini-Games tab; client mirror `LofTeleportsData`). Wiki article restored. Team 2's coffer ruling unchanged. |
| Multicannon | **Absent** — no player cannon, no NPC artillery; only the decorative loc 11868 in Nulodion's yard and cannon parts in drop tables. | **Reusable war artillery, not a quest fake**: `content/war/artillery/DwarfCannon.kt` — a placed loc-6 multicannon (`CannonEmplacement`) a player LOADS with cannonballs (Fire) and that then fires by itself at hostile npcs its owner names: one shot a tick, cannonball gfx 53, up to 30, the ranged hit-delay curve, kill credit to the loader. The White Wall finale's batteries and any march gun line reuse it. The *player-owned* OSRS multicannon (parts, set-up, decay, pick-up) is deliberately NOT built: parts would need a Team 2 ruling on how they enter the economy; the firing engine is written so that can sit on top. Limit: no loc-animation packet exists, so the barrel does not visibly rotate. |
| Nulodion | Stock world spawn 1400 @ 3011,3453 (Talk-to, Trade), his hut at the Dwarven Mine's Ice Mountain entrance; his **workshop yard** south of the fence already holds a display multicannon, cannon parts, a work bench and sacks (r12085 loc dump). | Reused in place. Everyday lines added (`NpcTalk` default). |
| Blast Furnace Foreman | Stock npc 2923 (Talk-to, Pay); world spawn 1944,4958; the furnace plugin hand-places him at 1942,4958. | Reused — the recognisable operator IS the Keldagrim contact. No "Master Steelwright". |
| Sir Amik Varze | Stock 4771 @ 2960,3336,**2**, Falador castle west tower. **Unreachable**: nothing bound the white-stone staircases (24072/24075 Climb-up, 24074/24068 Climb-down, 24067) — `LadderPlugin` only knew the 16671 family. | `LadderPlugin` now binds them (climb to the nearest walkable tile of the target plane — the stairwell above a spiral is clipped). The identical block is carried by the At the White Wall (#351) and A Matter of Trolls (#353) PRs so any merge order is clean. Amik's everyday lines belong to At the White Wall; this quest registers quest-priority branches + a placeholder. |
| Black Knights | 516 "Black Knight" (level 33, Attack) — the Kinshra of the campaign. The "Black Knight Captain" 4777 has no Attack option. | Reused, renamed at spawn ("Kinshra Raider" / "Kinshra Raid Captain" / "Kinshra Saboteur"), stats via `NpcCombatDef` copies (raider 60/55/55, 75 hp; captain 75/65/70, 120 hp). |
| Steel / ore / coal / cannonballs / bucket | All stock items (2353, 440, 453, 2, 1929). | Reused. No "military steel", no crate token. |

## Integration audit — every beat mapped to the build

| Beat | Existing NPC / place (unchanged) | Existing system | Code seam |
|---|---|---|---|
| "You said Falador has another problem." | **Sir Amik Varze**, Falador castle top floor | `NpcTalk` (bindTalk, shared with A1/A2) | `talk(AMIK, "start")` → satisfy |
| Nulodion: steel, Keldagrim, the assignment | **Nulodion** 3011,3453 | `NpcTalk` | `talk(NULODION, "nulodion")` |
| Travel to Keldagrim | The **Blast Furnace** room (region 7757, loaded by the furnace plugin) | the teleport portal (Mini-Games → Blast Furnace, landing 1940,4958) — normal server-wide access, not a quest teleport | `TeleportRegistry` row `blast_furnace` |
| The Foreman refuses; the route | **Blast Furnace Foreman** 1942,4958 | `NpcTalk` (the furnace plugin binds him; default branch = his explanation) | `talk(FOREMAN, "keldagrim")` |
| The Kinshra raiding cell | the clearing in the trees **west of Nulodion's yard** (x 2998-3006, z 3448-3455 — the pocket the surface route crosses between the cliffs and the mine; r11829 collision dump) | temporary PERSONAL spawn: 5 raiders + 1 captain, loot-less, aggressive only to their owner; respawned minus the dead on login, removed on logout / step clear | `KinshraRoute` (`GunsOfAsgarniaEncounters.kt`); step `route` = `Predicate(kills ≥ 6)` fed by the additive death hook — *anyone's* kill counts |
| "The Kinshra raiding position has been cleared." / patrols line | — | chat lines on the sixth death; no caravan simulation | `KinshraRoute.onNpcDeath` |
| The agreement + the trial order's materials | the Foreman | items: 10 iron ore, 10 coal, 1 bucket of water (overflow → bank) | `talk(FOREMAN, "agreement")`: give → satisfy back-to-back (dupe-proof) |
| **Produce 10 steel bars** | the Blast Furnace (belt 1943,4967, dispenser 1940,4963) | **the normal furnace**: 30 Smithing (inherited, not a story gate), half coal, hot bars; the coffer is on Keldagrim's account while on this step (`feeWaivers`) | `barListeners` → `GunsOfAsgarnia.onBarsTaken` counts steel taken from the dispenser (`kills` counter → client "(n/10)"); at 10 the step clears with "The first trial order is complete." The Foreman re-issues ore once if it is lost |
| The Foreman's word on the trial | the Foreman | dialogue | `talk(FOREMAN, "shipment")` (optional) |
| Ten bars to Nulodion | Nulodion | 10 × `item.steel_bar` in the pack (noted/banked refused) | `talk(NULODION, "shipment")`: remove → satisfy back-to-back, then straight into the assembly |
| Assembly | Nulodion at his bench | narration (no minigame, no custom pieces) | `talk(NULODION, "cannon")` → satisfy → `sabotage.onEnter` |
| **"Kinshra!"** — the workshop defence | a **private copy of Nulodion's yard** (`QuestInstances`, Area 3000,3440–3031,3471): Nulodion at his bench (3013,3445), two Dwarven Mine guards, the new gun on its stand (3x3 at 3015,3444), six Kinshra in two waves (west opening, then the north fence gaps) | the war-artillery emplacement + the instance sweep; Nulodion gives 30 cannonballs; **Fire** loads the gun; the gun assists, the player fights | `WorkshopDefence`; step `sabotage` = `Predicate(kills ≥ 6)`; death/logout/leave/10 min end it without credit and Nulodion restarts it; leftover cannonballs go back |
| §44 battle lines | — | overhead chat on the first two shots | `CannonEmplacement.onShot` |
| §46 payoff / §49-53 debrief | Nulodion / Sir Amik | dialogue; completion | `talk(NULODION, "payoff")`, `talk(AMIK, "report")` → completes: +50 War Effort, flag `asgarnia.artillery_restored`, 2 QP; `onComplete` prints the ASGARNIA — BREACH strategic update |

**Rejected options.** A quest-only "click furnace → wait → receive quest steel" (forbidden; the furnace was
restored instead). A custom cannon-grade alloy or a "military shipment" token (ten ordinary steel bars).
A caravan/escort simulation (two chat lines). A cannon assembly minigame (Nulodion is the engineer). A
full player-owned multicannon system (a Team 2 economy question; the engine is reusable regardless).
A quest teleport to Keldagrim (the portal row is a normal destination for everyone). Spawning the
raiding cell in the shared world as world-hostile (owner-only aggro so passers-by are not jumped).
Solving BREACH here (that is the finale's payoff — `StrategicObjectives.solve(p, Breach)` is untouched).

## Dialogue

Shipped verbatim from the design (§5-53) with joins only to save clicks; see `GunsOfAsgarnia.kt`.
Additions beyond the design: Sir Amik's mid-quest nudges; Nulodion's "come back" lines per step and his
everyday lines; the Foreman's route reminder and progress/re-issue line; the alarm ("A dwarf on the fence
shouts: Kinshra!"), Nulodion's overhead lines during the fight ("Kinshra! Through the west gap!", "More
of them - round the mine!", "Still standing. Both of us."), and the §54 strategic update.

## Quest journal (server step ids = client rows)

| State (step id) | Journal |
|---|---|
| START (`start`) | Falador's artillery has been depleted by twelve years of war. Speak with Sir Amik. |
| NULODION (`nulodion`) | Ask Nulodion why Asgarnia cannot replace its dwarf multicannons. |
| KELDAGRIM (`keldagrim`) | Travel to Keldagrim and discuss restoring military steel shipments. |
| ROUTE (`route`) | Clear the Kinshra raiding cell disrupting the southern steel route. (n/6) |
| AGREEMENT (`agreement`) | Return to the Blast Furnace Foreman. |
| FURNACE (`furnace`) | Produce 10 steel bars at the Blast Furnace for the first trial order. (n/10) |
| SHIPMENT (`shipment`) | Take the first steel shipment to Nulodion. |
| CANNON (`cannon`) | Help Nulodion complete the first replacement multicannon. |
| SABOTAGE (`sabotage`) | Defend the workshop and field-test the cannon against the Kinshra. (n/6) |
| PAYOFF (`payoff`) | Speak with Nulodion about the first cannon. *(the §46 beat as its own step — re-triggerable after a closed dialogue)* |
| REPORT (`report`) | Report the restored artillery supply to Sir Amik. |
| DONE | Keldagrim has resumed limited military steel shipments and Asgarnia can produce replacement multicannons again. |

`::guns` prints the objective (with progress) and opens the Quest Journal on the quest. `::bf` is the
furnace's own status line.

## Rewards

**2 Quest Points** · **50 War Effort** · flag **`asgarnia.artillery_restored`** (`Flags`) · Smithing XP from
the ten bars (the furnace's own) · the Blast Furnace and its portal row for everyone (not a reward — a
restored system) · main campaign progression: the artillery component of BREACH. No multicannon
ownership is granted (none exists as open-world gameplay); Falador's *capability* is what was restored.

## Strategic update (printed on completion)

ASGARNIA — BREACH · Northern Frontier: SECURED if A Matter of Trolls is complete, else UNRESOLVED ·
Artillery Production: RESTORED · Keldagrim Military Steel: LIMITED TRADE RESTORED · Kinshra Front: ACTIVE ·
Temple Knight Intelligence: UNRESOLVED · BREACH: INCOMPLETE.

## Development section

| Field | The Guns of Asgarnia |
|---|---|
| Start NPC | Sir Amik Varze, Falador castle west tower top floor (2960,3336,2) — auto-begins; he opens it |
| NPCs Used | Sir Amik Varze · Nulodion (3011,3453) · Blast Furnace Foreman (1942,4958) · Black Knight 516 renamed · Dwarf 1401 renamed ("Dwarven Mine guard") · the furnace's five dwarves |
| Locations | Falador castle · Nulodion's hut and yard (unchanged) · the clearing west of the yard · the Blast Furnace room |
| Gameplay Used | The Blast Furnace (restored) · the teleport portal · War Effort · `QuestInstances` · the war-artillery emplacement · `NpcTalk` |
| Dialogue | Amik ×3 beats · Nulodion ×7 + everyday · Foreman ×5 · alarm/fight/victory lines · strategic update |
| Quest State | Framework `QuestStates` (`guns_of_asgarnia`: step ids above; counters `kills` per step, `captain`); flags `quest.guns_of_asgarnia.done`, `asgarnia.artillery_restored` |
| Journal | table above; client `LofQuest.GUNS_OF_ASGARNIA` (varp 4695); native tab = Dwarf Cannon relabelled (varp 0, complete 11, sort "17 The Guns of Asgarnia") |
| System Hooks | `NpcTalk` · `QuestEngine` poll (three Predicates + auto-begin) · the additive npc-death list · `BlastFurnace.feeWaivers` / `barListeners` (new seams) · `QuestInstances` · `DwarfCannon` · `Reward.WarEffort` / `Reward.Flag` |
| Temporary Content | The road cell (personal, loot-less, owner-only aggro) · the instanced yard (Nulodion copy, 2 guards, 6 Kinshra, 1 gun) |
| New NPCs | NONE |
| New Maps | NONE |
| World Changes | NONE (the Blast Furnace room is the stock region; the portal row is a normal destination) |
| New Mechanics | The reusable war-artillery emplacement (`content/war/artillery/`) — a general war system, not a quest mechanic; Falador castle staircases bound (a fix) |
| Development Cost | **Low–Medium** — Script + the restored Blast Furnace + one small reusable system |

## Shared-file contract (Asgarnia campaign, four concurrent PRs)

Keys `at_the_white_wall` (#351) · `a_matter_of_trolls` (#353) · `guns_of_asgarnia` (this) · `old_wounds`;
chain slots 14/15/16/17, varps 4693/4694/4695/4696, sort keys = slot + 1 ("15 …" … "18 …"); each PR
adds its own constants, the client `LofQuest` entries for slots 14 and 15 are carried verbatim from
#353 so any merge order is clean, and whoever lands last re-checks `QuestBook` constants vs enum order
and bumps `LAST_INDEX`. The `LadderPlugin` staircase block is identical in #351, #353 and here. Sir Amik:
`bindTalk` + quest-priority branches only (his everyday lines are #351's).

## Operator steps after merge

1. **Quest cache relabel** (Actions → *Quest cache relabel* → `relabel`, then `hide`): adds "The Guns of
   Asgarnia" to the stock quest tab (Dwarf Cannon's row). One run covers whichever Asgarnia PRs have merged.
2. **Client:** the `ship-client` workflow runs on the push (client/ changed — quest journal entry, teleport
   mirror row); launchers auto-update.
3. **Smoke test** on an account past the gate: "The Guns of Asgarnia — begun" → Amik (castle stairs now
   climb) → Nulodion → portal → Blast Furnace → Foreman → the cell west of Nulodion's yard (six kills,
   anyone's) → Foreman (kit) → belt / dispenser (bucket) → 10 bars → Nulodion (bars taken, assembly,
   alarm, instanced yard: Fire the gun, six saboteurs) → Nulodion → Amik → complete, +50 WE, the
   strategic update; `::guns`, the Quest Journal row, `::bf`. Then `::questdebug reset guns_of_asgarnia`.
   Also verify: the furnace for a normal player (coffer required, XP, hot bars), the portal row lands at
   1940,4958, Falador castle stairs on all three floors.

## Known follow-ups (not this quest's scope)

- Guardians of the Rift (#340) is likewise not on `main`; restore it the same way if wanted.
- A player-owned multicannon (parts, set-up, decay, pick-up, Nulodion repairs) on top of `DwarfCannon` —
  needs Team 2's ruling on how parts enter the economy.
- The White Wall finale's batteries: several `CannonEmplacement`s along the White Knight line, fed from
  the march.
