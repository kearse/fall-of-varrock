import { ChannelType, Guild, Role, TextChannel } from "discord.js";

/**
 * Canonical names used by content seeding, live boards, giveaways and
 * engagement. These must match the names created by /setup (src/setup/layout.ts).
 * Looking channels up by name keeps the feature config env-free.
 */
export const CH = {
  welcome: "welcome",
  rules: "rules",
  download: "download",
  howToPlay: "how-to-play",
  guides: "guides",
  store: "store",
  donations: "donations",
  linkAccount: "link-account",
  botCommands: "bot-commands",
  market: "market",
  bugReports: "bug-reports",
  suggestions: "suggestions",
  serverStatus: "server-status",
  hiscores: "hiscores",
  roles: "roles",
  enterGiveaway: "enter-giveaway",
  dailyWinners: "daily-winners",
  weeklyWinners: "weekly-winners",
  checkEligibility: "check-eligibility",
} as const;

/** Self-assignable notification roles (created by /setup, toggled in #roles). */
export const SELF_ROLES: { key: string; name: string; emoji: string; color: number }[] = [
  { key: "updates", name: "🔔 Updates", emoji: "🔔", color: 0x3498db },
  { key: "giveaways", name: "🎉 Giveaway Pings", emoji: "🎉", color: 0x2ecc71 },
  { key: "pvp", name: "⚔️ PvP Pings", emoji: "⚔️", color: 0xe74c3c },
  { key: "pvm", name: "🐲 PvM Pings", emoji: "🐲", color: 0xe67e22 },
];

/** Find a text channel by (lowercased) name, or null. */
export function findText(guild: Guild, name: string): TextChannel | null {
  const lower = name.toLowerCase();
  const ch = guild.channels.cache.find(
    (c) => c.type === ChannelType.GuildText && c.name.toLowerCase() === lower,
  );
  return (ch as TextChannel) ?? null;
}

export function findRoleByName(guild: Guild, name: string): Role | null {
  return guild.roles.cache.find((r) => r.name === name) ?? null;
}
