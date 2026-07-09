// Fall of Varrock emblem. Renders the shield icon logo so every crest across the
// site (wiki sidebar, auth panels, player profile) matches the header/hero art.
// The `className` drives sizing exactly like before (e.g. "h-14 w-14").
export function Crest({ className = "h-8 w-8" }: { className?: string }) {
  return (
    // eslint-disable-next-line @next/next/no-img-element
    <img src="/img/logo-icon.png" alt="" aria-hidden className={`object-contain ${className}`} />
  );
}
