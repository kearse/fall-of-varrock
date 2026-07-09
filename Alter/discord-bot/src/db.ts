import { Collection, Db, Document, MongoClient } from "mongodb";
import { config } from "./config.js";

/**
 * Shared MongoDB access — the same database the website (web/src/lib/db.ts) and
 * the Kotlin game server use. Field names here MUST match
 * web/src/lib/collections.ts; see that file for the canonical contract.
 */

let clientPromise: Promise<MongoClient> | undefined;

function getClient(): Promise<MongoClient> {
  if (!clientPromise) {
    clientPromise = new MongoClient(config.mongoUri, { maxPoolSize: 5 }).connect();
  }
  return clientPromise;
}

export async function getDb(): Promise<Db> {
  return (await getClient()).db(config.mongoDb);
}

export async function closeDb(): Promise<void> {
  if (clientPromise) await (await clientPromise).close();
  clientPromise = undefined;
}

// ---- Document shapes (subset of the shared schema we read/write) ----

export interface AccountDoc extends Document {
  loginUsername: string;
  currentDisplayName: string;
  passwordHash: string;
  roles: string[];
  donorPoints: number;
  membership: { tier: string | null; expires: number | null };
}

export interface DetailsDoc extends Document {
  loginUsername: string;
  attributes?: Document;
}

export interface DiscordLinkDoc extends Document {
  loginUsername: string;
  discordUserId?: string;
  discordTag?: string;
  code?: string;
  linkedAt?: number;
  createdAt: number;
}

/**
 * discord_events: the game→Discord queue. The Kotlin DiscordBridge inserts a
 * doc per in-game event; eventFeed.ts polls for unposted ones, posts an embed,
 * then marks `posted: true`. Mirrors the news `discordPosted` pattern.
 */
export interface DiscordEventDoc extends Document {
  kind: string; // "level99" | "drop" | "pk" | "boss" | "war" | "status" | ...
  title: string;
  description?: string;
  player?: string; // display name, if player-scoped
  fields?: { name: string; value: string; inline?: boolean }[];
  thumbnailItemId?: number; // optional: a game item id for an icon
  posted: boolean;
  createdAt: number;
  postedAt?: number;
}

/**
 * online: lightweight presence the game maintains (one doc per online player).
 * Inserted on login, removed on logout, wiped on boot. Read by /online.
 */
export interface OnlineDoc extends Document {
  loginUsername: string;
  displayName: string;
  since: number;
}

/** giveaways: owned by the bot. */
export interface GiveawayDoc extends Document {
  prize: string;
  hostId: string; // discord user id of the host
  channelId: string; // #enter-giveaway message location
  messageId: string;
  winnersCount: number;
  endsAt: number;
  entrants: string[]; // discord user ids
  ended: boolean;
  winners?: string[]; // discord user ids, set when drawn
  announce: "daily" | "weekly"; // which winners channel to post to
  createdAt: number;
}

export interface NewsDoc extends Document {
  slug: string;
  title: string;
  body: string;
  authorName: string;
  category: string;
  published: boolean;
  pushToDiscord?: boolean; // dev opt-in; absent on legacy docs = treated as true
  discordPosted: boolean;
  publishedAt?: number;
}

async function col<T extends Document>(name: string): Promise<Collection<T>> {
  return (await getDb()).collection<T>(name);
}

export const Collections = {
  accounts: () => col<AccountDoc>("accounts"),
  details: () => col<DetailsDoc>("details"),
  discordLinks: () => col<DiscordLinkDoc>("discord_links"),
  discordEvents: () => col<DiscordEventDoc>("discord_events"),
  online: () => col<OnlineDoc>("online"),
  news: () => col<NewsDoc>("news"),
  giveaways: () => col<GiveawayDoc>("giveaways"),
};
