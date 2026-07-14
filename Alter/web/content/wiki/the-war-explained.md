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

The first target is [Fallen Varrock](/wiki/fallen-varrock). The city has no army left - a campaign there is a **purge of the occupiers** who run the loot hub: **marauders** on the southern approach, **Black Knights** holding the square, and the **rogue captains** dug in at the ruined palace. The deeper the line, the richer the kills.

| Operation | Rank needed | Cost | Supply needed | Allied force | Win condition | Reward pool |
| --- | --- | --- | --- | --- | --- | --- |
| **March** (scheduled) | - anyone joins | free | 150 supply | 10 knights | Kill 15 in the district | Pooled spoils + 1–3 Commendations |
| **Grand March** (every 8th) | - anyone joins | free | 300 supply | 16 knights | Kill 20; fell the Warden | Pooled spoils + up to 5 Commendations + Warden's embers |
| Send troops | Lord+ | 1,000,000 gp | - | 8 knights | Boss raid support | Damage share |
| Summon boss | Lord+ | 3,000,000 gp | - | Backing raid | Kill the boss | Boss drops (damage split) |
| Campaign (Varrock) | Minister+ | 3,000,000 gp | 1,500 supply | 40 knights | Kill 60 frontier enemies | 750,000 gp + 25 prestige |
| Conquest (Varrock) | King | 15,000,000 gp | 2,800 supply | 64 knights | Kill 140 enemies | 3,000,000 gp + 60 prestige |

The scheduled **marches** are the free, no-rank way in - see [Marches & the reconquest](/wiki/marches-and-the-reconquest) for districts, Wardens and the wanted captains. Broken districts shave 10% each off a campaign/conquest kill quota on Varrock.

Commands: `::march`, `::districts`, `::bounties`, `::sendtroops`, `::summonboss corporeal_beast`, `::campaign`, `::conquest`. Check the realm's war stores anytime with `::supply` - the meter is filled by skillers handing supplies to the Quartermaster in The Mire (see [Skilling & the war effort](/wiki/skilling-overview)).

## Troop command

Troops you sponsor fight autonomously by default. Switch their orders with:

- `::troops advance` - fight on their own (default)
- `::troops follow` - form up and escort you

Lords who field troops earn a damage share for everything their squad kills - commanding well pays.

## How loot is split

When a campaign or conquest is won, the pooled reward is divided like this:

- The **sponsor keeps a 10% tithe** off the top - rank has its privileges.
- The rest is split by **contribution** - your time and activity in the battle is tracked every game tick.
- **Donors** earn a bonus +1% of the pool value as donor points, minted on top (it never comes out of other players' shares).
- Everyone who took part earns **prestige / war effort points** toward their next rank.
- Coins are paid **straight to your bank** - no loot scramble.

## Defending the realm

When enemy raids hit a city's frontier, defenders earn War Effort for their kills. A successful defence pays coin bounties (scaling with contribution, capped at 50,000 gp) and rolls a rare-loot table - dragon scimitar, dragon dagger, rune platebody, abyssal whip and more. The player who lands the finishing blow is MVP and gets a **guaranteed** rare.
