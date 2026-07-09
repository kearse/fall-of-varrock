import Link from "next/link";
import { notFound } from "next/navigation";
import { getCategory, getThreads } from "@/lib/forum";
import { getSession } from "@/lib/session";
import { NewThreadForm } from "@/components/forum/NewThreadForm";

export const dynamic = "force-dynamic";

function ago(ts: number): string {
  const s = Math.floor((Date.now() - ts) / 1000);
  if (s < 60) return "just now";
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  return `${Math.floor(s / 86400)}d ago`;
}

const AVATAR_GRADS = [
  "from-violet-500 to-indigo-600", "from-cyan-400 to-sky-600",
  "from-pink-500 to-fuchsia-600", "from-amber-400 to-orange-600", "from-emerald-400 to-teal-600",
];
function gradFor(name: string) {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0;
  return AVATAR_GRADS[h % AVATAR_GRADS.length];
}

export default async function CategoryPage({ params }: { params: { slug: string } }) {
  const category = await getCategory(params.slug);
  if (!category) notFound();
  const threads = await getThreads(params.slug);
  const session = await getSession();
  const isStaff = session?.roles?.some((r) => ["admin", "moderator", "developer"].includes(r));
  const canPost = session && (category.minRoleToPost !== "staff" || isStaff);

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <Link href="/forum" className="link-cyan text-sm">← Forum</Link>
          <h1 className="mt-1 text-3xl font-bold text-white">{category.name}</h1>
          <p className="text-sm text-lumbridge-parchment/60">{category.description}</p>
        </div>
        {canPost && <NewThreadForm categorySlug={category.slug} />}
      </div>

      {!canPost && (session ? (
        category.minRoleToPost === "staff" && <p className="text-sm text-lumbridge-parchment/50">Only staff can start threads here.</p>
      ) : (
        <p className="text-sm text-lumbridge-parchment/50"><Link href="/login" className="link-cyan">Log in</Link> to start a thread.</p>
      ))}

      <div className="overflow-hidden rounded-2xl border border-white/10 bg-white/[0.03] backdrop-blur-xl">
        {threads.length === 0 && <p className="px-4 py-10 text-center text-lumbridge-parchment/50">No threads yet - be the first!</p>}
        {threads.map((t) => (
          <Link key={t._id?.toString()} href={`/forum/thread/${t._id}`}
            className="flex items-center gap-3 border-b border-white/5 px-4 py-3 transition-colors last:border-0 hover:bg-white/5">
            <span className={`grid h-10 w-10 shrink-0 place-items-center rounded-full bg-gradient-to-br ${gradFor(t.authorName)} text-sm font-bold text-white`}>
              {t.authorName.charAt(0).toUpperCase()}
            </span>
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-1.5 font-medium text-white">
                {t.pinned && <span className="text-lumbridge-gold" title="Pinned">📌</span>}
                {t.locked && <span title="Locked">🔒</span>}
                <span className="truncate">{t.title}</span>
              </div>
              <div className="text-xs text-lumbridge-parchment/50">by {t.authorName}</div>
            </div>
            <div className="shrink-0 text-right text-xs text-lumbridge-parchment/50">
              <div className="chip border-white/10 bg-white/5">{t.replyCount} repl{t.replyCount === 1 ? "y" : "ies"}</div>
              <div className="mt-1">{ago(t.lastPostAt)}</div>
            </div>
          </Link>
        ))}
      </div>
    </div>
  );
}
