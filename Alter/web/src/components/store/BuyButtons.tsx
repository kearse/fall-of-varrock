"use client";

import { useState } from "react";

const PROVIDERS: { id: string; label: string }[] = [
  { id: "stripe", label: "Card" },
  { id: "paypal", label: "PayPal" },
  { id: "coinbase", label: "Crypto" },
];

export function BuyButtons({ packageId, loggedIn, devEnabled, providers }: { packageId: string; loggedIn: boolean; devEnabled: boolean; providers: string[] }) {
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
    return <a href="/login?next=/store" className="btn-ghost w-full">Log in to buy</a>;
  }

  // UX: don't render payment buttons that can only fail - pre-disable when no provider
  // is configured (mirrors the download cards' honest "Building…" state).
  const enabled = PROVIDERS.filter((p) => providers.includes(p.id));
  if (enabled.length === 0 && !devEnabled) {
    return (
      <button className="btn-ghost w-full cursor-not-allowed opacity-60" disabled>
        Payments open soon
      </button>
    );
  }

  return (
    <div className="space-y-2">
      <div className={`grid gap-2 ${enabled.length === 3 ? "grid-cols-3" : enabled.length === 2 ? "grid-cols-2" : "grid-cols-1"}`}>
        {enabled.map((p) => (
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
