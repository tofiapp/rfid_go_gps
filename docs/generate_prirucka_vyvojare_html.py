#!/usr/bin/env python3
"""Generate interactive HTML developer guide with rendered Mermaid diagrams."""

from __future__ import annotations

import html
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent
MD_PATH = ROOT / "prirucka-vyvojare.md"
OUT_PATH = ROOT / "prirucka-vyvojare.html"

CSS = """
:root {
  --bg: #f7f8fa;
  --surface: #ffffff;
  --text: #1a1d23;
  --muted: #5c6570;
  --accent: #0b5cab;
  --accent-soft: #e8f1fb;
  --border: #d5dde6;
  --code-bg: #f0f3f6;
  --sidebar-w: 280px;
}
* { box-sizing: border-box; }
html { scroll-behavior: smooth; }
body {
  margin: 0;
  font-family: "Segoe UI", "DejaVu Sans", system-ui, sans-serif;
  background: var(--bg);
  color: var(--text);
  line-height: 1.55;
}
a { color: var(--accent); text-decoration: none; }
a:hover { text-decoration: underline; }
.layout { display: flex; min-height: 100vh; }
nav.toc {
  position: sticky; top: 0; align-self: flex-start;
  width: var(--sidebar-w); max-height: 100vh; overflow: auto;
  background: var(--surface); border-right: 1px solid var(--border);
  padding: 1.25rem 1rem 2rem;
  flex-shrink: 0;
}
nav.toc h1 {
  font-size: 0.95rem; margin: 0 0 0.75rem; color: var(--accent);
  letter-spacing: 0.02em;
}
nav.toc .meta { font-size: 0.75rem; color: var(--muted); margin-bottom: 1rem; }
nav.toc a {
  display: block; font-size: 0.82rem; color: var(--text);
  padding: 0.28rem 0.4rem; border-radius: 4px; margin: 0.05rem 0;
}
nav.toc a:hover { background: var(--accent-soft); text-decoration: none; }
nav.toc a.h3 { padding-left: 1rem; color: var(--muted); font-size: 0.78rem; }
main {
  flex: 1; max-width: 920px; padding: 2rem 2.5rem 4rem; margin: 0 auto;
}
.hero {
  background: linear-gradient(145deg, #0b5cab 0%, #154f8a 55%, #1a3348 100%);
  color: #fff; border-radius: 12px; padding: 1.75rem 1.75rem 1.5rem;
  margin-bottom: 2rem;
}
.hero h1 { margin: 0 0 0.4rem; font-size: 1.75rem; font-weight: 700; }
.hero p { margin: 0.35rem 0; opacity: 0.92; font-size: 0.95rem; }
.hero .links { margin-top: 0.9rem; display: flex; gap: 0.6rem; flex-wrap: wrap; }
.hero .links a {
  background: rgba(255,255,255,0.15); color: #fff;
  padding: 0.35rem 0.75rem; border-radius: 6px; font-size: 0.85rem;
}
.hero .links a:hover { background: rgba(255,255,255,0.28); text-decoration: none; }
h2 {
  margin-top: 2.4rem; padding-top: 0.6rem;
  border-top: 2px solid var(--border); color: #0d47a1; font-size: 1.45rem;
}
h3 { margin-top: 1.6rem; color: var(--accent); font-size: 1.12rem; }
p, li { font-size: 0.98rem; }
blockquote {
  margin: 1rem 0; padding: 0.75rem 1rem;
  background: var(--accent-soft); border-left: 4px solid var(--accent);
  color: var(--muted); border-radius: 0 6px 6px 0;
}
table {
  width: 100%; border-collapse: collapse; margin: 0.9rem 0 1.2rem;
  font-size: 0.9rem; background: var(--surface);
}
th, td { border: 1px solid var(--border); padding: 0.45rem 0.6rem; text-align: left; vertical-align: top; }
th { background: var(--accent-soft); color: #0d47a1; }
tr:nth-child(even) td { background: #fafbfc; }
pre {
  background: var(--code-bg); border: 1px solid var(--border);
  border-radius: 8px; padding: 0.85rem 1rem; overflow-x: auto;
  font-size: 0.82rem; line-height: 1.4;
}
code {
  font-family: "DejaVu Sans Mono", "Consolas", monospace;
  font-size: 0.88em; background: var(--code-bg); padding: 0.1em 0.35em; border-radius: 3px;
}
pre code { background: none; padding: 0; font-size: inherit; }
.mermaid {
  background: var(--surface); border: 1px solid var(--border);
  border-radius: 10px; padding: 1rem; margin: 1rem 0 1.25rem;
  overflow-x: auto; text-align: center;
}
hr { border: none; border-top: 1px solid var(--border); margin: 2rem 0; }
.footer-note { color: var(--muted); font-size: 0.85rem; margin-top: 3rem; text-align: center; }
@media (max-width: 900px) {
  .layout { flex-direction: column; }
  nav.toc {
    position: relative; width: 100%; max-height: none;
    border-right: none; border-bottom: 1px solid var(--border);
  }
  main { padding: 1.25rem 1rem 3rem; }
}
"""

JS_HEAD = """
<script type="module">
  import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs';
  mermaid.initialize({
    startOnLoad: true,
    theme: 'base',
    themeVariables: {
      primaryColor: '#e8f1fb',
      primaryTextColor: '#1a1d23',
      primaryBorderColor: '#0b5cab',
      lineColor: '#5c6570',
      secondaryColor: '#f7f8fa',
      tertiaryColor: '#ffffff',
      fontFamily: 'Segoe UI, DejaVu Sans, system-ui, sans-serif'
    },
    flowchart: { curve: 'basis', htmlLabels: true },
    sequence: { actorMargin: 24, messageMargin: 32 }
  });
</script>
"""


def slugify(text: str) -> str:
    text = text.lower().strip()
    text = re.sub(r"[`*_]", "", text)
    text = re.sub(r"[^\w\s\-áčďéěíňóřšťúůýž]", "", text, flags=re.IGNORECASE)
    text = re.sub(r"\s+", "-", text)
    return text


def inline_md(text: str) -> str:
    text = html.escape(text)
    text = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"`([^`]+)`", r"<code>\1</code>", text)
    text = re.sub(
        r"\[([^\]]+)\]\(([^)]+)\)",
        r'<a href="\2">\1</a>',
        text,
    )
    return text


def parse_md_to_html(md: str) -> tuple[str, str, list[tuple[str, str, int]]]:
    """Returns (body_html, version, toc_entries)."""
    lines = md.splitlines()
    out: list[str] = []
    toc: list[tuple[str, str, int]] = []
    version = ""
    i = 0
    skip_h1 = True

    while i < len(lines):
        line = lines[i]

        if "**Verze aplikace:**" in line and not version:
            version = line.split("**Verze aplikace:**", 1)[-1].strip()

        if skip_h1 and line.startswith("# "):
            skip_h1 = False
            i += 1
            continue

        if line.startswith("## "):
            title = line[3:].strip()
            sid = slugify(title)
            toc.append((title, sid, 2))
            out.append(f'<h2 id="{sid}">{inline_md(title)}</h2>')
            i += 1
            continue

        if line.startswith("### "):
            title = line[4:].strip()
            sid = slugify(title)
            toc.append((title, sid, 3))
            out.append(f'<h3 id="{sid}">{inline_md(title)}</h3>')
            i += 1
            continue

        if line.strip() == "---":
            out.append("<hr>")
            i += 1
            continue

        if line.strip().startswith("> "):
            chunks: list[str] = []
            while i < len(lines) and lines[i].strip().startswith("> "):
                chunks.append(lines[i].strip()[2:])
                i += 1
            out.append(f"<blockquote><p>{inline_md(' '.join(chunks))}</p></blockquote>")
            continue

        if line.strip().startswith("```"):
            lang = line.strip()[3:].strip().lower()
            i += 1
            code_lines: list[str] = []
            while i < len(lines) and not lines[i].strip().startswith("```"):
                code_lines.append(lines[i])
                i += 1
            if i < len(lines):
                i += 1
            body = "\n".join(code_lines)
            if lang == "mermaid":
                out.append(f'<pre class="mermaid">{html.escape(body)}</pre>')
            else:
                out.append(f"<pre><code>{html.escape(body)}</code></pre>")
            continue

        if line.strip().startswith("|"):
            rows: list[str] = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                rows.append(lines[i].strip())
                i += 1
            out.append(render_table(rows))
            continue

        if line.strip().startswith("- "):
            items: list[str] = []
            while i < len(lines) and lines[i].strip().startswith("- "):
                items.append(lines[i].strip()[2:])
                i += 1
            out.append("<ul>" + "".join(f"<li>{inline_md(x)}</li>" for x in items) + "</ul>")
            continue

        if re.match(r"^\d+\.\s", line.strip()):
            items = []
            while i < len(lines) and re.match(r"^\d+\.\s", lines[i].strip()):
                items.append(re.sub(r"^\d+\.\s*", "", lines[i].strip()))
                i += 1
            out.append("<ol>" + "".join(f"<li>{inline_md(x)}</li>" for x in items) + "</ol>")
            continue

        if line.strip() == "":
            i += 1
            continue

        if line.strip().startswith("*") and line.strip().endswith("*") and not line.strip().startswith("**"):
            out.append(f'<p class="footer-note">{inline_md(line.strip().strip("*"))}</p>')
            i += 1
            continue

        para = [line.strip()]
        i += 1
        while (
            i < len(lines)
            and lines[i].strip()
            and not lines[i].startswith(("#", "|", "-", ">", "---", "```"))
            and not re.match(r"^\d+\.\s", lines[i].strip())
        ):
            para.append(lines[i].strip())
            i += 1
        out.append(f"<p>{inline_md(' '.join(para))}</p>")

    return "\n".join(out), version, toc


def render_table(rows: list[str]) -> str:
    parsed: list[list[str]] = []
    for idx, row in enumerate(rows):
        cells = [c.strip() for c in row.strip("|").split("|")]
        if idx == 1 and all(re.match(r"^:?-+:?$", c.replace(" ", "")) for c in cells):
            continue
        parsed.append(cells)
    if not parsed:
        return ""
    html_rows = []
    for ri, row in enumerate(parsed):
        tag = "th" if ri == 0 else "td"
        cells = "".join(f"<{tag}>{inline_md(c)}</{tag}>" for c in row)
        html_rows.append(f"<tr>{cells}</tr>")
    return "<table>\n" + "\n".join(html_rows) + "\n</table>"


def build_toc_html(toc: list[tuple[str, str, int]]) -> str:
    parts = ['<nav class="toc">', "<h1>Obsah</h1>", '<p class="meta">RFID Go GPS · vývojářská příručka</p>']
    for title, sid, level in toc:
        cls = ' class="h3"' if level == 3 else ""
        # Skip deep subsection noise in sidebar for h3 under long chapters — keep all for searchability
        if level == 3 and not re.match(r"^\d", title):
            continue
        parts.append(f'<a href="#{sid}"{cls}>{html.escape(title)}</a>')
    parts.append("</nav>")
    return "\n".join(parts)


def main() -> None:
    md = MD_PATH.read_text(encoding="utf-8")
    body, version, toc = parse_md_to_html(md)
    toc_html = build_toc_html(toc)
    ver_label = f"Verze aplikace {version}" if version else ""

    doc = f"""<!DOCTYPE html>
<html lang="cs">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>RFID Go GPS – Technická příručka pro vývojáře</title>
  <style>{CSS}</style>
  {JS_HEAD}
</head>
<body>
<div class="layout">
{toc_html}
<main>
  <div class="hero">
    <h1>RFID Go GPS</h1>
    <p>Technická příručka pro vývojáře</p>
    <p>{html.escape(ver_label)}</p>
    <div class="links">
      <a href="RFID_Go_GPS_prirucka_vyvojare.pdf">PDF</a>
      <a href="prirucka-vyvojare.md">Markdown zdroj</a>
      <a href="../README.md">README</a>
    </div>
  </div>
{body}
</main>
</div>
</body>
</html>
"""
    OUT_PATH.write_text(doc, encoding="utf-8")
    print(f"Generated: {OUT_PATH}")


if __name__ == "__main__":
    main()
