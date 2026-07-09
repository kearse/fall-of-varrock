import Link from "next/link";
import { notFound } from "next/navigation";
import { getGuideBySlug } from "@/lib/guides";
import { renderMarkdown } from "@/lib/markdown";

export const dynamic = "force-dynamic";

export default async function GuidePage({ params }: { params: { slug: string } }) {
  const guide = await getGuideBySlug(params.slug);
  if (!guide || !guide.published) notFound();

  return (
    <article className="mx-auto max-w-3xl space-y-4">
      <Link href="/guides" className="text-sm text-lumbridge-gold hover:underline">← All guides</Link>
      <div className="text-xs uppercase tracking-wide text-lumbridge-parchment/50">{guide.category}</div>
      <h1 className="text-3xl font-bold text-lumbridge-gold">{guide.title}</h1>
      {guide.summary && <p className="text-lumbridge-parchment/70">{guide.summary}</p>}
      <div className="text-lumbridge-parchment/90" dangerouslySetInnerHTML={{ __html: renderMarkdown(guide.body) }} />
      <p className="pt-4 text-xs text-lumbridge-parchment/40">By {guide.authorName}</p>
    </article>
  );
}
