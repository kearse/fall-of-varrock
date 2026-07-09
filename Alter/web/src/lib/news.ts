import { Collections, NewsDoc } from "./collections";
import { newsEmbed, postWebhook } from "./discord";

function slugify(s: string): string {
  return (
    s.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 60) || "update"
  );
}

export async function getPublishedNews(limit = 30): Promise<NewsDoc[]> {
  const news = await Collections.news();
  return news.find({ published: true }).sort({ publishedAt: -1, createdAt: -1 }).limit(limit).toArray();
}

export async function getNewsBySlug(slug: string): Promise<NewsDoc | null> {
  const news = await Collections.news();
  return news.findOne({ slug });
}

export async function createNews(input: {
  title: string;
  body: string;
  category: NewsDoc["category"];
  authorName: string;
  pushToDiscord?: boolean; // dev's choice; defaults to true
}): Promise<NewsDoc> {
  const news = await Collections.news();
  const now = Date.now();
  const slug = `${slugify(input.title)}-${now.toString(36)}`;
  const pushToDiscord = input.pushToDiscord ?? true;
  const doc: NewsDoc = {
    slug,
    title: input.title,
    body: input.body,
    authorName: input.authorName,
    category: input.category,
    published: true,
    pushToDiscord,
    discordPosted: false,
    createdAt: now,
    publishedAt: now,
  };
  await news.insertOne(doc);

  // Only announce to Discord if the dev opted in. Best-effort: post now if a
  // webhook is configured; otherwise the discord-bot worker picks it up (it
  // queries pushToDiscord:true + discordPosted:false).
  if (pushToDiscord) {
    const posted = await postWebhook(newsEmbed(doc));
    if (posted) {
      await news.updateOne({ slug }, { $set: { discordPosted: true } });
      doc.discordPosted = true;
    }
  }
  return doc;
}
