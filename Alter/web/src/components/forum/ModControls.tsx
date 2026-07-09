"use client";

import { useRouter } from "next/navigation";

export function ModControls({ threadId, pinned, locked }: { threadId: string; pinned: boolean; locked: boolean }) {
  const router = useRouter();

  async function act(action: string) {
    if (action === "delete" && !confirm("Delete this thread and all its posts?")) return;
    await fetch("/api/forum/moderate", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ threadId, action }),
    });
    if (action === "delete") router.push("/forum");
    else router.refresh();
  }

  return (
    <div className="flex flex-wrap gap-2 text-xs">
      <span className="text-lumbridge-parchment/40">Staff:</span>
      <button className="hover:text-lumbridge-gold" onClick={() => act(pinned ? "unpin" : "pin")}>{pinned ? "Unpin" : "Pin"}</button>
      <button className="hover:text-lumbridge-gold" onClick={() => act(locked ? "unlock" : "lock")}>{locked ? "Unlock" : "Lock"}</button>
      <button className="text-red-400 hover:text-red-300" onClick={() => act("delete")}>Delete</button>
    </div>
  );
}
