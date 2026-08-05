# PK-bot fight styles — per-loadout behaviour + the PID model

**Goal:** the Rogue Knight ladder (and every PKer bot) teaches players to fight *good humans*.
That only works if every behaviour a bot shows is one a real OSRS PKer shows — a player who
learns to beat our bots must have learned nothing bot-specific, or they learned wrong.
Realism is therefore the design test for every mechanic below: *"is this exactly what a human
does, and is the counter the same counter?"*

The system is two layers on the existing NH brain (`bots/BotBrain.kt`):

1. a **FightProfile** on each `BotLoadout` — how that archetype fights beyond its gear, and
2. a **PID model** — the fight-momentum coin that decides same-tick races, exactly one
   mechanic deep, mirrored from the real game.

Everything is data on the loadout; no per-boss code.

## The PID model

Real OSRS processes players in PID order each tick and reshuffles PIDs at random intervals
(~1 minute). Holding the lower PID ("having PID") means your actions resolve first in
same-tick races: your hit beats their flick, your maul stacks on your AGS, their switch is
read a tick late. Good PKers *time their KO around the swap*.

We model exactly that much, per fight (`PkBot.hasPid`, reshuffled every **100–150 ticks**):

| Who holds PID | Effect |
| --- | --- |
| **The player** | Same-tick races go to the player: when the bot rolls the *minimum* of its reaction range, it arrives one tick later (`+1`). Your sharpest switches beat its sharpest flicks. |
| **The bot** | Its combo follow-ups stack tight — the granite maul lands with **no gap** after the leading spec (the true ags+maul stack). And a `specOnPidSwap` archetype launches its held KO burst in this phase. |

Two hard rules keep it honest:

- **PID never speeds a bot's reaction up.** Reaction ranges (`RogueKnightDef.reactionTicks`)
  are the floor of fairness — PID only settles ties, it never shrinks the window below the
  tuned range. The "never pin 1..1" rule stands under every PID state.
- **PID is invisible, like the real thing.** No overhead icon, no message. You feel it the
  same way you do on OSRS: your switches suddenly biting, or his maul suddenly stacking.
  That *feel* is the transferable lesson.

What players learn: respect the swap. Sometimes your switches are a tick safer, sometimes
his burst stacks harder — and the burst comes when momentum flips, so play defensive when
your hits start arriving late.

## FightProfile fields

```kotlin
data class FightProfile(
    val koAtHp: Int = 55,          // target HP at/below which the KO attempt starts
    val baitOneIn: Int? = null,    // 1-in-N per idle tick: gear-flash prayer bait
    val specOnPidSwap: Boolean = false, // hold the burst for the PID flip
)
```

- **`koAtHp`** — when the spec weapons come out. Pures/mages burst lower (dds range),
  AGS/claws archetypes start higher. Replaces the old global 55.
- **`baitOneIn`** — the gear-flash. Between swings (never at swing time) the bot briefly
  dresses another style, then re-dresses its true style before it attacks. This is the
  prayer bait every real NHer throws: pull the overhead, hit with the other book. The
  counter is the human counter — read the weapon *at swing time*, not mid-cycle.
- **`specOnPidSwap`** — the patient KO. In KO range with energy up, the bot keeps whip
  pressure until the fight's PID flips to it, then bursts — the classic "claws on the
  swap". Falls back to bursting anyway after ~15 ticks so it never waits forever.

### Combos (existing `meleeSpecRotation`, upgraded)

The rotation *is* the combo. What's new: a **granite maul follow-up fires instantly**
(it has no swing timer on spec, exactly like the real item) instead of waiting a full
attack delay — so `[ags, granite_maul]` is a real 2-tick KO stack, `[dragon_dagger,
granite_maul]` the classic zerker combo. PID sets the stack gap (0 with, 1 without).

## Per-archetype profiles

| Loadout | KO at | Baits | Spec on PID | Combo |
| --- | --- | --- | --- | --- |
| `elite_nh` (Vexmar) | 60 | 1-in-6 | yes | AGS → gmaul (instant) |
| `max_main` / `max_tent` | 60 | 1-in-8 | yes | AGS → gmaul / claws |
| `claws_brid` | 60 | 1-in-6 | yes | claws |
| `vesta_duelist` (Dathen) | 60 | 1-in-7 | yes | VLS → gmaul (instant) |
| `zuriel_mage` | 45 | 1-in-7 | – | dds burst on frozen target |
| `mage_elite` / `ancient_mage` | 45 | 1-in-8 | – | dds |
| `range_elite` | 60 | 1-in-8 | – | msb spec / AGS switch |
| `classic_hybrid` | 50 | 1-in-10 | – | dds → gmaul (instant) |
| `budget_zerker` | 50 | 1-in-12 | – | dds → gmaul (instant) |
| `budget_pure` | 45 | 1-in-12 | – | dds |
| `budget_main` | 50 | – | – | dds → gmaul (instant) |
| everything else | 55 | – | – | loadout rotation as-is |

Metal-tier fodder never baits (single gear set, no prayer) — new players still get honest
simple fights at the bottom of the ladder.

## What each rung teaches (the realism curriculum)

- **Metal camps** — clicking, eating, "the fight is winnable": no prayer, no tricks.
- **Budget camps** — protect prayers + first combos: the dds→gmaul you'll eat at Edgeville.
- **Mid camps** — hybrid switching, freezers, the first baits: read swing-time gear.
- **High/elite camps** — held bursts on the PID swap, tight stacks, frequent baits:
  the full rhythm of a real NH fight, windows tuned small but never zero.

## Tuning knobs

- Reaction ranges: `RogueKnightDef.reactionTicks` (floor rule: never `1..1`).
- Burst timing: `koAtHp`, `specOnPidSwap`, KO-wait cap (`BotBrain.KO_WAIT_MAX_TICKS`).
- Bait rate: `baitOneIn` (higher = rarer).
- PID cadence: `BotBrain.PID_SHUFFLE_TICKS` (100–150, matching OSRS's ~minute shuffle).
