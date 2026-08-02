# Fall of Varrock — Combat Standard

The authoritative spec every combat change is checked against. Source of truth is the
[OSRS Wiki](https://oldschool.runescape.wiki) (its combat pages cite Jagex devs directly);
the wiki **DPS calculator** is the numerical oracle — `::combatdebug` in-game prints live
hit chance / max hit for comparison. Golden tests pinning these numbers live in
`CombatMathTests`, `HitPipelineTests`, `DragonClawsSpecTests`.

## Ticks
- 600 ms tick. Task order per tick: messages → queues → NPC cycles → player cycles (in
  shuffled PID order, reshuffled every 100–150 ticks) → sync.
- Attacking arms one global `ATTACK_DELAY` = weapon speed in ticks. **Switching weapons
  never resets it** (basis of gear switching); equip recalculates bonuses immediately so
  same-tick swap-and-attack uses the new gear.
- Rapid: −1 tick. Spells: always 5 ticks except powered staves (weapon speed).
- Eating adds +3 (karambwan +2, stacking) ONLY to an already-running attack delay.

## Accuracy & damage ([DPS/Melee](https://oldschool.runescape.wiki/w/Damage_per_second/Melee))
```
effectiveLevel = floor( floor(visible × prayer) + styleBonus + 8 )      [× void, floored]
maxRoll        = effectiveLevel × (equipBonus + 64)
maxHit         = floor( 0.5 + effectiveStrength × (strBonus + 64) / 640 )
P(hit)         = atk > def ? 1 − (def + 2) / (2 × (atk + 1)) : atk / (2 × (def + 1))
```
- Style bonuses: +3 accurate/aggressive/defensive, +1 controlled; ranged accurate +3;
  magic (powered staves only) accurate +2 / longrange +1.
- Defence roll = **target's** effective Defence × target's bonus for the attacker's
  damage type. Player magic defence = 70% Magic + 30% Defence.
- NPC effective levels = level + 9. NPC magic evasion uses its **Magic** level.
- Damage = uniform 0..maxHit. Gear multipliers floor at each step. Dharok's:
  `1 + (lostHP/100 × maxHP/100)`, overheal clamped (1/99 HP → ×1.9702).
- Salve = undead only; black mask/slayer helm = on-task only; never stack (salve wins).
  Regular salve/mask are melee-only; imbued works for all styles.
- Twisted bow modifiers are PERCENTAGES: divide by 100, cap 250%/140% (350/250 in CoX).

## Hit pipeline
- Hits queue on the defender with a real `damageDelay`: melee 0, ranged
  `1 + floor((3+d)/6)`, magic `1 + floor((1+d)/3)` (Chebyshev distance). This is what
  makes tick-eating, hit-stacking and mid-flight prayer switches work.
- Protection prayers are a damage transform at **application time**: 100% block vs NPC
  attackers (bosses may pass `respectsProtection = false`), 40% vs players, accuracy
  unaffected. XP, Smite, Redemption, damage-map credit all run at application.
- Projectiles land even if the attacker died; nobody retaliates at a corpse.

## Timers & state
- HP +1/100 ticks (Rapid Heal ×2). Stats ±1/100 toward base (Rapid Restore ×2 drained,
  Preserve 150-tick boosts). Spec +10%/50 ticks. Prayer never regens.
- Prayer drain: counter += Σ drain effects per tick; resistance = 60 + 2×prayerBonus.
- Poison/venom: every 30 ticks; poison severity −1/proc; venom 6→+2→cap 20; antipoison
  converts venom→poison; immunity enforced in `Poison.poison` itself.
- Freeze: gated at the `walkRoute` funnel (attacking allowed, moving not); 5-tick
  post-thaw immunity; Protect-from-Magic halves PvP freezes; blitz/barrage 24/32 ticks.
  Freeze and skull clear on death (skull after the drop calc).
- Divine potions: 10 HP sip (refused ≤10 HP), re-boost every 25 ticks for 5 min.
- Run energy (0–10000): drain 67 + 67×min(weight,64)/64 per running tick (stamina ×0.3);
  restore floor(agility/6)+8 per tick (+30% graceful).

## PvP
- PJ timer: 20 ticks, single-way, PLAYER opponents only (fighting an NPC never shields
  you from PKers). Both participants refreshed per exchange.
- Skull 2000 ticks on unprovoked attack; retaliation never skulls. Items kept: 3 (+1
  Protect Item, 0/1 skulled) counted in **units** — stacks split.
- Teleblock 500 ticks, halved by Protect from Magic. Smite drains floor(dmg/4);
  Redemption heals floor(0.25×Prayer) below 10% HP; Retribution = 3×3 explosion.
- Vengeance: 75% reflect, 50-tick cooldown, never consumed by a 0. Ring of recoil:
  ceil(dmg/10) victim-side, 40 charge points, shatters at 0.
- X-log: logout blocked while the current aggressor is a player (not damage-gated).
- Spec energy varp ×10; gmaul spec is 0-tick (no cooldown re-arm, adjacency by
  bordering boxes); energy deducts after the spec body runs.

## Specs (key values)
DDS 25%: 2 hits ×1.15/×1.15 · Claws 50%: exact 4-roll table (`DragonClawsSpec`) ·
AGS 50%: ×2 acc ×1.375 · BGS 50%: ×2 acc ×1.21 + drain cascade Def→Str→(Prayer)→Att→
Magic→Ranged (works on NPCs) · DWH 50%: ×1.5, −30% current Def (NPCs too) · Voidwaker
50%: guaranteed 50–150% of melee max · MSB 55%: 2 arrows ×0.9 acc · ACB 40%: ×2 acc +
guaranteed bolt proc · Dragon crossbow 60%: ×1.2, 3×3 splash in multi · Whip 50%:
×1.25 acc + 10% run-energy steal · Sara sword 100%: ×1.1 + 1–16 magic.
Bolt procs (per shot): ruby 6% (20% target HP cap 100, 10% self-cost, blocked ≤10% HP),
diamond 10% (pierce + ×1.15), dragonstone 6%, onyx 11% (×1.2 + 25% heal), emerald 55%
(poison 5), opal 5%, pearl 6%, sapphire 5% (prayer leech, 25% returned).

## NPCs
- Combat stats load from `npc_combat.json` (10 columns + optional 11th style column:
  0 melee / 1 ranged / 2 magic — ranged/magic monsters fight at range with projectiles
  via `NpcCombatDef.combatClass/projectile`).
- Aggro: players-first nearest scan within `aggressiveRadius`; level ≤ 2× npc level
  rule; tolerance timer. NPC poison/venom chances roll on landed hits.

## Server identity (deliberate non-OSRS)
XP rates, full spec on login, spec restore on PvP kill, `::kit` loadouts, custom
wilderness map/raid cities, PK bots. Everything else follows this standard — when in
doubt, the wiki wins; cite the page in a comment next to the constant.
