# THE NORTH

**Main Story Quest 3 · Act: Early Main Campaign · Primary NPCs: General Zo, Oziach · Location: Edgeville**
**Quest type: exploration / reconnaissance / story · Development cost: Light Custom** (dialogue + quest
state + two area triggers + one relabelled note item; no new NPC, map, enemy, mechanic, teleport or instance)
**Status: BUILT 2026-09-11** (branch `claude/the-north-quest-ae52bb`, stacked on The Last Free City, PR #346)

**Primary purpose:** the first two quests teach the player how the realm fights (*the war comes to you* ·
*you go to the war*). The North is where the player **finally sees what the Fall of Varrock did to the
kingdom** — and where the main campaign starts shifting from onboarding toward exploration, atmosphere,
history, Fallen Varrock, the Rogue Knights, the northern frontier and the mystery behind the Fall.

The player should finish it understanding: Varrock's fall affected far more than Varrock · Misthalin lost
its roads and territory in the north · Rogue Knights and other hostile groups filled the vacuum · Edgeville
survived as the important northern settlement — and is the realm's **PKing hub**: the last bank before
the ditch, where players gear up and cross to fight each other · the Wilderness begins at the normal
boundary north of Edgeville · Rogue Knights are dangerous NPC enemies, the Wilderness adds a different danger (**other
players**) · the accepted story says Zemouregal and Arrav destroyed Varrock, and something about it may
one day deserve a closer look · the kingdom cannot reclaim Varrock until it reclaims the ground toward it.

## Core implementation decision

**A framework quest** (`content/quests/north/TheNorth.kt`, `QuestDefinition` key `the_north`) — the first
one on the main road, so it also proves the framework's journal/native-tab path for the quests that
follow (First Reclamation, A Kingdom Alone are being built against the same contract). Everything is
dialogue and state over the existing world: Oziach stands where he always has, the Wilderness ditch is
the stock ditch, travel is whatever the player already has. The only custom asset is the dispatch, and
even that is an existing note def with a new name.

**Gate.** The design says "after First March", and since 2026-09-13 that is exactly the prerequisite:
`Prerequisite.QuestComplete("first_march")` ([first-march.md](first-march.md)). (Until First March was
built the gate was a `Prerequisite.Custom` — First March once registered, The Last Free City until
then — so the chain never dead-ended.) The quest **auto-begins** the moment its gate opens (login,
rank-up, or the framework poll — `QuestEngine.pollTick` auto-begins eligible `autoBegin` quests
mid-session, so Zo's First March debrief starts The North on the spot).

## Integration audit — every beat mapped to the build

| Beat | Existing NPC / place (unchanged) | Existing system | Code seam |
|---|---|---|---|
| "You said I should see Edgeville." | **General Zo** 3220,3210 (castle courtyard, `GeneralZoPlugin`) | `NpcTalk` — Zo migrated from a raw `onNpcOption` to `bindTalk` + a default branch (his march/muster/War-Prep III menu is untouched) | `TheNorth.talk(ZO, "brief")` → `QuestEngine.satisfy` |
| Travel north | Any existing travel: walk (Rogue Knights may cross the road — desirable, never required), **amulet of glory → Edgeville** (`AmuletOfGloryPlugin`, lands 3087,3496) | no quest teleport, no custom road | step `edgeville` = `Objective.ReachArea(Edgeville town box 3067,3488–3098,3522)` (= PvpZones' safe carve-out) |
| Arrival lines | Edgeville itself | chat narration, no cutscene | `edgeville.onLeave` → "Edgeville. Northern Misthalin." / "The Wilderness lies just beyond the town. Varrock lies to the east." |
| Someone who remembers the Fall | **Oziach** 3069,3517 — his stock spawn (`npc_spawns.json` 822), presence-gated by `WorldSpawnsPlugin`, in his hut at the town's NW edge. Verified spawned in the data; nothing spawns or moves him | `NpcTalk` (`TheNorthPlugin.bindTalk("npc.oziach")` + everyday lines at default priority) | `talk(OZIACH, "contact")` |
| The Wilderness line | The stock **Wilderness ditch** north of Edgeville (z 3521-3522; `WildernessDitchPlugin` handles the jump as before) | a small area trigger immediately SOUTH of the ditch — the player is never asked to cross | step `wilderness` = `ReachArea(3074,3516–3104,3520)` (starts east of Oziach's hut so talking to him can't trip it); `onLeave` → the four boundary lines |
| Oziach at the boundary / The Fall / the dispatch | Oziach | item hand-out + in-dialogue reading (`messageBox` pages) | `talk(OZIACH, "return_oziach")`: give → `satisfy("return_oziach")` back-to-back (dupe-proof) → read → `satisfy("dispatch")` → reaction + lore + "take it to Zo" |
| Read the dispatch | the **Weathered Varrock Dispatch** = stock "Old note" def **25829** (chosen because its cache def already carries the **Read** + Drop pack verbs — the "Message" notes only have Drop, verified with `itemDef inspect`) | pack **Read** option (bound defensively — a def without the verb just logs) | `TheNorthPlugin.onItemOption(DISPATCH, "read")` → pages; on the `dispatch` step also clears it |
| Debrief | General Zo | dialogue; completion reward | `talk(ZO, "return_zo")` → requires the dispatch in the pack (bank/lost handled) → `satisfy` completes: +15 War Effort |
| Journal | client Quest Journal + stock quest tab | varp **4686** (generic framework packing); native row = relabelled **Ernest the Chicken** (dbrow 44, varp 32, complete 3) | `QuestJournal.NORTH_VARP`; `TheNorth.nativeTabVarp` → `QuestEngine.publish` (new generic native-tab mirror on `QuestDefinition`) |

**Rejected options.** A quest-only Edgeville teleport or portal row (the design forbids it; glory and the
roads already reach Edgeville). A custom warning sign or object at the ditch (an area trigger costs
nothing and needs no cache edit). A cutscene on arrival (the recognisable place does the work). A
scripted Rogue Knight ambush (emergent encounters on the road are the point — nothing is spawned).
Punishing the loss of the dispatch (the "don't bother coming back" line is a joke: Oziach re-issues it;
banked → he sends you to fetch it; Zo won't debrief without it). A bespoke quest-point system (the
1 QP is declared as `questPoints = 1` on the definition — A Kingdom Alone's PR derives the summary
tab's total from the registry, so it counts once that lands; nothing is invented here).

## World rule — as the design states it, and as the build stands today

The design: real PvP begins at the normal OSRS Wilderness line north of Edgeville; Edgeville is safe; the
broad Misthalin world south of the Wilderness is not PvP territory; Rogue Knights remain active
throughout the world as the bridge between PvE and real PvP.

The build today (`combat/PvpZones.kt`): Edgeville is a safe carve-out up to the ditch ✓, but the custom
red wilderness still starts at the **top of Lumbridge** (z 3258), so the road from Lumbridge to Edgeville
is PvP ground and Rogue Knights (`PkBot`) live only in that red. **This quest does not change the world
rule** — that is the Rogue Knights / PvP programme's call (a parallel session holds it). The quest's
boundary text is written to the design's rule and stays true whichever way the line moves: "Beyond this
line, other adventurers can attack you too." Until the rule is applied, the glory teleport is the
safe route north.

## Dialogue (shipped — spec text verbatim where it exists, joined only to save clicks)

**General Zo — brief (`brief`).** Zo opens — the quest auto-begins, so nobody has mentioned Edgeville
to the player before this. "Good. I've an errand for you, {address}." / *Player:* "Another march?" / "No
sword needed for this one. Go to Edgeville." / "What's there?" / "Perspective." / "That sounds ominous." / "It usually is." / "You've seen Lumbridge attacked.
You've marched with our Knights. You've even watched us win a field." / "If that's all you saw, you might
start thinking we're winning." / "We aren't?" / "We're surviving. There's a difference." / "Go to
Edgeville. Look at what remains between us and Varrock." / "Then come back and tell me what you think
we're actually fighting for." → options **"That's the whole assignment?"** ("Go. Look. Come back. Not
every lesson needs a sword.") · **"Why Edgeville?"** ("Because it survived." / "So did Lumbridge." /
"Lumbridge still has a kingdom behind it. Edgeville has the Wilderness behind it." / "And Fallen Varrock
in front of it.") · **"I'll go."** (→ `edgeville`).

**Oziach — first conversation (`contact`).** "What?" / "General Zo sent me." / "Then General Zo can come
himself." / "He told me to see what happened to the north." / "Did he? Then he wants you frightened." /
"I don't think he said that." / "Generals rarely do." / "Were you here when Varrock fell?" / "Aye." / "I
was here before it fell. I was here while it fell." / "And I've been here every miserable year since." /
"What happened to the north?" / "Varrock was the capital. Every road, every patrol, every coin in
Misthalin ran through it." / "Then Varrock fell. And everything that leaned on it came down after." /
"The patrols stopped. The roads emptied. Merchants changed routes. Farms were abandoned." / "Every thug
with a sword suddenly decided he was a warlord." / "The Rogue Knights?" / "Some of them. Deserters.
Mercenaries. Bandits. Opportunists." / "Call them whatever makes dying to one feel better." / "They're all
over the roads." / "Exactly." / "Are they the ones who took Varrock?" / "Zemouregal's dead? No. Most of
them have nothing to do with him. That would almost be simpler." / "Zemouregal?" / "The one whose dead
walked into Varrock. Nobody in Lumbridge gave you the name?" / "They told me Varrock fell." / "Aye. That's
the short version." *(Oziach is the first NPC in the game to say the name — all Lumbridge has told the
player is "twelve years ago, Varrock fell" — so it comes from him and the player reacts to it.)* / "Then
why are the Rogue Knights attacking everyone?" / "Because nobody stops them." / "Varrock kept order
through most of Misthalin. Nobody has kept it since." / "The people who prefer a
world without rules noticed." → (`wilderness`) "Come north." / "There's something else Zo expects you to
understand."

**The boundary (`wilderness`, on arrival).** *The road continues north into the Wilderness.* / *Rogue
Knights may attack travellers throughout the realm.* / *Beyond this line, other adventurers can attack you
too.* / *Everything learned fighting the Rogue Knights matters more on the other side.*

**Oziach — the Wilderness, the Fall, the dispatch (`return_oziach`).** "So that's the Wilderness." /
"That's the polite name." / "And the Rogue Knights stay south of it too." / "Of course. Lines on maps only
matter to people who respect them." / "But north of that line, players can attack me." / "Aye. That's
what this town is for now." / "Edgeville?" / "Look around. The bank sits thirty paces from the ditch. Every
fighter in the realm gears up here, walks north, and comes back richer or empty." / "This is where people
go to fight people. Nobody planned it. It's just the last safe ground before the wild, and everyone knows
it." *(Edgeville is the realm's PKing hub — the iconic RSPS PK spot — and Oziach says so in plain words.)*
/ "And if a Rogue Knight comes at me up there?" / "Then you know what he wants." / "And another adventurer?" / "Your guess is as good as
mine." / "Usually your armour." / "What does all this have to do with Varrock?" / "Everything." / "Before
Varrock fell, these roads belonged to a kingdom." / "Afterward? They belonged to whoever happened to be
standing on them." / "And Edgeville?" / "Edgeville stayed. Barely." / "Refugees came through here for
weeks. Soldiers too. Some still had weapons. Some didn't." / "Did anyone know Varrock was going to fall?"
/ "They knew they were in trouble." → *(dispatch handed over, → `dispatch`)* "Last official message I ever
got from Varrock." / "Twelve years old. Still waiting for the next one."

**The dispatch (read).** *To the northern watch: Hold the roads. Do not send reinforcements south.* /
*Zemouregal's dead have breached the outer approaches. Arrav has been sighted among them.* / *Varrock
will hold.* / *No further orders arrived.* (→ `return_zo`)

**Oziach — reaction.** "'Varrock will hold.'" / "Aye." / "It didn't." / "No." / "What happened after
this?" / "Nothing." / "Nothing?" / "No runners. No orders. No army. Just refugees." / "At first there were
thousands. Then hundreds. Then dozens. Then nobody." → optional lore: **Who is Zemouregal?** ("A Mahjarrat
necromancer. Old. Powerful. Fond of corpses." / "Charming." / "You should meet him." / "I'd rather not." /
"Good instinct.") · **Who was Arrav?** ("Once? A hero. Varrock's greatest, depending which drunk you ask."
/ "And now?" / "The man people saw marching with Zemouregal's dead." / "Is he still alive?" / "People have
been arguing about that for twelve years.") · **Why did you stay?** ("Cheap property." / "Seriously?" /
"No. Mind your business.") → "Take that back to Zo." / "If he sent you here to understand the north, give
him the original." / "You kept this for twelve years and you're just giving it to me?" / "I expect it
back." / "Oh." / "And if you lose it to some Rogue Knight on the road, don't bother coming back."

**General Zo — debrief (`return_zo`).** "Oziach still had this?" / "He wants it back." / "Of course he
does." / *(reads it)* "'Varrock will hold.'" / "It was the last message." / "For Edgeville, yes." / *(hands
it back)* "So. What did you see?" → **"The north is lawless."** · **"Edgeville is still holding."** ·
**"The Wilderness is the least of our problems."** — all converge: "Varrock didn't just lose a battle.
When it fell, everything around it started falling apart too." / "Exactly." / "Armies are obvious.
Collapsed roads aren't." / "Neither are farms that stop producing. Or patrols that never come home. Or a
hundred little warlords deciding nobody can stop them." / "So the Rogue Knights are part of the war too?"
/ "Not every enemy wears Zemouregal's colours. That doesn't make them harmless." / "The longer this
kingdom stays broken, the more men discover they prefer it that way." / "So what do we do?" / "What we've
been doing. One piece at a time." / "Another March?" / "No. A March wins a battlefield." / "What I want
next is something we can actually use." / "What?" / **QUEST COMPLETE** (+15 War Effort) / "A position." /
"Something between Lumbridge and the north that belongs to us when the fighting stops." / "It's time you
helped take one back." → **First Reclamation.**

Accepted history only (revelation ladder Stage 1): nothing about Sliske, the Elder Horn, Lucien and the
Stone, Senntisten, the Fracture, or Arrav resisting Zemouregal is said or hinted beyond "people have
been arguing about that for twelve years".

## Quest journal (server step objectives = client journal rows)

| State (step id) | Journal |
|---|---|
| START (`brief`) | General Zo wants me to understand what Varrock's fall did to northern Misthalin. |
| EDGEVILLE (`edgeville`) | Travel to Edgeville. |
| CONTACT (`contact`) | Find someone in Edgeville who remembers the Fall. |
| WILDERNESS (`wilderness`) | Inspect the Wilderness boundary north of Edgeville. |
| RETURN_OZIACH (`return_oziach`) | Return to Oziach and tell him what I saw. |
| DISPATCH (`dispatch`) | Read the Weathered Varrock Dispatch. |
| RETURN_ZO (`return_zo`) | Take Oziach's dispatch to General Zo. |
| DONE | Varrock's fall broke more than a city. Misthalin lost roads, patrols and control of the north. General Zo intends to start taking that ground back. |

`::north` prints the current objective and opens the Quest Journal on the quest.

## Rewards (light, as designed)

**1 Quest Point** (`questPoints = 1`) · **15 War Effort** · the **Weathered Varrock Dispatch** stays
with the player (untradeable, kept on death, re-readable from the pack — the lore journal) · main
campaign progression · unlocks **First Reclamation**. No equipment, no currency, no PvP reward.

## Rogue Knight and Wilderness handling

No Rogue Knight kill is required and none is spawned; if one attacks the player on the road north, the
story just told them why. The player must **reach** the Wilderness boundary and is **never required to
cross it**: new players learn where real PvP begins, Edgeville is established as the staging town, and
no inexperienced player is forced to risk gear.

## Development section

| Field | The North |
|---|---|
| Start NPC | General Zo, 3220,3210 (Lumbridge castle courtyard) — the quest auto-begins; he opens it |
| NPCs Used | General Zo · Oziach (stock Edgeville spawn 3069,3517) — both existing, both in place |
| Locations | Lumbridge courtyard · Edgeville (unchanged) · the Wilderness ditch north of Edgeville (unchanged) |
| Gameplay Used | Existing travel (roads, amulet of glory) · the stock ditch · the presence-gated world spawns · War Effort |
| Dialogue | Zo brief + debrief · Oziach ×3 beats + optional lore + everyday lines · boundary and arrival narration · the dispatch text |
| Quest State | Framework `QuestStates` blob (`the_north`: step ids above + a `reaction` counter); completion flag `quest.the_north.done` |
| Journal | table above; client `LofQuest.THE_NORTH` (varp 4686, generic packing); native tab = Ernest the Chicken relabelled (varp 32) |
| System Hooks | `NpcTalk` (Zo, Oziach) · `QuestEngine` poll (two `ReachArea`s + auto-begin) · pack item option (Read) · `Reward.WarEffort` |
| Temporary Content | None |
| New NPCs | NONE |
| New Maps | NONE |
| World Changes | NONE |
| New Mechanics | NONE (two small framework additions: `QuestDefinition.nativeTabVarp` mirror in `QuestEngine.publish`; auto-begin in the poll) |
| Development Cost | **Light Custom** — the Weathered Varrock Dispatch (item 25829 relabelled: override YAML + `ItemDefTool dispatch` cache rename) |

## Operator steps after merge

1. **Item def cache edit** (Actions → *Item def cache edit* → `dispatch`): renames item 25829 in the
   live cache to "Weathered Varrock dispatch" with its examine (~90 s restart). Until then the pack
   shows the stock "Old note" — the quest works regardless.
2. **Quest cache relabel** (Actions → *Quest cache relabel* → `relabel`, then `hide`): adds "The North"
   to the stock quest tab (Ernest the Chicken's row). The same run picks up The Last Free City's rename.
3. **Client:** the `ship-client` workflow runs on the push (client/ changed); launchers auto-update.
4. **Smoke test** on an account that has finished The Last Free City: log in → "The North — begun" →
   Zo brief → glory/walk to Edgeville (arrival lines) → Oziach → ditch (boundary lines) → Oziach (dispatch
   handed over, read, reaction) → Zo debrief → complete, +15 War Effort; `::north`, the Quest Journal
   row, and the pack's Read option. Then `::questdebug reset the_north` to walk it again.
