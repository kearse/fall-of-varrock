# Fall of Varrock — Long-Term Vision

> A living, dystopian RuneScape. The cities are the only safe ground; everywhere
> else is hostile. You don't progress by grinding XP in a vacuum — you climb a
> **feudal political ladder**, and your standing is what unlocks the right to use
> land, resources, and eventually to command armies and rule.
>
> This document is the destination. It is intentionally bigger than what is built
> today. For the *current* battle implementation see [`war-system-design.md`](war-system-design.md).
> Status tags: ✅ built · 🔶 in progress · ⬜ planned.

---

## 1. The core loop (the pitch)

Everywhere outside a city wants you dead. You shelter in a city, and you earn the
right to leave it — and to use it — by gaining **political power**. Power buys you
a **title**, and your title is a *key*: it unlocks which resources you may gather,
which facilities you may use, and ultimately which armies you may raise.

```
fight / serve the city  ──▶  political power  ──▶  feudal title (a key)
        ▲                                                  │
        │                                                  ▼
   spend coin / gear        ◀──  money & resources  ◀──  unlocked access
   to fight better                                   (woodcutting, mining,
                                                       fishing, smithing…)
```

The world is hostile, so every step outward is a risk gated by *strength* and
*standing*, never by invisible walls.

---

## 2. Feudal ranks (bought with coin) 🔶 (core built ✅)

Progression is **coin-driven**: you earn money, then **buy your rank from Duke
Horacio** in Lumbridge Castle. Each rank raises the **armour tier** you may wear,
so a richer player is a stronger player. Simple, legible — "X coins of work = the
next rank."

| Rank | Cost (coins) | Armour unlocked |
|------|-------------:|-----------------|
| **Peasant** | free (start) | up to iron |
| **Commoner** | 10,000 | steel |
| **Squire** | 50,000 | black |
| **Soldier** | 150,000 | mithril |
| **Knight** | 500,000 | adamant |
| **Lord** | 2,000,000 | rune and beyond (incl. dragon) |
| **Minister** | 10,000,000 | (rune+) — extra perks planned |
| **King** | 50,000,000 | (rune+) — extra perks planned; later tied to the King quest (§5) |

**Where coin comes from:** killing goblins at the front (loot), and skilling +
selling to the general store. **Built:** the ranks, Duke Horacio's shop dialogue,
the armour-tier equip gate (you can't wear armour above your rank), and goblin coin
drops. Costs/tiers are tunable constants. `::title` shows your rank; `::settitle` /
`::givecoins` are admin test commands.

**Design rules**
- Rank gates **armour** (checked on equip); weapons aren't gated.
- Rank is bought and permanent (spending coin on gear never lowers it).
- Minister/King are prestige ranks for now; their non-armour perks come later.
- The same ladder will be reused per city as more cities open (§6).

---

## 3. Economy from gated resources ⬜

Titles turn the map into a progression of livelihoods. Each unlock is both a money
faucet and a skilling outlet:

- **Serf → woodcutting**: sell logs to the general store.
- **Cottager → fishing**: food for war survivability, surplus sold for coin.
- **Freeman → mining**: ore feeds smithing and trade.
- **Yeoman → smithing**: turn ore into armour/weapons — your own war gear and a
  product to sell.

Coin earned this way funds better equipment, which lets you fight harder, which
earns more political power. The economy and the war feed each other.

---

## 4. The battle system 🔶 (foundation ✅)

The signature feature: an **AI-commanded war** at Lumbridge (goblins vs. the
**Knights of Lumbridge**) across the three approaches. The roaming goblins hold the
open perimeter; the **castle defense is a discrete random event** — a *game of war*
the AI plays to win. Full detail in [`war-system-design.md`](war-system-design.md) §20.

- ✅ **Tiered raids, not a constant grind.** PEACE → a random raid rolls a finite
  goblin roster (Probe ~50 / Raid ~150 / Siege ~300) → resolve → PEACE. The
  `AttackDirector` ("War Brain") runs it.
- ✅ **A real AI duel.** Live perception (player levels + numbers), adaptive postures,
  a goblin/knight commander maneuvering finite forces across fields, and **focus-fire
  targeting that hunts the highest-level players first**.
- ✅ **Win conditions with stakes.** Goblins hunt **General Zo** at the castle — a
  breach makes the **city fall** (vendors/bank shut, slow recovery). Knights hunt the
  **Goblin Warlord** — routing it + wiping the roster **wins the defense** and pays
  **rare loot** to the players who fought.
- ✅ **Status HUD** (bottom-left, interface 1001): peace / raid tier + goblins / fallen.
- 🔶 **The deep AI** (formations, morale/retreat, baiting, **ranged & mage units**) is
  the planned next step — see the `WAR BRAIN ROADMAP` anchors.

---

## 5. NPC armies, raids & conquest ⬜ (long-term)

The end-game turns the player from soldier into commander. You raise and command
**NPC armies** and send them on **raids** and **campaigns**. Raids unlock new
power: skills, abilities, equipment tiers, and new territory.

Examples of the intended shape:
- **Raid the Wizards' Tower** → reward: **Protect from Magic**.
- **Conquer Dragon Isle**, defeat the **King Dragon** → unlocks **dragon**
  equipment / the dragon tier.
- Each conquered city becomes a new front you must hold, opening **city-vs-city**
  warfare (the Force system is the groundwork for this).

The **King of Lumbridge** quest sits at the top of the feudal ladder: a questline
that, once completed, makes the player the ruler of the city — with the authority
to direct its armies and reshape its laws.

**The hook already exists in-world.** 🔶 **General Zo** (the castle's defense
commander, built 2026-06) runs the auto-defense today and reports its status. His
dialogue already carries the (rank-gated) "**Take command of the defense**" and
"**Recruit troops under my banner**" options — *"only a Lord of Lumbridge may
command my knights."* When this §5 lands, a Lord/Minister/King unlocks exactly those
options: redirect the existing knight pool, buy a personal company, and march on
other cities. The War Brain's commander/allocation layers are the seam this plugs
into (no engine rework).

---

## 6. Cities & expansion ⬜

Lumbridge is city #1 and the template. Each city has:
- its own safe zone, citizenship, and respawn;
- its own war front and Force budgets;
- its own feudal ladder to climb (your title is per-city standing).

As cities open, the world becomes a map of contested holds linked by hostile
wilderness — venture between them only when strong or in numbers.

Backlog: name & rename the city clans/holds; populate the wider hostile world so
"only cities are safe" is literally true.

---

## 7. Known technical constraints

- **HUD overlays** (e.g. the siege progress bar) need a client-side interface in
  the cache. The server can push text/values to interfaces, but a brand-new
  on-screen widget requires cache work or repurposing an existing overlay.
- **NPC tile collision** doesn't exist in the engine, so units can stack at
  chokepoints. Mitigated with 1-v-1 claiming; a real collision pass is a future
  option.

---

## 8. Roadmap (sequencing)

1. ✅ **Battle foundation** — open-world war, Force/commander layer, city
   consequences (service shutdown, street invasion).
2. 🔶 **Perfect Lumbridge** — battle pacing/tuning, self-driving siege, the HUD bar.
3. 🔶 **Ranking system** — political power + feudal titles + progression + test
   commands. *(In progress now.)*
4. ⬜ **Gated economy** — title-locked woodcutting/fishing/mining/smithing + selling.
5. ⬜ **Command & raids** — lead NPC warbands; first raids (Wizards' Tower).
6. ⬜ **Conquest & cities** — Dragon Isle, city-vs-city, the King of Lumbridge quest.

> Legal framing: local single-developer learning project. Keep on localhost; never
> host publicly or monetize (Jagex IP/ToS).
