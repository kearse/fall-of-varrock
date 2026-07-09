"use client";

import { useEffect, useState } from "react";

interface Status {
  linked: boolean;
  discordTag: string | null;
  pendingCode: string | null;
}

export function DiscordLinkPanel() {
  const [status, setStatus] = useState<Status | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function refresh() {
    const res = await fetch("/api/discord/link");
    if (res.ok) setStatus(await res.json());
  }

  useEffect(() => { refresh(); }, []);

  async function generate() {
    setBusy(true);
    setError(null);
    try {
      const res = await fetch("/api/discord/link", { method: "POST" });
      const data = await res.json();
      if (!res.ok) { setError(data.error ?? "Failed."); return; }
      await refresh();
    } catch { setError("Network error."); } finally { setBusy(false); }
  }

  if (!status) return <p className="text-sm text-lumbridge-parchment/50">Loading…</p>;

  if (status.linked) {
    return (
      <div className="space-y-1">
        <p className="text-sm">
          ✅ Linked to Discord{status.discordTag ? <> as <strong>{status.discordTag}</strong></> : null}.
        </p>
        <p className="text-xs text-lumbridge-parchment/50">
          Your donor/membership roles sync automatically. Use <code className="rounded bg-black/40 px-1">/unlink</code> in Discord to disconnect.
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {status.pendingCode ? (
        <div className="rounded-md border border-lumbridge-gold/40 bg-black/30 p-4 text-center">
          <div className="text-xs text-lumbridge-parchment/60">Your linking code</div>
          <div className="my-1 font-mono text-2xl tracking-widest text-lumbridge-gold">{status.pendingCode}</div>
          <div className="text-xs text-lumbridge-parchment/60">
            In the Fall of Varrock Discord, run <code className="rounded bg-black/40 px-1">/link {status.pendingCode}</code>
          </div>
        </div>
      ) : (
        <p className="text-sm text-lumbridge-parchment/70">
          Link your game account to Discord to get your donor/member roles and access linked-only channels.
        </p>
      )}
      {error && <p className="text-sm text-red-400">{error}</p>}
      <button className="btn-gold" onClick={generate} disabled={busy}>
        {busy ? "…" : status.pendingCode ? "Generate a new code" : "Generate linking code"}
      </button>
    </div>
  );
}
