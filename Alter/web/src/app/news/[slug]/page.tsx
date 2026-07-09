import Link from "next/link";
import { notFound } from "next/navigation";
import { getNewsBySlug } from "@/lib/news";
import { renderMarkdown } from "@/lib/markdown";

export const dynamic = "force-dynamic";

export default async function NewsArticle({ params }: { params: { slug: string } }) {
  const n = await getNewsBySlug(params.slug);
  if (!n || !n.published) notFound();

  const when = new Date(n.publishedAt ?? n.createdAt).toLocaleDateString(undefined, {
    year: "numeric", month: "long", day: "numeric",
  });

  return (
    <article className="mx-auto max-w-3xl space-y-4">
      <Link href="/news" className="text-sm text-lumbridge-gold hover:underline">← All news</Link>
      <div className="flex items-center gap-3 text-xs text-lumbridge-parchment/50">
        <span className="rounded bg-white/10 px-2 py-0.5 uppercase tracking-wide">{n.category}</span>
        <span>{when}</span>
        <span>· {n.authorName}</span>
      </div>
      <h1 className="text-3xl font-bold text-lumbridge-gold">{n.title}</h1>
      <div className="text-lumbridge-parchment/90" dangerouslySetInnerHTML={{ __html: renderMarkdown(n.body) }} />
    </article>
  );
}
