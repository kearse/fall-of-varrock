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

  const map = new Map<string, SkillStat>();
  for (const [name, raw] of Object.entries(skillDoc)) {
    const level = Number(raw?.level ?? 1);
    const xp = Number(raw?.xp ?? 0);
    map.set(name.toLowerCase(), { name: name.toLowerCase(), level, xp });
  }
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
    const skillDoc = (d.attributes as any)?.skills as Record<string, any> | undefined;
    const map = new Map<string, SkillStat>();
    let total = 0;
    if (skillDoc) {
      for (const [name, raw] of Object.entries(skillDoc)) {
        const level = Number(raw?.level ?? 1);
        map.set(name.toLowerCase(), { name, level, xp: 0 });
        total += level;
      }
    }
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
