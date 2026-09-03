<!-- Converted from Downloads\FoV_Current_Design_Authority_Dev_Handoff.docx (September 2026) by a plain OpenXML pass; the .docx is canonical. Part of the Block-1 design authority - see docs/LEGACY.md for what it supersedes. -->

FALL OF VARROCK
Current Design Authority & Developer Handoff
Story + Systems Reconciliation • September 2026

This document records the current design authority for Fall of Varrock. It supersedes conflicting legacy concepts in the existing repository and captures major story/system decisions made after the last documentation pass.
Core rule: current story/world decisions are the design authority; existing code is the implementation inventory.

## 1. Purpose and Authority

FoV already contains substantial implementation work, but the repository also preserves several older design directions. This handoff is intended to prevent developers from treating legacy documentation or code behavior as current product direction.
Design authority rule: Current locked story/world decisions override old design documents. Existing code should be evaluated as reusable implementation, not as automatic design authority.

### Status Labels

| STATUS | AREA | CURRENT DIRECTION |
| --- | --- | --- |
| LOCKED | FoV canon/design | Current direction unless intentionally changed in a future design decision. |
| KEEP | Existing implementation | Concept remains useful and should be preserved. |
| ADJUST | Existing implementation | Keep the underlying system but change behavior, presentation, gating, or world assumptions. |
| REMOVE / SUPERSEDE | Legacy design | Do not preserve as current gameplay simply because code/docs exist. |
| OPEN | Unresolved | Architecture is understood, but exact balance, names, numbers, or implementation details remain undecided. |

### Shared-World Development Rule

A player's quest progression cannot require large temporary changes to the normal overworld that conflict with other players' progression.
Prefer existing world systems such as Marches, War Effort, bosses, skilling, and transportation.
Use small quest instances for contained story moments or battles.
Use permanent shared-world content when it makes sense at every progression state.
Use dialogue/journal state when physical world-state simulation is unnecessary.
Do not simulate armies simply to tell the player an army exists.

## 2. Current Story Authority

### The Core Premise

Fall of Varrock is an alternate continuation of OSRS set 12 years after a magical catastrophe known as The Fall. Varrock is lost, Lumbridge is the primary surviving center of Misthalin, and the player begins as a Peasant who gradually earns standing and authority.
The campaign does not end with Varrock becoming safe. It ends with Gielinor becoming capable of fighting for Varrock.
Fallen Varrock remains a permanent endgame ecosystem: repeated assaults, exploration, salvage, captains, Wardens, bosses, war-forging materials, narrative discoveries, and future content.

### True Cause of The Fall — Working Canon

Zemouregal launches the largest undead assault against Varrock and forces Arrav to lead it.
Sliske manipulates the convergence and uses the Elder Horn with buried Senntisten infrastructure.
Lucien arrives with the Stone of Jas and overloads the network rather than be controlled.
The Dragonkin converge because of the Stone.
Necromancy, Elder Artefacts, Senntisten infrastructure, and the battlefield collapse into one catastrophe.
For roughly a minute, ancient Senntisten phases into modern Varrock before the network fractures.
The disaster awakens something beneath Senntisten that none of the major actors understood.
The Fall is not a simple explosion. It is an earthquake, magical fallout event, dimensional collapse, and war disaster that leaves recognizable ruins and long-term instability.

### Arrav, the Adventurer, and the Player

Arrav is publicly blamed for the fall of Varrock, but secretly resisted Zemouregal and helped civilians escape.
Arrav survives and is intended for later redemption.
The original legendary Adventurer disappears during the Fall; no body or definitive equipment remains.
The player is secretly the child of the original Adventurer. This is a late reveal, not a royal-blood claim.
The coalition values the player before learning the lineage; the reveal explains history, not entitlement.

### Lore Exposition Rule — LOCKED

FoV never assumes the player has completed or remembers an OSRS quest. Previous OSRS events are history the character learns through people who lived them.
Use short contextual dialogue and optional reminder branches. The player's hidden parentage makes ignorance of earlier world events natural.

## 3. World State at 12 AF

| STATUS | AREA | CURRENT DIRECTION |
| --- | --- | --- |
| LOCKED | Lumbridge | Safe but strained; the Last Free City of Misthalin and the player's primary starting center. |
| LOCKED | Varrock | Fallen; permanent hostile/endgame zone, never permanently reclaimed in launch story. |
| LOCKED | Falador / Asgarnia | Fortified surviving military power; not a fallen raid city. |
| LOCKED | Al Kharid | Fortified and neutral; not a hostile extraction city. |
| LOCKED | Edgeville | Fallen frontier / PvP-adjacent staging area; strong home for Rogue Knight and future PvP systems. |
| LOCKED | Wilderness | Catastrophic continuation of the First Scar; central to Stone of Jas investigation. |
| LOCKED | Morytania | Post-Drakan and unsettled; preserve current OSRS outcome rather than resurrecting Drakan. |
| LOCKED | Kandarin | Fragmented but alive and relatively prosperous; critical logistics/transport region. |
| LOCKED | Kourend / Tirannwn | Stable/distant or sealed enough to remain outside the immediate collapse. |

## 4. Main Campaign and Regional Architecture

The main story is the Varrock spine. Regional Campaigns are the body of the story. Open-world gameplay is how the player lives between and through those arcs. The world should not feel like a 30-quest hallway.

### Strategic Dependency Model

After the early campaign, the player receives a Reclamation of Varrock objective set. Progress is based on solving four strategic problems rather than completing an arbitrary number of alliances:

| STATUS | AREA | CURRENT DIRECTION |
| --- | --- | --- |
| LOCKED | BREACH | Asgarnia provides the military/artillery capability needed to penetrate major Varrock defenses. |
| LOCKED | SECURE | Morytania/Salve diplomacy prevents an eastern catastrophe while Misthalin commits forces north. |
| LOCKED | UNDERSTAND | Wilderness and Desert arcs reveal the Stone, Lucien, Sliske, the Elder Horn, and the convergence at Senntisten. |
| LOCKED | SUSTAIN | Kandarin + War Effort/logistics create the ability to support sustained operations. |

When these conditions are satisfied, a small Council of Gielinor authorizes sustained assaults. The council is a representative meeting, not a giant overworld army spectacle.

### Regional Campaigns — Current High-Level Authority

| STATUS | AREA | CURRENT DIRECTION |
| --- | --- | --- |
| LOCKED | Asgarnia | Falador is powerful but cannot safely commit before stabilizing Asgarnia. White Knight/Kinshra conflict, Tiffy/Temple Knight intelligence, dwarven cannon capability, and the First Scar lead-in. |
| LOCKED | Wilderness | The First Scar. Two required quests reveal Forinthry's earlier destruction and that Lucien possessed the Stone during the Fall. |
| LOCKED | Desert | Azzanadra / Sliske / Elder Horn investigation. Reveals Sliske's motive and the convergence, but not yet the full mechanism. |
| LOCKED | Kandarin | Restore long-distance trade and transport capacity. Primary contribution to SUSTAIN. |
| LOCKED | Morytania | Post-Drakan Salve crisis. Prevent a false-flag war and secure a non-aggression / mutual-enforcement accord. Primary contribution to SECURE. |

## 5. Rank and Authority

Ranks measure standing and command authority. Quests can introduce opportunities or satisfy eligibility, but gameplay and service earn promotion.

| STATUS | AREA | CURRENT DIRECTION |
| --- | --- | --- |
| LOCKED | Rank ladder | Peasant → Commoner → Squire → Soldier → Knight → Lord → Minister → King. |
| LOCKED | Participation | Any player may join an active war regardless of rank. |
| LOCKED | Command | Starting higher-tier wars is rank-gated. |
| LOCKED | Veteran of Varrock | Earned through meaningful contribution to a major Varrock assault; intended as part of higher-rank eligibility, not a free promotion. |
| ADJUST | High ranks | Coins may remain a sink, but Minister/King should require service, world accomplishments, war participation, and career milestones. |
| REMOVE / SUPERSEDE | Quest auto-promotion | Do not make a quest completion automatically carry the player through major ranks. |

## 6. Offensive War System

FoV only needs offensive war as the core recurring system. The old General Zo / Lumbridge siege loop is legacy.

| STATUS | AREA | CURRENT DIRECTION |
| --- | --- | --- |
| KEEP | March | Small, frequent, public offensive event. Automatic/server-driven. Everyone may join. |
| KEEP | Grand March | Larger scheduled offensive with stronger enemies/commander and better rewards. |
| ADJUST | Lord operation | Player-started smaller offensive. Public participation. Likely coin-funded and supply-free unless testing proves otherwise. |
| KEEP / ADJUST | Campaign | Minister-started major Fallen Varrock operation; consumes coins + Realm Supplies. |
| KEEP / ADJUST | Conquest | King-started largest/deepest operation; temporary battlefield victory, not permanent ownership. |
| REMOVE / SUPERSEDE | Defensive siege | Remove General Zo survival condition, Lumbridge fall states, service shutdowns, and recurring defensive pressure. |

Normal Marches should mostly strike hostile camps, roadblocks, Rogue Knight positions, undead/Zemouregal camps, and frontier threats. Serious penetration into Fallen Varrock is reserved for Campaigns, Conquests, major story events, and high-level exploration.
Simple event loop: Event starts → objective appears → players fight → event ends → contribution determines rewards.

## 7. Fallen Varrock

| STATUS | AREA | CURRENT DIRECTION |
| --- | --- | --- |
| LOCKED | Permanent state | Varrock remains fallen. No permanent player liberation or safe restoration during the main campaign. |
| KEEP | District identity | Districts may remain as locations/content identities for navigation, encounters, loot, and art direction. |
| REMOVE | District pressure | No fortified/contested/weakened/exposed meter, no softening requirement, and no persistent liberation state. |
| LOCKED | Open access | Players can enter parts of Varrock outside wars for exploration, grind, tasks, salvage, captains, and repeatable content. |
| LOCKED | Palace | Deep/high-risk repeatable surface target; not a one-time ownership endpoint. |
| OPEN | PvP pockets | Optional high-risk PvP areas may exist later, but core Varrock war must not require human PK. |

## 8. War Effort and Realm Supplies

Skillers supply the kingdom. Commanders spend those supplies to launch bigger wars.

| STATUS | AREA | CURRENT DIRECTION |
| --- | --- | --- |
| LOCKED | War Effort | Personal lifetime service/contribution. Can be earned through useful war, skilling, Rogue, and kingdom-support activities. |
| LOCKED | Realm Supplies | Shared consumable kingdom stockpile used for high-tier player-commanded operations. |
| KEEP | Resource Contracts | Gather resources for contribution/rewards. |
| KEEP | Supply Depot | Turn useful finished goods into Realm Supplies and personal contribution. |
| KEEP | Supply Drives | Rotating demand windows that encourage different skills/resources. |
| REMOVE / SUPERSEDE | March supply dependency | Scheduled Marches and Grand Marches remain available even if Realm Supplies are low. |
| OPEN | Exact costs | Campaign/Conquest costs and rank thresholds are balance questions for later. |

Avoid multiple public stockpile currencies unless later testing proves they add meaningful gameplay. A single Realm Supplies number is easier to understand.

## 9. Rogue Knights and Future PvP Training

Rogue Knights are optional PvE progression designed to evolve into one of FoV's signature PvP-learning systems. They are no longer mandatory rank/story gates.

| STATUS | AREA | CURRENT DIRECTION |
| --- | --- | --- |
| KEEP | Rogue camps | Increasingly difficult camps and bosses with XP, loot, and PK-inspired behavior. |
| KEEP / ADJUST | Named bosses | Preserve the escalating boss ladder as optional progression. |
| ADJUST | Role | Rogues become practice targets, bounties, open-world enemies, and occasional March targets. |
| REMOVE / SUPERSEDE | The Rogue Problem as required path | No mandatory Squire→Knight shortcut and no required human PKing. |
| FUTURE | PvP Training Arena | Separate instanced/teleported training system that teaches one OSRS PvP skill at a time. |

### PvP Training Vision — Future High Priority

The arena teaches. Rogue Knights test. Real players prove it. A trainer/portal—likely centered around Edgeville—teleports the player into controlled drills. Each lesson teaches one concept, then recommends a Rogue opponent that uses that skill in real combat.
Early topics: eating/survival, protection prayers, offensive prayers, basic gear switching, special attacks.
Intermediate topics: combo eating, freezes/movement, multi-way switching, NH fundamentals.
Advanced topics: tick timing, fakies/spec prediction, PID, advanced NHing.
Veteran PKers should be able to skip lessons and challenge Rogues directly.
Launch priority is the camp/boss progression; the full training arena belongs in the future roadmap.

## 10. Companion System

Players can recruit, train, equip, and develop persistent named soldiers, but may only have one active companion at a time.

| STATUS | AREA | CURRENT DIRECTION |
| --- | --- | --- |
| LOCKED | Active limit | One active companion maximum for every player. |
| LOCKED | Unlock | Knight unlocks the first sworn soldier / companion system. |
| LOCKED | Progression | Companions gain combat XP and can wear real gear; equipment drives much of their specialization. |
| LOCKED | Identity | Persistent named soldiers rather than disposable summons. |
| LOCKED | Use | Compatible open-world combat, Rogue camps, Marches, wars, and FoV-original bosses. |
| ADJUST | Roster | Players may eventually own/recruit multiple companions, but deploy only one at a time. |
| REMOVE | Rank-based active count | Lord/Minister/King do not increase simultaneous companion count. |
| MONETIZATION GUARDRAIL | Donor benefits | Extra roster/recruit/cosmetic options are safer than allowing extra active companions, which would become direct combat power. |

> **Amendment 2026-09-02 (operator decision, in code):** the active limit above is lifted - a player fields their whole roster at once (`CompanionRegistry.ACTIVE_MAX = MAX`, three). The guardrail is price instead of count: the first soldier costs 10M coins, the second 100M, the third 500M (`RecruitMenu.RECRUIT_COSTS`). Rank still sets the roster size (Knight 1 / Lord 2 / Minister+ 3).
>
> **Re-confirmed 2026-09-03.** The September docs *03 Ranks, War & Core Systems* §5 and *06 Development Authority* §3 restate "one active"; the operator was asked and chose the whole-roster rule again (a PR re-locking one-active, #314, was closed unmerged). **Do not flip this back without the operator.** The public check is `CompanionApi.canDeploy` (`docs/core-api.md`).

## 11. War-Forging and Gear Economy

OSRS content gets the player the base gear. FoV endgame lets the player War-Forge it.

| STATUS | AREA | CURRENT DIRECTION |
| --- | --- | --- |
| KEEP | Royal Smith | Primary War-Forging service. |
| KEEP | Commendations | Universal war progression currency earned through contribution. |
| LOCKED | Base gear philosophy | War-Forging should generally require the existing high-end OSRS item rather than bypassing classic PvM. |
| ADJUST | Fallen Varrock rewards | Major wars/exploration supply unique FoV materials and better Commendation opportunities. |
| ADJUST | Recipes | Use understandable combinations of base gear + Commendations + normal materials/coins + limited rare FoV materials. |
| REMOVE / AVOID | Currency clutter | Avoid many overlapping permanent war currencies and abstract salvage meters. |
| OPEN | Stats/sets | Exact bonuses, recipes, and companion access to War-Forged gear are future balance decisions. |

## 12. Quest Unlocks and Quest Journal

Quests explain the world, introduce systems, and unlock major capabilities. They should not block ordinary RuneScape gameplay without a strong reason.

| STATUS | AREA | CURRENT DIRECTION |
| --- | --- | --- |
| KEEP | Custom journal | Track main campaign, regional campaigns, active objectives, and strategic dependencies. |
| KEEP | Objective arrows | Use for the quest/activity the player actively chooses to follow; avoid screen clutter from multiple campaigns. |
| LOCKED | Hidden future chapters | Do not reveal major late-game quest names/revelations before the player discovers them. |
| ADJUST | System introductions | Quests may introduce Resource Contracts, War Effort, Marches, Rogue content, etc. without hard-locking all ordinary use. |
| LOCKED | Command authority | Rank/eligibility gates starting wars; never gate participation in someone else's active war. |
| REMOVE | Arbitrary gating | Avoid quests whose only purpose is to unlock ordinary skilling, normal bosses, basic travel, or open-world activities. |

## 13. Transportation and Regional Unlocks

Story can restore routes, reveal routes, or authorize routes. Story should rarely remove ordinary routes just to create progression.

| STATUS | AREA | CURRENT DIRECTION |
| --- | --- | --- |
| KEEP | Classic mobility | Walking, normal teleports, boats/routes, and familiar transport remain available where the post-Fall world still supports them. |
| LOCKED | Kandarin payoff | Regional campaign restores/improves long-distance trade and transport capacity; Spirit Trees, gliders, Fairy Rings, and convoy systems can be part of that payoff. |
| KEEP | Special access | Hidden Senntisten routes, sealed ruins, military passages, and narrative-only travel may remain quest-gated. |
| REMOVE / AVOID | Blanket shutdown | Do not force players to re-earn basic RuneScape mobility solely to manufacture quest rewards. |
| LOCKED | Varrock | Parts of Fallen Varrock remain physically explorable outside Campaigns/Conquests. |

## 14. Bosses and Repeatable PvM

When players enter classic RuneScape content, they should generally get classic RuneScape gameplay. FoV-original content is where the server innovates.

| STATUS | AREA | CURRENT DIRECTION |
| --- | --- | --- |
| LOCKED | Classic bosses | GWD, Barrows, and other familiar OSRS bosses should remain mechanically faithful and largely untouched. |
| LOCKED | Training value | Preserve RSPS value as a low-risk place to learn/practice OSRS boss mechanics. |
| LOCKED | Classic loot | Do not replace recognizable boss drops with FoV currencies or post-Fall redesigns. |
| KEEP | Custom bosses | War bosses, Rogue bosses, Fallen Varrock bosses, Senntisten encounters, and story bosses can use fully custom mechanics. |
| ADJUST | Companions | Prefer companion-aware design in FoV-original bosses; allow them in classic content only if mechanics/balance safely support it. |

## 15. Extraction / Raid-Zone System

The extraction-style PvP system remains useful, but its old map assumptions conflict with the new world state. Treat 'raid city' as a legacy label; future locations can be hostile zones, forts, ruins, frontier areas, or occupied camps.

| STATUS | AREA | CURRENT DIRECTION |
| --- | --- | --- |
| KEEP | Extraction loop | Enter dangerous area → gather valuable loot → survive NPC/player threats → extract. |
| REMOVE / REPLACE | Falador raid city | Falador is a surviving Asgarnian power and cannot remain configured as a hostile extraction city. |
| REMOVE / REPLACE | Al Kharid raid city | Al Kharid is fortified/neutral and cannot remain configured as a hostile extraction city. |
| ADJUST | Location strategy | Prefer Wilderness/frontier/rogue-held/lore-supported hostile areas. Choose exact maps after world-state cleanup. |
| LOCKED | Optional PvP | Core main-story and Varrock war progression must not require human-player PKing. |

## 16. Legacy Contradiction Sweep

The following concepts should be explicitly tagged in repository documentation/code comments during implementation cleanup so they are not accidentally preserved.

| STATUS | AREA | CURRENT DIRECTION |
| --- | --- | --- |
| REMOVE / SUPERSEDE | Permanent reclamation of Varrock | No district-by-district permanent capture, no safe restored city, no palace ownership endpoint. |
| REMOVE | District pressure / liberation | Keep districts only as location identity. |
| REMOVE / REUSE ENGINE | General Zo defensive siege | Reuse AI/pathing/contribution technology where useful; remove the defensive product loop. |
| ADJUST | March target pool | Ordinary Marches mostly strike hostile camps/frontiers rather than repeatedly entering Varrock. |
| FIX | War Effort contradiction | War Effort is personal service; Realm Supplies are shared consumables; Marches are free. |
| REMOVE AS REQUIRED | The Rogue Problem | Preserve Rogue ecosystem, remove mandatory story/rank shortcut. |
| REMOVE / REPLACE | Falador + Al Kharid raid configs | Extraction system survives; locations do not. |
| ADJUST | War-Forging district dependency | Remove liberated-district shops/pressure gating; preserve forge/economy scaffold. |
| REMOVE | Multiple active companions by rank | One active companion maximum. |
| REDUCE | Quest hard-gating | Introduce systems without blocking ordinary RuneScape gameplay. |
| LOCK CLASSIC | Classic boss redesign | Do not alter familiar OSRS boss mechanics merely to fit the post-Fall setting. |

## 17. Developer Cleanup Priorities

When implementation begins, the first development pass should reconcile existing systems before adding major new content.
Tag or remove legacy Varrock district-pressure and liberation logic.
Disable/remove recurring defensive Lumbridge/General Zo war behavior while preserving reusable battle AI infrastructure.
Update March target architecture to support hostile camps/frontiers and Rogue Knight locations.
Separate personal War Effort from shared Realm Supplies and remove March supply dependency.
Decouple Rogue Knight progression from mandatory rank/story progression.
Enforce one active companion maximum and preserve future multi-roster capability.
Replace Falador and Al Kharid extraction-zone assumptions; leave exact replacement maps open until world-state selection.
Audit War-Forging dependencies that reference liberated districts or obsolete currencies.
Audit quest journal/unlock code for arbitrary system gating.
Keep classic boss mechanics untouched unless there is a technical compatibility bug.

## 18. Story Documentation Backlog

Several important story decisions were made after the previous documentation set. These should be propagated into the Master Design Bible, Main Campaign, Regional Campaigns, World/Faction Bible, Systems document, and change log before detailed quest scripting begins.
True cause of The Fall: Sliske + Elder Horn, Lucien + Stone of Jas, Zemouregal/Arrav, Dragonkin, Senntisten network, and the unknown entity beneath Senntisten.
Day Varrock Fell timeline and the disappearance of Arrav / the original Adventurer.
Player origin: child of the original Adventurer; late reveal; not royal blood; coalition values the player first.
Lore exposition rule: prior OSRS events are history the player learns, not assumed quest-memory.
World state at 12 AF, especially Falador/Asgarnia, Al Kharid, Morytania post-Drakan, Kandarin, and Edgeville.
Regional dependency architecture: BREACH / SECURE / UNDERSTAND / SUSTAIN.
Current high-level Asgarnia, Wilderness, Desert, Kandarin, and Morytania regional arcs.
Council of Gielinor as a small representative authorization meeting rather than an overworld army spectacle.
Varrock remains a permanent endgame war zone; the first major assault is a milestone, not permanent capture.
King becomes a long-term endgame achievement rather than an automatic final-story reward.
Veteran of Varrock as meaningful major-assault participation and higher-rank eligibility.
Offensive-only core war direction; ordinary Marches use hostile camps/frontiers.
One-active-companion system and future named soldier progression.
Rogue Knight system as optional progression plus future Edgeville PvP Training Arena.
Classic OSRS boss fidelity as a permanent design rule.

## 19. Next Design Phase

With the major systems reconciled, the next design task is to finish the main story from the first major Varrock assault through the endgame transition.
Define what the first major Varrock assault reveals and changes without permanently capturing territory.
Bring Arrav back into the narrative and complete his redemption arc.
Resolve Zemouregal's role after the first major assault.
Deliver the late player-parentage reveal without making lineage the source of authority.
Move the narrative beneath Fallen Varrock into Senntisten and reveal the true mechanism of The Fall.
Define the thing beneath Senntisten after a dedicated lore/research pass.
End the launch narrative by opening the long-term Varrock/Senntisten endgame rather than closing it.
Keep King as a long-term career achievement that can extend beyond main-story completion.
