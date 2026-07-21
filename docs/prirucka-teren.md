# RFID Go GPS – Příručka pro terén

**Verze aplikace:** 3.160  
**Zařízení:** Chainway C5 (čtečka s GPS)

Technické detaily (CSV, Pokročilé): `RFID_Go_GPS_prirucka.pdf`.

![Ikona](../fotky_pro_prirucku/ikona.png)

---

## Obsah

| # | Kapitola |
|---|----------|
| 1 | [Hlavní obrazovka](#1-hlavní-obrazovka) |
| 2 | [Zápis tagu](#2-zápis-tagu) |
| 3 | [Ruční výběr UDU / výhybky](#3-ruční-výběr-udu--výhybky) |
| 4 | [Hranice TUDU](#4-hranice-tudu) |
| 5 | [Kontrola](#5-kontrola) |
| 6 | [Když něco nejde](#6-když-něco-nejde) |
| 7 | [Slovníček](#7-slovníček) |

---

## 1. Hlavní obrazovka

![Hlavní obrazovka](../fotky_pro_prirucku/hlavni_strana.png)

| → | Prvek |
|---|--------|
| Nahoře | **Daleko** / **Blízko** – výkon čtečky (povinné před spouští) |
| Vedle režimu | GPS souřadnice (zelené = fix) |
| Čtyři tečky | průběh zápisu (EPC → CSV → heslo → zámek) |
| Tři kroky | **UDU** → **Načtení** → **Hotovo** |
| Karta | UDU, výhybka, čip – klepnutím ruční výběr |
| V kartě | **Hranice TUDU**, **Načíst polohu** |
| Uprostřed | který čip načítat; u 3částové **Jazyk** / **Rovně** / **Odbočka** |
| **Kontrola** | ověření tagu bez zápisu |
| Dole | poslední záznam + **Smazat** |

**Barvy kroků:** šedá = ne · modrá = běží · zelená ✓ = hotovo · oranžová = chybí Daleko/Blízko · červená = chyba

---

## 2. Zápis tagu

1. GPS fix → zkontrolujte UDU / výhybku / čip.
2. **Daleko** (tag v koleji) nebo **Blízko** (tag u antény).
3. U 3částové výhybky: **Jazyk** / **Rovně** / **Odbočka**.
4. Spoušť.
5. Dialog **„Načetli jste“** → **Pokračovat** nebo **Opakovat**.

Bez Daleko/Blízko spoušť nefunguje (Načtení oranžové).

### Úspěch

![Úspěšné načtení](../fotky_pro_prirucku/uspesne_nacteni.png)

| → | Prvek |
|---|--------|
| Tři zelené fajfky | zápis doběhl |
| Dialog | co se zapsalo (výhybka, čip) |
| **Pokračovat** | další čip |
| **Opakovat** | stejný čip znovu |

### Neúspěch

![Neúspěšné načtení](../fotky_pro_prirucku/neuspesne_nacteni.png)

| → | Prvek |
|---|--------|
| **Načtěte znovu** | načtení selhalo |
| Červené **Načtení** | totéž |
| Co zkusit | znovu přiložit, Daleko ↔ Blízko, jiná strana tagu |

---

## 3. Ruční výběr UDU / výhybky

GPS nabídne nejbližší výhybku. Ručně: klepněte na UDU nebo výhybku v kartě.  
Po ruční změně GPS nepřepíná, dokud neklepnete **Načíst polohu**.

### UDU

![Výběr UDU](../fotky_pro_prirucku/vyber_udu.png)

| → | Prvek |
|---|--------|
| Seznam | 10 nejbližších UDU podle vzdálenosti |
| Oranžové kolečko | aktuální výběr |
| **Zrušit** | zavřít beze změny |

### Výhybka

![Výběr výhybky](../fotky_pro_prirucku/vyber_vyhybky.png)

| → | Prvek |
|---|--------|
| Hledat… | filtr výhybek |
| Vzdálenost | u každé položky |
| **Chybí N čipů** | nedokončená výhybka |
| Zašedlé | hotové – nejdou vybrat |

**Typy:** 3částová = Jazyk/Rovně/Odbočka · 4částová = části z DB (CA/CB, …).  
Aplikace nastaví první chybějící čip.

---

## 4. Hranice TUDU

Zápis na hranici dvou úseků (ne běžná výhybka 1–4).

1. **Hranice TUDU** v kartě.
2. Vyplnit → **Použít**.
3. Daleko/Blízko → spoušť (jako běžný zápis).
4. Po zápisu režim skončí.

![Hranice TUDU](../fotky_pro_prirucku/hranice_tudu.png)

| → | Prvek |
|---|--------|
| **Vybrat TUDU z okolí** | výběr podle GPS |
| **TUDU (ručně)** | ruční kód úseku |
| **Objekt** | kolej / výhybka |
| **KM_EXT** | volitelné |
| **Použít** / **Zrušit** | potvrdit / zavřít |

Ukončení dřív: ruční změna UDU/výhybky, nebo **Načíst polohu**.

---

## 5. Kontrola

**Kontrola** = ověření už zapsaného tagu, **nic se nezapisuje**.

1. Daleko nebo Blízko.
2. Spoušť.
3. Údaje z tagu (+ z CSV, pokud tam je).

![Kontrola](../fotky_pro_prirucku/kontrola.png)

| → | Prvek |
|---|--------|
| **EPC** / **TID** | z tagu |
| **TUDU**, **OBJEKT**, … | z CSV |
| **Daleko** / **Blízko** | výkon při kontrole |
| ✕ | zavřít |

Tag mimo CSV → **„Tag není v CSV“**. Více shod → šipky.

---

## 6. Když něco nejde

| Problém | Řešení |
|---------|--------|
| Spoušť nic / Načtení oranžové | Daleko nebo Blízko |
| Načtení červené / Načtěte znovu | znovu, Daleko ↔ Blízko, jiná strana tagu |
| GPS nejde | volné místo / Testovací režim GPS |
| Špatná výhybka | klepnout na kartu → ruční výběr |
| Chyba po zápisu | **Smazat** u posledního záznamu → znovu |

---

## 7. Slovníček

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
