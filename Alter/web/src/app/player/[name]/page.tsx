import Link from "next/link";
import { notFound } from "next/navigation";
import { normalizeLogin } from "@/lib/accounts";
import { Collections } from "@/lib/collections";
import { getHiscores, getPlayerSkills, SKILLS, SkillName, titleCase } from "@/lib/hiscores";
import { Crest } from "@/components/Crest";

export const dynamic = "force-dynamic";

// Small accent dot per skill (no icon art available) - OSRS-inspired hues.
const SKILL_DOTS: Record<SkillName, string> = {
  attack: "#b91c1c", defence: "#60a5fa", strength: "#22c55e", hitpoints: "#f87171",
  ranged: "#84cc16", prayer: "#e7e0c9", magic: "#818cf8", cooking: "#a855f7",
  woodcutting: "#a16207", fletching: "#2dd4bf", fishing: "#38bdf8", firemaking: "#f59e0b",
  crafting: "#c084fc", smithing: "#94a3b8", mining: "#78716c", herblore: "#4ade80",
  agility: "#3b82f6", thieving: "#7c3aed", slayer: "#dc2626", farming: "#65a30d",
  runecraft: "#fbbf24", hunter: "#b45309", construction: "#d6d3d1",
};

function fmt(n: number): string {
  return n.toLocaleString();
}

export default async function PlayerPage({ params }: { params: { name: string } }) {
  const display = decodeURIComponent(params.name);
  const loginUsername = normalizeLogin(display);

  const accounts = await Collections.accounts();
  const account = await accounts.findOne({ loginUsername });
  if (!account) notFound();

  const skills = await getPlayerSkills(loginUsername);
  const membershipActive = account.membership?.expires != null && account.membership.expires > Date.now();

  // Overall rank on the hiscores ladder (0 = unranked).
  let overallRank = 0;
  if (skills) {
    const ladder = await getHiscores("overall");
    overallRank = ladder.findIndex((p) => p.loginUsername === loginUsername) + 1;
  }

  return (
    <div className="space-y-6">
      {/* Player header */}
      <div className="card relative overflow-hidden">
        <div
          className="pointer-events-none absolute inset-0 bg-gradient-to-br from-lumbridge-gold/[0.07] via-transparent to-transparent"
          aria-hidden
        />
        <div className="relative flex flex-wrap items-center gap-5">
          <Crest className="h-14 w-14 shrink-0 opacity-90" />
          <div className="min-w-0 flex-1">
            <div className="eyebrow">Adventurer of the Fallen City</div>
            <h1 className="mt-1 truncate font-display text-3xl font-bold text-lumbridge-parchment sm:text-4xl">
              {account.currentDisplayName}
            </h1>
            <div className="mt-2 flex flex-wrap gap-2 text-xs">
              {account.roles?.filter((r) => r !== "player").map((r) => (
                <span key={r} className="rounded bg-lumbridge-blood/40 px-2 py-0.5 capitalize text-lumbridge-parchment/90">{r}</span>
              ))}
              {membershipActive && (
                <span className="rounded bg-lumbridge-gold/30 px-2 py-0.5 capitalize text-lumbridge-goldlight">
                  {account.membership.tier} member
                </span>
              )}
              {(account.donorPoints ?? 0) > 0 && (
                <span className="rounded bg-lumbridge-moss/40 px-2 py-0.5 text-lumbridge-parchment/90">{account.donorPoints} donor pts</span>
              )}
            </div>
          </div>
          {skills && (
            <div className="flex items-center gap-6 sm:gap-8">
              <div className="text-right">
                <div className="text-3xl font-bold tabular-nums text-lumbridge-parchment">{fmt(skills.totalLevel)}</div>
                <div className="mt-0.5 text-[10px] uppercase tracking-wide text-lumbridge-parchmentdark">Total level</div>
                <div className="text-[10px] text-lumbridge-parchment/40">{fmt(skills.totalXp)} xp</div>
              </div>
              {overallRank > 0 && (
                <div className="border-l border-white/10 pl-6 text-right sm:pl-8">
                  <div
                    className={
                      "text-3xl font-bold tabular-nums " +
                      (overallRank === 1 ? "text-lumbridge-gold" : "text-lumbridge-parchment")
                    }
                  >
                    #{fmt(overallRank)}
                  </div>
                  <div className="mt-0.5 text-[10px] uppercase tracking-wide text-lumbridge-parchmentdark">Overall rank</div>
                  <Link href="/hiscores" className="text-[10px] text-lumbridge-gold hover:underline">
                    View ladder
                  </Link>
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      {/* Combat record */}
      {skills && (
        <div>
          <h2 className="eyebrow mb-2">Combat record</h2>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            {(
              [
                ["Kills", fmt(skills.pvp.kills)],
                ["Deaths", fmt(skills.pvp.deaths)],
                ["K/D", skills.pvp.kdr.toFixed(2)],
                ["Elo", fmt(skills.pvp.elo)],
              ] as const
            ).map(([label, value]) => (
              <div key={label} className="card card-hover py-4 text-center">
                <div className="text-2xl font-bold tabular-nums text-lumbridge-parchment">{value}</div>
                <div className="mt-1 text-[10px] uppercase tracking-wide text-lumbridge-parchmentdark">{label}</div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Duel Arena record (staked duels only) - hidden until they've duelled */}
      {skills && skills.duel.wins + skills.duel.losses + skills.duel.draws > 0 && (
        <div>
          <h2 className="eyebrow mb-2">Duel record</h2>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            {(
              [
                ["Duels won", fmt(skills.duel.wins)],
                ["Duels lost", fmt(skills.duel.losses)],
                ["Draws", fmt(skills.duel.draws)],
                ["Biggest pot", skills.duel.biggestPot > 0 ? `${fmt(skills.duel.biggestPot)} gp` : "-"],
              ] as const
            ).map(([label, value]) => (
              <div key={label} className="card card-hover py-4 text-center">
                <div className="text-2xl font-bold tabular-nums text-lumbridge-parchment">{value}</div>
                <div className="mt-1 text-[10px] uppercase tracking-wide text-lumbridge-parchmentdark">{label}</div>
              </div>
            ))}
          </div>
          <Link href="/duels" className="mt-2 inline-block text-xs text-lumbridge-gold hover:underline">
            Arena scoreboard →
          </Link>
        </div>
      )}

      {/* Skills */}
      {skills ? (
        <div>
          <h2 className="eyebrow mb-2">Skills</h2>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
            {SKILLS.map((s) => (
              <div key={s} className="card card-hover flex items-center justify-between py-3">
                <span className="flex min-w-0 items-center gap-2">
                  <span
                    className="h-2 w-2 shrink-0 rounded-full"
                    style={{ backgroundColor: SKILL_DOTS[s] }}
                    aria-hidden
                  />
                  <span className="truncate text-sm text-lumbridge-parchment/70">{titleCase(s)}</span>
                </span>
                <span className="text-right">
                  <span className="font-semibold tabular-nums text-lumbridge-parchment">{skills.skills[s].level}</span>
                  <span className="block text-[10px] tabular-nums text-lumbridge-parchmentdark">
                    {fmt(skills.skills[s].xp)} xp
                  </span>
                </span>
              </div>
            ))}
          </div>
        </div>
      ) : (
        <div className="card text-center text-lumbridge-parchment/60">
          This account hasn&apos;t logged into the game yet - no stats to show.
        </div>
      )}

      <Link href="/hiscores" className="inline-block text-sm text-lumbridge-gold hover:underline">
        ← Back to hiscores
      </Link>
    </div>
  );
}
