#!/usr/bin/env python3
"""Generate the compact field guide PDF for RFID Go GPS."""

from __future__ import annotations

from pathlib import Path

from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import cm
from reportlab.platypus import HRFlowable, Paragraph, SimpleDocTemplate, Spacer

from generate_manual import (
    BORDER,
    MUTED,
    PRIMARY_DARK,
    build_styles,
    extract_meta,
    parse_markdown,
    register_fonts,
)

ROOT = Path(__file__).resolve().parent
MD_PATH = ROOT / "prirucka-teren.md"
OUT_PATH = ROOT / "RFID_Go_GPS_prirucka_teren.pdf"


def add_page_number(canvas, doc) -> None:
    canvas.saveState()
    canvas.setFont("DejaVu", 8)
    canvas.setFillColor(MUTED)
    canvas.drawRightString(A4[0] - 1.4 * cm, 0.9 * cm, f"{doc.page}")
    canvas.drawString(1.4 * cm, 0.9 * cm, "RFID Go GPS – terén")
    canvas.restoreState()


def main() -> None:
    regular, bold, mono = register_fonts()
    styles = build_styles(regular, bold, mono)
    styles["h1"] = ParagraphStyle(
        "teren_h1",
        parent=styles["h1"],
        fontSize=13,
        leading=16,
        spaceBefore=2,
        spaceAfter=5,
        textColor=PRIMARY_DARK,
    )
    styles["h2"] = ParagraphStyle(
        "teren_h2",
        parent=styles["h2"],
        fontSize=11,
        leading=14,
        spaceBefore=6,
        spaceAfter=3,
    )
    styles["body"] = ParagraphStyle(
        "teren_body",
        parent=styles["body"],
        fontSize=9.5,
        leading=12.5,
        spaceAfter=3,
        alignment=TA_LEFT,
    )
    styles["bullet"] = ParagraphStyle(
        "teren_bullet",
        parent=styles["bullet"],
        fontSize=9.5,
        leading=12.5,
        spaceAfter=1,
    )
    styles["footer"] = ParagraphStyle(
        "teren_caption",
        parent=styles["footer"],
        fontSize=8,
        leading=10,
        textColor=MUTED,
        spaceBefore=1,
        spaceAfter=3,
        alignment=TA_CENTER,
    )
    styles["title_line"] = ParagraphStyle(
        "teren_title",
        parent=styles["h1"],
        fontSize=16,
        leading=20,
        alignment=TA_CENTER,
        spaceAfter=2,
    )
    styles["meta"] = ParagraphStyle(
        "teren_meta",
        parent=styles["footer"],
        alignment=TA_CENTER,
        spaceAfter=6,
    )

    md = MD_PATH.read_text(encoding="utf-8")
    _, version = extract_meta(md)

    doc = SimpleDocTemplate(
        str(OUT_PATH),
        pagesize=A4,
        leftMargin=1.4 * cm,
        rightMargin=1.4 * cm,
        topMargin=1.2 * cm,
        bottomMargin=1.4 * cm,
        title="RFID Go GPS – Příručka pro terén",
        author="RFID Go GPS",
    )

    story: list = [
        Paragraph("RFID Go GPS – Příručka pro terén", styles["title_line"]),
    ]
    if version:
        story.append(Paragraph(f"Verze {version} · Chainway C5", styles["meta"]))
    story.append(
        HRFlowable(width="100%", thickness=0.8, color=BORDER, spaceBefore=0, spaceAfter=8)
    )
    story.extend(parse_markdown(md, styles, md_path=MD_PATH, compact=True))

    doc.build(story, onFirstPage=add_page_number, onLaterPages=add_page_number)
    print(f"Generated: {OUT_PATH}")


if __name__ == "__main__":
    main()
