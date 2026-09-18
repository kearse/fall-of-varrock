# Community feedback batch — September 2026

> Four batches so far. **[Batch 4 (2026-09-18)](#batch-4--2026-09-18)**,
> **[Batch 3 (2026-09-17)](#batch-3--2026-09-17)** and
> **[Batch 2 (2026-09-13)](#batch-2--2026-09-13)** are at the bottom of this file; batch 1
> (2026-09-03) is immediately below.

## Batch 1 — 2026-09-03

Source: the community list the operator pasted on 2026-09-03 (`Suggestions.txt` = shop/economy
suggestions; the chat message = 23 bug reports). Every report was traced to code before anything
was changed; the fixes shipped as one PR (`claude/community-bug-reports-5f1594`, six commits).
Operator decisions taken on 2026-09-04: powered staves usable in PvP (all four); wilderness bosses
stay at the surface lairs; the Digsite becomes a safe pocket; Karuulm opens with OSRS Slayer levels
and the boss gates.

### Bug reports

| # | Report | Root cause | Status |
|---|---|---|---|
| 1 | Hydra bones (and others) can't be used on the altar | The altar/bury plugin carried a 7-bone table; every other bone had no Bury binding and no offer binding | **Fixed** — shared `Bones` table with 16 more bone types (hydra, superior dragon, wyrm, drake, dagannoth, ourg, raurg, fayrg, zogre, wyvern, lava dragon, burnt, monkey, shaikahan, wyrmling, sunkissed) |
| 2 | Powered staves can't hit players / bot players | `MagicCombatStrategy.canAttack` vetoed every powered-staff spell against a `Player` (bots are Players) | **Fixed** — veto removed (operator decision: all four staves usable in PvP) |
| 2b | Can't cast spellbook spells while wielding a powered staff | Blanket refusal in `CombatSpellsPlugin` that also sat above the bind/curse/Tele Block dispatch | **Fixed** — casts once (not autocast), exactly as OSRS; binds/curses/TB work again |
| 3 | Bows hit very light (webweaver, crystal) | Ammo-less bows still read + consumed the quiver, webweaver had no range entry, no revenant Wilderness bonus, unverified cache bonuses | **Fixed** — quiver ignored for crystal/bowfa/craw's/webweaver, crystal arrow drawn, range 9/10, +50% vs Wilderness npcs, OSRS bonuses pinned in `itemOverrides/unique/ammoless_bows.yml` |
| 3b | Webweaver spec does nothing | No special attack registered | **Fixed** — Swarm (4 × 40%, double accuracy, poison) |
| 4 | Scythe doesn't hit 1×3 | Melee strategy dealt exactly one hit for every weapon | **Fixed** — 1×3 arc in multi + 50%/25% follow-ups on 2×2/3×3 targets |
| 5 | Barrage/burst not 3×3 on stacked npcs | The AoE only ran in the Wilderness or in the 4 boss regions flagged multi; big npcs matched on their SW tile only | **Fixed** — GWD rooms flagged multi, size-aware overlap, quiet bystander checks. (Catacombs/Karuulm multi flags: follow-up) |
| 6 | Blood fury / sang staff don't heal | Blood fury never existed; sang heal existed but was unreachable in PvP (#2) and invisible | **Fixed** — blood fury 20%/30% on melee; sang heal graphic |
| 7 | Prayer book shows off but overhead is on | The 0-prayer-points refusal never synced varp 83 back; prayers persisted across a crash relog without the icon; D-scim's disable only armed a timer | **Fixed** — sync + message, prayers off on login, overhead stripped; boot assertion for quick-prayer slots |
| 8 | Wildy mage bots don't cast, some rangers don't shoot | Bots never set the autocast varbit (spell stripped after the first cast → melee with a wand), no Ancients/free runes; four loadouts paired msb(i) with dragon arrows the bow rejects | **Fixed** — varbit + spellbook + inf runes at spawn; amethyst arrows; loadouts validated at spawn (ERROR log) |
| 9 | Wildy agility shortcuts don't work | The Wilderness course's entry pipe/ropeswing and the eastern ditch twin are unbound | **Deferred** — needs tile-pair data per obstacle (see follow-ups) |
| 10 | Ring of suffering doesn't work | Recoil only matched the ring of recoil id | **Fixed** — every suffering variant recoils (uncharged); recoil formula corrected to floor(d/10)+1 |
| 11 | Nightmare kill teleports you out before looting | 6-second kick, loot on the instance floor which the allocator wiped ~15 s later; exit at Digsite = wilderness 22 | **Fixed** — loot at the killer's feet, 60 s grace, Digsite is a safe carve-out (operator decision) |
| 12 | Moons of Peril teleport wrong height | Landing on plane 0 = a sealed dead-end corridor; the walkway hub is plane 1 (map dump) | **Fixed** — antechamber + chamber exits on plane 1 |
| 13 | Kraken won't spawn | "Disturb" is menu slot 2, which the engine hard-routes to attack; the whirlpool isn't attackable → "You can't attack this npc." No fishing explosive is involved | **Fixed** — op-2 falls through to a bound option when the npc isn't attackable; ambient whirlpool rows boss-reserved |
| 14 | Scorpia not in wildy / scorpions passive / no exit / respawn timer | Zoning was surface-only; offspring had no combat row (aggro 0); cavern/crevice objects unbound; 9.6 s respawn, no countdown | **Fixed** — underground wilderness bands (54/34/41/28), offspring statted, cave doors bound, respawn 30 s with countdown |
| 15 | Venenatis drains prayer for no reason / weird animation | Every-tick sap outside the attack gate; 2022 model has stand/walk only | **Fixed** — sap rides a landed magic hit; old-model Venenatis (6504) with its full archive |
| 16 | Callisto out of cave / smacked on arrival | Landing tile inside aggro radius → roar knockback on tick 1; 2022 model has no frame archive | **Fixed** — landing moved out of aggro; old-model Callisto (6503). Surface lair kept (operator decision); den move = follow-up |
| 17 | Vet'ion out of cave / bugged animation | 2022 model has NO frame archive and no old id exists | **Mitigated** — plays no foreign animations (lightning/quake/chat carry the fight); den move + cache repack = follow-up |
| 18 | Poison status not shown on the HP bar | The server never wrote varp 102 from the poison path (only an unused wrapper did, with a constant) | **Fixed** — varp 102 derived from state on every apply/proc/cure/login; client decodes damage + countdown. "Prayer-drain yellow" is OSRS *disease* (varp 456) — no such mechanic here |
| 19 | Dragon thrownaxe spec doesn't work | Registered `executeInstantly`, which needs a melee-adjacent target | **Fixed** |
| 20 | Nightmare staff / orb staff missing spec | Never registered | **Fixed** — Immolate + Invocate (Magic-level scaled, +50% accuracy, eldritch restores prayer) |
| 21 | Two Cerberus, one unattackable; add Slayer req to teleport | Wiki-dump variant 5863 spawned on the plugin's tiles; no portal gate | **Fixed** — variant boss-reserved; portal checks 91 Slayer |
| 22 | Hydra: no Slayer req; gate the teleport; open the dungeon | No `slayerData`; Karuulm monsters pruned for lack of stats | **Fixed** — 95 Slayer to attack + portal gate; Karuulm dungeon open (wyrm 62 / drake 84 / hydra 95 / sulphur lizard 44) with its own portal row |
| 23 | GWD altar doesn't work | The four altars were never bound | **Fixed** — Pray-at restores prayer, 10-minute cooldown (persisted) |

### Found while investigating (also fixed)

- Skotizo's awakened altars were unkillable (cache combat level 0) — the "kill the altars" mechanic
  is live via `forceAttackable`.
- Chaos Fanatic and Chaos Elemental played wrong animations; the elemental "blocked" with its walk.
- `MagicCombatFormula.getAccuracy` ignored the special-attack multiplier.
- The Zaff (Magic Shop) rune-altar teleport died on a `chatNpc` without an npc id.
- The river cooking range at (3239,3246) stood in the River Lum; the Hunter thickets were flat
  farming soil; the Hunter portal row landed on the agility dispenser; Runecraft/Farming had no
  portal rows; the fishing spots that "jump out of the river" were wiki-dump rod spots given a walk
  radius on water.
- Boss-tile loot for Zemouregal had the same instance-wipe race as the Convergence.

### Suggestions (logged, not implemented)

| Suggestion | Owner / decision |
|---|---|
| War Forge two-tier ladder (Bandos→Inquisitor→Torva, Armadyl→Masori→Masori (f), Dagon'hai→Virtus→Ancestral) with bars/feathers/runes inputs and 12–25M fees | Operator + Team 2 (`war/forge/WarForge.kt` RECIPES is the single seam; Team 2 already has "WarForge inputs" queued) |
| Vote shop one-page rework (guild set, ring of stone, halos, kits, rune sets) | Team 2 vote-shelf review |
| PK rewards (Blood money) shop full reprice + a Barrows-parts page | Team 2 "BM shelf reprice / PkRewardStock split" (already next in their queue) |
| Warlord's Armoury 5-page catalogue (Fang/Rapier/…/relics ×6) and a Commander's Regalia megarare shelf | Conflicts with the September Boss-Ticket retirement (design doc 04 §13): the Armoury is Barrows-only by decision. Operator call |
| World boss in an open space with ~10k HP | Team 1 war seam |
| Slayer: boss-task tier after the quest, wilderness boss tasks, a Slayer reward store | Team 4 backlog |
| GWD: teleport to the middle, kill-count gate, KC scaled by title | Team 4 backlog (the KC gate is OSRS-faithful) |
| Wilderness warning signs at safe/unsafe boundaries | Feature backlog (the Digsite carve-out answers the sharpest case) |
| PK bots drop blood money by level + a "supply key" | **Blood Money by level SHIPPED 2026-09-12** (`bots/RogueBounty.kt`: half the player formula, named knights ×2, no cap; the worn kit no longer drops at all — only `PkLootPools` rares). The "supply key" half is still open |
| Skill-cape / max-cape shop | Feature backlog |
| Skilling: 1×1 stool stalls, fire pit/range in the skilling area, wildy 1.5–2× xp, a hunter area with quarry, talisman shop + rune altars, construction/farming onboarding, AFK area | Feature backlog. The concrete bugs in this group (river range, invisible thickets, hunter landing, Zaff, no Runecraft/Farming rows, click hints on the altar/workbench/flowerbed) shipped |

### Follow-ups (not in this PR)

- Move Callisto / Vet'ion / Venenatis into the 2022 dens (regions 13215 / 12959 / 13472-3 are in
  the cache; needs den spawn tiles, entrance bindings, and the new-model npcs need a cache repack
  for animations).
- Wilderness Agility Course lap (pipe 23137 `Squeeze-through` @ 3004,3938, ropeswing 23132
  `Swing-on` @ 3005,3952 — never re-bind 23542/23556) and the other unbound wildy shortcuts
  (crevices 40386/46995, stepping stones 14917/14918/53237, ledges 53288/53289, crack 26382,
  underwall 16529/16530, eastern ditch twin 50652).
- Multi-combat flags for the catacombs and Karuulm.
- The client's `WildernessZones.java` boundary lines are stale versus `PvpZones` (south edge,
  Falador/Lumbridge carve-outs, Digsite) — cosmetic.
- Neypotzli gathering loop (fishing/hunting spawns); hunter quarry npcs at the Mire thickets.
- The client only shows the poison heart icon for severities ≤ 9 (bar colour is right at any
  severity) — RuneLite `StatusBarsOverlay` quirk.

---

## Batch 2 — 2026-09-13

Source: the community list the operator pasted on 2026-09-13 (10 bug reports + 6 suggestions).
Same method as batch 1: every report traced to code before anything changed. Operator decisions
taken the same day: the clue system ships as a **minimal working loop** (not the full OSRS trail
engine); zombies get **both** a Wilderness field and a kept-but-defanged home cluster; the Mage
Bank opens with **both** god-cape tiers; Blood Money is answered by **repricing the shop upward**
rather than cutting the earn rate.

### Bug reports

| # | Report | Root cause | Status |
|---|---|---|---|
| 1 | Pegasian boots say they need 75 Attack | The cache's `PRIMARY_SKILL`/`PRIMARY_LEVEL` params on all three Cerberus boots resolve to the **attack** skill id rather than the boot's own style, and `ItemMetadataService` writes whatever the param holds — it cannot tell a wrong skill id from a right one. A maxed ranger with 1 Attack could not wear the Pegasians they had just looted | **Fixed** — OSRS pairs pinned in `itemOverrides/unique/crystal_boots_reqs.yml` (pegasian 75 Ranged + 75 Def, primordial 75 Str + 75 Def, eternal 75 Magic + 75 Def, plus the 23644 duplicate row). A YAML `skillReqs` list REPLACES the cache pairs, so the bogus Attack requirement is gone |
| 2 | GoR needs 27 Runecrafting but no store sells talismans | GotR gates at 27 (`Gotr.RUNECRAFT_REQ`, the OSRS number) and the only way to train the skill was mining the two essence rocks south of the Mire yard and walking them to the fire altar. No shop sold essence, pouches or talismans. (This server's Runecraft is a single multi-rune altar, so a talisman is not actually an altar key here — but from the player's side "I can't reach 27" reads as "I can't buy the talisman OSRS makes me buy") | **Fixed** — Zaff is now a tabbed vendor with a **Runecraft** shelf: essence, all four pouches, the full talisman set. Essence is deliberately the expensive line so mining stays far cheaper |
| 3 | Blood money is too easy to make now — will prices be updated? | Not a bug. The 2026-09-12 bot bounty (`RogueBounty`: uncapped, no guard, doubled for named knights) pays ~200 BM per elite kill, on the order of 15–25k BM an hour, where a bot used to pay in gear instead of currency. The old shelf bought an AGS in well under an hour | **Fixed (operator decision)** — flat **×3** on all three gear wings of `PkRewardsPlugin` (AGS 15k→45k, voidwaker 30k→90k, VLS 25k→75k). A multiplier preserves the already-tuned ratios between wings and is trivial to re-scale. **Supplies are not repriced** — they are the consumption loop, and taxing food punishes fighting rather than farming |
| 4 | All accounts should start with 3 Herblore since no quest exists | Druidic Ritual grants the first three levels in OSRS and Herblore is the one skill you cannot train a single point of without them — every unfinished potion needs level 3+. A fresh account could buy the hub's herbs and vials and had no level to use them | **Fixed** — `OSRSPlugin.onLogin` floors Herblore at 3, the same rule Hitpoints already uses. Only ever raises |
| 5 | Larran's key has no chest | The key sits on **322 npc drop rows** and nothing in the plugin tree referenced it or either chest object. Every key ever dropped was dead weight | **Fixed** — `objects/larranschest`. Both chest objects bound by id (so any chest the rev-228 map already carries works), plus one of each spawned on this server's PK corridor beside the Raider Fields and Deep Wilderness landings. Separate small and big tables, the big one ~3× and carrying the wilderness chase weapons |
| 6 | Clue scrolls and the reward chest don't work | There was no clue system at all. Scrolls dropped from ~20 curated boss tables and were inert; the generic drop handler had already blanket-vetoed every clue and casket id (`drops/config.yml`, "no Treasure Trails system exists") after the osrsbox import put elite caskets on 27 monsters at 100% | **Fixed, scoped (operator decision)** — `minigames/cluescrolls`: read → dig the trail → reward casket → tiered loot, one trail at a time, persisted, re-readable. **Coordinate clues only** — see the note below |
| 7 | Teleport anchoring scroll doesn't work | Item 29455 drops from every zombie row (110 of them) and had no binding of any kind | **Fixed** — reading it spends the scroll for a permanent, persisted anchor. Its examine promises protection from unwanted teleportation and this server has exactly one involuntary teleport: the Chaos Elemental's displacement, which now honours the anchor. The Elemental's disarm is deliberately untouched (separate mechanic, separate counter) |
| 8 | Zombies are not in the wildy but in the skilling area | `SwampHubPlugin` deliberately spawned `npc.zombie` (id 26) SW of the Mire yard so Slayer zombie contracts had a target near home — but id 26 is flagged aggressive in the world dump, so it got a 4-tile aggro radius on top of a 4-tile wander, and the route from the working yard south to the collection grounds runs straight through it | **Fixed, both halves (operator decision)** — the home corner now spawns **id 64**: the same "Zombie" by cache name (Slayer matches on name, so contracts still credit), 30 hp, but `stats[7] != 1` so `WorldSpawnsPlugin` gives it `aggressiveRadius = 0`. It cannot start a fight. The aggressive zombies moved to the **Graveyard of Shadows** (`areas/wilderness/WildernessUndeadPlugin`), already a multi-combat box in `PvpZones` and already served by the Deep Wilderness portal row |
| 9 | Mythical cape teleport doesn't work | No plugin referenced item 21913 or its 22114 twin; the cape's teleport verb did nothing | **Fixed** — both ids teleport to the Myths' Guild on the standard modern-teleport rules |
| 10 | Unable to get into the mage bank | The `mage_bank` teleport row was a `COMING_SOON` placeholder, so the portal listed a destination `TeleportService` then refused | **Fixed** — a real landing on the Mage Bank floor (2539,4716), a safe pocket well outside `Wilderness.SURFACE`. Client mirror (`LofTeleportsData`) flipped to match |

### Suggestions

| # | Suggestion | Status |
|---|---|---|
| 1 | Bank trash bin with a confirm button, for bot-pickup junk | **Shipped** — the bin already existed (incinerator toggle on component 53, destroy on 47); what it lacked was the confirm, so a misclick destroyed the slot instantly. Every incineration now names the item and amount and asks first. The slot is captured before the dialog (it is overwritten by the next click) and re-read after it, so a bank that changed while the prompt was open cannot destroy the wrong thing |
| 2 | PK bots should not drop untradeables (defenders, void, torso, fire/inf cape) | **Shipped** — fire and infernal capes were never in the pools. Removed: void ×5 (Pest Control is its source and stays so), rune/dragon defender, fighter torso, barrows gloves, the three imbued rings. The avernic defender became its **tradeable hilt**. Defenders, fighter torso and barrows gloves have no minigame here, so they moved to a new **Untradeables** wing on the Blood Money shelf — a known price instead of a random flood off a respawning bot. Replacements in the pools are all tradeable (Barrows shells, obsidian cape, ring of suffering, amulet of blood fury) |
| 3 | Max hit dummies and undead dummies at home | **Shipped** — `npcs/dummies`, at the east end of the Lumbridge market aisle. Zero defence and zero defensive bonuses so every swing lands (a dummy that can block is useless for reading a max hit), zero offence and no aggression, 30k hp, and `NO_LOOT` so the generic drop plugin does not pay out for training equipment |
| 4 | Melee / ranged / magic shops should be re-edited | **Needs operator input** — the ask does not say what is wrong with them. The current rules are deliberate (`LumbridgeShopHubPlugin`: weapons capped at adamant, armour rank-gated through the shared apprentice armoury, runes to death with a top-of-category markup so Runecrafting undercuts the shop). Say which of those should change and it is a small edit; Zaff's counter was reorganised into tabs in this batch either way |
| 5 | Missing god capes | **Shipped** — they were stocked on the Lumbridge stylist's **cosmetic** rack at 2,000 gp. They are combat capes, so they came off the rack and became the Mage Arena I reward |
| 6 | Missing god cape 2 | **Shipped** — the imbued capes were in the cache and unobtainable. They are the Mage Arena II reward |

### The Mage Arena (reports 10 + suggestions 5, 6)

`minigames/magearena`, three reports answered by one piece of content.

- **Mage Bank** — real teleport destination, bank booth, Kolodion, and the three claim statues.
- **Mage Arena I** — Kolodion tests anyone with 60 Magic, shifting through five forms in sequence,
  each death spawning the next. Felling the fifth unlocks the statues; one god's cape may be
  claimed, permanently, as in OSRS. The forms have no ambient spawns, so their combat defs are
  registered in `MageArenaConfigsPlugin` — unregistered they would have fought as the 10 hp
  default human def.
- **Mage Arena II** — a pilgrimage rather than a fight. OSRS asks you to cast your god's spell at
  three wilderness locations; **no spellbook here has god spells**, so the trial keeps the shape
  and drops the spell: wear the cape to all three shrines (Dark Warriors' Fortress, Graveyard of
  Shadows, Demonic Ruins), then return to Kolodion for the imbue. Every shrine stands in
  multi-combat wilderness, which is the point.

The bank statues and the wilderness shrines are the same cache object ids, so one binding serves
both and routes on the player's distance from the bank.

### What the clue system does and does not do

Every step is a **coordinate clue** — the dig. This is a deliberate boundary, not an unfinished
one: anagram, cryptic and emote steps must bind an option on an existing npc or object, and this
codebase binds **one handler per (id, option) pair**, so claiming `Talk-to` on an npc another
plugin already owns would silently replace that plugin's handler. Coordinate clues need none of
that — the spade's `dig` is a single funnel, hooked exactly the way Barrows already hooks it.

Trail length climbs with the tier (easy 1 step → master 5) and so does the danger: easy walks
around Lumbridge and the Mire, hard crosses the fallen city and the near Wilderness, elite and
master sit on multi-combat ground with bots hunting it. Reward tables always pay coins and a
supply line so a casket is never a disappointment, and the chase items are cosmetics and gilded
gear rather than combat upgrades — clues should not be a shortcut past the PK and boss ladders.

All five tiers are wired. **Only hard and elite currently have sources** (the curated boss
tables). The generic drop veto stays in place: it was a data-quality fix for a broken import, not
a placeholder for this system, and re-opening it would put elite caskets back on 27 monsters.

### Follow-ups (not in this PR)

- **Point a source at easy / medium / master clues.** They are fully wired and currently
  undroppable. Where they should come from (thieving stalls, low-level slayer, a curated
  low-tier boss row) is a content decision, not a code one.
- **Richer clue step types** — anagrams, cryptics, emotes, maps, puzzle boxes. Each needs a way to
  bind an npc/object option **without** stomping an existing handler; a shared "multiplex" binder
  that chains to the previously registered handler would unblock all of them at once.
- **The melee/ranged/magic shop re-edit** — blocked on the operator saying what should change.
- **TUNE the new stand-on tiles in-game**: both Larran's chests, the two dummies, the twelve
  Graveyard of Shadows zombie spawns, the three Mage Arena II shrines, and the Mage Bank furniture.
  All are placed on ground that live content already uses, but none has been walked.
- The plain god capes are no longer purchasable. Anyone who bought one for 2,000 gp before this
  batch keeps it — there is no clawback, and it is a legitimate (if cheap) Mage Arena I cape.

---

## Batch 3 — 2026-09-17

Source: two reports from the player **Rude**, relayed by the operator. Same method as the earlier
batches: both were traced to code before anything changed.

### Bug reports

| # | Report | Root cause | Status |
|---|---|---|---|
| 1 | All pets don't follow when dropped | There was no pet system at all. Every pet on this server is only an inventory item — the boss tables hand one out (`bosses/BossDeath`) and the Collection Log records it — and nothing ever turned one into a follower. `Drop` was the plain inventory drop, so a pet went on the floor for anyone to take | **Fixed** — `items/pets`. Dropping a pet takes the item out of the pack and spawns its follower npc at your feet; it walks (and runs) after you, snaps to your side across floors/teleports, is stored on logout and comes back on login, and goes back in the pack with the follower's own **Pick-up** (or `::pet`) |
| 2 | Every login it makes me do the same small march | **First March** (Main Story Quest 2). Its MARCH step resolved only from `WarHooks.onOperationEnded`, which walks `world.players` — so it only ever reached players **still online when the column finished**. A march runs on the realm's half-hour clock and the column has to walk home, so logging out mid-battle meant the result never reached you: the step stayed on MARCH, and General Zo's `regroup` line ("we go again") launched a **fresh public march** every time you talked to him. Same dead end for a share that rounds to 0% (the share table is whole percents of the whole column's fighting) | **Fixed** — the fight is now recorded the moment it happens (`WarHooks.onFightingInOp` → `CampaignDirector.recordParticipation`) into a persisted quest counter, so it survives the logout. Zo now closes the step out for anyone who was in the line instead of sending them out again, and the share-rounding case counts as participation too |

### Pets — how the follower npc is resolved

A pet item and its follower npc are **not** named the same (item "Pet general graardor" → npc
"General Graardor Jr."), and several cache npcs share one pet's name: the Kraken **boss** and the
Kraken **pet** are both "Kraken"; Zulrah's combat snakelings and the Snakeling pet are both
"Snakeling". So `Pets.PETS` maps each pet item to the follower's cache **name** (written as the
`npc.rscm` slug spells it) and the resolver picks, out of every npc with that name, the lowest id
the cache flags as a follower (`NpcType.isFollower`, or failing that one carrying a `Pick-up`
option). A pet whose follower can't be identified that way is left unbound and logged at boot —
it keeps its old drop-to-floor behaviour rather than spawning a boss by mistake.

Twenty-six pets are covered: every Collection Log pet (lair, GWD, wilderness, slayer, Zulrah,
Vorkath, Corp, Hydra) plus TzRek-Jad, the Wintertodt Phoenix and the GotR Abyssal protector. There
are no skilling pets on this server, so none are listed.

The follower does **no route-finding** — it queues the next tile or two toward its owner,
collision-checked, exactly like the npc wander loop (`mechanics/npcwalk`), and snaps to the
owner's side when it falls more than 12 tiles behind, changes floor, or sits walled off for five
ticks. The item leaves the inventory while the pet is out, so `ACTIVE_PET_ATTR` (persistent) *is*
the ownership record: it has to survive a logout or the pet would be destroyed.

### Follow-ups (not in this PR)

- **Pet metamorphosis** (the recolour/variant forms several pets carry as extra npc ids) and pet
  insurance are not implemented — a pet is the one follower form the cache resolves.
- **A pet lost before this batch is not recoverable by the server**: pets dropped on the floor
  under the old behaviour despawned like any ground item. Anyone who lost one that way needs an
  operator hand-back.
- **First March's offline-loss edge**: a player who fought in a column that was *driven back*
  while they were offline keeps the participation mark, so Zo takes their report on the next
  login rather than sending them out again. Being generous once on a 1-QP intro quest beats the
  alternative that was shipped — being stuck on it forever.

---

## Batch 4 — 2026-09-18

Source: the community list the operator pasted on 2026-09-18 (22 distinct items — 19 bug reports
and 3 questions; the message repeated most of the list twice). Same method as the earlier batches:
every item was traced to code, and to the cache where the cache was the authority, before anything
changed.

Operator decisions taken the same day: fix the traced bugs **plus** the cheap missing systems
(Accursed sceptre, scale charging, fire-cape pet exchange, dragon hunter lance/wand, anti-dragon
shield) and defer the Slayer reward shop to its own pass; the anti-dragon shield gets **both** a
shop shelf and dragon drop rows; and Rogue Knights keep hunting the mainland, with the **client
made honest** about it rather than the knights confined.

### The one that was not on the list

Tracing "dragon knives / throw axe unable to spec" turned up something much larger underneath it.

**Ranged strength is missing from the entire cache.** `ItemMetadataService` builds `def.bonuses[11]`
from equipment param 189, and `RangedCombatFormula.getMaxHit` reads the summed worn value as `b` in
`floor(0.5 + a * (b + 64) / 640)`. Rev-228 ships param 189 on only **59 of 30,644** item defs, and
every one of those is a *weapon* (bows, the venator family, a ring, an amulet). **No arrow, bolt,
dart, knife, thrownaxe or javelin carries it at all.** Every quiver in the game summed to zero, so a
99-Ranged player firing dragon arrows maxed around 11 — roughly Ranged÷10, whatever the bow.

Melee was never affected (param 10 is present on 1253 defs), which is why this read as "bows hit
light" rather than "combat is broken". The 2026-09-03 batch patched the four *ammo-less* bows
because those were the ones players named by weapon; everything that actually uses ammo was still
rolling zero. `itemOverrides/unique/ranged_strength_ammo.yml` now pins the OSRS ranged strength of
every arrow, bolt (plain, gem-tipped and enchanted), dart, knife, thrownaxe and javelin.

### Bug reports

| # | Report | Root cause | Status |
|---|---|---|---|
| 1 | Clue scroll plugin doesn't work | The system shipped 2026-09-13, but `drops/config.yml` still carried the 2026-09-02 blanket veto on every clue id, commented "no Treasure Trails system exists". The only clues in the game were the hard/elite rows hand-written onto ~20 boss tables — no easy, no medium, no master existed anywhere, and no regular monster dropped one. Players never saw a scroll to test the loop with | **Fixed** — easy through master are live again. Beginner stays out (`ClueScrolls.Tier` has no BEGINNER tier, so the scroll would be inert); reward caskets stay out deliberately — a casket is the reward for finishing a trail, not a drop |
| 2 | Soulreaper axe does not work and no stats to wield | `SoulreaperAxe.ITEM_KEYS` listed only 28338, but **25484** is the def this server hands out — the id the PK shop, the loot pools and `ChaseGearGuardPlugin` all name. No soul stacks, no HP cost, no Behead: it swung as a plain axe. Separately, both defs carry no wear-requirement params at all, so a level-3 account could wield the best melee weapon in the game | **Fixed** — both defs register; `soulreaper_axe_reqs.yml` pins the OSRS 80 Attack / 80 Strength. Its bonuses were always fine (+134 slash, +121 strength) |
| 3 | An area that shows no pk zone has Rogue Knights pking you | Working as designed — knights hunt the whole mainland (2026-09-12 decision) — but the client had no way to say so, so leaving town looked safe and was not | **Fixed (operator decision: make the client honest)** — `WildernessOverlayPlugin` publishes a Rogue Knight danger band (varp 4703) using the same answer `RogueTerritory` gives the knights: zero on a city core, a sanctuary, a PvP carve-out, off the mainland, or inside the wilderness where the skull already speaks. The overlay draws "Rogue Knights / hunt here" in **amber**, never the wilderness red |
| 4 | Scorpia boss cave is not marked as wildy | The **server** is right: Scorpia's cave is `Wilderness.DUNGEONS` level 54, every spawn and cave door sits inside the box, and `PvpZonesTests` asserts it — PvP, skulling and death drops all work there. The **indicator** was missing: `WildernessBannerOverlay` anchors to the native OSRS PvP skull widget and returned null until that widget reported bounds, and the stock interface-90 script derives its own level from world Y, so at y≈10300 it never lays the widget out | **Fixed** — falls back to the OSRS-default spot under the minimap. Affected all four underground lairs (Scorpia, Vet'ion, Callisto, Venenatis) |
| 5 | Accursed sceptre has no built-in autocast | Autocast itself was never broken: both sceptres are cache weapon type 18 (MAGIC_STAFF) and are not powered staves, so `CombatConfigs.canAutocast` permits them and autocast arms by casting once. What the sceptre had was **no plugin of any kind** — no special attack, and no Wilderness bonus — despite being a 30k Blood Money line in the PK shop | **Fixed** — "Accursed Touch" (50% energy: drains the target's Defence and Magic by 15% of current level, halved against players), plus the revenant Wilderness bonus below |
| 6 | Not able to charge uncharged items (blowpipe, serp helm) | Worse than weaker: in rev-228 the uncharged forms carry **no Wear/Wield option at all**, so an uncharged serpentine helm or an empty blowpipe was a completely dead item, and Zulrah's scales had no use anywhere despite dropping 100–299 at a time | **Fixed** — `items/charging`: scales on the uncharged item convert it and bank them as a persisted charge count; Uncharge reverses it and returns the remainder; Check reports the total. Blowpipe + all three helms. **Charges are deliberately not consumed** — see the note below |
| 7 | Spell book still locked behind a quest so spells don't light up | The server never checks a quest for any spell (all level-gated), but the stock spellbook clientscript greys a spell by reading the progress varp of the quest that unlocks it — and on an account that can never do those quests, those sit at 0 forever. Castable but rendered dead | **Fixed** — `SpellUnlocksPlugin` pins them on login, the same treatment `PrayersPlugin` already gives Chivalry/Piety via King's Ransom. Ardougne/Watchtower/Trollheim/Ape Atoll/Kourend teleports, Iban Blast, Magic Dart, Ancients, the Lunar book, the higher enchants |
| 8 | No way to obtain the anti-dragon shield | Item 1540 had **no source at all**: no shop stocked it and it sat on zero drop tables. OSRS gives it out through Dragon Slayer, which this server does not have | **Fixed (operator decision: both routes)** — a general-store staple and a common dragon drop, so a player who walks into dragons without one can get it from the thing that just killed them. It is nearly worthless as armour (+9 slash defence, 20gp); its value is entirely the dragonfire block |
| 9 | Mole does not dig or escape when attacked | The burrow was implemented but unreachable in practice: it only rolled **below half health**, and only on a tick where the mole had just **landed its own attack**. Kill it from full, out-damage it, or hit it from where it cannot reach you and it never dug once | **Fixed** — OSRS digs when HURT at any health, so it now rolls off damage taken, with a 30-tick cooldown so a fast hitter can still finish the fight |
| 10 | Spec + a spell fires the spec from far away "like you're fcing" | `getCombatClass` answers MAGIC whenever a spell is armed (correct — OSRS lets you cast with a whip in hand), so the combat cycle picks the magic strategy and its 10-tile range check. The spec branch sits above the strategy dispatch and did not care which strategy had just passed `canAttack`, so an armed **melee** special fired from spell range | **Fixed** — a special belongs to the WEAPON, so `getWeaponCombatClass` answers the weapon's own class with the queued spell ignored, and the spec only fires when that matches the strategy about to attack. It stays armed for the next real melee swing instead of being spent at range |
| 11 | Dragon knives / throwing axes unable to spec | `SpecialAttacks` keys on the exact worn item id and only the **unpoisoned** knife (22804) was registered, so the p/p+/p++ knives — what almost everyone throws — had no special and the orb did nothing. The thrownaxe had the same problem with its second def (21207) | **Fixed** — every wieldable knife def registers (22812/22814 sit in the shield slot in this cache and are excluded); both thrownaxe defs register, and the spec now consumes the axe actually wielded instead of a hardcoded id |
| 12 | Thieving stalls are still messed up | Two things. **Six of the nine were not clickable**: each stall key names one def out of a family and only some defs carry `Steal-from`; the base ids for silk (629), seed (6947), fur (632), silver (628), spice (633) and gem (631) have *no actions at all* in rev-228, so those six stood there as scenery. **They also overlapped**: all nine are 2×2 (the baker's 2×1) but were spawned one tile apart, so each one's east half sat inside its neighbour | **Fixed** — each spawns a sibling def that carries the option (all already listed in `stalls.json`, so tier/xp/loot unchanged) and spacing is now 3 tiles. The spawner warns at boot if a stall def has no `Steal-from`, so this cannot come back silently |
| 13 | TzHaar: exchange a fire cape for a 1/200 pet chance doesn't work | It did not exist. The only route to TzRek-Jad was a 1/1000 roll on a full clear, so a second, third and tenth cape were worth nothing but a bank slot | **Fixed** — TzHaar-Mej-Jal takes a cape for a 1-in-200 roll, consumed either way so it is a gamble and not a free reroll. The reporter quoted 1/200 (OSRS is 1/100); their number keeps a spare cape better than a fresh clear without dominating actually running the cave |

### Questions (answered)

| Question | Answer |
|---|---|
| Does the t-bow scale off npc magic level? | **Yes, and this one was already right.** It scales off `max(the NPC's Magic level, its magic attack bonus)` through the OSRS curve, capped at 250% damage / 140% accuracy. **One deviation left alone:** OSRS also applies the curve against PLAYERS and this server restricts it to NPCs. Changing that is a PvP balance call on a PK server, not a bug fix — **operator decision pending** |
| Do dragon hunter items do extra damage vs dragons? | **Only the crossbow did.** It had ×1.25 damage / ×1.3 accuracy vs draconic since the Kronos port; the **lance** and the **wand** were never wired and hit exactly as hard as any other weapon in their class. Now: lance ×1.2/×1.2, wand ×1.2 damage / ×1.5 accuracy, against the same `NpcSpecies.DRACONIC` |
| Do pk wildy weapons do more damage & accuracy in the wildy? | **Only two of the six did.** The revenant rule (+50% vs NPCs in the Wilderness while charged) lived inside `RangedCombatFormula`, so Craw's bow and the webweaver got it and nothing else did — Viggora's chainmace, the Ursine chainmace, Thammaron's sceptre and the Accursed sceptre had no bonus anywhere. A Viggora's hit as hard in the deep wild as a rune mace. `RevenantWeapons` now holds the family and the multiplier in one place, read by all three formulas |

### Traced but NOT reproducible from code — need an in-game check

These four were traced end to end and the code path checks out. They are not dismissed; they need
someone standing in the game to narrow further.

- **Torag's stairs is broken, can't go up.** All six staircases exist in region 14231 at plane 3,
  all six are 1×3 with a `Climb-up` action, `BarrowsPlugin.bindObj` binds that option for every
  brother identically, and Torag's landing (3568,9683) is directly adjacent to the east face of his
  staircase (3565–3567, 9683) with a clear walk around it. Nothing distinguishes Torag from the five
  that work. **Check:** does the option appear on right-click, or is it the click that does nothing?
- **Barrows brothers don't move when summoned.** `Barrows.spawnBrother` calls `npc.attack(owner)`
  and `BarrowsCombatPlugin.brotherCombat` drives every brother through `moveToAttackRange`, the same
  loop the mole and the lair bosses use. Plane 3 of the crypt region is almost entirely walkable, so
  they are not walled in either. **Check:** do they fail to move at all, or only when the player is
  out of their leash radius (`LEASH_RADIUS`, which despawns rather than chases)?
- **Clan, friend and ignore lists still not working.** The server side is complete: handlers are
  registered, `Social` pushes both lists on login (`Player.login`), `PlayerDetails.resolveAccount`
  falls back to the live accounts collection for website-created accounts, and the friend chat is a
  single shared channel. **Check:** what exactly fails — adding a name, seeing online status, or
  private messages?
- **Bank throws items into another tab when dragging.** The insert path does full tab bookkeeping,
  but the default **swap** path (`BankPlugin`, `REARRANGE_MODE_VARBIT == 0`) calls
  `container.swap(src, dst)` with none — correct when both slots are in the same tab, and a genuine
  tab change when they are not, which is also OSRS behaviour. One real defect is visible though: the
  bounds guard is `srcSlot in 0 until container.occupiedSlotCount`, and `occupiedSlotCount` counts
  **non-null items**, not slots — so with any gap in the bank (released placeholders) a drag to a
  legitimate high slot falls through to the resync branch and silently does nothing.
  **Check:** is it a drag that does nothing, or a drag that moves the item to the wrong place?

### Deliberately not changed

- **Runecraft teleport lands in the skilling area.** It lands at (3237,3199), in front of the fire
  altar — which *is* six tiles from the Mire hub pad, because this server's Runecraft is a single
  multi-rune altar in the Mire and there is nowhere else to send you. Working as designed; the
  confusion is real but the fix is new Runecraft content, not a new tile.
- **Karuulm Slayer Dungeon teleport.** (1311,10184) is genuinely inside Karuulm, at the lift, in the
  right region (5279) — not the Catacombs. But it is a dead pocket: the nearest plane-0 NPCs are the
  Kaal- trio 20 tiles north, the wyrms are 30+ tiles west, and the drakes and sulphur lizards are on
  **plane 1**. Landing somewhere with content would answer the complaint better than the tile being
  technically correct. **Operator call** on where it should put you.
- **Missing official Slayer shop.** Confirmed absent — there is no Slayer reward shop of any kind.
  Deferred by operator decision: it needs a points currency and an unlock shelf designed, not a
  shelf bolted on.
- **Scale charges are not consumed by combat.** The charged blowpipe and helms work today; adding
  per-attack drain would take gear that functions and start degrading it — a nerf to every current
  owner. The counter is stored and reported so drain can be switched on later without a migration.

### Follow-ups

- Twisted bow vs players (above) — operator balance call.
- The sceptres' `Swap` option (standard ↔ attuned form, for autocasting Ancients) is unbound. Low
  value here because `canAutocast` does not distinguish spellbooks, but it is a visible dead option.
- Beginner clue scrolls need a `ClueScrolls.Tier` entry before 23182 can come off the drop veto.
- `extraDrops` in `data/cfg/drops/config.yml` is new and general: rows added to a monster's table by
  lowercased npc name, the counterpart to `excludeItemIds`, for items OSRS hands out through content
  this server lacks. The anti-dragon shield is its first user.

### Tooling added while investigating

- `:game-server:itemCheck -PitemArgs="param:<id>"` — audit the whole cache for one equipment param
  (how many defs carry it, and the highest few). This is what established that rev-228 ships no
  ranged strength at all.
- `:game-server:itemCheck` also prints equipment bonuses now, and `:game-server:metaReqCheck` prints
  post-`loadAll()` bonuses plus category/weaponType — so an `itemOverrides` document can be verified
  end to end rather than by reading the YAML back.
- `:game-server:questTable -PquestArgs="dump"` — read-only listing of every row in the cache's quest
  DBTable. Column 19 is the completion value (verified against all eight OSRS quests this server
  already reuses for its native quest-tab rows), which is where the spellbook unlock numbers came
  from instead of guesswork.
