"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

export function NewThreadForm({ categorySlug }: { categorySlug: string }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const res = await fetch("/api/forum/thread", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ categorySlug, title, body }),
      });
      const data = await res.json();
      if (!res.ok) { setError(data.error ?? "Failed."); return; }
      router.push(`/forum/thread/${data.threadId}`);
    } catch { setError("Network error."); } finally { setBusy(false); }
  }

  if (!open) {
    return <button className="btn-gold" onClick={() => setOpen(true)}>New thread</button>;
  }

  return (
    <form onSubmit={submit} className="card space-y-3">
      <input className="input" placeholder="Thread title" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={140} required />
      <textarea className="input min-h-[140px]" placeholder="Write your post (markdown supported)…" value={body} onChange={(e) => setBody(e.target.value)} required />
      {error && <p className="text-sm text-red-400">{error}</p>}
      <div className="flex gap-2">
        <button className="btn-gold" disabled={busy}>{busy ? "Posting…" : "Create thread"}</button>
        <button type="button" className="btn-ghost" onClick={() => setOpen(false)}>Cancel</button>
      </div>
    </form>
  );
}
