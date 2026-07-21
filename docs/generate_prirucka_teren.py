#!/usr/bin/env python3
"""Generate the simple field guide PDF for RFID Go GPS."""

from __future__ import annotations

from pathlib import Path

from reportlab.lib.pagesizes import A4
from reportlab.lib.units import cm
from reportlab.platypus import PageBreak, Paragraph, SimpleDocTemplate, Spacer

from generate_manual import MUTED, build_styles, extract_meta, parse_markdown, register_fonts

ROOT = Path(__file__).resolve().parent
MD_PATH = ROOT / "prirucka-teren.md"
OUT_PATH = ROOT / "RFID_Go_GPS_prirucka_teren.pdf"


def add_page_number(canvas, doc) -> None:
    canvas.saveState()
    canvas.setFont("DejaVu", 8)
    canvas.setFillColor(MUTED)
    canvas.drawRightString(A4[0] - 2 * cm, 1.2 * cm, f"Strana {doc.page}")
    canvas.drawString(2 * cm, 1.2 * cm, "RFID Go GPS – Příručka pro terén")
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
        title="RFID Go GPS – Příručka pro terén",
        author="RFID Go GPS",
    )

    story: list = []
    story.append(Spacer(1, 3.5 * cm))
    story.append(Paragraph("RFID Go GPS", styles["title"]))
    story.append(Paragraph("Příručka pro terén", styles["subtitle"]))
    if version:
        story.append(Paragraph(f"Verze aplikace {version}", styles["subtitle"]))
    story.append(Spacer(1, 1.5 * cm))
    story.append(
        Paragraph(
            "Každodenní práce v terénu – obrazovka, zápis jednoho tagu, "
            "barvy, výhybky a hranice TUDU. Hlavní postup je kapitola 3.",
            styles["body"],
        )
    )
    story.append(PageBreak())
    story.extend(parse_markdown(md, styles))

    doc.build(story, onFirstPage=add_page_number, onLaterPages=add_page_number)
    print(f"Generated: {OUT_PATH}")


if __name__ == "__main__":
    main()
