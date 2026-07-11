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
  // Direct client download links for the #download buttons. Empty = button
  // hidden (the "All downloads" button to SITE_URL/play always shows). Accepts
  // the website's CLIENT_*_URL names as a fallback so one .env can feed both.
  downloads: {
    windows: opt("DOWNLOAD_WINDOWS", opt("CLIENT_WINDOWS_URL")),
    mac: opt("DOWNLOAD_MAC", opt("CLIENT_MAC_URL")),
    linux: opt("DOWNLOAD_LINUX", opt("CLIENT_LINUX_URL")),
    jar: opt("DOWNLOAD_JAR", opt("CLIENT_JAR_URL")),
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
    case "update":
      // Deploy/update announcements belong with the news, not the status feed.
      return config.channels.news || config.channels.status;
    case "status":
    case "boot":
    case "shutdown":
      return config.channels.status;
    default:
      return config.channels.status;
  }
}
