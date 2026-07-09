import { Client, EmbedBuilder, TextChannel } from "discord.js";
import { config } from "../config.js";
import { Collections } from "../db.js";

/**
 * Posts website news to #announcements. Mirrors the schema flag the web app
 * sets: a NewsDoc with published=true and discordPosted=false is picked up,
 * posted, then flagged discordPosted=true so it's sent once.
 */

const CATEGORY_COLOR: Record<string, number> = {
  update: 0x3498db,
  announcement: 0xf1c40f,
  patch: 0x2ecc71,
  event: 0xe67e22,
};

function excerpt(markdown: string, max = 600): string {
  const text = markdown.replace(/[#>*_`]/g, "").trim();
  return text.length > max ? text.slice(0, max).trimEnd() + "…" : text;
}

let running = false;

export async function pollNews(client: Client): Promise<void> {
  if (running) return;
  running = true;
  try {
    if (!config.channels.news) return; // news channel not configured
    const news = await Collections.news();
    const batch = await news
      // pushToDiscord !== false → legacy docs (field absent) still post.
      .find({ published: true, discordPosted: { $ne: true }, pushToDiscord: { $ne: false } })
      .sort({ publishedAt: 1 })
      .limit(10)
      .toArray();
    if (!batch.length) return;

    const channel = await client.channels.fetch(config.channels.news).catch(() => null);
    if (!channel || !channel.isTextBased()) return;

    for (const post of batch) {
      const embed = new EmbedBuilder()
        .setColor(CATEGORY_COLOR[post.category] ?? 0x5865f2)
        .setTitle(post.title)
        .setURL(`${config.siteUrl}/news/${post.slug}`)
        .setDescription(excerpt(post.body))
        .setAuthor({ name: post.authorName })
        .setFooter({ text: `Kingdom of Lumbridge • ${post.category}` })
        .setTimestamp(post.publishedAt ? new Date(post.publishedAt) : new Date());

      await (channel as TextChannel).send({ embeds: [embed] }).catch((e) =>
        console.error("[newsFeed] send failed:", e?.message ?? e),
      );
      await news.updateOne({ _id: post._id }, { $set: { discordPosted: true } });
    }
  } catch (e) {
    console.error("[newsFeed] poll error:", e);
  } finally {
    running = false;
  }
}

export function startNewsFeed(client: Client): void {
  setInterval(() => void pollNews(client), config.feedPollMs);
  console.log(`[newsFeed] polling news every ${config.feedPollMs}ms`);
}
