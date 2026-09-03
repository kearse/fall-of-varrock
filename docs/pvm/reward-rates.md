# PvM reward-rate ledger (Team 2 review sheet)

Every drop rate Team 4 ships is listed here so the economy team can review them in one
place. "Source" says where the number comes from: **OSRS** = the live-game rate (classic
bosses keep classic loot), **Kronos** = the donor server's rate, **FoV** = our own number
(FoV-original content, needs review). Change a rate in code → change it here.

Legend for Team 2: ✅ reviewed · ⚠️ needs review · — n/a.

**Per-boss cache-value expectation** (expected qty × cache cost per kill, per hour, 0.7× realised, band
vs Team 2's mid/endgame bands): `docs/pvm/cache-value-expectation.md`.

**Team 2 review, 2026-09-03: every table below is approved as-is** (all rows at or below the mid band;
FoV-original content being low in gp terms is the design — its value is War-Forging inputs + War Effort).
Standing conditions from Team 2: **no new coin rows anywhere without their sign-off** (coins are the one
thing their sink caps cannot touch; Zulrah's 17k/kill is the only coin-dominated row and stays), and
Boss Tickets are being retired — `BossDeath.payout(tickets =)` is the single seam to no-op when they ping.
The ⚠️ marks below are therefore historical; new or changed rates re-open review.

**Boss Tickets retired 2026-09-03 (economy #336 + Team 4 follow-up):** every "Boss Tickets" row below is
void — `BossDeath.payout` no longer takes a ticket count and no boss or minigame awards them. The two
cosmetics that lost their shelf (Champion's cape 22114, Divine halo 12637) are now `BossKills`
milestones at 100 and 500 total boss kills (no economy value).

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

## Fallen Varrock PvM layer (PR 6, `content/pvm/varrock/`)

FoV-original. Materials: **Varrock salvage** (numulite 21555 renamed, stackable, tradeable, cost 0)
and **Relic of old Varrock** (relic part 2373 renamed, tradeable, cost 0) — the War-Forging
ingredients the war team's recipes will consume (their wiring). War Effort / Commendations go
through `addPoints` / `WarForge.awardCommendations`.

| Source | Reward | Rate | Review |
|---|---|---|---|
| Elite undead (7 kinds, 16 posts) | salvage 1–3 (30/100), runes/food/bars/coins 500–2500 (12/100) | per kill | ⚠️ |
| Elite undead | Relic | 1/60 | ⚠️ |
| Salvage pile (28, 90 s refill) | salvage 2–5; relic 1/40; 1/8 wakes an elite | per search | ⚠️ |
| Malachai the Hollow (every ~20 min) | 2 relics + 8–14 salvage always; supplies; 15 tickets, 20 WE, 3 Commendations | per kill | ⚠️ |
| Palace Warden (15-min respawn) | 1–2 relics + 12–20 salvage + 2 dragon bones always; supplies; 30 tickets, 40 WE, 5 Commendations; Warden's ember 1/8 | per kill | ⚠️ |
| Arrav Intelligence | Purge 15 WE + 2 Comm + 5 salvage · Salvage 12 WE + 2 Comm + 1/6 relic · Bounty 25 WE + 4 Comm + relic · Warden 40 WE + 6 Comm + 2 relics | per assignment | ⚠️ |

## Senntisten Expeditions (PR 7, `content/pvm/senntisten/`)

FoV-original. One 12-minute solo run = 3 waves + the Custodian.

| Source | Reward | Rate | Review |
|---|---|---|---|
| Wave cleared (×3) | Varrock salvage 2–4 | per wave | ⚠️ |
| The Custodian | 2 relics + 10 salvage + 1 expedition log (untradeable, old journal 1493 renamed) always; runes/bars/bones/restores main; 25 tickets, 30 WE, 4 Commendations | per kill | ⚠️ |

## Story bosses (PR 8, `content/pvm/story/`)

FoV-original, quest-gated once Team 3 lands the quests (Knight rank meanwhile). Warden's embers
are the top War-Forging material; `arravs_axe` (30320) is untradeable in the cache — a cosmetic
chase, not a market item (Team 2: flip via YAML if you want it traded).

| Boss | Reward | Rate | Review |
|---|---|---|---|
| Zemouregal (1200 HP, +Arrav ally) | 3 relics + 15 salvage always; runes/bars/bones/potions main; 40 tickets, 50 WE, 6 Commendations | per kill | ⚠️ |
| Zemouregal | Warden's ember | 1/5 | ⚠️ |
| Zemouregal | Arrav's axe (cosmetic) / Mahjarrat notes a–j / k–z (lore) | 1/150 / 1/40 / 1/40 | ⚠️ |
| The Convergence (1500 HP) | 4 relics + 20 salvage + **1 Warden's ember** always; high runes (incl. wrath)/bars/bones/potions main; 60 tickets, 80 WE, 10 Commendations | per kill | ⚠️ |

## Wintertodt (PR 9, `content/minigames/wintertodt/`)

Kronos donor + OSRS wiki. Points: light 25, fix 25, root 10, kindling 25, heal 30; crate per 500
(+ proportional chance of one more). XP per donor (feed root 3×FM + cur+5, kindling 3.8×FM, light
6×FM, fix 4×Con, fletch 0.6×Fle, chop 0.3×WC, end 100×FM).

| Reward | Rate | Source | Review |
|---|---|---|---|
| Crate supply rolls | 3 per crate; logs/ores/herbs/seeds/fish/gems/essence/bones weighted, FM-gated tiers; **no coins** | FoV (OSRS shape) | ⚠️ |
| Burnt page | 1/45 per crate | OSRS-ish | ⚠️ |
| Pyromancer garb / robe / hood / boots, warm gloves, bruma torch | 1/150 each per crate | OSRS | ⚠️ |
| Tome of fire (empty) | 1/1000 per crate | OSRS | ⚠️ |
| Phoenix | 1/5000 per crate | OSRS | ⚠️ |

## Pest Control (PR 9, `content/minigames/pestcontrol/`)

| Reward | Rate | Source | Review |
|---|---|---|---|
| Pest Control points per win (attr `pest_points`; never "commendations" — that word is the war team's currency) | novice 10 / intermediate 15 / veteran 20 (activity ≥ 50) | FoV (Kronos 14/24/30, OSRS 2/3/4) | ⚠️ |
| Coins per win | none (Kronos paid 3–7k) | FoV rule | — |
| Armoury prices | top/robe/mace 250, gloves 150, helms 200, elite top/robe 200 + plain piece | OSRS | ⚠️ |
| Party minimum / departure | 1 player / 60 s | FoV (low pop) | ⚠️ |

## Guardians of the Rift (PR 11, `content/minigames/gotr/`) — Team 2 approved 2026-09-03

OSRS wiki rates exactly (never above). Pearls untradeable/stackable, Rewards-Guardian-only;
Temple Supplies wares guarded (`SpecialShopGuard`).

| Reward | Rate | Source | Review |
|---|---|---|---|
| Points | 1 per 100 energy of each type (+ fractional chance); XP = RC level × 45 at ≥300 energy | OSRS | ✅ |
| Search (weights /140) | pearls 14–16 (18), pouches (15), elemental runes 400–500 (4 each), mind 250–400 (4), body 80–150 (4), chaos/cosmic/nature/law/death/blood (10 each, OSRS ranges), talismans (16), intricate pouch (5), abyssal ashes (1), needle (1) | OSRS | ✅ |
| Rares per search | Atlax's diary 1/20 once, catalytic talisman 1/200, abyssal needle 1/300 once, lantern 1/700, dyes 1/1200 each, abyssal protector 1/4000 | OSRS | ✅ |
| Temple Supplies (pearls) | raiments 400/350/350/250, lantern 1500, needle 750, catalytic talisman 100, elemental talisman 50 | OSRS | ✅ |

## Blast Furnace (PR 12, `content/minigames/blastfurnace/`) — Team 2 approved 2026-09-03

| Item | Value | Source | Review |
|---|---|---|---|
| Coffer | 72,000 coins/hour (1,200/min while ore is in the machine), 25,000 minimum deposit, everyone pays — a pure gp sink | OSRS official-world model (Team 2) | ✅ |
| Coal per bar | steel 1 / mithril 2 / adamant 3 / rune 4 (half the regular furnace) | OSRS | ✅ |
| Smithing XP per bar | bronze 6.2, iron 12.5, silver 13.7, steel 17.5, gold 22.5 (56.2 with goldsmith gauntlets), mithril 30, adamant 37.5, rune 50 | OSRS | ✅ |
| Ordan ore shop | not built (Team 2: the Skilling Materials shop is the only NPC ore tap) | — | — |

## Boss Ticket rates already live (for comparison)

| Boss | Tickets/kill | Source |
|---|---|---|
| Vorkath | 20 | FoV (existing) |
| Zulrah | 20 | FoV (existing) |
| Alchemical Hydra | 25 | FoV (existing) |
| GWD generals | 25 each | FoV (existing) |
| Fight Cave clear | 150 | FoV (existing) |
