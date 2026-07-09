"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

export function GuideComposer() {
  const router = useRouter();
  const [title, setTitle] = useState("");
  const [summary, setSummary] = useState("");
  const [category, setCategory] = useState("General");
  const [body, setBody] = useState("");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setMsg(null);
    try {
      const res = await fetch("/api/admin/guides", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ title, summary, category, body }),
      });
      const data = await res.json();
      if (!res.ok) { setMsg(data.error ?? "Failed."); return; }
      setMsg(`Saved guide: ${data.slug}`);
      setTitle(""); setSummary(""); setBody("");
      router.refresh();
    } catch { setMsg("Network error."); } finally { setBusy(false); }
  }

  return (
    <form onSubmit={submit} className="space-y-4">
      <div className="grid gap-4 sm:grid-cols-2">
        <div>
          <label className="label">Title</label>
          <input className="input" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={140} required />
        </div>
        <div>
          <label className="label">Category</label>
          <input className="input" value={category} onChange={(e) => setCategory(e.target.value)} maxLength={40} />
        </div>
      </div>
      <div>
        <label className="label">Summary</label>
        <input className="input" value={summary} onChange={(e) => setSummary(e.target.value)} maxLength={300} />
      </div>
      <div>
        <label className="label">Body (markdown)</label>
        <textarea className="input min-h-[180px] font-mono text-sm" value={body} onChange={(e) => setBody(e.target.value)} required />
      </div>
      {msg && <p className="text-sm text-lumbridge-goldlight">{msg}</p>}
      <button className="btn-gold" disabled={busy}>{busy ? "Saving…" : "Publish guide"}</button>
    </form>
  );
}
