import { Collection, Document } from "mongodb";
import { getDb } from "./db";

/**
 * Shared MongoDB schema - the contract between the web app and the Kotlin game
 * server. The game writes/reads `accounts` and `details`; the web app owns the
 * rest. Keep field names in sync with the Phase 0 game-server refactor.
 */

// ---- accounts: credentials + web/meta. Keyed by lowercased loginUsername. ----
// The game's DisplayName decoder reads currentDisplayName/previousDisplayName/
// dateChanged from this same doc, so those fields must stay present.
export interface AccountDoc extends Document {
  loginUsername: string; // lowercase, primary key
  currentDisplayName: string; // shown in-game/on-site
  previousDisplayName: string;
  dateChanged: number; // -1 if never changed
  passwordHash: string; // bcrypt $2a$ - verifiable by jBCrypt in-game
  email: string | null;
  roles: string[]; // e.g. ["player"], ["player","moderator","admin"]
  donorPoints: number;
  membership: { tier: string | null; expires: number | null };
  createdAt: number;
  source: "web" | "game";
}

// ---- details: the heavy game save blob (owned by the game server). ----
// Declared loosely here; the web app only reads stats from it for hiscores.
export interface DetailsDoc extends Document {
  loginUsername: string;
  passwordHash?: string; // legacy location; accounts is now source of truth
  attributes?: Document;
}

// ---- entitlements: purchases waiting to be applied in-game on next login. ----
export interface EntitlementDoc extends Document {
  loginUsername: string;
  kind: "donor_points" | "membership" | "items" | "patron_march" | "vote_points";
  payload: Document; // e.g. { points: 500 } | { tier, days } | { items:[...] }
  orderId: string;
  applied: boolean;
  createdAt: number;
  appliedAt?: number;
}

// ---- votes: confirmed toplist postbacks (audit trail + cooldown guard). ----
// Written by /api/vote/postback/*; the points themselves ride the entitlements
// pipeline (kind "vote_points") so the game delivers them like store purchases.
export interface VoteDoc extends Document {
  loginUsername: string;
  site: string; // toplist id, e.g. "rsps-list"
  points: number; // points credited for this vote
  votedAt: number;
}

// ---- settings: small shared key/value config. Web reads, game admins write
// (e.g. ::setvotepoints). Keyed by `key`. ----
export interface SettingDoc extends Document {
  key: string; // e.g. "votePointsPerVote"
  value: number | string | boolean;
  updatedAt: number;
}

// ---- orders: payment records (audit trail). ----
export interface OrderDoc extends Document {
  orderId: string; // our id
  loginUsername: string;
  provider: "stripe" | "paypal" | "coinbase";
  providerRef: string; // session/checkout id from the provider
  packageId: string;
  amount: number; // minor units (cents)
  currency: string;
  status: "pending" | "paid" | "refunded" | "failed";
  createdAt: number;
  paidAt?: number;
}

// ---- news: dev updates. Source of truth for site News + Discord posts. ----
export interface NewsDoc extends Document {
  slug: string;
  title: string;
  body: string; // markdown
  authorName: string;
  category: "update" | "announcement" | "patch" | "event";
  published: boolean;
  pushToDiscord: boolean; // dev chose to announce this to Discord (default true)
  discordPosted: boolean; // already delivered (by webhook or the bot worker)
  createdAt: number;
  publishedAt?: number;
}

// ---- forum ----
export interface ForumCategoryDoc extends Document {
  slug: string;
  section: string; // grouping header, e.g. "Community", "The War"
  name: string;
  description: string;
  order: number; // sort order within the whole forum
  minRoleToPost: string; // "" = anyone logged in, "staff" = staff only
}

export interface ForumThreadDoc extends Document {
  categorySlug: string;
  title: string;
  authorName: string;
  pinned: boolean;
  locked: boolean;
  replyCount: number;
  lastPostAt: number;
  createdAt: number;
}

export interface ForumPostDoc extends Document {
  threadId: string;
  body: string; // markdown
  authorName: string;
  createdAt: number;
  editedAt?: number;
  deleted?: boolean;
}

export interface GuideDoc extends Document {
  slug: string;
  title: string;
  summary: string;
  body: string; // markdown
  category: string;
  authorName: string;
  published: boolean;
  createdAt: number;
}

// ---- discord_links: one-time-code account linking. ----
export interface DiscordLinkDoc extends Document {
  loginUsername: string;
  discordUserId?: string;
  discordTag?: string;
  code?: string; // pending link code
  linkedAt?: number;
  createdAt: number;
}

// ---- discord_events: game→Discord feed queue. Written by the Kotlin
// DiscordBridge, drained by the discord-bot worker (sets posted=true). ----
export interface DiscordEventDoc extends Document {
  kind: string; // "level99" | "drop" | "pk" | "boss" | "war" | "status" | ...
  title: string;
  description?: string;
  player?: string; // display name, if player-scoped
  fields?: { name: string; value: string; inline?: boolean }[];
  thumbnailItemId?: number;
  posted: boolean;
  createdAt: number;
  postedAt?: number;
}

// ---- online: live presence, maintained by the game (insert on login, remove
// on logout, wiped on boot). Read by the bot's /online command. ----
export interface OnlineDoc extends Document {
  loginUsername: string;
  displayName: string;
  since: number;
}

export const Collections = {
  accounts: () => col<AccountDoc>("accounts"),
  details: () => col<DetailsDoc>("details"),
  entitlements: () => col<EntitlementDoc>("entitlements"),
  votes: () => col<VoteDoc>("votes"),
  settings: () => col<SettingDoc>("settings"),
  orders: () => col<OrderDoc>("orders"),
  news: () => col<NewsDoc>("news"),
  forumCategories: () => col<ForumCategoryDoc>("forum_categories"),
  forumThreads: () => col<ForumThreadDoc>("forum_threads"),
  forumPosts: () => col<ForumPostDoc>("forum_posts"),
  guides: () => col<GuideDoc>("guides"),
  discordLinks: () => col<DiscordLinkDoc>("discord_links"),
  discordEvents: () => col<DiscordEventDoc>("discord_events"),
  online: () => col<OnlineDoc>("online"),
};

async function col<T extends Document>(name: string): Promise<Collection<T>> {
  const db = await getDb();
  return db.collection<T>(name);
}
