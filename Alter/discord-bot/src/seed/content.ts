import { config } from "../config.js";
import { CH } from "../guild.js";

/**
 * All seed copy for the info channels, in one editable place. `seed.ts` turns
 * these into embeds (+ optional link buttons) and posts them idempotently.
 * Edit freely — re-running /seed updates nothing already posted (matched by the
 * embed title), so to refresh a channel delete the old message then /seed.
 *
 * Every claim here is accurate to the game (Fall of Varrock: a war server —
 * feudal ranks bought from Duke Horacio, war commands, Wilderness PKing). Full
 * how-to guides live on the website; #guides links to them.
 *
 * Links/download URLs come from config (env: SITE_URL, DOWNLOAD_*).
 */
const SITE = config.siteUrl;
const client = (fallback: string) => fallback || `${SITE}/download/client`;

export interface SeedEntry {
  channel: string;
  title: string; // also the idempotency marker — keep unique & stable
  color: number;
  /** Markdown description. */
  body: string;
  /** Optional URL buttons: [label, url, emoji?]. Buttons with empty url are dropped. */
  links?: { label: string; url: string; emoji?: string }[];
}

export const SEED: SeedEntry[] = [
  {
    channel: CH.welcome,
    title: "⚔️ Welcome to the Fall of Varrock",
    color: 0xd4af37,
    body:
      "**The realm is at war.** Lumbridge stands, but the frontier is contested — and every recruit " +
      "earns their place. Here's how to begin:\n\n" +
      "**1.** Download the client and log in (or register on the website — same account).\n" +
      `**2.** Link Discord to your game account: run \`/link\` (steps in #${CH.linkAccount}).\n` +
      "**3.** In-game, the Recruiting Sergeant at the castle gate enlists you — run `::trials` to track your orders.\n" +
      `**4.** Read #${CH.rules}, then check #${CH.howToPlay} and #${CH.guides} to learn the war, the ranks, and the Wilderness.\n\n` +
      "Linking unlocks the game channels, hiscores commands, and your rank/donor roles here.",
    links: [
      { label: "Play", url: `${SITE}/play`, emoji: "🎮" },
      { label: "Website", url: SITE, emoji: "🌐" },
      { label: "Hiscores", url: `${SITE}/hiscores`, emoji: "🏆" },
    ],
  },
  {
    channel: CH.rules,
    title: "📜 Server Rules",
    color: 0xc0392b,
    body:
      "Breaking these can mean a mute, kick, or ban. Don't argue with staff in public — open a ticket.\n\n" +
      "**1.** Be respectful. No harassment, hate speech, or discrimination.\n" +
      "**2.** No real-world trading (RWT), account selling, or buying gold/items for cash.\n" +
      "**3.** No advertising other servers or services.\n" +
      "**4.** No scamming. No bug abuse — report exploits privately via a ticket, don't share them.\n" +
      "**5.** No spam, NSFW, or malicious links.\n" +
      "**6.** One account per giveaway; no alt/multi-account abuse.\n" +
      "**7.** Wilderness PKing, trash talk in good fun, and rivalries are fine — targeted harassment is not.\n" +
      "**8.** Staff have the final say. Use tickets for disputes.",
  },
  {
    channel: CH.download,
    title: "📥 Download the Client",
    color: 0x2ecc71,
    body:
      "Grab the official Fall of Varrock client for your platform, then log in (new username + " +
      "password at the login screen creates your account). Trouble loading? Open a support ticket.\n\n" +
      "The website has every download link and the current login world host.",
    links: [
      { label: "Windows", url: client(config.downloads.windows), emoji: "🪟" },
      { label: "Mac", url: client(config.downloads.mac), emoji: "🍎" },
      { label: "Jar", url: client(config.downloads.jar), emoji: "☕" },
      { label: "All downloads / help", url: `${SITE}/play`, emoji: "🌐" },
    ],
  },
  {
    channel: CH.howToPlay,
    title: "🧭 How to Play — the War Loop",
    color: 0x3498db,
    body:
      "Fall of Varrock is a **war server**. You start a Peasant and rise through a feudal ladder " +
      "of *earned* power. The loop:\n\n" +
      "**1. Enlist.** You spawn in Lumbridge. The Recruiting Sergeant at the castle gate starts your " +
      "**Recruit Trials** — run `::trials` to see your next objective.\n" +
      "**2. Earn coin.** Fight the goblin frontier north of town, take **Slayer war-contracts** from " +
      "Vannaka, or train a skill and sell. Coin is power here.\n" +
      "**3. Rise in rank.** Buy your title from **Duke Horacio** in the castle (`::title`): Peasant → " +
      "Commoner → Squire → Soldier → Knight → **Lord** → **Minister** → **King**. Your rank sets the " +
      "armour you may wear *and* the war you may command — and high enough rank makes lesser enemies " +
      "stop bothering you.\n" +
      "**4. Command the war.** **Lord+** summon bosses and send raid parties; **Minister+** launch " +
      "**campaigns** on Varrock; the **King** leads full **conquests**. Win and the enemy's loot " +
      "**pools** and splits among everyone who fought.\n" +
      "**5. PK.** Head north into the **Wilderness** for Blood Money and an Elo rank (`::pkstats`).\n\n" +
      `📖 Full guides — the war, the ranks, getting rich, and PKing — are in #${CH.guides}.`,
  },
  {
    channel: CH.store,
    title: "🛒 Store",
    color: 0xf1c40f,
    body:
      "Support the server on the website store. Purchases deliver **in-game on your next login**, and " +
      "your **donor rank here syncs automatically** once your account is linked.\n\n" +
      "**No pay-to-win.** Rank and power are *earned* in-game — as Duke Horacio puts it, *“no mere " +
      "donation buys a title here.”* Donations grant cosmetics, convenience, and donor points, not raw power.\n\n" +
      "We accept card, PayPal, and crypto. The website is the **only** official store — never buy from DMs.",
    links: [{ label: "Open Store", url: `${SITE}/store`, emoji: "🛒" }],
  },
  {
    channel: CH.donations,
    title: "💎 Donator Ranks",
    color: 0xb9f2ff,
    body:
      "Donating grants a colored Discord rank (synced from your linked account) plus donor perks " +
      "in-game — convenience and cosmetics, not combat advantage:\n\n" +
      "🥉 **Bronze** · 🥈 **Silver** · 🥇 **Gold** · 💎 **Diamond**\n\n" +
      "Perks include donor points (earned, e.g., as a bonus from won campaigns) and quality-of-life " +
      "features. Ranks sync within a few minutes of purchase once you've linked with `/link` — use " +
      "`/sync` (staff) if a rank ever looks out of date.",
    links: [{ label: "Donate", url: `${SITE}/store`, emoji: "💎" }],
  },
  {
    channel: CH.linkAccount,
    title: "🔗 Link Your Account",
    color: 0x2ecc71,
    body:
      "Linking connects your Discord to your Fall of Varrock game account. It unlocks the game " +
      "channels, your rank/donor roles, and lookup commands.\n\n" +
      "**How to link:**\n" +
      "**1.** Log in on the website and open **Account → Link Discord**.\n" +
      "**2.** Copy the 8-character code it gives you.\n" +
      "**3.** Come back here and run `/link <code>`.\n\n" +
      "Your roles apply instantly. Use `/whoami` to check, or `/unlink` to disconnect.",
    links: [{ label: "Get a link code", url: `${SITE}/account`, emoji: "🔗" }],
  },
  {
    channel: CH.botCommands,
    title: "🤖 Bot Commands",
    color: 0x5865f2,
    body:
      "Discord slash commands:\n\n" +
      "`/link <code>` — link your game account\n" +
      "`/unlink` · `/whoami` — manage / check your link\n" +
      "`/player <name>` — look up a player's stats\n" +
      "`/hiscores` — top players by total level\n" +
      "`/online` — who's online right now\n\n" +
      `In-game commands (typed with \`::\` in the client) — like \`::title\`, \`::trials\`, \`::pkstats\`, ` +
      `\`::campaign\`, \`::kbd\` — are covered in the guides in #${CH.guides}.\n\n` +
      "_Staff:_ `/sync`, `/setup`, `/seed`, `/ticketpanel`, `/giveaway`.",
  },
  {
    channel: CH.market,
    title: "🪙 Player Market — How to Post",
    color: 0xe67e22,
    body:
      "Trade with other players here. Use a clear format:\n\n" +
      "```\n[SELLING] Dragon scimitar — 1.2m\n[BUYING]  Rune platebody — paying 60k\nIGN: YourName\n```\n" +
      "In-game you can also use the **Trading Post** (`::market`) for buy/sell offers with an NPC " +
      "liquidity backstop. The main currencies are **GP** and **Blood Money** (from PKing).\n\n" +
      "**Trades are player-to-player and at your own risk** — staff don't mediate market deals. " +
      "No real-money trading (bannable). Screenshot your trades.",
  },
  {
    channel: CH.bugReports,
    title: "🐞 Bug Reports — How to Post",
    color: 0xe74c3c,
    body:
      "Found a bug? Help us squash it. Please include:\n\n" +
      "```\nWhat happened:\nWhat you expected:\nSteps to reproduce:\nYour IGN + time it happened:\n```\n" +
      "Screenshots/clips help a lot. **Do not publicly post exploits** — open a ticket for anything abusable.",
  },
  {
    channel: CH.checkEligibility,
    title: "✅ Giveaway Eligibility",
    color: 0x2ecc71,
    body:
      "To enter giveaways you must:\n\n" +
      "• Have your account **linked** (`/link`) — winners are paid in-game.\n" +
      "• Follow the server rules (no alt/multi-account entries).\n\n" +
      "Enter active giveaways by clicking **🎉 Enter** on the giveaway post. Winners are announced in " +
      `#${CH.dailyWinners} and #${CH.weeklyWinners}.`,
  },
];
