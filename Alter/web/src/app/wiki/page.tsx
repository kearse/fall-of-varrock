import Link from "next/link";
import { getArticlesByCategory } from "@/lib/wiki";

export const metadata = { title: "Fall of Varrock Wiki" };
export const dynamic = "force-dynamic";

export default function WikiMainPage() {
  const byCategory = getArticlesByCategory();
  const total = [...byCategory.values()].reduce((n, list) => n + list.length, 0);

  return (
    <div>
      <h1 className="wiki-title">Fall of Varrock Wiki</h1>
      <p className="wiki-tagline">The official wiki - {total} articles, maintained by the dev team</p>

      <div className="wiki-mainbox">
        <div className="wiki-mainbox-header">Welcome to the Fall of Varrock Wiki</div>
        <div className="wiki-mainbox-body">
          <p style={{ margin: 0 }}>
            The official reference for how this server actually works - the War, minigames, unlocks, the
            economy and skilling. Articles are updated the same day features ship, so the wiki always matches
            the live game. New here? Start with{" "}
            <Link href="/wiki/welcome-to-the-kingdom">Welcome to the Kingdom</Link> and{" "}
            <Link href="/wiki/recruit-trials">Recruit Trials</Link>. Looking for the spellbooks? See{" "}
            <Link href="/wiki/unlocking-spellbooks">Unlocking the magic books</Link>.
          </p>
        </div>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(19rem, 1fr))", gap: "1rem" }}>
        {[...byCategory.entries()].map(([cat, list]) => (
          <section key={cat} className="wiki-mainbox" style={{ marginBottom: 0 }}>
            <div className="wiki-mainbox-header">{cat}</div>
            <div className="wiki-mainbox-body">
              <ul>
                {list.map((a) => (
                  <li key={a.slug}>
                    <Link href={`/wiki/${a.slug}`}>{a.title}</Link>
                    {a.summary && <div className="wiki-item-summary">{a.summary}</div>}
                  </li>
                ))}
              </ul>
            </div>
          </section>
        ))}
      </div>
    </div>
  );
}
