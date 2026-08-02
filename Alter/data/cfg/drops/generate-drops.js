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
// variant of a multi-variant page into ONE drop list per id: every "Zombie" id carries the
// union of all seven level-13..53 tables (148 rows — five separate 100% Bones lines, the
// Rag-and-Bone-Man-quest-only Zombie bone at 1/4, triplicate herb/coin blocks), so each
// kill rained several variants' worth of loot. Since our roaming zombies live in the
// wilderness zone (the fallen city streets + the Mire), every Zombie id gets the one
// wilderness variant instead: the block inside the union carrying the wilderness-only
// tertiaries (Dark fishing bait/Larran's key/Slayer's enchantment mark those rows), i.e.
// the level-24 Chaos Temple/Graveyard of Shadows zombie, plus the standard 1/128 gem
// table also present in the union. Rows keep the exact osrsbox rarities; the quest-only
// Zombie bone is dropped. Keyed by lowercased monster name; same row shape as the output.
const CURATED = {
  zombie: [
    [526, 1, 1, 1, 1], // Bones (always)
    [995, 0.3125, 10, 10, 1], // Coins
    [995, 0.164063, 18, 18, 1], // Coins
    [1420, 0.0234375, 1, 1, 1], // Iron mace
    [1203, 0.015625, 1, 1, 1], // Iron dagger
    [1189, 0.0078125, 1, 1, 1], // Bronze kiteshield
    [888, 0.0234375, 1, 1, 1], // Mithril arrow
    [556, 0.0234375, 4, 4, 1], // Air rune
    [559, 0.015625, 3, 3, 1], // Body rune
    [562, 0.0078125, 4, 4, 1], // Chaos rune
    [564, 0.0078125, 2, 2, 1], // Cosmic rune
    [554, 0.0078125, 7, 7, 1], // Fire rune
    [199, 0.0584795, 1, 1, 1], // Grimy guam leaf
    [201, 0.0438596, 1, 1, 1], // Grimy marrentill
    [203, 0.0330033, 1, 1, 1], // Grimy tarromin
    [205, 0.025641, 1, 1, 1], // Grimy harralander
    [207, 0.0201613, 1, 1, 1], // Grimy ranarr weed
    [209, 0.0146413, 1, 1, 1], // Grimy irit leaf
    [211, 0.010989, 1, 1, 1], // Grimy avantoe
    [213, 0.00915751, 1, 1, 1], // Grimy kwuarm
    [215, 0.00732601, 1, 1, 1], // Grimy cadantine
    [2485, 0.00549451, 1, 1, 1], // Grimy lantadyme
    [217, 0.00549451, 1, 1, 1], // Grimy dwarf weed
    [1623, 0.00195313, 1, 1, 1], // Uncut sapphire (gem table)
    [1621, 0.000976563, 1, 1, 1], // Uncut emerald (gem table)
    [1619, 0.000488281, 1, 1, 1], // Uncut ruby (gem table)
    [1452, 0.000183091, 1, 1, 1], // Chaos talisman (gem table)
    [1462, 0.000183091, 1, 1, 1], // Nature talisman (gem table)
    [1617, 0.00012207, 1, 1, 1], // Uncut diamond (gem table)
    [830, 0.0000610352, 5, 5, 1], // Rune javelin (gem table)
    [987, 0.0000610352, 1, 1, 1], // Loop half of key (gem table)
    [985, 0.0000610352, 1, 1, 1], // Tooth half of key (gem table)
    [1247, 0.0000038147, 1, 1, 1], // Rune spear (gem table)
    [2366, 0.00000190735, 1, 1, 1], // Shield left half (gem table)
    [1249, 0.0000014304, 1, 1, 1], // Dragon spear (gem table)
    [21257, 0.00332226, 1, 1, 1], // Slayer's enchantment
    [23490, 0.000798085, 1, 1, 1], // Larran's key
    [6807, 0.0002, 1, 1, 1], // Zombie champion scroll
  ],
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

  const curated = CURATED[String(m.name || "").toLowerCase().trim()];
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
