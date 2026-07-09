"use client";

import { useState } from "react";

const PROVIDERS: { id: string; label: string }[] = [
  { id: "stripe", label: "Card" },
  { id: "paypal", label: "PayPal" },
  { id: "coinbase", label: "Crypto" },
];

export function BuyButtons({ packageId, loggedIn, devEnabled }: { packageId: string; loggedIn: boolean; devEnabled: boolean }) {
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function checkout(provider: string) {
    setBusy(provider);
    setError(null);
    try {
      const res = await fetch("/api/store/checkout", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ packageId, provider }),
      });
      const data = await res.json();
      if (!res.ok) { setError(data.error ?? "Checkout failed."); return; }
      window.location.href = data.url;
    } catch { setError("Network error."); } finally { setBusy(null); }
  }

  if (!loggedIn) {
    return <a href="/login" className="btn-ghost w-full">Log in to buy</a>;
  }

  return (
    <div className="space-y-2">
      <div className="grid grid-cols-3 gap-2">
        {PROVIDERS.map((p) => (
          <button key={p.id} className="btn-gold px-2 py-1 text-sm" disabled={busy !== null} onClick={() => checkout(p.id)}>
            {busy === p.id ? "…" : p.label}
          </button>
        ))}
      </div>
      {devEnabled && (
        <button className="btn-ghost w-full py-1 text-xs" disabled={busy !== null} onClick={() => checkout("dev")}>
          {busy === "dev" ? "…" : "Dev: grant instantly (local)"}
        </button>
      )}
      {error && <p className="text-xs text-red-400">{error}</p>}
    </div>
  );
}
