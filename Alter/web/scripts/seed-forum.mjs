// Seed the NodeBB forum: category structure + grounded guides + starter content.
// Idempotent — safe to re-run (skips categories/topics that already exist by name/title).
//
//   node scripts/seed-forum.mjs
//
// Requires a NodeBB master API token (Admin -> Settings -> API Access).

const BASE = process.env.NODEBB_URL || "http://localhost:4567";
const TOKEN = process.env.NODEBB_TOKEN || "7bee84e3-1b7a-4c32-9d50-10ad2e3cd4ec";
const UID = 1; // act as the admin account

const H = { Authorization: `Bearer ${TOKEN}`, "Content-Type": "application/json" };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(method, path, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: H,
    body: body ? JSON.stringify({ _uid: UID, ...body }) : JSON.stringify({ _uid: UID }),
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${JSON.stringify(json.status || json)}`);
  return json.response;
}

// ---- categories ----
async function listCategories() {
  const res = await fetch(`${BASE}/api/categories`);
  const json = await res.json();
  const flat = [];
  const walk = (arr) => arr?.forEach((c) => { flat.push(c); if (c.children) walk(c.children); });
  walk(json.categories || []);
  return flat;
}

let catCache = null;
async function ensureCategory(name, description, parentCid = 0, extra = {}) {
  if (!catCache) catCache = await listCategories();
  const existing = catCache.find((c) => c.name === name);
  if (existing) return existing.cid;
  const r = await api("POST", "/api/v3/categories", { name, description, parentCid, ...extra });
  catCache.push({ cid: r.cid, name, parentCid });
  console.log(`  + category "${name}" (cid ${r.cid})`);
  return r.cid;
}

async function purgeCategoryByName(name) {
  const cats = await listCategories();
  const hit = cats.find((c) => c.name === name);
  if (hit) {
    try { await api("DELETE", `/api/v3/categories/${hit.cid}`); console.log(`  - removed demo category "${name}"`); }
    catch (e) { console.log(`  ! could not remove "${name}": ${e.message}`); }
  }
}

// ---- topics ----
async function topicTitlesIn(cid) {
  try {
    const res = await fetch(`${BASE}/api/category/${cid}`);
    const json = await res.json();
    return new Set((json.topics || []).map((t) => t.title));
  } catch { return new Set(); }
}

async function postGuide(cid, title, content, pin = false) {
  const have = await topicTitlesIn(cid);
  if (have.has(title)) { console.log(`  = guide exists: ${title}`); return; }
  const r = await api("POST", "/api/v3/topics", { cid, title, content });
  if (pin && r?.tid) { try { await api("PUT", `/api/v3/topics/${r.tid}/pin`); } catch {} }
  console.log(`  + guide: ${title}${pin ? " (pinned)" : ""}`);
  await sleep(400);
}

async function main() {
  console.log("Removing NodeBB demo categories...");
  for (const n of ["Announcements", "General Discussion", "Comments & Feedback", "Blogs"]) await purgeCategoryByName(n);
  catCache = null; // refresh after purge

  console.log("Creating category structure...");
  // Sections (top-level) + boards (children)
  const secOfficial = await ensureCategory("Kingdom of Lumbridge", "Official news, rules and links.");
  await ensureCategory("Announcements", "Official server announcements and patch notes.", secOfficial);
  await ensureCategory("Rules", "In-game and forum rules. Read before posting.", secOfficial);
  await ensureCategory("Donate", "Support the realm.", secOfficial, { link: `${process.env.SITE_URL || "http://localhost:3000"}/store` });
  await ensureCategory("Vote", "Vote for Kingdom of Lumbridge.", secOfficial, { link: `${process.env.SITE_URL || "http://localhost:3000"}/vote` });

  const secGeneral = await ensureCategory("General", "Talk, trade and learn.");
  await ensureCategory("General Discussion", "Talk about anything Kingdom of Lumbridge.", secGeneral);
  const guidesCid = await ensureCategory("Guides", "Player guides, money-makers and walkthroughs.", secGeneral);
  await ensureCategory("Marketplace", "Buy, sell and trade in-game goods.", secGeneral);
  await ensureCategory("Suggestions", "Ideas to improve the game and forum.", secGeneral);
  await ensureCategory("Feedback", "Server, staff and forum feedback.", secGeneral);

  const secWar = await ensureCategory("The War", "Campaigns, raids, kill pics and companies.");
  await ensureCategory("War Strategy & Tactics", "Siege tactics, troop comps and boss raids.", secWar);
  await ensureCategory("Campaign Reports & Kill Pics", "Battle reports and your best kills.", secWar);
  await ensureCategory("Clan & Company Recruitment", "Recruit for your clan or war company.", secWar);

  const secMedia = await ensureCategory("Media", "Show off your achievements and clips.");
  await ensureCategory("Goals & Achievements", "Milestones, level-ups and brag posts.", secMedia);
  await ensureCategory("Screenshots & Videos", "Share screenshots and clips.", secMedia);
  await ensureCategory("Events", "Community and staff-hosted events.", secMedia);

  const secSupport = await ensureCategory("Support", "Help, reports and applications.");
  await ensureCategory("Help & Support", "Stuck? Ask the community.", secSupport);
  await ensureCategory("Player Reports", "Report rule-breakers (with evidence).", secSupport);
  await ensureCategory("Bug Reports", "Found a bug? Report it here.", secSupport);
  await ensureCategory("Appeals", "Appeal a mute, ban or jail.", secSupport);
  await ensureCategory("Staff Applications", "Apply to join the staff team.", secSupport);

  // Announcements / Rules cids for seed content
  const annCid = (await listCategories()).find((c) => c.name === "Announcements")?.cid;
  const rulesCid = (await listCategories()).find((c) => c.name === "Rules")?.cid;

  console.log("Posting starter content...");
  if (annCid) await postGuide(annCid, "Welcome to the Kingdom of Lumbridge forums", WELCOME, true);
  if (rulesCid) await postGuide(rulesCid, "Server & Forum Rules", RULES, true);

  console.log("Posting guides...");
  for (const g of GUIDES) await postGuide(guidesCid, g.title, g.body, g.pin);

  console.log("Done.");
}

// ============================ CONTENT ============================
const WELCOME = `# Welcome, adventurer!

This is the official community forum for **Kingdom of Lumbridge** — a free-to-play RuneScape private server built around **siege warfare, deep skilling and real progression**, with its home at Lumbridge.

**New here? Start with these:**
- 📜 *Your First Hour — The Recruit Trials*
- ⚔️ *The War Explained*
- 🛡️ *Feudal Titles & Ranks*

You can find them all in the **Guides** board. Your website account works here too — log in and say hello in **Introductions**. See you on the battlefield. ⚔️`;

const RULES = `# Server & Forum Rules

**In-game**
1. No real-world trading of gold or items for real money.
2. No cheating, botting or third-party automation.
3. No bug abuse — report exploits in **Bug Reports**.
4. No harassment, hate speech, or scamming other players.
5. Staff decisions are final; appeal punishments in **Appeals**.

**Forum**
1. Be respectful. No flaming, spam, or NSFW content.
2. Post in the correct board and search before posting.
3. No advertising other servers.
4. English in public boards, please.

Breaking these can lead to a mute, ban or jail. Play fair and have fun.`;

const GUIDES = [
  // -------------------- Getting Started --------------------
  { title: "Your First Hour — The Recruit Trials", pin: true, body:
`Every new citizen of Lumbridge starts with the **Recruit Trials** — a short 4-step onboarding that teaches the core loop and kits you out. Hint arrows guide each step.

## The four steps
1. **FIGHT** — Kill **5 goblins** at the western frontier (just west of Lumbridge). Reward: a Bronze armour set.
2. **RANK** — Take your goblin coins to **Duke Horacio** in the Lumbridge market and claim your first feudal rank.
3. **SLAY** — Get a contract from **Vannaka** (south side of the market) — your first is always 5 rats.
4. **SUPPLY** — Mine **5 ore** in the Lumbridge Castle cellar (handed to you as a resource contract).

## Rewards
- Steel armour pieces at each milestone → a **full steel kit** on completion.
- **50 War Effort** — enough to start qualifying for the daily XP/drop bonus.

Once you're done, the realm is yours: skill, slay, trade, or march to war. Type \`::home\` any time to return to Lumbridge.` },

  { title: "Essential Commands — the ones you'll actually use", pin: true, body:
`Type these in the chatbox. The big ones:

| Command | What it does |
|---|---|
| \`::home\` | Teleport to Lumbridge (works even in combat) |
| \`::portal\` | Open the teleport portal (categories + destinations) |
| \`::portalto <key>\` | Teleport to a destination by key |
| \`::portalback\` | Repeat your last portal teleport |
| \`::title\` | Your feudal rank, max armour tier, and next-rank cost |
| \`::points\` | Your point balances (War Effort, Boss, Vote, Prestige, Donor) |
| \`::slayer\` | Your current Slayer/war contract + streak |
| \`::companions\` | List your companion roster |
| \`::pkstats\` | Your PK kills, deaths and Elo |
| \`::yell <msg>\` | Broadcast to everyone (rank-coloured) |

A full reference lives in **Command Reference** below.` },

  { title: "The Teleport Portal — getting around", body:
`Fast travel runs through the **teleport portal**. Until the in-client UI lands, drive it with commands:

- \`::portal\` — list all categories, plus your recent and the server's popular destinations.
- \`::portal <category>\` — show destinations in a category (each has a key + danger level).
- \`::portalto <key>\` — teleport to a destination.
- \`::portalback\` — instantly re-cast your last teleport.

Your **previous teleports** are remembered per character, and there's a server-wide **"popular now"** list. Note: teleblock and combat lock stop portal use (standard rules) — but \`::home\` always works.` },

  // -------------------- The War --------------------
  { title: "The War Explained", pin: true, body:
`Kingdom of Lumbridge is **player-offense**: you don't just defend — you raise troops and march on hostile cities for their loot. There are three tiers, unlocked by your feudal rank:

- **RAID** (Lord+) — summon a boss to your city and bring an escort squad.
- **CAMPAIGN** (Minister+) — field a column of allied troops that breaks a city's frontier garrison and seizes a cut of its loot.
- **CONQUEST** (King) — a full army assault for the biggest prizes.

**Loot is split by contribution.** Everyone who deals damage in a raid/campaign earns a share of the captured war-chest (coins, gear, supplies) proportional to the damage they did. Sponsors who pay to start the fight earn a tithe on top.

Read the rank, troop and boss guides below to start leading.` },

  { title: "Feudal Titles & Ranks", pin: true, body:
`Your **rank** is bought with coins from **Duke Horacio** in Lumbridge Castle. It gates the metal armour you can wear, colours your name, and unlocks war commands.

| Rank | Armour | Cost | Unlocks |
|---|---|---|---|
| Peasant | Iron | free | — |
| Commoner | Steel | 10,000 | — |
| Squire | Black | 50,000 | coloured name |
| Soldier | Mithril | 150,000 | — |
| Knight | Adamant | 500,000 | — |
| **Lord** | Dragon | 2,000,000 | **RAID** (boss summons) |
| **Minister** | Dragon | 10,000,000 | **CAMPAIGN** |
| **King** | Dragon | 50,000,000 | **CONQUEST** |

Try to equip armour above your rank and it's stripped automatically. Check your standing any time with \`::title\`.` },

  { title: "Boss Raids — Summoning the Corporeal Beast", body:
`Once you're **Lord+**, you can summon a world boss for your city with \`::summonboss corporeal_beast\` (\`::boss\` teleports you to the arena).

**The Corporeal Beast**
- Cost to summon: **3,000,000 coins** · HP: **1,200** · spawns near Lumbridge (3247, 3319).
- **Loot is by damage %** — everyone who hits it shares the drop. The sponsor takes a 10% tithe and is **guaranteed one rare** (MVP rule).
- **Rare table:** Spirit shield, Holy elixir, Rune platebody/legs, Dragon med helm, Battlestaff, Dragon dagger, Rune scimitar.
- **Mega-rares** (rolled independently for each contributor):
  - Arcane / Spectral / Elysian **Sigil** — 1/150 each
  - Blessed Spirit Shield — 1/120
  - Draconic Visage — 1/200
  - Corporeal pet — 1/700
- Coin drop 20k–45k, plus **Boss points** (25 sponsor / 10 each) and **Prestige**.

Bring friends — more damage dealt means more rolls on the table.` },

  { title: "Commanding Troops & Campaigns", body:
`Leading the war is about **troops** and **cities**.

**Raids (Lord+)** — \`::sendtroops\` (1,000,000 coins) spawns your boss and an escort squad. Order them:
- \`::troops advance\` — they fight independently; you earn their kills.
- \`::troops follow\` — they guard you.

**Campaigns (Minister+)** — \`::campaign [city]\` marches a column to a hostile city (currently Varrock), breaks its garrison, and seizes loot. Every contributor splits the war-chest.

**Conquest (King)** — \`::conquest [city]\` is the full-army version: larger forces, bigger payout.

You pay the cost up front, so coordinate with your company before launching.` },

  { title: "The Goblin Warren — frontier farming", body:
`East of Lumbridge, the **Goblin Warren** is where the frontier mobs mass — a solid early coin source and a taste of the war.

- **Hill Giants** (6) — 100–250 coins
- **Ogres** (4) — 250–600 coins
- **General Bentnoze** (boss) — 2,000–4,000 coins
- **General Wartface** (boss) — 2,000–4,000 coins

The population is continuously managed (no single-kill respawn timer), so there's always something to fight. Great for funding your first few ranks.` },

  // -------------------- Skilling --------------------
  { title: "Mining in the Lumbridge Cellar", body:
`Mining lives in the **Lumbridge Castle cellar**. Grab the **bronze pickaxe** that respawns on the floor when you enter, then click "Mine" — rocks never deplete and you auto-mine until your pack is full.

| Ore | Level | XP |
|---|---|---|
| Copper / Tin / Clay | 1 | 17.5 / 17.5 / 5 |
| Iron | 15 | 35 |
| Silver | 20 | 40 |
| Coal | 30 | 50 |
| Gold | 40 | 65 |
| Mithril | 55 | 80 |
| Adamant | 70 | 95 |
| Rune | 85 | 125 |
| Amethyst | 92 | 240 |

**Layout:** low/mid rocks on the south wall, high-tier (mith→amethyst) on the east wall, the furnace on the west wall, anvils to the north — so you can mine → smelt → smith in one room.` },

  { title: "Smithing — Furnace & Anvil", body:
`Smithing is the second half of the cellar. **Smelt** ore into bars at the furnace, then **forge** bars into gear at the anvils.

**Smelting** (use ore on the furnace):

| Bar | Needs | Level | XP |
|---|---|---|---|
| Bronze | Copper + Tin | 1 | 6.2 |
| Iron | Iron | 15 | 12.5 |
| Steel | Iron + 2 coal | 30 | 17.5 |
| Mithril | Mith + 4 coal | 50 | 30 |
| Adamant | Adam + 6 coal | 70 | 37.5 |
| Rune | Rune + 8 coal | 85 | 50 |

**Smithing** (use bar on an anvil): Dagger (1 bar) · Scimitar (2) · Full helm (2) · Kiteshield (3) · Platebody (5). The level needed = the metal's base level + the piece's offset — e.g. a **Rune scimitar = 89 Smithing** (85 + 4).

Everything you smith can be handed to the **Supply Depot** for War Effort.` },

  { title: "Agility Shortcuts", body:
`Agility here is about **world traversal** — shortcuts that save you walking, gated by level:

| Obstacle | Level | XP |
|---|---|---|
| Stile / broken fence | 1 | 3 |
| Rock | 5 | 5 |
| Rockslide | 10 | 8 |
| Stepping stones | 15 | 6 |
| Jutting wall (squeeze) | 20 | 10 |
| Log balance | 30 | 12 |

Click the action on the obstacle ("Climb-over", "Jump", "Squeeze"…) and you're carried across. Rooftop courses are coming later.` },

  { title: "Slayer & War Contracts", body:
`**Vannaka** in the Lumbridge market (3219, 3215) hands out two kinds of contract — both pay **War Effort**, the realm's primary currency.

- **Combat contracts** — kill a set number of an assigned NPC (rats, goblins, spiders, skeletons, demons, dragons…).
- **Resource contracts** — gather an assigned resource (ore, fish, logs, herbs), unlocked as your skills grow.

**Reward:** a base of **8 War Effort**, **+4 for every 5-contract streak** (so your 6th contract pays 12, your 11th pays 16…). Check your task with \`::slayer\`.

**Slayer reward shop** (spend War Effort): Shark (4), Prayer pot (8), Super att/str/def (6 each), Super combat (20), Rune scimitar (30), Rune full helm (25), Amulet of power (20).` },

  // -------------------- Economy --------------------
  { title: "Currencies of Lumbridge", body:
`Beyond coins, the realm runs on **points** — persistent counters you can't trade or drop, earned from content. See them all with \`::points\`.

- **War Effort** — the main one. Earned from Slayer/war contracts, supply deliveries and boss kills; spent at the Slayer/war shops. Also drives the **daily bonus** (see that guide).
- **Boss points** — +10 per boss kill (shop coming).
- **Vote points** — from voting (claiming coming soon).
- **Prestige** — awarded for sponsoring boss summons.
- **Donor points** — from supporting the server.

Coins (and Blood Money for PvP) remain normal tradeable items.` },

  { title: "The Supply Depot — fund the war", body:
`The **Quartermaster** buys finished goods for **War Effort** — turning your skilling into reputation and currency:

- **Cooked food:** Shrimp (1 WE) → Shark (8 WE)
- **Potions** (any dose): Attack (3 WE) → Super combat (10 WE)
- **Bars:** Bronze (1 WE) → Rune (12 WE)

So a mining/smithing session doesn't just train Smithing — every bar is War Effort waiting to be claimed. Cook, brew or smith, then deliver.` },

  { title: "The Daily War-Effort Bonus", body:
`**War Effort you earn each day** (separate from your balance) unlocks a daily buff to **skilling XP and monster drops**:

| WE earned today | XP bonus | Drop bonus |
|---|---|---|
| 0–39 | 0% | 0% |
| 40–79 | +5% | +3% |
| 80–149 | +10% | +5% |
| 150+ | +15% | +8% |

It resets at midnight UTC. The takeaway: knock out a few contracts and supply deliveries early, and the rest of your day's grinding is boosted. (Note: the bonus applies to XP and drops, not to War Effort itself.)` },

  { title: "Best Early Money-Makers", body:
`Working toward your first ranks? Here's the efficient early loop:

1. **Goblins & the Goblin Warren** — fast coins from day one (Generals drop 2–4k).
2. **War contracts from Vannaka** — coins + War Effort; the streak bonus rewards consistency.
3. **Mining → Smithing → Supply Depot** — train two skills and sell the bars for War Effort.
4. **Claim the daily bonus first** (150 WE) so everything else earns more.

Reinvest coins into **ranks** (Duke Horacio) — better armour opens better content, and Lord+ opens boss raids, which is where the real loot is.` },

  // -------------------- PvP & Reference --------------------
  { title: "Wilderness & PvP Guide", body:
`PvP happens in **graded zones** — know where you stand before you fight.

- **Safe core** (Lumbridge & around) — always safe.
- **Contested frontier** (town borders, west & east) — PvP on, but **capped at level 10**.
- **Main wilderness** — north of the core, getting deeper (and higher level) the further you go (up to ~56).
- **East Lumbridge PK pocket** — an isolated single-combat duel spot, no level cap.

**Rules of engagement:** attacking first **skulls** you (30 min) and drops your loot penalty to **−25%**. On death you drop everything except your **3 most valuable items** (prayer/magic gear is auto-kept). Single-combat zones are 1v1; multi zones allow piling. Your combat level ± the wilderness level sets who can attack you.` },

  { title: "PK Stats, Elo & the Ladder", body:
`Every real-vs-real kill counts toward your **PK ladder**. Check yours with \`::pkstats\` — lifetime **kills**, **deaths**, **K/D** and an **Elo rating** (everyone starts at 1000). Beating higher-Elo players gains you more; the website **Hiscores** has live **Top Kills / Deaths / K/D / Elo** tabs.

Kills against practice bots don't count — the ladder is genuine PvP only. Climb it and your name sits at the top of the realm.` },

  { title: "Companions — your personal squad", body:
`You can recruit, train, gear and command a squad of **companions** (Lieutenants) who fight beside you.

- \`::companions\` — list your roster (names, combat levels, current order).
- \`::companion <train|follow|deploy|return> [slot]\` — set orders: **train** (auto-fight to level up), **follow** (guard you), **deploy** (independent offense), **return** (recall).
- \`::companion equip <slot> <itemId>\` / \`unequip\` — gear them from your **bank** (they have their own equipment).

Companions persist between sessions and respawn if they fall. They're a force multiplier for PvM and a head start in the war.` },

  { title: "Command Reference", pin: true, body:
`The full player command list:

| Command | Description |
|---|---|
| \`::home\` | Teleport to Lumbridge home |
| \`::portal\` / \`::portalto <key>\` / \`::portalback\` | Teleport portal |
| \`::title\` | Your rank, armour tier and next-rank cost |
| \`::points\` | All point balances |
| \`::slayer\` | Current contract + streak |
| \`::companions\` / \`::companion …\` | Companion roster & orders |
| \`::pkstats\` | PK kills / deaths / Elo |
| \`::sendtroops\` / \`::troops advance\|follow\` | Raid squad (Lord+) |
| \`::campaign [city]\` | Campaign (Minister+) |
| \`::conquest [city]\` | Conquest (King) |
| \`::summonboss <key>\` / \`::boss\` | Summon / go to a boss (Lord+) |
| \`::yell <msg>\` | Server-wide broadcast |

Some commands unlock with your rank. Staff/dev commands aren't listed here.` },

  { title: "FAQ — frequently asked questions", body:
`**Is it free?** Yes — completely free to play.

**Where's home?** Lumbridge. Type \`::home\` from anywhere.

**How do I make money early?** Goblins, war contracts from Vannaka, and mining/smithing into the Supply Depot. See *Best Early Money-Makers*.

**How do I rank up?** Earn coins, then buy ranks from **Duke Horacio** in the castle. Ranks gate armour and unlock the war.

**How do I start the war / raid bosses?** Reach **Lord** (2M) for boss raids, **Minister** for campaigns, **King** for conquest.

**Does my website account work in-game?** Yes — one account works on the site, the forum and in-game.

**Found a bug?** Post it in **Bug Reports** with steps to reproduce.` },
];

main().catch((e) => { console.error(e); process.exit(1); });
