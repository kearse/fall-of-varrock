import Link from "next/link";
import { notFound } from "next/navigation";
import { getArticle, getAllArticles } from "@/lib/wiki";
import { renderWikiMarkdown, renderWikiInline } from "@/lib/wiki-render";

export const dynamic = "force-dynamic";

export function generateMetadata({ params }: { params: { slug: string } }) {
  const article = getArticle(params.slug);
  return { title: article ? `${article.title} - Fall of Varrock Wiki` : "Fall of Varrock Wiki" };
}

export default function WikiArticlePage({ params }: { params: { slug: string } }) {
  const article = getArticle(params.slug);
  if (!article) notFound();
  const { html, toc } = renderWikiMarkdown(article.body);

  // Same-category siblings for an auto "See also" footer (getAllArticles is pre-sorted by order, title).
  const seeAlso = getAllArticles().filter((a) => a.category === article.category && a.slug !== article.slug);

  // MediaWiki-style section numbering: 1, 1.1, 2 ...
  let major = 0;
  let minor = 0;
  const numbered = toc.map((t) => {
    if (t.level === 2) { major++; minor = 0; return { ...t, num: `${major}` }; }
    minor++;
    return { ...t, num: `${major}.${minor}` };
  });

  return (
    <article>
      <h1 className="wiki-title">{article.title}</h1>
      <div className="wiki-tagline">From the Fall of Varrock Wiki - {article.summary}</div>

      {article.infobox.length > 0 && (
        <aside className="wiki-infobox">
          <div className="wiki-infobox-title">{article.title}</div>
          <table>
            <tbody>
              {article.infobox.map(([k, v]) => (
                <tr key={k}>
                  <th>{k}</th>
                  <td dangerouslySetInnerHTML={{ __html: renderWikiInline(v) }} />
                </tr>
              ))}
            </tbody>
          </table>
        </aside>
      )}

      {numbered.length >= 3 && (
        <nav className="wiki-toc">
          <div className="wiki-toc-title">Contents</div>
          <ol>
            {numbered.map((t) => (
              <li key={t.id} className={t.level === 3 ? "wiki-toc-sub" : undefined}>
                <span className="wiki-toc-num">{t.num}</span>
                <a href={`#${t.id}`}>{t.text}</a>
              </li>
            ))}
          </ol>
        </nav>
      )}

      <div className="wiki-content" dangerouslySetInnerHTML={{ __html: html }} />

      {seeAlso.length > 0 && (
        <nav className="wiki-seealso">
          <div className="wiki-seealso-title">See also</div>
          <ul>
            {seeAlso.map((a) => (
              <li key={a.slug}>
                <Link href={`/wiki/${a.slug}`}>{a.title}</Link>
              </li>
            ))}
          </ul>
        </nav>
      )}

      <div className="wiki-catbar">
        <Link href="/wiki">Wiki</Link> › {article.category}
      </div>
      {article.updated && <p className="wiki-lastedit">This page was last edited on {article.updated}.</p>}
    </article>
  );
}
