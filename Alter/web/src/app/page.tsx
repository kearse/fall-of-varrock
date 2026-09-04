import Link from "next/link";
import { getPublishedNews } from "@/lib/news";
import { stripMarkdown } from "@/lib/markdown";
import { Reveal } from "@/components/Reveal";
import { HeroScene } from "@/components/HeroScene";
import { Collections } from "@/lib/collections";

export const dynamic = "force-dynamic";

async function getStats() {
  try {
    const online = await Collections.online();
    const onlineNow = await online.countDocuments();
    return { onlineNow };
  } catch {
    return { onlineNow: 0 };
  }
}

const FLOATERS = [
  { img: "item-whip", cls: "left-[6%] top-[18%] h-12 w-12", delay: "0s" },
  { img: "item-tbow", cls: "right-[10%] top-[12%] h-16 w-16", delay: "1.2s" },
  { img: "item-ags", cls: "left-[16%] bottom-[14%] h-14 w-14", delay: "0.6s" },
  { img: "item-bandos", cls: "right-[16%] bottom-[18%] h-12 w-12", delay: "1.8s" },
  { img: "item-dclaws", cls: "right-[34%] top-[8%] h-10 w-10", delay: "2.2s" },
];

// The feudal ladder (from content/wiki/titles-and-citizenship.md). Knight tops out at
// rune; Lord and up wear every armour and gain the war-command powers.
const RANKS = [
  { name: "Peasant", cost: "Free", unlock: "Bronze & Iron armour" },
  { name: "Commoner", cost: "10k", unlock: "Steel armour" },
  { name: "Squire", cost: "50k", unlock: "Black armour, coloured name" },
  { name: "Soldier", cost: "150k", unlock: "Mithril & Adamant armour" },
  { name: "Knight", cost: "500k", unlock: "Rune armour, 1 companion" },
  { name: "Lord", cost: "2M", unlock: "All armour, field troops, 2 companions", commander: true },
  { name: "Minister", cost: "10M", unlock: "Launch war campaigns, 3 companions", commander: true },
  { name: "King", cost: "50M", unlock: "Launch conquests, lead the realm", commander: true },
];

const WAR_STEPS = [
  { n: "1", title: "Rally an army", body: "Sponsor an offensive and up to 64 allied knights spawn and fight at your side." },
  { n: "2", title: "Storm a fallen city", body: "March on the enemy holding it and cut down everything in your path." },
  { n: "3", title: "Split the spoils", body: "A shared war chest pays out by contribution, straight to your bank." },
];

const COMPANION_LOOP = ["Recruit", "Train", "Gear", "Command"];
const COMPANION_ARCHETYPES = ["Melee", "Ranged", "Magic"];
const COMPANION_PERKS = [
  "Train them in real combat: a seasoned companion hits measurably harder.",
  "Gear them from your own bank, and revive them with every level and item intact.",
  "Take them anywhere: bosses, the wilderness, minigames and the war.",
  "Their damage counts toward your share of boss and war loot.",
];

// The wider content grid. Easy to keep adding to as new features ship.
const MORE_FEATURES = [
  { icon: "⚔️", name: "Wizard Tower", body: "Storm the tower with the Void Knight — solo or in a team of five." },
  { icon: "🐉", name: "World Boss", body: "Rally the town against the Corporeal Beast and split its spoils." },
  { icon: "⛏️", name: "Full Skilling", body: "Mining, smithing, fishing, farming, agility and beyond." },
  { icon: "☠️", name: "Slayer", body: "Task-based combat with points, unlocks and gear rewards." },
  { icon: "🏴", name: "Wilderness PvP", body: "Deep wilderness, loot keys and real risk versus reward." },
  { icon: "📜", name: "Custom Quests", body: "Story quests that unlock the Ancient, Lunar and Arceuus spellbooks." },
  { icon: "📈", name: "Grand Exchange", body: "A player-driven market for everything worth trading." },
  { icon: "🌀", name: "Teleport Portal", body: "One-tap travel to every corner of the kingdom." },
  { icon: "🎲", name: "Duel Arena", body: "Challenge anyone in a safe zone and stake it all in a classic one-versus-one." },
];

const STEPS = [
  { num: "1", title: "Create your account", body: "One login for the website, the forum and the game. It takes under a minute." },
  { num: "2", title: "Download the client", body: "Grab our custom client and sign in with that same account." },
  { num: "3", title: "Log in and play", body: "Spawn in Lumbridge, learn the ropes and start your climb." },
];

export default async function HomePage() {
  const [{ onlineNow }, news] = await Promise.all([getStats(), getPublishedNews(3)]);
  const forumUrl = process.env.FORUM_URL || "/forum";
  const discordUrl = process.env.NEXT_PUBLIC_DISCORD_URL || "https://discord.gg/AmtccSBKYz";

  return (
    <div className="space-y-24">
      {/* ============ HERO ============ */}
      <section className="relative left-1/2 right-1/2 -mx-[50vw] -mt-10 w-screen overflow-hidden">
        <div className="pointer-events-none absolute inset-0">
          <HeroScene className="h-full w-full" />
          <div className="absolute inset-0 bg-cover bg-center bg-no-repeat" style={{ backgroundImage: "url(/img/hero-bg.jpg)" }} />
          <div className="absolute inset-0 bg-[#080506]/40" />
          <div className="absolute inset-0 bg-gradient-to-b from-[#080506]/85 via-[#080506]/35 to-[#080506]" />
          <div className="absolute inset-0 bg-[radial-gradient(ellipse_72%_58%_at_50%_50%,rgba(8,5,6,0.5),transparent_72%)]" />
          <div className="absolute inset-0 bg-[radial-gradient(900px_420px_at_50%_0%,rgba(220,38,38,0.14),transparent_70%)]" />
        </div>

        <div className="pointer-events-none absolute inset-0">
          {FLOATERS.map((f) => (
            // eslint-disable-next-line @next/next/no-img-element
            <img key={f.img} src={`/img/${f.img}.gif`} alt="" style={{ animationDelay: f.delay }}
              className={`absolute ${f.cls} animate-float object-contain opacity-15`} />
          ))}
        </div>

        <div className="relative mx-auto flex max-w-4xl flex-col items-center px-4 py-24 text-center md:py-32">
          <h1 className="sr-only">Fall of Varrock</h1>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src="/img/logo-full-trim.png" alt="Fall of Varrock" className="animate-scale-in w-[min(88vw,540px)] drop-shadow-[0_10px_44px_rgba(220,38,38,0.4)]" />
          <div className="animate-fade-up eyebrow mt-5" style={{ animationDelay: "40ms" }}>⚔ OSRS Revision 228</div>
          <p className="animate-fade-up mt-5 max-w-2xl text-base text-lumbridge-parchment/80 md:text-lg" style={{ animationDelay: "140ms" }}>
            Varrock has fallen, and the world economy fell with it. The kingdom
            of Lumbridge holds on by a thread. Join the front line and bring order and peace back
            to the land of Gielinor.
          </p>
          <div className="animate-fade-up mt-9 flex flex-wrap items-center justify-center gap-3" style={{ animationDelay: "240ms" }}>
            <Link href="/play" className="btn-play text-base">▶ Play Now</Link>
            <a href={discordUrl} target="_blank" rel="noopener noreferrer" className="btn-ghost">Join Our Discord</a>
          </div>
          <div className="animate-fade-up mt-4 flex items-center gap-2 text-sm text-lumbridge-parchment/70" style={{ animationDelay: "280ms" }}>
            <span className="h-2.5 w-2.5 animate-pulse-dot rounded-full bg-lumbridge-green shadow-[0_0_10px_2px_rgba(34,197,94,0.7)]" />
            <span className="font-semibold text-lumbridge-green">{onlineNow.toLocaleString()}</span> adventurers online now
          </div>
        </div>

        <div className="pointer-events-none absolute inset-x-0 bottom-0 h-px bg-gradient-to-r from-transparent via-lumbridge-gold/50 to-transparent" />
      </section>

      {/* ============ SECTION INTRO ============ */}
      <section className="text-center">
        <Reveal>
          <div className="eyebrow mx-auto w-fit">Server features</div>
          <h2 className="mt-2 text-3xl font-bold md:text-4xl">What makes <span className="text-gradient">Fall of Varrock</span> different</h2>
          <p className="mx-auto mt-3 max-w-2xl text-sm text-lumbridge-parchment/60 md:text-base">
            A custom Old School server built around a living war, companions that fight beside you,
            and a real climb from peasant to king.
          </p>
        </Reveal>
      </section>

      {/* ============ 1. PEASANT TO KING (text L | ladder R) ============ */}
      <Reveal>
        <section id="ranks" className="relative scroll-mt-24 overflow-hidden rounded-3xl border border-lumbridge-gold/20">
          <div className="pointer-events-none absolute inset-0">
            <div className="absolute inset-0 bg-cover bg-center bg-no-repeat" style={{ backgroundImage: "url(/img/bg-war.jpg)" }} />
            <div className="absolute inset-0 bg-[#080506]/40" />
            <div className="absolute inset-0 bg-gradient-to-r from-[#080506] via-[#080506]/82 to-transparent" />
            <div className="absolute inset-0 bg-[radial-gradient(700px_320px_at_88%_18%,rgba(220,38,38,0.16),transparent_70%)]" />
            <div className="absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-lumbridge-gold/60 to-transparent" />
          </div>

          <div className="relative grid gap-10 p-8 md:p-12 lg:grid-cols-[1fr,1.05fr] lg:gap-14">
            <div className="self-center">
              <div className="eyebrow">Progression</div>
              <h2 className="mt-2 text-3xl font-bold md:text-4xl">From <span className="text-gradient">Peasant to King</span></h2>
              <p className="mt-4 max-w-xl text-sm text-lumbridge-parchment/70 md:text-base">
                Buy your rank with coin you earn in the world. Every rung unlocks heavier armour and
                more standing, all the way up to commanding armies as a Lord, Minister or King.
              </p>
              <div className="mt-7 flex flex-wrap gap-3">
                <Link href="/wiki/titles-and-citizenship" className="btn-play text-sm">See the full ladder</Link>
                <a href="#war" className="btn-ghost text-sm">March to war ↓</a>
              </div>
            </div>

            <div className="self-center rounded-2xl border border-white/10 bg-black/35 p-2 backdrop-blur-xl">
              <div className="grid grid-cols-[6rem_3.5rem_1fr] gap-2 px-3 py-2 text-[10px] font-semibold uppercase tracking-wider text-lumbridge-parchment/40">
                <span>Rank</span><span>Cost</span><span>Unlocks</span>
              </div>
              <ul className="space-y-0.5">
                {RANKS.map((r) => (
                  <li
                    key={r.name}
                    className={
                      "grid grid-cols-[6rem_3.5rem_1fr] items-center gap-2 rounded-lg px-3 py-2 text-sm " +
                      (r.commander
                        ? "border border-lumbridge-gold/25 bg-lumbridge-gold/[0.08]"
                        : "border border-transparent")
                    }
                  >
                    <span className={"font-bold " + (r.commander ? "text-lumbridge-gold" : "text-white")}>{r.name}</span>
                    <span className="text-lumbridge-parchment/50">{r.cost}</span>
                    <span className={r.commander ? "text-lumbridge-parchment/85" : "text-lumbridge-parchment/60"}>{r.unlock}</span>
                  </li>
                ))}
              </ul>
              <p className="px-3 pb-1 pt-2 text-[11px] text-lumbridge-parchment/40">
                <span className="font-semibold text-lumbridge-gold">Commander tiers</span> unlock the war powers.
              </p>
            </div>
          </div>
        </section>
      </Reveal>

      {/* ============ 2. THE WAR (bullets box L | text R) ============ */}
      <Reveal>
        <section id="war" className="relative scroll-mt-24 overflow-hidden rounded-3xl border border-lumbridge-gold/20">
          <div className="pointer-events-none absolute inset-0">
            <div className="absolute inset-0 bg-cover bg-center bg-no-repeat" style={{ backgroundImage: "url(/img/bg-war-battle.jpg)" }} />
            <div className="absolute inset-0 bg-[#080506]/40" />
            {/* Dark on the RIGHT (text side); the battle shows through centre + behind the glass box. */}
            <div className="absolute inset-0 bg-gradient-to-l from-[#080506] via-[#080506]/70 to-transparent" />
            <div className="absolute inset-0 bg-[radial-gradient(700px_320px_at_12%_20%,rgba(220,38,38,0.16),transparent_70%)]" />
            <div className="absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-lumbridge-gold/60 to-transparent" />
          </div>

          <div className="relative grid gap-10 p-8 md:p-12 lg:grid-cols-[1fr,1fr] lg:items-center lg:gap-14">
            {/* Bullets box */}
            <div className="order-2 self-center rounded-2xl border border-white/10 bg-black/45 p-5 backdrop-blur-xl md:p-6 lg:order-1">
              <ul className="space-y-3">
                {WAR_STEPS.map((s) => (
                  <li key={s.n} className="flex items-start gap-4">
                    <span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl border border-lumbridge-gold/30 bg-lumbridge-gold/[0.1] text-sm font-bold text-lumbridge-goldlight">{s.n}</span>
                    <div>
                      <h3 className="font-bold text-white">{s.title}</h3>
                      <p className="mt-0.5 text-sm text-lumbridge-parchment/70">{s.body}</p>
                    </div>
                  </li>
                ))}
              </ul>
            </div>

            {/* Text */}
            <div className="order-1 self-center lg:order-2">
              <div className="eyebrow">The War</div>
              <h2 className="mt-2 text-3xl font-bold md:text-4xl">Gear up. <span className="text-gradient">March to war.</span></h2>
              <p className="mt-4 max-w-xl text-sm text-lumbridge-parchment/80 md:text-base">
                Every city beyond Lumbridge has fallen to the enemy. Band together, raise an army of
                allied knights, and take them back. This is the server&apos;s endgame, and every fight
                pays out to everyone who shows up.
              </p>
              <div className="mt-5 flex flex-wrap gap-2">
                <span className="chip border-lumbridge-gold/25 bg-lumbridge-gold/[0.06] px-3 py-1.5 text-lumbridge-parchment/80">Lead up to 64 allied knights</span>
                <span className="chip border-lumbridge-gold/25 bg-lumbridge-gold/[0.06] px-3 py-1.5 text-lumbridge-parchment/80">Loot split by contribution</span>
              </div>
              <div className="mt-7">
                <Link href="/wiki/the-war-explained" className="btn-play text-sm">See how the war works</Link>
              </div>
            </div>
          </div>
        </section>
      </Reveal>

      {/* ============ 3. COMPANIONS (text L | visual R) ============ */}
      <Reveal>
        <section id="companions" className="relative scroll-mt-24 overflow-hidden rounded-3xl border border-lumbridge-gold/20">
          <div className="pointer-events-none absolute inset-0">
            <div className="absolute inset-0 bg-cover bg-center bg-no-repeat" style={{ backgroundImage: "url(/img/bg-companions.jpg)" }} />
            <div className="absolute inset-0 bg-[#080506]/45" />
            <div className="absolute inset-0 bg-gradient-to-r from-[#080506] via-[#080506]/72 to-transparent" />
            <div className="absolute inset-0 bg-[radial-gradient(700px_320px_at_88%_18%,rgba(245,158,11,0.14),transparent_70%)]" />
            <div className="absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-lumbridge-ember/50 to-transparent" />
          </div>

          <div className="relative grid gap-10 p-8 md:p-12 lg:grid-cols-[1.1fr,1fr] lg:gap-14">
            {/* Text */}
            <div className="self-center">
              <div className="eyebrow">Companions</div>
              <h2 className="mt-2 text-3xl font-bold md:text-4xl">Companions that <span className="text-gradient">fight for you</span></h2>
              <p className="mt-4 max-w-xl text-sm text-lumbridge-parchment/75 md:text-base">
                Recruit up to three AI knights, train them in real combat, and gear them from your
                own bank. Take them everywhere: boss fights, the wilderness, minigames and the war.
                They level up as they fight and grow into a war party that is genuinely yours.
              </p>

              <div className="mt-6 flex flex-wrap items-center gap-2">
                {COMPANION_LOOP.map((step, i) => (
                  <div key={step} className="flex items-center gap-2">
                    <span className="rounded-lg border border-lumbridge-gold/30 bg-lumbridge-gold/[0.08] px-3 py-1.5 text-sm font-semibold text-lumbridge-goldlight">{step}</span>
                    {i < COMPANION_LOOP.length - 1 && <span className="text-lumbridge-gold/60">→</span>}
                  </div>
                ))}
              </div>

              <ul className="mt-6 space-y-2.5">
                {COMPANION_PERKS.map((p) => (
                  <li key={p} className="flex items-start gap-2.5 text-sm text-lumbridge-parchment/75">
                    <span className="mt-1 h-1.5 w-1.5 shrink-0 rotate-45 bg-lumbridge-ember" />
                    <span>{p}</span>
                  </li>
                ))}
              </ul>

              <div className="mt-7">
                <Link href="/wiki/companions" className="btn-play text-sm">How companions work →</Link>
              </div>
            </div>

            {/* Visual */}
            <div className="flex flex-col items-center gap-4 lg:justify-center">
              <div className="w-full max-w-sm overflow-hidden rounded-2xl border border-lumbridge-gold/20 shadow-[0_10px_40px_-8px_rgba(0,0,0,0.7)]">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src="/img/feature-companions.jpg" alt="Your companions: melee, ranged and magic knights" className="w-full object-cover" />
              </div>
              <div className="flex flex-wrap justify-center gap-2">
                {COMPANION_ARCHETYPES.map((a) => (
                  <span key={a} className="chip border-lumbridge-gold/25 bg-lumbridge-gold/[0.06] px-3 py-1.5 text-lumbridge-parchment/80">{a}</span>
                ))}
              </div>
            </div>
          </div>
        </section>
      </Reveal>

      {/* ============ 4. MORE FEATURES ============ */}
      <section className="space-y-8">
        <Reveal className="text-center">
          <div className="eyebrow mx-auto w-fit">More to do</div>
          <h2 className="mt-2 text-3xl font-bold md:text-4xl">A whole world to <span className="text-gradient">explore</span></h2>
          <p className="mx-auto mt-3 max-w-2xl text-sm text-lumbridge-parchment/60 md:text-base">
            The war and your companions are just the start. Here is some of what else is waiting,
            with more landing all the time.
          </p>
        </Reveal>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {MORE_FEATURES.map((f, i) => (
            <Reveal key={f.name} delay={i * 60}>
              <div className="card card-hover flex h-full flex-col">
                <span className="grid h-12 w-12 place-items-center rounded-2xl border border-lumbridge-gold/20 bg-lumbridge-gold/[0.06] text-2xl">{f.icon}</span>
                <h3 className="mt-3 font-bold text-white">{f.name}</h3>
                <p className="mt-1 flex-1 text-sm text-lumbridge-parchment/65">{f.body}</p>
              </div>
            </Reveal>
          ))}
        </div>
      </section>

      {/* ============ 5. DISCORD ============ */}
      <Reveal>
        <section id="discord" className="relative overflow-hidden rounded-3xl border border-lumbridge-gold/20 bg-white/[0.03] backdrop-blur-xl">
          <div className="pointer-events-none absolute inset-0">
            <div className="absolute inset-0 bg-[radial-gradient(800px_360px_at_15%_0%,rgba(220,38,38,0.14),transparent_70%)]" />
            <div className="absolute inset-0 bg-[radial-gradient(600px_300px_at_90%_100%,rgba(245,158,11,0.08),transparent_70%)]" />
            <div className="absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-lumbridge-gold/50 to-transparent" />
          </div>
          <div className="relative grid items-center gap-8 p-8 md:p-12 lg:grid-cols-[1.3fr,1fr]">
            <div>
              <div className="eyebrow">Community</div>
              <h2 className="mt-2 text-3xl font-bold md:text-4xl">
                Join the community on <span className="text-gradient">Discord</span>
              </h2>
              <p className="mt-3 max-w-xl text-sm text-lumbridge-parchment/70 md:text-base">
                Link your account, grab your rank roles, and be first to hear when an event or war
                kicks off. It is the fastest way to find a team and keep up with the server.
              </p>
              <ul className="mt-6 grid gap-2.5 text-sm text-lumbridge-parchment/70 sm:grid-cols-2">
                <li className="flex items-start gap-2"><span className="mt-0.5 text-lumbridge-gold">◆</span> Live event feed straight from the game</li>
                <li className="flex items-start gap-2"><span className="mt-0.5 text-lumbridge-gold">◆</span> Pings the moment an event begins</li>
                <li className="flex items-start gap-2"><span className="mt-0.5 text-lumbridge-gold">◆</span> Account linking for rank and donor roles</li>
                <li className="flex items-start gap-2"><span className="mt-0.5 text-lumbridge-gold">◆</span> Drops, hiscores and lookups on demand</li>
              </ul>
            </div>
            <div className="flex flex-col items-center gap-3 text-center">
              <div className="grid h-24 w-24 place-items-center rounded-3xl border border-lumbridge-gold/25 bg-lumbridge-gold/[0.07] p-1.5 shadow-[0_6px_30px_rgba(220,38,38,0.25)]">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src="/img/icon-warroom.png" alt="" className="h-full w-full object-contain" />
              </div>
              <a href={discordUrl} target="_blank" rel="noopener noreferrer" className="btn-play w-full max-w-xs text-base">Join the Discord</a>
              <Link href={forumUrl} className="btn-ghost w-full max-w-xs text-sm">Browse the forum</Link>
            </div>
          </div>
        </section>
      </Reveal>

      {/* ============ 6. SERVER NEWS ============ */}
      <section className="space-y-6">
        <Reveal className="flex items-end justify-between">
          <div>
            <div className="eyebrow">Updates</div>
            <h2 className="mt-2 text-2xl font-bold md:text-3xl">Server News</h2>
          </div>
          <Link href="/news" className="link-cyan text-sm">All news →</Link>
        </Reveal>
        {news.length > 0 ? (
          <div className="grid gap-5 md:grid-cols-3">
            {news.map((n, i) => (
              <Reveal key={n.slug} delay={i * 80}>
                <Link href={`/news/${n.slug}`} className="card card-hover flex h-full flex-col">
                  <div className="mb-3 flex items-center gap-2 text-xs text-lumbridge-parchment/50">
                    <span className="chip uppercase tracking-wide text-lumbridge-parchmentdark">{n.category}</span>
                    <span>{new Date(n.publishedAt ?? n.createdAt).toLocaleDateString()}</span>
                  </div>
                  <h3 className="text-lg font-bold text-white">{n.title}</h3>
                  <p className="mt-1.5 line-clamp-3 flex-1 text-sm text-lumbridge-parchment/65">{stripMarkdown(n.body).slice(0, 150)}</p>
                  <span className="mt-3 text-sm link-cyan">Read more →</span>
                </Link>
              </Reveal>
            ))}
          </div>
        ) : (
          <Reveal>
            <div className="card flex flex-col items-center py-10 text-center">
              <span className="mb-3 grid h-12 w-12 place-items-center rounded-2xl border border-lumbridge-gold/20 bg-lumbridge-gold/[0.06] text-lg text-lumbridge-gold">✉</span>
              <h3 className="font-bold text-white">Nothing posted yet</h3>
              <p className="mt-1.5 max-w-sm text-sm text-lumbridge-parchment/55">
                Patch notes, events and updates will show up here as soon as they go live.
              </p>
            </div>
          </Reveal>
        )}
      </section>

      {/* ============ 7. GET STARTED ============ */}
      <Reveal>
        <section className="relative overflow-hidden rounded-3xl border border-lumbridge-gold/20 bg-white/[0.03] p-8 text-center backdrop-blur-xl md:p-12">
          <div className="pointer-events-none absolute -inset-px -z-10 bg-brand-gradient opacity-10 blur-2xl" />
          <div className="pointer-events-none absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-lumbridge-gold/60 to-transparent" />
          <div className="eyebrow mx-auto w-fit">Get started</div>
          <h2 className="mt-3 text-3xl font-bold md:text-4xl">Download and <span className="text-gradient">play in minutes</span></h2>
          <p className="mx-auto mt-3 max-w-xl text-lumbridge-parchment/75">
            The account you create here is the <strong className="text-lumbridge-parchment">same login</strong> you use in
            the game launcher. Make it once, and you are in.
          </p>

          <ol className="mx-auto mt-8 grid max-w-3xl gap-4 sm:grid-cols-3">
            {STEPS.map((s) => (
              <li key={s.num} className="rounded-2xl border border-white/10 bg-white/[0.03] p-5 text-left">
                <span className="grid h-9 w-9 place-items-center rounded-xl border border-lumbridge-gold/30 bg-lumbridge-gold/[0.08] text-sm font-bold text-lumbridge-goldlight">{s.num}</span>
                <h3 className="mt-3 font-bold text-white">{s.title}</h3>
                <p className="mt-1 text-sm text-lumbridge-parchment/60">{s.body}</p>
              </li>
            ))}
          </ol>

          <div className="mt-9 flex flex-wrap justify-center gap-3">
            <Link href="/register" className="btn-play text-base">Create your account</Link>
            <Link href="/play" className="btn-ghost">Download the client</Link>
          </div>
        </section>
      </Reveal>
    </div>
  );
}
