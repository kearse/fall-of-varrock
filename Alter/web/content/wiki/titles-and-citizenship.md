---
title: Titles & citizenship
category: The War
summary: The feudal rank ladder - what each title costs, what it unlocks, and why armour is rank-gated.
order: 3
updated: 2026-07-08
---

Everyone is a citizen of a city (Lumbridge, for now - it's the only free one), and every citizen has a **feudal rank**. Rank is bought with coins from **Duke Horacio** in Lumbridge Castle, and it gates three things: the heaviest metal armour you may wear, how many [companions](/wiki/companions) you can field, and your political power in the War.

Check your current rank, armour tier and next rank price with `::title`. Check your citizenship with `::city`.

## The ladder

| Rank | Cost | Melee armour | Ranged armour | Magic armour | Companions | War powers |
| --- | --- | --- | --- | --- | --- | --- |
| Peasant | Free | Bronze & Iron | Leather | Basic robes | - | - |
| Commoner | 10,000 gp | Steel | Studded | - | - | - |
| Squire | 50,000 gp | Black | Snakeskin | - | - | Name shown in colour |
| Soldier | 150,000 gp | Mithril & Adamant | Green d'hide | - | - | - |
| Knight | 500,000 gp | Rune, granite, fremennik, void | Blue/red & blessed d'hide | Mystic, splitbark, enchanted, infinity | 1 | - |
| Lord | 2,000,000 gp | **All armour** - Dragon, barrows, godwars, endgame sets | Black d'hide, karil's, armadyl | Ahrim's, ancestral | 2 | `::sendtroops`, `::summonboss` |
| Minister | 10,000,000 gp | All armour | All armour | All armour | 3 | + `::campaign` |
| King | 50,000,000 gp | All armour | All armour | All armour | 3 | + `::conquest` |

From Squire up, your title displays in colour over your head - rank here is earned status, not a cosmetic you buy in a store.

## Rank capes

Every noble rank (Squire and up) comes with a **rank cape**, handed to you the moment you buy the rank. The capes go from plain to elaborate up the ladder, and each one's colour echoes the rank's name colour - so you can read someone's standing off their back at a glance:

| Rank | Cape | Cape bonuses (attack / defence / strength / prayer) |
| --- | --- | --- |
| Squire | Squire's cape - plain green wool | 0 / +1 / 0 / +1 |
| Soldier | Soldier's cape - plain blue wool | +1 / +2 / 0 / +1 |
| Knight | Knight's cape - white with the star of knighthood | +2 / +4 / +1 / +2 |
| Lord | Lord's cape - black with the purple wreath of lordship | +3 / +6 / +2 / +3 |
| Minister | Minister's cape - deep crimson cape of state | +4 / +8 / +3 / +4 |
| King | King's cape - gold-embroidered ceremonial cape | +5 / +10 / +4 / +6 |

Attack and defence bonuses apply to **all combat styles** - ranks aren't style-bound. Wearing a rank cape needs at least that rank (a Knight's cape on someone's back proves they're a Knight at minimum), so like the armour gate, the cape is proof of standing, not a costume. The capes are untradeable, and the King's cape deliberately stays a shade under the [fire cape](/wiki/fight-cave) - the earned combat capes remain the trophies.

Lost yours? `::cape` reclaims your current rank's cape for free.

All three combat styles climb the same ladder: each ranged/magic family is pegged to the metal rung of similar strength (studded ≈ steel, green d'hide ≈ adamant, mystic ≈ rune, black d'hide ≈ dragon). Untiered utility gear - monk robes, vestments, graceful, basic wizard robes - is free for everyone.

## Why gate armour?

**Armour needs both your levels and your rank.** All gear keeps its classic level requirements - 40 Defence for rune armour, 40 Attack for a rune scimitar - and armour is *additionally* rank-gated: a maxed Peasant still cannot wear steel, and a level-3 Lord still needs the Defence for dragon. There are no quest requirements on any armour or weapon. (Weapons are never rank-gated - levels alone decide what you can wield.) Your companions live under the same ceiling: they can't wear armour above **your** rank, and each piece is checked against the companion's own levels.

The armour gate is the server's core progression brake: you can't buy your way into rune on day one - you climb the ladder, and each rung both marks your standing and funds the realm (rank fees are a major coin sink). It also means seeing someone in dragon tells you something real: they're a **Lord** at minimum.

## Rank and the War

Lords and above stop being just soldiers and become **commanders**: they pay to field troops, summon city bosses and (at Minister/King) launch campaigns and conquests - and take a 10% tithe from the pooled loot when their operation succeeds. See [The War explained](/wiki/the-war-explained).
