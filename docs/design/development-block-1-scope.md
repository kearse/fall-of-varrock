<!-- Converted from Downloads\FoV_Development_Block_1_Scope.docx (September 2026) by a plain OpenXML pass; the .docx is canonical. Part of the Block-1 design authority - see docs/LEGACY.md for what it supersedes. -->

FALL OF VARROCK
Development Block 1 — Systems & Foundation
Developer Scope • September 2026
This is the build-now document. It defines what developers may safely implement before detailed quest implementation briefs and final dialogue are complete.

## 1. Documents to Use

Development Block 1 should use only the current documents below as design authority.

| PRIORITY | DOCUMENT | HOW TO USE IT |
| --- | --- | --- |
| 1 | FoV Development Block 1 — Systems & Foundation | Immediate implementation scope, sequencing, and do-not-build boundaries. |
| 2 | FoV Current Design Authority & Developer Handoff | Current systems/world decisions and explicit legacy contradiction cleanup. |
| 3 | Fall of Varrock — Master Story & Quest Plan | Narrative context so system work does not accidentally conflict with the planned story. |

Do not use the older eight-document FoV Documentation Set as current design authority. Those documents remain historical/implementation reference only because they contain superseded concepts such as permanent Varrock liberation, district pressure, defensive General Zo wars, old rank shortcuts, and obsolete raid-city assumptions.

## 2. Block 1 Goal

Block 1 is not the quest-production block. Its purpose is to reconcile the existing server with the current design and leave stable systems that future quests can call into.
Clean up contradictory legacy behavior.
Stabilize ranks, authority, war, supplies, companions, Rogue Knights, War-Forging, and quest/journal infrastructure.
Preserve reusable code instead of rebuilding systems solely because their old design wrapper changed.
Keep classic OSRS content mechanically faithful.
Create clean APIs/hooks that Block 2 quests can use.

## 3. Build Now — Core Systems

| PRIORITY | SYSTEM | BLOCK 1 WORK |
| --- | --- | --- |
| P0 | War architecture | Remove/disable recurring defensive Lumbridge/General Zo gameplay. Preserve reusable battlefield AI, troop movement, pathing, target selection, contribution, and payout infrastructure. |
| P0 | Fallen Varrock state | Remove player-facing district pressure/liberation logic and any permanent ownership assumptions. Districts may remain as locations only. |
| P0 | Marches | Make normal Marches/Grand Marches automatic, public, offensive, and supply-free. Support a flexible hostile-target pool outside deep Varrock. |
| P0 | War Effort / Realm Supplies | Separate personal lifetime War Effort from shared consumable Realm Supplies. High-tier commanded wars may spend supplies; scheduled Marches do not. |
| P0 | Ranks / authority | Preserve Peasant→King ladder, but decouple major promotions from simple coin/quest shortcuts. Enforce: participation open, command rank-gated. |
| P0 | Companions | Enforce one active companion maximum for everyone. Preserve persistent named soldiers, gear, XP, and future roster expansion. |
| P1 | Rogue Knights | Decouple Rogue progression from mandatory main-story/rank progression. Keep camps, escalating bosses, XP/loot, and PK-style AI where useful. |
| P1 | War-Forging | Preserve Royal Smith + Commendations + base-gear upgrade model. Remove dependencies on liberated districts/obsolete currency clutter. |
| P1 | Quest journal | Support Main Campaign, Regional Campaigns, strategic objectives, optional/service content, active quest tracking, and objective arrows without hard-gating ordinary gameplay. |
| P1 | Extraction zones | Remove Falador and Al Kharid as hostile raid/extraction locations. Keep the reusable extraction system; replacement locations remain open. |
| P1 | Boss compatibility | Do not redesign classic OSRS bosses. Audit only for technical compatibility with FoV systems/companions. |

## 4. Build Now — Infrastructure for Block 2

Reusable quest-state/objective framework and journal hooks.
Small quest-instance support for contained story sequences.
Hooks for starting/joining public war events from quests without making the quest own the shared overworld state.
Contribution checks suitable for achievements such as Veteran of Varrock.
Rank eligibility hooks separate from automatic promotion.
War Effort and Realm Supplies APIs usable by quests and regional content.
Companion allow/deny hooks per activity or boss.
Transport-route state hooks for later Kandarin/special-access quests without globally disabling ordinary travel.
Reusable NPC dialogue/action hooks; final dialogue text will come in Block 2.

## 5. Do NOT Build Yet

| AREA | WHY |
| --- | --- |
| Full quest implementations | Second-pass quest implementation briefs are not yet complete. |
| Final dialogue scripts | Dialogue should be polished after quest flow exists; only temporary/dev dialogue should be used now. |
| Late Arrav quest chain | Post–first-major-assault sequence is still being finalized. |
| Adventurer-parent reveal implementation | Story beat is locked at high level, but exact sequence/timing is not. |
| Deep Senntisten maps/mechanics | Narrative architecture and final threat still require design/research. |
| Final boss / thing beneath Senntisten | Explicitly OPEN. |
| King endgame requirements | High-level philosophy is locked, exact thresholds/milestones are not. |
| Replacement extraction-zone maps | System survives, exact locations are still OPEN. |
| Full PvP Training Academy | Future high-priority expansion; not Block 1 launch scope. |
| Classic boss redesigns | Intentionally prohibited; preserve OSRS learning/practice value. |

## 6. Safe Story Work in Block 1

Developers may build neutral infrastructure and placeholder scaffolding for early quests, but should not make narrative decisions. If story-specific work is needed before a quest brief arrives, use placeholders rather than inventing canon.
Lumbridge opening can receive technical scaffolding/instance/event support, but final objectives/dialogue wait for the quest brief.
Regional locations/NPCs that already exist can be audited for asset reuse.
First-major-Varrock-assault war infrastructure can be prepared as a generic high-tier public operation, without scripting the story reveal yet.
Existing OSRS NPC models should be reused unless a custom model is independently justified; do not visually age NPCs merely because 12 years passed.

## 7. Recommended Implementation Order

| SPRINT ORDER | FOCUS |
| --- | --- |
| 1 | Repository contradiction cleanup: defensive war, district pressure, obsolete raid-city assumptions, mandatory Rogue/rank shortcuts. |
| 2 | War Event foundation: offensive Marches/Grand Marches, public participation, contribution, target abstraction. |
| 3 | War Effort + Realm Supplies + Resource Contracts/Depot/Drives reconciliation. |
| 4 | Ranks/authority + Veteran/eligibility hooks. |
| 5 | Companion one-active rule + persistence/equipment/XP cleanup. |
| 6 | Rogue camps/boss progression decoupling and March-target integration. |
| 7 | War-Forging/economy cleanup. |
| 8 | Quest journal/objective/instance infrastructure. |
| 9 | Extraction system map decoupling and future-location abstraction. |
| 10 | Regression pass: classic OSRS bosses/content remain faithful and ordinary gameplay is not accidentally quest-gated. |

## 8. Block 1 Exit Criteria

No active design path depends on permanent Varrock liberation or district-pressure progression.
No recurring core gameplay depends on defending General Zo/Lumbridge from the old siege system.
Scheduled Marches function as simple public offensive events.
War Effort and Realm Supplies are clearly separate in code and player-facing concepts.
Rank gates command authority without blocking participation in active wars.
Only one companion can be active at a time.
Rogue Knights function as optional progression.
War-Forging no longer relies on obsolete liberated-district state.
Falador and Al Kharid are no longer configured as hostile extraction cities.
Quest/journal infrastructure is ready for authored quest briefs.
Classic boss mechanics remain unchanged except for bug/compatibility fixes.
Developers can begin Block 2 quest implementation without needing to refactor the same foundations again.

## 9. Block 2 Handoff

While Block 1 is in development, quest design will proceed one quest at a time. Each Block 2 quest brief will define prerequisites, ordered objectives, NPCs, locations, instances, combat, reused systems, story revelations, rewards/unlocks, journal state, required new assets, shared-world considerations, and major dialogue beats. Final dialogue polish can continue alongside implementation.
