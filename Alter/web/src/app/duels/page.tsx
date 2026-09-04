import Link from "next/link";
import { Collections } from "@/lib/collections";

export const dynamic = "force-dynamic";

// The Duel Arena scoreboard - the classic arena wall boards (last 50 duels),
// virtualized. Fed by the game's DuelLog on every resolved staked duel;
// exhibition/tournament matches are never listed. `::duels` in-game shows the
// same feed.

const BOARD_SIZE = 50;

function fmt(n: number): string {
  return n.toLocaleString();
}

function ago(at: number): string {
  const mins = Math.floor((Date.now() - at) / 60_000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins}m ago`;
  if (mins < 60 * 24) return `${Math.floor(mins / 60)}h ago`;
  return `${Math.floor(mins / (60 * 24))}d ago`;
}

function endLabel(end: string): { text: string; draw: boolean } {
  switch (end) {
    case "forfeit":
      return { text: "forfeit", draw: false };
    case "logout":
      return { text: "fled", draw: false };
    case "draw-timeout":
      return { text: "draw - time expired", draw: true };
    case "draw-double-ko":
      return { text: "draw - double KO", draw: true };
    case "draw-vanished":
      return { text: "draw - connection lost", draw: true };
    default:
      return { text: "defeated", draw: false };
  }
}

export default async function DuelsPage() {
  let rows: Awaited<ReturnType<typeof loadRows>> = [];
  try {
    rows = await loadRows();
  } catch {
    // No database reachable - render the empty board rather than erroring.
  }

  return (
    <div className="space-y-6">
      <div className="card">
        <div className="eyebrow">Duel Arena</div>
        <h1 className="mt-1 font-display text-3xl font-bold text-lumbridge-parchment">Scoreboard</h1>
        <p className="mt-2 text-sm text-lumbridge-parchment/70">
          The last {BOARD_SIZE} staked duels, straight from the arena. Winner takes the whole pot;
          draws send both stakes home. Check your own record in-game with{" "}
          <code className="rounded bg-black/30 px-1">::duels</code>.
        </p>
      </div>

      {rows.length === 0 ? (
        <div className="card text-center text-lumbridge-parchment/60">
          No duels recorded yet - the board is waiting for its first stake.
        </div>
      ) : (
        <div className="card overflow-x-auto p-0">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-white/10 text-left text-[10px] uppercase tracking-wide text-lumbridge-parchmentdark">
                <th className="px-4 py-3">Winner</th>
                <th className="px-4 py-3">Result</th>
                <th className="px-4 py-3">Loser</th>
                <th className="px-4 py-3 text-right">Pot</th>
                <th className="hidden px-4 py-3 sm:table-cell">Rules</th>
                <th className="px-4 py-3 text-right">When</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((d, i) => {
                const end = endLabel(d.end);
                return (
                  <tr key={i} className="border-b border-white/5 last:border-0">
                    <td className="px-4 py-2.5">
                      <Link href={`/player/${encodeURIComponent(d.winner)}`} className="text-lumbridge-gold hover:underline">
                        {d.winner}
                      </Link>
                    </td>
                    <td className={"px-4 py-2.5 " + (end.draw ? "text-lumbridge-parchment/50" : "text-lumbridge-parchment/80")}>
                      {end.text}
                    </td>
                    <td className="px-4 py-2.5">
                      <Link href={`/player/${encodeURIComponent(d.loser)}`} className="text-lumbridge-parchment/80 hover:underline">
                        {d.loser}
                      </Link>
                    </td>
                    <td className="px-4 py-2.5 text-right tabular-nums text-lumbridge-parchment">
                      {d.potValue > 0 ? `${fmt(d.potValue)} gp` : "-"}
                    </td>
                    <td className="hidden max-w-[16rem] truncate px-4 py-2.5 text-lumbridge-parchment/50 sm:table-cell" title={d.rules}>
                      {d.rules}
                    </td>
                    <td className="px-4 py-2.5 text-right text-lumbridge-parchment/50">{ago(d.at)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      <Link href="/wiki/duel-arena-staking" className="inline-block text-sm text-lumbridge-gold hover:underline">
        How duelling &amp; staking works →
      </Link>
    </div>
  );
}

async function loadRows() {
  const duels = await Collections.duels();
  return duels.find({}).sort({ at: -1 }).limit(BOARD_SIZE).toArray();
}
