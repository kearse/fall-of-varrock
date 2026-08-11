---
title: PK Training Arena
category: Minigames & Bosses
summary: A Duel Arena sparring ground that teaches you to PK. Build any loadout in the LMS-style kit editor (or bring your own gear), fight a matched bot at Easy/Medium/Hard, keep nothing, lose nothing, gain XP.
order: 9
updated: 2026-07-19
infobox: Type = Sparring ground (duel-style rounds); Location = Duel Arena; Entry = Talk to Ardan the Ripper (or `::pktrain`); Kits = Fully editable (Dharok's / NH presets + 3 save slots) / Bring-your-own; Opponent = Matched sparring bot (Easy / Medium / Hard); Keep gear = No; Death = Safe (no item loss); XP = Full; Leave = Walk out or `::unkit`
---

The **PK Training Arena** is a sparring ground at the **Duel Arena** built to teach the actual
skills of PKing - **1-ticking combos, spec timing, PID, prayer and gear switching, and
Vengeance** - without risking a single item. It is **not** Last Man Standing: no lobby, no fog,
no loot chests. You get a kit, you get an opponent, and you fight until you've got the tempo down.

## Getting in

Head to the Duel Arena and talk to **Ardan the Ripper**, the battle-scarred mercenary who runs
the grounds - or use `::pktrain` to teleport straight to him.

He offers you two ways to fight:

- **The kit locker** - the LMS-style **kit editor**. Start from a **Dharok's** or **NH tribrid**
  preset, then change absolutely anything: every worn slot and all 28 inventory slots are yours to
  edit from the armoury, with your spellbook and the bot's difficulty on the same screen. **Save**
  up to three custom kits to your account - they're waiting on every visit (and can be loaded from
  your own bank anywhere with `::kit`, see below).
- **Bring your own gear** - spar in your own setup.

Then the bout runs **just like a real duel**: you're teleported into a **private copy of the
fight pit** — every trainee gets their own, so the whole server can train at once and nobody can
walk in on your lesson — with your opponent standing across from you, a **3... 2... 1... FIGHT!**
countdown, then it's live. When one of you falls, you're teleported back to Ardan, healed and
ready. Talk to him for **another round** (same setup in one click), a new setup, or to hand the
kit back.

## The rules that make it safe

- **You keep nothing.** A loaned kit exists only **during the fight**: the moment a round ends -
  win, lose, leave, or log out - the kit returns to Ardan automatically and you're back in your
  own gear. Your real inventory, equipment and spellbook are stored safely first and returned
  intact - a server restart can't lose them.
- **Borrowed gear is sealed.** While kitted you can't bank, trade, drop, stake, alchemise, or hand
  items to a companion - the kit can't leave the fight by any route.
- **You lose nothing.** The whole arena is a **safe zone**: dying drops no items - it just ends
  the round and puts you back at the trainer, ready to go again immediately.
- **You still gain XP.** You fight at your **real levels**, so every hit trains your combat stats
  as normal. The one exception is Magic: it's boosted and your book is set to **Lunar** so you can
  practise **Vengeance** at any level.

## Your sparring partner

There's **always someone to fight**, even when the server is quiet - the trainer summons a
**bot opponent** matched to your kit for each round. Pick your challenge:

| Difficulty | What you're facing |
| --- | --- |
| **Easy** | A beginner - won't use protection prayers, panics and eats early. Good for drilling combos and tick-eating without pressure. |
| **Medium** | A solid PKer - prays, switches, and specs you like a competent opponent. |
| **Hard** | A maxed sweat - full prayer switching, gear swaps off your overhead, and spec combos to finish you. This is the real thing. |

The bot reads your **overhead prayer** and switches its attack style to whatever you're *not*
protecting - so if you sit one prayer, it punishes you. Switching your own prayer correctly is
half the lesson.

**Companions stand down while you spar** - this is your lesson, not theirs. They wait outside
the pit and rejoin you the moment the round ends.

## What to practise

- **1-ticking** - chain your spec and combo food/karambwan in the same tick to burst damage.
- **Prayer switching** - flick to the bot's attack style; watch it switch off yours and re-flick.
- **Gear switching (NH)** - swap to the style your opponent isn't praying, mid-fight.
- **Spec timing** - open or finish with your spec weapon when it counts, not on cooldown.
- **Vengeance** - pop veng before you take a big hit and reflect it back. Learn the 30-second timing.
- **PID awareness** - feel out who "wins the tick" when you both attack on the same game cycle.

Start on **Easy** to get your switches clean, then move up. When you're done, just walk out of the
arena - the gear stays with Ardan, and whatever you learned comes with you.

## Building, saving and loading kits (::kit)

The same saved kits do double duty outside the arena. Type `::kit` (or `::kits`) **anywhere** and
the kit editor appears in bank mode: pick a saved kit, build one from your bank's items, or press
**Wearing** to copy your current worn gear and inventory - the full setup, spellbook included -
into the editor. Tweak it however you like, then press **Save** to keep it in one of your three
kit slots (double-click the kit's name to rename it).

Building feels like using your bank. The browser shows your bank with
real stack counts - **type in the search bar to filter it live**, and use the **All / Gear /
Food / Pot / Misc** tabs to browse by type. **Click** a bank item to withdraw it into the kit's
pack, or **right-click** for options: Equip puts gear straight onto the doll (ammo equips its
whole stack), and food, potions and other stackables get Withdraw-5/10/X/All. In the kit's pack,
**clicking gear equips it** (two-handers and shields swap correctly, displaced pieces drop back
into the pack) and clicking supplies deposits them - right-click for the full Equip/Deposit menu.

**Load kit** re-arms you **from your own bank in one click**: everything you're carrying is
deposited, then the kit is withdrawn and equipped, exact slot layout and all - and it works
**anywhere outside dangerous places** (no wilderness, duels, or minigames; you don't need a bank
open). In a hurry? `::kit <1-3>` skips the editor and loads that saved slot directly.

Bank mode never creates items: anything the kit lists that **isn't in your bank is skipped** and
reported, and gear you don't meet the requirements for stays in your pack instead of being worn.
