# OLD WOUNDS

**Asgarnia — BREACH, Quest 4 · Primary NPCs: Sir Tiffy Cashien, Lord Daquarius · Locations: Falador
Park → Dark Warriors' Fortress → the First Scar (southern Wilderness) → Falador**
**Quest type: intelligence / Wilderness / investigation / lore · Development cost: Low–Medium**
(dialogue + quest state + area triggers + one crate search + two light-custom documents + two
owner-bound Daquarius scenes; no new map, NPC, enemy, object or mechanic)
**Status: BUILT 2026-09-12** (branch `claude/old-wounds-quest-a60c23`)

**Purpose:** the intelligence component of Asgarnia's campaign. After *A Matter of Trolls* (manpower)
and *The Guns of Asgarnia* (artillery) the player expects "Great. Let's attack the Kinshra." Instead
Sir Tiffy says something is wrong: Kinshra officers and couriers are going north and not coming
back. The quest follows them to the Dark Warriors' Fortress, recovers their orders — half military
intelligence that makes BREACH possible, half a search for "the site designated FIRST SCAR" and old
Temple Knight records concerning Lucien — introduces **Lord Daquarius** as an intelligent recurring
rival (not a boss), and takes the player to **the First Scar** itself: an old Temple Knight survey
site whose buried report sounds disturbingly like the Fall of Varrock. The player learns exactly one
new fact: *whatever happened at Varrock may not have begun at Varrock.* Nothing about Sliske, the
Elder Horn, Senntisten or the Stone is said. It is also the player's **first deliberate Wilderness
mission** — real PvP, never instanced, never protected, kept to the lower Wilderness.

## Core implementation decision

A framework quest (`quests/asgarnia/OldWounds.kt`, key `old_wounds`) layered over the world as it
is. Tiffy is the stock park-bench spawn; the fortress is unchanged and its Dark Warriors remain
ordinary enemies (no kill count — the environment is the challenge); the orders live in the
fortress hall's existing searchable crate (the stock crate 354 — its *Search* bind belongs to
`SearchCratesPlugin`, so a new `CrateSearch` hook claims that one tile instead of a second bind);
the First Scar is an existing ruin — the ring of ruined pillars and rubble south-east of the
fortress (Wilderness level 9) — with three tile-triggered inspections (the pillars and rubble have no
cache verbs, so "quest-only interaction options" would need a world change); the two documents ride
stock note defs that already carry the *Read* verb. Daquarius appears twice as an **owner-bound npc**
(`quests/asgarnia/LordDaquarius.kt`): visible to the one player whose scene it is, never fightable,
swept on logout — two players reading the same crate each get their own Daquarius and nobody else in
the Wilderness sees a Kinshra lord standing about. Design rule honoured: no instancing, no protected
corridor, no PvP disabled around the player.

## Integration audit — every beat mapped to the build

| Beat | Existing NPC / place (unchanged) | Existing system | Code seam |
|---|---|---|---|
| Journal entry after both quests: "Sir Tiffy Cashien wants a word in Falador Park." | — | framework `autoBegin` (login / poll / rank-up) | `OldWounds.prerequisites` = `Prerequisite.Custom` — both `a_matter_of_trolls` and `guns_of_asgarnia` complete (whichever keys are registered; falls back down the chain if neither has landed) |
| Brief: "heavily armed… or behaving strangely?", the fortress, the Wilderness warning | **Sir Tiffy Cashien** 2997,3373 (park bench; `npc_spawns.json` 4687) | `NpcTalk` — At the White Wall owns his `bindTalk` + idle lines; this quest registers quest-priority branches (+ an eligible-player opener at `PRIORITY_QUEST - 1`, and a placeholder so he is never mute if this PR lands first) | `OldWounds.brief` → `satisfy(brief)` |
| Enter the fortress | **Dark Warriors' Fortress** interior 3020-3038 × 3622-3642 (region dumps 12088/12089; hall doors on the south face) | `Objective.ReachArea` (the 3-tick poll) | step `fortress` — `onLeave` narrates the Kinshra traces |
| Find the orders — one existing crate gains *Search* meaning | the stock **Crate 354** at 3026,3628 in the hall (the only searchable containers there are it and the sacks) | `SearchCratesPlugin`'s existing *Search* bind + the new `CrateSearch` hook (tile-checked) | `OldWounds.searchCrate` → give the orders (dupe-proof) → `satisfy(orders)` → read + scene |
| The Kinshra Field Orders | stock **"Orders note" 28431** (Read/Destroy verbs) | item override YAML (`itemOverrides/quests/OldWounds.yml`: untradeable → always kept on death) + `objs.csv` examine + cache rename (`ItemDefTool oldwounds`, workflow action) | `OldWounds.ORDERS`; `onItemOption(read)` guarded in the plugin |
| Daquarius at the fortress: "You might as well finish reading it." | **Lord Daquarius** (stock 4929, Talk-to only) spawned owner-bound at 3029,3630, leaves via 3033,3627 | `Npc(owner, id, tile, world)` — `NetworkServiceFactory.shouldAdd` shows an owned npc to its owner only; `WorldRemoveTask` sweeps it on logout | `LordDaquarius.appear / leave / sweep`; counter `daq_fortress` (replayable from the pack's Read inside the fortress until it has run) |
| Report to Tiffy: the useful half, the second section, Lucien, "classified from us", the reference | Sir Tiffy | `NpcTalk` | `OldWounds.report` — Tiffy keeps the orders (`take`) → `satisfy(report_tiffy)`; banked → fetch; lost → the crate re-issues |
| Reach the First Scar | the existing **ring of ruined pillars** 3056-3066 × 3587-3595 (locs 34795-34798 Ruined Pillar / Pillar, 34803-34804 Rubble; nothing named "First Scar" anywhere — the name is Temple Knight terminology) | `ReachArea` | step `first_scar` — `onLeave` arrival narration |
| Three inspections (stone / rock / cache) | the south standing stones (3059-3061,3587), the centre mass (3059-3061 × 3590-3592), the north-west rubble (34803 at 3057,3594) | three sequential `ReachArea`s (3×3 around each point; the locs carry no verbs) | steps `scar_stone` → `scar_rock` → `scar_cache`; the third gives the report (dupe-proof) and queues the read |
| The Damaged Temple Knight Report | stock **"Intel report" 761** (Read/Destroy verbs) | same treatment as the orders; the player KEEPS it (lore journal) | `OldWounds.REPORT`; `readReportFromPack` → `satisfy(report_found)` + the reflection lines |
| Daquarius at the Scar: "So it exists." … "why someone wanted everyone to stop." | Daquarius spawned owner-bound at 3060,3597, leaves north | as above | counter `daq_scar`; replayable from the pack's Read on site |
| Return: "Oh dear." — the honest uncertainty, the removed authorisation code, the good news | Sir Tiffy | `NpcTalk` | `OldWounds.debrief` → `satisfy(return_tiffy)` completes; banked → fetch; lost → the cache re-issues (plugin sweep, `OldWounds.sweep`) |
| Rewards + campaign board | — | `Reward.WarEffort(75)`, `Reward.Flag("asgarnia.intelligence_secured")`, `Reward.Flag("first_scar.discovered")`, `questPoints = 2` | `completionRewards`; `onComplete` prints the ASGARNIA — BREACH board (reads the siblings' `asgarnia.northern_front_secured` / `asgarnia.artillery_restored` or their quest keys) |
| Handoff | — | journal DONE text: Sir Amik will send word when Falador is ready to move east (the offensive quest is not built yet, so nothing names it to the player) | `completionMessage`; `::oldwounds` |

**Deliberately NOT done (design §13-16, §38-39, §44):** no instance, no protected corridor, no PvP
disabled; no redesigned fortress, dungeon or infiltration map; no lockpicking / stealth / disguise;
no kill-15-Dark-Warriors; no map edit for a crater — the name does the work; no archaeology or
puzzle interface; Daquarius is never fought and never a boss; nothing about Sliske, the Elder Horn,
Senntisten, the Stone, Dragonkin or Arrav's fate; BREACH itself is NOT solved here (The White Wall
calls `StrategicObjectives.solve(p, Breach)`), and UNDERSTAND is NOT advanced here (that belongs to
the Wilderness campaign's *The First Scar*).

**Rejected options.** Binding *Search* on a fortress crate directly (the stock crate is already
bound — a second `onObjOption` drops the plugin; the sacks 14743 would have worked but "orders in a
sack" reads badly); dynamic objects with *Inspect* verbs at the Scar (a world change for three
lines of text); a shared-world Daquarius spawn (fightable, campable, and wrong for two players at
once); Tiffy re-issuing lost documents (he never had the report — the ground does); a custom
"campaign journal" object (the board is derived live from the quests' flags, nothing to keep in sync).

## Dialogue (shipped — the design text, verbatim where it exists)

**Sir Tiffy — brief.** "Ah! There you are." / "Were you looking for me?" / "I was indeed." *(works whether
the player heard Amik's "Tiffy is about to find a new problem" — now said at the end of whichever of
Trolls / Guns finishes last — or not)* / "I understand
you've been solving several of Asgarnia's more inconvenient military problems." / "The trolls." /
"Yes." / "The cannons." / "Quite." / "So what's next?" / "That depends." / "Do you prefer your
enemies heavily armed… or behaving strangely?" / "Which one is worse?" / "The strange ones,
usually." / "The Kinshra front hasn't collapsed. But something has changed." / "What?" / "Movement.
Small groups. Officers. Couriers. Not toward Falador." / *(pause)* "North." / "The Wilderness?" /
"Precisely." / "Maybe they're getting reinforcements." / "Then one would generally expect the
reinforcements to come back. They aren't." / *(intercepted messages)* "We've seen Kinshra
detachments moving through the Dark Warriors' Fortress. Not enough for an offensive. Far too many
for sightseeing." / "What are they doing there?" / "That… is the inconvenient part." / "You don't
know." / "I prefer the phrase 'not yet'." / "One other matter." / "The Wilderness." / "Excellent.
You're learning." / "The people you've fought on the roads follow rules. Even the unpleasant ones.
Beyond the Wilderness ditch, other adventurers do not. Take only what you're prepared to lose." /
"And you're sending me there alone." / "Technically, I'm sending you there discreetly." / "That
doesn't make it better." / "It makes the paperwork considerably easier."

**The fortress (arrival narration).** "The Dark Warriors' Fortress. The Dark Warriors hold the walls
as they always have." "Fresh boot-tracks in the mud, a Kinshra pennant left on a spear wall, ration
crates that are not the fortress's own." "Whatever the Kinshra left behind is inside. The hall is
the place to start."

**The Kinshra Field Orders (four pages).** *Western companies maintain present positions. Reserve
strength is to remain concealed behind the northern works. / Artillery crews are not to redeploy
without direct authorization. Supply convoys will continue through the western approach. / Survey
detachments will continue north through the fortress. All recovered records bearing the Temple
Knight seal are to be delivered unopened. / Priority remains the site designated FIRST SCAR. Searches
concerning Lucien are authorized by Lord Daquarius personally.* — "Lucien?" — journal: *The Kinshra
are searching the Wilderness for something called the First Scar. Their orders specifically mention
old Temple Knight records concerning Lucien. I should report this to Sir Tiffy.*

**Lord Daquarius — the fortress.** "You might as well finish reading it." / "Lord Daquarius." /
"Good. At least Tiffy tells his agents who they're stealing from." / "Are you going to stop me?" /
"If I intended to stop you, we would not be having this conversation." / "That's reassuring." / "It
wasn't intended to be." / "What is the First Scar?" / "If I knew that… you wouldn't be holding my
orders." / "You've been sending men into the Wilderness looking for something you don't
understand?" / "You say that as though your own kingdom hasn't spent twelve years doing precisely
that." / "This has something to do with Varrock." / *(pause)* "Perhaps." / "You know it does." / "I
know there are questions your White Knights stopped asking a long time ago." / "Before Varrock fell,
the Temple Knights investigated disturbances in the Wilderness. Most were dismissed. Some were
classified. One was buried so thoroughly that even Tiffy's people seem to have forgotten it." /
"And Lucien?" / "His name appears in the surviving records. That alone makes them worth finding." /
"So this isn't about attacking Falador." / "Everything I do is somehow about Falador. But no. Not
this." / "Why tell me any of this?" / "Because if I'm wrong… you've learned nothing useful." / "And
if you're right?" / "Then I would rather Sir Tiffy start paying attention." — *He leaves.*

**Sir Tiffy — report.** "Reserve positions… Supply route… Artillery placements…" *(smiles)* "Oh,
this is useful." / "Keep reading." *(the smile disappears)* "Where did you get this?" / "Where you
sent me." / "The second section." / "Daquarius said your people investigated something before
Varrock fell." *(or, if the scene was skipped: "Someone has the Kinshra digging for old Temple Knight
records…")* / *(silence)* → **Who was Lucien?** "A Mahjarrat." / "Like Zemouregal?" / "Yes.
Different ambitions. Equally unpleasant consequences." → **What are these old records?** "There
were Wilderness reports. Old ones. From operations involving Lucien." / "And?" / "They were
classified." / "You're Temple Knights. That doesn't sound unusual." / "They were classified from
us." / *(bureaucracy)* "I have it." / "The report?" / "A reference to it." / "Survey Site Seven.
Southern Wilderness. Field designation…" *(pause)* "First Scar." / "The old ring of pillars
south-east of the Dark Warriors' Fortress, in the lower Wilderness. Go and look at it — before the
Kinshra recover whatever remains there." / "I'll keep these. Sir Amik will want the first half
rather badly."

**The First Scar (narration).** Arrival: "Survey Site Seven." "Ruined pillars stand in a broken ring
on open ground. The Temple Knights called this place the First Scar." "It does not look like much.
That is probably why nobody stopped here in twelve years." · Stone: "The stone is discoloured, but
there is no sign of ordinary fire." · Rock: "Some of the surrounding rock appears warped rather than
broken." — "What happened here?" · Cache: "You shift the rubble. Beneath it, wrapped in rotted
oilcloth, is an old Temple Knight field cache." "Inside: a damaged Temple Knight report, the seal
long broken."

**The Damaged Temple Knight Report (four pages).** *FIELD REPORT — SITE SEVEN. The disturbance has
ended. No conventional magical source has been identified. Witness accounts remain inconsistent. /
Several reported hearing movement or voices where no persons were present. Magnetic and
navigational instruments failed inside the affected area. / Stone recovered from the centre displays
deformation inconsistent with heat or impact. Similar disturbances have been noted during operations
concerning Lucien. / Further investigation recommended. **Recommendation denied. Site sealed.
Records restricted.*** — "Voices." "Warped stone." "Magic nobody could identify…" — *Something about
the report sounds disturbingly similar to accounts from the Fall of Varrock.*

**Lord Daquarius — the Scar.** "So it exists." / "You followed me." / "No. I followed the same
twelve-year-old trail." / "I got here first." / "Yes. You seem very proud of that." / "You knew this
looked like Varrock." / "I suspected." / "You could have told us." / "I command the army trying to
conquer your kingdom." / "Fair." / "Does it name a cause?" / "No." *(genuinely disappointed)* "Then
we're both still ignorant." / "Why do you care?" / "Because Zemouregal attacked Varrock. Arrav led
his dead. Everyone knows that." *(he looks at the ruined site)* "But this happened before either of
them reached the city." / "What are you going to do now?" / "Continue looking." / "For what?" *(he
turns away)* "The question you should be asking… is why someone wanted everyone to stop." — *He
leaves. His men will be along soon — too late.*

**Sir Tiffy — return.** *(a longer pause)* "Oh dear." / "That's not usually what you want your
spymaster to say." / "Strictly speaking, I'm not—" / "Tiffy." / "Yes. Oh dear." / "Is it the same
thing that happened at Varrock?" / "I don't know." / "Zemouregal attacked Varrock. His dead broke its
defences. Arrav was seen among them. Those facts haven't changed." *(he looks at the report)* "But
perhaps something else was happening at the same time." / "Who denied the investigation?" / "The
authorisation code has been removed." / "Can you find out?" / "I intend to." / "And Daquarius?" /
"Unfortunately… so does he." / "There is one piece of good news." / "Finally." / "Daquarius has been
moving experienced troops off the Falador front to conduct these searches. And his orders identify
his remaining reserves." *(he spreads out the stolen plans)* "For twelve years, we've attacked the
Kinshra where they were strongest." / "And now?" *(taps the orders)* "Now we know where they
aren't." / "You want to attack." / "Oh, heavens no." / "No?" / "I want Sir Amik to attack." /
**QUEST COMPLETE** / "I intend to stand somewhere comfortably behind him." / "Keep the report. I have
copied every word, and I would rather the original were somewhere the Kinshra don't think to look." /
"Sir Amik will send word when Falador is ready to move. It won't be tomorrow."

**Mid-quest Tiffy** (quest-priority reminders so his everyday lines never confuse): the fortress
("North of the ditch, west of the road… And — discreetly."), the Scar ("Survey Site Seven… Before the
Kinshra, if you'd be so kind."), the report ("Read it, and bring me every word.").

## Quest journal (server objective lines = client step rows)

| Step (id) | Journal |
|---|---|
| START (`brief`) | Sir Tiffy believes the Kinshra are conducting unusual operations in the Wilderness. *(nudge: he has asked for me in Falador Park)* |
| FORTRESS (`fortress`) | Search the Dark Warriors' Fortress for information about Kinshra activity. *(nudge: Wilderness ~level 14; other players can attack you; take only what you're prepared to lose)* |
| ORDERS (`orders`) | Recover the Kinshra Field Orders. *(nudge: search the crate in the fortress hall)* |
| REPORT_TIFFY (`report_tiffy`) | Take the stolen orders to Sir Tiffy. |
| FIRST_SCAR (`first_scar`) | Investigate the old Temple Knight site known as the First Scar. *(nudge: the ring of ruined pillars south-east of the fortress, Wilderness level 9)* |
| INVESTIGATE (`scar_stone` / `scar_rock` / `scar_cache`) | Examine the strange remains at the First Scar. *(nudges: the south standing stones / the centre rock / the north-west rubble)* |
| REPORT_FOUND (`report_found`) | Read the damaged Temple Knight report. |
| RETURN (`return_tiffy`) | Take the report back to Sir Tiffy. |
| DONE | The First Scar predates Varrock's fall. Asgarnia now has the intelligence required to attack the Kinshra front. |

Client: `LofQuest.OLD_WOUNDS` (generic journal varp **4696**, rows = the 1-based server step index;
the three inspection steps have their own rows). Native quest tab: the **Wanted!** row relabelled
(`QuestTablePatch.PLAN` "18 Old Wounds", dbrow 156, col0 92, varp 1051, complete 11 — Wanted!'s own
start NPC is Sir Tiffy) — **operator: run the Quest cache relabel workflow → `relabel`, then `hide`.**
Chain slot 17 (`QuestBook.OLD_WOUNDS`): after At the White Wall 14, A Matter of Trolls 15, The Guns
of Asgarnia 16 — whichever Asgarnia PR merges last orders the client enum and re-checks the constants.

## Campaign journal (`::oldwounds`, and printed at completion)

```
ASGARNIA - BREACH
 Northern Frontier: SECURED   Artillery Production: RESTORED
 Kinshra Intelligence: SECURED   The First Scar: DISCOVERED
 BREACH: READY - speak with Sir Amik Varze
```
Derived live: Northern Frontier from `asgarnia.northern_front_secured` or `a_matter_of_trolls`
complete; Artillery from `asgarnia.artillery_restored` or `guns_of_asgarnia` complete; the rest from
this quest. Nothing is stored twice.

## Rewards

**2 Quest Points · 75 War Effort · `asgarnia.intelligence_secured` · `first_scar.discovered`** (the
design's ASGARNIA_INTELLIGENCE_SECURED / FIRST_SCAR_DISCOVERED, as `Flags`) · the Damaged Temple
Knight Report kept as a re-readable lore item · Lord Daquarius established · the handoff to **The
White Wall** (Sir Amik). No equipment reward — the reward is campaign progression and the discovery.

## Development section

| Field | Old Wounds |
|---|---|
| Start NPC | Sir Tiffy Cashien, 2997,3373 (Falador Park bench) — auto-begins after A Matter of Trolls + The Guns of Asgarnia, or on talk |
| NPCs Used | Sir Tiffy Cashien · Lord Daquarius (stock 4929, owner-bound scene spawns) · Dark Warriors (stock, unchanged) — all existing |
| Locations | Falador Park · Dark Warriors' Fortress · the ruined ring south-east of it (the First Scar) · the real Wilderness — unchanged |
| Gameplay Used | The Wilderness and its PvP (`PvpZones`) · the stock crate search · `NpcTalk` · `Flags` · War Effort · the quest framework's areas / counters / Read verbs |
| Dialogue | Tiffy ×4 beats (brief, report, return, reminders) · Daquarius ×2 scenes + idle · two four-page documents · narration at the fortress and the Scar |
| Quest State | `QuestStates` blob (`old_wounds`: step id + counters `daq_fortress`, `daq_scar`) · flags `quest.old_wounds.done`, `asgarnia.intelligence_secured`, `first_scar.discovered` |
| Journal | table above; varp 4696; native row Wanted! (varp 1051) |
| System Hooks | `NpcTalk` (Tiffy, Daquarius) · quest poll (`ReachArea` ×5) · `CrateSearch` (new, over `SearchCratesPlugin`) · `onItemOption(read)` ×2 · a 2-tick sweep (`LordDaquarius.sweep`, cache re-issue) · `Flags` |
| Temporary Content | Lord Daquarius, twice, owner-bound and removed at the end of each scene (or on logout / after ~5 min) |
| New NPCs | NONE |
| New Maps | NONE |
| World Changes | NONE (no object is added or moved; the ruins are read, not edited) |
| New Mechanics | NONE (`CrateSearch` and `LordDaquarius` are seams over existing engine behaviour) |
| Development Cost | **Low–Medium** (Script + Light Custom: two relabelled note defs) |

## Operator steps after merge

1. **Quest cache relabel** (native quest tab): Actions → *Quest cache relabel* → `relabel`, then `hide`
   — adds the "Old Wounds" row (Wanted! relabelled). Until then the stock tab lacks the row; the
   client Quest Journal and `::quests` / `::oldwounds` work regardless.
2. **Item def cache edit** → `oldwounds`: renames the "Orders note" (28431) to *Kinshra field orders*
   and the "Intel report" (761) to *Damaged Temple Knight report* with their examines (~90 s restart).
   Until then the pack shows the stock names; the server-side names / examines / untradeable rule
   apply regardless.
3. **Client:** the `ship-client` workflow runs on the push (client/ changed): the journal entry and
   varp. Old clients show no journal entry until they update.
4. **Smoke test** (needs both prerequisites complete, or `::questdebug begin old_wounds`): Tiffy brief
   → walk or teleport to the Wilderness, enter the fortress hall (narration) → Search the crate at
   3026,3628 → the four pages, "Lucien?", the journal line, Daquarius appears (only you see him) and
   leaves → Tiffy report (ask about Lucien) → the ring south-east of the fortress: arrival lines →
   stand by the south pillars → the centre rock ("What happened here?") → the north-west rubble →
   the report opens itself, the reflection lines, Daquarius again → Tiffy return → complete, the
   campaign board prints, +75 WE, `::questdebug flags` shows both flags. Also: close a Daquarius
   scene mid-way and re-read the document from the pack on site (it replays); bank the orders and
   talk to Tiffy (sent to fetch); drop the report and stand at the rubble (re-issued);
   `::questdebug varps` before/after must leave the legacy chains untouched.
