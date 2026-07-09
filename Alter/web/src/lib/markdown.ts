/**
 * Tiny, dependency-free, injection-safe markdown renderer.
 *
 * Strategy: HTML-escape the entire input FIRST, then apply a small set of regex
 * transforms that only ever introduce a known-safe tag set. Links are restricted
 * to http(s):// and site-relative (/) URLs. Good enough for news, guides and
 * forum posts without pulling in a markdown engine + sanitizer.
 */
function escapeHtml(s: string): string {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function inline(s: string): string {
  return s
    .replace(/`([^`]+)`/g, '<code class="rounded bg-black/40 px-1">$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/\*([^*]+)\*/g, "<em>$1</em>")
    .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+|\/[^\s)]*)\)/g, '<a href="$2" class="text-lumbridge-gold underline" rel="noopener noreferrer">$1</a>');
}

/** Strip markdown syntax to plain text - for card teasers / previews. */
export function stripMarkdown(input: string): string {
  return input
    .replace(/`{1,3}([^`]*)`{1,3}/g, "$1")        // code
    .replace(/!\[[^\]]*\]\([^)]*\)/g, "")          // images
    .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")        // links -> text
    .replace(/^\s{0,3}#{1,6}\s+/gm, "")            // headings
    .replace(/(\*\*|__)(.*?)\1/g, "$2")            // bold
    .replace(/(\*|_)(.*?)\1/g, "$2")               // italic
    .replace(/^\s{0,3}[-*+]\s+/gm, "")             // list bullets
    .replace(/^\s{0,3}>\s?/gm, "")                 // blockquote
    .replace(/\r?\n+/g, " ")                        // collapse newlines
    .replace(/\s+/g, " ")
    .trim();
}

/** A GFM table delimiter row, e.g. `| --- | :--: |` or `---|---`. */
function isTableDelimiter(line: string): boolean {
  const t = line.trim();
  if (!t.includes("-") || !t.includes("|")) return false;
  return /^\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?$/.test(t);
}

/** Split a table row into trimmed cells, dropping the empty edges from outer pipes. */
function tableCells(line: string): string[] {
  const cells = line.trim().replace(/^\|/, "").replace(/\|$/, "").split("|");
  return cells.map((c) => c.trim());
}

export function renderMarkdown(input: string): string {
  const escaped = escapeHtml(input.replace(/\r\n/g, "\n"));
  const lines = escaped.split("\n");
  const html: string[] = [];
  let inList = false;

  const closeList = () => {
    if (inList) {
      html.push("</ul>");
      inList = false;
    }
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const trimmed = line.trim();
    if (trimmed === "") {
      closeList();
      continue;
    }
    // GFM table: a header row with pipes followed by a delimiter row.
    if (trimmed.includes("|") && i + 1 < lines.length && isTableDelimiter(lines[i + 1])) {
      closeList();
      const header = tableCells(trimmed);
      const rows: string[][] = [];
      let j = i + 2;
      while (j < lines.length && lines[j].trim() !== "" && lines[j].includes("|")) {
        rows.push(tableCells(lines[j]));
        j++;
      }
      const thead =
        "<thead><tr>" +
        header.map((c) => `<th class="border border-white/15 px-3 py-1.5 text-left font-semibold text-lumbridge-gold">${inline(c)}</th>`).join("") +
        "</tr></thead>";
      const tbody =
        "<tbody>" +
        rows
          .map((r) => "<tr>" + r.map((c) => `<td class="border border-white/10 px-3 py-1.5 align-top">${inline(c)}</td>`).join("") + "</tr>")
          .join("") +
        "</tbody>";
      html.push(`<div class="my-3 overflow-x-auto"><table class="w-full border-collapse text-sm">${thead}${tbody}</table></div>`);
      i = j - 1; // resume after the table block
      continue;
    }
    if (/^###\s+/.test(trimmed)) {
      closeList();
      html.push(`<h3 class="mt-4 mb-1 font-display text-lg text-lumbridge-gold">${inline(trimmed.replace(/^###\s+/, ""))}</h3>`);
    } else if (/^##\s+/.test(trimmed)) {
      closeList();
      html.push(`<h2 class="mt-5 mb-2 font-display text-xl text-lumbridge-gold">${inline(trimmed.replace(/^##\s+/, ""))}</h2>`);
    } else if (/^#\s+/.test(trimmed)) {
      closeList();
      html.push(`<h1 class="mt-5 mb-2 font-display text-2xl text-lumbridge-gold">${inline(trimmed.replace(/^#\s+/, ""))}</h1>`);
    } else if (/^[-*]\s+/.test(trimmed)) {
      if (!inList) {
        html.push('<ul class="my-2 list-disc space-y-1 pl-6">');
        inList = true;
      }
      html.push(`<li>${inline(trimmed.replace(/^[-*]\s+/, ""))}</li>`);
    } else {
      closeList();
      html.push(`<p class="my-2 leading-relaxed">${inline(trimmed)}</p>`);
    }
  }
  closeList();
  return html.join("\n");
}
