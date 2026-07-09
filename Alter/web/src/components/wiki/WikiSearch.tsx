"use client";

import { useRouter } from "next/navigation";
import { useMemo, useRef, useState } from "react";

export type WikiSearchItem = { slug: string; title: string; category: string };

export function WikiSearch({ items }: { items: WikiSearchItem[] }) {
  const router = useRouter();
  const [q, setQ] = useState("");
  const [sel, setSel] = useState(0);
  const [open, setOpen] = useState(false);
  const boxRef = useRef<HTMLDivElement>(null);

  const results = useMemo(() => {
    const needle = q.trim().toLowerCase();
    if (!needle) return [];
    const starts = items.filter((i) => i.title.toLowerCase().startsWith(needle));
    const contains = items.filter(
      (i) => !i.title.toLowerCase().startsWith(needle) && i.title.toLowerCase().includes(needle)
    );
    return [...starts, ...contains].slice(0, 8);
  }, [q, items]);

  function go(slug: string) {
    setQ("");
    setOpen(false);
    router.push(`/wiki/${slug}`);
  }

  return (
    <div className="wiki-search" ref={boxRef}>
      <input
        type="search"
        placeholder="Search the wiki"
        value={q}
        aria-label="Search the wiki"
        onChange={(e) => {
          setQ(e.target.value);
          setSel(0);
          setOpen(true);
        }}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
        onFocus={() => setOpen(true)}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown") { e.preventDefault(); setSel((s) => Math.min(s + 1, results.length - 1)); }
          else if (e.key === "ArrowUp") { e.preventDefault(); setSel((s) => Math.max(s - 1, 0)); }
          else if (e.key === "Enter" && results[sel]) { e.preventDefault(); go(results[sel].slug); }
          else if (e.key === "Escape") setOpen(false);
        }}
      />
      {open && results.length > 0 && (
        <div className="wiki-search-results">
          {results.map((r, i) => (
            <a
              key={r.slug}
              href={`/wiki/${r.slug}`}
              className={i === sel ? "wiki-sel" : undefined}
              onMouseDown={(e) => { e.preventDefault(); go(r.slug); }}
            >
              {r.title} <span className="wiki-search-cat">- {r.category}</span>
            </a>
          ))}
        </div>
      )}
    </div>
  );
}
