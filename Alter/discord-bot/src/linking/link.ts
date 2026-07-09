import { GuildMember } from "discord.js";
import { Collections } from "../db.js";
import { syncMemberRoles } from "../roles/sync.js";

/**
 * Account linking via one-time code.
 *
 * Flow:
 *  1. Logged-in user on the website clicks "Link Discord" → web inserts/updates a
 *     discord_links doc { loginUsername, code, createdAt } (no discordUserId yet).
 *     (Web route: web/src/app/api/discord/link/route.ts)
 *  2. User runs `/link <code>` in Discord. We match an unclaimed doc by code,
 *     stamp discordUserId/tag, clear the code, then grant + sync roles.
 */

export interface LinkResult {
  ok: boolean;
  message: string;
  loginUsername?: string;
}

export async function linkByCode(member: GuildMember, code: string): Promise<LinkResult> {
  const links = await Collections.discordLinks();
  const clean = code.trim().toUpperCase();

  // Already linked? Don't let one Discord account claim two game accounts.
  const existing = await links.findOne({ discordUserId: member.id });
  if (existing) {
    return {
      ok: false,
      message: `You're already linked to **${existing.loginUsername}**. Use \`/unlink\` first if you want to switch.`,
    };
  }

  const pending = await links.findOne({ code: clean, discordUserId: { $exists: false } });
  if (!pending) {
    return { ok: false, message: "That code is invalid or already used. Generate a fresh one on the website." };
  }

  // Make sure this game account isn't linked to someone else's Discord.
  const claimedByOther = await links.findOne({
    loginUsername: pending.loginUsername,
    discordUserId: { $exists: true },
  });
  if (claimedByOther) {
    return { ok: false, message: "That game account is already linked to a different Discord user." };
  }

  await links.updateOne(
    { _id: pending._id },
    {
      $set: {
        discordUserId: member.id,
        discordTag: member.user.tag,
        linkedAt: Date.now(),
      },
      $unset: { code: "" },
    },
  );

  await syncMemberRoles(member, pending.loginUsername, { grantBaseRoles: true });
  return { ok: true, message: `Linked to **${pending.loginUsername}** 🎉  Roles synced.`, loginUsername: pending.loginUsername };
}

export async function unlink(member: GuildMember): Promise<LinkResult> {
  const links = await Collections.discordLinks();
  const doc = await links.findOne({ discordUserId: member.id });
  if (!doc) return { ok: false, message: "You aren't linked to any account." };

  await links.updateOne({ _id: doc._id }, { $unset: { discordUserId: "", discordTag: "", linkedAt: "" } });
  await syncMemberRoles(member, null, { revokeAll: true });
  return { ok: true, message: `Unlinked from **${doc.loginUsername}**. Your synced roles were removed.` };
}

export async function whoami(member: GuildMember): Promise<{ loginUsername: string } | null> {
  const links = await Collections.discordLinks();
  const doc = await links.findOne({ discordUserId: member.id });
  return doc?.loginUsername ? { loginUsername: doc.loginUsername } : null;
}
