import Link from "next/link";
import { redirect } from "next/navigation";
import { getForumSections, getBoardStats } from "@/lib/forum";
import { Reveal } from "@/components/Reveal";

export const metadata = { title: "Forum · Fall of Varrock" };
export const dynamic = "force-dynamic";

// Once NodeBB is live, the community forum lives there - send visitors over.
const FORUM_URL = process.env.FORUM_URL;

// Gradient per section, cycled.
const GRADS = [
  "from-violet-500 to-indigo-600",
  "from-cyan-400 to-sky-600",
  "from-pink-500 to-fuchsia-600",
  "from-amber-400 to-orange-600",
  "from-emerald-400 to-teal-600",
  "from-rose-500 to-red-600",
];

function ago(ts: number | null): string {
  if (!ts) return "No posts yet";
  const s = Math.floor((Date.now() - ts) / 1000);
  if (s < 60) return "just now";
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  return `${Math.floor(s / 86400)}d ago`;
}

export default async function ForumIndex() {
  if (FORUM_URL) redirect(FORUM_URL);
  const sections = await getForumSections();
  const stats = new Map<string, { threads: number; lastPostAt: number | null }>();
  await Promise.all(sections.flatMap((s) => s.boards).map(async (b) => stats.set(b.slug, await getBoardStats(b.slug))));

  return (
    <div className="space-y-10">
      <Reveal>
        <div className="eyebrow">Community</div>
        <h1 className="mt-2 text-4xl font-bold">Kingdom <span className="text-gradient">Forum</span></h1>
        <p className="mt-2 text-lumbridge-parchment/65">Guides, strategy, the war, marketplace and more. Jump in.</p>
      </Reveal>

      {sections.map((section, si) => (
        <Reveal key={section.section} delay={si * 60} className="space-y-3">
          <div className="flex items-center gap-3">
            <span className={`h-5 w-1.5 rounded-full bg-gradient-to-b ${GRADS[si % GRADS.length]}`} />
            <h2 className="font-display text-lg font-bold uppercase tracking-wide text-white">{section.section}</h2>
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            {section.boards.map((b, bi) => {
              const st = stats.get(b.slug) ?? { threads: 0, lastPostAt: null };
              const grad = GRADS[(si + bi) % GRADS.length];
              return (
                <Link key={b.slug} href={`/forum/${b.slug}`}
                  className="card card-hover group flex items-center gap-4 p-4">
                  <span className={`grid h-12 w-12 shrink-0 place-items-center rounded-xl bg-gradient-to-br ${grad} font-display text-lg font-bold text-white shadow-lg transition-transform group-hover:scale-110`}>
                    {b.name.charAt(0)}
                  </span>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="truncate font-semibold text-white group-hover:text-lumbridge-cyan">{b.name}</span>
                      {b.minRoleToPost === "staff" && <span className="chip border-lumbridge-pink/30 bg-lumbridge-pink/15 text-lumbridge-pink">Staff</span>}
                    </div>
                    <p className="truncate text-sm text-lumbridge-parchment/55">{b.description}</p>
                  </div>
                  <div className="shrink-0 text-right text-xs text-lumbridge-parchment/45">
                    <div className="font-semibold text-lumbridge-parchment/70">{st.threads}</div>
                    <div>{ago(st.lastPostAt)}</div>
                  </div>
                </Link>
              );
            })}
          </div>
        </Reveal>
      ))}
    </div>
  );
}
