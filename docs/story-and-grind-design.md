# Story Arc, Marches & the Endgame Grind — Design

> The master plan for keeping players moving: the three-act quest spine, the **March**
> system (scheduled knight warbands anyone can join), the mid-game grind loops that feed
> it, and the endgame gear economy — max gear as the months-long goal, earned across every
> pillar of the game. Companion to [`long-term-vision.md`](long-term-vision.md) (the world)
> and [`war-system-design.md`](war-system-design.md) (the battle engine).
> Status tags: ✅ built · 🔶 in progress · ⬜ planned.

---

## 1. The story spine

**"Varrock fell. Lumbridge holds. You rise from peasant to the ruler who takes it back."**

The server name is the backstory; the player's career is the answer. The wiki already
promises the ending (`the-war-explained`: the King's campaign on Varrock is a *purge of
the occupiers*) — the quest line's job is to walk the player there in three acts, where
every quest reward is a **system unlock**, not just loot.

| Act | Ranks | Quests | What each unlocks |
|-----|-------|--------|-------------------|
| **I — The Recruit** ✅🔶 | Peasant → Squire | Recruit Trials ✅ · War-Prep I (Magic) ✅ · **"First March"** ⬜ | Combat, ranks, skilling loop, spellbooks. *First March* replaces the dead-end "raids are opening to you" finale: the player joins their first March (§2). |
| **II — The Soldier** ⬜ | Soldier → Knight → Lord | War-Prep II (Ranged) ✅ · **"The Rogue Problem"** ✅ · **"Supply Lines"** · **"First Command"** (Lord) | Each quest opens one repeatable grind loop: rogue milestones (§4), supply contracts (§3), and — at Lord — General Zo's "take command" finally pays off with a tutorialized `::sendtroops` raid. War-Prep II (Ranged, Knight→Lord) and III (Survival, Lord→Minister) now guide the mid-game climb. |
| **III — The Crown** ⬜ | Minister → King | Campaign quests per Varrock district · **King of Lumbridge** · **Conquest of Varrock** | Campaigns retake Varrock district by district (§5); Conquest of the Palace is the server-wide climax. Post-game: hold Varrock (new front, city-vs-city). |

Design rule: Act I holds your hand, Act II hands you loops, Act III hands you armies.
The guidance never stops — it changes altitude.

---

## 2. Marches 🔶 (the scheduled knight warband)

> **Built (v1):** `MarchPlugin.kt` + a realm-sponsored `CampaignTier.MARCH` (10 knights,
> quota 15) riding the whole campaign engine — `CampaignDirector` (nullable
> sponsor) does the marching/fighting down the Varrock route, `CapturePayout` splits the
> pooled spoils by participation (no commander stake/tithe on a realm march). Cycle: muster
> call ~5 min ahead → launch every ~30 min → win/loss broadcast. **Automatic and supply-free:**
> a march fires every cycle no matter the player count or the supply meter, and spends nothing
> from the realm's war-stores — the early game keeps moving on its own. `::march` rallies any
> player to the column, with a type-it-twice warning when the column is on PvP ground.
> `::marchnow` (admin) fast-forwards the cycle. War-Prep's finale now points here.
> **Still ⬜:** the physical muster (Knight-Captain NPC + column forming at the gate),
> commendation payouts (build item 5), district targets (item 4), a "March" HUD label.

Every ~30 minutes, **10 Knights of Lumbridge march on a hostile target. Any player can
join.** This is the beginner/mid player's entry into the war's *offense* (today they only
defend), and the always-on heartbeat of the war — it runs on its own so there is always
something to fight, no matter how quiet or unsupplied the realm is.

**The command ladder** (naming is now canonical — do not overload "raid"):

| Event | Who | Cost | Cadence |
|-------|-----|------|---------|
| **March** | anyone joins | free (no supply cost) | scheduled (~30 min), automatic |
| **Raid** (`::sendtroops`) | Lord commands | 1M coins | on demand |
| **Campaign** (`::campaign`) | Minister commands | 3M + 1,500 supplies | on demand |
| **Conquest** (`::conquest`) | King commands | 15M + 2,800 supplies | on demand |

The March is the public bus; the Lord's Raid is chartered.

### Mechanics

- **Muster, not a timer.** 5 minutes before departure the knights physically form up at
  the Lumbridge gate and the server (and Discord feed) announces it. Talk to the
  **Knight-Captain** or stand in the muster zone to enlist. `::march` teleports a
  latecomer to the warband mid-fight.
- **Automatic & supply-free.** A March fires every cycle regardless of the realm-supply
  meter and spends nothing from it — the war keeps moving without waiting on players to
  stock the stores, so the early game never stalls. The supply meter (`RealmSupply`) is
  spent only by the paid command ladder (campaigns and conquests); Marches sit free
  beneath it.
- **Targets are Fallen Varrock districts** (plus the goblin camp, bandit camps, the dark
  wizards' tower) — see §5. Random among currently-contested targets.
- **Marches can fail.** If no players join, the knights sometimes get wiped — players
  must learn "when we don't march with them, they die." Outcomes accumulate on the
  district pressure meter (§5); even NPC-only marches move it slightly.
- **Rewards scale with participation, not presence** — reuse the siege-defense
  contribution tracking. Pays coins + War Effort + **Commendations** (§6), MVP bonus.
- ✅ **Grand March** — every 8th launched march (persisted counter in `WarState`),
  upsized (`CampaignTier.GRAND_MARCH`: 16 knights, quota 20, up to 5
  Commendations; supply-free like every march). The district's **Warden** (boss-tier, 1500hp, base-model animations)
  waits at the rally; his fall pays **Warden's embers** (`item.burnt_page` — tradeable
  forge component): guaranteed for the damage MVP, 1/3 roll for every other fighter,
  realm-wide broadcast. A driven-back Grand March despawns the Warden with a taunt.
  Embers gate the forge's **helm tier** (§6). Cadence/stats TUNE.

Engineering note: this is the inverse of the existing goblin raid event — the
`AttackDirector` / commander / Force layers and `CityFrontiers` are the reuse surface.

---

## 3. Supply contracts 🔶 (the skilling faucet)

Vannaka's **resource contracts** already exist (`ResourceContracts.kt`: gather N of a
resource → coins + War Effort, auto-completing; `SupplyDepot.kt`: finished goods → War
Effort + realm supply; `SupplyDrive.kt`: rotating 2× demand window). The build here is
**opening the faucet**:

- ✅ **Rank-tiered work orders** — richer contracts unlock with feudal rank (coal →
  runite, willow → magic logs, pike → shark), Knight-tier pay ~40–45k/contract so the
  Squire→Lord climb is weeks of skilling, not months of 50-gp goblins. Assignment
  prefers the best unlocked tier; Vannaka points at the Duke when skills outrank title.
- ✅ **Launch generosity multiplier** (`GENEROSITY_MULT`) on contract payouts — start
  hot, step toward 1 as the economy matures (§7).
- ⬜ Smithed-goods work orders ("30 mithril platebodies for the levy") — the Yeoman rung.
- ⬜ Introduced by the Act II quest **"Supply Lines"**.
- Directly feeds §2: contracts fill the meter, the meter feeds marches.

## 4. Rogue hunting & the bounty board 🔶 (the solo hunter's track)

- ✅ **Milestone track** (`war/roguehunt/`): lifetime rogue-family kill counter (rogues,
  muggers, highwaymen, thugs, bandits, outlaws — persistent, death never resets it),
  milestones at **10 / 50 / 250 / 1,000** paying escalating coins + War Effort. The
  Recruiting Sergeant is the paymaster (bounty dialogue + the pitch that sends veterans
  to Fallen Varrock); `::rogues` tracks progress; milestone crossings nudge in chat.
- ⬜ Top-milestone functional reward (Sergeant's shop discount or a third Varrock safe
  pocket) and a "Rogue-hunter" cosmetic title.
- **Rotating bounty:** "this week the Sergeant pays double for dark wizards" — one
  config line, feeds the Discord event feed.
- ✅ **Bounty board — named captains** (`war/captains/`): one elite, uniquely-named
  captain per district (Karn the Red / Silas the Hollow / Vex of the Row / Grimjaw),
  ~60-min respawn, boosted stats on the base model's combat def. A kill pays 100k coins
  (banked — no PK bonus), 2 Commendations and 25 War Effort, and rolls the captain's
  **signature weapon** at 1/8 (Dragon claws / Nightmare staff / Armadyl crossbow /
  Dragon warhammer — realm-wide broadcast on the drop). `::bounties` shows the board.
  Captains count toward the rogue tally. All numbers TUNE.
- ✅ Introduced by the Act II quest **"The Rogue Problem"** (`war/roguehunt/RogueProblem.kt`) — the
  player's first scripted trip into Fallen Varrock, the city they will one day retake. It
  **auto-starts the moment the War-Prep chain finishes** (the Squire rank-up), given/paid by the
  Recruiting Sergeant: hunt 30 rogues → **kill your first assigned Rogue Knight** (the ladder
  opener, below) → collect a purse sized off the ladder (Soldier + Knight cost) → climb to
  **Knight** at Duke Horacio, which opens the first companion and the wilderness/PK loop. Fully
  wired into the Quest Journal (client hint arrows + `::rogueproblem`) so the quest helper guides
  the whole bridge from Wizard Tower to Knight. Numbers (hunt goal, purse) are TUNE — the purse
  deliberately short-cuts the Squire→Knight grind into one guided quest; dial it down to keep the
  climb a longer haul. Now also on the **native quest tab** (reuses The Restless Ghost / varp 107,
  coloured by `QuestJournal.syncNativeTab`; run the cache `relabel` + `hide` to surface it — see
  `docs/quest-tab-handoff.md`). **Still ⬜:** a dedicated wilderness hunt instance if the open
  streets prove too punishing for a fresh Knight.

### 4b. The Rogue Knight ladder ✅ (`bots/knights/` — organized PK-bot progression)

The PK bots, reorganized from an anonymous wilderness spread into a **career**: the quest's
"captain" beat was reworked into an extensible ladder of ~14 **individually named Rogue Knight
bosses** (PkBot fake-players, boss-tuned) the Sergeant assigns weakest → strongest, stationed at
**organized camps by level band** — so players always know exactly what they're chasing next, and
the chase doubles as PK training + gear progression.

- **The camps** (`BotZones` fixed-tier ambient zones + the named knights): the **Bandit Hideout**
  west of Lumbridge (SAFE — bronze/iron/steel fodder, reclaimable deaths, the learning camp) →
  the **Siege of Port Sarim** (SAFE — the rogues raid Lumbridge's lifeline port in waves;
  `areas/portsarim/PortSiegePlugin` runs a Knights-of-Lumbridge-vs-Rogue-Raider NPC battle tuned
  so the garrison loses 6v4 unless a player kills 2–3 raiders to flip it — wave wins pay a small
  +5 War Effort; ladder knights 3–6 lead the raids) → **Fallen Varrock** (shallow single-combat
  wild — existing capped pool + void ranger / dark-bow sniper) → the **Wild Bandit Camp** (deep
  multi — maxers + Statius/Morrigan/rev-weapon builds) → the **Rogue Commander's Redoubt** (wild
  45+ — the elite NH guard + Vesta/Zuriel knights). The safe camps ride `BotZoneConfig.allowSafe`
  (+ per-bot `ambushEverywhere`); the wilderness depth-grid roamers stay, with the 8 archetype
  loadouts folded into its tiers. (Fallen Falador needs no dedicated camp — the raid-city colony
  covers it.)
- **Assignment loop** (`RogueKnightLadder` + `ROGUE_KNIGHT_RANK_ATTR`): kill your assigned knight →
  rank up, get a **first-kill unlock** (deliberately the CORE of the next build up the ladder — the
  gear that beats the next knight), and the Sergeant names the next mark. Beaten knights stay
  **farmable** (`::huntknight n` / the Sergeant's menu); `::knights` lists the board. Dying to a
  knight never resets anything — it's the expected loop, and the low camps make it cheap.
- **Per-hunter instances** (`RogueKnightCampPlugin`): every hunter at a camp gets their OWN bound
  copy of the knight (`PkBot.boundHunter` locks its aggro), so a crowded rung never queues.
  Presence-gated, ~45s respawn, capped per camp. Boss knobs on `PkBot` (`maxHpOverride` — current-
  level based, never `setBaseLevel`>99; `reactionTicksRange`; `specRegenPeriod`) make the top
  knights take several genuine attempts (Vexmar: 160hp, frame-perfect prayers, 3-tick spec regen).
- **Tracking** (the quest-helper ask): a native **hint arrow** follows the hunt — tile arrow toward
  the camp from anywhere, flipping to a player arrow locked on the live knight in scene; respects
  the guidance mute; varp **4644** publishes rank+target for a future client panel.
- **PK-set loot pools** (`PkLootPools`): every bot death still drops its full worn kit, PLUS a
  rare-tier roll from its band's pool — pools are built around real PK builds so grinding a camp
  assembles a recognisable set: starter metal → **pure/zerker kits** → **hybrid kit** (whip,
  neitiznot, barrows pieces) → **maxer kit** (bandos, claws 1/150, DWH) → **NH tribrid kit**
  (ancestral/masori/kodai, AGS 1/100, voidwaker 1/500) + a **revenant-weapon trickle**. Named
  knights roll their signature table instead at far better odds (e.g. the four Ancient-Warrior
  knights drop their Statius/Morrigan/Vesta/Zuriel sets at ~1/15-1/18). Rates anchored to the BM
  shop so drops complement the sink; wilderness rolls seal into the killer's **loot key**. All TUNE.
- **Scaling:** knight #15+ is one `RogueKnights.LADDER` entry — spawning, arrows, dialogue and
  drops are all data-driven off the registry. The named captains (`war/captains`) remain standalone
  bounty content.

Also planned, lower priority: **salvage runs** (loot "relics of old Varrock" from ruined
buildings — a Historian NPC + collection log; relics are a forge material, §6),
**caravan escorts** (defend the supply wagon to the frontier), **prisoner rescue**
(free captured knights back into the garrison).

---

## 5. The reconquest of Varrock 🔶 (the meta-progression)

> **Built (v1):** `Districts.kt` — four districts (the Slums, the Old Market, the East
> Quarter, the Museum Quarter) with persistent pressure meters in `WarState`. Every march
> targets a district (its own street approach off the campaign route's city mouth — TUNE
> the waypoints in-game); a won march raises pressure, breaking the district at its
> threshold with realm-wide announcements, and once all four are broken the Palace-road
> call goes out. Broken districts shave 10% each off a campaign/conquest kill quota on
> Varrock (floor 50%) — the marches genuinely soften the ground the commanders fight on.
> `::districts` shows the map. **Still ⬜:** counterattacks that re-raise broken districts,
> district armouries (§6), named captains per district, Palace conquest gating on
> all-broken, liberated safe pockets.

Fallen Varrock is split into **districts** (Old Market, the slums, the church quarter,
the Grand Exchange approaches, … , the **Palace** last). Each district has a persistent
**pressure meter** moved by war activity:

```
Marches soften a district  ──▶  softened: eligible for a Lord's Raid
Raids break its garrison   ──▶  broken: eligible for a Minister's Campaign
Campaign purges it         ──▶  liberated: safe pocket grows, its ARMOURY opens (§6)
The Palace                 ──▶  the King's Conquest — the server-wide climax
```

- Every player action — a march joined, a contract filled, a captain slain — pushes the
  same visible meter. The whole server watches the climax approach.
- Liberated districts can be **counterattacked** (defense marches) so the map breathes
  and the loop doesn't end at 100%.
- Post-Conquest: Varrock becomes a second front — the city-vs-city groundwork
  (`long-term-vision.md` §6).

---

## 6. The endgame gear economy 🔶 — max gear as the grind

> **Built (v1):** `war/forge/` — **Commendations** are live as an item token
> (`item.ectotoken`: stackable, cache-untradeable; renamed to "Commendation" with a
> Thurgo-pointing examine via the `ItemDefTool` `commendation` action — apply on live
> with the "Item def cache edit" workflow; TUNE: resprite via a cache edit
> later), paid by `CapturePayout` on every WON war op, contribution-scaled (March 1–3,
> Campaign 1–6, Conquest 1–10; losses pay nothing). The **Royal Smith** stands in the
> Lumbridge castle courtyard (tile TUNE) and forges the three BIS armour lines —
> Bandos→Torva, Armadyl→Masori, Ahrim's→Ancestral, two pieces each — for
> 1 base + 25 Commendations + 20 runite bars + 250k coins per piece (TUNE). Forging is
> gated at rank Knight; outputs are Lord-tier to wear and fully tradeable; each forging
> is a realm-wide headline. **Still ⬜:** relics as a recipe ingredient (salvage runs),
> forge components + pity shards (Wardens, item 6), spec-weapon restoration (named
> captains), the self-made cosmetic mark, weapons/helms to complete the sets.

**Goal:** the long-term chase is a full set of best-in-slot gear per combat style — but
no single loop grants it. Every max piece routes through **all four pillars**: the war,
PvM, the wilderness, and skilling.

### 6a. Acquisition tiers

| Tier | What | Source | Status |
|------|------|--------|--------|
| **1. Rank gear** | bronze → rune/dragon | rank ladder + shops + drops | ✅ |
| **2. Elite uniques** | godswords, Bandos, Armadyl, Ancestral, Masori, claws, tbow-class | boss drop tables ✅ · deep-wilderness PK-bot kits ✅ (bots at 31+ already wear and drop this) · **district armouries** ⬜ (each liberated district opens a themed unlock pool — e.g. the church quarter → prayer gear, the Palace → godsword components) | 🔶 |
| **3. War-forged** (BIS) | upgraded, ember-and-gold recolored variants of tier 2 — the true max | **forged**, never dropped — see 6b | ⬜ |

### 6b. War-forging — the max-gear recipe

The **Royal Smith** (unlocked by an Act II/III quest, forging gated at rank **Knight+**,
the best pieces at **Lord+**) upgrades an elite base item into its **war-forged**
variant. Every forge consumes materials from a different pillar:

| Material | Pillar | Source | Tradeable |
|----------|--------|--------|-----------|
| **Base item** | PvM / wilderness | boss drop or bot-kit loot key | yes |
| **Commendations** | the war | March/Raid/Campaign participation, MVP bonus (item tokens, like the boss/vote ticket migration — no new currency enum) | **no** — the one untradeable, and deliberately the *smallest* ingredient (a handful of marches, not the wall) |
| **Relics of old Varrock** | risk/salvage | Fallen Varrock salvage runs + rare rogue drops | yes (trading post — creates a market) |
| **Smithing materials** | skilling | supply-contract outputs (bars, leathers, cloth) | yes |
| **Forge component** | apex PvM | named captains (§4), district Wardens (Grand March MVP), campaign bosses — each drops a *specific* component | yes, rare |

**Finished war-forged gear is tradeable too.** This is the RSPS economy players expect —
grind gp your own way, buy power on the market (the OSRS twisted-bow model). The
commendation fee doesn't stop a rich player buying a finished piece from a grinder; it
guarantees every forged item in the economy represents real marches fought by *somebody*,
so the war stays populated no matter how wealth flows, and the store can never mint max
gear. It's a faucet control, not a player restriction.

**Self-made mark:** an item forged entirely from ingredients the customer earned
themselves gets a small cosmetic mark + the title *"forged by their own hand"*. Same
stats as a bought piece — power is tradeable, status is earned.

**Spec weapons** follow the same path with a flavor twist: each district's named captain
carries a **broken signature weapon** (e.g. *Karn's cleaver* → restored as an
AGS-class spec weapon). Restoring it at the forge = base broken drop + the standard
recipe.

### 6c. Pacing (tunable sketch)

- Winning March = **1–3 Commendations** (participation-scaled), MVP +2. Raid/Campaign
  pay more. Rough target: **~25 Commendations per armour piece, ~40 per weapon** ⇒
  ~30–50 marches per piece ⇒ a full 3-style BIS kit is a **multi-month** career, not a
  lucky week.
- **Bad-luck protection** on forge components: district Wardens drop a pity shard every
  kill; N shards = one component. RNG excites, determinism retains.
- Commendations being untradeable is the anti-swipe valve on the *faucet*: bonds can buy
  gp, gp can buy every tradeable ingredient and even finished pieces — but the store
  alone can never create a forged item that no one marched for.
- Sink: war-forged gear uses the standard death rules — smithable **repair** costs
  (more contract materials + a gp fee) rather than loss, so the economy keeps cycling.
- **Inflation watch:** tradeable + very-high-value only works if gp has sinks. Bond
  buyers inject demand; if prices outrun what self-made players can earn, tighten the
  sinks (repair/forge fees) or widen the faucets (contract payouts) — tune, don't panic.

### 6d. Why this shape

1. **Max gear demands every loop** — a pure PvMer still marches, a pure warrior still
   needs the market or the Mire. The loops advertise each other.
2. **The story and the grind are the same track** — armouries and captains live in
   districts, so gearing up *is* retaking Varrock.
3. **Legible chase** — a player can point at any BIS piece and list exactly what it
   needs; the Quest Journal / collection log can render the recipe as a checklist.
4. **No new currency enum** — commendations/relics/components are items, consistent
   with the ticket migration already done in `Currencies.kt`.

---

## 7. Monetization ⬜ (sell speed, status, and events — never rank, command, or stats)

Principles: the free game is tuned to be fun on its own (**launch generosity curve** —
contract payouts / drop rates / commendation gains start hot, tighten as the economy
matures, plus event weekends). Bonds are the coin-buying valve and are self-limiting
early (a bond needs a grinder with gp on the other side). The store sells on top:

| Product | What it is | Why it's safe |
|---------|-----------|----------------|
| **Patron of the March** ✅ | Real money funds the next March or Grand March in the patron's name: the muster call and launch credit them realm-wide, and the realm's supply meter is untouched (the patron covered it). Store SKUs `patron-march`/`patron-grand-march` → `patron_march` entitlement → persisted queue in `WarState`, consumed by `MarchPlugin` at the next muster (re-queued if the cycle skips — a purchase is never lost) | Donor buys visibility + influence; *everyone* gets more content. This is "pay to stimulate the economy" done right |
| **Sponsored events** | Grand March sponsorship, double-contract-pay hour | Server-wide faucet, individually credited |
| **Companion stable slots** ⬜ | Own 3 companions, **deploy one at a time** (hard rule) | Variety/convenience, zero combat power. **Still blocked, but the primitive now exists:** a companion can be *dismissed* (off the field, on the roster — `CompanionData.dismissed`, the panel's Dismiss/Summon), so "owned" and "fielded" are already separate counts and the rank cap gates the roster (`CompanionRegistry.rosterSize`). What's left before slots can be sold is the hard *deploy-one* rule — today `Title.companions` still sets how many may be fielded at once, so selling slots would sell army size |
| **Companion & gear cosmetics** | Skins, armour paint matching war-forged sets, banners, titles | Pure status |
| **War Chest seasonal pass** | Cosmetic reward track advanced by marches/contracts/bounties; free track for all, paid track for donors | Monetizes engagement with the loops we want populated |
| **Liberation memorial** | Patrons who funded marches on a district get their name on a statue in the liberated streets | The highest-status purchase is recognition, not power |
| **Rank commission (cap: Soldier)** | Optional catch-up product for late joiners | Below Knight/Lord — no command powers, mid armour tier only |

**Never sold:** ranks above Soldier (campaigns spend the *shared* supply meter — wallet
commanders burning communal supplies is community poison; rank colors are the status
ladder), companion style-flex/NH (raw combat power — if it exists at all it's an earned
unlock), commendations or any forge ingredient, XP, stats.

---

## 8. Build order — the working checklist

1. 🔶 **Supply contracts** (§3) — the coin faucet; unblocks the Squire→Lord chasm and
   feeds everything else. (= long-term-vision roadmap #4.)
   - ✅ Rank-tiered contract roster (Peasant → Knight) with chasm-bridging payouts,
     best-tier-preferred assignment, Vannaka's "rank up for richer work" nudge.
   - ✅ Launch-generosity payout multiplier (`ResourceContracts.GENEROSITY_MULT`).
   - ⬜ Smithed-goods work orders; "Supply Lines" intro quest.
2. 🔶 **Marches** (§2) — visible war offense; fixes the tutorial dead-end.
   - ✅ Scheduled MARCH tier on the campaign engine: 30-min cycle, 5-min muster call,
     automatic and supply-free (fires no matter the player count or supply level),
     `::march` rally (PvP double-confirm),
     participation-split spoils, War-Prep finale now points at the muster call.
   - ⬜ Physical muster at the gate (Knight-Captain NPC), Commendation payouts (item 5),
     district targets (item 4), "March" HUD label in the client.
3. 🔶 **Rogue milestones + bounty board** (§4) — cheap, parallel solo track.
   - ✅ Persistent rogue-family kill counter + Sergeant milestone bounties
     (10/50/250/1000), `::rogues` progress command.
   - ✅ **The Rogue Knight ladder** (§4b): named-knight assignment track, organized camps,
     per-hunter boss instances, hint-arrow tracking, PK-set loot pools.
   - ⬜ Rotating weekly bounty target, named captains (with item 4's districts).
4. 🔶 **Districts + pressure meter** (§5) — turns marches into meta-progression.
   - ✅ Four districts w/ persistent pressure, march targeting + win credit, break/all-broken
     announcements, campaign quota discount, `::districts`.
   - ⬜ Counterattacks, armouries, named captains, Palace gating, liberated safe pockets.
5. 🔶 **Commendations + the Royal Smith + first war-forged pieces** (§6) — the endgame
   chase.
   - ✅ Commendation item token paid on won ops (contribution-scaled); Royal Smith NPC
     with forge dialogue; six BIS recipes (Torva/Masori/Ancestral body+legs).
   - ⬜ Relics + forge components in recipes, spec weapons, helms/weapons, self-made mark.
6. 🔶 **Grand March / Wardens, named captains' spec weapons, armouries** — content
   breadth on top of the framework.
   - ✅ Named captains per district with bounties + signature spec-weapon drops,
     `::bounties` board.
   - ✅ Grand March (every 8th) + district Wardens dropping Warden's embers; ember-gated
     helm recipes complete the three war-forged sets (9 recipes total).
   - ⬜ District armouries, broken-weapon restoration recipes, pity shards on embers.
7. 🔶 **Patron products** (§7) — store SKUs ride the entitlements pipeline (`Alter/web`).
   - ✅ Patron of the March + Patron of the Grand March: store "Patron" category →
     `patron_march` entitlement → persisted `WarState` queue → next muster call runs in
     the patron's name with the supply cost covered.
   - ⬜ Companion stable slots (blocked on the deploy-one rework), War Chest pass,
     liberation memorial.
8. 🔶 **Wiki pages** — `Alter/web/content/wiki/`:
   - ✅ "Marches & the reconquest of Varrock" (marches, districts, Grand March/Wardens,
     Commendations, wanted captains) + "War-forging — the Royal Smith" (all nine
     recipes + ingredient sources); "The War explained" table gains March/Grand March
     rows and links the reconquest page.
   - ⬜ Post-deploy sweep once numbers settle in-game (rates/costs are TUNE).
