# FIRST MARCH

**Main Story Quest 2 · Primary NPC: General Zo · Location: Lumbridge castle courtyard / the goblin camp east of the castle**
**Quest type: war onboarding / story · Development cost: Script** (dialogue + quest state + one
event-started public March on an existing target; no new NPC, map, enemy, item, mechanic or instance)
**Status: BUILT 2026-09-13** (branch `claude/ecstatic-mendel-t21h9u`)

**Purpose (master plan §7):** *introduce the living offensive war and let the player experience a real
public March early.* The Last Free City taught how the war comes to Lumbridge (*the war comes to you*);
First March is where Lumbridge goes to the war (*you go to the war*). The design's "Early War Exposure"
rule — the player should see or join a March in the first session — is met without waiting on the
half-hour timer: General Zo sends the column on the player's word.

The gap this closes: the built chain went The Last Free City → The North directly. Damien's debrief
already pointed at Zo's marches ("when you hear the call for the next March... answer it") and The
North's brief already assumed it ("You've marched with our Knights. You've even watched us win a
field."), but no quest ever put the player in a column. The North now gates on this quest's key.

## Core implementation decision

A framework quest (`content/quests/story/FirstMarch.kt`, key `first_march`) layered over the existing
march system. The battle is the existing `goblin_camp` march target — the camp the morning's probe
came from — launched as a public **MARCH** through `WarEvents.startPublicOperation`: free, sponsor-less,
open to every player, the same machinery as the Knight-Captain's scheduled column. Victory is read from
`WarHooks.onOperationEnded`, never by polling the ledger. **Any march-tier op counts** (Zo's column, the
scheduled march, a Lord's operation): if the realm's column is already in the field when the player
gives the word, Zo points them at it instead — a march is a march.

No retry step: a loss, or a win the player sat out, jumps back to the `ready` step with a counter set
(`driven_back` / `missed`), and Zo's ready prompt opens with the matching lines. That keeps the client
journal free of the battle-row/retry-row special case First Reclamation needed.

## Integration audit — every beat mapped to the build

| Beat | Existing NPC / place (unchanged) | Existing system | Code seam |
|---|---|---|---|
| Damien's handoff ("General Zo musters the columns…") | **Sergeant Damien** (The Last Free City's debrief — unchanged) | Framework auto-begin (`Prerequisite.QuestComplete("recruit_trials")`, `autoBegin`) — begins the moment the debrief completes (`QuestEngine.pollTick`) or on login | `FirstMarch` step `brief` |
| Brief: what a March is, why the goblin camp | **General Zo** 3220,3210 (castle hub) | `NpcTalk` — Zo is on `bindTalk` + a default branch; quests claim him on their own steps | `talk(ZO, "brief")` → `satisfy` → flows into the ready prompt |
| "Send the column" | General Zo | `WarEvents.startPublicOperation(world, WarType.MARCH, "goblin_camp")` — free, supply-free; `Announce.broadcast` muster line so every soldier may `::march` | `talk(ZO, "ready")` → `launch` → `advanceTo("march")` |
| Column already out (any target) | General Zo | `WarEvents.current()` (`CampaignRegistry.activeMarch`), `MarchTargets.isPvpGround` for the wilderness warning | `launch` Busy branch → `advanceTo("march")` |
| Rally / fight | The goblin camp 3254,3234 (`MarchTargets.GOBLIN_CAMP`) — or wherever the live column is | `::march` (`WarEvents.join`, with its PvP double-confirm); participation = ticks attacking inside the battle area (`CampaignDirector.recordParticipation`) | step `march` (`Objective.Manual`, anchor = the camp rally tile) |
| Victory / loss | — | `WarHooks.onOperationEnded` (after the ledger + payout); `WarResult.participated(name, 1)` | `FirstMarch.onMarchResult` → `advanceTo("report")` / back to `ready` with `missed` or `driven_back` |
| Column back, step never resolved (player was offline at the end) | General Zo | — | `talk(ZO, "march")` → points at a live column, else `launch` again |
| Debrief + handoff to The North | General Zo | completion reward | `talk(ZO, "report")` → `satisfy` completes: +25 War Effort, 1 QP |
| Journal | client Quest Journal + stock quest tab | varp **4698** (generic framework packing); native row = relabelled **Tree Gnome Village** (dbrow 150, varp 111, complete 9) | `QuestJournal.FIRST_MARCH_VARP`; `FirstMarch.nativeTabVarp` → `QuestEngine.publish` |

**Rejected options.** A Knight-Captain NPC and a physical muster at the gate (the design's "still ⬜"
list — not needed for the beat; Zo already musters). Waiting on the scheduled timer (up to 30 minutes
in a first session). Restricting the result to Zo's own column (a player who answers the real muster
call instead has done exactly what the quest asks). A rank or War Effort gate (participation is never
rank-gated — quest-authoring rule 5). A separate `retry` step (see above).

## Dialogue (shipped)

**General Zo — brief (`brief`).** *Player:* "Sergeant Damien sent me. He said you muster the columns." /
"He says that about everyone he's finished with." / "You held the east camp this morning." / *"I did."* /
"Then you've seen what a defence looks like. Men in a line, waiting to be hit." / "Now see the other
half." / *"The other half?"* / "The March. Every half hour, ten Knights of Lumbridge walk out of this
courtyard and hit something that deserves it. A camp. A road. A rogue position." / "Anyone may march
with them. No rank required." / "The goblins that probed the gate this morning came from a camp across
the river. If nobody visits, they'll be back at the gate by the end of the week." / "So we visit." →
options **"What happens on a march?"** ("The column forms up here and walks. When it reaches the ground
it fights until the enemy breaks — or the column does." / "Whoever fought shares the spoils. The realm
pays its soldiers." / "Type `::march` while the column is out and you'll be sent to it.") · **"Do the
knights need me?"** ("Ten knights alone get driven back more often than I'd like to admit. Marches
fail." / "When you don't march with them, they die. So yes.") · **"Understood."** → READY, straight
into the ready prompt.

**General Zo — ready (`ready`).** *(after a loss:)* "We were driven back." / *"So that's it?"* / "No.
That's a march. Now we know what didn't work, and we go again." *(after a win without the player:)*
"The column won that ground without you." / *"I..."* / "A march isn't something you watch from the
courtyard. I need you in the line, not behind it." — then: "The column musters on your word. Every
soldier of the realm will hear the call and may march with it." → **"I'm ready. Send the column."** /
**"Not yet - I need to prepare."** ("Then prepare. Eat something. Sharpen something. Come back and I'll
give the order.")

**Launch.** Started → broadcast *"General Zo has ordered a march on the goblin camp! The Knights of
Lumbridge set out from the castle courtyard. Any soldier may fight beside the column: `::march`."* /
"The column sets out now. Rally to it — `::march` — and stay in the line until the camp breaks." ·
Busy, column on the camp → "The column is already at the camp. Rally to it — `::march` — and make
yourself useful. If it breaks the camp with you in the line, that's your first march." · Busy, column
elsewhere → "The Knight-Captain's column is already out — at <target>. Rally to it: `::march`. A march
is a march; if it wins with you in the line, that's your first." (+ on wilderness ground: "That's
wilderness ground — other adventurers can attack you there. If you'd rather not, wait for the column to
return and I'll send it to the camp.") · Busy otherwise → "Another operation holds that ground (<reason>).
The column waits until the field is clear — come back shortly and I'll give the order."

**General Zo — march (`march`).** Live column → "The column is in the field right now — at <target>.
`::march`. Get in the line." · none → "The column's back and you're standing in my courtyard. We go
again." → launch.

**General Zo — report (`report`).** *"The field's ours."* / "For now. Goblins breed faster than we
march." / *"Then why bother?"* / "Because every march we don't make, they make instead. You saw the gate
this morning." / "That's the March. Free, frequent, and it never waits for anyone." / "You'll hear the
muster call every half hour for as long as you're a soldier of this realm. Now you know what it's
asking." / *"Do I have to answer every one?"* / "No. But the ones you skip, the knights walk alone." /
**QUEST COMPLETE** / "You've seen Lumbridge attacked. Now you've marched with our Knights and watched us
win a field." / "Don't let it go to your head. There's something I want you to see in the north." → The
North auto-begins ("You said I should see Edgeville.").

## Quest journal (server step objectives = client journal rows)

| State | Journal |
|---|---|
| BRIEF (`brief`) | General Zo musters the columns that march against the enemy. Report to him in the castle courtyard. |
| READY (`ready`) | Tell General Zo when I am ready to march with the Knights of Lumbridge. |
| MARCH (`march`) | Fight beside the Knights of Lumbridge in the march and see it through to victory. |
| REPORT (`report`) | Report the march to General Zo. |
| DONE | I marched with the Knights of Lumbridge and watched the realm win a field. The muster call sounds every half hour; now I know what it is asking. |

Result lines: *"The column holds the field. Report to General Zo."* · *"The knights won <target> without
you in the line - General Zo will want you in the next march."* · *"The column was driven back. Speak
with General Zo to march again."* `::firstmarch` prints the current objective and opens the Quest
Journal on the quest.

## Rewards

**1 Quest Point** · **25 War Effort** · the march's own payout (the realm pays every fighter from the
pooled spoils, contribution-scaled — `CapturePayout`) · main campaign progression · unlocks **The
North**. No gear, no currency from the quest itself.

## Development section

| Field | First March |
|---|---|
| Start NPC | General Zo, 3220,3210 (Lumbridge castle courtyard) — the quest auto-begins after The Last Free City |
| NPCs Used | General Zo · the Knights of Lumbridge (the march column) · the camp goblins (the march target's garrison) — all existing |
| Locations | Lumbridge courtyard · the goblin camp east of the castle (`goblin_camp` march target) — unchanged |
| Gameplay Used | Marches (`MarchPlugin` / `CampaignDirector`, event-started through `WarEvents`) · `::march` rally · participation ledger + war payout · War Effort |
| Dialogue | Zo brief (+2 optional branches) · ready prompt (+2 setback openers) · launch outcomes · march regroup · debrief |
| Quest State | Framework `QuestStates` blob (`first_march`: step ids above + `missed` / `driven_back` counters); completion flag `quest.first_march.done` |
| Journal | table above; client `LofQuest.FIRST_MARCH` (varp 4698, generic packing; chain slot 18); native tab = Tree Gnome Village relabelled (varp 111, complete 9; sort "19 First March") |
| System Hooks | `NpcTalk` (Zo) · `WarEvents.startPublicOperation` / `current` · `WarHooks.onOperationEnded` · `Announce.broadcast` · `Reward.WarEffort` |
| Temporary Content | None (the public march is the realm's own machinery; it ends the way every march ends) |
| New NPCs | NONE |
| New Maps | NONE |
| World Changes | NONE |
| New Mechanics | NONE |
| Development Cost | **Script** |

**Chain-order note.** Framework quests take chain slots in build order (The North took 7 after the legacy
hallway). First March is Main Story Quest 2 but was built after Old Wounds, so it is chain slot 18 /
tab sort "19" — the journal's node track shows it after the Asgarnia quests. Re-slotting the whole
chain into story order is a separate, client-and-cache-wide change and was not done here.

**Existing players.** Anyone who finished The Last Free City before this landed gets First March
auto-begun on their next login (or the next framework poll), whatever else they have done — the quest
is short and the muster call is the point. Their The North / First Reclamation states are untouched.

## Operator steps after merge

1. **Quest cache relabel** (Actions → *Quest cache relabel* → `sync`): adds "First March" to the stock
   quest tab (Tree Gnome Village's row, moved under Free Quests by `free`). The quest works regardless.
2. **Client:** the `ship-client` workflow runs on the push (client/ changed); launchers auto-update.
3. **Smoke test** on an account that has finished The Last Free City: log in → "First March — begun" →
   Zo brief → "Send the column" (broadcast; the column walks to the camp) → `::march` → fight until the
   camp breaks → "The column holds the field" → Zo debrief → complete, +25 War Effort → "The North —
   begun". Then `::questdebug reset first_march` and test the loss path (`::marchnow` can interfere:
   Zo's column refuses to start while the scheduled march is out — he points you at it instead) and the
   sat-out path (send the column, stand in the courtyard until it wins → back to Zo, "without you").
