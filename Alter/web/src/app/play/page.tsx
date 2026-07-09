import Link from "next/link";
import { Collections } from "@/lib/collections";

const WORLD = process.env.NEXT_PUBLIC_GAME_WORLD_HOST ?? "play.fallofvarrock.com";

export const metadata = { title: "Play · Fall of Varrock" };

// Download targets are env-driven so the buttons go live the moment a client
// artifact is hosted (set CLIENT_WINDOWS_URL / CLIENT_MAC_URL / CLIENT_LINUX_URL /
// CLIENT_JAR_URL). Until then they render as a clear "Building" state rather than
// a dead link.
type Platform = { key: string; label: string; sub: string; url?: string };
const PLATFORMS: Platform[] = [
  { key: "win", label: "Windows", sub: "Windows 10 / 11 - 64-bit", url: process.env.CLIENT_WINDOWS_URL },
  { key: "mac", label: "macOS", sub: "Apple Silicon & Intel", url: process.env.CLIENT_MAC_URL },
  { key: "linux", label: "Linux", sub: ".AppImage / .deb", url: process.env.CLIENT_LINUX_URL },
  { key: "jar", label: "Java (.jar)", sub: "Any OS - needs Java 11+", url: process.env.CLIENT_JAR_URL },
];

async function onlineCount(): Promise<number | null> {
  try {
    return await (await Collections.online()).countDocuments();
  } catch {
    return null;
  }
}

const STEPS = [
  {
    n: 1,
    title: "Create your account",
    body: (
      <>
        <Link href="/register" className="text-lumbridge-gold hover:text-lumbridge-goldlight">Register here</Link>{" "}
        or just type a fresh username &amp; password at the in-game login screen - either way it&apos;s the{" "}
        <strong className="text-lumbridge-parchment">same account</strong> everywhere: game, website, hiscores and forum.
      </>
    ),
  },
  {
    n: 2,
    title: "Download &amp; launch the client",
    body: (
      <>
        Pick your platform below and run the installer. The client is a custom RuneLite build (OSRS revision 228)
        with our plugins baked in - no extra setup needed.
      </>
    ),
  },
  {
    n: 3,
    title: "Log in &amp; claim the realm",
    body: (
      <>
        Log in with your account and you&apos;ll spawn in Lumbridge. Finish the <strong className="text-lumbridge-parchment">Recruit
        Trials</strong> to learn the ropes, then start climbing the feudal ranks. Your stats sync to the{" "}
        <Link href="/hiscores" className="text-lumbridge-gold hover:text-lumbridge-goldlight">hiscores</Link> in real time.
      </>
    ),
  },
];

const FAQ = [
  {
    q: "Is it free to play?",
    a: "Yes - completely free. Membership and donator perks are optional and only ever cosmetic or convenience; nothing pay-to-win.",
  },
  {
    q: "Do I need the official RuneScape client?",
    a: "No. Use our client below. It will not affect or connect to your real Jagex/OSRS account in any way.",
  },
  {
    q: "Are my website and game accounts the same?",
    a: "They are one account. Register in either place and the credentials work in both, including the forum (single sign-on).",
  },
  {
    q: "Will my antivirus flag the client?",
    a: "Unsigned community clients sometimes trip Windows SmartScreen. Choose “More info → Run anyway”. The source is open for inspection.",
  },
  {
    q: "What are the system requirements?",
    a: "Roughly anything from the last decade: a 64-bit OS, 2 GB free RAM and OpenGL 2+ graphics. The native installers bundle their own Java runtime.",
  },
];

export default async function PlayPage() {
  const online = await onlineCount();
  const anyDownload = PLATFORMS.some((p) => p.url);

  return (
    <div className="mx-auto max-w-4xl space-y-14 pb-8">
      {/* Hero */}
      <header className="relative overflow-hidden rounded-3xl border border-white/10 bg-gradient-to-b from-white/[0.06] to-transparent px-6 py-12 text-center sm:px-10">
        <div className="pointer-events-none absolute -top-24 left-1/2 h-64 w-64 -translate-x-1/2 rounded-full bg-lumbridge-gold/15 blur-3xl" />
        <p className="badge mx-auto mb-4 w-fit">OSRS Revision 228</p>
        <h1 className="font-display text-4xl font-bold text-white sm:text-5xl">
          Play <span className="bg-brand-gradient bg-clip-text text-transparent">Fall of Varrock</span>
        </h1>
        <p className="mx-auto mt-4 max-w-xl text-lumbridge-parchment/70">
          A free, community-built RuneScape private server. Three steps and you&apos;re in the fight.
        </p>
        <div className="mt-6 flex items-center justify-center gap-2 text-sm">
          {online != null ? (
            <>
              <span className="h-2.5 w-2.5 animate-pulse-dot rounded-full bg-lumbridge-green shadow-[0_0_10px_2px_rgba(34,197,94,0.6)]" />
              <span className="font-semibold text-lumbridge-green">{online.toLocaleString()}</span>
              <span className="text-lumbridge-parchment/60">adventurers online now</span>
            </>
          ) : (
            <span className="text-lumbridge-parchment/50">World status updating…</span>
          )}
        </div>
        <a href="#download" className="btn-gold mt-8 inline-flex">Download the client</a>
      </header>

      {/* Steps */}
      <section>
        <h2 className="mb-6 text-center font-display text-2xl font-bold text-white">Getting started</h2>
        <ol className="grid gap-4 sm:grid-cols-3">
          {STEPS.map((s) => (
            <li key={s.n} className="card card-hover">
              <div className="mb-3 flex h-9 w-9 items-center justify-center rounded-full bg-brand-gradient font-display font-bold text-lumbridge-stone">
                {s.n}
              </div>
              <h3 className="mb-1.5 font-semibold text-lumbridge-gold" dangerouslySetInnerHTML={{ __html: s.title }} />
              <p className="text-sm leading-relaxed text-lumbridge-parchment/70">{s.body}</p>
            </li>
          ))}
        </ol>
      </section>

      {/* Download */}
      <section id="download" className="scroll-mt-24">
        <h2 className="mb-1 text-center font-display text-2xl font-bold text-white">Download the client</h2>
        <p className="mb-6 text-center text-sm text-lumbridge-parchment/60">
          {anyDownload
            ? "Choose your platform - it's the same realm wherever you log in."
            : "Native installers are being finalised - check back shortly."}
        </p>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {PLATFORMS.map((p) => (
            <DownloadCard key={p.key} p={p} />
          ))}
        </div>

        {/* Login details */}
        <div className="card mt-6">
          <h3 className="mb-3 font-semibold text-lumbridge-gold">Connection details</h3>
          <dl className="grid gap-3 text-sm sm:grid-cols-2">
            <div className="flex items-center justify-between rounded-lg bg-black/30 px-4 py-2.5">
              <dt className="text-lumbridge-parchment/60">World / login server</dt>
              <dd className="font-mono text-lumbridge-goldlight">{WORLD}</dd>
            </div>
            <div className="flex items-center justify-between rounded-lg bg-black/30 px-4 py-2.5">
              <dt className="text-lumbridge-parchment/60">Game revision</dt>
              <dd className="font-mono text-lumbridge-goldlight">OSRS 228</dd>
            </div>
          </dl>
          <p className="mt-3 text-xs text-lumbridge-parchment/45">
            Using a manual RuneLite/RSProx setup? Point your proxy target at the world above. The native client is
            already configured for it.
          </p>
        </div>
      </section>

      {/* FAQ */}
      <section>
        <h2 className="mb-6 text-center font-display text-2xl font-bold text-white">Frequently asked</h2>
        <div className="space-y-3">
          {FAQ.map((f) => (
            <details key={f.q} className="card group">
              <summary className="flex cursor-pointer items-center justify-between font-semibold text-lumbridge-parchment marker:content-none">
                {f.q}
                <span className="text-lumbridge-gold transition-transform group-open:rotate-45">+</span>
              </summary>
              <p className="mt-3 text-sm leading-relaxed text-lumbridge-parchment/70">{f.a}</p>
            </details>
          ))}
        </div>
      </section>

      <p className="text-center text-sm text-lumbridge-parchment/55">
        Stuck? Browse the{" "}
        <Link href="/guides" className="text-lumbridge-gold hover:text-lumbridge-goldlight">guides</Link> or ask on the{" "}
        <Link href="/forum" className="text-lumbridge-gold hover:text-lumbridge-goldlight">forum</Link>.
      </p>
    </div>
  );
}

function DownloadCard({ p }: { p: Platform }) {
  const enabled = Boolean(p.url);
  const inner = (
    <>
      <span className="font-semibold text-lumbridge-parchment">{p.label}</span>
      <span className="mt-0.5 text-xs text-lumbridge-parchment/50">{p.sub}</span>
      <span className={`mt-3 rounded-lg px-3 py-1.5 text-xs font-semibold ${enabled ? "bg-brand-gradient text-lumbridge-stone" : "border border-white/10 text-lumbridge-parchment/45"}`}>
        {enabled ? "Download" : "Building…"}
      </span>
    </>
  );
  const cls = "card flex flex-col items-center text-center";
  return enabled ? (
    <a href={p.url} className={`${cls} card-hover`} download>
      {inner}
    </a>
  ) : (
    <div className={`${cls} opacity-70`} aria-disabled>
      {inner}
    </div>
  );
}
