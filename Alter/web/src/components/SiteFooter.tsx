import Link from "next/link";

export function SiteFooter() {
  const forum = process.env.FORUM_URL || "/forum";
  const guides = process.env.FORUM_URL ? `${process.env.FORUM_URL}/category/13` : "/guides";
  const COLS: { title: string; links: { href: string; label: string }[] }[] = [
    {
      title: "Game",
      links: [
        { href: "/play", label: "Play now" },
        { href: "/hiscores", label: "Hiscores" },
        { href: "/store", label: "Store" },
        { href: "/vote", label: "Vote" },
      ],
    },
    {
      title: "Community",
      links: [
        { href: forum, label: "Forum" },
        { href: "/wiki", label: "Wiki" },
        { href: guides, label: "Guides" },
        { href: "/news", label: "News" },
        { href: "/account", label: "My account" },
      ],
    },
    {
      title: "Support",
      links: [
        { href: forum, label: "Help & Support" },
        { href: forum, label: "Report a bug" },
        { href: forum, label: "Appeals" },
        { href: guides, label: "Guides & Rules" },
      ],
    },
  ];
  return (
    <footer className="mt-20 border-t border-white/10 bg-black/30">
      <div className="mx-auto grid w-full max-w-6xl gap-8 px-4 py-12 sm:grid-cols-2 lg:grid-cols-4">
        <div className="space-y-3">
          <div className="flex items-center">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/img/logo-text-trim.png" alt="Fall of Varrock" className="h-8 w-auto" />
          </div>
          <p className="text-sm text-lumbridge-parchment/55">
            Varrock has fallen and Gielinor lies in ruin. A community RuneScape private server of
            war campaigns, companions, deep skilling and real progression - bring back order.
          </p>
        </div>
        {COLS.map((col) => (
          <div key={col.title}>
            <h4 className="mb-3 text-sm font-semibold uppercase tracking-wider text-lumbridge-gold/80">{col.title}</h4>
            <ul className="space-y-2">
              {col.links.map((l) => (
                <li key={l.label}>
                  <Link href={l.href} className="text-sm text-lumbridge-parchment/65 hover:text-lumbridge-gold">
                    {l.label}
                  </Link>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>
      <div className="border-t border-white/5 py-5">
        <div className="mx-auto flex w-full max-w-6xl flex-col items-center gap-3 px-4 text-xs text-lumbridge-parchment/40 sm:flex-row sm:justify-between">
          <span>© {new Date().getFullYear()} Fall of Varrock. Not affiliated with Jagex Ltd.</span>
          <nav className="flex flex-wrap items-center justify-center gap-x-4 gap-y-1">
            <Link href="/legal/terms" className="hover:text-lumbridge-gold">Terms</Link>
            <Link href="/legal/privacy" className="hover:text-lumbridge-gold">Privacy</Link>
            <Link href="/legal/refund" className="hover:text-lumbridge-gold">Refunds</Link>
          </nav>
        </div>
      </div>
    </footer>
  );
}
