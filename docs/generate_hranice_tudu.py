#!/usr/bin/env python3
"""Generate a short PDF guide for the TUDU boundary (hranice TUDU) feature."""

from __future__ import annotations

from pathlib import Path

from reportlab.lib.pagesizes import A4
from reportlab.lib.units import cm
from reportlab.platypus import PageBreak, Paragraph, SimpleDocTemplate, Spacer

from generate_manual import MUTED, build_styles, extract_meta, parse_markdown, register_fonts

ROOT = Path(__file__).resolve().parent
MD_PATH = ROOT / "hranice-tudu.md"
OUT_PATH = ROOT / "RFID_Go_GPS_hranice_TUDU.pdf"


def add_page_number(canvas, doc) -> None:
    canvas.saveState()
    canvas.setFont("DejaVu", 8)
    canvas.setFillColor(MUTED)
    canvas.drawRightString(A4[0] - 2 * cm, 1.2 * cm, f"Strana {doc.page}")
    canvas.drawString(2 * cm, 1.2 * cm, "RFID Go GPS – Hranice TUDU")
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
        title="RFID Go GPS – Hranice TUDU",
        author="RFID Go GPS",
    )

    story: list = []
    story.append(Spacer(1, 4 * cm))
    story.append(Paragraph("Hranice TUDU", styles["title"]))
    story.append(Paragraph("Jak zapsat tag na hranici úseku tratě", styles["subtitle"]))
    if version:
        story.append(Paragraph(f"Verze aplikace {version}", styles["subtitle"]))
    story.append(Spacer(1, 1.5 * cm))
    story.append(
        Paragraph(
            "Krátký návod pro terén – bez technických detailů. "
            "Popisuje jen zápis tagu na hranici dvou úseků tratě.",
            styles["body"],
        )
    )
    story.append(PageBreak())
    story.extend(parse_markdown(md, styles))

    doc.build(story, onFirstPage=add_page_number, onLaterPages=add_page_number)
    print(f"Generated: {OUT_PATH}")


if __name__ == "__main__":
    main()
