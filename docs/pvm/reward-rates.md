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

## Wilderness bosses (PR 3, `content/bosses/wilderness/`)

Main tables are the Kronos `drops/eco/<Name>.json` sub-tables folded flat (each sub-table
rescaled to ≈ equal weight). Uniques keep OSRS-era odds. All seven lairs are Wilderness PvP.

| Boss | Reward | Rate | Source | Review |
|---|---|---|---|---|
| Callisto / Vet'ion / Venenatis | Tyrannical / Ring of the gods / Treasonous ring | 1/512 | OSRS (pre-2023) | ⚠️ |
| Callisto / Vet'ion / Venenatis / Chaos Elemental | Dragon pickaxe | 1/256 | OSRS (pre-2023) | ⚠️ |
| Callisto / Vet'ion / Venenatis / Scorpia | Pets (cub / jr / spiderling / offspring) | 1/1000 | FoV (OSRS 1/2000) | ⚠️ |
| Chaos Elemental | Pet chaos elemental | 1/300 | OSRS | ⚠️ |
| Chaos Fanatic / Crazy Archaeologist / Scorpia | Odium + Malediction shards 1 / 2 / 3 | 1/256 each | OSRS | ⚠️ |
| Crazy Archaeologist | Fedora | 1/128 | OSRS | ⚠️ |
| Boss Tickets | Callisto/Vet'ion/Venenatis 25; Scorpia/Chaos Ele 20; Fanatic/Arch 15 | — | FoV | ⚠️ |
| Coin rows | Callisto 12–20k (8/~300), Vet'ion 15–20k (6/~330), Venenatis 15–20k (29/~330), Scorpia ≤3987, Chaos Ele 7.5k (4/~230), Fanatic ≤4k, Arch ≤4k | — | Kronos | ⚠️ |

## Slayer bosses (PR 4, `content/bosses/slayer/`)

Main tables are the Kronos `drops/eco/<Name>.json` sub-tables folded flat. Kraken 87 /
Cerberus 91 / Thermy 93 Slayer gates are engine-enforced. Skotizo costs a dark totem per kill.

| Boss | Reward | Rate | Source | Review |
|---|---|---|---|---|
| Kraken | Trident of the seas / Kraken tentacle | 1/512 / 1/400 | OSRS | ⚠️ |
| Kraken | Jar of dirt / Pet kraken | 1/1000 each | FoV (OSRS 1/1000 / 1/3000) | ⚠️ |
| Cerberus | Primordial / Pegasian / Eternal crystal, Smouldering stone | 1/512 each | OSRS | ⚠️ |
| Cerberus | Jar of souls / Hellpuppy | 1/1000 each | FoV (OSRS 1/2000 / 1/3000) | ⚠️ |
| Thermy | Occult necklace / Smoke battlestaff | 1/350 / 1/512 | OSRS | ⚠️ |
| Thermy | Dragon chainbody / Pet smoke devil | 1/1000 each | FoV | ⚠️ |
| Skotizo | Dark claw / Skotos | 1/25 / 1/65 | OSRS | ⚠️ |
| Skotizo | Uncut onyx / Jar of darkness | 1/128 / 1/500 | FoV (OSRS ~1/... / 1/2500) | ⚠️ |
| Skotizo | Ancient shards 1-3 + hard clue | always | OSRS | — |
| Demonic gorilla | Zenyte shard | 1/300 | OSRS | ⚠️ |
| Demonic gorilla | Ballista limbs / spring / Heavy frame / Monkey tail | 1/500 each | OSRS-ish | ⚠️ |
| Demonic gorilla | Coins | 15–25k at 7/~150 (Kronos had 150–250k GUARANTEED — cut ×10 and moved off the always tier) | FoV | ⚠️ |
| Boss Tickets | Kraken 20 · Cerberus 30 · Thermy 20 · Skotizo 30 · gorilla 5 | — | FoV | ⚠️ |

## Moons of Peril (PR 5, `content/minigames/moons/`)

OSRS-wiki rules; the Moons drop nothing themselves — the Lunar Chest pays.

| Reward | Rate | Source | Review |
|---|---|---|---|
| Common rolls per claim | 1 / 3 / 6 for 1 / 2 / 3 Moons subdued | OSRS | — |
| Unique per subdued Moon (one of that Moon's 4 pieces) | 1/56 (≈ 1/224 per piece) | OSRS | ⚠️ |
| Common table (weights /30) | atlatl darts 72–120 (5), blessed bone shards 160–179 (2), wyrmling bones 42–54 (1), sun-kissed bones 6–12 (3), swamp tar 79–119 (4), water orbs 30–45 (2), supercompost 6–12 (3), soft clay 15–25 (3), harralander 12–18 (3), irit 12–18 (1), maple seed 1–2 (2), yew seed 1 (1) | OSRS | — |
| Boss Tickets | 10 per subdued Moon | FoV | ⚠️ |
| Camp supply crates | 8 cooked bream per 3-minute cooldown (v1 stand-in for the gathering loop) | FoV | ⚠️ |
| Food heals | cooked bream 12, cooked moonlight antelope 26 (`Food.kt`) | OSRS (TUNE) | ⚠️ |

## Boss Ticket rates already live (for comparison)

| Boss | Tickets/kill | Source |
|---|---|---|
| Vorkath | 20 | FoV (existing) |
| Zulrah | 20 | FoV (existing) |
| Alchemical Hydra | 25 | FoV (existing) |
| GWD generals | 25 each | FoV (existing) |
| Fight Cave clear | 150 | FoV (existing) |
