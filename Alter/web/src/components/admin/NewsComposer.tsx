"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

const CATEGORIES = ["update", "announcement", "patch", "event"] as const;

export function NewsComposer() {
  const router = useRouter();
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [category, setCategory] = useState<(typeof CATEGORIES)[number]>("update");
  const [postToDiscord, setPostToDiscord] = useState(true);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();

    // Standard process: confirm the Discord push before it goes out to everyone.
    if (postToDiscord && !window.confirm(`Post "${title}" to Discord #announcements now? Everyone in the server will see it.`)) {
      return;
    }

    setBusy(true);
    setMsg(null);
    try {
      const res = await fetch("/api/admin/news", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ title, body, category, postToDiscord }),
      });
      const data = await res.json();
      if (!res.ok) {
        setMsg(data.error ?? "Failed to publish.");
        return;
      }
      setMsg(
        data.pushToDiscord
          ? `Published! ${data.discordPosted ? "Posted to Discord." : "Queued - the bot will post it to Discord shortly."}`
          : "Published to the site only (not sent to Discord).",
      );
      setTitle("");
      setBody("");
      router.refresh();
    } catch {
      setMsg("Network error.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="space-y-4">
      <div>
        <label className="label">Title</label>
        <input className="input" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={140} required />
      </div>
      <div>
        <label className="label">Category</label>
        <select className="input" value={category} onChange={(e) => setCategory(e.target.value as typeof category)}>
          {CATEGORIES.map((c) => (
            <option key={c} value={c} className="bg-lumbridge-stone2 capitalize">{c}</option>
          ))}
        </select>
      </div>
      <div>
        <label className="label">Explain the update (markdown supported)</label>
        <textarea
          className="input min-h-[160px] font-mono text-sm"
          value={body}
          onChange={(e) => setBody(e.target.value)}
          placeholder="What changed, and why it matters to players. This is exactly what gets posted to Discord."
        />
      </div>
      <label className="flex items-start gap-2 text-sm text-lumbridge-parchment/80">
        <input
          type="checkbox"
          className="mt-1"
          checked={postToDiscord}
          onChange={(e) => setPostToDiscord(e.target.checked)}
        />
        <span>
          Post this update to <strong>Discord #announcements</strong>
          <span className="block text-xs text-lumbridge-parchment/50">
            Leave unchecked to publish to the site only. You&apos;ll be asked to confirm before it&apos;s sent.
          </span>
        </span>
      </label>
      {msg && <p className="text-sm text-lumbridge-goldlight">{msg}</p>}
      <button className="btn-gold" disabled={busy}>
        {busy ? "Publishing…" : postToDiscord ? "Publish & post to Discord" : "Publish (site only)"}
      </button>
    </form>
  );
}
