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
