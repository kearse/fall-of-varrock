import Link from "next/link";
import { getPriceRows } from "@/lib/prices";
import { PriceSearch } from "@/components/wiki/PriceSearch";

export const dynamic = "force-dynamic";

export function generateMetadata() {
  return { title: "Item prices - Fall of Varrock Wiki" };
}

export default function PricesPage() {
  const rows = getPriceRows();
  return (
    <article>
      <h1 className="wiki-title">Item prices</h1>
      <div className="wiki-tagline">
        From the Fall of Varrock Wiki - every priced shop item in one searchable table.
      </div>
      <div className="wiki-content">
        <p>
          Search across the realm&rsquo;s priced shop tables - the{" "}
          <Link href="/wiki/shops-directory">Warlord&rsquo;s Armoury</Link> (Boss Tickets), the Emblem
          Trader&rsquo;s PK rewards (Blood Money), and Valaine&rsquo;s ticket shelves. This page is generated
          from the <Link href="/wiki/shops-directory">shops directory</Link>, so it always matches it. For
          the full route to every rare - including drop-only gear - see{" "}
          <Link href="/wiki/path-to-end-game">Path to end game</Link>.
        </p>
        <PriceSearch rows={rows} />
      </div>
      <div className="wiki-catbar">
        <Link href="/wiki">Wiki</Link> › Economy &amp; Trading
      </div>
    </article>
  );
}
