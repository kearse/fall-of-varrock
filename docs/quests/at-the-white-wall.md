# AT THE WHITE WALL

**Asgarnia — BREACH, Quest 1 · Regional opener / combat / reconnaissance · Location: Falador · Start: after A Kingdom Alone**
**Development cost: Script / Light Custom** (dialogue + journal + a shared-world scripted skirmish at the north gate + three area triggers + existing objects as checkpoint dressing)
**Status: BUILT 2026-09-12** (branch `claude/asgarnia-regional-campaign-2642ba`)

**Primary purpose:** answer one question — *why doesn't Falador just send its army to Varrock?* — and open
the Asgarnia campaign. By the end the player knows Falador survived, is militarily powerful, is not idle,
and cannot spare a soldier: the Kinshra only need to keep Falador *busy*; Burthorpe eats the Imperial
Guard; the guns are wearing out faster than they can be replaced. Sir Tiffy suspects there is more to
the Fall than the public story.

## Core implementation decision

**A framework quest (`QuestDefinition`) over unchanged Falador.** Sir Amik Varze and Sir Tiffy Cashien
are the stock, presence-gated world spawns where OSRS puts them (castle top floor 2960,3336,2; park bench
2997,3373). White Knights and Black Knights are the stock npcs. The Falador north gate is the gate as it
is in the cache. The only things added are a **White Knight checkpoint** on the road outside the north
gate (four stock White Knights on posts, a stretcher case + nurse, crates and spiky barricades placed as
dynamic objects — no cache edit) and a **rolling Kinshra raid** on it that runs only while a player on
the fight step stands at the gate. No instance, no new NPC/enemy/map/mechanic.

**Why the NORTH gate.** It is the approach that faces the Kinshra (the Black Knights' Fortress is north,
past Ice Mountain) and it is quiet ground: no shop hub, no bank, no other content spawns there. Since
PR #350 the PvP wilderness is the OSRS surface box (north of the Edgeville ditch) plus the Varrock
pocket, so the whole Falador approach is safe ground — the story's PvE skirmish and its observation
points need no carve-out (the earlier custom wild started at z3401; a pocket for it was drafted and
dropped in the merge). Rogue Knights roam the mainland under their own rules and may cross the road,
as anywhere. Kinshra raiders stage in the field beyond the first fence and push south to the gate.

## Integration audit — every beat mapped to the build

| Beat | Existing NPC / place (unchanged) | Existing system | Code seam |
|---|---|---|---|
| "Travel to Asgarnia" | Falador north gate (opening x2964-2967, z3392-3394) | Any transport into Falador | `AtTheWhiteWall` step `travel` — `Objective.ReachArea(APPROACH)` |
| The checkpoint: "Halt." | Stock **White Knight** (1798) ×4 on posts across the gate road | `NpcTalk` quest-priority branch on the shared id, **proximity-gated** (≤12 tiles of the checkpoint) so Falador's castle knights keep their own lines | `WhiteWallCheckpoint` spawns/maintains the posts (presence-gated like `GoblinCampPlugin`); `AtTheWhiteWall.checkpointHalt` |
| The attack — defeat 5 Black Knights | Stock **Black Knight** (516) renamed "Kinshra raider", loot-less, stats +1 notch; stages north of the fence (z3409-3413) | Stock NPC combat; `Pawn.attack` only (never the engine aggro path) | step `defend` — `Objective.KillNpcs(5, filter = raider tag)`; `WhiteWallCheckpoint.tick` streams raiders (6 alive, +2 per tick) while a DEFEND-step player is within 24 tiles; withdraws when none |
| Kill credit while knights out-damage the player | — | `onAnyNpcDeath` (additive) + `damageMap.playerDamage()` | `AtTheWhiteWallPlugin`: every DEFEND-step player who drew blood is credited (`AtTheWhiteWall.creditRaiderKill`), except the top-damage killer the framework hook already counted |
| "You should've led with the sword." | The checkpoint knight | `NpcTalk` (AMIK step, near the checkpoint) + queued on DEFEND clear | `AtTheWhiteWall.knightAfterFight` |
| Sir Amik: "I have an army. I do not have an army to spare." + the three pressures + Tiffy | **Sir Amik Varze** 4771 @ 2960,3336,2 (world spawn) — reached by the west tower's white-stone staircases 24072/24074 (+24067/24068/24075), which nothing bound before 2026-09-12: `LadderPlugin.climbWhiteStairs` (an identical hunk is carried by The Guns of Asgarnia and A Matter of Trolls) snaps the climb to the nearest walkable tile of the next plane | `bindTalk` + `NpcTalk` | step `amik` — `talk(AMIK, "amik")` → `amikMeeting` |
| Sir Tiffy: the Fallen Varrock question, the breadcrumb, "go look at the ground" | **Sir Tiffy Cashien** 4687 @ 2997,3373 (world spawn) | `bindTalk` + `NpcTalk`, `options` | step `tiffy` — `talk(TIFFY, "tiffy")` → `tiffyMeeting` |
| Inspect the front (3 observations, any order) | The checkpoint (2957-2973 × 3395-3400) · the gate road inside the wall (2961-2970 × 3383-3391) · the field beyond the fence (2956-2974 × 3406-3414) | `Objective.Predicate` on the framework poll; per-observation counters in the quest state | step `front` — `AtTheWhiteWall.observe` narrates each once, clears when all three are seen |
| Report + handoff to Burthorpe | Sir Amik | — | step `report` — `amikReport` → quest complete (+25 War Effort, 1 QP) |
| Native quest tab row | Recruitment Drive (dbrow 118, quest id 86, varp 657, complete 2) — the OSRS quest whose start NPC is Sir Amik | `QuestTablePatch.PLAN` + `QuestDefinition.nativeTabVarp` | `QuestEngine.publish` |
| Client journal | `LofQuest.AT_THE_WHITE_WALL` (generic varp 4693), Black Knights highlighted on the fight step | `lofquests` | chain slot 14 |

**Rejected cheaper-looking options.** A quest instance of the gate (kill credit in the shared world is
reliable through damage share, and the design prefers the shared world). A one-shot wave of exactly 5
raiders (the White Knights would finish them before a slow player got a swing; the rolling stream makes
the objective un-strandable). A named checkpoint commander (the design forbids one; the proximity gate on
the shared White Knight id does the job). Moving Amik to a command room (his stock spawn is fine; the arrow
points at the top floor). Cache loc edits for the dressing (dynamic objects after an idempotent region
force-load are enough for six crates and four barricades).

## The checkpoint (light custom, all existing assets)

- **Posts:** White Knights at (2963,3397) (2967,3397) (2961,3399) (2969,3399), facing north; the road
  x2964-2966 stays open. Stats 90 hp / 70 att / 60 str / 70 def, 4-tick — a knight beats a raider
  one-on-one but slowly, so the player's blows decide the fight. Respawn ~14 s after death; stand down
  when no player is within 40 tiles.
- **Dressing (once, then left):** crates (obj 354) at 2960-2961,3396 · 2970-2971,3396 · 2962,3389 ·
  2969,3389; spiky barricades (obj 4421) at 2959-2960,3402 · 2970-2971,3402; a Wounded soldier (6826)
  at 2959,3397 and Nurse Sarah (1152) at 2960,3398, each with a placeholder line. Regions 11829/11828
  are force-loaded first so a chunk built later cannot drop them.
- **The raid:** raiders spawn on seven staging tiles at z3409-3413, walk through the fence gaps, and
  target DEFEND-step players within 14 tiles (≤3 per player), then the knights, else push to
  (2965,3400). A raider dragged more than 34 tiles from the checkpoint leaves the raid. Shouts every
  ~7 s: knights "Hold the gate!" / "Kinshra!" / "Don't let them through!", raiders "Break the line!" /
  "Keep them pinned!" (the latter is the quest's tell — the player only understands it later).
- **Safety:** raiders carry `NO_LOOT_ATTR` (a rolling stream of Black Knights next to a bank is not a
  drop faucet); nothing here touches `Npc.aggroCheck`; the tick is wrapped in `runCatching`.

## Dialogue (shipped — the design's lines, verbatim where it gave them)

**White Knight — checkpoint (CHECKPOINT → DEFEND).** "Halt." / *Player:* "I'm here from Lumbridge." /
"Business?" / *options:* "I need to speak with Sir Amik." · "I'm here about Varrock." · "Just visiting."
(all three roads lead to Varrock) / "Varrock?" *(he looks you over)* "You've come a long way to ask for
soldiers we don't have." / *Player:* "Falador looks like it has plenty." / "Then you've been here thirty
seconds." → **DEFEND** / "Movement!" "Kinshra! Hold the gate!"

**White Knight — after the raid (AMIK).** "You said you wanted to speak with Sir Amik?" / "Yes." / "You
should've led with the sword." / "I did eventually." / "Go on. Castle." / "He'll want to hear why someone
from Lumbridge is fighting Kinshra outside his walls."

**Sir Amik (AMIK → TIFFY).** "Lumbridge." … "I meant the crest on the dispatch." … "Though I've already
heard about the gate. Apparently you made yourself useful." / *Player:* "Lumbridge is preparing for
Varrock … The Southern Watch … And General Zo thinks Falador has one." / "He's right." / "So you'll
help?" / "No." / **"I have an army." "I do not have an army to spare."** / the Kinshra strategy ("They
don't need Falador. They need Falador busy.") / the three pressures (the Kinshra front; Burthorpe and the
trolls; "twelve years wearing out the weapons … our dwarves still know how to build them … what we no
longer have is the industrial supply") / "Before I send you anywhere, there's someone else who wants a
word." "Tiffy." → **TIFFY** / "You'll understand why I'm hesitating after you meet him."

**Sir Tiffy (TIFFY → FRONT).** "Ah." / "Sir Tiffy?" / "Usually." … "You've been near Varrock?" / "Closer
than I'd like." / "Quite. Did you notice anything unusual?" / *options:* undead · ruins · "Define
unusual." / "Stone where stone ought not be. Old markings. Magic behaving strangely. Anything that looked
older than the city around it." / "Why?" / "Oh, no reason." / "That sounded exactly like there was a
reason." / "You remind me of someone." / "Sergeant Damien said something like that." / "Did he?" … "Someone
who asked too many questions." / "That's not an answer." / "Precisely." / "Before you start solving Sir
Amik's problems … I'd suggest you understand the first one." → **FRONT** / "Go look at the front. Not
the soldiers. The ground." / "What am I looking for?" / "Why nobody moves."

**The front (FRONT, chat narration on first entry to each area).** *Line:* "White Knights hold a
fortified position facing the Kinshra approach." "The soldiers appear prepared for another attack." ·
*Supply:* "Food, armour and weapons are continually being moved toward the front." "Maintaining the line
consumes resources even when no major battle is taking place." · *Kinshra:* "The Kinshra position does
not appear prepared to assault Falador's walls directly." "They only need enough strength here to
prevent the White Knights from leaving." → all three: "The Kinshra do not need to conquer Falador. By
keeping the White Knights occupied, they prevent Asgarnia from helping reclaim Varrock."

**Sir Amik (REPORT → DONE).** "What did you learn?" / "They're not trying to beat you." / "No?" / "They're
making sure you can't leave." → **QUEST COMPLETE** / "Exactly." / "So breaking the front means more than
defending Falador." / "It means Asgarnia becomes useful again." / "Then let's break it." / "With what
soldiers?" / "The ones outside?" / "Those are the soldiers holding it." / "Right." / "We begin somewhere
else. Burthorpe." / "The Imperial Guard has spent years watching the mountain passes. If the northern
frontier stabilises… those soldiers can come south." / "And then we hit the Kinshra?" / "Then we'll have
enough men to start thinking about it."

Everyday lines (no quest beat live): the checkpoint knights ("Halt. The north road is closed while the
Kinshra press us."), the castle knights ("Falador holds, citizen…"), Amik and Tiffy per quest state.

## Quest journal (server objective lines = client step rows)

| State | Journal |
|---|---|
| travel | Falador may possess the military capability needed to breach Fallen Varrock. Travel to Asgarnia. |
| checkpoint | Speak with the White Knights guarding the Falador approach. |
| defend | Help the White Knights repel the Kinshra attack. [X/5] |
| amik | Speak with Sir Amik Varze in Falador. |
| tiffy | Sir Amik says Sir Tiffy Cashien wants to speak with me. Find him in Falador. |
| front | Inspect the White Knight-Kinshra front and determine why Falador cannot commit troops east. |
| report | Report what I learned to Sir Amik. |
| DONE | Falador has an army, but no army to spare. The Kinshra keep the White Knights pinned while the Imperial Guard is tied down in the north. Sir Amik has directed me toward Burthorpe. |

`::whitewall` prints the objective and opens the Quest Journal on the quest.

## Rewards

1 Quest Point · 25 War Effort · the Asgarnia campaign (BREACH) formally begun · **A Matter of Trolls**
unlocked (it gates on `at_the_white_wall`) · the Asgarnian Front (the checkpoint) added to the world.
No equipment, no White Knight armour, no access gate to Falador — the city stays ordinary open-world
content.

## Development section

| Field | At the White Wall |
|---|---|
| Start NPC | None — auto-begins when A Kingdom Alone completes; the first beat is the stock White Knights at the north-gate checkpoint |
| NPCs Used | White Knight (1798) · Black Knight (516) · Sir Amik Varze (4771, stock spawn) · Sir Tiffy Cashien (4687, stock spawn) · Wounded soldier (6826) + Nurse Sarah (1152) as dressing — all existing |
| Locations | Falador north gate + the road inside/outside it · the White Knights' Castle top floor · Falador Park — unchanged |
| Gameplay Used | Stock NPC combat · presence-gated garrison (goblin-camp pattern) · framework quest engine (areas, kills, predicate, talk) · War Effort · quest points · native quest tab · client journal |
| Dialogue | Checkpoint knight ×3 beats · Amik ×2 (+ idle) · Tiffy ×1 (+ idle) · castle-knight, nurse, wounded-soldier lines |
| Quest State | `QuestStates` blob, key `at_the_white_wall`; step ids travel/checkpoint/defend/amik/tiffy/front/report; counters `kills`, `obs_line`, `obs_supply`, `obs_kinshra`; completion flag `quest.at_the_white_wall.done` |
| Journal | table above; client `LofQuest.AT_THE_WHITE_WALL` (varp 4693, chain slot 14); native tab row = Recruitment Drive relabelled (varp 657) |
| System Hooks | `QuestEngine` poll (travel, front) · `onAnyNpcDeath` (defend, damage share) · `NpcTalk` (checkpoint, amik, tiffy, report) · `QuestTablePatch`/`QuestJournal` publish |
| Temporary Content | The Kinshra raid (only while a DEFEND-step player stands at the gate); the garrison stands down when the road is empty |
| New NPCs | NONE (stock ids; one runtime rename "Kinshra raider") |
| New Maps | NONE |
| World Changes | Light: checkpoint dressing (6 crates, 4 barricades, 2 npcs) placed at runtime — no cache edit, no zoning change |
| New Mechanics | NONE |
| Development Cost | **Script / Light Custom** |

## Cross-PR contract (five quest sessions built in parallel, 2026-09-12)

- Gate: `Prerequisite.QuestComplete("a_kingdom_alone")` — A Kingdom Alone (PR #349, merged) — so the
  quest auto-begins the moment that quest completes; `::questdebug begin at_the_white_wall` forces it.
- Chain slot **14** (`QuestBook.AT_THE_WHITE_WALL` = `LAST_INDEX`; slots 7-13 = The North, First
  Reclamation, A Kingdom Alone, BREACH, SECURE, UNDERSTAND, SUSTAIN). The client
  `LofQuest.AT_THE_WHITE_WALL` enum entry sits directly after SUSTAIN — declaration order IS the slot.
- Journal varp **4693** (`QuestJournal.WHITE_WALL_VARP`); 4686-4692 are the main-story quests'.
- Native tab row: Recruitment Drive, PLAN sort key `"15 At the White Wall"` (two-digit keys throughout
  `PLAN` so the lexicographic sort survives past nine rows).
- The Asgarnia payoff (Quest 5, The White Wall) — not this quest — solves BREACH via
  `StrategicObjectives.solve(p, Breach)` (package `content/quests/story`, PR #349).
- The generic-quest client support (`LofQuest` generic constructor, `LofQuestStep.goal`,
  `LofQuestVarps.generic*`, `isJournalVarp`) and the server `nativeTabVarp` / `questPoints` /
  poll auto-begin hunks are carried identically in the sibling PRs; merges are additive.

## Operator steps after merge

1. **Quest cache relabel** (native quest tab): Actions → *Quest cache relabel* → `relabel` (renames the
   Recruitment Drive row and re-sorts the legacy rows), then `hide`.
2. **Client:** `ship-client` runs on the push (`client/**` changed); launchers auto-update.
3. **Smoke test** (an account past A Kingdom Alone, or `::questdebug begin at_the_white_wall`): walk to
   the north gate → talk to a checkpoint knight → raiders stream in, fight beside a knight to confirm
   damage-share credit (5/5) → the knight's "led with the sword" line → Amik (castle top floor) →
   Tiffy (park bench) → stand at the checkpoint, inside the gate, and beyond the fence → Amik → complete;
   `::whitewall` and `::quests` track it; the stock quest tab row should go red → yellow → green.
   `::zone` at 2965,3408 should read SAFE (it is outside every red box since PR #350).
