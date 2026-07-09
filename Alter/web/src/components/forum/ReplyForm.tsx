"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

export function ReplyForm({ threadId, locked }: { threadId: string; locked: boolean }) {
  const router = useRouter();
  const [body, setBody] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (locked) {
    return <p className="card text-sm text-lumbridge-parchment/60">🔒 This thread is locked.</p>;
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const res = await fetch("/api/forum/post", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ threadId, body }),
      });
      const data = await res.json();
      if (!res.ok) { setError(data.error ?? "Failed."); return; }
      setBody("");
      router.refresh();
    } catch { setError("Network error."); } finally { setBusy(false); }
  }

  return (
    <form onSubmit={submit} className="card space-y-3">
      <textarea className="input min-h-[110px]" placeholder="Write a reply (markdown supported)…" value={body} onChange={(e) => setBody(e.target.value)} required />
      {error && <p className="text-sm text-red-400">{error}</p>}
      <button className="btn-gold" disabled={busy}>{busy ? "Posting…" : "Reply"}</button>
    </form>
  );
}
