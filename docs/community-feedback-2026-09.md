# Community feedback batch — September 2026

Source: the community list the operator pasted on 2026-09-03 (`Suggestions.txt` = shop/economy
suggestions; the chat message = 23 bug reports). Every report was traced to code before anything
was changed; the fixes shipped as one PR (`claude/community-bug-reports-5f1594`, six commits).
Operator decisions taken on 2026-09-04: powered staves usable in PvP (all four); wilderness bosses
stay at the surface lairs; the Digsite becomes a safe pocket; Karuulm opens with OSRS Slayer levels
and the boss gates.

## Bug reports

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

## Suggestions (logged, not implemented)

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
| PK bots drop blood money by level + a "supply key" | Team 5 (`PkLootPools.kt`) |
| Skill-cape / max-cape shop | Feature backlog |
| Skilling: 1×1 stool stalls, fire pit/range in the skilling area, wildy 1.5–2× xp, a hunter area with quarry, talisman shop + rune altars, construction/farming onboarding, AFK area | Feature backlog. The concrete bugs in this group (river range, invisible thickets, hunter landing, Zaff, no Runecraft/Farming rows, click hints on the altar/workbench/flowerbed) shipped |

## Follow-ups (not in this PR)

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
