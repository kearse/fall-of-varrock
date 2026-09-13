# A KINGDOM ALONE

**Main Story Quest 5 · Primary NPCs: Duke Horacio, General Zo · Location: Lumbridge command area**
**Quest type: strategic story / regional campaign unlock · Development cost: Script** (dialogue +
quest state + four standing journal entries; nothing new in the world)
**Status: BUILT 2026-09-12** (branch `claude/kingdom-alone-quest-57b7dc`)

**Primary purpose:** the uncomfortable conclusion of the opening arc — **Lumbridge cannot retake
Varrock alone** — and one of the largest content unlocks in the early campaign: the regional
campaign phase opens, all four strategic problems at once.

## Core implementation decision

A framework quest (`content/quests/story/AKingdomAlone.kt`, key `a_kingdom_alone`) of four
`TalkTo` steps — START → ZO → DUKE → STRATEGY → DONE — at two NPCs who already stand side by side.
The "Main Campaign journal becomes a strategic overview" requirement is met with the **normal quest
journal**: the four objectives are four more framework quests (`StrategicObjectives.kt`: `breach`,
`secure`, `understand`, `sustain`) with `Objective.Manual` steps, begun by A Kingdom Alone's
completion and solved only by their regional campaigns' payoffs. That gives them a client journal
entry, a native quest-tab row (red / yellow / green = not yet open / open / solved), a
`::questdebug` line and the standard `quest.<key>.done` flag each — with no new interface, no
war-table, no council scene.

The design's four strategic flags **are** those framework flags (`Breach.flag` =
`quest.breach.done` …); `COUNCIL_AVAILABLE` is `StrategicObjectives.allSolved(p)` and nothing else
(not quest points, not a count of regional quests, not rank, not War Effort).

## Integration audit — every beat mapped to the build

| Beat | Existing NPC / place (unchanged) | Existing system | Code seam |
|---|---|---|---|
| Start: "Duke Horacio wants a report" | **Duke Horacio** 3220,3211 (Lumbridge command area, already spawned) | Framework auto-begin (`Prerequisite.QuestComplete("first_reclamation")`, `autoBegin`) — begins the moment First Reclamation completes (`QuestEngine.pollTick` auto-begin) or on login | `AKingdomAlone` step `report`; `DukeHoracioPlugin` migrated to `bindTalk` + an `NpcTalk` default branch so the quest's `talk(DUKE, …)` branches claim him on their steps only |
| The question / the reality | **General Zo** 3220,3210 (one tile south of the Duke) | `NpcTalk` priority branches | `GeneralZoPlugin` migrated the same way; step `zo` (three converging player options) |
| Return to the Duke | Duke Horacio | — | step `duke`; satisfied mid-conversation and flows straight into the strategy discussion (no second click) |
| The surviving kingdoms / no simple alliance / the four problems / "which first?" / final exchange | Duke Horacio, with **Zo speaking through his own portrait** in the same conversation (`chatNpc(npc = zoId)`) | — | step `strategy` (re-entry branch for a closed chat box); `QuestEngine.satisfy` right before the Duke's last line → complete |
| Regional phase opens | — | Framework quests + flags | `AKingdomAlone.onComplete` → `StrategicObjectives.open` begins BREACH / SECURE / UNDERSTAND / SUSTAIN |
| Standing overview | — | Quest journal (client window, side panel, native tab) | `StoryQuestsPlugin`: one login line + `::strategy` (prints the four statuses, opens the journal on the first unsolved) |
| Council of Gielinor gate | — | — | `StrategicObjectives.councilAvailable(p)`; the Council quest (separate content) gates on it |

**Rejected options (design "what this quest is NOT").** Four fetch/ambassador quests; a council
scene; a custom strategy interface or war-table; another battle; a forced choice of one path; a
War Effort reward (the player fought and supplied nothing here).

## Dialogue (shipped — condensed from the design; nothing added beyond two handoff lines)

**Duke (START → ZO).** "I heard the standard is flying at the Southern Watch." / *"It is."* /
"Then you've done something this kingdom hasn't managed in twelve years." / *"Taken ground back."*
/ "Yes." / "...And now we discover whether we can keep doing it." / "Zo has the numbers for an
assault on Varrock. Get them from him — he's beside me." / "I suspect you won't enjoy them." /
*"I'm getting used to that."*

**Zo (ZO → DUKE).** "The Duke sent you back to me." / *"He says you've done the arithmetic on
Varrock."* / "I have. You won't like it." / "Think we can take it?" *(not a re-run of First Reclamation's
"we can't take Varrock — not alone": the Duke has just sent the player back for the arithmetic)* — options
*Not with what we have* ("No. We can't.") / *Give me enough Knights* ("That's the problem. We don't
have enough Knights.") / *We won't know until we try* ("We would know. Once.") — converge. "Lumbridge
has done better than anyone expected. We survived. We rebuilt a fighting force. We reopened roads.
We established a position beneath Varrock itself." / *"That sounds like progress."* / "It is. But a
forward post and a city are very different things." / "If we march on Varrock today... we cannot
reliably break its defences. We cannot guarantee Misthalin remains secure while our army is north.
We don't actually understand what destroyed the city. And even if we solve all three... we cannot
keep an army that size supplied for long." / "...We would lose." / *"So the Southern Watch was
pointless?"* / "No. It proved something." / *"What?"* / "That the enemy can be pushed back." /
"What it did not prove... is that Lumbridge can finish the job alone." / "Go and tell the Duke."

**Duke (DUKE → STRATEGY, same conversation).** *"Zo says we don't have what we need."* / "He's
right." / *"So what now?"* / "We stop pretending Misthalin is the only kingdom left in Gielinor."

**Strategy (→ DONE).** The surviving kingdoms ("Falador still stands. Kandarin still trades. People
still live beyond the Salve. The Wilderness still keeps secrets older than Varrock's ruin. And the
desert remembers things our scholars have forgotten.") → why nobody is coming yet → "Find out what
each problem actually requires. Then solve it." → **BREACH** (Duke: Falador's soldiers, engineers,
weapons) → **SECURE** (Zo: "If our army marches north, something else notices." / *"Morytania."* /
"Among others.") → **UNDERSTAND** (*"We already know what happened…"* / "We know what people saw." /
*"What's the difference?"* / "That's what worries me." — reveals nothing) → **SUSTAIN** (Zo:
"Now imagine feeding thousands… For weeks." / *"That sounds expensive."* / "That's the first
sensible thing you've said all day.") → *"Which one do I do first?"* / "Whichever opportunity opens
first." / *"That's not very helpful."* / Zo: "Neither is a war." / "Work where you can. Falador.
Morytania. The Wilderness. Kandarin. Progress in one may open opportunities in another. We need all
four problems solved before we commit to Varrock." → *"So that's it? Fix half of Gielinor?"* / Zo:
"Only the useful half." / "General." / "What?" / *"And when all of this is done?"* / "Then we stop
asking whether Varrock can be reclaimed." / **QUEST COMPLETE** / "...And decide how."

Each problem also lands as a chat line (`BREACH — Asgarnia: … Lead: Falador / Asgarnia — first
quest: At the White Wall.`) so the four are readable after the conversation.

## Quest journal (server objective lines = client step rows, 1-based)

| State | Journal |
|---|---|
| `report` (START) | Duke Horacio wants a report on the kingdom's position after establishing the Southern Watch. |
| `zo` (ZO) | Speak with General Zo about what would be required to attack Fallen Varrock. |
| `duke` (DUKE) | Report General Zo's assessment to Duke Horacio. |
| `strategy` (STRATEGY) | Discuss the surviving kingdoms and the four problems preventing an assault on Varrock. |
| DONE | Lumbridge cannot reclaim Varrock alone. I can now work across Gielinor to solve BREACH, SECURE, UNDERSTAND and SUSTAIN in preparation for a future assault. |

Quest complete message: "The war for Varrock is no longer only Misthalin's war. Four strategic
problems now stand between the surviving kingdoms and a sustained assault on Fallen Varrock." then
"Regional campaigns unlocked: BREACH (Asgarnia), SECURE (Morytania), UNDERSTAND (Wilderness, then
the Desert), SUSTAIN (Kandarin and the War Effort)."

### The four standing entries (PREPARING FOR VARROCK)

| Objective (key) | Journal line | Lead | Solved by |
|---|---|---|---|
| **BREACH** (`breach`) | Find a way for coalition forces to break through Varrock's defences. | Falador / Asgarnia — At the White Wall | the Asgarnia campaign's payoff (`StrategicObjectives.solve(p, Breach)`) |
| **SECURE** (`secure`) | Ensure Misthalin will remain secure while its army fights in the north. | River Salve / Morytania — Across the Salve | the Salve Accord |
| **UNDERSTAND** (`understand`) | Discover what truly happened during the Fall and whether the same danger remains. Three ordered steps: `wilderness` → `desert` → `senntisten` (`StrategicObjectives.advance`). | the Wilderness — The First Scar; then the Desert; then Senntisten | the Senntisten step |
| **SUSTAIN** (`sustain`) | Create the supply and transportation network required to maintain a major offensive. | Kandarin + the War Effort — The Long Road East | restored logistics |

They have no guidance arrow (`serverArrow = false`), no login nag (`loginReminder = false` — the
phase announces itself as ONE line: `Preparing for Varrock: BREACH incomplete SECURE incomplete …
::strategy`), and cannot complete any other way. The Wilderness lead never requires a PvP kill.

## Rewards

**2 Quest Points** (`questPoints = 2` — `QuestJournal.sync` now derives `Varp.QUEST_POINTS` and the
summary tab's quest counts from the registry) · the **Regional Campaign Phase** · BREACH / SECURE /
UNDERSTAND / SUSTAIN opened · leads in the journal · the Council of Gielinor completion condition
armed. No coins, no gear, no War Effort.

## Open-world rule

Completing the quest unlocks the regional **story campaigns** and their story conversations, not
the regions: Falador, Morytania, the Wilderness and Kandarin stay open-world locations regardless.
A regional campaign's first quest gates on `Prerequisite.QuestComplete("a_kingdom_alone")`.

## Development section

| Field | A Kingdom Alone |
|---|---|
| Start NPC | Duke Horacio, 3220,3211 (Lumbridge command area) — auto-begun after First Reclamation |
| NPCs Used | Duke Horacio · General Zo (3220,3210) — existing, in place, side by side |
| Locations | Lumbridge command area — unchanged |
| Gameplay Used | Quest framework (`TalkTo` steps, `NpcTalk` branches, auto-begin, flags), Quest Journal (client window + side panel + native tab), `::strategy` |
| Dialogue | Duke ×3 beats, Zo ×1 beat + interjections in the strategy scene (all new; the NPCs' everyday dialogue untouched) |
| Quest State | `QuestStates` step ids `report` / `zo` / `duke` / `strategy`; completion flag `quest.a_kingdom_alone.done`; four objective quests with `quest.breach.done` etc. |
| Journal | table above; client `LofQuest.A_KINGDOM_ALONE` (varp 4688) + `BREACH`/`SECURE`/`UNDERSTAND`/`SUSTAIN` (4689-4692) + a `COUNCIL_OF_GIELINOR` FUTURE teaser; native rows Rune Mysteries / Black Knights' Fortress / Prince Ali Rescue / Vampyre Slayer / Pirate's Treasure (`QuestTablePatch.PLAN`, two-digit sort keys) |
| System Hooks | `QuestEngine.pollTick` auto-begin (new, shared) · `QuestEngine.publish` native-row mirror (`nativeTabVarp`, shared) · `questPoints` / `loginReminder` (new) · `StrategicObjectives.solve/advance/allSolved` for the regional campaigns and the Council |
| Temporary Content | None |
| New NPCs | NONE |
| New Maps | NONE |
| World Changes | NONE |
| New Mechanics | NONE (four standing journal entries = four framework quests) |
| Development Cost | **Script** |

## Operator steps after merge

1. **Quest cache relabel** (native quest tab): Actions → *Quest cache relabel* → `relabel`, then
   `hide` — five new rows (A Kingdom Alone + the four objectives) and the re-keyed sort names.
2. **Client:** `ship-client` runs on the push (client/ changed); launchers auto-update. The Quest
   Journal window's node track now carries 14 nodes.
3. **Smoke test** (admin, until First Reclamation is live: `::questdebug begin a_kingdom_alone`):
   Duke → Zo (try each of the three answers) → Duke → the strategy scene runs to "...And decide
   how." → quest complete, four objectives begun (`::questdebug dump`), `::strategy` prints the
   overview and opens the journal on BREACH, native tab shows A Kingdom Alone green and the four
   objectives yellow, summary tab shows 2 quest points. Close the chat box mid-scene and re-talk to
   the Duke: the strategy beat re-runs from "As I was saying". `::questdebug complete breach` turns
   BREACH green and prints the "solved" line; with all four complete `::strategy` says the Council
   can convene.
