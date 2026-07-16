import Link from "next/link";
import { Crest } from "@/components/Crest";
import { WikiSearch } from "@/components/wiki/WikiSearch";
import { getArticlesByCategory } from "@/lib/wiki";

export const dynamic = "force-dynamic";

/**
 * MediaWiki-style frame for the whole /wiki section: parchment page, left
 * sidebar with search + navigation portlets, white content well. Article and
 * index pages render inside `.wiki-page`.
 */
export default function WikiLayout({ children }: { children: React.ReactNode }) {
  const byCategory = getArticlesByCategory();
  const searchItems = [...byCategory.entries()].flatMap(([cat, list]) =>
    list.map((a) => ({ slug: a.slug, title: a.title, category: cat }))
  );

  return (
    <div className="wiki-root">
      <div className="wiki-shell">
        <aside className="wiki-sidebar">
          <Link href="/wiki" className="wiki-sidebar-brand">
            <Crest className="h-6 w-6" />
            <span>Fall of Varrock Wiki</span>
          </Link>

          <WikiSearch items={searchItems} />

          <nav className="wiki-portlet">
            <div className="wiki-portlet-title">Navigation</div>
            <ul>
              <li><Link href="/wiki">Main page</Link></li>
              <li><Link href="/wiki/prices">Item prices</Link></li>
              <li><Link href="/wiki/random">Random page</Link></li>
              <li><Link href="/news">Game updates</Link></li>
              <li><Link href="/">Back to site</Link></li>
            </ul>
          </nav>

          {[...byCategory.entries()].map(([cat, list]) => (
            <nav key={cat} className="wiki-portlet">
              <div className="wiki-portlet-title">{cat}</div>
              <ul>
                {list.map((a) => (
                  <li key={a.slug}>
                    <Link href={`/wiki/${a.slug}`}>{a.title}</Link>
                  </li>
                ))}
              </ul>
            </nav>
          ))}
        </aside>

        <div className="wiki-page">
          <div className="wiki-mobilebar">
            <WikiSearch items={searchItems} />
          </div>
          {children}
        </div>
      </div>
    </div>
  );
}
