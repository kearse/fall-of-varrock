# FIRST RECLAMATION

**Main Story Quest 4 · Primary NPC: General Zo · Location: Varrock southern road / the stone circle**
**Development cost: Low–Medium** (dialogue + quest state + one event-started Grand March on an existing
target + a light-custom shared outpost: three knight spawns, one renamed Quartermaster, one existing
banner object, one route-locked portal row)
**Status: BUILT 2026-09-12** (branch `claude/first-reclamation-quest-96e211`)

**Purpose:** the first meaningful offensive victory of the main story. *The Last Free City* proved
Lumbridge can survive, *First March* that it can strike back, *The North* what was lost. First
Reclamation proves **the kingdom can take something back** — and then shows the limit: "We can't
take Varrock." / "Not alone." That sentence is the opening premise of *A Kingdom Alone*.

## Core implementation decision

A framework quest (`quests/story/FirstReclamation.kt`, key `first_reclamation`) layered over the
existing war. The battle is the existing `varrock_outskirts` march target launched as a public
**Grand March** through `WarEvents.startPublicOperation` — free, sponsor-less, open to every player
(the story's *Reclamation Column*). Victory is read from `WarHooks.onOperationEnded`, never by
polling the ledger. The outpost — **the Southern Watch** — is a **shared-world** post on the real
stone circle south of Varrock's gate; what the player earns personally is the travel route and the
milestone flag. The surrounding road stays the repeatable march target: the enemy keeps contesting
it, the post is where the realm meets them now. No per-player world state, no territory meter, no
map edit, no new NPC/enemy/model.

## Integration audit — every beat mapped to the build

| Beat | Existing NPC / place (unchanged) | Existing system | Code seam |
|---|---|---|---|
| Brief: "looking at something everyone says is lost… and deciding it isn't" | **General Zo** 3220,3210 (castle hub) | `NpcTalk` — Zo migrated to `bindTalk` + a default branch; quests claim him on their own steps | `FirstReclamation.talk(ZO, "brief")`; the eligible-player opener also begins the quest on talk (`NpcTalk.register(ZO, PRIORITY_QUEST - 1)`) |
| Walk north, survey the southern road | The outskirts battlefield's lower end, road x≈3226-3232 z≈3336-3352 | `Objective.ReachArea` (the 3-tick quest poll) | step `scout_south` — `onLeave` prints the arrival + south-road lines |
| Inspect the stone circle | The real ring 3222-3233 × 3364-3375 ("Stone circle wall" locs, centre stone 17454 at 3227,3369) | `ReachArea` | step `scout_circle` |
| Look toward Fallen Varrock | The road at the south gate 3206-3218 × 3377-3384 — before the city's own lines (z3384+) | `ReachArea` | step `scout_varrock` — plants "far more than Lumbridge possesses" |
| Recon report → "Now we reclaim." | General Zo | `NpcTalk` | `report` → `satisfy` → `ready`; the same script continues into the launch prompt |
| The Reclamation Column | `MarchTargets.VARROCK_OUTSKIRTS` (rally 3213,3376; marauders lvl 34 / Black Knights 68 / risen dead 55; 16 knights; quota 20) | `WarEvents.startPublicOperation(world, WarType.GRAND_MARCH, "varrock_outskirts")` + `Announce.broadcast` | `FirstReclamation.launch` → `advanceTo(battle)`; a column already on that ground is joined instead; other live ops → "the reclamation waits" |
| Fight beside the column; the battle must be **won** with ≥1% share | the live op; `::march` rally (PvP confirm from `PvpZones`) | `WarHooks.onOperationEnded` → `WarResult.participated(username, 1)` | `FirstReclamation.onSouthernRoadResult`: won+share → `establish`; won without share → `retry` (+`missed`); lost → `retry`. Any tier on that key counts (Zo's Grand March or the Knight-Captain's own march) |
| Defeat: "We go again." — immediate retry once the ground clears | General Zo | same launch path | Zo branch shared by `battle`/`retry`; no cooldown, no reset |
| Establish the Southern Watch: **plant the standard** | The Castle Wars **Saradomin Standard** (loc 4902, option **Capture** — a banner on its stand) spawned at 3227,3372, north of the centre stone | `onObjOption` (bound defensively from the cache actions) | `SouthernWatchPlugin.bindStandard` → `SouthernWatch.onCapture` hook → `FirstReclamation.raiseStandard` → `satisfy(establish)` |
| Debrief at Zo: "About fifty yards and several thousand corpses" … "Not alone." | General Zo (lowest-risk option in the design — Zo is not moved) | `NpcTalk` | `debrief` → `satisfy` → complete |
| Rewards | — | `Reward.WarEffort(50)`, `Reward.Custom` → `SouthernWatch.unlock` (route `southern_watch` + flag `southern_watch`) | `completionRewards` |
| Fast travel | Portal War tab row **Southern Watch** (lands 3225,3371 inside the ring; `DangerTag.HOSTILE`, route-locked); General Zo's menu gains **"Send me to the Southern Watch."**; `::southernwatch` | `TeleportRegistry` + `TransportRoutes.register` | `TeleportRegistry` (appended at the end of WAR — mirror `LofTeleportsData`), `GeneralZoPlugin.dialog`, `SouthernWatchPlugin` |
| Field Quartermaster | `npc.quartermaster` renamed "Field Quartermaster" at spawn (3230,3369) | the global hand-in bind (`WarlordsArmouryPlugin`) + `SupplyDepot.POSTS` | `SouthernWatchPlugin.spawnQuartermaster`; `SupplyDepot.POSTS` gains the tile; the dialogue title follows `SouthernWatch.isAtPost` |
| Garrison | three **Knights of Lumbridge** (`npc.knight_of_saradomin`, `Campaigns.ALLIED_DEF`) at 3224,3372 / 3231,3372 / 3227,3366 | `GoblinCampPlugin`'s knight lifecycle (owned respawn, presence-gated, explicit skirmish) | `SouthernWatchPlugin.tick` — knights strike the outskirts' living enemies within 10 tiles (the lines stage straight through the ring during a march) |

**Rejected options.** A per-player reconstructed map / permanent removal of the outskirts enemies
(the shared-world rule); a new banner model (the Castle Wars standard already carries a fitting
*Capture* verb); a custom inventory item to carry across the quest; moving Zo to the circle for the
debrief (risk to his other duties — the design's lowest-risk fallback is used); a camera pan (optional
in the design, skipped); a private battle instance or quest army (the public war IS the point).

## PvP-rollback cleanup (shipped alongside)

`MarchTargets.statusLines` and `MarchPlugin.muster` no longer assume `VARROCK_OUTSKIRTS = wilderness`:
`MarchTargets.isPvpGround(t)` asks `PvpZones.isWilderness(t.op.objectiveTile)` — the same live
answer `WarEvents.join` already uses for the `::march` confirm — so the muster call, the `::marches`
board and the rally warning stay correct when the wilderness boundary moves. `::marches` also shows
an event-started op on a target as UNDER ATTACK NOW (`CampaignRegistry.isAttacking`).

## Dialogue (shipped)

**General Zo — brief (brief → scout_south).** "You went to Edgeville." / *Player:* "I did." / "And?" /
"I think I understand. Varrock didn't just fall. The kingdom around it fell apart." / "Good. Then
you're ready for the next lesson." / "Which is?" / "Looking at something everyone says is lost… and
deciding it isn't." / "What are we taking back?" / "Not Varrock." / "I wasn't getting my hopes up." /
"Good. There is an old approach south of the city — the stone circle. We've fought there before.
Marches reach it now and again. But every time the fighting ends, we leave." / "And the enemy comes
back." / "Exactly. A march wins ground. This time we're going to use it." / "So this is another march?"
/ "No. A march hits the enemy and returns. This is a reclamation. We clear the southern approach…
then we establish a permanent forward post behind the line." / "Permanent?" / "As permanent as
anything gets this close to Varrock." / "I don't send soldiers to ground their commander hasn't
seen." / "I'm not the commander." / "Today you're the person I'm sending. Try to enjoy the
promotion." / "Go north — on foot. Walk the southern road, look the stone circle over, and see the
city from beyond it. Then come back and tell me what you saw."

**Survey narration (chat, on reaching each area).** South: "The southern road to Varrock stretches
ahead." "The city's walls rise in the distance." "This is as far north as Lumbridge regularly projects
military force." "The road behind you leads back toward Lumbridge." "Holding this ground would give
soldiers somewhere to regroup before approaching Varrock." · Circle: "The stone circle sits on
defensible ground overlooking the southern road." "It could serve as a forward staging position." ·
Gate: "Fallen Varrock lies ahead." "Even from here, the enemy presence is obvious." "Taking the city
itself will require far more than Lumbridge currently possesses."

**General Zo — report (report → ready → launch).** "Well?" / "The position is usable. The city
isn't." / "Good assessment." / "That's it?" / "You looked at Fallen Varrock and didn't suggest charging
the gate. That already puts you ahead of several officers I've known." / "What do we need?" /
"Soldiers. Supplies. And enough people willing to keep fighting after they see what's waiting north of
that circle." / "So now we attack?" / "Now we reclaim." / "The Reclamation Column musters on your word.
Every soldier of the realm will hear the call and may march with it." → **We're ready. Send the
column.** / Not yet — I need to prepare. On launch, realm-wide: **"General Zo has ordered a reclamation
push toward Fallen Varrock! The Knights of Lumbridge march on the southern road. All soldiers of the
realm may join the attack — ::march."**

**General Zo — driven back (retry).** "We were pushed off the road." / "So that's it?" / "No." / "We
just lost." / "Yes. And now we know what didn't work." / "We go again." *(Cleared without you:)* "The
road was cleared — without you." / "A reclamation isn't a victory you watch from Lumbridge. I need you
on that road, not behind it." / "We go again."

**Victory.** Realm-wide: **"The enemy line breaks. The southern approach to Varrock is clear."** —
to the player: "The southern road is yours. Return to the stone circle and raise the standard."

**The standard (establish).** *Capture* → "You raise Lumbridge's standard over the southern approach."
/ "For the first time in twelve years, the kingdom has established a forward position in the shadow
of Fallen Varrock."

**General Zo — debrief (debrief → DONE).** "We took it." / "We took the position." / "What's the
difference?" / "About fifty yards and several thousand corpses." / "Right." / "The roads around it are
still hostile. Enemy patrols will return. The dead certainly will. But now when they do… we have
somewhere to meet them." / "The Southern Watch is holding." / "For now." / "For now is how every kingdom
starts." / "We're closer to Varrock." / "Yes." / "So what's next?" / "…" / "General?" / "You saw the
city." / "I did." / "How many soldiers do you think are in there?" / "Too many." / "How many do you think
we have?" / "…We can't take Varrock." / "Not alone." / "We can hold Lumbridge. We can clear roads. We
can establish posts. We can even bloody the enemy at Varrock's doorstep." / "But storming those walls?
Misthalin doesn't have enough soldiers. Not anymore." / **QUEST COMPLETE** / "So we find more." /
"Exactly." / "The Southern Watch is yours to use now. Ask me, or the portal, and you'll stand at the
circle."

## Quest journal (server objective lines = client step rows)

| Step (id) | Journal |
|---|---|
| START (`brief`) | General Zo believes Lumbridge is ready to reclaim its first northern position. Report to him. |
| SCOUT / SOUTH (`scout_south`) | Travel north to the stone circle south of Fallen Varrock and survey the southern road. |
| CIRCLE (`scout_circle`) | Inspect the stone circle as a possible forward position. |
| VARROCK (`scout_varrock`) | Look north toward Fallen Varrock from the road beyond the circle. |
| REPORT (`report`) | Report your findings to General Zo. *(nudge: The stone circle could support a forward position, but the southern approach must first be cleared.)* |
| READY (`ready`) | Tell General Zo when you are ready to begin the reclamation. |
| BATTLE (`battle`) | Fight beside the reclamation column and clear the Varrock southern road. |
| RETRY (`retry`) | The reclamation failed. Speak with General Zo and try again. |
| ESTABLISH (`establish`) | The road is clear. Return to the stone circle and establish the Southern Watch — raise the standard at its heart. |
| DEBRIEF (`debrief`) | Report the successful reclamation to General Zo. |
| DONE | Lumbridge has established the Southern Watch near Fallen Varrock. We can take ground back, but General Zo believes Misthalin cannot reclaim the city alone. |

Client: `LofQuest.FIRST_RECLAMATION` (generic journal varp **4687**, rows = the 1-based server step
index; the `retry` step renders as the battle row with a "driven back — see General Zo" suffix and
its arrow on Zo). Native quest tab: the Romeo & Juliet row relabelled (`QuestTablePatch.PLAN`,
varp 144, complete 100) — **operator: run the Quest cache relabel workflow → `relabel`, then `hide`.**

## Rewards

**50 War Effort** · **the Southern Watch**: fast travel (portal War tab, General Zo's "Send me to the
Southern Watch.", `::southernwatch`), the Field Quartermaster hand-in post, the garrison, and Varrock
march staging on the doorstep · the normal spoils of the won Grand March (paid by the war, not the
quest) · the next main quest, *A Kingdom Alone*. No equipment reward — the outpost is the reward.
Quest Points are not modelled in this build (no QP system exists); the design's "2 Quest Points" is
recorded here for when one does.

## Development section

| Field | First Reclamation |
|---|---|
| Start NPC | General Zo, 3220,3210 (castle hub) — auto-begins after The North (`autoBegin`, login / poll), or on talk |
| NPCs Used | General Zo · Knights of Lumbridge (the column + the garrison) · marauders / Black Knights / risen dead (`varrock_outskirts` lines) · Quartermaster (renamed Field Quartermaster) — all existing |
| Locations | Varrock southern road · the stone circle (the real ring) · the south-gate approach — unchanged |
| Gameplay Used | Public marches / Grand March (`WarEvents`) · participation ledger + `WarHooks` · war payout · teleport portal + `TransportRoutes` · Supply Depot · `PvpZones` |
| Dialogue | Zo ×5 beats (brief, report + launch prompt, retry, debrief, the menu's Southern Watch line) · survey narration · standard lines · two realm-wide announcements |
| Quest State | `QuestStates` blob (`first_reclamation`: step id + `missed` counter) · flags `quest.first_reclamation.done`, `southern_watch`, `route.southern_watch` |
| Journal | table above; varp 4687; native row Romeo & Juliet (varp 144) |
| System Hooks | `NpcTalk` (Zo) · quest poll (`ReachArea`) · `WarEvents.startPublicOperation` · `WarHooks.onOperationEnded` · `onObjOption` (the standard) · `TeleportRegistry` / `TransportRoutes` · `SupplyDepot.POSTS` |
| Temporary Content | None per player. The outpost's spawns (standard, 3 knights, Field Quartermaster) are permanent shared-world content; the knights are presence-gated |
| New NPCs | NONE (renames at spawn only) |
| New Maps | NONE |
| World Changes | NONE permanent to the map. One dynamic banner object + spawns at the stone circle for everyone |
| New Mechanics | NONE |
| Development Cost | **Low–Medium** (Script + Light Custom) |

## Operator steps after merge

1. **Quest cache relabel** (native quest tab): Actions → *Quest cache relabel* → `relabel`, then `hide`
   — adds the "First Reclamation" row (Romeo & Juliet relabelled). Until then the stock tab lacks
   the row; the client Quest Journal and `::quests` work regardless.
2. **Client:** the `ship-client` workflow runs on the push (client/ changed): the journal entry, the
   generic-varp support and the portal mirror row. Old clients show no journal entry and no "Southern
   Watch" portal row until they update (the server row exists; `::southernwatch` and Zo work regardless).
3. **Smoke test** (needs The North complete, or `::questdebug begin first_reclamation`): Zo brief →
   walk north: south-road lines at ~3228,3344 → step into the ring → the gate approach → Zo report →
   "We're ready" → realm announcement + Grand March on the outskirts → `::march`, fight (≥1% share) →
   on victory "The enemy line breaks…" → Capture the standard at 3227,3372 → Zo debrief → complete;
   then the portal's Southern Watch row, Zo's "Send me to the Southern Watch." and `::southernwatch`
   land inside the ring, the Field Quartermaster takes a hand-in, and the three knights stand.
   Lose the march (or sit it out) → RETRY text → Zo relaunches with no wait. `::questdebug dump`
   shows the step; `::questdebug varps` before/after must leave the legacy chains untouched.
