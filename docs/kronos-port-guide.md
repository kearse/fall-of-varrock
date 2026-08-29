# Kronos → Alter port guide

The working translation map for porting boss/minigame content from the owner-released
**Kronos rev-184 source** (`The-RSPS-Archive/184---Kronos-Source`) onto our Alter fork.
Established by the **Vorkath pilot** (`content/bosses/vorkath/`); every later port is
repetition of this pattern. The reboot brief's rules apply: donor structure/ids/timings
carry over 1:1, every engine call is rewritten, disputed mechanics get a wiki citation or
an RSProx capture — never a guess.

## Where donor material lives

| What | Kronos path |
|---|---|
| Fight scripts | `Kronos-master/kronos-server/src/main/java/io/ruin/model/activities/...` |
| Per-npc combat stats | `Kronos-master/kronos-server/data/npcs/combat/<Name>.json` |
| Drop tables | `Kronos-master/kronos-server/data/npcs/drops/eco/<Name>.json` |
| Spawns | `Kronos-master/kronos-server/data/npcs/spawns/*.json` |

Fetch raw files from GitHub; the id tables in the fight scripts' doc comments (anims,
projectiles, gfx, objects, npc forms) are the most valuable part — they are cache facts
and copy over unchanged into rev 228.

## The three-file shape (per boss)

1. **`<Boss>ConfigsPlugin`** — `setCombatDef` per npc form, stats straight from the Kronos
   combat JSON. Gotchas: `hitpoints`/`attackSpeed`/`respawnDelay`/`anims { death }` are
   REQUIRED or the plugin silently fails to load (grep the boot log for `Failed to load`).
2. **`<Boss>Plugin`** — everything around the fight: entry/instancing, death → `DropTable`
   roll + Boss Tickets + `CollectionLog` + rare broadcast (the KBD pattern), respawn.
3. **`<Boss>CombatPlugin`** — the fight loop: `onNpcCombat` → `npc.queue { combat(this) }`,
   the KBD/Zulrah loop shape (`canEngageCombat` / `moveToAttackRange` / `isAttackDelayReady`
   / `postAttackLogic` / `task.wait(1)`).

## Engine translation table

| Kronos (`io.ruin`) | Alter |
|---|---|
| `NPCCombat.attack()` return-true loop | `onNpcCombat` + suspend combat loop in a queue |
| `new Projectile(gfx, sh, eh, delay, dur, …).send(npc, target)` | `world.spawn(createProjectile(target, gfx, startHeight, endHeight, delay, angle, steepness))` |
| `Projectile.send(npc, x, y)` (tile-targeted) | `createProjectile(tile, gfx, ProjectileType.X)` |
| `target.hit(new Hit(npc, AttackStyle.X).randDamage(n))` | `bossMelee` / `bossProjectile` (full pipeline: accuracy formula, protection prayers, kill credit) |
| `AttackStyle.DRAGONFIRE` hits | `dealHit(target, formula = DragonfireFormula(maxHit = n), delay = …)` |
| `hit.postDamage { … }` | `dealHit(...) { if (it.landed()) … }` |
| `npc.hitListener postDefend` damage gate (immunity) | `npc.attr[Combat.DAMAGE_TAKE_MULTIPLIER] = 0.0 / 0.5` (all three formulas honour it) |
| `target.freeze(ticks, npc)` | `target.freeze(cycles) { message }` |
| `target.envenom(n)` | `Poison.venom(target)` |
| `player.getPrayer().deactivateAll()` | `Prayers.deactivateAll(player)` |
| `World.sendGraphics(id, height, delay, pos)` | `world.spawn(TileGraphic(tile, id, height, delay))` |
| `new GameObject(id, pos, 10, rot).spawn()` / `.remove()` | `world.spawn(DynamicObject(id, 10, rot, tile))` / `world.remove(obj)` |
| `new DynamicMap().build(regionId, 1)` | `RaidInstance.allocate(world, sourceArea, exitTile, owner)` + `instance.translate(srcTile)` |
| `npc.transform(id)` | remove old npc + spawn the new form (no npc transmog helper) |
| `NPCAction.register(id, "option", …)` | `onNpcOption(npc = "npc.x", option = "option")` |
| `ObjectAction.register(id, …)` | `onObjOption(obj = "object.x", option = "…")` |
| `npc.addEvent(event -> { event.delay(n); … })` | `npc.queue { wait(n); … }` |
| `Random.get(n)` / `Random.rollDie(n, m)` | `world.random(n)` / `world.chance(m, n)` |

## Kotlin/engine gotchas hit during the pilot

- **`Tile` has no copy constructor** — `Tile(t.x, t.z, t.height)`, never `Tile(t)`.
- **`heal()` is Player-only** — npc healing is `setCurrentHp(minOf(maxHp, current + n))`.
- **`Npc.faceDirection` is a val** — set facing via spawn direction or skip it.
- **Ids go through RSCM** — look names up in `Alter/data/cfg/rscm/{npc,object,item}.rscm`
  (e.g. `npc.vorkath_8061`, `object.acid_pool_32000`); never hardcode raw ids.
- **Instanced deaths are already safe** — `SafeDeaths` treats any instanced-map death as
  safe; no arena registration needed for instanced bosses.
- **`onNpcDeath` opts the npc out of the generic drop system** (`hasNpcDeathHandler`), so
  a ported boss's custom table is authoritative automatically.
- Verify plugin LOAD, not just compile: boot the server and grep the log for
  `Failed to load` / `loaded with failures`.

## What still needs a non-Kronos source

Castle Wars, Nex, Sarachnis, Grotesque Guardians, Muspah/DT2, and the post-2023 reworked
wilderness bosses (Voidwaker droppers) have **no donor** — those are OSRS-Wiki-spec builds
verified with RSProx captures.
