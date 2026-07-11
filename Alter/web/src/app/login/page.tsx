import { redirect } from "next/navigation";
import { AuthForm } from "@/components/AuthForm";
import { Crest } from "@/components/Crest";
import { getSession } from "@/lib/session";

const BULLETS = [
  {
    title: "One account, everywhere",
    body: "The same username and password works on the site, the forum and in-game.",
  },
  {
    title: "Live hiscores",
    body: "Your levels, ranks and PK record, updated as you play.",
  },
  {
    title: "War campaigns and companions",
    body: "Your companions and campaign standing are waiting where you left them.",
  },
];

export default async function LoginPage({ searchParams }: { searchParams: { next?: string } }) {
  if (await getSession()) redirect("/");
  return (
    <div className="relative left-1/2 right-1/2 -mx-[50vw] -my-10 w-screen">
      <div className="grid min-h-[calc(100vh-4rem)] lg:grid-cols-2">
        {/* ---- Atmospheric art panel (desktop only) ---- */}
        <div className="relative hidden overflow-hidden lg:block">
          <div
            className="absolute inset-0 bg-cover bg-center"
            style={{ backgroundImage: "url(/img/hero-bg.jpg)" }}
          />
          {/* Dark crimson scrim over the ruined city */}
          <div className="absolute inset-0 bg-red-950/45 mix-blend-multiply" />
          <div className="absolute inset-0 bg-gradient-to-t from-[#080506] via-[#080506]/55 to-[#080506]/25" />
          {/* Blend the panel's edge into the form side */}
          <div className="absolute inset-y-0 right-0 w-40 bg-gradient-to-l from-[#080506] to-transparent" />

          <div className="relative flex h-full flex-col justify-center p-12 xl:p-16">
            <Crest className="h-20 w-20 drop-shadow-[0_0_24px_rgba(220,38,38,0.45)]" />
            <p className="eyebrow mt-10">The war for Gielinor</p>
            <h2 className="font-display text-4xl font-bold leading-tight text-lumbridge-parchment xl:text-5xl">
              Return to the <span className="text-gradient">fight</span>
            </h2>
            <p className="mt-4 max-w-md text-lumbridge-parchment/70">
              The occupied cities still burn, and the reclamation needs its
              veterans. Log in and pick up where you left off.
            </p>
            <ul className="mt-10 max-w-md space-y-5">
              {BULLETS.map((b) => (
                <li key={b.title} className="flex gap-4">
                  <span className="mt-1.5 h-2.5 w-2.5 shrink-0 rotate-45 rounded-[2px] bg-gradient-to-br from-lumbridge-ember to-lumbridge-gold shadow-[0_0_10px_rgba(245,158,11,0.6)]" />
                  <div>
                    <p className="font-semibold text-lumbridge-parchment">{b.title}</p>
                    <p className="text-sm text-lumbridge-parchment/60">{b.body}</p>
                  </div>
                </li>
              ))}
            </ul>
          </div>
        </div>

        {/* ---- Form panel ---- */}
        <div className="relative flex items-center justify-center overflow-hidden px-4 py-14 sm:px-8">
          <div className="pointer-events-none absolute -top-40 left-1/2 h-72 w-[38rem] -translate-x-1/2 rounded-full bg-red-600/10 blur-3xl" />
          <div className="relative w-full max-w-md">
            {/* Compact brand header when the art panel is hidden */}
            <div className="mb-8 flex flex-col items-center text-center lg:hidden">
              <Crest className="h-14 w-14 drop-shadow-[0_0_18px_rgba(220,38,38,0.4)]" />
              <p className="eyebrow mt-4">The war for Gielinor</p>
              <h2 className="font-display text-2xl font-bold text-lumbridge-parchment">
                Return to the <span className="text-gradient">fight</span>
              </h2>
            </div>
            <AuthForm mode="login" next={searchParams.next} />
          </div>
        </div>
      </div>
    </div>
  );
}
