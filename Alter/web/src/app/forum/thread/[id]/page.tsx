import Link from "next/link";
import { notFound } from "next/navigation";
import { getThread, getPosts, getCategory } from "@/lib/forum";
import { getSession } from "@/lib/session";
import { renderMarkdown } from "@/lib/markdown";
import { ReplyForm } from "@/components/forum/ReplyForm";
import { ModControls } from "@/components/forum/ModControls";

export const dynamic = "force-dynamic";

function when(ts: number): string {
  return new Date(ts).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
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

export default async function ThreadPage({ params }: { params: { id: string } }) {
  const thread = await getThread(params.id);
  if (!thread) notFound();
  const [posts, category, session] = await Promise.all([
    getPosts(params.id),
    getCategory(thread.categorySlug),
    getSession(),
  ]);
  const isStaff = session?.roles?.some((r) => ["admin", "moderator", "developer"].includes(r));

  return (
    <div className="mx-auto max-w-3xl space-y-4">
      <div className="flex items-center justify-between">
        <Link href={`/forum/${thread.categorySlug}`} className="text-sm text-lumbridge-gold hover:underline">
          ← {category?.name ?? "Back"}
        </Link>
        {isStaff && <ModControls threadId={params.id} pinned={thread.pinned} locked={thread.locked} />}
      </div>

      <h1 className="text-2xl font-bold text-lumbridge-gold">
        {thread.pinned && <span className="mr-1">📌</span>}
        {thread.locked && <span className="mr-1">🔒</span>}
        {thread.title}
      </h1>

      <div className="space-y-4">
        {posts.map((p, i) => (
          <div key={p._id?.toString()} className="card flex gap-4">
            <Link href={`/player/${encodeURIComponent(p.authorName)}`}
              className={`grid h-11 w-11 shrink-0 place-items-center self-start rounded-full bg-gradient-to-br ${gradFor(p.authorName)} text-sm font-bold text-white`}>
              {p.authorName.charAt(0).toUpperCase()}
            </Link>
            <div className="min-w-0 flex-1">
              <div className="mb-2 flex items-center justify-between text-xs text-lumbridge-parchment/50">
                <span className="flex items-center gap-2">
                  <Link href={`/player/${encodeURIComponent(p.authorName)}`} className="font-semibold text-white hover:text-lumbridge-cyan">{p.authorName}</Link>
                  {i === 0 && <span className="chip border-lumbridge-violet/30 bg-lumbridge-violet/15 text-lumbridge-violet">OP</span>}
                </span>
                <span>{when(p.createdAt)}</span>
              </div>
              <div className="text-sm text-lumbridge-parchment/90" dangerouslySetInnerHTML={{ __html: renderMarkdown(p.body) }} />
            </div>
          </div>
        ))}
      </div>

      {session ? (
        <ReplyForm threadId={params.id} locked={thread.locked} />
      ) : (
        <p className="text-sm text-lumbridge-parchment/50">
          <Link href="/login" className="text-lumbridge-gold">Log in</Link> to reply.
        </p>
      )}
    </div>
  );
}
