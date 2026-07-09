import Link from "next/link";
import { getPublishedNews } from "@/lib/news";
import { stripMarkdown } from "@/lib/markdown";
import { Reveal } from "@/components/Reveal";
import type { NewsDoc } from "@/lib/collections";

export const metadata = { title: "News · Fall of Varrock" };
export const dynamic = "force-dynamic";

const CATEGORIES: { key: NewsDoc["category"]; label: string }[] = [
  { key: "update", label: "Updates" },
  { key: "announcement", label: "Announcements" },
  { key: "patch", label: "Patch Notes" },
  { key: "event", label: "Events" },
];

const CATEGORY_LABEL: Record<string, string> = {
  update: "Update",
  announcement: "Announcement",
  patch: "Patch Notes",
  event: "Event",
};

/** Per-category chip tint so the grid scans at a glance. */
const CATEGORY_CHIP: Record<string, string> = {
  update: "border-lumbridge-gold/30 bg-lumbridge-gold/10 text-lumbridge-goldlight",
  announcement: "border-lumbridge-ember/30 bg-lumbridge-ember/10 text-lumbridge-ember",
  patch: "border-white/15 bg-white/10 text-lumbridge-parchment",
  event: "border-lumbridge-ember/30 bg-lumbridge-ember/10 text-lumbridge-ember",
};

function date(ts?: number): string {
  if (!ts) return "";
  return new Date(ts).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}

function excerpt(body: string, len: number): string {
  const text = stripMarkdown(body).replace(/\s+/g, " ").trim();
  if (text.length <= len) return text;
  return `${text.slice(0, len).replace(/\s+\S*$/, "")}...`;
}

function CategoryChip({ category }: { category: string }) {
  return (
    <span className={`chip uppercase tracking-wider ${CATEGORY_CHIP[category] ?? "text-lumbridge-parchment"}`}>
      {CATEGORY_LABEL[category] ?? category}
    </span>
  );
}

function Meta({ n }: { n: NewsDoc }) {
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-2 text-xs text-lumbridge-parchment/50">
      <CategoryChip category={n.category} />
      <span>{date(n.publishedAt ?? n.createdAt)}</span>
      <span aria-hidden="true">·</span>
      <span>{n.authorName}</span>
    </div>
  );
}

/** Big latest-post hero: ruined-city art under a heavy scrim, crimson edge. */
function FeaturedCard({ n }: { n: NewsDoc }) {
  return (
    <Link
      href={`/news/${n.slug}`}
      className="group relative block overflow-hidden rounded-2xl border border-lumbridge-gold/25 shadow-glass transition-all duration-300 hover:-translate-y-1 hover:border-lumbridge-gold/50 hover:shadow-glow"
    >
      {/* Ruined-Varrock art as a CSS background, kept subtle under the scrim. */}
      <div
        aria-hidden="true"
        className="absolute inset-0 bg-cover bg-center opacity-40 transition-transform duration-700 group-hover:scale-105"
        style={{ backgroundImage: "url(/img/hero-bg.jpg)" }}
      />
      <div aria-hidden="true" className="absolute inset-0 bg-gradient-to-t from-[#080506] via-[#080506]/80 to-[#080506]/30" />
      <div aria-hidden="true" className="absolute inset-0 bg-gradient-to-r from-red-600/15 via-transparent to-transparent" />
      <div aria-hidden="true" className="absolute inset-x-0 bottom-0 h-px bg-gradient-to-r from-transparent via-red-500/60 to-transparent" />

      <div className="relative p-6 sm:p-10">
        <div className="mb-4 flex flex-wrap items-center gap-x-3 gap-y-2 text-xs text-lumbridge-parchment/60">
          <span className="chip border-lumbridge-gold/40 bg-lumbridge-gold/15 font-semibold uppercase tracking-wider text-lumbridge-goldlight">
            Latest Dispatch
          </span>
          <CategoryChip category={n.category} />
          <span>{date(n.publishedAt ?? n.createdAt)}</span>
          <span aria-hidden="true">·</span>
          <span>{n.authorName}</span>
        </div>
        <h2 className="max-w-2xl text-2xl font-bold leading-tight text-lumbridge-parchment sm:text-4xl">
          {n.title}
        </h2>
        <p className="mt-4 max-w-2xl text-sm leading-relaxed text-lumbridge-parchment/70 sm:text-base">
          {excerpt(n.body, 240)}
        </p>
        <span className="link-gold mt-6 inline-flex items-center gap-2 text-sm">
          Read more
          <span aria-hidden="true" className="transition-transform duration-300 group-hover:translate-x-1">
            &rarr;
          </span>
        </span>
      </div>
    </Link>
  );
}

function PostCard({ n }: { n: NewsDoc }) {
  return (
    <Link href={`/news/${n.slug}`} className="card card-hover group flex h-full flex-col">
      <Meta n={n} />
      <h3 className="mt-3 text-lg font-semibold leading-snug text-lumbridge-parchment transition-colors group-hover:text-lumbridge-goldlight">
        {n.title}
      </h3>
      <p className="mt-2 flex-1 text-sm leading-relaxed text-lumbridge-parchment/60">{excerpt(n.body, 140)}</p>
      <span className="link-gold mt-4 inline-flex items-center gap-1.5 text-xs uppercase tracking-wider">
        Read more
        <span aria-hidden="true" className="transition-transform duration-300 group-hover:translate-x-1">
          &rarr;
        </span>
      </span>
    </Link>
  );
}

function EmptyState({ filtered }: { filtered: boolean }) {
  return (
    <div className="card flex flex-col items-center gap-4 py-16 text-center">
      <div
        aria-hidden="true"
        className="flex h-14 w-14 items-center justify-center rounded-full border border-lumbridge-gold/25 bg-lumbridge-gold/10 text-2xl"
      >
        &#9876;
      </div>
      <div>
        <p className="text-lg font-semibold text-lumbridge-parchment">No dispatches from the front yet.</p>
        <p className="mt-1 text-sm text-lumbridge-parchment/60">
          {filtered ? "Nothing filed under this banner so far." : "The scribes are still sharpening their quills."} Check
          back soon.
        </p>
      </div>
      {filtered && (
        <Link href="/news" className="link-gold text-sm">
          View all dispatches
        </Link>
      )}
    </div>
  );
}

export default async function NewsPage({
  searchParams,
}: {
  searchParams?: { category?: string };
}) {
  const news = await getPublishedNews();
  const active = CATEGORIES.find((c) => c.key === searchParams?.category)?.key;
  const shown = active ? news.filter((n) => n.category === active) : news;
  const [featured, ...rest] = shown;

  return (
    <div className="mx-auto max-w-5xl space-y-8">
      <Reveal>
        <span className="eyebrow">War Dispatches</span>
        <h1 className="text-3xl font-bold text-lumbridge-parchment sm:text-4xl">
          News from the <span className="text-gradient">Front</span>
        </h1>
        <p className="mt-2 max-w-xl text-sm text-lumbridge-parchment/60">
          Updates, patch notes and events from the ruins of Varrock.
        </p>
      </Reveal>

      {/* Category filters - real links, filtered server-side. */}
      <Reveal delay={80}>
        <nav aria-label="Filter news by category" className="flex flex-wrap items-center gap-2">
          <Link
            href="/news"
            className={`chip transition-colors ${
              !active
                ? "border-lumbridge-gold/50 bg-lumbridge-gold/15 text-lumbridge-goldlight"
                : "text-lumbridge-parchment/60 hover:border-white/25 hover:text-lumbridge-parchment"
            }`}
          >
            All
          </Link>
          {CATEGORIES.map((c) => (
            <Link
              key={c.key}
              href={`/news?category=${c.key}`}
              className={`chip transition-colors ${
                active === c.key
                  ? "border-lumbridge-gold/50 bg-lumbridge-gold/15 text-lumbridge-goldlight"
                  : "text-lumbridge-parchment/60 hover:border-white/25 hover:text-lumbridge-parchment"
              }`}
            >
              {c.label}
            </Link>
          ))}
        </nav>
      </Reveal>

      {shown.length === 0 ? (
        <Reveal delay={140}>
          <EmptyState filtered={!!active} />
        </Reveal>
      ) : (
        <>
          <Reveal delay={140}>
            <FeaturedCard n={featured} />
          </Reveal>

          {rest.length > 0 && (
            <div className="space-y-4">
              <div className="flex items-center gap-4">
                <h2 className="text-xs font-semibold uppercase tracking-wider text-lumbridge-parchmentdark">
                  Earlier dispatches
                </h2>
                <div className="rule-gold flex-1" />
              </div>
              <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
                {rest.map((n, i) => (
                  <Reveal key={n.slug} delay={Math.min(i, 5) * 70} className="h-full">
                    <PostCard n={n} />
                  </Reveal>
                ))}
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}
