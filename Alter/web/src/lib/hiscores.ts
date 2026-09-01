import { Collections } from "./collections";

// The 23 real skills, in game id order (matches SkillSet on the server). The save
// blob also has a junk "skill" key which we ignore.
export const SKILLS = [
  "attack", "defence", "strength", "hitpoints", "ranged", "prayer", "magic",
  "cooking", "woodcutting", "fletching", "fishing", "firemaking", "crafting",
  "smithing", "mining", "herblore", "agility", "thieving", "slayer", "farming",
  "runecraft", "hunter", "construction",
] as const;

export type SkillName = (typeof SKILLS)[number];

// Standard OSRS XP table - minimum XP required for each level (1..99).
const XP_FOR_LEVEL: number[] = (() => {
  const table = [0, 0]; // index by level; level 1 = 0 xp
  let points = 0;
  for (let level = 1; level < 99; level++) {
    points += Math.floor(level + 300 * Math.pow(2, level / 7));
    table[level + 1] = Math.floor(points / 4);
  }
  return table;
})();

export function xpToLevel(xp: number): number {
  let level = 1;
  for (let l = 99; l >= 1; l--) {
    if (xp >= XP_FOR_LEVEL[l]) {
      level = l;
      break;
    }
  }
  return level;
}

// Combat/PvP ranking categories (mirror Roat's combat hiscore tabs). Data comes
// from the persisted PK attributes written by PkStatsPlugin.
export const COMBAT_CATEGORIES = ["kills", "deaths", "kdr", "elo"] as const;
export type CombatCategory = (typeof COMBAT_CATEGORIES)[number];
export type HiscoreCategory = SkillName | "overall" | CombatCategory;
export const DEFAULT_ELO = 1000;

export interface PvpStats {
  kills: number;
  deaths: number;
  kdr: number;
  elo: number;
}

export interface PlayerSkills {
  displayName: string;
  loginUsername: string;
  skills: Record<SkillName, { xp: number; level: number }>;
  totalLevel: number;
  totalXp: number;
  pvp: PvpStats;
}

function parsePvp(attributes: any): PvpStats {
  const attr = attributes?.attribute ?? {};
  const kills = Math.max(0, Math.floor(attr.pk_kills ?? 0));
  const deaths = Math.max(0, Math.floor(attr.pk_deaths ?? 0));
  const elo = Math.floor(attr.pk_elo ?? DEFAULT_ELO);
  const kdr = deaths === 0 ? kills : kills / deaths;
  return { kills, deaths, kdr, elo };
}

function parseSkills(displayName: string, loginUsername: string, attributes: any): PlayerSkills | null {
  const raw = attributes?.skills;
  if (!raw) return null;
  const skills = {} as PlayerSkills["skills"];
  let totalLevel = 0;
  let totalXp = 0;
  for (const name of SKILLS) {
    const xp = Math.max(0, Math.floor(raw[name]?.xp ?? 0));
    // Hitpoints starts at level 10 (1154 xp); xpToLevel handles it from the table.
    const level = name === "hitpoints" ? Math.max(10, xpToLevel(xp)) : xpToLevel(xp);
    skills[name] = { xp, level };
    totalLevel += level;
    totalXp += xp;
  }
  return { displayName, loginUsername, skills, totalLevel, totalXp, pvp: parsePvp(attributes) };
}

export function isCombatCategory(c: string): c is CombatCategory {
  return (COMBAT_CATEGORIES as readonly string[]).includes(c);
}

// ---- UI metadata: how each category is grouped/labelled on the hiscores page. ----

/** The category rail, grouped like OSRS/Roat. */
export const CATEGORY_GROUPS: { name: string; items: HiscoreCategory[] }[] = [
  { name: "General", items: ["overall"] },
  { name: "Player vs Player", items: [...COMBAT_CATEGORIES] },
  { name: "Skills", items: [...SKILLS] },
];

/** Nav label for a category. */
export function categoryLabel(c: HiscoreCategory): string {
  if (c === "overall") return "Overall";
  if (c === "kills") return "Kills";
  if (c === "deaths") return "Deaths";
  if (c === "kdr") return "K/D Ratio";
  if (c === "elo") return "Elo Rating";
  return titleCase(c);
}

/** Header for the ranked value column. */
export function valueHeader(c: HiscoreCategory): string {
  if (c === "overall") return "Total Lvl";
  if (c === "kdr") return "K/D";
  if (c === "elo") return "Elo";
  if (c === "kills" || c === "deaths") return categoryLabel(c);
  return "Level";
}

// ---- caching: parsing every save blob per request is wasteful; the leaderboard
// only needs to be a few seconds fresh, so memoize the parsed set briefly. ----
const CACHE_MS = 30_000;
let cache: { at: number; players: PlayerSkills[] } | null = null;

/** All players that have a save blob, ready to rank (cached ~30s). */
export async function getAllPlayerSkills(): Promise<PlayerSkills[]> {
  if (cache && Date.now() - cache.at < CACHE_MS) return cache.players;

  const accounts = await Collections.accounts();
  const details = await Collections.details();

  const detailDocs = await details.find({}).toArray();
  // Logins are matched case-insensitively: the game normalizes to lowercase on login now,
  // but details docs written by older builds (and the one-shot migration) carry mixed case —
  // an exact-match lookup silently dropped their display names and player pages.
  const nameByLogin = new Map<string, string>();
  for (const a of await accounts.find({}, { projection: { loginUsername: 1, currentDisplayName: 1 } }).toArray()) {
    nameByLogin.set(String(a.loginUsername).toLowerCase(), a.currentDisplayName);
  }

  // Dedupe by normalized login (a stale mixed-case doc + the live lowercase doc would
  // otherwise rank the same player twice) — the doc with the most total XP wins.
  const byLogin = new Map<string, PlayerSkills>();
  for (const d of detailDocs) {
    const login = String(d.loginUsername ?? "").toLowerCase();
    if (!login) continue;
    const display = nameByLogin.get(login) ?? d.loginUsername;
    const parsed = parseSkills(display, login, d.attributes);
    if (!parsed) continue;
    const prev = byLogin.get(login);
    if (!prev || parsed.totalXp > prev.totalXp) byLogin.set(login, parsed);
  }
  const players = [...byLogin.values()];
  cache = { at: Date.now(), players };
  return players;
}

/** Ranked list. `category` = "overall", a skill name, or a combat category. */
export async function getHiscores(category: HiscoreCategory): Promise<PlayerSkills[]> {
  const players = [...(await getAllPlayerSkills())];
  players.sort((a, b) => {
    if (category === "overall") {
      return b.totalLevel - a.totalLevel || b.totalXp - a.totalXp;
    }
    if (isCombatCategory(category)) {
      return b.pvp[category] - a.pvp[category];
    }
    return b.skills[category].xp - a.skills[category].xp;
  });
  return players;
}

export interface RankedPlayer extends PlayerSkills {
  rank: number;
}

export interface HiscorePage {
  rows: RankedPlayer[];
  total: number;
  page: number;
  pageCount: number;
  pageSize: number;
}

/** One ranked, paginated page of the leaderboard. */
export async function getHiscoresPage(
  category: HiscoreCategory,
  page = 1,
  pageSize = 25,
): Promise<HiscorePage> {
  const ranked: RankedPlayer[] = (await getHiscores(category)).map((p, i) => ({ ...p, rank: i + 1 }));
  const total = ranked.length;
  const pageCount = Math.max(1, Math.ceil(total / pageSize));
  const safePage = Math.min(Math.max(1, Math.floor(page) || 1), pageCount);
  const start = (safePage - 1) * pageSize;
  return { rows: ranked.slice(start, start + pageSize), total, page: safePage, pageCount, pageSize };
}

export async function getPlayerSkills(loginUsername: string): Promise<PlayerSkills | null> {
  const players = await getAllPlayerSkills();
  const key = loginUsername.toLowerCase();
  return players.find((p) => p.loginUsername.toLowerCase() === key) ?? null;
}

export function titleCase(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}
