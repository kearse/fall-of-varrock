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
| **II — The Soldier** ⬜ | Soldier → Knight → Lord | War-Prep II (Ranged) 🔶 · **"The Rogue Problem"** · **"Supply Lines"** · **"First Command"** (Lord) | Each quest opens one repeatable grind loop: rogue milestones (§4), supply contracts (§3), and — at Lord — General Zo's "take command" finally pays off with a tutorialized `::sendtroops` raid. |
| **III — The Crown** ⬜ | Minister → King | Campaign quests per Varrock district · **King of Lumbridge** · **Conquest of Varrock** | Campaigns retake Varrock district by district (§5); Conquest of the Palace is the server-wide climax. Post-game: hold Varrock (new front, city-vs-city). |

Design rule: Act I holds your hand, Act II hands you loops, Act III hands you armies.
The guidance never stops — it changes altitude.

---

## 2. Marches ⬜ (the scheduled knight warband)

Every ~30 minutes, **10 Knights of Lumbridge march on a hostile target. Any player can
join.** This is the beginner/mid player's entry into the war's *offense* (today they only
defend), and the visible output of the realm-supply economy.

**The command ladder** (naming is now canonical — do not overload "raid"):

| Event | Who | Cost | Cadence |
|-------|-----|------|---------|
| **March** | anyone joins | consumes realm supplies | scheduled (~30 min) |
| **Raid** (`::sendtroops`) | Lord commands | 1M coins | on demand |
| **Campaign** (`::campaign`) | Minister commands | 3M + 1,500 supplies | on demand |
| **Conquest** (`::conquest`) | King commands | 15M + 2,800 supplies | on demand |

The March is the public bus; the Lord's Raid is chartered.

### Mechanics

- **Muster, not a timer.** 5 minutes before departure the knights physically form up at
  the Lumbridge gate and the server (and Discord feed) announces it. Talk to the
  **Knight-Captain** or stand in the muster zone to enlist. `::march` teleports a
  latecomer to the warband mid-fight.
- **Supply-fed.** Each March consumes a slice of the shared realm-supply meter
  (`RealmSupply`). Well-supplied realm ⇒ marches on schedule; starved realm ⇒ the
  Captain announces "the stores are empty — no march today." Suppliers finally *see*
  their hand-ins become knight activity.
- **Targets are Fallen Varrock districts** (plus the goblin camp, bandit camps, the dark
  wizards' tower) — see §5. Random among currently-contested targets.
- **Marches can fail.** If no players join, the knights sometimes get wiped — players
  must learn "when we don't march with them, they die." Outcomes accumulate on the
  district pressure meter (§5); even NPC-only marches move it slightly.
- **Rewards scale with participation, not presence** — reuse the siege-defense
  contribution tracking. Pays coins + War Effort + **Commendations** (§6), MVP bonus.
- **Weekly Grand March** ⬜ — a bigger roster against a district's **Warden** (a
  boss-tier defender using the `BossCombat` primitives). Guaranteed forge component
  (§6) for the MVP, rolled for everyone else.

Engineering note: this is the inverse of the existing goblin raid event — the
`AttackDirector` / commander / Force layers and `CityFrontiers` are the reuse surface.

---

## 3. Supply contracts ⬜ (the skilling faucet)

The Quartermaster posts rotating **work orders**: *"200 oak logs for siege ladders"*,
*"50 lobsters for the garrison"*, *"30 mithril platebodies for the levy"*. Filling one
pays **coins + War Effort + realm supply**.

- This is roadmap item 4 (the gated economy, `long-term-vision.md` §3) wearing a war
  skin — the missing coin faucet for the Squire → Lord climb.
- Contracts rotate daily; higher-rank players see higher-tier (better-paying) orders.
- Introduced by the Act II quest **"Supply Lines"**.
- Directly feeds §2: contracts fill the meter, the meter feeds marches.

## 4. Rogue hunting & the bounty board ⬜ (the solo hunter's track)

- **Milestone track:** the Recruiting Sergeant pays on total rogue-family kills
  (rogues, muggers, highwaymen, thugs — the occupiers of Varrock) at **10 / 50 / 250 /
  1,000**: coins + War Effort early, the **"Rogue-hunter"** title mid, something
  functional at the top (Sergeant's shop discount, or a key to a third Varrock safe
  pocket). The counter is a persistent stat — death never resets it.
- **Rotating bounty:** "this week the Sergeant pays double for dark wizards" — one
  config line, feeds the Discord event feed.
- **Bounty board:** wanted posters for **named captains** — elite, uniquely-named
  spawns ("Karn the Red — last seen: the Old Market, wilderness 28") on long respawns.
  Each district has one (§5); each carries a **signature broken spec weapon** (§6).
- Introduced by the Act II quest **"The Rogue Problem"** — the player's first scripted
  trip into Fallen Varrock, the city they will one day retake.

Also planned, lower priority: **salvage runs** (loot "relics of old Varrock" from ruined
buildings — a Historian NPC + collection log; relics are a forge material, §6),
**caravan escorts** (defend the supply wagon to the frontier), **prisoner rescue**
(free captured knights back into the garrison).

---

## 5. The reconquest of Varrock ⬜ (the meta-progression)

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

## 6. The endgame gear economy ⬜ — max gear as the grind

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

## 7. Build order

1. ⬜ **Supply contracts** (§3) — the coin faucet; unblocks the Squire→Lord chasm and
   feeds everything else. (= long-term-vision roadmap #4.)
2. ⬜ **Marches** (§2) — visible war offense; point the War-Prep finale ("First March")
   at it. Fixes the tutorial dead-end.
3. ⬜ **Rogue milestones + bounty board** (§4) — cheap, parallel solo track.
4. ⬜ **Districts + pressure meter** (§5) — turns marches into meta-progression.
5. ⬜ **Commendations + the Royal Smith + first war-forged pieces** (§6) — the endgame
   chase. Start with one weapon per style + one armour set; expand.
6. ⬜ **Grand March / Wardens, named captains' spec weapons, armouries** — content
   breadth on top of the framework.
