# RFID Go GPS – Příručka pro terén

**Verze aplikace:** 3.160  
**Zařízení:** Chainway C5 (čtečka s GPS)

Technické detaily: `RFID_Go_GPS_prirucka.pdf`.

![Ikona](../fotky_pro_prirucku/ikona.png)

---

## Obsah

| # | Kapitola |
|---|----------|
| 1 | [Běžný zápis](#1-běžný-zápis) |
| 2 | [Špatné UDU / výhybka](#2-špatné-udu--výhybka) |
| 3 | [Hranice TUDU](#3-hranice-tudu) |
| 4 | [Kontrola](#4-kontrola) |
| 5 | [Když něco nejde](#5-když-něco-nejde) |
| 6 | [Slovníček](#6-slovníček) |

---

## 1. Běžný zápis

1. Zapněte aplikaci → zelené GPS nahoře.
2. V kartě zkontrolujte **UDU**, **výhybku**, **čip**. Špatně → kap. 2.
3. **Daleko** (tag v koleji) nebo **Blízko** (u antény). Bez toho spoušť nefunguje (Načtení oranžové).
4. U 3částové výhybky: **Jazyk** / **Rovně** / **Odbočka**.
5. Spoušť.
6. Dialog **„Načetli jste“** → **Pokračovat** / **Opakovat**.

![Hlavní obrazovka](../fotky_pro_prirucku/hlavni_strana.png)

*Nahoře Daleko/Blízko a GPS · tři kroky UDU → Načtení → Hotovo · karta s výběrem · uprostřed nápověda čipu.*

**Barvy kroků:** šedá = ne · modrá = běží · zelená ✓ = hotovo · oranžová = chybí Daleko/Blízko · červená = chyba

### Úspěch

Zápis platí jen s dialogem **„Načetli jste“**.

![Úspěšné načtení](../fotky_pro_prirucku/uspesne_nacteni.png)

### Neúspěch

![Neúspěšné načtení](../fotky_pro_prirucku/neuspesne_nacteni.png)

*Červené Načtení + „Načtěte znovu“ = selhalo → znovu přiložit, Daleko ↔ Blízko, jiná strana tagu.*

---

## 2. Špatné UDU / výhybka

Klepněte v kartě na UDU nebo výhybku.  
Po ruční změně GPS nepřepíná, dokud neklepnete **Načíst polohu**.

![Výběr UDU](../fotky_pro_prirucku/vyber_udu.png)

![Výběr výhybky](../fotky_pro_prirucku/vyber_vyhybky.png)

*U výhybky: **Chybí N čipů** = nedokončená · zašedlé = hotové (nejdou vybrat).*

3částová = Jazyk/Rovně/Odbočka · 4částová = části z DB. Aplikace nastaví první chybějící čip.

---

## 3. Hranice TUDU

Zápis na hranici dvou úseků (ne výhybka 1–4).

1. **Hranice TUDU** v kartě.
2. Vyplnit TUDU, Objekt (KM_EXT volitelné) → **Použít**.
3. Daleko/Blízko → spoušť.
4. Po zápisu režim skončí.

![Hranice TUDU](../fotky_pro_prirucku/hranice_tudu.png)

Ukončení dřív: změna UDU/výhybky, nebo **Načíst polohu**.

---

## 4. Kontrola

Ověření už zapsaného tagu – **nic se nezapisuje**.

1. Daleko nebo Blízko.
2. Spoušť → údaje z tagu (+ z CSV, pokud tam je).

![Kontrola](../fotky_pro_prirucku/kontrola.png)

Mimo CSV → **„Tag není v CSV“**. Více shod → šipky.

---

## 5. Když něco nejde

| Problém | Řešení |
|---------|--------|
| Spoušť nic / Načtení oranžové | Daleko nebo Blízko |
| Načtení červené / Načtěte znovu | znovu, Daleko ↔ Blízko, jiná strana tagu |
| GPS nejde | volné místo / Testovací režim GPS |
| Špatná výhybka | kap. 2 |
| Chyba po zápisu | **Smazat** u posledního záznamu → znovu |

---

## 6. Slovníček

| Pojem | Význam |
|-------|--------|
| **UDU / TUDU** | úsek tratě |
| **Výhybka** | číslo výhybky |
| **Čip** | část výhybky |
| **Daleko / Blízko** | výkon čtečky |
| **Hranice TUDU** | zápis na hranici úseků |
| **Kontrola** | čtení bez zápisu |
| **CSV** | tabulka záznamů (Stažené soubory) |

---

*RFID Go GPS 3.160 – příručka pro terén. Kompletní dokumentace: `RFID_Go_GPS_prirucka.pdf`.*
