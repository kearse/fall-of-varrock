import { ObjectId } from "mongodb";
import { Collections, ForumThreadDoc, ForumPostDoc, ForumCategoryDoc } from "./collections";

// Roat-style structure, themed to Fall of Varrock. Boards are grouped under
// a `section` header on the forum index. Order is global (sections inherit the
// order of their first board).
const DEFAULT_CATEGORIES: Omit<ForumCategoryDoc, "_id">[] = [
  // Fall of Varrock (official)
  { slug: "announcements", section: "Fall of Varrock", name: "Announcements", description: "Official news from the staff team.", order: 0, minRoleToPost: "staff" },
  { slug: "updates", section: "Fall of Varrock", name: "Updates & Patch Notes", description: "Every game update and patch, logged.", order: 1, minRoleToPost: "staff" },
  { slug: "rules", section: "Fall of Varrock", name: "Rules", description: "The in-game and forum rules. Read before posting.", order: 2, minRoleToPost: "staff" },

  // Community
  { slug: "general", section: "Community", name: "General Discussion", description: "Talk about anything Fall of Varrock.", order: 10, minRoleToPost: "" },
  { slug: "introductions", section: "Community", name: "Introductions", description: "New to the realm? Say hello.", order: 11, minRoleToPost: "" },
  { slug: "achievements", section: "Community", name: "Goals & Achievements", description: "Show off your milestones and brag a little.", order: 12, minRoleToPost: "" },
  { slug: "media", section: "Community", name: "Media & Screenshots", description: "Share screenshots, clips and videos.", order: 13, minRoleToPost: "" },
  { slug: "events", section: "Community", name: "Events", description: "Community and staff-hosted events.", order: 14, minRoleToPost: "" },

  // The War (unique KoL content)
  { slug: "war-strategy", section: "The War", name: "War Strategy & Tactics", description: "Siege tactics, campaign planning and troop comps.", order: 20, minRoleToPost: "" },
  { slug: "recruitment", section: "The War", name: "Clan & Troop Recruitment", description: "Recruit for your clan or war company.", order: 21, minRoleToPost: "" },
  { slug: "kill-pics", section: "The War", name: "Campaign Reports & Kill Pics", description: "Battle reports and your best kill pics.", order: 22, minRoleToPost: "" },

  // Help & Guides
  { slug: "guides", section: "Help & Guides", name: "Guides & Strategies", description: "Money-makers, skilling and PvP guides.", order: 30, minRoleToPost: "" },
  { slug: "help", section: "Help & Guides", name: "Help & Support", description: "Stuck? Ask the community.", order: 31, minRoleToPost: "" },
  { slug: "account-recovery", section: "Help & Guides", name: "Account Recovery", description: "Recover a lost or compromised account.", order: 32, minRoleToPost: "" },

  // Economy
  { slug: "marketplace", section: "Economy", name: "Marketplace", description: "Buy, sell and trade in-game goods.", order: 40, minRoleToPost: "" },
  { slug: "price-checks", section: "Economy", name: "Price Checks", description: "Not sure what it's worth? Ask here.", order: 41, minRoleToPost: "" },

  // Feedback & Reports
  { slug: "suggestions", section: "Feedback & Reports", name: "Suggestions", description: "Ideas to improve the game and forum.", order: 50, minRoleToPost: "" },
  { slug: "bug-reports", section: "Feedback & Reports", name: "Bug Reports", description: "Found a bug? Report it here.", order: 51, minRoleToPost: "" },
  { slug: "player-reports", section: "Feedback & Reports", name: "Player Reports", description: "Report rule-breakers (with evidence).", order: 52, minRoleToPost: "" },
  { slug: "appeals", section: "Feedback & Reports", name: "Punishment Appeals", description: "Appeal a mute, ban or jail.", order: 53, minRoleToPost: "" },
  { slug: "applications", section: "Feedback & Reports", name: "Staff Applications", description: "Apply to join the staff team.", order: 54, minRoleToPost: "" },
];

/** Ensures the default boards exist and are up to date (idempotent upsert by slug). */
export async function ensureCategories(): Promise<void> {
  const col = await Collections.forumCategories();
  await Promise.all(
    DEFAULT_CATEGORIES.map((c) =>
      col.updateOne({ slug: c.slug }, { $set: c }, { upsert: true })
    )
  );
}

export async function getCategories(): Promise<ForumCategoryDoc[]> {
  await ensureCategories();
  const col = await Collections.forumCategories();
  return col.find({}).sort({ order: 1 }).toArray();
}

/** Boards grouped by their section header, in order. */
export async function getForumSections(): Promise<{ section: string; boards: ForumCategoryDoc[] }[]> {
  const boards = await getCategories();
  const sections: { section: string; boards: ForumCategoryDoc[] }[] = [];
  for (const b of boards) {
    let s = sections.find((x) => x.section === b.section);
    if (!s) { s = { section: b.section, boards: [] }; sections.push(s); }
    s.boards.push(b);
  }
  return sections;
}

/** Per-board thread + post counts and last-post time, for the index. */
export async function getBoardStats(slug: string): Promise<{ threads: number; lastPostAt: number | null }> {
  const threadsCol = await Collections.forumThreads();
  const threads = await threadsCol.countDocuments({ categorySlug: slug });
  const latest = await threadsCol.find({ categorySlug: slug }).sort({ lastPostAt: -1 }).limit(1).toArray();
  return { threads, lastPostAt: latest[0]?.lastPostAt ?? null };
}

export async function getCategory(slug: string): Promise<ForumCategoryDoc | null> {
  const col = await Collections.forumCategories();
  return col.findOne({ slug });
}

export async function getThreads(categorySlug: string, limit = 50): Promise<ForumThreadDoc[]> {
  const col = await Collections.forumThreads();
  return col.find({ categorySlug }).sort({ pinned: -1, lastPostAt: -1 }).limit(limit).toArray();
}

export async function getLatestThreads(limit = 8): Promise<ForumThreadDoc[]> {
  const col = await Collections.forumThreads();
  return col.find({}).sort({ lastPostAt: -1 }).limit(limit).toArray();
}

export async function getThread(id: string): Promise<ForumThreadDoc | null> {
  if (!ObjectId.isValid(id)) return null;
  const col = await Collections.forumThreads();
  return col.findOne({ _id: new ObjectId(id) } as any);
}

export async function getPosts(threadId: string): Promise<ForumPostDoc[]> {
  const col = await Collections.forumPosts();
  return col.find({ threadId, deleted: { $ne: true } }).sort({ createdAt: 1 }).toArray();
}

// Simple anti-spam: minimum seconds between a user's posts.
const POST_COOLDOWN_MS = 15_000;

async function tooSoon(authorName: string): Promise<boolean> {
  const col = await Collections.forumPosts();
  const last = await col.find({ authorName }).sort({ createdAt: -1 }).limit(1).toArray();
  if (last.length === 0) return false;
  return Date.now() - last[0].createdAt < POST_COOLDOWN_MS;
}

export async function createThread(opts: {
  categorySlug: string;
  title: string;
  body: string;
  authorName: string;
}): Promise<{ threadId: string } | { error: string }> {
  const category = await getCategory(opts.categorySlug);
  if (!category) return { error: "Category not found." };
  if (await tooSoon(opts.authorName)) return { error: "You're posting too fast - wait a moment." };

  const now = Date.now();
  const threads = await Collections.forumThreads();
  const posts = await Collections.forumPosts();

  const res = await threads.insertOne({
    categorySlug: opts.categorySlug,
    title: opts.title.slice(0, 140),
    authorName: opts.authorName,
    pinned: false,
    locked: false,
    replyCount: 0,
    lastPostAt: now,
    createdAt: now,
  } as ForumThreadDoc);

  await posts.insertOne({
    threadId: res.insertedId.toString(),
    body: opts.body,
    authorName: opts.authorName,
    createdAt: now,
  } as ForumPostDoc);

  return { threadId: res.insertedId.toString() };
}

export async function createPost(opts: {
  threadId: string;
  body: string;
  authorName: string;
}): Promise<{ ok: true } | { error: string }> {
  const thread = await getThread(opts.threadId);
  if (!thread) return { error: "Thread not found." };
  if (thread.locked) return { error: "This thread is locked." };
  if (await tooSoon(opts.authorName)) return { error: "You're posting too fast - wait a moment." };

  const now = Date.now();
  const posts = await Collections.forumPosts();
  const threads = await Collections.forumThreads();

  await posts.insertOne({
    threadId: opts.threadId,
    body: opts.body,
    authorName: opts.authorName,
    createdAt: now,
  } as ForumPostDoc);

  await threads.updateOne(
    { _id: new ObjectId(opts.threadId) } as any,
    { $set: { lastPostAt: now }, $inc: { replyCount: 1 } }
  );
  return { ok: true };
}

export async function moderateThread(
  threadId: string,
  action: "pin" | "unpin" | "lock" | "unlock" | "delete"
): Promise<boolean> {
  if (!ObjectId.isValid(threadId)) return false;
  const threads = await Collections.forumThreads();
  const filter = { _id: new ObjectId(threadId) } as any;
  switch (action) {
    case "pin": await threads.updateOne(filter, { $set: { pinned: true } }); break;
    case "unpin": await threads.updateOne(filter, { $set: { pinned: false } }); break;
    case "lock": await threads.updateOne(filter, { $set: { locked: true } }); break;
    case "unlock": await threads.updateOne(filter, { $set: { locked: false } }); break;
    case "delete": {
      await threads.deleteOne(filter);
      const posts = await Collections.forumPosts();
      await posts.deleteMany({ threadId });
      break;
    }
  }
  return true;
}
