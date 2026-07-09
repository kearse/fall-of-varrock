import { Collections, GuideDoc } from "./collections";

function slugify(s: string): string {
  return s.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 60) || "guide";
}

export async function getPublishedGuides(): Promise<GuideDoc[]> {
  const guides = await Collections.guides();
  return guides.find({ published: true }).sort({ createdAt: -1 }).toArray();
}

export async function getGuideBySlug(slug: string): Promise<GuideDoc | null> {
  const guides = await Collections.guides();
  return guides.findOne({ slug });
}

export async function createGuide(input: {
  title: string;
  summary: string;
  body: string;
  category: string;
  authorName: string;
}): Promise<GuideDoc> {
  const guides = await Collections.guides();
  const now = Date.now();
  const slug = `${slugify(input.title)}`;
  const doc: GuideDoc = {
    slug,
    title: input.title,
    summary: input.summary,
    body: input.body,
    category: input.category || "General",
    authorName: input.authorName,
    published: true,
    createdAt: now,
  };
  // Upsert by slug so editing a guide with the same title replaces it.
  await guides.updateOne({ slug }, { $set: doc }, { upsert: true });
  return doc;
}
