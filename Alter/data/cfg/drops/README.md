# NPC drops (real OSRS tables + rates)

Generic loot for every non-boss monster. Bosses keep their bespoke tables
(`game-plugins/.../content/bosses`, `.../content/npcs/**`) and are auto-skipped.

| File | What it is |
| --- | --- |
| `npc_drops.json` | Generated drop data: `byId` (npcId → drop rows) + `byName` (name → representative npcId fallback for variant ids). Drop row = `[itemId, rarity, qmin, qmax, rolls]`, rarity = real OSRS probability. |
| `config.yml` | Runtime dials: `enabled`, `coinMultiplier` (gp faucet), `quantityMultiplier`, `excludeIds`. Tune without regenerating or recompiling. |
| `generate-drops.js` | Rebuilds `npc_drops.json` from the osrsbox dataset. |

## How it works
`content/drops/NpcDropPlugin` binds one `onAnyNpcDeath`: on a **player** kill of an
npc with no dedicated death handler, it rolls each row independently at its real
rarity and drops the loot for the killer. Loaded by `content/drops/NpcDropTables`.

## Regenerate / update rates
Data is a snapshot of the open **osrsbox** monster dataset (ids line up 1:1 with the
OSRS-228 cache). To refresh:

```
curl -L -o osrsbox-monsters.json https://raw.githubusercontent.com/osrsbox/osrsbox-db/master/docs/monsters-complete.json
node generate-drops.js
```

Current snapshot: **2383 monster tables, 65,719 drop rows, 737 name aliases**.

## Curated overrides
osrsbox merges every variant of a multi-variant wiki page into one drop list per npc id
(e.g. every "Zombie" id carried the union of all seven level-13..53 tables — five 100%
Bones lines per kill). `generate-drops.js` has a `CURATED` map (keyed by monster name)
that replaces such broken entries at generation time; zombies use the wilderness
(level-24 Chaos Temple / Graveyard of Shadows) variant, matching the wilderness zone our
street zombies roam. Overrides survive regeneration — add new ones to `CURATED`.
