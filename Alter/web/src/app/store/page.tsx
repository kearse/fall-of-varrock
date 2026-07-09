import Link from "next/link";
import { PACKAGES, STORE_SECTIONS, formatPrice } from "@/lib/store";
import { getSession } from "@/lib/session";
import { BuyButtons } from "@/components/store/BuyButtons";
import { Reveal } from "@/components/Reveal";

export const metadata = { title: "Store · Fall of Varrock" };

const SECTION_ART: Record<string, string> = {
  Bonds: "/img/store-bonds.png",
  Membership: "/img/store-membership.png",
  "Donator Points": "/img/store-donator.png",
  Bundles: "/img/store-bundles.png",
};

const SECTION_COPY: Record<string, string> = {
  Bonds: "Tradeable in-game. Buy one with cash, or earn one from another player with gold.",
  Membership: "30-day perk tiers. Time stacks with whatever you already have.",
  "Donator Points": "Currency for the in-game donor store.",
  Bundles: "One-time kits, delivered straight to your bank.",
};

function sectionId(section: string) {
  return section.toLowerCase().replace(/\s+/g, "-");
}

export default async function StorePage() {
  const session = await getSession();
  const devEnabled = process.env.STORE_DEV_CHECKOUT === "1";

  return (
    <div className="space-y-12">
      {/* Intro + quick nav */}
      <Reveal className="space-y-5">
        <div>
          <p className="eyebrow">Support the realm</p>
          <h1 className="font-display text-4xl font-bold text-lumbridge-parchment">
            The <span className="text-gradient">War Chest</span>
          </h1>
          <p className="mt-3 max-w-2xl text-sm leading-relaxed text-lumbridge-parchment/70">
            Every purchase keeps Fall of Varrock online and funds new content. Delivered{" "}
            <strong className="text-lumbridge-parchment">in-game on your next login</strong>: points and
            membership apply instantly, bundles go to your bank. Pay by card, PayPal or crypto.
          </p>
        </div>
        <nav aria-label="Store categories" className="flex flex-wrap gap-2">
          {STORE_SECTIONS.map((section) => (
            <a
              key={section}
              href={`#${sectionId(section)}`}
              className="chip gap-2 px-3.5 py-1.5 text-sm text-lumbridge-parchment/80 transition-all duration-200 hover:border-lumbridge-gold/40 hover:bg-lumbridge-gold/10 hover:text-lumbridge-goldlight"
            >
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={SECTION_ART[section]} alt="" className="h-5 w-5 object-contain" />
              {section}
            </a>
          ))}
        </nav>
      </Reveal>

      {STORE_SECTIONS.map((section, si) => {
        const items = PACKAGES.filter((p) => p.category === section);
        if (items.length === 0) return null;
        return (
          <Reveal key={section} delay={si * 60}>
            <section id={sectionId(section)} className="scroll-mt-24 space-y-4">
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img
                  src={SECTION_ART[section]}
                  alt=""
                  className="h-8 w-8 object-contain drop-shadow-[0_0_8px_rgba(239,68,68,0.45)]"
                />
                <h2 className="font-display text-2xl text-lumbridge-goldlight">{section}</h2>
                <span className="hidden text-xs text-lumbridge-parchmentdark sm:inline">
                  {SECTION_COPY[section]}
                </span>
                <span className="rule-gold min-w-8 flex-1" />
              </div>

              <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
                {items.map((p) => {
                  const featured = !!p.badge;
                  return (
                    <div
                      key={p.id}
                      className={`card card-hover relative flex flex-col overflow-hidden ${
                        featured
                          ? "border-lumbridge-gold/50 ring-1 ring-lumbridge-gold/40 shadow-[0_0_36px_-8px_rgba(220,38,38,0.6)] hover:border-lumbridge-gold/70"
                          : ""
                      }`}
                    >
                      {featured && (
                        <span className="absolute left-1/2 top-0 z-10 -translate-x-1/2 rounded-b-lg bg-gold-gradient px-3 py-1 text-[10px] font-bold uppercase tracking-widest text-white shadow-[0_2px_14px_rgba(220,38,38,0.65)]">
                          {p.badge}
                        </span>
                      )}

                      {/* Decorative product art with ember glow */}
                      <div
                        aria-hidden
                        className="pointer-events-none absolute -right-7 -top-7 h-28 w-28 rounded-full bg-lumbridge-gold/[0.14] blur-2xl"
                      />
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img
                        src={SECTION_ART[section]}
                        alt=""
                        className="pointer-events-none absolute right-4 top-5 h-16 w-16 object-contain opacity-90 drop-shadow-[0_0_12px_rgba(239,68,68,0.45)]"
                      />

                      <h3 className="pr-16 text-lg font-semibold text-lumbridge-gold">{p.name}</h3>
                      <p className="mt-1 pr-14 text-sm text-lumbridge-parchment/60">{p.description}</p>
                      {p.perks && (
                        <ul className="mt-3 space-y-1 text-xs text-lumbridge-parchment/70">
                          {p.perks.map((perk) => (
                            <li key={perk} className="flex gap-1.5">
                              <span className="text-lumbridge-gold">✓</span> {perk}
                            </li>
                          ))}
                        </ul>
                      )}
                      <div className="mt-4 flex items-baseline gap-1.5 pb-3">
                        <span className="text-2xl font-bold text-lumbridge-parchment">{formatPrice(p.priceCents)}</span>
                        <span className="text-[10px] font-semibold uppercase tracking-wider text-lumbridge-parchmentdark">USD</span>
                      </div>
                      <div className="mt-auto">
                        <BuyButtons packageId={p.id} loggedIn={!!session} devEnabled={devEnabled} />
                      </div>
                    </div>
                  );
                })}
              </div>
            </section>
          </Reveal>
        );
      })}

      <p className="text-center text-xs text-lumbridge-parchment/40">
        Payments are processed securely by Stripe, PayPal and Coinbase Commerce. Boosted drop-rate perks require
        the in-game donator system. By purchasing you agree to our{" "}
        <Link href="/legal/terms" className="text-lumbridge-gold/80 hover:text-lumbridge-gold">Terms</Link> and{" "}
        <Link href="/legal/refund" className="text-lumbridge-gold/80 hover:text-lumbridge-gold">Refund Policy</Link>.
      </p>
    </div>
  );
}
