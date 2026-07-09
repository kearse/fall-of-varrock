import "dotenv/config";

function req(name: string): string {
  const v = process.env[name];
  if (!v || !v.trim()) throw new Error(`Missing required env var: ${name}`);
  return v.trim();
}

function opt(name: string, fallback = ""): string {
  return (process.env[name] ?? fallback).trim();
}

function num(name: string, fallback: number): number {
  const v = process.env[name];
  const n = v ? Number(v) : NaN;
  return Number.isFinite(n) ? n : fallback;
}

export const config = {
  mongoUri: opt("MONGODB_URI", "mongodb://localhost:27017"),
  mongoDb: opt("MONGODB_DB", "lumbridge"),

  token: req("DISCORD_BOT_TOKEN"),
  clientId: req("DISCORD_CLIENT_ID"),
  guildId: req("DISCORD_GUILD_ID"),

  // Feed channel ids — empty string means "feed disabled / not configured yet".
  channels: {
    news: opt("DISCORD_NEWS_CHANNEL_ID"),
    status: opt("DISCORD_FEED_STATUS_ID"),
    achievements: opt("DISCORD_FEED_ACHIEVEMENTS_ID"),
    drops: opt("DISCORD_FEED_DROPS_ID"),
    pk: opt("DISCORD_FEED_PK_ID"),
    boss: opt("DISCORD_FEED_BOSS_ID"),
    modlog: opt("DISCORD_FEED_MODLOG_ID"),
  },

  feedPollMs: num("FEED_POLL_MS", 4000),
  roleSyncMs: num("ROLE_SYNC_MS", 300_000),
  siteUrl: opt("SITE_URL", "http://localhost:3000"),

  // Live status board: TCP-probe the game world to show UP/DOWN (no game code needed).
  gameHost: opt("GAME_HOST", "localhost"),
  gamePort: num("GAME_PORT", 43594),
  liveBoardMs: num("LIVE_BOARD_MS", 60_000),
  // Download links shown in #download / #welcome (edit to your real URLs).
  downloads: {
    windows: opt("DOWNLOAD_WINDOWS", ""),
    mac: opt("DOWNLOAD_MAC", ""),
    jar: opt("DOWNLOAD_JAR", ""),
  },
} as const;

/** Maps an in-game event "kind" to the configured channel id (or "" if unset). */
export function channelForEventKind(kind: string): string {
  switch (kind) {
    case "achievement":
    case "level99":
    case "levelMilestone":
      return config.channels.achievements;
    case "drop":
      return config.channels.drops;
    case "pk":
      return config.channels.pk;
    case "boss":
    case "raid":
    case "war":
      return config.channels.boss;
    case "status":
    case "boot":
    case "shutdown":
      return config.channels.status;
    default:
      return config.channels.status;
  }
}
