// Regenerate npc_drops.json from the open osrsbox monster dataset (real OSRS drop tables + rates).
//
// Usage (from this directory, needs Node.js):
//   1. Download the source dataset:
//        curl -L -o osrsbox-monsters.json \
//          https://raw.githubusercontent.com/osrsbox/osrsbox-db/master/docs/monsters-complete.json
//      (PowerShell: Invoke-WebRequest <url> -OutFile osrsbox-monsters.json)
//   2. node generate-drops.js
//   -> writes npc_drops.json next to this script.
//
// Output shape (compact, minified):
//   { "byId":   { "<npcId>": [[itemId, rarity, qmin, qmax, rolls], ...] },
//     "byName": { "<lowername>": <representativeNpcId> } }
// rarity is the real OSRS drop probability (0..1). Loaded by content/drops/NpcDropTables.kt.
//
// osrsbox npc/item ids line up 1:1 with our OSRS-228 cache (verified: goblin=655, zombie=26,
// cow=2790, ...). byName covers variant ids the dataset doesn't list individually (e.g. rat=1020).
const fs = require("fs");

const SRC = process.argv[2] || "osrsbox-monsters.json";
const OUT = process.argv[3] || "npc_drops.json";
const db = JSON.parse(fs.readFileSync(SRC, "utf8"));

// Curated tables for monsters whose osrsbox entry is broken. osrsbox glues every wiki
// variant of a multi-variant page into ONE drop list per id: every "Zombie" id carried the
// union of all seven level-13..53 tables (148 rows — five separate 100% Bones lines, the
// Rag-and-Bone-Man-quest-only Zombie bone at 1/4, triplicate herb/coin blocks), so each
// kill rained several variants' worth of loot; "Zombie pirate" and "Monkey Zombie" had the
// same merged-union bug. Keyed by lowercased monster name; same row shape as the output.
//
// Zombies and zombie pirates both get the modern wilderness Chaos Temple **Zombie pirate**
// table (the 2024 post-nerf one, flattened from its weighted form: 50% nothing pre-roll,
// then 1 pick from a 173-weight main table — so a weight-12 row lands at 6/173, the three
// dragon weapons at 1/346 each). Our roaming zombies live in the wilderness zone (the
// fallen city streets + the Mire), so the wildy-pirate loot line is the fit: big coin
// stacks, blighted supplies, rune gear, and a real shot at dragon dagger/longsword/scim.
// Omitted from the real table: Zombie pirate key 1/24 (its Locker isn't content here).
// Kept: Teleport anchoring scroll 1/20000 and Adamant seeds as rare/flavor drops.
const ZOMBIE_PIRATE_ROWS = [
  [526, 1, 1, 1, 1], // Bones (always)
  [565, 0.0115607, 30, 60, 1], // Blood rune
  [560, 0.0115607, 30, 90, 1], // Death rune
  [562, 0.0115607, 30, 90, 1], // Chaos rune
  [558, 0.0115607, 30, 90, 1], // Mind rune
  [1391, 0.0231214, 1, 3, 1], // Battlestaff
  [1123, 0.017341, 1, 1, 1], // Adamant platebody
  [1147, 0.017341, 1, 1, 1], // Rune med helm
  [1347, 0.017341, 1, 1, 1], // Rune warhammer
  [1373, 0.017341, 1, 1, 1], // Rune battleaxe
  [1303, 0.017341, 1, 1, 1], // Rune longsword
  [1289, 0.017341, 1, 1, 1], // Rune sword
  [1432, 0.017341, 1, 1, 1], // Rune mace
  [1215, 0.00289017, 1, 1, 1], // Dragon dagger (1/346)
  [1305, 0.00289017, 1, 1, 1], // Dragon longsword (1/346)
  [4587, 0.00289017, 1, 1, 1], // Dragon scimitar (1/346)
  [24607, 0.0346821, 10, 30, 1], // Blighted ancient ice sack
  [24592, 0.0346821, 5, 15, 1], // Blighted anglerfish
  [24589, 0.0346821, 5, 15, 1], // Blighted manta ray
  [24595, 0.0346821, 5, 15, 1], // Blighted karambwan
  [24598, 0.0346821, 1, 3, 1], // Blighted super restore(4)
  [995, 0.0346821, 1000, 8000, 1], // Coins
  [2, 0.0346821, 20, 100, 1], // Cannonball
  [444, 0.0346821, 5, 15, 1], // Gold ore
  [29458, 0.0231214, 5, 10, 1], // Adamant seeds
  [29455, 0.00005, 1, 1, 1], // Teleport anchoring scroll (1/20000)
  [6807, 0.0002, 1, 1, 1], // Zombie champion scroll (1/5000)
];

const CURATED = {
  // Street/Mire zombies additionally keep the wildy-slayer tertiaries (they're the
  // Slayer-contract "Zombies" target; zombie pirates are not).
  zombie: [
    ...ZOMBIE_PIRATE_ROWS,
    [21257, 0.00332226, 1, 1, 1], // Slayer's enchantment (1/301)
    [23490, 0.000798085, 1, 1, 1], // Larran's key (1/1253)
  ],
  "zombie pirate": ZOMBIE_PIRATE_ROWS,
};

// Per-id curated tables (checked before CURATED): the Monkey Zombie union gave every kill
// BOTH sizes of zombie monkey bones plus a Monkey paw; each variant drops only its own bones.
const CURATED_BY_ID = {
  5281: [[3185, 1, 1, 1, 1]], // Monkey Zombie lvl 98 — Monkey bones (small zombie)
  5283: [[3185, 1, 1, 1, 1]], // Monkey Zombie lvl 82 — Monkey bones (small zombie)
  5282: [[3186, 1, 1, 1, 1]], // Monkey Zombie lvl 129 — Monkey bones (large zombie)
};

function parseQty(q) {
  if (q == null) return [1, 1];
  const nums = String(q).match(/\d+/g);
  if (!nums || nums.length === 0) return [1, 1];
  const ints = nums.map(Number).filter((n) => Number.isFinite(n));
  if (ints.length === 0) return [1, 1];
  return [Math.min(...ints), Math.max(...ints)];
}

const byId = {};
const nameGroups = {};
let monstersOut = 0, rowsOut = 0;

for (const key of Object.keys(db)) {
  const m = db[key];
  if (!Array.isArray(m.drops) || m.drops.length === 0) continue;
  if (typeof m.id !== "number") continue;

  const curated = CURATED_BY_ID[m.id] || CURATED[String(m.name || "").toLowerCase().trim()];
  if (curated) {
    byId[m.id] = curated;
    monstersOut++;
    rowsOut += curated.length;
    const nm = String(m.name).toLowerCase().trim();
    const cur = nameGroups[nm];
    if (!cur || curated.length > cur.dropCount) nameGroups[nm] = { id: m.id, dropCount: curated.length };
    continue;
  }

  const rows = [];
  for (const dr of m.drops) {
    if (typeof dr.id !== "number" || dr.id <= 0) continue;
    let rarity = typeof dr.rarity === "string" ? Number(dr.rarity) : dr.rarity;
    if (!Number.isFinite(rarity) || rarity <= 0) continue;
    if (rarity > 1) rarity = 1;
    const [qmin, qmax] = parseQty(dr.quantity);
    let rolls = Number(dr.rolls);
    if (!Number.isFinite(rolls) || rolls < 1) rolls = 1;
    rows.push([dr.id, Number(rarity.toPrecision(6)), qmin, qmax, rolls]);
    rowsOut++;
  }
  if (rows.length === 0) continue;

  byId[m.id] = rows;
  monstersOut++;
  const nm = String(m.name || "").toLowerCase().trim();
  if (nm) {
    const cur = nameGroups[nm];
    if (!cur || rows.length > cur.dropCount) nameGroups[nm] = { id: m.id, dropCount: rows.length };
  }
}

const byName = {};
for (const [nm, v] of Object.entries(nameGroups)) byName[nm] = v.id;

fs.writeFileSync(OUT, JSON.stringify({ byId, byName }));
console.log(`Wrote ${OUT}: ${monstersOut} monster tables, ${rowsOut} drop rows, ${Object.keys(byName).length} name aliases.`);
