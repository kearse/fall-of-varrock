/**
 * Seeds the core player guides into the `guides` collection. Re-runnable:
 * createGuide upserts by slug, so editing a guide's body here and re-running
 * replaces it. Run with: npm run seed:guides (needs MONGODB_URI to point at the
 * same database the game + site use).
 *
 * Titles are chosen so the derived slug matches the Discord #guides index links
 * (discord-bot/src/seed/guidesIndex.ts): e.g. "The War and Campaigns" -> the-war-and-campaigns.
 */
import { createGuide } from "../src/lib/guides";

const AUTHOR = "Fall of Varrock";

interface Seed {
  title: string;
  category: string;
  summary: string;
  body: string;
}

const GUIDES: Seed[] = [
  {
    title: "Getting Started",
    category: "Getting Started",
    summary: "Your first hour: enlist, find your way around Lumbridge, and earn your first rank.",
    body: `# Getting Started

Welcome to **Fall of Varrock** — a war server. You begin a lowly **Peasant** and rise through a feudal ladder of *earned* power. This guide gets you on your feet.

## 1. Log in
Register on the website or just type a new username and password at the in-game login — it's the same account. Your stats are shared with the website hiscores in real time.

## 2. Enlist — the Recruit Trials
You spawn in **Lumbridge** (your home; \`::home\` returns you here any time). The realm is at war, and the **Recruiting Sergeant** at the castle gate enlists you. Type \`::trials\` to see your current objective. The trials walk you through the basics:

- **Fight** the goblins on the northern frontier.
- **Rank up** by buying your first title from Duke Horacio.
- **Slay** — take a war-contract from Vannaka.
- **Supply** — mine some ore in the cellar.

Finishing the trials puts you on the feudal ladder with your first War Effort.

## 3. Get around — the teleport portal
There's a **teleport portal** at home (the courtyard market). Open it to travel to skilling spots, the Wilderness, bosses, Slayer, and minigames. Handy commands: \`::home\`, and the portal's "previous teleport" repeat.

## 4. The Lumbridge shop hub
East of the castle is a courtyard market — two facing rows of vendors selling weapons (up to adamant), ranged gear, runes, prayer bones, crafting/skilling supplies, and a reward-points exchange. Higher-tier food is sold **raw** (you cook it) and strong potions sold **unfinished** (you finish them with Herblore) — by design, so skills matter.

## 5. Train and earn
Coin is power here. Early money comes from the goblin frontier, **Slayer war-contracts** (Vannaka), and skilling + selling. See **Making Money** for the full picture.

## 6. Your first rank
When you've saved up, talk to **Duke Horacio** in the castle to buy your way up the ladder (\`::title\` shows your rank and the next cost). Each rank lets you wear better armour and, eventually, command the war.

## Where to next
- **Feudal Power and Riches** — the rank ladder and how to fund it.
- **The War and Campaigns** — raids, campaigns, and conquests.
- **PKing and the Wilderness** — risk fights for Blood Money and rank.
- **Making Money** — currencies, the economy, and money-makers.`,
  },
  {
    title: "The War and Campaigns",
    category: "War",
    summary: "Raids, campaigns, and conquests — commanding troops and splitting the spoils.",
    body: `# The War and Campaigns

The endgame of Fall of Varrock is **war**. High-ranking players command allied troops against enemy cities; the loot the enemy drops is **pooled** and split among everyone who fought. There are three scales of operation, each unlocked by your feudal rank.

## The three operations

| Operation | Rank needed | Troops | Win condition | Cost | Reward pool |
|---|---|---|---|---|---|
| **Raid** | Lord | 8 | Kill the summoned boss | Free (with the boss summon) | Boss loot, by damage |
| **Campaign** | Minister | 40 | Clear 60 frontier enemies | 3,000,000 coins | 750,000 base pool |
| **Conquest** | King | 64 | Clear 140 frontier enemies | 15,000,000 coins | 3,000,000 base pool |

The first hostile target is **Varrock**. Launch an op with \`::campaign varrock\` (Minister+) or \`::conquest varrock\` (King). Lord+ can summon a boss and back it with a small **raid party** (\`::sendtroops\`).

## Commanding your troops
Your allied column musters and **marches** to the target, fighting through the enemy lines. You don't have to babysit them — set their orders with:

- \`::troops advance\` — they push the objective on their own (default).
- \`::troops follow\` — they guard you and travel at your side.

## How the spoils are split
When a campaign or conquest is **won**, the enemy city's loot — pooled instead of dropped on the floor — is divided like this:

- **Commander's tithe:** the sponsor who funded and led the op takes **10%** off the top, and gets their coin war-stake **refunded** (you only lose the cost if the op *fails*).
- **Participants:** everyone who fought splits the rest **by contribution** (time spent fighting in the battle area). Your share is auto-banked as coins.
- **Donor bonus:** donor participants also receive **+1% of the pool's value in donor points** — minted separately, never taken from the shared loot.

You also earn **War Effort / Prestige** for taking part (10 for a raid, 25 for a campaign, 60 for a conquest).

## Your officers
Lord+ commanders can field **Lieutenants** — levelable combat companions (melee, ranged, or mage, up to 3) that train, follow, and fight alongside you, geared from your bank. (The full recruitment flow via General Zo is rolling out.)

## See also
- **Feudal Power and Riches** — earn the rank to command.
- **Making Money** — fund the 3M / 15M war-stakes.`,
  },
  {
    title: "Feudal Power and Riches",
    category: "War",
    summary: "The rank ladder, what each title unlocks, and how to fund your climb to King.",
    body: `# Feudal Power and Riches

Power in Lumbridge is a **feudal ladder**. You buy your rank from **Duke Horacio** in Lumbridge Castle with coin you've *earned* — there is no shortcut and **no donation buys a title**. Your rank decides the armour you may wear and the war you may command.

## The rank ladder

| Rank | Cost (coins) | Max armour | Unlocks |
|---|---|---|---|
| **Peasant** | — (start) | Iron | — |
| **Commoner** | 10,000 | Steel | — |
| **Squire** | 50,000 | Black | Coloured name |
| **Soldier** | 150,000 | Mithril | — |
| **Knight** | 500,000 | Adamant | — |
| **Lord** | 2,000,000 | Rune & Dragon | **Raids** — summon bosses, send a raid party |
| **Minister** | 10,000,000 | Dragon | **Campaigns** — march on enemy cities |
| **King** | 50,000,000 | Dragon | **Conquests** — full army invasions |

Check your standing any time with \`::title\` (shows your rank, armour cap, and next cost). Equip armour above your rank and it's politely removed.

## Rank is respect
Out-ranking an enemy makes them stop bothering you: goblins ignore Squires and up, hobgoblins ignore Soldiers and up, ogres ignore Knights and up. The higher you climb, the less the rabble troubles you — but the Wilderness respects no titles (there, only combat level matters).

## How to fund the climb
The ladder is steep (50M to be King), so riches and power go hand in hand. Coin comes from:

- **The frontier:** goblins, hobgoblins, and ogres drop coin and the odd bit of gear.
- **Slayer war-contracts:** Vannaka's tasks pay War Effort *and* feed your coffers.
- **Skilling and selling:** gather and craft, then sell to shops or other players.
- **Bossing:** the Corporeal Beast world-boss event (see Making Money).
- **War spoils:** winning campaigns/conquests auto-banks your share — and as commander, your war-stake comes back on a win.

## See also
- **The War and Campaigns** — spend that rank on the battlefield.
- **Making Money** — the full earning toolkit.`,
  },
  {
    title: "PKing and the Wilderness",
    category: "PvP",
    summary: "Wilderness zones, what you risk, the Elo ladder, and Blood Money.",
    body: `# PKing and the Wilderness

North of Lumbridge lies the **Wilderness** — the only place players can attack each other. Win fights for **Blood Money** and a spot on the **Elo ladder**.

## Where it's dangerous
- The Wilderness begins at the **top of Lumbridge** and runs north. Anything outside it is safe.
- A few **safe carve-outs** sit inside the Wilderness: Falador, the Grand Exchange, the Varrock and Edgeville banks. The Lumbridge town/spawn core is always safe.
- The town's **contested frontier** (the hobgoblin/knight bands east and west) is PvP but capped at **Wilderness level 10** — not open any-level.

## Single vs multi
The shallower core of the Wilderness is **single combat** (one-on-one). Deeper and wider areas are **multi** — you can be piled by several attackers, so bring friends or watch your back.

## Wilderness level and who can hit you
Your **Wilderness level** rises the deeper you push (about +1 level per 8 tiles north, up to **56**). The deeper you are, the wider the combat-level range that can attack you — so deep Wilderness is open season.

## What you risk
Die in the Wilderness and you keep your **3 most valuable items** (4 with Protect Item). If you're **skulled** — from attacking another player unprovoked — you keep **nothing** (1 with Protect Item). Everything else drops to your killer. So only take in what you're willing to lose.

## Rewards
- **Blood Money** — a currency you carry (and can lose), awarded per real-player kill: **25 + (victim's combat level × 3)**. Spend it at the PK Rewards shop (\`::pkshop\`) on supplies and gear.
- **Elo & rank** — every real kill/death adjusts your rating; \`::pkstats\` shows your kills, deaths, K/D, Elo, and your **TOP %** standing. Killing practice bots doesn't count.

## Getting into it
Use the teleport portal's Wilderness tab — there's a PK "corridor" from the **Outlaw Camp** (shallow) down to the **Deep Wilderness** (level 50+). Bank your good gear first, kit a risk-appropriate setup, and go hunting.

## See also
- **Making Money** — fund your PK trips.
- **Feudal Power and Riches** — rank doesn't protect you in the wild, but the coin helps.`,
  },
  {
    title: "Making Money",
    category: "Economy",
    summary: "Currencies, the shop economy, bossing, Slayer, and the best early money-makers.",
    body: `# Making Money

Coin is the engine of the kingdom — it buys your ranks, funds your wars, and kits your PK trips. Here's how the economy works and how to get rich.

## The currencies
- **GP (coins)** — the main currency; trades, shops, ranks, war-stakes.
- **Blood Money** — earned by PKing; spent at the PK Rewards shop (\`::pkshop\`).
- **Reward points** — **Boss**, **Vote**, **War Effort**, and **Donor** points. Check balances with \`::points\` and spend them at the reward vendors.

## The shop economy
The Lumbridge market is deliberately *gated* so skills and content matter:
- Weapons sell only up to **adamant** — rune and beyond come from content (drops, rewards).
- Strong **food is sold raw** (cook it yourself) and strong **potions unfinished** (finish them with Herblore).
- The **General Store** buys most junk back as a coin sink.
- The **Trading Post** (\`::market\`) is a player-to-player marketplace with an NPC liquidity backstop for common supplies.

## The best money-makers
1. **The frontier** — early, steady coin from goblins/hobgoblins/ogres while you train.
2. **Slayer war-contracts** — Vannaka's tasks (\`::slayer\`) pay War Effort and let you bank kills; the reward shop sells supplies and rune gear cheaply.
3. **The Corporeal Beast** (\`::worldboss\`) — the war's world boss. Every contributor rolls its high-value loot table by damage share, with sigils, the visage, and its pet as chase drops logged to your **Collection Log** (\`::clog\`).
4. **War spoils** — once you can command, winning campaigns/conquests banks a share of huge loot pools (and refunds your stake on a win).
5. **PKing** — Blood Money plus whatever your victims were risking.

## Sinks (where money goes)
Ranks (Duke Horacio), shops, war-stakes, the Trading Post margin, and consumables all drain coin out of the economy — keeping prices honest.

## See also
- **Feudal Power and Riches** — what you're saving up for.
- **The War and Campaigns** — the biggest payouts in the game.`,
  },
];

async function main() {
  for (const g of GUIDES) {
    const doc = await createGuide({ title: g.title, summary: g.summary, body: g.body, category: g.category, authorName: AUTHOR });
    console.log(`seeded guide: ${doc.slug}`);
  }
  console.log(`Done — ${GUIDES.length} guides seeded.`);
  process.exit(0);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
