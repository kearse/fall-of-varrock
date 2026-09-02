---
title: The War explained
category: The War
summary: Campaigns, conquests, troop command and how pooled war loot is split by contribution.
order: 1
updated: 2026-07-11
---

The War is the server's endgame. Enemy forces hold every city beyond Lumbridge, and high-ranking players spend real coin to launch offensives against them - with every participant paid out of a pooled reward split by **personal contribution**.

## How an offensive works

A ranked player sponsors an operation, allied knight troops march out from Lumbridge alongside the players, and the fight runs until the victory condition is met or the operation times out. When it succeeds, the loot pool is split (see below).

The commanders' target is [Fallen Varrock](/wiki/fallen-varrock). The city stays fallen - a campaign there is a **temporary battlefield victory** over the host that holds it, never a reclamation: the **risen dead** on the southern approach and lower square, **skeletal warriors** through the square, and the **undead champions** dug in at the ruined palace. The deeper the line, the richer the kills. The rogue occupiers - marauders and Black Knights - hold the road *outside* the walls, where the public marches fight.

| Operation | Rank needed | Cost | Realm Supplies | Allied force | Win condition | Reward pool |
| --- | --- | --- | --- | --- | --- | --- |
| **March** (scheduled) | - anyone joins | free | - | 10 knights | Kill 15 at the target | Pooled spoils + 1-3 Commendations |
| **Grand March** (every 8th) | - anyone joins | free | - | 16 knights | Kill 20; fell the Warden | Pooled spoils + up to 5 Commendations + Warden's embers |
| **Operation** | Lord+ starts, anyone joins | 500,000 gp | - | 10 knights | Kill 15 at the target | Pooled spoils + Commendations; sponsor's tithe |
| Send troops | Lord+ | 1,000,000 gp | - | 8 knights | Boss raid support | Damage share |
| Summon boss | Lord+ | 3,000,000 gp | - | Backing raid | Kill the boss | Boss drops (damage split) |
| Campaign (Varrock) | Minister+ | 3,000,000 gp | 1,500 | 40 knights | Kill 60 of the garrison | 750,000 gp + 25 prestige |
| Conquest (Varrock) | King | 15,000,000 gp | 2,800 | 64 knights | Kill 140 of the garrison | 3,000,000 gp + 60 prestige |

The scheduled **marches** are the free, no-rank way in - see [Marches - the realm's warband](/wiki/marches-and-the-reconquest) for the targets, Wardens, Lord operations and the wanted captains. **Rank only ever gates starting a war** - every citizen may join any war that is under way.

Commands: `::march`, `::marches`, `::operation`, `::bounties`, `::sendtroops`, `::summonboss corporeal_beast`, `::campaign`, `::conquest`. Check the Realm Supplies stockpile anytime with `::supply` - it is filled by skillers handing supplies to the Quartermaster in The Mire (see [Skilling & the war effort](/wiki/skilling-overview)) and spent only by campaigns and conquests.

## Troop command

Troops you sponsor fight autonomously by default. Switch their orders with:

- `::troops advance` - fight on their own (default)
- `::troops follow` - form up and escort you

Lords who field troops earn a damage share for everything their squad kills - commanding well pays.

## How loot is split

When a campaign or conquest is won, the pooled reward is divided like this:

- The **commander** gets their war-stake back **plus a 10% tithe** off the top - rank has its privileges. This does **not** auto-bank: it's held as **war spoils** the commander collects with **`::claim`** (a themed claim window - Bank All, or take pieces one at a time). A short banner pops the moment the war is won.
- The rest is split by **contribution** - your time and activity in the battle is tracked every game tick - and paid **straight to your bank**, no loot scramble. (The commander earns a contribution share too, as a fighter; only their *command* cut goes to `::claim`.)
- **Donors** earn a bonus +1% of the pool value as donor points, minted on top (it never comes out of other players' shares).
- Everyone who took part earns **prestige / war effort points** toward their next rank.

## 3rd age relics - the war's prestige drop

The [3rd age](/wiki/path-to-end-game) antique sets are earned in the war, two ways:

- **Battlefield drop** - while a campaign or conquest is live, every enemy you cut down on the battlefield has a small chance (**~1/5,000**) to drop a random 3rd age piece straight to you. Server-sized rare: a piece surfaces only every few wars, so wearing one says you fought for it.
- **Leadership relic** - the **Minister or King who wins the war** has a **1/1,000** chance to earn a random piece on top of their coin. It lands in their **`::claim`** war-spoils box alongside the tithe, the moment the war is won.

Both are announced realm-wide. The Quartermaster still sells 3rd age as a slow, tripled-price pity-path, and buys pieces back from Lord+ at a third of shelf value - but the drop is the real story.

## Lumbridge is never besieged

The war is fought *out* of Lumbridge, never against it: the Last Free City's shops, bank and gates stay open no matter how the offensives go. General Zo at the castle reports the live war and musters your [companions](/wiki/companions); the frontier goblins outside the gate are the recruit's training ground, not a siege.
