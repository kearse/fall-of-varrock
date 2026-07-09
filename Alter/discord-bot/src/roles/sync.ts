import { Guild, GuildMember, Role } from "discord.js";
import { Collections } from "../db.js";
import { normalizeLogin } from "../util.js";
import { MANAGED_ROLES, SYNCED_ROLE_KEYS } from "./roleMap.js";

/**
 * Reconciles a Discord member's roles against their linked game account:
 *  - donor/member/staff roles are ADDED when the account qualifies, REMOVED when
 *    it no longer does (so a refund/expiry strips the role on the next sync).
 *  - "linked"/"player" are granted once at link time and only removed on unlink.
 */

let roleCache = new Map<string, Role>(); // key -> Role, per guild run

function buildRoleCache(guild: Guild) {
  roleCache = new Map();
  for (const def of MANAGED_ROLES) {
    const role = guild.roles.cache.find((r) => r.name === def.name);
    if (role) roleCache.set(def.key, role);
  }
}

export async function syncMemberRoles(
  member: GuildMember,
  loginUsername: string | null,
  opts: { grantBaseRoles?: boolean; revokeAll?: boolean } = {},
): Promise<void> {
  buildRoleCache(member.guild);

  if (opts.revokeAll || !loginUsername) {
    const toRemove = MANAGED_ROLES.map((d) => roleCache.get(d.key))
      .filter((r): r is Role => !!r && member.roles.cache.has(r.id));
    if (toRemove.length) await member.roles.remove(toRemove).catch(() => {});
    return;
  }

  const accounts = await Collections.accounts();
  const account = await accounts.findOne({ loginUsername: normalizeLogin(loginUsername) });
  if (!account) return;

  const add: Role[] = [];
  const remove: Role[] = [];

  for (const def of MANAGED_ROLES) {
    const role = roleCache.get(def.key);
    if (!role) continue;
    const isBase = def.key === "linked" || def.key === "player";
    const has = member.roles.cache.has(role.id);

    if (isBase) {
      if (opts.grantBaseRoles && !has) add.push(role);
      continue; // base roles are never auto-removed here
    }
    if (!SYNCED_ROLE_KEYS.includes(def.key)) continue;

    const should = def.applies(account);
    if (should && !has) add.push(role);
    else if (!should && has) remove.push(role);
  }

  if (add.length) await member.roles.add(add).catch(() => {});
  if (remove.length) await member.roles.remove(remove).catch(() => {});
}

/**
 * Sweeps every linked member in the guild and reconciles their roles. Called on
 * an interval and by `/sync`. Cheap for a community-sized server.
 */
export async function syncAllRoles(guild: Guild): Promise<{ checked: number; updated: number }> {
  const links = await Collections.discordLinks();
  const linked = await links.find({ discordUserId: { $exists: true } }).toArray();

  let updated = 0;
  for (const link of linked) {
    const member = await guild.members.fetch(link.discordUserId!).catch(() => null);
    if (!member) continue;
    const before = member.roles.cache.size;
    await syncMemberRoles(member, link.loginUsername, { grantBaseRoles: true });
    if (member.roles.cache.size !== before) updated++;
  }
  return { checked: linked.length, updated };
}
