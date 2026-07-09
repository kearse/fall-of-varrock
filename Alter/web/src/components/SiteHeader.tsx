"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useState } from "react";
import type { SessionPayload } from "@/lib/session";

export function SiteHeader({ session, forumUrl = "/forum", guidesUrl = "/guides" }: { session: SessionPayload | null; forumUrl?: string; guidesUrl?: string }) {
  const forumExternal = forumUrl.startsWith("http");
  const NAV = [
    { href: "/news", label: "News" },
    { href: "/wiki", label: "Wiki" },
    { href: "/hiscores", label: "Hiscores" },
    { href: forumUrl, label: "Forum", external: forumExternal },
    { href: guidesUrl, label: "Guides", external: guidesUrl.startsWith("http") },
    { href: "/store", label: "Store" },
    { href: "/vote", label: "Vote" },
    { href: "/play", label: "Play" },
  ];
  const router = useRouter();
  const pathname = usePathname();
  const [open, setOpen] = useState(false);
  const isStaff = session?.roles?.some((r) => ["admin", "moderator", "developer"].includes(r));

  async function logout() {
    await fetch("/api/auth/logout", { method: "POST" });
    router.refresh();
  }

  const active = (href: string) => pathname === href || pathname.startsWith(href + "/");

  return (
    <header className="sticky top-0 z-50 border-b border-white/10 bg-lumbridge-stone/60 backdrop-blur-xl">
      <div className="border-b border-white/5">
        <div className="mx-auto flex h-9 w-full max-w-6xl items-center justify-between px-4 text-xs">
          <div className="flex items-center gap-4 text-lumbridge-parchment/55">
            <span className="hidden sm:inline">⚔ OSRS Revision 228</span>
            <Link href="/play" className="link-cyan">Download client</Link>
          </div>
          <div className="flex items-center gap-4">
            <a href={process.env.NEXT_PUBLIC_DISCORD_URL || "https://discord.gg"} target="_blank" rel="noopener noreferrer" className="link-cyan">Discord</a>
            <Link href="/vote" className="link-cyan">Vote</Link>
            <Link href="/store" className="font-semibold text-gradient-gold">Donate</Link>
          </div>
        </div>
      </div>

      <div className="mx-auto flex h-16 w-full max-w-6xl items-center gap-6 px-4">
        <Link href="/" className="group flex items-center">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src="/img/logo-text-trim.png" alt="Fall of Varrock" className="h-8 w-auto transition-transform group-hover:scale-[1.03]" />
        </Link>

        <nav className="ml-2 hidden items-center gap-1 lg:flex">
          {NAV.map((n) => (
            <Link key={n.href} href={n.href}
              className={"rounded-lg px-3 py-1.5 text-sm font-medium transition-all " +
                (active(n.href)
                  ? "bg-lumbridge-gold/12 text-white shadow-[inset_0_0_0_1px_rgba(220,38,38,0.45)]"
                  : "text-lumbridge-parchment/70 hover:bg-white/5 hover:text-white")}>
              {n.label}
            </Link>
          ))}
          {isStaff && (
            <Link href="/admin"
              className={"rounded-lg px-3 py-1.5 text-sm font-medium transition-all " +
                (active("/admin") ? "bg-lumbridge-pink/20 text-lumbridge-pink" : "text-lumbridge-pink/80 hover:bg-white/5")}>
              Admin
            </Link>
          )}
        </nav>

        <div className="ml-auto flex items-center gap-2">
          {session ? (
            <>
              <Link href="/account" className="hidden items-center gap-2 rounded-lg px-2 py-1 text-sm font-medium hover:text-white sm:flex">
                <span className="grid h-7 w-7 place-items-center rounded-full bg-gold-gradient text-xs font-bold text-white">
                  {session.name.charAt(0).toUpperCase()}
                </span>
                {session.name}
              </Link>
              <button onClick={logout} className="btn-ghost px-3 py-1.5 text-sm">Log out</button>
            </>
          ) : (
            <>
              <Link href="/login" className="px-2 py-1 text-sm font-medium text-lumbridge-parchment/80 hover:text-white">Log in</Link>
              <Link href="/register" className="btn-primary px-4 py-1.5 text-sm">Register</Link>
            </>
          )}
          <button className="ml-1 rounded-lg p-2 text-lumbridge-parchment/80 hover:bg-white/5 lg:hidden" onClick={() => setOpen((o) => !o)} aria-label="Menu">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <line x1="3" y1="6" x2="21" y2="6" /><line x1="3" y1="12" x2="21" y2="12" /><line x1="3" y1="18" x2="21" y2="18" />
            </svg>
          </button>
        </div>
      </div>

      {open && (
        <nav className="flex flex-col gap-1 border-t border-white/10 px-4 py-3 lg:hidden">
          {NAV.map((n) => (
            <Link key={n.href} href={n.href} onClick={() => setOpen(false)}
              className={"rounded-lg px-3 py-2 text-sm " + (active(n.href) ? "bg-white/10 text-white" : "text-lumbridge-parchment/80 hover:bg-white/5")}>
              {n.label}
            </Link>
          ))}
          {isStaff && <Link href="/admin" onClick={() => setOpen(false)} className="rounded-lg px-3 py-2 text-sm text-lumbridge-pink">Admin</Link>}
        </nav>
      )}
    </header>
  );
}
