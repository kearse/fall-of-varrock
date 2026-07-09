import Link from "next/link";

// A legal/policy document. Body entries are either a paragraph (string) or a
// bullet list (string[]). Kept deliberately simple - no markdown engine needed.
export type LegalSection = { h: string; body: (string | string[])[] };

export function LegalPage({
  title,
  updated,
  intro,
  sections,
}: {
  title: string;
  updated: string;
  intro?: string;
  sections: LegalSection[];
}) {
  return (
    <article className="mx-auto max-w-3xl pb-10">
      <header className="mb-8 border-b border-white/10 pb-6">
        <p className="badge mb-3 w-fit">Legal</p>
        <h1 className="font-display text-3xl font-bold text-white sm:text-4xl">{title}</h1>
        <p className="mt-2 text-sm text-lumbridge-parchment/50">Last updated {updated}</p>
        {intro && <p className="mt-4 leading-relaxed text-lumbridge-parchment/75">{intro}</p>}
      </header>

      <div className="space-y-8">
        {sections.map((s, i) => (
          <section key={s.h}>
            <h2 className="mb-2 font-display text-lg font-semibold text-lumbridge-gold">
              {i + 1}. {s.h}
            </h2>
            <div className="space-y-3">
              {s.body.map((b, j) =>
                Array.isArray(b) ? (
                  <ul key={j} className="ml-1 space-y-1.5">
                    {b.map((li) => (
                      <li key={li} className="flex gap-2 text-sm leading-relaxed text-lumbridge-parchment/75">
                        <span className="mt-2 h-1 w-1 shrink-0 rounded-full bg-lumbridge-gold/70" />
                        <span>{li}</span>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p key={j} className="text-sm leading-relaxed text-lumbridge-parchment/75">
                    {b}
                  </p>
                )
              )}
            </div>
          </section>
        ))}
      </div>

      <footer className="mt-10 flex flex-wrap gap-x-5 gap-y-2 border-t border-white/10 pt-6 text-sm">
        <span className="text-lumbridge-parchment/45">Related:</span>
        <Link href="/legal/terms" className="text-lumbridge-gold hover:text-lumbridge-goldlight">Terms of Service</Link>
        <Link href="/legal/privacy" className="text-lumbridge-gold hover:text-lumbridge-goldlight">Privacy Policy</Link>
        <Link href="/legal/refund" className="text-lumbridge-gold hover:text-lumbridge-goldlight">Refund Policy</Link>
      </footer>
    </article>
  );
}
