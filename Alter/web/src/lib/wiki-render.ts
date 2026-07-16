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
    // Images: root-relative sources only (local assets under /public) - never remote URLs,
    // so the wiki can't be made to load a tracking/offsite image. Runs before the link rule
    // so "![alt](/x)" isn't mis-parsed as a link.
    .replace(/!\[([^\]]*)\]\((\/[^\s)]+)\)/g, '<img src="$2" alt="$1" loading="lazy">')
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
  let listType: "ul" | "ol" | null = null;
  let inFence = false;
  let fenceLines: string[] = [];

  const closeList = () => {
    if (listType) {
      html.push(listType === "ol" ? "</ol>" : "</ul>");
      listType = null;
    }
  };
  const openList = (t: "ul" | "ol") => {
    if (listType && listType !== t) closeList();
    if (!listType) {
      html.push(t === "ol" ? "<ol>" : "<ul>");
      listType = t;
    }
  };
  // A wrapped continuation line (indented, no marker) belongs to the current list
  // item — append it to the open <li> instead of orphaning it into its own <p>.
  const appendContinuation = (textEscaped: string) => {
    const last = html.pop();
    if (last !== undefined && last.endsWith("</li>")) {
      html.push(`${last.slice(0, -"</li>".length)} ${inline(textEscaped)}</li>`);
    } else {
      if (last !== undefined) html.push(last);
      closeList();
      html.push(`<p>${inline(textEscaped)}</p>`);
    }
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const trimmed = line.trim();

    // Fenced code block (``` … ```). Contents are already HTML-escaped; emit verbatim.
    if (inFence) {
      if (/^```/.test(trimmed)) {
        html.push(`<pre><code>${fenceLines.join("\n")}</code></pre>`);
        fenceLines = [];
        inFence = false;
      } else {
        fenceLines.push(line);
      }
      continue;
    }
    if (/^```/.test(trimmed)) {
      closeList();
      inFence = true;
      fenceLines = [];
      continue;
    }

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
    } else if (/^&gt;\s?/.test(trimmed)) {
      // Blockquote — the leading ">" was HTML-escaped to "&gt;" up front.
      closeList();
      html.push(`<blockquote>${inline(trimmed.replace(/^&gt;\s?/, ""))}</blockquote>`);
    } else if (/^!\[[^\]]*\]\(\/[^\s)]+\)$/.test(trimmed)) {
      // A line that is only an image -> a captioned figure (the alt text becomes the caption).
      closeList();
      const m = trimmed.match(/^!\[([^\]]*)\]\((\/[^\s)]+)\)$/)!;
      const cap = m[1] ? `<figcaption>${m[1]}</figcaption>` : "";
      html.push(`<figure class="wiki-figure"><img src="${m[2]}" alt="${m[1]}" loading="lazy">${cap}</figure>`);
    } else if (/^[-*]\s+/.test(trimmed)) {
      openList("ul");
      html.push(`<li>${inline(trimmed.replace(/^[-*]\s+/, ""))}</li>`);
    } else if (/^\d+\.\s+/.test(trimmed)) {
      openList("ol");
      html.push(`<li>${inline(trimmed.replace(/^\d+\.\s+/, ""))}</li>`);
    } else if (listType && /^\s+/.test(line)) {
      appendContinuation(trimmed);
    } else {
      closeList();
      html.push(`<p>${inline(trimmed)}</p>`);
    }
  }
  if (inFence) html.push(`<pre><code>${fenceLines.join("\n")}</code></pre>`);
  closeList();
  return { html: html.join("\n"), toc };
}

/** Render inline markdown for infobox values (safe subset, same escaping). */
export function renderWikiInline(input: string): string {
  return inline(escapeHtml(input));
}
