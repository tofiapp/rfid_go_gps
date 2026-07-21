#!/usr/bin/env python3
"""Generate documentation UI mockups for the field guide from known screen layouts."""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent / "teren"
OUT.mkdir(parents=True, exist_ok=True)

W, H = 540, 960
PRIMARY = (0, 100, 148)
PRIMARY_DARK = (0, 75, 113)
ACCENT = (230, 81, 0)
VYHYBKA = (21, 101, 192)
BG = (244, 246, 248)
CARD = (255, 255, 255)
TEXT = (27, 27, 31)
MUTED = (92, 102, 112)
ERR = (183, 28, 28)
GREEN = (46, 125, 50)
GREEN_GPS = (56, 142, 60)
LIGHT_BLUE = (200, 230, 255)
LIGHT_ORANGE = (255, 219, 204)
GREY = (189, 189, 189)
LINE = (224, 224, 224)

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
FONT_B = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(FONT_B if bold else FONT, size)


def round_rect(draw: ImageDraw.ImageDraw, box, fill, radius=16, outline=None, width=1):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def phone_base(title_time: str = "9:00") -> tuple[Image.Image, ImageDraw.ImageDraw]:
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    # status bar
    d.rectangle((0, 0, W, 36), fill=CARD)
    d.text((16, 10), title_time, font=font(14), fill=TEXT)
    d.text((W - 90, 10), "GPS  🔋", font=font(12), fill=MUTED)
    return img, d


def header_bar(d: ImageDraw.ImageDraw, y: int, mode: str = "blizko", status: str = "") -> int:
    # logo
    round_rect(d, (12, y, 92, y + 52), CARD, 10, outline=LINE)
    d.text((22, y + 8), "RFID", font=font(12, True), fill=PRIMARY_DARK)
    d.text((30, y + 26), "Go", font=font(14, True), fill=ACCENT)

    # Daleko / Blízko toggle
    tx, ty = 110, y + 8
    round_rect(d, (tx, ty, tx + 210, ty + 36), (230, 230, 230), 18)
    if mode == "blizko":
        round_rect(d, (tx + 100, ty + 2, tx + 208, ty + 34), ACCENT, 16)
        d.text((tx + 22, ty + 9), "Daleko", font=font(13), fill=MUTED)
        d.text((tx + 125, ty + 9), "Blízko", font=font(13, True), fill=CARD)
    else:
        round_rect(d, (tx + 2, ty + 2, tx + 108, ty + 34), ACCENT, 16)
        d.text((tx + 22, ty + 9), "Daleko", font=font(13, True), fill=CARD)
        d.text((tx + 125, ty + 9), "Blízko", font=font(13), fill=MUTED)

    if status:
        color = ERR if "Načtěte" in status or "znovu" in status else MUTED
        d.text((340, ty + 10), status, font=font(12, True), fill=color)
    return y + 60


def gps_line(d: ImageDraw.ImageDraw, y: int, text: str = "50.2145° 15.8087° ±5m") -> int:
    d.text((W // 2 - 110, y), text, font=font(14, True), fill=GREEN_GPS)
    return y + 28


def steps(d: ImageDraw.ImageDraw, y: int, states: tuple[str, str, str]) -> int:
    """states: done|active|pending|error|warn for UDU, Načtení, Hotovo"""
    labels = ["UDU", "Načtení", "Hotovo"]
    xs = [90, 270, 450]
    for i, (x, lab, st) in enumerate(zip(xs, labels, states)):
        if st == "done":
            d.ellipse((x - 18, y, x + 18, y + 36), fill=GREEN)
            d.text((x - 7, y + 6), "✓", font=font(16, True), fill=CARD)
        elif st == "active":
            d.ellipse((x - 18, y, x + 18, y + 36), fill=ACCENT)
            d.text((x - 5, y + 7), str(i + 1), font=font(15, True), fill=CARD)
        elif st == "error":
            d.ellipse((x - 18, y, x + 18, y + 36), fill=ERR)
            d.text((x - 5, y + 7), str(i + 1), font=font(15, True), fill=CARD)
        elif st == "warn":
            d.ellipse((x - 18, y, x + 18, y + 36), fill=ACCENT)
            d.text((x - 5, y + 7), str(i + 1), font=font(15, True), fill=CARD)
        else:
            d.ellipse((x - 18, y, x + 18, y + 36), fill=GREY)
            d.text((x - 5, y + 7), str(i + 1), font=font(15, True), fill=CARD)
        d.text((x - 22, y + 42), lab, font=font(11), fill=MUTED)
        if i < 2:
            d.line((x + 22, y + 18, xs[i + 1] - 22, y + 18), fill=LINE, width=3)
    return y + 70


def save(img: Image.Image, name: str) -> None:
    path = OUT / name
    img.save(path, "PNG", optimize=True)
    print(f"Wrote {path}")


def make_01_hlavni() -> None:
    img, d = phone_base("8:59")
    y = header_bar(d, 44, "blizko", "Vyberte režim")
    y = gps_line(d, y)
    y = steps(d, y, ("done", "active", "pending"))

    round_rect(d, (16, y, W - 16, y + 130), CARD, 14, outline=LINE)
    d.text((28, y + 12), "UDU · Výhybka", font=font(13, True), fill=PRIMARY_DARK)
    # buttons
    round_rect(d, (280, y + 8, 390, y + 36), CARD, 8, outline=PRIMARY, width=2)
    d.text((288, y + 14), "Hranice TUDU", font=font(10), fill=PRIMARY)
    round_rect(d, (400, y + 8, 520, y + 36), CARD, 8, outline=ACCENT, width=2)
    d.text((410, y + 14), "Načíst polohu", font=font(10), fill=ACCENT)
    d.text((28, y + 50), "UDU", font=font(11), fill=MUTED)
    d.text((28, y + 68), "1302F", font=font(18, True), fill=TEXT)
    d.text((160, y + 50), "Výhybka", font=font(11), fill=MUTED)
    d.text((160, y + 68), "45", font=font(18, True), fill=VYHYBKA)
    d.text((280, y + 50), "Čip", font=font(11), fill=MUTED)
    d.text((280, y + 68), "1/3", font=font(18, True), fill=TEXT)
    y += 150

    d.text((W // 2 - 140, y), "Načtěte Čip 1, Výhybky 45", font=font(16, True), fill=TEXT)
    y += 36
    # Jazyk / Rovně / Odbočka
    segs = [("Jazyk", True), ("Rovně", False), ("Odbočka", False)]
    x0 = 40
    for label, on in segs:
        box = (x0, y, x0 + 140, y + 40)
        round_rect(d, box, ACCENT if on else CARD, 10, outline=ACCENT if on else LINE, width=2)
        d.text((x0 + 35, y + 11), label, font=font(13, True), fill=CARD if on else TEXT)
        x0 += 155
    y += 60

    round_rect(d, (160, y, 380, y + 48), PRIMARY, 12)
    d.text((220, y + 14), "Kontrola", font=font(16, True), fill=CARD)
    y += 70

    round_rect(d, (16, y, W - 16, y + 90), (237, 241, 237), 12, outline=(197, 206, 197))
    d.text((28, y + 12), "Poslední záznam", font=font(12, True), fill=MUTED)
    d.text((28, y + 40), "Výhybka 46   Čip 3/3", font=font(14), fill=TEXT)
    round_rect(d, (400, y + 36, 510, y + 68), ERR, 8)
    d.text((420, y + 44), "Smazat", font=font(12, True), fill=CARD)
    y += 110
    d.text((W // 2 - 40, H - 40), "Pokročilé", font=font(12), fill=MUTED)
    save(img, "01_hlavni_nacteni.png")


def make_02_uspech() -> None:
    img, d = phone_base("9:00")
    y = header_bar(d, 44, "blizko", "")
    # four green dots
    for i, x in enumerate((400, 420, 440, 460)):
        d.ellipse((x, 58, x + 12, 70), fill=GREEN)
    y = gps_line(d, y)
    y = steps(d, y, ("done", "done", "done"))

    # dim overlay
    overlay = Image.new("RGBA", (W, H), (0, 0, 0, 90))
    img = img.convert("RGBA")
    img.alpha_composite(overlay)
    d = ImageDraw.Draw(img)

    box = (50, 280, W - 50, 680)
    round_rect(d, box, CARD, 18)
    d.text((170, 310), "Načetli jste", font=font(20, True), fill=PRIMARY_DARK)
    round_rect(d, (90, 370, W - 90, 430), LIGHT_BLUE, 12)
    d.text((170, 388), "Výhybka 45", font=font(18, True), fill=VYHYBKA)
    round_rect(d, (90, 450, W - 90, 510), LIGHT_BLUE, 12)
    d.text((200, 468), "Čip ", font=font(18, True), fill=VYHYBKA)
    d.text((255, 468), "1", font=font(18, True), fill=ACCENT)

    round_rect(d, (90, 550, W - 90, 600), PRIMARY, 12)
    d.text((200, 564), "Pokračovat", font=font(16, True), fill=CARD)
    round_rect(d, (90, 615, W - 90, 665), ERR, 12)
    d.text((215, 629), "Opakovat", font=font(16, True), fill=CARD)
    save(img.convert("RGB"), "02_dialog_nacetli.png")


def make_03_chyba() -> None:
    img, d = phone_base("9:34")
    y = header_bar(d, 44, "blizko", "Načtěte znovu")
    y = gps_line(d, y, "50.2145° 15.8087° ±72m")
    y = steps(d, y, ("done", "error", "pending"))
    d.text((60, y + 40), "Zápis se nepodařil – přiložte tag znovu", font=font(14), fill=ERR)
    d.text((60, y + 70), "a stiskněte spouště. Případně přepněte", font=font(14), fill=MUTED)
    d.text((60, y + 95), "Daleko ↔ Blízko.", font=font(14), fill=MUTED)
    save(img, "03_nacteni_chyba.png")


def make_04_vyhybka() -> None:
    img = Image.new("RGB", (W, 720), CARD)
    d = ImageDraw.Draw(img)
    d.text((24, 24), "Vyberte výhybku (97 v UDU, 9 bez GPS v DB)", font=font(14, True), fill=TEXT)
    # search
    round_rect(d, (24, 60, W - 24, 110), BG, 10, outline=ACCENT, width=2)
    d.text((40, 76), "🔍  Hledat výhybku...", font=font(14), fill=MUTED)

    rows = [
        ("Výhybka 45", "51 m", "Chybí 2 čipy", True),
        ("Výhybka 42", "74 m", "", False),
        ("Výhybka 49", "85 m", "", False),
        ("Výhybka 41", "110 m", "", False),
        ("Výhybka 50", "140 m", "", False),
    ]
    y = 130
    for name, dist, note, sel in rows:
        d.line((24, y, W - 24, y), fill=LINE)
        # name with blue number
        parts = name.rsplit(" ", 1)
        d.text((32, y + 14), parts[0] + " ", font=font(15), fill=TEXT)
        tw = d.textlength(parts[0] + " ", font=font(15))
        d.text((32 + tw, y + 14), parts[1], font=font(15, True), fill=VYHYBKA)
        d.text((32, y + 38), dist, font=font(12), fill=MUTED)
        if note:
            d.text((110, y + 38), "Chybí ", font=font(12), fill=MUTED)
            d.text((160, y + 38), "2", font=font(12, True), fill=ACCENT)
            d.text((175, y + 38), " čipy", font=font(12), fill=MUTED)
        # radio
        cx, cy = W - 50, y + 30
        d.ellipse((cx - 12, cy - 12, cx + 12, cy + 12), outline=ACCENT if sel else GREY, width=2)
        if sel:
            d.ellipse((cx - 6, cy - 6, cx + 6, cy + 6), fill=ACCENT)
        y += 70
    d.text((W - 100, y + 20), "Zrušit", font=font(14, True), fill=PRIMARY)
    save(img, "04_vyber_vyhybky.png")


def make_05_udu() -> None:
    img = Image.new("RGB", (W, 520), CARD)
    d = ImageDraw.Draw(img)
    d.text((24, 24), "Nejbližší UDU (10)", font=font(16, True), fill=TEXT)
    rows = [
        ("1302F", "52 m", True),
        ("1302U", "1.2 km", False),
        ("13021", "1.5 km", False),
        ("1631G", "3.1 km", False),
        ("1601B", "3.7 km", False),
        ("1302T", "3.9 km", False),
    ]
    y = 70
    for code, dist, sel in rows:
        d.line((24, y, W - 24, y), fill=LINE)
        d.text((32, y + 18), f"{code}  ·  {dist}", font=font(15), fill=TEXT)
        cx, cy = W - 50, y + 28
        d.ellipse((cx - 12, cy - 12, cx + 12, cy + 12), outline=ACCENT if sel else GREY, width=2)
        if sel:
            d.ellipse((cx - 6, cy - 6, cx + 6, cy + 6), fill=ACCENT)
        y += 58
    d.text((W - 100, y + 16), "Zrušit", font=font(14, True), fill=PRIMARY)
    save(img, "05_nejblizsi_udu.png")


def make_06_hranice() -> None:
    img = Image.new("RGB", (W, 640), CARD)
    d = ImageDraw.Draw(img)
    d.text((24, 24), "Hranice TUDU", font=font(20, True), fill=PRIMARY_DARK)
    d.text((24, 70), "TUDU", font=font(12), fill=MUTED)
    round_rect(d, (24, 92, W - 24, 140), CARD, 10, outline=PRIMARY, width=2)
    d.text((40, 108), "◎  Vybrat TUDU z okolí", font=font(14, True), fill=PRIMARY)

    d.text((24, 160), "TUDU (ručně)", font=font(12), fill=MUTED)
    round_rect(d, (24, 182, W - 24, 230), BG, 10, outline=LINE)
    d.text((40, 198), "1302FA", font=font(15), fill=TEXT)

    d.text((24, 250), "Objekt", font=font(12), fill=MUTED)
    round_rect(d, (24, 272, W - 24, 320), BG, 10, outline=LINE)
    d.text((40, 288), "Kolej / výhybka", font=font(15), fill=TEXT)

    d.text((24, 340), "KM_EXT (volitelné)", font=font(12), fill=MUTED)
    round_rect(d, (24, 362, W - 24, 410), BG, 10, outline=LINE)
    d.text((40, 378), "KM_EXT – lze doplnit později", font=font(14), fill=MUTED)

    d.text((280, 480), "Zrušit", font=font(14, True), fill=PRIMARY)
    round_rect(d, (370, 465, W - 24, 515), PRIMARY, 12)
    d.text((400, 480), "Použít", font=font(14, True), fill=CARD)
    save(img, "06_hranice_tudu.png")


def make_07_kontrola() -> None:
    img = Image.new("RGB", (W, 900), CARD)
    d = ImageDraw.Draw(img)
    d.text((24, 24), "Kontrola", font=font(20, True), fill=PRIMARY_DARK)
    # toggle
    round_rect(d, (200, 20, 410, 56), (230, 230, 230), 18)
    round_rect(d, (300, 22, 408, 54), ACCENT, 16)
    d.text((222, 31), "Daleko", font=font(13), fill=MUTED)
    d.text((325, 31), "Blízko", font=font(13, True), fill=CARD)
    d.text((W - 40, 28), "✕", font=font(18), fill=MUTED)

    d.text((24, 80), "ID RFID 636", font=font(16, True), fill=TEXT)

    fields = [
        ("EPC", "E28011C020000715458A0317", True),
        ("TID", "E28011C020000715458A0317", False),
        ("TUDU", "1302FA", False),
        ("OBJEKT", "45", True),
        ("POZICE", "1", False),
        ("POLOHA", "JAP", False),
        ("RO_ID_1", "7663292", False),
        ("RO_ID_2", "—", False),
        ("KM_EXT", "22.393", False),
    ]
    y = 120
    for label, value, accent in fields:
        bg = LIGHT_ORANGE if accent else LIGHT_BLUE
        fg = ACCENT if accent else PRIMARY_DARK
        round_rect(d, (24, y, W - 24, y + 64), bg, 12)
        d.text((36, y + 8), label, font=font(11), fill=MUTED)
        # wrap long values
        if len(value) > 22:
            d.text((36, y + 28), value[:18], font=font(13, True), fill=fg)
            d.text((36, y + 44), value[18:], font=font(13, True), fill=fg)
        else:
            d.text((36, y + 30), value, font=font(14, True), fill=fg)
        y += 74
    save(img, "07_kontrola.png")


def make_08_logo() -> None:
    # Cover-style image using real launcher foreground if available
    logo_path = Path(__file__).resolve().parents[2] / "app/src/main/res/drawable-nodpi/ic_launcher_foreground_img.png"
    img = Image.new("RGB", (720, 720), PRIMARY)
    # subtle pattern
    d = ImageDraw.Draw(img)
    for i in range(0, 720, 24):
        d.line((0, i, 720, i + 120), fill=(0, 120, 170), width=1)
    if logo_path.exists():
        logo = Image.open(logo_path).convert("RGBA")
        logo = logo.resize((360, 360), Image.Resampling.LANCZOS)
        # white rounded plate
        plate = Image.new("RGBA", (400, 400), (0, 0, 0, 0))
        pd = ImageDraw.Draw(plate)
        pd.rounded_rectangle((0, 0, 399, 399), radius=48, fill=(255, 255, 255, 255))
        img.paste(plate, (160, 100), plate)
        img.paste(logo, (180, 120), logo)
    d = ImageDraw.Draw(img)
    d.text((180, 560), "RFID Go GPS", font=font(36, True), fill=CARD)
    d.text((200, 620), "Příručka pro terén", font=font(20), fill=(200, 230, 255))
    save(img, "08_logo_cover.png")


def main() -> None:
    make_01_hlavni()
    make_02_uspech()
    make_03_chyba()
    make_04_vyhybka()
    make_05_udu()
    make_06_hranice()
    make_07_kontrola()
    make_08_logo()


if __name__ == "__main__":
    main()
