import { Collections } from "./collections";
import { normalizeLogin } from "./accounts";
import {
  DEFAULT_COOLDOWN_HOURS,
  DEFAULT_VOTE_POINTS,
  VOTE_POINTS_SETTING_KEY,
  VOTE_SITES,
} from "./vote";

/**
 * Server side of toplist vote postbacks (server-to-server callbacks the
 * toplists fire when a vote is confirmed). One adapter per toplist translates
 * that site's callback params into a normalized shape; `creditVote` then does
 * the shared work: cooldown guard, vote record, `vote_points` entitlement.
 *
 * Adding a toplist = one entry in VOTE_SITES (lib/vote.ts, drives the vote
 * page) + one adapter here + its secret in the environment. The callback URL
 * to paste into a toplist's dashboard is always:
 *   https://fallofvarrock.com/api/vote/postback/<adapter-id>
 */
export interface PostbackAdapter {
  /** VOTE_SITES id — also the last path segment of the callback URL. */
  siteId: string;
  /** Env var holding this site's API secret (from their dashboard). */
  secretEnv: string;
  /** Whether the site's callback tester sends the literal secret "TEST". */
  allowTestSecret?: boolean;
  /** Pull this site's callback params into a normalized result. */
  parse(params: URLSearchParams): {
    secret: string | null;
    voted: boolean;
    userid: string | null;
  };
}

export const POSTBACK_ADAPTERS: Record<string, PostbackAdapter> = {
  // rsps-list.com "Incentive Voting": sends secret / voted (1|0) / userid,
  // where userid is the id= value from our vote link (the player's login name).
  "rsps-list": {
    siteId: "rsps-list",
    secretEnv: "RSPS_LIST_SECRET",
    allowTestSecret: true,
    parse: (p) => ({
      secret: p.get("secret"),
      voted: p.get("voted") === "1",
      userid: p.get("userid"),
    }),
  },

  // rulocus.com: fires only on a successful vote, sending callback (the value
  // from our vote link's callback= param, i.e. the player's login name) + ip.
  // They send NO secret of their own, so ours rides in the callback URL we
  // register with them: .../api/vote/postback/rulocus?key=<RULOCUS_SECRET>.
  rulocus: {
    siteId: "rulocus",
    secretEnv: "RULOCUS_SECRET",
    allowTestSecret: true,
    parse: (p) => ({
      secret: p.get("key"),
      voted: true,
      userid: p.get("callback"),
    }),
  },

  // topg.org "Voting Check": fires only on a successful vote, sending p_resp
  // (the value appended to our vote link — the player's login name) + ip.
  // No secret of their own either — ours rides in the registered URL:
  // .../api/vote/postback/topg?key=<TOPG_SECRET>.
  topg: {
    siteId: "topg",
    secretEnv: "TOPG_SECRET",
    allowTestSecret: true,
    parse: (p) => ({
      secret: p.get("key"),
      voted: true,
      userid: p.get("p_resp"),
    }),
  },
};

export function postbackSecretOk(adapter: PostbackAdapter, secret: string | null): boolean {
  const expected = process.env[adapter.secretEnv];
  if (expected && secret === expected) return true;
  // Callback testers send a literal "TEST" secret; honour it only outside
  // production so a missing env secret can never be exploited live.
  if (adapter.allowTestSecret && secret === "TEST" && process.env.NODE_ENV !== "production") {
    return true;
  }
  return false;
}

async function pointsPerVote(): Promise<number> {
  const settings = await Collections.settings();
  const doc = await settings.findOne({ key: VOTE_POINTS_SETTING_KEY });
  const n = typeof doc?.value === "number" ? Math.floor(doc.value) : NaN;
  return Number.isFinite(n) && n >= 1 ? n : DEFAULT_VOTE_POINTS;
}

export type CreditResult =
  | { credited: true; points: number }
  | { credited: false; reason: "unknown account" | "cooldown" };

/** Record a confirmed vote and queue its points for in-game delivery. */
export async function creditVote(siteId: string, useridRaw: string): Promise<CreditResult> {
  const loginUsername = normalizeLogin(useridRaw);
  const accounts = await Collections.accounts();
  const account = await accounts.findOne({ loginUsername });
  if (!account) return { credited: false, reason: "unknown account" };

  // Our own cooldown guard on top of the toplist's: one credit per site per window.
  const site = VOTE_SITES.find((s) => s.id === siteId);
  const cooldownMs = (site?.cooldownHours ?? DEFAULT_COOLDOWN_HOURS) * 3_600_000;
  const now = Date.now();
  const votes = await Collections.votes();
  const recent = await votes.findOne({
    loginUsername,
    site: siteId,
    votedAt: { $gt: now - cooldownMs },
  });
  if (recent) return { credited: false, reason: "cooldown" };

  const points = await pointsPerVote();
  await votes.insertOne({ loginUsername, site: siteId, points, votedAt: now });

  const entitlements = await Collections.entitlements();
  await entitlements.insertOne({
    loginUsername,
    kind: "vote_points",
    payload: { points },
    orderId: `vote-${siteId}-${loginUsername}-${now}`,
    applied: false,
    createdAt: now,
  });

  return { credited: true, points };
}
