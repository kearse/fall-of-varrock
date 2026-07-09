// Give every forum category a themed icon + colour, and post the first patch note.
// Idempotent. node scripts/nodebb-style.mjs
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

// name -> [FontAwesome icon, bgColor]
const STYLE = {
  // sections
  "Fall of Varrock": ["fa-crown", "#3a2f10"],
  "General": ["fa-comments", "#1f2937"],
  "The War": ["fa-khanda", "#3a1414"],
  "Media": ["fa-photo-film", "#1e293b"],
  "Support": ["fa-life-ring", "#14342f"],
  // official
  "Announcements": ["fa-bullhorn", "#caa011"],
  "Game Updates": ["fa-code-branch", "#b8860b"],
  "Forum Updates": ["fa-pen-nib", "#9a7b0a"],
  "Rules": ["fa-gavel", "#64748b"],
  "Donate": ["fa-gem", "#16a34a"],
  "Vote": ["fa-square-poll-vertical", "#22c55e"],
  // general
  "General Discussion": ["fa-comments", "#3b82f6"],
  "Guides": ["fa-book", "#eab308"],
  "Marketplace": ["fa-store", "#16a34a"],
  "Suggestions": ["fa-lightbulb", "#f59e0b"],
  "In-Game Suggestions": ["fa-lightbulb", "#d97706"],
  "Forum Suggestions": ["fa-comment-dots", "#0ea5e9"],
  "Feedback": ["fa-comment-dots", "#14b8a6"],
  // the war
  "War Strategy & Tactics": ["fa-chess", "#b91c1c"],
  "Campaign Reports & Kill Pics": ["fa-skull", "#7f1d1d"],
  "Clan & Company Recruitment": ["fa-shield-halved", "#7c3aed"],
  // media
  "Goals & Achievements": ["fa-trophy", "#ca8a04"],
  "Screenshots & Videos": ["fa-camera", "#3b82f6"],
  "Events": ["fa-calendar-day", "#f97316"],
  // support
  "Help & Support": ["fa-circle-question", "#0ea5e9"],
  "Player Reports": ["fa-flag", "#dc2626"],
  "Bug Reports": ["fa-bug", "#ea580c"],
  "Appeals": ["fa-scale-balanced", "#64748b"],
  "Staff Applications": ["fa-user-plus", "#16a34a"],
};

const decode = (s) => (s || "").replace(/&amp;/g, "&").replace(/&#x?\w+;/g, "");

async function main() {
  const cats = await listCategories();

  // Remove the lingering demo category if present.
  const demo = cats.find((c) => decode(c.name) === "Comments & Feedback");
  if (demo) { try { await api("DELETE", `/api/v3/categories/${demo.cid}`); console.log(`  - removed demo "Comments & Feedback"`); } catch (e) { console.log("  ! " + e.message); } }

  console.log("Styling categories...");
  for (const c of cats) {
    if (demo && c.cid === demo.cid) continue;
    const s = STYLE[decode(c.name)];
    if (!s) { console.log(`  ? no style for "${c.name}"`); continue; }
    const [icon, bgColor] = s;
    await api("PUT", `/api/v3/categories/${c.cid}`, { icon, bgColor, color: "#ffffff" });
    console.log(`  * ${c.name} -> ${icon} ${bgColor}`);
    await sleep(120);
  }

  // Sub-forums (3rd level — not returned by /api/categories) styled by cid.
  console.log("Styling sub-forums...");
  const SUBS = [
    [31, "fa-code-branch", "#b8860b"],  // Game Updates
    [32, "fa-pen-nib", "#9a7b0a"],      // Forum Updates
    [33, "fa-lightbulb", "#d97706"],    // In-Game Suggestions
    [34, "fa-comment-dots", "#0ea5e9"], // Forum Suggestions
  ];
  for (const [cid, icon, bgColor] of SUBS) {
    try { await api("PUT", `/api/v3/categories/${cid}`, { icon, bgColor, color: "#ffffff" }); console.log(`  * sub ${cid} -> ${icon}`); } catch (e) { console.log("  ! " + e.message); }
    await sleep(120);
  }

  // First patch note in Game Updates (cid 31)
  const title = "Patch 1.0 — The Realm Goes Live";
  if (!(await topicTitles(31)).has(title)) {
    await api("POST", "/api/v3/topics", { cid: 31, title, content: PATCH_1_0 });
    console.log("  + posted: " + title);
  } else console.log("  = patch note exists");
  console.log("Done.");
}

const PATCH_1_0 = `# Patch 1.0 — The Realm Goes Live

Welcome to the official launch of **Fall of Varrock**! Here's what's in at launch:

## ⚔️ The War
- Player-offense war system: **RAID** (Lord+), **CAMPAIGN** (Minister+) and **CONQUEST** (King).
- Boss raids — summon the **Corporeal Beast** and split the loot by damage dealt.
- The **Goblin Warren** frontier and the **Recruit Trials** onboarding quest.

## 🛡️ Progression
- The full **feudal rank ladder** (Peasant → King) with armour gating and coloured names.
- **Companions** — recruit, train, gear and command your own Lieutenants.

## 🪓 Skilling & Economy
- Custom **Mining**, **Smithing** and **Agility** in and around the Lumbridge cellar.
- **Slayer & War Contracts** from Vannaka, the **Supply Depot**, and the **daily War-Effort bonus**.
- Points economy: War Effort, Boss, Vote, Prestige and Donor points.

## 🌐 Community
- A brand-new website with **shared accounts**, live **hiscores**, a **store** and this **forum**.
- **Single sign-on** — your game account logs you in here automatically.

Jump into the **Guides** board to learn the ropes, and we'll see you on the battlefield. ⚔️

*— The Fall of Varrock team*`;

main().catch((e) => { console.error(e); process.exit(1); });
