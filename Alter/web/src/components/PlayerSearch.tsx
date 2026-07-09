"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

/** "Find player" box - jumps straight to a player's profile. */
export function PlayerSearch() {
  const router = useRouter();
  const [name, setName] = useState("");

  function go(e: React.FormEvent) {
    e.preventDefault();
    const q = name.trim();
    if (q) router.push(`/player/${encodeURIComponent(q)}`);
  }

  return (
    <form onSubmit={go} className="relative w-full sm:w-64">
      <svg
        className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-lumbridge-parchment/40"
        viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
      >
        <circle cx="11" cy="11" r="7" /><line x1="21" y1="21" x2="16.65" y2="16.65" />
      </svg>
      <input
        value={name}
        onChange={(e) => setName(e.target.value)}
        placeholder="Find player…"
        aria-label="Find player"
        className="input py-2 pl-9 text-sm"
      />
    </form>
  );
}
