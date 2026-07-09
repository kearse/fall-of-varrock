import Link from "next/link";
import {
  getHiscoresPage, SKILLS,
  HiscoreCategory, isCombatCategory,
  CATEGORY_GROUPS, categoryLabel, valueHeader, RankedPlayer,
} from "@/lib/hiscores";
import { PlayerSearch } from "@/components/PlayerSearch";

export const metadata = { title: "Hiscores · Fall of Varrock" };
export const dynamic = "force-dynamic";

const PAGE_SIZE = 25;

function fmt(n: number): string {
  return n.toLocaleString();
}

function validCategory(input: string | undefined): HiscoreCategory {
  if (!input || input === "overall") return "overall";
  if ((SKILLS as readonly string[]).includes(input)) return input as HiscoreCategory;
  if (isCombatCategory(input)) return input;
  return "overall";
}

// Primary + secondary value shown for a row, by category.
function valueOf(category: HiscoreCategory, p: RankedPlayer): { primary: string; secondary: string } {
  if (category === "overall") return { primary: fmt(p.totalLevel), secondary: fmt(p.totalXp) + " xp" };
  if (category === "kdr") return { primary: p.pvp.kdr.toFixed(3), secondary: `${p.pvp.kills} / ${p.pvp.deaths}` };
  if (category === "kills") return { primary: fmt(p.pvp.kills), secondary: `${p.pvp.deaths} deaths` };
  if (category === "deaths") return { primary: fmt(p.pvp.deaths), secondary: `${p.pvp.kills} kills` };
  if (category === "elo") return { primary: fmt(p.pvp.elo), secondary: `K/D ${p.pvp.kdr.toFixed(2)}` };
  return { primary: fmt(p.skills[category].level), secondary: fmt(p.skills[category].xp) + " xp" };
}

// Icon path per category: custom lava art for Overall + PvP, official OSRS icons
// for skills (drop them in /public/img/skills/<skill>.png).
function iconFor(c: HiscoreCategory): string {
  if (c === "overall") return "/img/rank/overall.png";
  if (isCombatCategory(c)) return `/img/rank/${c}.png`;
  return `/img/skills/${c}.png`;
}

// Rendered as a CSS background so a not-yet-added skill icon simply shows nothing
// (no broken-image icon) until the file exists.
function CatIcon({ c, className = "" }: { c: HiscoreCategory; className?: string }) {
  return (
    <span
      aria-hidden
      className={`inline-block shrink-0 bg-contain bg-center bg-no-repeat ${className}`}
      style={{ backgroundImage: `url(${iconFor(c)})` }}
    />
  );
}

export default async function HiscoresPage({
  searchParams,
}: {
  searchParams: { c?: string; page?: string };
}) {
  const category = validCategory(searchParams.c);
  const requestedPage = parseInt(searchParams.page ?? "1", 10);
  const { rows, total, page, pageCount } = await getHiscoresPage(category, requestedPage, PAGE_SIZE);

  const hrefFor = (c: HiscoreCategory) => (c === "overall" ? "/hiscores" : `/hiscores?c=${c}`);
  const pageHref = (n: number) =>
    category === "overall" ? `/hiscores?page=${n}` : `/hiscores?c=${category}&page=${n}`;

  return (
    <div className="space-y-6">
      <div>
        <div className="eyebrow">Leaderboards</div>
        <h1 className="mt-1 text-3xl font-bold text-lumbridge-gold">Hiscores</h1>
        <p className="mt-1 text-sm text-lumbridge-parchment/60">
          The ladder of the Fall of Varrock - skills, total level, and PvP glory.
        </p>
      </div>

      {/* Mobile category scroller */}
      <div className="-mx-4 flex gap-2 overflow-x-auto px-4 pb-1 lg:hidden">
        {CATEGORY_GROUPS.flatMap((g) => g.items).map((c) => (
          <Link
            key={c}
            href={hrefFor(c)}
            className={
              "flex shrink-0 items-center gap-1.5 rounded-full px-3 py-1 text-sm " +
              (c === category
                ? "bg-lumbridge-gold text-lumbridge-stone"
                : "border border-white/10 text-lumbridge-parchment/80")
            }
          >
            <CatIcon c={c} className="h-4 w-4" />
            {categoryLabel(c)}
          </Link>
        ))}
      </div>

      <div className="grid gap-6 lg:grid-cols-[220px_1fr]">
        {/* Desktop category rail */}
        <aside className="hidden lg:block">
          <div className="sticky top-24 space-y-5">
            {CATEGORY_GROUPS.map((group) => (
              <div key={group.name}>
                <div className="mb-2 px-2 text-[11px] font-semibold uppercase tracking-wider text-lumbridge-parchment/40">
                  {group.name}
                </div>
                <div className="space-y-0.5">
                  {group.items.map((c) => (
                    <Link
                      key={c}
                      href={hrefFor(c)}
                      className={
                        "flex items-center gap-2 rounded-lg px-3 py-1.5 text-sm transition-colors " +
                        (c === category
                          ? "bg-lumbridge-gold/15 font-medium text-lumbridge-gold shadow-[inset_0_0_0_1px_rgba(220,38,38,0.3)]"
                          : "text-lumbridge-parchment/70 hover:bg-white/5 hover:text-white")
                      }
                    >
                      <CatIcon c={c} className="h-5 w-5" />
                      {categoryLabel(c)}
                    </Link>
                  ))}
                </div>
              </div>
            ))}
          </div>
        </aside>

        {/* Leaderboard */}
        <div className="min-w-0 space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-3">
              <CatIcon c={category} className="h-9 w-9" />
              <div>
                <h2 className="font-display text-xl font-bold text-white">{categoryLabel(category)}</h2>
                <p className="text-xs text-lumbridge-parchment/50">
                  {total.toLocaleString()} ranked player{total === 1 ? "" : "s"}
                </p>
              </div>
            </div>
            <PlayerSearch />
          </div>

          <div className="widget overflow-x-auto p-0">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-white/10 text-left text-xs uppercase tracking-wide text-lumbridge-parchment/50">
                  <th className="w-16 px-4 py-3">Rank</th>
                  <th className="px-4 py-3">Player</th>
                  <th className="px-4 py-3 text-right">{valueHeader(category)}</th>
                  <th className="hidden px-4 py-3 text-right sm:table-cell">Detail</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((p) => {
                  const v = valueOf(category, p);
                  return (
                    <tr key={p.loginUsername} className="border-b border-white/5 transition-colors hover:bg-white/5">
                      <td className="px-4 py-2.5">
                        <RankBadge rank={p.rank} />
                      </td>
                      <td className="px-4 py-2.5">
                        <Link
                          href={`/player/${encodeURIComponent(p.displayName)}`}
                          className="font-medium text-lumbridge-parchment hover:text-lumbridge-gold hover:underline"
                        >
                          {p.displayName}
                        </Link>
                      </td>
                      <td
                        className={
                          "px-4 py-2.5 text-right font-semibold tabular-nums " +
                          (p.rank === 1 ? "text-lumbridge-gold" : "text-lumbridge-parchment")
                        }
                      >
                        {v.primary}
                      </td>
                      <td className="hidden px-4 py-2.5 text-right tabular-nums text-lumbridge-parchment/50 sm:table-cell">
                        {v.secondary}
                      </td>
                    </tr>
                  );
                })}
                {rows.length === 0 && (
                  <tr>
                    <td colSpan={4} className="px-4 py-12 text-center text-lumbridge-parchment/50">
                      No ranked players yet - be the first.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          {pageCount > 1 && (
            <div className="flex items-center justify-between text-sm">
              <PageLink href={pageHref(page - 1)} disabled={page <= 1}>
                ← Prev
              </PageLink>
              <span className="text-lumbridge-parchment/50">
                Page {page} of {pageCount}
              </span>
              <PageLink href={pageHref(page + 1)} disabled={page >= pageCount}>
                Next →
              </PageLink>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

const MEDALS: Record<number, string> = {
  1: "bg-gradient-to-br from-amber-300 to-yellow-500 text-[#3a2a08]",
  2: "bg-gradient-to-br from-slate-200 to-slate-400 text-[#1e293b]",
  3: "bg-gradient-to-br from-orange-400 to-amber-700 text-[#2a1505]",
};

function RankBadge({ rank }: { rank: number }) {
  const medal = MEDALS[rank];
  if (medal) {
    return (
      <span className={`grid h-7 w-7 place-items-center rounded-full text-xs font-bold tabular-nums ${medal}`}>
        {rank}
      </span>
    );
  }
  return <span className="pl-1.5 tabular-nums text-lumbridge-parchment/55">{rank}</span>;
}

function PageLink({ href, disabled, children }: { href: string; disabled: boolean; children: React.ReactNode }) {
  if (disabled) {
    return (
      <span className="cursor-not-allowed rounded-lg border border-white/5 px-4 py-1.5 text-lumbridge-parchment/25">
        {children}
      </span>
    );
  }
  return (
    <Link
      href={href}
      className="rounded-lg border border-white/10 px-4 py-1.5 text-lumbridge-parchment/80 transition-colors hover:border-lumbridge-gold/50 hover:text-white"
    >
      {children}
    </Link>
  );
}
