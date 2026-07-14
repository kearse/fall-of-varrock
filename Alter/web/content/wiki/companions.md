---
title: Companions
category: Companions & Progression
summary: Recruit, train, gear and command your own levelable AI fighters - up to three at your side.
order: 1
updated: 2026-07-12
---

**Companions** are player-owned AI fighters - real combatants with real skills that level from combat, wearing real gear you give them. You can field up to **three** at once, in melee, ranged and mage archetypes. They spawn when you log in, follow you across the world, and fight beside you.

## Recruiting

Companions are recruited through **General Zo** at the castle. How many you may field is set by your [feudal rank](/wiki/titles-and-citizenship): a **Knight** commands 1, a **Lord** 2, and a **Minister** or **King** the full 3. Each starts with archetype-appropriate gear and a supply pack (brews, restores, sharks, karambwans and a combat potion - restocked free).

## Commands

Everything runs through `::companion` (and `::companions` to list your roster):

- `::companion follow` / `attack` / `train` / `deploy` / `return` - orders for all, or add a slot number for one. *Attack* is the aggressive escort: your companions fight any attackable monster near you, wherever you are, and fall in behind you when nothing's left. *Train* runs the goblin-camp levelling loop when you're around Lumbridge, and behaves like *attack* everywhere else. *Deploy* sends them at the city boss autonomously. *Follow* is a bodyguard, not a bystander: if anything attacks you - monster, PK bot or player - your following companions immediately fight back, and a player jumping you takes priority over whatever they were hitting. *Return* recalls them and never picks a fight (your safe pull-out order). They'll never turn on friendly troops - allied knights and General Zo are off-limits, and they won't steal a monster another player is already fighting.
- `::companion equip <slot> <itemId>` - give them gear straight from your bank (weapons honour level requirements, and their armour is capped by **your** feudal rank, same as your own). `unequip` returns it; `gear <slot> <equipSlot>` lists what in your bank fits.
- `::companion style <slot> <0-3>` - set attack stance.
- `::companion spell <slot> <name|auto>` - mages can lock a spell or auto-scale to their Magic level.
- `::companion retaliate <slot>` - toggle auto-retaliation.
- `::companion rename <slot> <name>` - name them; the world addresses them as "Sir <Name>".

**Donor perk:** `::companion loot` toggles auto-looting - your companion banks nearby drops for you. No command needed in the custom client: the Companions side panel has an **Auto-loot ALL to bank** button on the roster, plus a per-companion toggle in each companion's detail view.

## Levelling & death

- Companions earn **real XP** from their kills - a veteran companion is measurably stronger than a fresh recruit.
- When one falls, it isn't gone: it waits at General Zo for **revival** (a fee), keeping its levels and gear.
- In city boss fights, companion damage counts toward **your** share of the pooled loot.

## Why they matter

Companions are the solo player's answer to group content - a reliable duo partner for bosses, an extra body in war raids, and someone to watch your back on the roads. Gear them like you'd gear an alt.
