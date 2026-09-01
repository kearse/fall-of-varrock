import { Collections } from "./db.js";
import { normalizeLogin } from "./util.js";

/**
 * Reads player skill data straight from the shared save. The game serialises
 * skills (game-server .../saving/impl/SkillSerialisation.kt) as:
 *   details.attributes.skills.<skillname> = { id, level, xp }
 */

export interface SkillStat {
  name: string;
  level: number;
  xp: number;
}

// The 23 real skills, in game id order — mirrors web/src/lib/hiscores.ts. Older saves
// also carry a junk "skill" key (the server's unnamed slots 23/24); iterating known
// skills instead of Object.entries keeps it out of totals so the Discord board and the
// website agree.
const SKILLS = [
  "attack", "defence", "strength", "hitpoints", "ranged", "prayer", "magic",
  "cooking", "woodcutting", "fletching", "fishing", "firemaking", "crafting",
  "smithing", "mining", "herblore", "agility", "thieving", "slayer", "farming",
  "runecraft", "hunter", "construction",
] as const;

// Standard OSRS XP table (levels 1..99) — levels are DERIVED from xp, like the website,
// because the stored `level` field is the boosted/drained current level, not the base.
const XP_FOR_LEVEL: number[] = (() => {
  const table = [0, 0];
  let points = 0;
  for (let level = 1; level < 99; level++) {
    points += Math.floor(level + 300 * Math.pow(2, level / 7));
    table[level + 1] = Math.floor(points / 4);
  }
  return table;
})();

function xpToLevel(xp: number): number {
  for (let l = 99; l >= 1; l--) {
    if (xp >= XP_FOR_LEVEL[l]) return l;
  }
  return 1;
}

/** Base-level skill map from a save's skills doc — known skills only, levels from xp. */
function parseSkillDoc(skillDoc: Record<string, any> | undefined): Map<string, SkillStat> {
  const map = new Map<string, SkillStat>();
  if (!skillDoc) return map;
  for (const name of SKILLS) {
    const xp = Math.max(0, Math.floor(Number(skillDoc[name]?.xp ?? 0)));
    const level = name === "hitpoints" ? Math.max(10, xpToLevel(xp)) : xpToLevel(xp);
    map.set(name, { name, level, xp });
  }
  return map;
}

export interface PlayerProfile {
  loginUsername: string;
  displayName: string;
  roles: string[];
  membershipTier: string | null;
  donorPoints: number;
  skills: SkillStat[];
  totalLevel: number;
  totalXp: number;
  combatLevel: number;
  found: boolean;
  hasSkills: boolean;
}

const lvl = (s: SkillStat | undefined) => s?.level ?? 1;

/** Standard OSRS combat level formula. */
function combatLevel(skills: Map<string, SkillStat>): number {
  const g = (n: string) => lvl(skills.get(n));
  const base = 0.25 * (g("defence") + g("hitpoints") + Math.floor(g("prayer") / 2));
  const melee = 0.325 * (g("attack") + g("strength"));
  const range = 0.325 * (Math.floor(g("ranged") * 3) / 2);
  const mage = 0.325 * (Math.floor(g("magic") * 3) / 2);
  return Math.floor(base + Math.max(melee, range, mage));
}

export async function getProfile(nameInput: string): Promise<PlayerProfile> {
  const key = normalizeLogin(nameInput);
  const accounts = await Collections.accounts();
  const account = await accounts.findOne({ loginUsername: key });

  const empty: PlayerProfile = {
    loginUsername: key,
    displayName: account?.currentDisplayName ?? nameInput,
    roles: account?.roles ?? [],
    membershipTier: account?.membership?.tier ?? null,
    donorPoints: account?.donorPoints ?? 0,
    skills: [],
    totalLevel: 0,
    totalXp: 0,
    combatLevel: 3,
    found: account != null,
    hasSkills: false,
  };
  if (!account) return empty;

  const details = await Collections.details();
  const detail = await details.findOne({ loginUsername: key });
  const skillDoc = (detail?.attributes as any)?.skills as Record<string, any> | undefined;
  if (!skillDoc) return empty;

  const map = parseSkillDoc(skillDoc);
  const skills = [...map.values()];

  return {
    ...empty,
    skills,
    totalLevel: skills.reduce((a, s) => a + s.level, 0),
    totalXp: Math.floor(skills.reduce((a, s) => a + s.xp, 0)),
    combatLevel: combatLevel(map),
    hasSkills: skills.length > 0,
  };
}

/** Top-N players by total level (reads all accounts; fine for a small server). */
export async function topByTotalLevel(limit = 10): Promise<{ displayName: string; totalLevel: number; combat: number }[]> {
  const details = await Collections.details();
  const all = await details.find({}, { projection: { loginUsername: 1, attributes: 1 } }).toArray();
  const rows = all.map((d) => {
    const map = parseSkillDoc((d.attributes as any)?.skills as Record<string, any> | undefined);
    let total = 0;
    for (const s of map.values()) total += s.level;
    return { login: d.loginUsername as string, totalLevel: total, combat: combatLevel(map) };
  });
  rows.sort((a, b) => b.totalLevel - a.totalLevel);

  // Resolve display names for the top rows only.
  const accounts = await Collections.accounts();
  const top = rows.slice(0, limit);
  const out: { displayName: string; totalLevel: number; combat: number }[] = [];
  for (const r of top) {
    const acc = await accounts.findOne({ loginUsername: r.login }, { projection: { currentDisplayName: 1 } });
    out.push({ displayName: acc?.currentDisplayName ?? r.login, totalLevel: r.totalLevel, combat: r.combat });
  }
  return out;
}
