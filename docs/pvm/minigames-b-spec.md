# Minigames B spec — Guardians of the Rift and Blast Furnace (wiki-spec builds)

Operator decision 2026-09-03: build **Guardians of the Rift** and **Blast Furnace** next (Tempoross,
Giants' Foundry and Barbarian Assault deferred). No donor code exists — Kronos (rev 184) predates
both minigames as they are today — so this spec is the build authority, drawn from the OSRS wiki
mechanics pages and strategy guides (read 2026-09-03) and checked against our rev-228 cache.
Classic rule applies: keep both recognisable; FoV adjustments are listed explicitly.

## Guardians of the Rift

### What the cache gives us (region 14484, Temple of the Eye, all plane 0)
| Thing | Cache | Notes |
|---|---|---|
| Temple hall | x 3596–3640, z 9490–9520 | lobby south of the barrier x 3607–3616, z 9472–9481 |
| Barrier (lobby ↔ temple) | object 43700 at (3613,9483), 5×1, Pass / Quick-pass / Peek | game entry gate |
| Bank chest (lobby) | 43697 at (3619,9473) | Use / Collect |
| The Great Guardian | npc 11403 "Power-up" (size 5); 11455 (no option) / 11456 variants | centre pedestal ≈ (3615,9503); stones are handed to him |
| Abyssal Rift | 43713 (7×1) at (3612,9526) | creatures spawn just south of it |
| Guardian parts / remains | 43715/43716 (1×1 "Mine"), 43717/43718 (2×2), around the hall edges | fragments |
| Large guardian remains | 43719 (3×3) at (3639,9497) and (3639,9508) | east, behind rubble 43724/43726 "Climb" (56 Agility) |
| Huge guardian remains | 43720 (3×3) at (3589,9497) and (3589,9508) | west, reached through the temporary portal 43729/43730 |
| Workbench | 43754 at (3611,9486) "Work-at" | fragments → guardian essence |
| Uncharged cells | 43732 at (3618,9488) Take-10 / Take-1 | cap 10 carried |
| Weak cells | 43733 at (3618,9486) Take / Take-chisel | |
| Weak cell tile | 43740 "Place-cell" (shape 22) | barrier slots; placed dynamically by us |
| Essence piles | 43722 elemental (3618,9490), 43723 catalytic (3611,9490) "Assemble" | rift guardians (v2) |
| Deposit pool | 43696 at (3609,9487) Deposit-runes / Deposit-items | bank runes made in the game |
| Altar portals ("Guardian of X") | 43701 air (3617,9494), 43702 water (3623,9500), 43703 earth (3623,9505), 43704 fire (3617,9511); 43705 mind (3612,9494), 43706 chaos (3606,9500), 43707 death (3606,9505), 43708 blood (3612,9511), 43709 body (3608,9496), 43710 cosmic (3621,9496), 43711 nature (3621,9509), 43712 law (3608,9509) — "Enter / Toggle-talisman" | static defs (no varbit transforms in 228): active state is ours to show |
| Guides | 43752 elemental / 43753 catalytic (info) | |
| Creatures | attackable Abyss ids: abyssal leech 2584, walker 2586, guardian 2585 (the GotR-specific 11405–11407 carry no Attack option) | |
| Apprentices / Rewards Guardian | 11440–11448 (Tamara / Cordelia / Felix), Rewards Guardian 5984 (Talk-to / Trade-with) | |
| Items | guardian fragments 26878, guardian essence 26879, elemental stone 26881, catalytic stone 26880, polyelemental 26941, cells 26882–26886, abyssal pearls 26792, needle 26813, lantern 26822, dyes 26807/26809/26811, raiments 26850–26856 (+ dyed), catalytic talisman 26798, elemental talisman 5516, colossal pouch 26784, intricate pouch 26908, tarnished locket 26910, lost bag 26912, Atlax's diary 26876, abyssal protector 11402 (pet) | |

### Rules kept from the wiki
- Requirement **27 Runecraft** (quest gate dropped — FoV rule: quests do not gate ordinary content). Bring a pickaxe; a chisel only for rift guardians.
- **Round loop.** Lobby wait (60 s once someone is in, 10 s when the previous round just ended) → the barrier drops and the game starts. **Preparation: 2 minutes** (mine, craft, place cells). First altar portals at **160 s**, then every **~140 s** (a grace portal spawns if a round ends more than 95 s after the last spawn — we simply keep the cadence). Each spawn opens **one elemental and one catalytic** portal, chosen at random (elemental: air/water/earth/fire; catalytic: mind/body/cosmic/chaos/nature/law/death/blood); a portal stays open **100 s**. Abyssal creatures begin at the 2-minute mark and keep coming until the rift closes.
- **Mining.** Fragments per success scale with the node: parts 1, remains 1–2, large 2–3, huge 3–4; success chance rises with Mining level (≈25% more fragments at 99 than at 41 — modelled as 60% + level/4). Mining XP is capped at the first **250 fragments per game** (wiki post-Oct-2023). Huge remains only while the west portal (43729) is open (~25 s, every ~2 min); large remains need **56 Agility** (rubble climb).
- **Workbench**: fragments → guardian essence 1:1, needs free slots; 3 ticks per 5 essence.
- **Imbuing at an altar**: "Enter" a Guardian of X while its portal is open and you have the Runecraft level for that rune → all guardian essence becomes **that rune (1 each) plus one guardian stone per essence** (elemental or catalytic to match the altar), Runecraft XP = the altar's per-essence XP (our runecraft table), and **one carried uncharged cell becomes a charged cell** of the altar's tier (weak ≤ level 5 altars, medium ≤ 14, strong ≤ 44, overcharged 54+). Combination-rune altars are v2 (polyelemental stones exist in the cache).
- **Powering the Great Guardian** ("Power-up" with stones in inventory): each stone adds **2 energy** of its type to the player's game score and raises the Guardian's **power** by 100 / (250 × players) percent (poly stones 3 energy). Run energy +1% per stone (wiki).
- **Cells and barriers**: Place-cell on a cell tile builds a barrier of the cell's tier (HP weak 40 / medium 70 / strong 110 / overcharged 160). Placing gives **2 energy** of each type; strengthening an existing barrier with a higher cell 7–22; repairing a broken tile costs **12 fragments** and gives **25 energy** each type. Up to 10 barrier tiles line the rift's approach.
- **Abyssal creatures**: leeches (most common) drain Guardian power 1% when they reach him; walkers batter barriers; guardians attack players. All attackable; killing them gives nothing but relief (wiki). **At 60% power** the temple rumbles: every barrier loses 50 HP and the spawn mix shifts to more walkers and guardians.
- **Rift closes at 100% power.** Everyone still inside is scored: **1 elemental point per 100 elemental energy** and **1 catalytic point per 100 catalytic energy**, with the remainder as a proportional chance of one more point (73 energy → 73% for a point). **Runecraft XP = Runecraft level × 45**, only if the player reached **300 total energy**. Per-game energy cap 1,000 per type. Points persist (`gotr_elemental` / `gotr_catalytic` attrs) until spent.
- **Rewards Guardian** (lobby): **Search** costs 1 elemental + 1 catalytic point, **Big-search** = 5 searches, or **25 abyssal pearls** for a search. Table per wiki (weights /140): pearls 14–16 (18), essence pouches (15, only the smallest you don't own), runes air/water/earth/fire 400–500 (4 each), mind 250–400 (4), body 80–150 (4), chaos 61–150 / cosmic 20–30 / nature 28–150 / law 5–120 / death 5–120 / blood 5–120 (10 each), talismans (16: noted elemental/mind/body/chaos/cosmic/nature 48–64 /4900, elemental talisman 16/4900), intricate pouch (5), abyssal ashes (1), needle (1). **Rares rolled separately per search**: Atlax's diary 1/20 (once), catalytic talisman 1/200, abyssal needle 1/300 (once), abyssal lantern 1/700, each dye 1/1200, **abyssal protector 1/4000**. Collection Log page: needle, lantern, dyes, raiments, protector, diary.
- **Temple Supplies** (Apprentice Felix, abyssal pearls): raiments hat 400 / top 350 / bottoms 350 / boots 250, lantern 1,500, needle 750, talismans 10–100. **Team 2 ruling 2026-09-03:** pearls are a kept minigame currency — untradeable, stackable, earned only from the Rewards Guardian; Temple Supplies is a pearls-only dialogue shop with no coin buyback and every ware registered with `SpecialShopGuard` at construction; OSRS prices approved as relative values; the reward table stays at OSRS quantities (never above).
- Death inside is safe; leaving through the barrier mid-game forfeits the game's energy; logout the same.

### FoV adjustments (explicit)
- Solo-friendly: the game starts with one player; power scaling uses the live player count (250 stones per player).
- Imbuing happens at the temple's guardian portal (no teleport to the real altar) — the loop is preserved, the walk is not.
- Rift guardians (Assemble at the essence piles, chisel + cells) are v2; barriers carry the defence in v1.
- Combination runes / polyelemental stones v2. Toggle-talisman ignored (portal talismans not modelled).
- HUD: no cache overlay (cs2 args unverified on 228); energy / power / portals are reported by chat messages every 25 s and on `::gotr`.

## Blast Furnace

### What the cache gives us (region 7757, Keldagrim, plane 0)
| Thing | Cache |
|---|---|
| Room | x 1935–1955, z 4955–4975; stairs 9138 at (1939,4956) (the Keldagrim exit); gate 9141 at (1937,4969) |
| Conveyor belt | 9100 at (1943,4967) "Put-ore-on" (+ 9101 decorative segments) |
| Melting pot | 9098 at (1942,4963) "Check" |
| Bar dispenser | 9093 "Check" / 9094–9096 (states) — placed by us next to the belt (the static room carries the base id) |
| Stove | 9085 at (1948,4963) "Refuel"; temperature gauge 9089 (1945,4961); pump 9090 (1950,4961); pedals 9097 (1947,4966); coke 9088 |
| Bank chest / deposit box | 26707 at (1948,4956), 10529 at (1950,4956); anvils 6150 |
| Npcs | Blast Furnace Foreman 2923 (Talk-to / Pay), Ordan 1560 (Talk-to / Trade), dwarves Dumpy 7386, Stumpy 7384, Pumpy 7385, Numpty 6602, Thumpy 5454 |
| Items | coal bag 12019 (open 24480), ice gloves 1580, goldsmith gauntlets 776, bucket of water 1929 |

### Rules kept from the wiki
- Smithing levels per bar as the regular furnace: bronze 1, iron 15, silver 20, steel 30, gold 40, mithril 50, adamant 70, rune 85. **Coal at the Blast Furnace is half the regular furnace's**: steel 1, mithril 2, adamant 3, rune 4 (our `SmithingPlugin` furnace uses 2/4/6/8 — consistent).
- **Machine capacity**: 28 primary ores and 254 coal in the machine at once; the dispenser holds up to 28 bars of a type. Ores go on the belt in one "Put-ore-on" (choose the ore, all of that ore in the pack plus the coal it needs, coal taken from the pack or the coal bag).
- **Automated by the dwarves** (the "official world" behaviour): no pedalling, pumping or stoking for players; the machine turns one bar every 2 ticks in belt order.
- **Taking bars**: bars are hot. With **ice gloves** (or smiths gloves) equipped, "Check" the dispenser takes every finished bar; with a **bucket of water** it cools one dispenser-load per bucket; bare-handed you take damage (5) and drop the attempt. Full Smithing XP per bar (bronze 6.2, iron 12.5, silver 13.7, steel 17.5, gold 22.5 — **56.2 with goldsmith gauntlets** — mithril 30, adamant 37.5, rune 50).
- **Fee — Team 2 ruling 2026-09-03: the OSRS coffer model.** Deposit coins in the coffer (minimum **25,000**); the furnace draws **72,000 coins per hour** (1,200 per minute, charged per minute while you have ore in the machine); everyone pays — no Smithing-60 exemption, no Foreman fee. The belt refuses ore when your coffer is empty. Reason: bars are a floor-only commodity faucet, so the sink scales with time at the furnace.
- **Coal bag**: Fill (from pack), Empty (to pack), holds 27; the belt draws coal from it.
- Ordan's ore shop: **skipped** (Team 2: mid/high ores are gathered; the Skilling Materials shop stays the only NPC ore tap).

### FoV adjustments (explicit)
- One shared machine in the real room (region 7757 is force-loaded at boot): the belt queue, coal store and dispenser are per player (each player's ores are theirs), which is how it feels on an official world.
- Iron at the Blast Furnace never fails (wiki: the furnace has no iron failure).
- Gold XP with goldsmith gauntlets applies when the gauntlets are worn at the moment the bar is taken (wiki).

## Delivery
PR 11 `pvm/12-guardians-of-the-rift` (`content/minigames/gotr/`) and PR 12 `pvm/13-blast-furnace`
(`content/minigames/blastfurnace/`), each with a portal Mini-Games row (+ client mirror), wiki page,
ledger rows for Team 2, and a headless boot check. Both flagged TUNE for the in-game smoke.
