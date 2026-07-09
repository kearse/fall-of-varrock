import fs from "fs";
import path from "path";

/**
 * File-based wiki. Articles are markdown files in `web/content/wiki/*.md` with a
 * tiny frontmatter block; the dev/prod server reads them from disk on every
 * request (pages are force-dynamic), so editing a file updates the live site on
 * refresh - no database, no admin UI. Add a feature to the game → drop a .md
 * file here in the same commit.
 *
 * Frontmatter:
 * ---
 * title: Wilderness Loot Keys
 * category: PvP
 * summary: One line shown on cards and in the sidebar.
 * order: 10            (sort within category, lower first)
 * updated: 2026-07-04
 * ---
 */

export type WikiArticle = {
  slug: string;
  title: string;
  category: string;
  summary: string;
  order: number;
  updated: string;
  body: string;
  /** Optional infobox rows from frontmatter: `infobox: Type = Minigame; Command = ::arena` */
  infobox: [string, string][];
};

/** Fixed category display order; unknown categories sort after these, alphabetically. */
export const WIKI_CATEGORIES = [
  "Getting Started",
  "The War",
  "PvP & Wilderness",
  "Minigames & Bosses",
  "Magic",
  "Skilling",
  "Economy & Trading",
  "Companions & Progression",
  "Store & Membership",
];

function contentDir(): string {
  return path.join(process.cwd(), "content", "wiki");
}

function parseFrontmatter(raw: string): { meta: Record<string, string>; body: string } {
  const meta: Record<string, string> = {};
  if (!raw.startsWith("---")) return { meta, body: raw };
  const end = raw.indexOf("\n---", 3);
  if (end === -1) return { meta, body: raw };
  const head = raw.slice(3, end).trim();
  for (const line of head.split("\n")) {
    const idx = line.indexOf(":");
    if (idx === -1) continue;
    meta[line.slice(0, idx).trim()] = line.slice(idx + 1).trim();
  }
  const body = raw.slice(end + 4).replace(/^\r?\n/, "");
  return { meta, body };
}

function loadFile(file: string): WikiArticle | null {
  try {
    const raw = fs.readFileSync(path.join(contentDir(), file), "utf8");
    const { meta, body } = parseFrontmatter(raw);
    const slug = file.replace(/\.md$/, "");
    if (!meta.title) return null;
    const infobox: [string, string][] = (meta.infobox || "")
      .split(";")
      .map((pair) => {
        const idx = pair.indexOf("=");
        if (idx === -1) return null;
        return [pair.slice(0, idx).trim(), pair.slice(idx + 1).trim()] as [string, string];
      })
      .filter((p): p is [string, string] => p !== null && p[0] !== "" && p[1] !== "");
    return {
      slug,
      title: meta.title,
      category: meta.category || "General",
      summary: meta.summary || "",
      order: Number(meta.order) || 999,
      updated: meta.updated || "",
      body,
      infobox,
    };
  } catch {
    return null;
  }
}

export function getAllArticles(): WikiArticle[] {
  let files: string[] = [];
  try {
    files = fs.readdirSync(contentDir()).filter((f) => f.endsWith(".md"));
  } catch {
    return [];
  }
  const articles = files.map(loadFile).filter((a): a is WikiArticle => a !== null);
  articles.sort((a, b) => a.order - b.order || a.title.localeCompare(b.title));
  return articles;
}

export function getArticle(slug: string): WikiArticle | null {
  // Reject anything that could escape the content dir.
  if (!/^[a-z0-9-]+$/.test(slug)) return null;
  return loadFile(`${slug}.md`);
}

/** Articles grouped by category, categories in WIKI_CATEGORIES order. */
export function getArticlesByCategory(): Map<string, WikiArticle[]> {
  const grouped = new Map<string, WikiArticle[]>();
  for (const a of getAllArticles()) {
    const list = grouped.get(a.category) ?? [];
    list.push(a);
    grouped.set(a.category, list);
  }
  const rank = (c: string) => {
    const i = WIKI_CATEGORIES.indexOf(c);
    return i === -1 ? WIKI_CATEGORIES.length : i;
  };
  return new Map([...grouped.entries()].sort((a, b) => rank(a[0]) - rank(b[0]) || a[0].localeCompare(b[0])));
}
