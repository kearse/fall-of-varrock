/**
 * Wiki-flavored markdown renderer - same injection-safe strategy as lib/markdown.ts
 * (escape everything first, then introduce a known-safe tag set), but emits
 * semantic MediaWiki-style HTML (`.wiki-content` scoped CSS in globals.css does
 * the theming) and collects a table of contents from ## / ### headings.
 */

export type TocEntry = { level: 2 | 3; id: string; text: string };

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function inline(s: string): string {
  return s
    .replace(/`([^`]+)`/g, "<code>$1</code>")
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/\*([^*]+)\*/g, "<em>$1</em>")
    .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+|\/[^\s)]*)\)/g, '<a href="$2" rel="noopener noreferrer">$1</a>');
}

/** Strip the inline markdown tokens for TOC/anchor text. TOC text is rendered
 * as React text (escaped again), so undo the document-wide escapeHtml here. */
function plain(s: string): string {
  return s
    .replace(/`([^`]+)`/g, "$1")
    .replace(/\*\*([^*]+)\*\*/g, "$1")
    .replace(/\*([^*]+)\*/g, "$1")
    .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&amp;/g, "&");
}

function headingId(text: string, used: Set<string>): string {
  let base = text.toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "") || "section";
  let id = base;
  let n = 2;
  while (used.has(id)) id = `${base}_${n++}`;
  used.add(id);
  return id;
}

function isTableDelimiter(line: string): boolean {
  const t = line.trim();
  if (!t.includes("-") || !t.includes("|")) return false;
  return /^\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?$/.test(t);
}

function tableCells(line: string): string[] {
  const cells = line.trim().replace(/^\|/, "").replace(/\|$/, "").split("|");
  return cells.map((c) => c.trim());
}

export function renderWikiMarkdown(input: string): { html: string; toc: TocEntry[] } {
  const escaped = escapeHtml(input.replace(/\r\n/g, "\n"));
  const lines = escaped.split("\n");
  const html: string[] = [];
  const toc: TocEntry[] = [];
  const usedIds = new Set<string>();
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
    if (trimmed.includes("|") && i + 1 < lines.length && isTableDelimiter(lines[i + 1])) {
      closeList();
      const header = tableCells(trimmed);
      const rows: string[][] = [];
      let j = i + 2;
      while (j < lines.length && lines[j].trim() !== "" && lines[j].includes("|")) {
        rows.push(tableCells(lines[j]));
        j++;
      }
      const thead = "<thead><tr>" + header.map((c) => `<th>${inline(c)}</th>`).join("") + "</tr></thead>";
      const tbody =
        "<tbody>" + rows.map((r) => "<tr>" + r.map((c) => `<td>${inline(c)}</td>`).join("") + "</tr>").join("") + "</tbody>";
      html.push(`<div class="wiki-table-scroll"><table class="wikitable">${thead}${tbody}</table></div>`);
      i = j - 1;
      continue;
    }
    if (/^###\s+/.test(trimmed)) {
      closeList();
      const text = plain(trimmed.replace(/^###\s+/, ""));
      const id = headingId(text, usedIds);
      toc.push({ level: 3, id, text });
      html.push(`<h3 id="${id}">${inline(trimmed.replace(/^###\s+/, ""))}</h3>`);
    } else if (/^##\s+/.test(trimmed)) {
      closeList();
      const text = plain(trimmed.replace(/^##\s+/, ""));
      const id = headingId(text, usedIds);
      toc.push({ level: 2, id, text });
      html.push(`<h2 id="${id}">${inline(trimmed.replace(/^##\s+/, ""))}</h2>`);
    } else if (/^#\s+/.test(trimmed)) {
      closeList();
      html.push(`<h2>${inline(trimmed.replace(/^#\s+/, ""))}</h2>`);
    } else if (/^[-*]\s+/.test(trimmed)) {
      if (!inList) {
        html.push("<ul>");
        inList = true;
      }
      html.push(`<li>${inline(trimmed.replace(/^[-*]\s+/, ""))}</li>`);
    } else {
      closeList();
      html.push(`<p>${inline(trimmed)}</p>`);
    }
  }
  closeList();
  return { html: html.join("\n"), toc };
}

/** Render inline markdown for infobox values (safe subset, same escaping). */
export function renderWikiInline(input: string): string {
  return inline(escapeHtml(input));
}
