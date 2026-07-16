import { getArticle } from "./wiki";

export type PriceRow = { item: string; cost: string; currency: string; where: string };

function stripMd(s: string): string {
  return s
    .replace(/\*\*/g, "")
    .replace(/`/g, "")
    .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
    .trim();
}

function cells(line: string): string[] {
  return line.trim().replace(/^\|/, "").replace(/\|$/, "").split("|").map((c) => c.trim());
}

function isDelimiter(line: string): boolean {
  const t = line.trim();
  return t.includes("-") && t.includes("|") && /^\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?$/.test(t);
}

function normCurrency(u: string): string {
  const s = stripMd(u);
  if (/^tickets$/i.test(s)) return "Boss Tickets";
  if (/^bm$/i.test(s)) return "Blood Money";
  return s;
}

/**
 * Build a flat, searchable price list by parsing the two-column price tables in the
 * `shops-directory` article — so the index is generated from that page and always
 * matches it (single source of truth; no separate dataset to drift out of sync).
 * Section context comes from the nearest ## / ### heading plus the bold tab label
 * that precedes each table (e.g. "**Weapons** (Boss Tickets):").
 */
export function getPriceRows(): PriceRow[] {
  const art = getArticle("shops-directory");
  if (!art) return [];
  const lines = art.body.replace(/\r\n/g, "\n").split("\n");
  const rows: PriceRow[] = [];
  let heading = "";
  let label = "";
  for (let i = 0; i < lines.length; i++) {
    const t = lines[i].trim();
    if (t === "") continue;
    const h = t.match(/^#{2,3}\s+(.*)$/);
    if (h) { heading = stripMd(h[1]); label = ""; continue; }
    // A line that STARTS with bold ("**Weapons** (Boss Tickets):") is the table's tab label.
    // Table rows start with "|", so they never match this.
    const b = t.match(/^\*\*([^*]+)\*\*/);
    if (b) { label = stripMd(b[1]); continue; }
    if (t.includes("|") && i + 1 < lines.length && isDelimiter(lines[i + 1])) {
      const header = cells(t);
      if (header.length === 2) {
        const currency = normCurrency(header[1]);
        const tab = label || (header[0].toLowerCase() !== "item" ? stripMd(header[0]) : "");
        const where = [heading, tab].filter(Boolean).join(" — ");
        let j = i + 2;
        while (j < lines.length && lines[j].trim() !== "" && lines[j].includes("|")) {
          const c = cells(lines[j]);
          if (c.length >= 2 && c[0] && c[1]) {
            rows.push({ item: stripMd(c[0]), cost: stripMd(c[1]), currency, where });
          }
          j++;
        }
        i = j - 1;
      }
    }
  }
  return rows;
}
