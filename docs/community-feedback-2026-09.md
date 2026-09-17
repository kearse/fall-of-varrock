# Community feedback batch — September 2026

> Two batches so far. **[Batch 2 (2026-09-13)](#batch-2--2026-09-13)** is at the bottom of this
> file; batch 1 (2026-09-03) is immediately below.

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
