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

export const VOTE_SITES: VoteSite[] = [
  // Example entries - replace with your real toplist callbacks when ready.
  // { id: "runelocus", name: "RuneLocus", url: "https://www.runelocus.com/vote/...?username={username}", reward: "1 vote point", icon: "🗡️", rewardPoints: 1 },
  // { id: "top100", name: "Top100Arena", url: "https://www.top100arena.com/...?u={username}", reward: "1 vote point", icon: "🏆", rewardPoints: 1, cooldownHours: 12 },
];

export function voteUrl(site: VoteSite, username: string): string {
  return site.url.replace(/\{username\}/g, encodeURIComponent(username.trim()));
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
