/**
 * Best-effort Discord webhook posting from the web app. The richer integration
 * (gateway bot, account linking, game-event relay, /online) lives in the separate
 * discord-bot worker; this is just the one-way "post an announcement" path so that
 * news published on the website reaches Discord even without the worker running.
 */
const BRAND_COLOR = 0xc9a227; // lumbridge gold

export interface DiscordEmbed {
  title: string;
  description?: string;
  url?: string;
  color?: number;
  fields?: { name: string; value: string; inline?: boolean }[];
  footer?: { text: string };
  timestamp?: string;
}

export async function postWebhook(embed: DiscordEmbed): Promise<boolean> {
  const url = process.env.DISCORD_WEBHOOK_URL;
  if (!url) return false;
  try {
    const res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        username: process.env.NEXT_PUBLIC_SITE_NAME ?? "Fall of Varrock",
        embeds: [{ color: BRAND_COLOR, ...embed }],
      }),
    });
    return res.ok;
  } catch {
    return false;
  }
}

export function newsEmbed(news: { title: string; body: string; slug: string; authorName: string }): DiscordEmbed {
  const site = process.env.NEXT_PUBLIC_SITE_URL;
  const trimmed = news.body.length > 600 ? news.body.slice(0, 600) + "…" : news.body;
  return {
    title: `📢 ${news.title}`,
    description: trimmed || "A new update has been posted.",
    url: site ? `${site}/news/${news.slug}` : undefined,
    footer: { text: `Posted by ${news.authorName}` },
    timestamp: new Date().toISOString(),
  };
}
