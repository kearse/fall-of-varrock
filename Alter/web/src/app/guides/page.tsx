import Link from "next/link";
import { redirect } from "next/navigation";
import { getPublishedGuides } from "@/lib/guides";

export const metadata = { title: "Guides · Fall of Varrock" };
export const dynamic = "force-dynamic";

export default async function GuidesPage() {
  // Guides now live on the forum (NodeBB Guides board) - send visitors there.
  if (process.env.FORUM_URL) redirect(`${process.env.FORUM_URL}/category/13`);
  const guides = await getPublishedGuides();
  const byCategory = new Map<string, typeof guides>();
  for (const g of guides) {
    const list = byCategory.get(g.category) ?? [];
    list.push(g);
    byCategory.set(g.category, list);
  }

  return (
    <div className="space-y-8">
      <h1 className="text-3xl font-bold text-lumbridge-gold">Guides</h1>
      {guides.length === 0 && <p className="text-lumbridge-parchment/60">No guides published yet.</p>}
      {[...byCategory.entries()].map(([cat, list]) => (
        <section key={cat} className="space-y-3">
          <h2 className="text-xl font-semibold text-lumbridge-goldlight">{cat}</h2>
          <div className="grid gap-3 sm:grid-cols-2">
            {list.map((g) => (
              <Link key={g.slug} href={`/guides/${g.slug}`} className="card transition hover:border-lumbridge-gold/40">
                <h3 className="font-semibold text-lumbridge-gold">{g.title}</h3>
                <p className="mt-1 text-sm text-lumbridge-parchment/60">{g.summary}</p>
              </Link>
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}
