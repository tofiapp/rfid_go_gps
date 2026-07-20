#!/usr/bin/env python3
"""Generate the technical developer guide PDF for RFID Go GPS."""

from __future__ import annotations

from pathlib import Path

from reportlab.lib.pagesizes import A4
from reportlab.lib.units import cm
from reportlab.platypus import PageBreak, Paragraph, SimpleDocTemplate, Spacer

from generate_manual import MUTED, build_styles, extract_meta, parse_markdown, register_fonts

ROOT = Path(__file__).resolve().parent
MD_PATH = ROOT / "prirucka-vyvojare.md"
OUT_PATH = ROOT / "RFID_Go_GPS_prirucka_vyvojare.pdf"


def add_page_number(canvas, doc) -> None:
    canvas.saveState()
    canvas.setFont("DejaVu", 8)
    canvas.setFillColor(MUTED)
    canvas.drawRightString(A4[0] - 2 * cm, 1.2 * cm, f"Strana {doc.page}")
    canvas.drawString(2 * cm, 1.2 * cm, "RFID Go GPS – Technická příručka pro vývojáře")
    canvas.restoreState()


def main() -> None:
    regular, bold, mono = register_fonts()
    styles = build_styles(regular, bold, mono)
    md = MD_PATH.read_text(encoding="utf-8")
    _, version = extract_meta(md)

    doc = SimpleDocTemplate(
        str(OUT_PATH),
        pagesize=A4,
        leftMargin=2 * cm,
        rightMargin=2 * cm,
        topMargin=2 * cm,
        bottomMargin=2.2 * cm,
        title="RFID Go GPS – Technická příručka pro vývojáře",
        author="RFID Go GPS",
    )

    story: list = []
    story.append(Spacer(1, 3.5 * cm))
    story.append(Paragraph("RFID Go GPS", styles["title"]))
    story.append(Paragraph("Technická příručka pro vývojáře", styles["subtitle"]))
    if version:
        story.append(Paragraph(f"Verze aplikace {version}", styles["subtitle"]))
    story.append(Spacer(1, 1.5 * cm))
    story.append(
        Paragraph(
            "Architektura, stavové automaty, datové toky a reference tříd. "
            "Začněte kapitolou 0 (jak číst) a kapitolou 1 (velký obraz).",
            styles["body"],
        )
    )
    story.append(PageBreak())
    story.extend(parse_markdown(md, styles))

    doc.build(story, onFirstPage=add_page_number, onLaterPages=add_page_number)
    print(f"Generated: {OUT_PATH}")


if __name__ == "__main__":
    main()
