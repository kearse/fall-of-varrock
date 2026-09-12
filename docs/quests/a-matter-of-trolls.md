# A MATTER OF TROLLS

**Regional Campaign: Asgarnia — BREACH · Campaign thread: Northern Front · Quest 2 of 5**
**Primary NPCs: Denulth, My Arm, Snowflake (Sir Amik bookends it) · Locations: Burthorpe → Death Plateau → Trollheim (above the Stronghold) → Weiss → Death Plateau · Quest type: exploration / diplomacy / combat**
**Development cost: Light Custom** (dialogue + quest state + area triggers + a per-player troll patrol + two hand-placed stock npcs + one existing-map coalition-battle instance; no new map, npc, item or mechanic)
**Status: BUILT 2026-09-12** (branch `claude/matter-of-trolls-quest-e310f5`, on main f5e1ce5a after The North #348 / First Reclamation #347)

**Primary purpose:** solve Asgarnia's northern manpower problem so Falador can commit east. After
At the White Wall, two independent problems open in parallel — this one (free the Imperial Guard)
and The Guns of Asgarnia (restore artillery). Neither gates the other; the Asgarnian finale (The
White Wall) needs both. And it plants the first breadcrumb strong enough that the player starts
asking who the original Adventurer was to them.

**Locked from the regional design and honoured here:** the hostile threat is a *post-Fall troll
splinter warband* and nothing else (no Sliske, Lucien, Zemouregal, or Fall mystery — "sometimes a
regional war is simply a regional war") · existing Troll Country relationships, not invented
factions · My Arm and Snowflake · the trolls are divided · a fixed human–troll coalition · it ends
in a Battle of the Pass · the northern frontier is stabilised · Imperial Guard manpower is freed.
The war-chief's final RuneScape-style name is still an open content detail: he is **"Troll
War-chief"** until it is chosen (one string in `BattleOfThePass`).

## Core implementation decision

**A framework quest** (`content/quests/asgarnia/AMatterOfTrolls.kt`, key `a_matter_of_trolls`,
chain slot 15, journal varp 4694, native row = the relabelled **Death Plateau** row). Every beat
sits on the existing world: Denulth in his stock Imperial Guard camp, the stock Death Plateau ground
and its stock trolls, a private copy of that same plateau for the battle. Two things had to be
placed by hand because the build genuinely lacks them — **My Arm** and **Snowflake**. The OSRS wiki
spawn dump's ids for both (740/8412-8417, 8432/8433) are multi-npc varbit placeholders in this
rev-228 cache (`npcDef inspect`: name `null`), so `WorldSpawnsPlugin` name-drift-skips them and
neither exists in the live world. Their real defs (`my_arm_8411`, `snowflake` 8431, both with
Talk-to) are hand-placed: that is reuse, not a new character, exactly as the design allows.

**Gate.** `Prerequisite.Custom`: **At the White Wall** (`at_the_white_wall`) once that key is
registered; until it lands, the deepest registered main-road quest before it (A Kingdom Alone →
First Reclamation → The North → The Last Free City), so the chain never dead-ends whatever merges
first. Auto-begins — Sir Amik's last White Wall line sends the player to Burthorpe, and the journal
already reads "Speak with Commander Denulth" when they arrive.

## Integration audit — every beat mapped to the build

| Beat | Existing NPC / place (unchanged) | Existing system | Code seam |
|---|---|---|---|
| Sir Amik sends the player north | **Sir Amik Varze** 2960,3336,2 — the stock Falador castle spawn is id **4771** (`npc.sir_amik_varze` is 1867, never spawned) | `NpcTalk`, quest-priority branches on THIS quest's steps only (At the White Wall owns his idle lines; a placeholder line keeps him from going mute if this lands first) | `talk(SIR_AMIK, start)` nudge · `talk(SIR_AMIK, report)` debrief |
| "Lumbridge." — Denulth's problem and the split | **Denulth** 4083 @ 2896,3528 (Imperial Guard camp, Burthorpe — stock spawn, presence-gated) | `NpcTalk` (`bindTalk` + default everyday branch in `AMatterOfTrollsPlugin`) | `talk(DENULTH, start)` → `satisfy` |
| Burthorpe stays Burthorpe | the stock town; two extra **Soldiers** (4086) at the forward post at the foot of the plateau (2866,3572 / 2870,3573) — "a few extra Imperial Guards" is the only addition | `spawnNpc` DSL | `FORWARD_POST_GUARDS`; their Talk-to carries the SCOUT hint |
| Recon: three observations | the ground north of the Warriors' Guild (forward post), the western path up (tracks), Death Plateau itself (arrival) | `Objective.Predicate` polled by the framework; each observation prints once (quest counters `obs_post` / `obs_approach`) | `AMatterOfTrolls.scoutPoll` — no clue models, area-triggered text only |
| The splinter patrol | Death Plateau's stock troll ground; **Mountain troll** (936) ×4 spawned around the player, renamed "Warband troll", aggro the owner only, loot as normal, swept after ~10 min | per-player temporary spawns (`TempSpawns`), `Objective.KillNpcs` filtered to the player's own patrol + damage-share credit | `PATROL.onEnter = spawnPatrol` · `AMatterOfTrollsPlugin.onAnyNpcDeath` |
| The neutral troll: "My Arm." | a **Burntmeat** def (4157 — the one plain troll def with Talk-to and *no* Attack, so it can't be killed) renamed "Troll scout", spawned beside the player when the patrol falls; the conversation starts itself | temporary spawn + auto-dialogue + a Talk-to branch that keeps offering the climb | `MY_ARM_STEP.onEnter = patrolBroken` · `talk(SCOUT_TROLL, my_arm)` · default `scoutIdle` |
| Travel to My Arm | the **Trollheim summit** 2891,3679 — where the stock Trollheim teleport spell/tab already lands. (The Death Plateau → Trollheim path needs the climbing-rock objects, which are not scripted, and My Arm's canonical roof is only reachable through the Stronghold interior — both verified on the collision dumps.) | the scout's "Take me to My Arm" = an existing-infrastructure teleport offered in dialogue — the design's own fallback rule for Weiss, applied one step earlier | `offerClimb` → `moveTo(TROLLHEIM_LANDING)` |
| Recognition scene · the warband · the ask | **My Arm** (hand-placed 8411) | dialogue; `satisfy` before the last beat | `talk(MY_ARM, my_arm)` |
| Travel to Weiss | Weiss 2869,3936 (stock town). Normal Weiss access (sled / icy basalt) is not operational in this build → **My Arm provides the quest travel option** exactly as the design specifies | dialogue teleport | `offerWeiss` |
| Snowflake's condition | **Snowflake** (hand-placed 8431 @ 2873,3934) | dialogue; no fetch | `talk(SNOWFLAKE, snowflake)`; her trolls "see you down" (travel back to Burthorpe / Trollheim) |
| The proposal, the agreement, the plan | Denulth | dialogue | `talk(DENULTH, denulth)` → READY |
| "Ready for the pass?" | Denulth | dialogue → the instance | `talk(DENULTH, ready)` → `satisfy` is the LAST call (the instance moves the player) |
| **The Battle of the Pass** | a private copy of **Death Plateau** (source 2848,3576–2879,3607, 4×4 chunks, unchanged) | `QuestInstances` + a scoped `HostileZone`-style skirmish loop; instanced deaths are already safe (`SafeDeaths`); companions already benched | `BattleOfThePass` — see below |
| After the battle | in the instance: Denulth spawned through the gap (stock def), My Arm and Snowflake already there | dialogue after the state change | `Battle.victory` → `epilogue` → `end(COMPLETE)` → exit at Burthorpe camp 2898,3533 |
| Report to Sir Amik | Sir Amik | dialogue; completion rewards | `talk(SIR_AMIK, report)` → complete: +50 War Effort, 2 QP, flag `asgarnia.northern_front_secured`, strategic update |
| Journal | client Quest Journal + stock quest tab | varp **4694** (generic packing); native row **Death Plateau** (dbrow 23, col0 58, varp 314, complete 80) | `QuestJournal.TROLLS_VARP` / `TROLLS_QUEST_VARP`; `QuestTablePatch.PLAN` "16 A Matter of Trolls" |

**Rejected options.** A new northern commander (Denulth exists, in place). A custom war-chief model
or mechanic (a Troll general def renamed, stronger hits, a big HP pool, two reinforcement calls).
Fifty npcs (5–6 guards, My Arm + 4, Snowflake + 4, 15 hostile trolls in three waves + the chief and
up to 4 reinforcements — the illusion is staged entry, three directions, overhead lines and the
recognisable characters fighting together). A fetch for Snowflake (her one condition is the safe-
passage order). A portal row / `TeleportRegistry` change for Troll Country (a client mirror + deploy
for a quest-travel need the dialogue already covers; the standing rule says every registry change
needs the `LofTeleportsData` mirror). A Quest Points system (`questPoints = 2` on the definition —
the registry-derived summary tab counts it). Extermination: some warband trolls flee at the
encirclement and at the end — "we are not exterminating Troll Country".

## The Battle of the Pass (`BattleOfThePass.kt`)

Geography (from the region 11319/11320 collision dumps): the walkable route from Burthorpe onto
Death Plateau climbs the *western* path, runs along the plateau's north band and enters through a
one-tile gap in its **north-east** corner — the only way down. So: the **Imperial Guard** (6 ×
Soldier 4086 renamed, att/str/def 55/50/55, hp 60) holds the gap; the player lands beside them
(2874,3597). The **warband** musters on the plateau's west half and presses the guard line in three
waves (6 / 5 / 4 mountain trolls, 55/62/42, hp 85). **My Arm** (8411, 80/85/70, hp 160) and 4
"Stronghold trolls" climb in over the southern cliff lobe to hit the flank; **Snowflake** (8431,
same stats) and 4 "Weiss trolls" (ice troll male/female, 65/70/45, hp 85) come down from the
north-western heights; the **War-chief** (Troll general 4120 renamed, 105/100/85, hp 320, speed 5)
enters with two guards once the warband is thinned *and* the player has their five, and calls two
reinforcements twice.

| Phase | Trigger | What happens |
|---|---|---|
| 1 Hold the pass | tick 4 | wave 1; "Hold the line!" |
| 2 My Arm arrives | 4 hostile deaths (or tick 250) | wave 2; My Arm's group from the south cliffs; "My Arm here!" / "Excellent timing!" / "My Arm was thinking!" / "Of course you were!" |
| 3 Weiss fighters | 9 deaths (or tick 450) | wave 3; Snowflake's group from the north-west; "Push them down!"; two of the warband turn and run |
| 4 The War-chief | 12 deaths + player's 5 kills (or tick 700) | chief + 2; "Humans make trolls weak!" / "No." / "Stupid make trolls weak." / "He has a point."; calls 2 more twice |
| Victory | chief dead (anyone's kill) | "The splinter warband breaks." / "…held by a much smaller Asgarnian force."; the rest flee; the quest moves to REPORT **before** the epilogue; Denulth walks up; epilogue; exit to Burthorpe |

Behaviour: enemies aggro the owner (engine aggro through a dead-simple `aggroCheck`) and fight
back any ally within 10 tiles, otherwise press toward the guard line; allies engage any enemy within
12 tiles, otherwise walk to their group's goal. All of it runs inside the guarded instance tick.
Player credit: `Objective.KillNpcs` filtered to the instance's tagged hostiles, **plus** damage-share
credit from the plugin's `onAnyNpcDeath` (an ally routinely lands the last hit; never double-counted
— the plugin skips kills the framework already credited to the player). Failure (death / leaving /
15-minute timeout / logout) ends the instance and puts the quest back on READY; Denulth's "Back up?"
re-opens it. `::questdebug instance end` for admins.

## Dialogue (shipped — spec text verbatim where it exists)

**Sir Amik (start).** "Burthorpe. Commander Denulth commands the Imperial Guard there. I sent word
ahead — he'll be expecting you."

**Denulth (start).** "Lumbridge." / "Does everyone know where I'm from?" / "You're carrying their
business halfway across Gielinor." / "People notice." / "Sir Amik sent me." / "Of course he did." /
"He needs soldiers." / "So do I." / "That seems to be a theme." / "Welcome to Asgarnia." / "We've kept
the mountain passes contained for years." / "Recently the raids changed." / "How?" / "More organized."
/ "More aggressive." / "And stranger." / "Stranger how?" / "Some of the dead trolls aren't ours." /
"They're fighting each other?" / "Looks that way." / "But I'm not sending men deeper into Troll
Country based on tracks and corpses." / "So you're sending me." → (SCOUT) "You learn quickly." /
"Death Plateau. North-west of town — past the Warriors' Guild, up the western path and around onto
the top…"

**Recon (area text).** *The Imperial Guard position shows signs of repeated troll attacks.* /
*Splintered stakes. A burned supply crate. Two graves — one of them fresh.* / *The damage is recent.*
· *Several sets of troll tracks cross the pass from different directions.* / *The tracks show signs
of fighting before reaching the human position.* / *Some of the blood on the rocks here is troll
blood.* · *Death Plateau. The tracks converge here — and something is moving between the rocks.* →
*Trolls! A patrol breaks from the rocks and comes straight for you.* → (4 down) *These trolls were
not attacking from the main Troll Stronghold force.* / *A troll steps out from behind the rocks. It
does not attack.*

**The troll scout.** "You kill bad trolls." / "That's what I was hoping." / "Bad trolls say all
trolls fight humans." / "And you don't?" / "Sometimes." / "Comforting." / "But not today." / "War
trolls say humans weak." / "Say trolls what talk to humans are weak too." / "They fight everyone." /
"Who leads them?" / "Big war-chief." / "Who can stop him?" / *(The troll thinks.)* "My Arm." / "Your
arm?" / "No." / "My Arm." / "...Right." / "My Arm on top of mountain. Above big troll house.
Thinking." → **Take me to My Arm** (→ Trollheim summit) · **I'll find my own way** ("Mountain path
need climbing boots. Trolls know other way. Ask again.")

**My Arm (recognition — not a joke until it lands).** *(My Arm stares.)* "You." / "..." / "Me?" /
"You come back." / "I don't think we've met." / "Yes we has." / "You help My Arm." / "You bring
goutweed." / "You go snow place." / "You help Snowflake." / *(genuinely happy)* "You is Adventurer."
/ "No." / "My name is [PLAYER]." / *(looks again, longer)* "...No." / *(steps closer)* "You not
Adventurer." / "That's what I said." / "But you is like Adventurer." / "Who was this Adventurer?" /
"Human." / "That narrows it down." / *(ignores the joke)* "Good human." / "Help My Arm grow
goutweed." / "Help My Arm go Weiss." / "Help Snowflake." / "Help My Arm marry Snowflake." / "They did
all that?" / "Yes." / "Why did you think I was them?" / *(struggles)* "My Arm not know." / "You
just... same." / "That's slightly unsettling." / "You less wrinkly." / "Thanks." / "Probably." —
no eye colour, hair, face, height, gender presentation, voice or physical resemblance: just
recognition. Then the warband ("Stupid trolls." / "That sounds judgmental." / "They very stupid."),
why the trolls are divided (post-Fall patrols gone; cooperation vs. "humans weak now"; the war-chief
collecting the stupid ones; what the warband hurts), "So you're going to help Burthorpe?" / "No." /
"Oh." / "Burthorpe help My Arm." / "That's going to be a difficult conversation." / "You do
conversation." / "Of course I do." / "Need more trolls. Snowflake has strong trolls." / "You want me
to go ask your wife for an army?" / "Yes." / "You're not coming?" / "My Arm busy." / "Doing what?" /
"Thinking." → (SNOWFLAKE) **Send me to Weiss** / **Not yet**.

**Snowflake.** "My Arm said a human was coming." / *(She looks at you.)* "Oh." / "What?" / "Nothing."
/ "Everyone keeps doing that." → optional **"My Arm thought I was someone else."** ("I can see why."
/ "Who were they?" / "A friend." / "A very strange one." / "That seems to be the only kind I hear
about.") — lineage never revealed. "He attacks Weiss scouts." / "He attacks Stronghold trolls." / "He
attacks humans." / "He seems committed." / "He is building one large warband." / "If he wins the
pass, more trolls join him." / "So you'll send fighters?" / "On one condition." / "Humans see trolls."
/ "Humans shoot trolls." / "Historically, that hasn't been completely unreasonable." / "Today it
is." / "Burthorpe must agree: allied trolls entering the pass will not be attacked by the Imperial
Guard. Get me that, and Weiss marches." → (DENULTH) "Go and tell the human commander. My trolls will
see you down the mountain." → **Walk me down to Burthorpe** / **Take me to My Arm on Trollheim** /
**I'll stay a while**.

**Denulth (proposal).** "I found your problem." / "I was hoping you'd kill it." / "More complicated
than that." / "It usually is." / "The warband is attacking other trolls too." / "There are trolls
willing to help us stop them." / *(Denulth stares.)* "You want me to bring trolls into an Imperial
Guard position." / "Yes." / "Deliberately." / "Yes." / "..." / "Amik is going to blame me for this."
/ "If they attack the warband from the mountain side..." / "...and we hold the southern pass..." /
"They're trapped." / "Or we are." / "You're very encouraging." / "Occupational habit." / the
safe-passage order and the three-force plan → (READY) "Get whatever you need. Food. Gear. Then tell
me you're ready, and we go up together." → **I'm ready. Let's go.** ("Then let's not keep the trolls
waiting.") / **Not yet.**

**After the battle (in the instance).** *(Denulth looks over the allied trolls.)* "I never thought
I'd be grateful to see that many trolls coming down a mountain." / "You welcome." / "I didn't say
welcome." / "You mean it." / *(Denulth chooses not to argue.)* "You fought well." / "Thank you." /
*(My Arm looks at you again.)* "Adventurer fight like that too." / *(Snowflake looks at him.)* "My
Arm." / "What?" / "Later." / *(You notice. Nobody explains.)* / "Right. Back down to Burthorpe. Sir
Amik hears this from you — not from my report."

**Sir Amik (debrief).** "Denulth says the passes are quiet." / "For now." / "For now is enough." /
"What happens to the Imperial Guard?" / "They stop spending every waking hour staring north." /
"We'll leave enough men to hold Burthorpe." / "The rest can move south." / "To the Kinshra front?" /
"Yes." / "Not all at once." / **QUEST COMPLETE** / "But for the first time in years..." / "...I have
soldiers I can move." Strategic update: *ASGARNIA — BREACH. Northern Frontier: SECURED. Imperial
Guard Manpower: AVAILABLE. Artillery Production: unresolved. Kinshra Front: active. Temple Knight
Intelligence: unresolved. BREACH: incomplete.*

After the quest: Denulth ("Quiet. For now." / "That many trolls coming down the mountain... I still
don't like it. But it worked."), My Arm ("Snowflake say 'later'. My Arm still waiting for later." +
travel), Snowflake ("My Arm talks about the Adventurer again." / "Later, I told him." + travel) —
the campaign dialogue and the Trollheim / Weiss / Burthorpe quest travel stay unlocked.

## Quest journal (server step objectives = client journal rows)

| State (step id) | Journal |
|---|---|
| START (`start`) | Sir Amik says Imperial Guard soldiers are tied down defending Burthorpe. Speak with Commander Denulth in Burthorpe. |
| SCOUT (`scout`) | Scout Death Plateau and investigate the changing troll attacks. |
| PATROL (`patrol`) | Defeat the hostile troll patrol. (n/4) |
| MY_ARM (`my_arm`) | Find My Arm around Troll Stronghold and ask about the splinter warband. |
| SNOWFLAKE (`snowflake`) | Speak with Snowflake in Weiss. |
| DENULTH (`denulth`) | Arrange safe passage for the allied trolls with Denulth. |
| READY (`ready`) | Tell Denulth when you are ready for the Battle of the Pass. |
| BATTLE (`battle`) | Fight beside the Imperial Guard and allied trolls — hold the pass. (n/5) |
| WAR_CHIEF (`war_chief`) | Defeat the troll War-chief and break the splinter warband. |
| REPORT (`report`) | The hostile troll warband has been broken and the northern frontier is stable. Report to Sir Amik in Falador. |
| DONE | The northern frontier is stable. Imperial Guard manpower can now reinforce Falador's war against the Kinshra. |

`::trolls` prints the current objective and opens the Quest Journal on the quest.

## Rewards

**2 Quest Points** · **50 War Effort** · flag **`asgarnia.northern_front_secured`** (`AMatterOfTrolls.NORTHERN_FRONT_FLAG`
— the Northern Front strategic flag the Asgarnian finale reads; The Guns of Asgarnia sets
`asgarnia.artillery_restored`) · the framework's `quest.a_matter_of_trolls.done` · My Arm / Snowflake
campaign dialogue · the Trollheim ↔ Weiss ↔ Burthorpe quest travel (dialogue) stays available.
Normal Troll Country gameplay is not gated behind any of it.

## Development section

| Field | A Matter of Trolls |
|---|---|
| Start NPC | Commander Denulth, 2896,3528 (Imperial Guard camp, Burthorpe — stock spawn); the quest auto-begins after At the White Wall and Sir Amik's nudge points there |
| NPCs Used | Sir Amik Varze (4771) · Denulth (4083) · Soldier (4086: forward post + battle guards) · Mountain troll (936: patrol, warband, Stronghold fighters) · Burntmeat def (4157: the neutral scout) · My Arm (8411, hand-placed) · Snowflake (8431, hand-placed) · Ice troll male/female (1875/1876: Weiss fighters) · Troll general (4120: the war-chief) — all existing cache npcs |
| Locations | Falador castle · Burthorpe · the ground north of the Warriors' Guild · Death Plateau · the Trollheim summit · Weiss — all unchanged; the battle is a private copy of Death Plateau |
| Gameplay Used | Presence-gated world spawns · the stock troll ground · `QuestInstances` (existing map instance) · NPC-vs-NPC skirmish (the `HostileZone` / goblin-camp pattern) · War Effort · quest points · existing Trollheim teleport landing |
| Dialogue | Denulth ×5 beats + everyday · scout ×2 · My Arm ×4 + everyday · Snowflake ×3 + everyday · Sir Amik ×3 · soldiers · recon text · battle overhead lines · epilogue |
| Quest State | Framework `QuestStates` blob (`a_matter_of_trolls`: step ids above + `obs_post` / `obs_approach` counters); completion flag `quest.a_matter_of_trolls.done` + `asgarnia.northern_front_secured` |
| Journal | table above; client `LofQuest.A_MATTER_OF_TROLLS` (varp 4694, generic packing, chain slot 15); native tab = Death Plateau relabelled (varp 314, complete 80, sort key "16 A Matter of Trolls") |
| System Hooks | `NpcTalk` (Denulth, My Arm, Snowflake, scout, soldiers, Sir Amik quest branches) · `QuestEngine` poll (`Predicate`) · `onAnyNpcDeath` (damage-share credit) · `QuestInstances` tick/end · world timer (temp-spawn sweep) · `onLogin` (mid-battle logout fallback) |
| Temporary Content | The per-player warband patrol (4 mountain trolls, ~10 min) and troll scout (~30 min) on Death Plateau; every npc of the battle instance |
| New NPCs | NONE (My Arm and Snowflake are stock defs hand-placed; the war-chief is a renamed Troll general) |
| New Maps | NONE (the battle instance copies Death Plateau unchanged) |
| World Changes | NONE permanent beyond two Soldiers at the forward post, My Arm on the Trollheim summit and Snowflake in Weiss |
| New Mechanics | NONE |
| Development Cost | **Light Custom** — the coalition-battle script over an existing-map instance |

## Coordination (the Asgarnia campaign is being built in parallel)

Contract agreed with the At the White Wall (Q1), The Guns of Asgarnia (Q3) and Old Wounds (Q4)
sessions: keys `at_the_white_wall` / `a_matter_of_trolls` / `guns_of_asgarnia` / `old_wounds` /
`the_white_wall`; chain slots 14 / 15 / 16 / 17 / 18; journal varps 4693–4697; PLAN sort keys are
slot + 1 ("15 At the White Wall", "16 A Matter of Trolls", "17 The Guns of Asgarnia", "18 Old
Wounds"); each PR adds only its own constants and the last to merge re-checks the client `LofQuest`
enum order against `QuestBook` and bumps `LAST_INDEX`. Sir Amik: quest-priority branches on
`npc.sir_amik_varze_4771` only — his everyday lines belong to At the White Wall. Old Wounds requires
this quest AND The Guns of Asgarnia and reads `asgarnia.northern_front_secured` for a Tiffy line.

## Operator steps after merge

1. **Quest cache relabel** (Actions → *Quest cache relabel* → `relabel`, then `hide`): adds the
   "A Matter of Trolls" row (Death Plateau relabelled). Until then the stock tab lists no row for it;
   the client Quest Journal works regardless.
2. **Client:** the `ship-client` workflow runs on the push (client/ changed); launchers auto-update.
   Until the client updates, the journal shows no entry for the quest (the server side is unaffected).
3. **Smoke test** (admin: `::questdebug begin a_matter_of_trolls` skips the gate): Denulth → walk
   north past the Warriors' Guild (forward-post text) → up the western path (tracks text) → onto the
   plateau (patrol of 4 spawns and attacks; fight beside the ambient trolls) → the scout appears and
   talks; take the climb → My Arm on the Trollheim summit (recognition; "Send me to Weiss") →
   Snowflake (condition; "Walk me down to Burthorpe") → Denulth (plan) → Denulth "I'm ready" → the
   instance: hold the gap, My Arm from the south, Snowflake from the north-west, the War-chief; die
   once on purpose to confirm READY + Denulth's "Back up?"; win → epilogue → exit at the Burthorpe
   camp → Sir Amik debrief → complete (50 WE, 2 QP, flag). `::trolls` and `::quests` track it;
   `::questdebug flags` shows `asgarnia.northern_front_secured`.
