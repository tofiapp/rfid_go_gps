#!/usr/bin/env python3
"""Generate end-user PDF manual for RFID Go GPS from prirucka-uzivatele.md."""

from __future__ import annotations

import re
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_JUSTIFY, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import cm, mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    CondPageBreak,
    HRFlowable,
    KeepTogether,
    PageBreak,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)

SUBSECTION_MIN_SPACE = 4 * cm

ROOT = Path(__file__).resolve().parent
MD_PATH = ROOT / "prirucka-uzivatele.md"
OUT_PATH = ROOT / "RFID_Go_GPS_prirucka.pdf"
FONT_DIR = Path("/usr/share/fonts/truetype/dejavu")

PRIMARY = colors.HexColor("#1565C0")
PRIMARY_DARK = colors.HexColor("#0D47A1")
MUTED = colors.HexColor("#546E7A")
TABLE_HEADER = colors.HexColor("#E3F2FD")
TABLE_ALT = colors.HexColor("#F5F9FC")
BORDER = colors.HexColor("#BBDEFB")


def register_fonts() -> tuple[str, str, str]:
    regular = str(FONT_DIR / "DejaVuSans.ttf")
    bold = str(FONT_DIR / "DejaVuSans-Bold.ttf")
    mono = str(FONT_DIR / "DejaVuSansMono.ttf")
    pdfmetrics.registerFont(TTFont("DejaVu", regular))
    pdfmetrics.registerFont(TTFont("DejaVu-Bold", bold))
    pdfmetrics.registerFont(TTFont("DejaVuMono", mono))
    return "DejaVu", "DejaVu-Bold", "DejaVuMono"


def build_styles(regular: str, bold: str, mono: str) -> dict[str, ParagraphStyle]:
    base = getSampleStyleSheet()
    return {
        "title": ParagraphStyle(
            "title",
            parent=base["Title"],
            fontName=bold,
            fontSize=26,
            leading=32,
            textColor=PRIMARY_DARK,
            alignment=TA_CENTER,
            spaceAfter=8,
        ),
        "subtitle": ParagraphStyle(
            "subtitle",
            parent=base["Normal"],
            fontName=regular,
            fontSize=12,
            leading=16,
            textColor=MUTED,
            alignment=TA_CENTER,
            spaceAfter=20,
        ),
        "h1": ParagraphStyle(
            "h1",
            parent=base["Heading1"],
            fontName=bold,
            fontSize=16,
            leading=20,
            textColor=PRIMARY_DARK,
            spaceBefore=14,
            spaceAfter=8,
            keepWithNext=True,
        ),
        "h2": ParagraphStyle(
            "h2",
            parent=base["Heading2"],
            fontName=bold,
            fontSize=13,
            leading=17,
            textColor=PRIMARY,
            spaceBefore=10,
            spaceAfter=6,
            keepWithNext=True,
        ),
        "body": ParagraphStyle(
            "body",
            parent=base["Normal"],
            fontName=regular,
            fontSize=10.5,
            leading=15,
            alignment=TA_JUSTIFY,
            spaceAfter=6,
        ),
        "bullet": ParagraphStyle(
            "bullet",
            parent=base["Normal"],
            fontName=regular,
            fontSize=10.5,
            leading=15,
            leftIndent=14,
            bulletIndent=0,
            spaceAfter=3,
        ),
        "quote": ParagraphStyle(
            "quote",
            parent=base["Normal"],
            fontName=regular,
            fontSize=10,
            leading=14,
            textColor=MUTED,
            leftIndent=12,
            borderColor=BORDER,
            borderWidth=0,
            borderPadding=6,
            backColor=colors.HexColor("#F8FBFF"),
            spaceAfter=8,
        ),
        "footer": ParagraphStyle(
            "footer",
            parent=base["Normal"],
            fontName=regular,
            fontSize=8,
            textColor=MUTED,
            alignment=TA_CENTER,
        ),
        "toc": ParagraphStyle(
            "toc",
            parent=base["Normal"],
            fontName=regular,
            fontSize=11,
            leading=18,
            leftIndent=8,
        ),
        "table_cell": ParagraphStyle(
            "table_cell",
            parent=base["Normal"],
            fontName=regular,
            fontSize=9.5,
            leading=13,
        ),
        "table_header": ParagraphStyle(
            "table_header",
            parent=base["Normal"],
            fontName=bold,
            fontSize=9.5,
            leading=13,
            textColor=PRIMARY_DARK,
        ),
    }


def escape_xml(text: str) -> str:
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    )


def inline_format(text: str, mono: str) -> str:
    text = escape_xml(text)
    text = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", text)
    text = re.sub(r"`([^`]+)`", rf'<font name="{mono}">\1</font>', text)
    return text


def parse_table(lines: list[str], styles: dict[str, ParagraphStyle]) -> Table:
    rows_raw = [line.strip().strip("|") for line in lines if line.strip()]
    parsed: list[list[str]] = []
    for i, row in enumerate(rows_raw):
        if i == 1 and re.match(r"^[-:|\s]+$", row.replace("|", "")):
            continue
        parsed.append([c.strip() for c in row.split("|")])

    if not parsed:
        return Table([[]])

    col_count = max(len(r) for r in parsed)
    data: list[list[Paragraph]] = []
    for ri, row in enumerate(parsed):
        while len(row) < col_count:
            row.append("")
        style = styles["table_header"] if ri == 0 else styles["table_cell"]
        data.append([Paragraph(inline_format(cell, "DejaVuMono"), style) for cell in row])

    table = Table(data, repeatRows=1, hAlign="LEFT")
    cmds = [
        ("BOX", (0, 0), (-1, -1), 0.5, BORDER),
        ("INNERGRID", (0, 0), (-1, -1), 0.25, BORDER),
        ("BACKGROUND", (0, 0), (-1, 0), TABLE_HEADER),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
    ]
    for r in range(1, len(data)):
        if r % 2 == 0:
            cmds.append(("BACKGROUND", (0, r), (-1, r), TABLE_ALT))
    table.setStyle(TableStyle(cmds))
    return table


def _append_bullet_list(target: list, items: list[str], styles: dict[str, ParagraphStyle]) -> None:
    bullets = [
        Paragraph(f"• {inline_format(item, 'DejaVuMono')}", styles["bullet"])
        for item in items
    ]
    if not bullets:
        return
    if len(bullets) <= 6:
        target.append(KeepTogether(bullets))
    else:
        target.extend(bullets)
    target.append(Spacer(1, 4))


def _peek_next_content_line(lines: list[str], start: int) -> str | None:
    for j in range(start, len(lines)):
        stripped = lines[j].strip()
        if stripped == "" or stripped == "---":
            continue
        return lines[j]
    return None


def parse_markdown(md: str, styles: dict[str, ParagraphStyle]) -> list:
    flow: list = []
    lines = md.splitlines()
    i = 0
    skip_title = True
    skip_preamble = True
    first_chapter = True
    first_subsection_in_chapter = True

    while i < len(lines):
        line = lines[i]

        if skip_title and line.startswith("# "):
            skip_title = False
            i += 1
            continue

        if skip_preamble:
            if line.startswith("## "):
                skip_preamble = False
            elif line.strip() == "" or line.startswith("---") or line.strip().startswith("**"):
                i += 1
                continue
            else:
                skip_preamble = False

        if line.startswith("---"):
            next_line = _peek_next_content_line(lines, i + 1)
            if next_line and next_line.startswith("## "):
                i += 1
                continue
            flow.append(Spacer(1, 4))
            flow.append(HRFlowable(width="100%", thickness=0.5, color=BORDER, spaceBefore=4, spaceAfter=8))
            i += 1
            continue

        if line.startswith("## "):
            if not first_chapter:
                flow.append(PageBreak())
            else:
                first_chapter = False
            first_subsection_in_chapter = True
            flow.append(Paragraph(inline_format(line[3:].strip(), "DejaVuMono"), styles["h1"]))
            i += 1
            continue

        if line.startswith("### "):
            if first_subsection_in_chapter:
                flow.append(CondPageBreak(SUBSECTION_MIN_SPACE))
                first_subsection_in_chapter = False
            else:
                flow.append(PageBreak())
            flow.append(Paragraph(inline_format(line[4:].strip(), "DejaVuMono"), styles["h2"]))
            i += 1
            continue

        if line.strip().startswith("|"):
            table_lines: list[str] = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                table_lines.append(lines[i])
                i += 1
            flow.append(parse_table(table_lines, styles))
            flow.append(Spacer(1, 8))
            continue

        if re.match(r"^\d+\.\s", line.strip()):
            items: list[str] = []
            while i < len(lines) and re.match(r"^\d+\.\s", lines[i].strip()):
                items.append(re.sub(r"^\d+\.\s*", "", lines[i].strip()))
                i += 1
            _append_bullet_list(flow, items, styles)
            continue

        if line.strip().startswith("- "):
            items = []
            while i < len(lines) and lines[i].strip().startswith("- "):
                items.append(lines[i].strip()[2:].strip())
                i += 1
            _append_bullet_list(flow, items, styles)
            continue

        if line.strip().startswith("> "):
            quote_lines: list[str] = []
            while i < len(lines) and lines[i].strip().startswith("> "):
                quote_lines.append(lines[i].strip()[2:].strip())
                i += 1
            flow.append(Paragraph(inline_format(" ".join(quote_lines), "DejaVuMono"), styles["quote"]))
            continue

        if line.strip() == "":
            i += 1
            continue

        if line.strip().startswith("*") and line.strip().endswith("*"):
            flow.append(Paragraph(inline_format(line.strip().strip("*"), "DejaVuMono"), styles["footer"]))
            i += 1
            continue

        para_lines = [line.strip()]
        i += 1
        while i < len(lines) and lines[i].strip() and not lines[i].startswith(("#", "|", "-", ">", "---")) and not re.match(r"^\d+\.\s", lines[i].strip()):
            para_lines.append(lines[i].strip())
            i += 1
        flow.append(Paragraph(inline_format(" ".join(para_lines), "DejaVuMono"), styles["body"]))

    return flow


def extract_meta(md: str) -> tuple[str, str]:
    title = "RFID Go GPS"
    version = ""
    for line in md.splitlines():
        if line.startswith("# "):
            title = line[2:].replace("–", "–").strip()
        if "**Verze aplikace:**" in line:
            version = line.split("**Verze aplikace:**", 1)[-1].strip()
        if title and version:
            break
    return title, version


def build_toc(styles: dict[str, ParagraphStyle]) -> list:
    entries = [
        "1. K čemu aplikace slouží",
        "2. Co budete potřebovat",
        "3. První spuštění",
        "4. Přehled obrazovky",
        "5. Jednoduchá příručka pro terén",
        "6. Rychlý start v terénu",
        "7. Zápis tagu",
        "8. Výběr UDU, výhybky a čipu",
        "9. GPS poloha",
        "10. Kontrola načteného tagu",
        "11. Hranice TUDU",
        "12. Práce s CSV souborem",
        "13. Časté situace a řešení",
        "14. Panel Pokročilé",
        "15. Slovníček",
    ]
    flow = [Paragraph("Obsah", styles["h1"]), Spacer(1, 6)]
    for entry in entries:
        flow.append(Paragraph(entry, styles["toc"]))
    flow.append(PageBreak())
    return flow


def add_page_number(canvas, doc) -> None:
    canvas.saveState()
    canvas.setFont("DejaVu", 8)
    canvas.setFillColor(MUTED)
    canvas.drawRightString(A4[0] - 2 * cm, 1.2 * cm, f"Strana {doc.page}")
    canvas.drawString(2 * cm, 1.2 * cm, "RFID Go GPS – Příručka pro uživatele")
    canvas.restoreState()


def main() -> None:
    regular, bold, mono = register_fonts()
    styles = build_styles(regular, bold, mono)
    md = MD_PATH.read_text(encoding="utf-8")
    title, version = extract_meta(md)

    doc = SimpleDocTemplate(
        str(OUT_PATH),
        pagesize=A4,
        leftMargin=2 * cm,
        rightMargin=2 * cm,
        topMargin=2 * cm,
        bottomMargin=2.2 * cm,
        title="RFID Go GPS – Příručka pro uživatele",
        author="RFID Go GPS",
    )

    story: list = []
    story.append(Spacer(1, 3 * cm))
    story.append(Paragraph(title, styles["title"]))
    story.append(Paragraph("Příručka pro běžné užívání v terénu", styles["subtitle"]))
    if version:
        story.append(Paragraph(f"Verze aplikace {version}", styles["subtitle"]))
    story.append(Spacer(1, 0.5 * cm))
    story.append(Paragraph("Chainway C5 · UHF RFID · GPS · DZS databáze", styles["subtitle"]))
    story.append(Spacer(1, 2 * cm))
    story.append(
        Paragraph(
            "Tento dokument popisuje každodenní práci s aplikací – přípravu, zápis tagů, "
            "kontrolu a export dat. Technické detaily vývoje nejsou součástí příručky.",
            styles["body"],
        )
    )
    story.append(PageBreak())
    story.extend(build_toc(styles))
    story.extend(parse_markdown(md, styles))

    doc.build(story, onFirstPage=add_page_number, onLaterPages=add_page_number)
    print(f"Generated: {OUT_PATH}")


if __name__ == "__main__":
    main()
