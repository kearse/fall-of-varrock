# THE LAST FREE CITY

**Main Story Quest 1 · Act I: The Last Free City · Location: Lumbridge · Starting rank: Peasant**
**Development cost: Script** (dialogue + journal copy + one added state + one Slayer target swap + one PK bot removed)
**Status: BUILT 2026-09-11** (branch `claude/quest-line-implementation-ea99eb`)

**Primary purpose:** throw the player directly into Fall of Varrock's war, then use the existing
Recruit Trials systems as the aftermath of that attack.

## Core implementation decision

**The Last Free City is the player-facing story quest. `RecruitTrials` remains the underlying
implementation.** No second quest system was built; the chain's state machine, hooks, rewards and
timers are unchanged. What changed is the framing, the battlefield, the first Slayer target, and
one extra state so the story has an ending.

The design authority (`master-story-and-quest-plan.md` §8) requires only: Peasant start · a
Lumbridge probe attack · Recruit Trials folded into it · a one-time story event, not the old
General Zo siege · the player sees/joins the war quickly. Everything beyond that was chosen for
what is cheapest in the current build.

## Integration audit — every beat mapped to the build

| Beat | Existing NPC / place (unchanged) | Existing system | Code seam |
|---|---|---|---|
| Opening (video → style → alarm) | Staging tile 3218,3218 | `FirstLoginFlow` VIDEO→STYLE→DONE | `FirstLoginFlow.onStyleConfirmed` → `RecruitTrials.greet` — **no change** |
| Sergeant sounds the alarm, hands out kit | **Sergeant Damien** 3217,3220 (spawned by `RecruitTrialsPlugin`) | `NpcTalk` default branch | `RecruitTrialsPlugin.alarm` → `RecruitTrials.grantMusterKit` + `advanceTo(FIGHT)` |
| The east camp fight | **East Lumbridge goblin camp** 3254,3234 — Knights of Lumbridge + ambient goblins (`GoblinCampPlugin`, presence-gated) | `RecruitTrials.Step.FIGHT` kill counter (`onAnyNpcDeath`) | Tutorial pack moved here (`RecruitTrials.TUTORIAL_GOBLIN_TILES`); credit = every FIGHT-step player in the goblin's `damageMap`, so knights out-damaging a recruit can't strand them |
| "It was a probe" + lineage breadcrumb | Sergeant Damien | REPORT reward (10,000 gp + bronze kit) | `RecruitTrialsPlugin.report` → `grantReportReward` (REPORT→RANK) |
| First rank = recognition | **Duke Horacio** 3220,3211 | Feudal ranks (`RankPurchase`, `RankEvents.onRankBought`) | `DukeHoracioPlugin.ensureIntro` (RANK-step opener) + `RecruitTrials.onBuyRank` (RANK→SLAY) — **mechanics unchanged** |
| Cleanup contract | **Vannaka** 3222,3212 | Slayer war-contracts (kill credit by cache NAME → any goblin anywhere) | `SlayerPlugin.assignTask` SLAY branch: `npc.goblin` ×5 (was castle rats ×5); `onSlayerTaskComplete` (SLAY→MINE_BRIEF) |
| Re-arm the city | Vannaka → **The Mire** rock 3237,3189 / furnace 3237,3192 / anvil 3238,3196 | Mining, Smithing, `TRIAL_TIMER` pack poll | `SlayerPlugin.recruitMiningBrief` → `onMiningAssigned`; `pollTick` SUPPLY→SMELT→SMITH→DELIVER — **unchanged** |
| Supply the war | **Quartermaster** 3248,3193 (Mire post) | `SupplyDepot.credit` (War Effort + Realm Supplies + service ledger) | `WarlordsArmouryPlugin.recruitSupplyHandIn` → `onSupplyDelivered` (DELIVER→RETURN) — **unchanged** |
| Vannaka pays out | Vannaka | Steel finish + bank pack | `SlayerPlugin.recruitFinale` → `grantFinalReward` (RETURN→**DEBRIEF**) |
| Debrief: Varrock fell, the war lies north | Sergeant Damien | — | `RecruitTrialsPlugin.debrief` → `RecruitTrials.onDebriefed` (DEBRIEF→DONE, +50 War Effort) |
| Handoff | General Zo (marches, `::march`), Vannaka (War-Prep I) | Existing march system; `WarPrepChain` | Damien's last lines point at both; `SlayerPlugin.dialog` offers War-Prep I when DONE && War-Prep NONE; `WarPrepChainPlugin` login back-fill still begins it regardless |

**Rejected cheaper-looking options.** A stock Lumbridge guard as the alarm NPC (Damien is already
spawned and wired to first login — a guard would be *new* work). The Cook for the supply beat (the
Mire→Quartermaster loop is already the SUPPLY step; the Cook would need new state and hooks). A
special attack instance / wave (the camp's living skirmish already reads as the attack).
"Knight-Captain" as the march lead (no such NPC — General Zo already musters marches).

## The east goblin camp

- **PK bot removed entirely** from `GoblinCampPlugin` (loadout, respawn cooldown, `maintainPker`,
  stand-down handling, PvP-teaser framing). The camp is a dangerous-but-accessible PvE battlefield
  for new players, never a surprise PvP encounter. The wilderness bot ladder is untouched.
- **Kept:** ambient goblins, three Knights of Lumbridge, knight-vs-goblin skirmish, presence gating,
  knight respawn, camp location.
- **Guaranteed combat:** the always-on tutorial goblin pack (8, respawning) moved from the back woods
  (3193,3221) to the camp's outer ring (`RecruitTrials.TUTORIAL_GOBLIN_TILES`, offsets of 5–6 tiles
  from 3254,3234). The knights skip that pack (`RecruitTrials.isTutorialGoblin`, by spawn tile — the
  respawn path wipes attributes) so there is always something standing for a recruit to fight.
- No separate tutorial battlefield, no duplicate goblin location: the iconic Lumbridge goblins ARE
  the opening battle.

## Dialogue (shipped)

**Sergeant Damien — alarm (TALK → FIGHT).** "YOU! Over here!" / "The eastern post is being overrun.
Goblins pushed through near the old camp. Our knights are holding them, but they need every pair of
hands we've got." / *Player:* "I just got here!" / "Then you picked a bad day." / *(muster kit:
wooden shield equipped + 50 bronze knives)* "Here. You'll need these." / "Head east, across the
river. You'll see the camp. Five goblins have pushed into the position — help the knights put them
down." / *Player:* "You want me to fight them?" / "I want you to decide." / "You can stay here and
hope somebody else keeps Lumbridge standing... or you can stand with us."

**Sergeant Damien — report (REPORT → RANK).** "You're alive." "Better than that. You held." / *Player:*
"Was that the attack?" / "No. That was a probe." "They pushed fighters against the eastern post to
see how quickly we'd respond. How many guards we'd move. Where the weak points were." / *Player:* "So
they're coming back?" / "They always come back." "...You know, for a second out there you reminded me
of someone." / *Player:* "Who?" / "Doesn't matter. We've got work to do." "Standing your ground once
doesn't make you a soldier. But it earns you the chance to become one." "The army lost weapons and
supplies today. If you want to keep helping, I'm putting you on the muster roll." / *Player:* "What do
I need to do?" / "First, take your pay." *(10,000 gp + bronze kit)* "Then go and see Duke Horacio in
the market. You stood for Lumbridge today. He'll recognise the service."

**Duke Horacio (RANK).** "Damien sent word ahead. He says you stood with the defenders at the eastern
camp." / *Player:* "I did what I could." / "And Lumbridge survives because enough people still do. I
am Duke Horacio, lord of Lumbridge." / "Every citizen begins a Peasant. Service to the realm earns
something greater…" *(existing ladder explanation)* / "You stood at the eastern camp when the line
broke. That is service, and service is what rank is FOR. Shall I raise you to Commoner? It costs
10,000 coins — the Sergeant's pay covers it." → **PEASANT → COMMONER** (+ steel full helm).

**Vannaka (SLAY).** "So you're Damien's new recruit. Heard you fought at the east camp. I'm Vannaka —
I sign the war-contracts." / *Player:* "The Duke told me to take a war contract." / *(contracts
explained)* / "Then we'll see if you can finish what you started. The knights broke their attack,
but some of the goblins scattered into the countryside." / "Find them. Don't let them regroup for
another push. Kill 5 Goblins — they're loose over the fields east of the castle and back toward the
camp."

**Vannaka (MINE_BRIEF → SUPPLY).** "The stragglers are dealt with. But killing them isn't the only
work today's left us." / *Player:* "What happened?" / "Weapons broke. Food was spent. Equipment needs
replacing. Every battle empties the stores, whether we win or lose." / "You want to know how
Lumbridge keeps surviving? Resource contracts…" / "Go to The Mire… Mine what you need: copper and
tin. Smelt a bronze bar at the furnace. Then smith something useful — hammer it into a bronze dagger
at the anvil." / "Then put it in the Quartermaster's hands…" *(steel platebody + pickaxe + hammer)*

**Quartermaster (DELIVER).** "A finished blade, forged from rock you dug yourself. Today's fight cost
us weapons — THIS is how they get replaced. I've logged it as War Effort." / "Fighters keep the
enemy away; skillers keep the fighters armed. The stores are a little fuller. Now report back to
Vannaka."

**Vannaka (RETURN → DEBRIEF).** "Back — and the Quartermaster's logged your dagger. The stores are a
little fuller than they were this morning." / *(steel scimitar + legs + kite; bank pack)* / "Now go
and report to Sergeant Damien. He'll want to hear it from you."

**Sergeant Damien — debrief (DEBRIEF → DONE).** "Look at you. This morning you were a Peasant." /
"Then the horns sounded." / "You fought when you could've run. You hunted down what got through.
You replaced what the army lost." / *Player:* "Is Lumbridge safe now?" / "No." / "But it's still
ours. Varrock couldn't say the same." / *Player:* "What happened there?" / "Twelve years ago, Varrock
fell." / "What remains of Misthalin has been fighting ever since to make sure the same thing doesn't
happen here." / "Today's attack wasn't meant to take Lumbridge. They were testing us. Someone wanted
to know how quickly we'd bleed." / *Player:* "Then maybe we shouldn't wait for the next attack." /
**QUEST COMPLETE** (+50 War Effort) / "Maybe you're learning." / "General Zo musters the columns
that march north against the enemy — you'll find him in the castle courtyard. When you hear the
call for the next March... answer it." / "Until the horns sound again, Vannaka has drills for you.
The front's mages will melt a soldier who can't pray — go and see him."

## Quest journal (server objective lines = client step rows)

| State | Journal |
|---|---|
| TALK | Lumbridge is under attack. Sergeant Damien is calling for every pair of hands by the castle gate. |
| FIGHT | Help the Knights of Lumbridge defeat the goblins attacking the east camp. [X/5] |
| REPORT | The attack has been pushed back. Report to Sergeant Damien. |
| RANK | I stood for Lumbridge. Duke Horacio can recognise my service and grant my first rank. |
| SLAY | Vannaka wants the surviving attackers hunted down before they regroup. |
| MINE_BRIEF | The stragglers are dealt with. Report back to Vannaka. |
| SUPPLY | The defence consumed equipment and supplies. I need to help replace them in The Mire — mine copper and tin. |
| SMELT | Smelt the copper and tin into a bronze bar at the furnace in The Mire. |
| SMITH | Smith the bronze bar into a dagger at the anvil in The Mire. |
| DELIVER | Deliver the weapon I made to the Quartermaster for the War Effort. |
| RETURN | The Quartermaster has my dagger. Report back to Vannaka. |
| DEBRIEF | The immediate danger has passed. Report to Sergeant Damien. |
| DONE | Lumbridge survived the probe. I entered the day a Peasant and ended it in the service of the last free city. The war lies north. |

Chat prefix: `The Last Free City: goblins defeated 1/5` (was `Recruit Trials: goblins killed 1/5`);
on the fifth: `…The attackers begin falling back.` then the REPORT objective.

## Rewards (distributed through play — no final reward screen)

Wooden shield + 50 bronze knives · 10,000 gp · full bronze kit → full steel set piece by piece ·
**Peasant → Commoner** · first war contract · first War Effort (5 at the hand-in, 50 on completion)
· the supply loop and the Quartermaster · bank pack (10k, food, potions, Book of Commands) · the
lineage breadcrumb · handoff to General Zo's marches and Vannaka's War-Prep I.

## Development section

| Field | The Last Free City |
|---|---|
| Start NPC | Sergeant Damien, 3217,3220 (inside the Lumbridge gate) — auto-triggered by `FirstLoginFlow` |
| NPCs Used | Sergeant Damien · Knights of Lumbridge · goblins · Duke Horacio · Vannaka · Quartermaster (Mire post) · General Zo (named only) — all existing, all in place |
| Locations | Lumbridge gate · east goblin camp 3254,3234 · market (Duke/Vannaka) · The Mire · Mire crypt post — unchanged |
| Gameplay Used | First-login flow · frontier goblins + camp skirmish · feudal ranks · Slayer war-contracts · Mining/Smithing · Supply Depot / War Effort · marches (handoff) · War-Prep chain (handoff) |
| Dialogue | Damien ×3 beats rewritten (+ per-step nudges) · Duke intro + rank offer · Vannaka intro / contract / brief / finale · Quartermaster hand-in · Damien debrief (new) |
| Quest State | `RECRUIT_TRIAL_STEP_ATTR` (persisted ordinal) + `RECRUIT_GOBLIN_KILLS_ATTR`; **DEBRIEF added** (declared after DONE to keep saved `11 = DONE` valid; wire ordinal remapped by `RecruitTrials.clientOrdinal` so the client sees 11 debrief / 12 done) |
| Journal | table above; client `LofQuest.LAST_FREE_CITY` (varp 4610); native tab row = Cook's Assistant relabelled (`QuestTablePatch` PLAN, varp 29) |
| System Hooks | `onAnyNpcDeath` (FIGHT, damage-share credit) · `RankEvents.onRankBought` (RANK) · `SlayerPlugin.onKill` (SLAY) · `TRIAL_TIMER` pack poll (SUPPLY/SMELT/SMITH) · `SupplyDepot.credit` (DELIVER) · `WarPrepChain` (handoff) |
| Temporary Content | None. (The always-on tutorial goblin pack is permanent ambient content, relocated.) |
| New NPCs | NONE |
| New Maps | NONE |
| World Changes | NONE permanent. The camp's PK bot is removed; the tutorial pack moved from the back woods to the camp. |
| New Mechanics | NONE |
| Development Cost | **Script** |

## Operator steps after merge

1. **Quest cache relabel** (native quest tab): Actions → *Quest cache relabel* → `relabel`, then
   `hide` — the row is renamed "The Last Free City". Until then the stock tab still reads "Recruit
   Trials" (colours already correct).
2. **Client:** the `ship-client` workflow runs on the push (client/ changed); launchers auto-update.
   Old clients briefly mis-render a player *on the DEBRIEF step* as finished (wire ordinal 11 ≥ old
   done 11) — cosmetic, self-corrects on client update.
3. **Smoke test on a fresh account:** alarm → shield equipped, knives in pack → east camp kill 5
   (fight beside a knight to confirm damage-share credit) → Damien pays → Duke offers Commoner →
   Vannaka assigns 5 goblins → brief → Mire loop → Quartermaster → Vannaka → Damien debrief → quest
   complete; then Vannaka offers War-Prep I. `::trials` and the Quest Journal (`::quests`) track it.
