# PvM reward-rate ledger (Team 2 review sheet)

Every drop rate Team 4 ships is listed here so the economy team can review them in one
place. "Source" says where the number comes from: **OSRS** = the live-game rate (classic
bosses keep classic loot), **Kronos** = the donor server's rate, **FoV** = our own number
(FoV-original content, needs review). Change a rate in code → change it here.

Legend for Team 2: ✅ reviewed · ⚠️ needs review · — n/a.

## Barrows (PR 1, `content/minigames/barrows/`)

| Reward | Rate | Source | Review |
|---|---|---|---|
| Rolls per chest | `1 + brothers killed`, max 7 | OSRS | — |
| Reward potential | Σ combat levels killed (cap 1000) + 2/brother, max 1012 | OSRS | — |
| Any of 24 Barrows pieces, per roll | `1 / (450 − 58 × brothers)` → 1/102 with six | OSRS | ⚠️ |
| Non-unique band, per roll | draw `1..potential`: coins ≤380, mind ≤505, chaos ≤630, death ≤755, blood ≤880, bolt racks ≤1005, key halves ≤1011, dragon med helm = 1012 | OSRS | ⚠️ |
| Band quantities | coins 2–774, mind 253–336, chaos 112–139, death 70–83, blood 37–43, racks 35–40 (linear across band) | OSRS | ⚠️ |
| Elite clue, per chest | `1 / max(29, 200 − 29 × brothers)` | OSRS | ⚠️ |
| Boss Tickets, per chest | 3 × brothers killed (max 18) | FoV | ⚠️ |
| Tunnel vermin drops | none (potential only) | FoV | ⚠️ |

Estimated value: a six-brother chest is ~7 rolls; expected Barrows pieces ≈ 0.07/chest
(one piece every ~14 chests), matching OSRS. No raw-GP faucet beyond the coin band
(≤774 coins per roll).

## Lair bosses (PR 2, `content/bosses/lairs/`)

Main tables are the Kronos `drops/eco/<Name>.json` sub-tables folded flat (equal sub-table
weight). Uniques keep OSRS odds; pets and the KBD chase items use the Vorkath-pilot scale.

| Boss | Reward | Rate | Source | Review |
|---|---|---|---|---|
| KBD | KBD heads | 1/128 | OSRS | ⚠️ |
| KBD | Dragon pickaxe | 1/1000 | OSRS (1/1000) | ⚠️ |
| KBD | Draconic visage | 1/1000 | FoV (OSRS 1/5000, Vorkath pilot scale) | ⚠️ |
| KBD | Prince black dragon | 1/1000 | FoV (OSRS 1/3000) | ⚠️ |
| KBD | Boss Tickets | 20/kill | FoV | ⚠️ |
| Giant Mole | Baby mole | 1/1000 | FoV (OSRS 1/3000) | ⚠️ |
| Giant Mole | Boss Tickets | 15/kill | FoV | ⚠️ |
| Kalphite Queen | Dragon chainbody, Dragon 2h, KQ head, Jar of sand | 1/128 each | OSRS | ⚠️ |
| Kalphite Queen | Kalphite princess | 1/1000 | FoV (OSRS 1/3000) | ⚠️ |
| Kalphite Queen | Boss Tickets | 25/kill | FoV | ⚠️ |
| Dagannoth Kings | Berserker/Warrior (Rex), Seers + Mud staff (Prime), Archers + Seercull (Supreme), Dragon axe (all) | 1/128 each | OSRS | ⚠️ |
| Dagannoth Kings | Pets | 1/1000 each | FoV (OSRS 1/5000) | ⚠️ |
| Dagannoth Kings | Boss Tickets | 15/king | FoV | ⚠️ |
| All four | Coin rows | KBD none; Mole none; KQ 15–20k (1/12 of "Other"); DKS 126–3000 | Kronos | ⚠️ |

## Boss Ticket rates already live (for comparison)

| Boss | Tickets/kill | Source |
|---|---|---|
| Vorkath | 20 | FoV (existing) |
| Zulrah | 20 | FoV (existing) |
| Alchemical Hydra | 25 | FoV (existing) |
| GWD generals | 25 each | FoV (existing) |
| Fight Cave clear | 150 | FoV (existing) |
