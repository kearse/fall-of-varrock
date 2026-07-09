import { Collections } from "@/lib/collections";

// Live presence is the `online` collection, maintained by the game: a doc per
// logged-in player (insert on login, delete on logout, wiped on boot - see the
// Kotlin DiscordIntegrationPlugin / DiscordBridge). `online` is null only if the
// DB read fails, which we render as "offline".
async function getStats(): Promise<{ online: number | null; players: number }> {
  try {
    const [onlineCol, accounts] = await Promise.all([Collections.online(), Collections.accounts()]);
    const [online, players] = await Promise.all([
      onlineCol.countDocuments(),
      accounts.estimatedDocumentCount(),
    ]);
    return { online, players };
  } catch {
    return { online: null, players: 0 };
  }
}

export async function ServerStatus() {
  const { online, players } = await getStats();
  const live = online != null;
  return (
    <div className="inline-flex items-center gap-4 rounded-lg border border-white/10 bg-black/30 px-4 py-2 text-sm">
      <span className="flex items-center gap-2">
        <span
          className={`h-2 w-2 rounded-full ${live ? "bg-lumbridge-moss animate-pulse" : "bg-red-500/70"}`}
        />
        {live ? "World online" : "World offline"}
      </span>
      {live && (
        <span className="text-lumbridge-parchment/70">
          <span className="font-semibold text-lumbridge-goldlight">{online.toLocaleString()}</span> playing
        </span>
      )}
      <span className="text-lumbridge-parchment/50">{players.toLocaleString()} accounts</span>
    </div>
  );
}
