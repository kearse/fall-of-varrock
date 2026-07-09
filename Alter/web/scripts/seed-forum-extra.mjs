// Sub-forums + a few community starter threads. Idempotent.
//   node scripts/seed-forum-extra.mjs
const BASE = process.env.NODEBB_URL || "http://localhost:4567";
const TOKEN = process.env.NODEBB_TOKEN || "7bee84e3-1b7a-4c32-9d50-10ad2e3cd4ec";
const UID = 1;
const H = { Authorization: `Bearer ${TOKEN}`, "Content-Type": "application/json" };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(method, path, body) {
  const res = await fetch(BASE + path, { method, headers: H, body: JSON.stringify({ _uid: UID, ...(body || {}) }) });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${JSON.stringify(json.status || json)}`);
  return json.response;
}
async function listCategories() {
  const json = await (await fetch(`${BASE}/api/categories`)).json();
  const flat = []; const walk = (a) => a?.forEach((c) => { flat.push(c); if (c.children) walk(c.children); });
  walk(json.categories || []); return flat;
}
async function topicTitles(cid) {
  try { const j = await (await fetch(`${BASE}/api/category/${cid}`)).json(); return new Set((j.topics || []).map((t) => t.title)); }
  catch { return new Set(); }
}

async function main() {
  const cats = await listCategories();
  const cidOf = (name) => cats.find((c) => c.name === name)?.cid;

  async function ensureChild(name, description, parentName) {
    const parentCid = cidOf(parentName);
    if (!parentCid) return console.log(`  ! parent "${parentName}" not found`);
    if (cats.find((c) => c.name === name)) return console.log(`  = sub-forum exists: ${name}`);
    const r = await api("POST", "/api/v3/categories", { name, description, parentCid });
    cats.push({ cid: r.cid, name });
    console.log(`  + sub-forum: ${name} (under ${parentName})`);
  }

  async function ensureThread(catName, title, content) {
    const cid = cidOf(catName);
    if (!cid) return console.log(`  ! category "${catName}" not found`);
    const have = await topicTitles(cid);
    if (have.has(title)) return console.log(`  = thread exists: ${title}`);
    await api("POST", "/api/v3/topics", { cid, title, content });
    console.log(`  + thread in ${catName}: ${title}`);
    await sleep(400);
  }

  console.log("Creating sub-forums...");
  await ensureChild("Game Updates", "In-game patch notes and changes.", "Announcements");
  await ensureChild("Forum Updates", "Forum changes and news.", "Announcements");
  await ensureChild("In-Game Suggestions", "Ideas for the game.", "Suggestions");
  await ensureChild("Forum Suggestions", "Ideas for the forum.", "Suggestions");

  console.log("Posting community starter threads...");
  await ensureThread("General Discussion", "👋 Welcome — introduce yourself here!",
`New to **Fall of Varrock**? Drop a reply and tell us:

- Your in-game name
- How you found the server
- What you're most excited to do — skilling, the war, or PKing?

Don't forget to check the **Guides** board for everything you need to get started. Welcome to the realm! ⚔️`);

  await ensureThread("Marketplace", "📦 How to trade safely (read first)",
`Welcome to the Marketplace! A few ground rules so everyone has a good time:

1. **Title format:** \`[WTS]\` selling, \`[WTB]\` buying, \`[WTT]\` trading. Include items + prices.
2. **Trade in-game**, face to face — never hand items over "to hold".
3. **Scamming is bannable.** Report scammers in **Player Reports** with screenshots.
4. Real-world trading (gold for real money) is **not allowed**.

Happy trading!`);

  await ensureThread("Goals & Achievements", "🏆 Post your first milestone!",
`Hit your first 99? Finished the Recruit Trials? Reached **Lord** and summoned your first boss? Show it off here — screenshots welcome.

Tag your post with the achievement so others can cheer you on.`);

  console.log("Done.");
}
main().catch((e) => { console.error(e); process.exit(1); });
