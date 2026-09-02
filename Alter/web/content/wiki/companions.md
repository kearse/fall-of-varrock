---
title: Companions
category: Companions & Progression
summary: Recruit, train, gear and command your own levelable AI fighter - one at your side, a roster that grows with your rank.
order: 1
updated: 2026-09-02
---

**Companions** are player-owned AI fighters - real combatants with real skills that level from combat, wearing real gear you give them. **One companion stands beside you at a time**, whoever you are; your [feudal rank](/wiki/titles-and-citizenship) sets how many you may *keep* on your banner and swap between. They come in melee, ranged and mage archetypes, spawn when you log in, follow you across the world, and fight beside you.

## Recruiting and the roster

Companions are recruited through **General Zo** at the castle. Your rank sets your **roster** - how many soldiers you may keep: a **Knight** keeps 1, a **Lord** 2, and a **Minister** or **King** 3. Only one of them is ever in the world with you; the rest wait **on the bench** with their levels and gear intact. Each starts with archetype-appropriate gear and a supply pack (brews, restores, sharks, karambwans and a combat potion - restocked free).

Recruit with someone already at your side and the new soldier goes straight to the bench, ready to swap in.

## Swapping, dismissing, summoning

- `::companion dismiss` - send your fielded companion off duty. He leaves the world but not the roster.
- `::companion summon <slot>` - call a benched companion to your side. If someone is already fielded, this is a **swap**: he stands down first, the other steps up. (A companion who is being attacked by another player can't stand down for 10 seconds - the same rule as logging out.)
- The Companions side panel in the custom client does all of this with buttons; benched companions show as cards you can summon.

## Where companions stand down

Some content is yours alone. Your companion **stands down automatically** - with a message saying why - when you enter the **Fight Cave** or a **solo boss instance** (Vorkath, Zulrah, the Alchemical Hydra), and **rejoins you the moment you leave**. Summoning inside is refused with the same reason. Shared-world fights (marches, God Wars, the Wizard Tower) are open to companions; classic bosses are never redesigned around them - they simply sit those out.

## Commands

Everything runs through `::companion` (and `::companions` to list your roster):

- `::companion follow` / `attack` / `train` / `deploy` / `return` - orders for your companion (add a slot number to be explicit). *Attack* is the aggressive escort: your companion fights any attackable monster near you, wherever you are, and falls in behind you when nothing's left. *Train* runs the goblin-camp levelling loop when you're around Lumbridge, and behaves like *attack* everywhere else. *Deploy* sends him at the city boss autonomously. *Follow* is a bodyguard, not a bystander: if anything attacks you - monster, PK bot or player - your following companion immediately fights back, and a player jumping you takes priority over whatever he was hitting. *Return* recalls him and never picks a fight (your safe pull-out order). He'll never turn on friendly troops - allied knights and General Zo are off-limits, and he won't steal a monster another player is already fighting.
- `::companion equip <slot> <itemId>` - give him gear straight from your bank (weapons honour level requirements, and his armour is capped by **your** feudal rank, same as your own). `unequip` returns it; `gear <slot> <equipSlot>` lists what in your bank fits.
- `::companion style <slot> <0-3>` - set attack stance.
- `::companion spell <slot> <name|auto>` - mages can lock a spell or auto-scale to their Magic level.
- `::companion retaliate <slot>` - toggle auto-retaliation.
- `::companion rename <slot> <name>` - name him; the world addresses him as "Sir <Name>".
- `::companion bones [slot]` - hand over every bone in **your** inventory: the companion buries them for Prayer XP (the same per-bone rates you'd get).
- `::companion archetype <slot> <melee|range|mage>` - **re-school** a companion to another combat style. His name, every skill he has trained and his gear all carry over, so one companion can master all three schools over his lifetime.

**Donor perk:** `::companion loot` toggles auto-looting - your companion banks nearby drops for you. No command needed in the custom client: the Companions side panel has an **Auto-loot to bank** toggle.

## Levelling & death

- Companions earn **real XP** from their kills - a veteran companion is measurably stronger than a fresh recruit.
- Their **combat skills** (Attack, Strength, Defence, Hitpoints, Ranged, Magic, Prayer) train from fighting; every **non-combat skill is already 99** - they're veteran knights, not skillers.
- **Prayer** trains through `::companion bones` - and it matters: in a fight a companion runs the best offence prayer his Prayer level unlocks (Burst of Strength up through Piety, Rigour or Augury by school), and protects against a player attacker's combat style once his level allows the protection prayers. Out of combat the prayers drop and his points recharge on their own.
- Locked into one style? Not any more: `::companion archetype` re-schools him, keeping all trained skills - so a melee veteran can pick up a crossbow without starting over.
- When he falls he simply **respawns at your side** with his levels and gear - there is no revival fee.
- In city boss fights, companion damage counts toward **your** share of the pooled loot.

## Why they matter

A companion is the solo player's answer to group content - a reliable duo partner for shared-world bosses, an extra body in marches, and someone to watch your back on the roads. Gear him like you'd gear an alt.
