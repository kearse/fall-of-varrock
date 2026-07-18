/**
 * Vote site configuration. Functionality (callback verification + reward claiming)
 * comes later - for now this drives the vote page UI. Add your toplist sites here;
 * `{username}` in the URL is replaced with the player's login name.
 */
export interface VoteSite {
  id: string;
  name: string;
  url: string; // may contain {username}
  reward: string; // human description, e.g. "1 vote point"
  /** Emoji shown on the site card. Falls back to a scroll. */
  icon?: string;
  /** Vote points granted per vote. Used for the "+X vote points" badge. */
  rewardPoints?: number;
  /** Hours between votes on this site. Defaults to DEFAULT_COOLDOWN_HOURS. */
  cooldownHours?: number;
}

/** Standard toplist cooldown, used when a site doesn't override it. */
export const DEFAULT_COOLDOWN_HOURS = 12;

/**
 * Settings-collection key for the admin-tunable points-per-vote (game admins set
 * it with ::setvotepoints; the postback route reads it). Card badges below show
 * the default; the credited amount always comes from this setting.
 */
export const VOTE_POINTS_SETTING_KEY = "votePointsPerVote";
export const DEFAULT_VOTE_POINTS = 1;

export const VOTE_SITES: VoteSite[] = [
  {
    id: "rsps-list",
    name: "RSPS-List",
    // `u` is our listing account on rsps-list.com; `id` carries the voter's
    // login name back to us in the postback's `userid` param.
    url: "https://www.rsps-list.com/index.php?a=in&u=BizzyZ&id={username}",
    reward: "1 vote point",
    icon: "🗡️",
    rewardPoints: DEFAULT_VOTE_POINTS,
    cooldownHours: 12,
  },
  {
    id: "rulocus",
    name: "RuLocus",
    // Base URL comes from the RuLocus account's listing page; `callback`
    // carries the voter's login name back in the postback's `callback` param.
    url: "https://www.rulocus.com/top-rsps-list/fall-of-varrock/vote?callback={username}",
    reward: "1 vote point",
    icon: "🛡️",
    rewardPoints: DEFAULT_VOTE_POINTS,
    cooldownHours: 12,
  },
  {
    id: "top100arena",
    name: "Top100Arena",
    // TODO: replace LISTING_ID with the numeric id from the Top100Arena
    // account (the xxx in top100arena.com/listing/xxx/vote). `incentive`
    // carries the voter's login name back in the postback's appended value.
    url: "https://www.top100arena.com/listing/LISTING_ID/vote?incentive={username}",
    reward: "1 vote point",
    icon: "⚔️",
    rewardPoints: DEFAULT_VOTE_POINTS,
    cooldownHours: 12,
  },
  {
    id: "moparscape",
    name: "Moparscape",
    // TODO: confirm the listing slug and how their vote link passes the player
    // name (dashboard's incentive/callback instructions) — ?incentive= is the
    // common convention their adapter also accepts.
    url: "https://www.moparscape.org/rsps-list/server/fall-of-varrock?incentive={username}",
    reward: "1 vote point",
    icon: "🪖",
    rewardPoints: DEFAULT_VOTE_POINTS,
    cooldownHours: 12,
  },
  {
    id: "topg",
    name: "TopG",
    // TopG's "link with username" format: server-<id>-<username>. The username
    // comes back in the postback's `p_resp` param (letters/digits/underscores
    // only — voteUrl() converts spaces to underscores).
    url: "https://topg.org/runescape-private-servers/server-684312-{username}#vote",
    reward: "1 vote point",
    icon: "🏆",
    rewardPoints: DEFAULT_VOTE_POINTS,
    cooldownHours: 12,
  },
];

export function voteUrl(site: VoteSite, username: string): string {
  // Send the login-form name (spaces → underscores): some toplists (TopG) only
  // accept [A-Za-z0-9_], and the postback route normalizes it back regardless.
  const login = username.trim().replace(/ /g, "_");
  return site.url.replace(/\{username\}/g, encodeURIComponent(login));
}

export function siteIcon(site: VoteSite): string {
  return site.icon ?? "📜";
}

export function siteCooldownHours(site: VoteSite): number {
  return site.cooldownHours ?? DEFAULT_COOLDOWN_HOURS;
}

/** "+2 vote points" / "+1 vote point", falling back to the freeform reward text. */
export function siteRewardLabel(site: VoteSite): string {
  if (typeof site.rewardPoints === "number") {
    return `+${site.rewardPoints} vote ${site.rewardPoints === 1 ? "point" : "points"}`;
  }
  return site.reward;
}
