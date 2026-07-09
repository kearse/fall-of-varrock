import { ChannelType } from "discord.js";

/**
 * The server structure that `/setup` builds. This is the code form of
 * SERVER_LAYOUT.md. Editing here and re-running /setup is idempotent: existing
 * roles/categories/channels (matched by name) are reused, missing ones created.
 *
 * Access tiers per channel:
 *   "public" — @everyone can read
 *   "linked" — only the "linked" role (+ staff/bot) can read
 *   "staff"  — only staff roles (+ bot) can read
 */
export type AccessTier = "public" | "linked" | "staff";

export interface ChannelDef {
  name: string;
  type: "text" | "voice";
  tier: AccessTier;
  /** read-only for non-staff (no Send Messages) — e.g. announcement feeds. */
  readOnly?: boolean;
  /** if set, the bot prints "feed KEY -> #channel (id)" after setup. */
  feedKey?: keyof typeof FEED_ENV;
  /** if set, /setup posts the "Open a ticket" button panel in this channel. */
  ticketPanel?: boolean;
}

export interface CategoryDef {
  name: string;
  tier: AccessTier;
  channels: ChannelDef[];
}

/** Feed key -> the env var that should hold the resulting channel id. */
export const FEED_ENV = {
  NEWS: "DISCORD_NEWS_CHANNEL_ID",
  STATUS: "DISCORD_FEED_STATUS_ID",
  ACHIEVEMENTS: "DISCORD_FEED_ACHIEVEMENTS_ID",
  DROPS: "DISCORD_FEED_DROPS_ID",
  PK: "DISCORD_FEED_PK_ID",
  BOSS: "DISCORD_FEED_BOSS_ID",
  MODLOG: "DISCORD_FEED_MODLOG_ID",
} as const;

export const t = ChannelType; // re-export convenience

export const LAYOUT: CategoryDef[] = [
  {
    name: "📢 INFORMATION",
    tier: "public",
    channels: [
      { name: "welcome", type: "text", tier: "public", readOnly: true },
      { name: "rules", type: "text", tier: "public", readOnly: true },
      { name: "download", type: "text", tier: "public", readOnly: true },
      { name: "guides", type: "text", tier: "public", readOnly: true },
      { name: "server-status", type: "text", tier: "public", readOnly: true },
      { name: "roles", type: "text", tier: "public", readOnly: true },
      { name: "announcements", type: "text", tier: "public", readOnly: true, feedKey: "NEWS" },
      { name: "updates", type: "text", tier: "public", readOnly: true, feedKey: "STATUS" },
      { name: "how-to-play", type: "text", tier: "public", readOnly: true },
    ],
  },
  {
    name: "💬 COMMUNITY",
    tier: "public",
    channels: [
      { name: "general", type: "text", tier: "public" },
      { name: "introductions", type: "text", tier: "public" },
      { name: "off-topic", type: "text", tier: "public" },
      { name: "media", type: "text", tier: "public" },
      { name: "memes", type: "text", tier: "public" },
      { name: "polls", type: "text", tier: "public" },
      { name: "community-events", type: "text", tier: "public" },
    ],
  },
  {
    name: "🎉 GIVEAWAYS",
    tier: "public",
    channels: [
      { name: "enter-giveaway", type: "text", tier: "public" },
      { name: "daily-winners", type: "text", tier: "public", readOnly: true },
      { name: "weekly-winners", type: "text", tier: "public", readOnly: true },
      { name: "check-eligibility", type: "text", tier: "public" },
    ],
  },
  {
    name: "⚔️ GAME",
    tier: "linked",
    channels: [
      { name: "game-chat", type: "text", tier: "linked" },
      { name: "hiscores", type: "text", tier: "linked", readOnly: true },
      { name: "achievements", type: "text", tier: "linked", readOnly: true, feedKey: "ACHIEVEMENTS" },
      { name: "drops-showcase", type: "text", tier: "linked", readOnly: true, feedKey: "DROPS" },
      { name: "pk-highlights", type: "text", tier: "linked", readOnly: true, feedKey: "PK" },
      { name: "boss-and-war", type: "text", tier: "linked", readOnly: true, feedKey: "BOSS" },
      { name: "goals-and-grinds", type: "text", tier: "linked" },
    ],
  },
  {
    name: "🏪 SUPPORT & STORE",
    tier: "public",
    channels: [
      { name: "store", type: "text", tier: "public", readOnly: true },
      { name: "donations", type: "text", tier: "public", readOnly: true },
      { name: "market", type: "text", tier: "public" },
      { name: "support-ticket", type: "text", tier: "public", readOnly: true, ticketPanel: true },
      { name: "bug-reports", type: "text", tier: "public" },
      { name: "suggestions", type: "text", tier: "public" },
    ],
  },
  {
    name: "🔗 ACCOUNT",
    tier: "public",
    channels: [
      { name: "link-account", type: "text", tier: "public" },
      { name: "bot-commands", type: "text", tier: "linked" },
    ],
  },
  {
    name: "🔊 VOICE",
    tier: "public",
    channels: [
      { name: "General", type: "voice", tier: "public" },
      { name: "PKing", type: "voice", tier: "linked" },
      { name: "Staking / 1v1", type: "voice", tier: "linked" },
      { name: "Skilling", type: "voice", tier: "linked" },
      { name: "Bossing / PVM", type: "voice", tier: "linked" },
      { name: "AFK", type: "voice", tier: "public" },
    ],
  },
  {
    name: "🛡️ STAFF",
    tier: "staff",
    channels: [
      { name: "staff-chat", type: "text", tier: "staff" },
      { name: "staff-commands", type: "text", tier: "staff" },
      { name: "mod-log", type: "text", tier: "staff", readOnly: false, feedKey: "MODLOG" },
    ],
  },
];
