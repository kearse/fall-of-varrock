import { getSession } from "@/lib/session";
import { VotePanel } from "@/components/vote/VotePanel";
import { Reveal } from "@/components/Reveal";
import { DEFAULT_COOLDOWN_HOURS } from "@/lib/vote";

export const metadata = { title: "Vote · Fall of Varrock" };

const STEPS = [
  {
    n: "01",
    title: "Enter your login name",
    body: "Type your exact in-game name below so the toplists know which soldier to credit.",
  },
  {
    n: "02",
    title: "Vote on each site",
    body: `Each toplist opens in a new tab. Every site has its own ${DEFAULT_COOLDOWN_HOURS}-hour cooldown, so you can vote on all of them, twice a day.`,
  },
  {
    n: "03",
    title: "Get paid in-game",
    body: "Once the toplist confirms your vote, your Vote Tickets are delivered on your next login — or grab them right away with ::claimvote.",
  },
];

const REWARDS = [
  { icon: "🎟️", label: "Vote points", body: "A tradeable currency spent at the vote store in Lumbridge." },
  { icon: "⚔️", label: "War-effort gear", body: "Cosmetics, consumables and supply crates for the front lines." },
  { icon: "📈", label: "A bigger army", body: "Every vote pushes us up the toplists and recruits new players to the war." },
];

export default async function VotePage({
  searchParams,
}: {
  searchParams?: { name?: string };
}) {
  // ?name= lets the in-game ::vote command deep-link with the player's login
  // pre-filled (they may not be signed in to the site in their browser).
  const session = await getSession();
  const defaultName = searchParams?.name?.trim().slice(0, 12) || session?.name || "";
  return (
    <div className="space-y-14">
      {/* ============ HERO ============ */}
      <section className="relative overflow-hidden rounded-3xl border border-lumbridge-gold/15 bg-gradient-to-b from-lumbridge-gold/[0.07] via-transparent to-transparent px-6 py-12 text-center md:py-16">
        <div className="pointer-events-none absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-lumbridge-gold/50 to-transparent" />
        <Reveal>
          <div className="eyebrow">Support the war effort</div>
          <h1 className="mt-3 text-4xl font-bold md:text-5xl">
            Vote for <span className="text-gradient">Fall of Varrock</span>
          </h1>
          <p className="mx-auto mt-4 max-w-2xl text-sm text-lumbridge-parchment/65 md:text-base">
            Varrock has fallen, and armies are built one recruit at a time. Every vote climbs us up
            the toplists, brings fresh soldiers to Gielinor, and pays you in vote points to spend
            in-game. It takes about a minute, every {DEFAULT_COOLDOWN_HOURS} hours.
          </p>
          <div className="mt-6 flex flex-wrap items-center justify-center gap-2">
            <span className="chip text-lumbridge-ember">🎟️ Earn vote points</span>
            <span className="chip text-lumbridge-parchmentdark">⏱️ {DEFAULT_COOLDOWN_HOURS}h cooldown per site</span>
            <span className="chip text-lumbridge-green">📈 Climb the toplists</span>
          </div>
        </Reveal>
        <div className="pointer-events-none absolute inset-x-0 bottom-0 h-px bg-gradient-to-r from-transparent via-lumbridge-gold/50 to-transparent" />
      </section>

      {/* ============ VOTE PANEL (username + site cards) ============ */}
      <section>
        <Reveal>
          <VotePanel defaultName={defaultName} />
        </Reveal>
      </section>

      {/* ============ HOW IT WORKS ============ */}
      <section className="space-y-8">
        <Reveal className="text-center">
          <div className="eyebrow">The drill</div>
          <h2 className="mt-2 text-3xl font-bold">
            How voting <span className="text-gradient">works</span>
          </h2>
        </Reveal>
        <div className="grid gap-5 md:grid-cols-3">
          {STEPS.map((s, i) => (
            <Reveal key={s.n} delay={i * 90}>
              <div className="card h-full">
                <div className="mb-3 text-3xl font-bold text-lumbridge-gold/30">{s.n}</div>
                <h3 className="mb-1.5 font-bold text-white">{s.title}</h3>
                <p className="text-sm text-lumbridge-parchment/65">{s.body}</p>
              </div>
            </Reveal>
          ))}
        </div>
      </section>

      {/* ============ WHAT YOU GET ============ */}
      <section>
        <Reveal>
          <div className="card mx-auto max-w-4xl">
            <div className="mb-5 text-center">
              <div className="eyebrow">The bounty</div>
              <h2 className="mt-2 text-2xl font-bold">
                What you <span className="text-gradient">get</span>
              </h2>
            </div>
            <div className="grid gap-5 sm:grid-cols-3">
              {REWARDS.map((r) => (
                <div key={r.label} className="text-center">
                  <div className="mx-auto mb-3 grid h-12 w-12 place-items-center rounded-xl border border-lumbridge-gold/20 bg-lumbridge-gold/[0.06] text-2xl">
                    {r.icon}
                  </div>
                  <h3 className="mb-1 text-sm font-bold text-white">{r.label}</h3>
                  <p className="text-xs text-lumbridge-parchment/60">{r.body}</p>
                </div>
              ))}
            </div>
            <p className="mt-6 text-center text-xs text-lumbridge-parchment/45">
              Votes are verified by the toplists before points are granted. One account per player,
              per site, per cooldown. Fall of Varrock is community-driven, and your votes keep the
              realm growing.
            </p>
          </div>
        </Reveal>
      </section>
    </div>
  );
}
