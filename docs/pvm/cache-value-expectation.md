# Cache-value expectation per boss / minigame (Team 4 → Team 2)

Generated 2026-09-03 from the DropTable literals on `pvm/09-minigames-a` (scripts in the Team 4 session scratchpad: `droptables3.py` → `cachevalue.py`).

**Method.** Expected quantity per kill = Σ always tiers + Σ main tiers (weight / total weight × mean qty) + Σ rare tiers (mean qty / N). 
Cache value = expected qty × item cache `cost`. *Tradeable* value counts only items whose cache def is tradeable (untradeable items cannot be NPC-sold and are listed separately). 
Realised gp/h today = **0.7 × tradeable cache value/h** (every tradeable NPC-sells at 70% of cache cost until Team 2's sink caps land). Coins rows are shown separately (raw GP, classic tables only — Kronos rows kept for the classic bosses; FoV-original content drops no coins). 
Kills/hour are full-uptime, one player, mid gear (my assumptions — adjust in `cachevalue.py`). Team 2's bands: **mid PvM 600k–1.2M**, **endgame 1.5M–3M** cache value per hour.

| Group | Boss / table | Kills/h | Cache value / kill | Tradeable / kill | Coins / kill | Cache value / h | Realised gp/h (0.7×) | Band | Tickets/h | Biggest contributors |
|---|---|---|---|---|---|---|---|---|---|---|
| FoV | Zemouregal — +ember 1/5 | 4 | 22k | 22k | 0 | 90k | 63k | below mid | 160 | Blood rune, Death rune, Runite bar |
| FoV | The Convergence — +1 ember always | 3 | 29k | 29k | 0 | 88k | 61k | below mid | 180 | Blood rune, Soul rune, Runite bar |
| FoV | Custodian (Senntisten run) — +2-4 salvage x3 waves | 5 | 16k | 16k | 0 | 79k | 55k | below mid | 125 | Blood rune, Death rune, Runite bar |
| FoV | Varrock elite undead — 16 posts; salvage piles separate | 60 | 1k | 1k | 180 | 70k | 60k | below mid | 0 | Blood rune, Death rune, Chaos rune |
| FoV | Palace Warden — 15-min respawn; +ember 1/8 | 4 | 17k | 17k | 0 | 67k | 47k | below mid | 120 | Blood rune, Death rune, Runite bar |
| FoV | Malachai the Hollow — spawns every ~20 min | 3 | 11k | 11k | 0 | 34k | 24k | below mid | 45 | Blood rune, Death rune, Runite bar |
| classic | KBD | 25 | 17k | 17k | 0 | 432k | 296k | below mid | 500 | Dragon med helm, Rune longsword, Adamant platebody |
| classic | Dagannoth Prime | 20 | 16k | 15k | 97 | 318k | 217k | below mid | 300 | Battlestaff (noted), Farseer helm, Skeletal top |
| classic | Kalphite Queen | 12 | 14k | 14k | 0 | 173k | 118k | below mid | 300 | Rune chainbody, Battlestaff (noted), Blood rune |
| classic | Giant Mole | 25 | 5k | 5k | 0 | 133k | 93k | below mid | 375 | Rune med helm, Yew logs (noted), Mole skin |
| classic | Dagannoth Rex | 20 | 5k | 4k | 84 | 93k | 62k | below mid | 300 | Rock-shell plate, Rock-shell legs, Dragon axe |
| classic | Dagannoth Supreme | 20 | 4k | 3k | 44 | 73k | 47k | below mid | 300 | Archer helm, Dragon axe, Yew logs (noted) |
| classic (GWD, pre-existing) | General Graardor | 15 | 35k | 35k | 4k | 532k | 433k | below mid | 375 | Rune platebody, Bandos boots, Bandos hilt |
| classic (GWD, pre-existing) | K'ril Tsutsaroth | 15 | 33k | 33k | 4k | 488k | 401k | below mid | 375 | Rune platelegs, Blood rune, Staff of the dead |
| classic (GWD, pre-existing) | Commander Zilyana | 15 | 30k | 30k | 4k | 456k | 379k | below mid | 375 | Rune plateskirt, Armadyl crossbow, Saradomin hilt |
| classic (GWD, pre-existing) | Kree'arra | 15 | 28k | 28k | 4k | 424k | 359k | below mid | 375 | Armadyl helmet, Rune arrow, Armadyl hilt |
| classic (Moons) | Moons of Peril chest (per common roll) — 6 rolls per 3-moon claim, ~5 claims/h | 30 | 9k | 9k | 0 | 272k | 183k | below mid | 0 | Atlatl dart, Water orb (noted), Blessed bone shards |
| classic (pre-existing) | Zulrah (pre-existing) | 20 | 29k | 29k | 17k | 570k | 736k | below mid | 400 | Battlestaff (noted), Death rune, Dragon med helm |
| classic (slayer) | Demonic gorilla | 40 | 22k | 22k | 0 | 886k | 620k | mid | 200 | Dragon scimitar, Rune javelin heads, Rune plateskirt |
| classic (slayer) | Skotizo — dark totem per kill | 6 | 99k | 98k | 0 | 592k | 412k | below mid | 180 | Blood rune, Rune platebody (noted), Soul rune |
| classic (slayer) | Kraken — 87 Slayer | 30 | 16k | 16k | 0 | 483k | 338k | below mid | 600 | Battlestaff (noted), Mystic robe top, Mystic water staff |
| classic (slayer) | Thermy — 93 Slayer | 30 | 13k | 13k | 0 | 404k | 282k | below mid | 600 | Rune chainbody, Dragon scimitar, Ancient staff |
| classic (slayer) | Cerberus — 91 Slayer | 20 | 13k | 13k | 0 | 259k | 181k | below mid | 600 | Rune halberd, Rune 2h sword, Battlestaff (noted) |
| classic (wildy) | Callisto | 15 | 25k | 25k | 0 | 376k | 263k | below mid | 375 | Dragon 2h sword, Soul rune, Death rune |
| classic (wildy) | Vet'ion | 12 | 31k | 31k | 0 | 371k | 259k | below mid | 300 | Ancient staff, Blood rune, Rune 2h sword |
| classic (wildy) | Scorpia | 20 | 18k | 18k | 136 | 360k | 254k | below mid | 400 | Rune 2h sword, Rune chainbody, Rune warhammer |
| classic (wildy) | Venenatis | 15 | 23k | 23k | 0 | 347k | 243k | below mid | 375 | Onyx bolt tips, Rune 2h sword, Dragon 2h sword |
| classic (wildy) | Chaos Elemental | 20 | 12k | 12k | 173 | 236k | 169k | below mid | 400 | Rune arrow, Dragon 2h sword, Dragon dagger |
| classic (wildy) | Chaos Fanatic | 30 | 5k | 5k | 56 | 154k | 109k | below mid | 450 | Splitbark body, Battlestaff (noted), Ancient staff |
| classic (wildy) | Crazy Archaeologist | 30 | 3k | 3k | 45 | 102k | 72k | below mid | 450 | Rune crossbow, Dragon arrow, Red d'hide body |
| pre-existing | Wizard Tower boss (pre-existing) | 6 | 67k | 67k | 10k | 403k | 342k | below mid | 0 | Blood rune, Death rune, Soul rune |
| pre-existing | Wizard Tower mage (pre-existing) | 40 | 5k | 5k | 550 | 183k | 150k | below mid | 0 | Chaos rune, Nature rune, Law rune |

## Untradeable items in the tables (no NPC cash-out; cache value shown above but excluded from *tradeable*)

- **Zulrah (pre-existing)**: Magma mutagen, Pet snakeling, Tanzanite mutagen
- **KBD**: Clue scroll (elite), Kbd heads
- **Giant Mole**: Clue scroll (elite)
- **Kalphite Queen**: Clue scroll (elite), Dragon chainbody, Ensouled kalphite head, Kq head
- **Dagannoth Rex**: Clue scroll (elite), Clue scroll (hard), Ensouled dagannoth head, Fremennik blade, Fremennik helm, Fremennik shield
- **Dagannoth Prime**: Clue scroll (elite), Clue scroll (hard), Ensouled dagannoth head, Fremennik helm, Fremennik shield
- **Dagannoth Supreme**: Clue scroll (elite), Clue scroll (hard), Ensouled dagannoth head, Fremennik helm, Fremennik shield
- **Kraken**: Clue scroll (elite), Rusty sword
- **Cerberus**: Clue scroll (elite), Key master teleport
- **Thermy**: Clue scroll (elite), Dragon chainbody, Ugthanki kebab
- **Skotizo**: Ancient shard, Clue scroll (elite), Clue scroll (hard), Dark claw, Dark totem
- **Demonic gorilla**: Clue scroll (elite), Clue scroll (hard), Spirit seed
- **Callisto**: Clue scroll (elite)
- **Vet'ion**: Clue scroll (elite)
- **Venenatis**: Clue scroll (elite)
- **Scorpia**: Clue scroll (hard), Ensouled scorpion head
- **Chaos Elemental**: Clue scroll (elite)
- **Chaos Fanatic**: Clue scroll (hard)
- **Crazy Archaeologist**: Clue scroll (hard), Rusty sword
- **Custodian (Senntisten run)**: Old journal, Relic part 1
- **Zemouregal**: Arrav's axe, Mahjarrat notes (a-j), Mahjarrat notes (k-z), Relic part 1
- **The Convergence**: Relic part 1
- **Varrock elite undead**: Relic part 1
- **Malachai the Hollow**: Relic part 1
- **Palace Warden**: Relic part 1
- **Moons of Peril chest (per common roll)**: Blessed bone shards, Sun-kissed bones (noted)

## Not DropTable-driven (hand-computed)

- **Barrows (6-brother chest, 7 rolls, 8 chests/h):** avg Barrows piece cache cost 95k × 7/102 per chest = 7k; rune/rack/key bands ≈ 38k; coins ≈ 1k. **Cache value ≈ 45k/chest → 358k/h (below mid); realised ≈ 259k/h** (+ 144 tickets/h).
- **Moons of Peril (3-moon claim, 5 claims/h):** 6 common rolls × 9k + 3 × 1/56 × avg unique 200k (13 uniques: Atlatl dart, Blood moon chestplate, Blood moon helm, Blood moon tassets, Blue moon chestplate, Blue moon helm…) = **65k/claim → 326k/h (below mid); realised ≈ 228k/h** (+ 150 tickets/h).
- **Wintertodt crate (all tiers unlocked, 3 crates/h):** 3 supply rolls ≈ 2k + uniques ≈ 40 = **2k/crate → 6k/h (below mid); realised ≈ 4k/h**. No coins.
- **Pest Control:** commendation points only (10/15/20 per win, ~3 wins/h) → armoury items are untradeable void pieces; cache value/h = 0, realised = 0.
- **Varrock salvage piles (28, 90 s refill):** 2–5 salvage (cache cost 0) + relic 1/40 (cost 0) → 0 gp; War-Forging inputs only.
- **Arrav Intelligence:** War Effort + Commendations + salvage/relics (all cost 0) → 0 gp.
- **Boss Tickets** (all rows, `BossDeath.payout(tickets=)`): one seam; Team 2 retiring the system → no-op or replace there.

## Notes

- Varrock salvage (21555), Relic of old Varrock (2373), expedition log (1493), Warden's ember: cache cost 0 by design — they carry War-Forging value, not gp.
- Classic tables are Kronos `drops/eco` sub-tables folded flat (equal sub-table weight) with OSRS unique odds; if a classic row lands above band, the fix is Team 2's call (cut coins first, then fold weights) and I apply it.
- Kills/hour drive everything: halve them for realistic uptime.
- Team 2's SpecialShopGuard (their PR 2, stacked on #332) makes every currency-shelf item alch/NPC-sell-proof wherever it came from — AGS, dragon claws, DWH, whip, granite maul, revenant weapons, justiciar, crystal gear, primordials/pegasians/eternals, zenyte jewellery. Where those appear in the rare tiers above (GWD hilts/boots, Cerberus crystals, gorilla zenyte shards, wilderness rings/pickaxes) their contribution is **player-market value, not 0.7 × cost** — the *Realised* column overstates them slightly and the *Cache value* column is the comparable number.
- Pest Control pays **Pest Control points** (attr `pest_points`) — not "commendations"; that word stays with the war team's Commendation currency (renamed per Team 2, 2026-09-03).
