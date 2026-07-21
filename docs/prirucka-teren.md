# RFID Go GPS – Příručka pro terén

**Verze aplikace:** 3.159  
**Zařízení:** Chainway C5 (čtečka s GPS)

Každodenní práce v terénu. Technické detaily (CSV, Pokročilé): `RFID_Go_GPS_prirucka.pdf`.

![Ikona aplikace RFID Go GPS](../fotky_pro_prirucku/ikona.png)

*Ikona aplikace **RFID Go GPS** na čtečce.*

---

## Obsah

| # | Kapitola | Kdy číst |
|---|----------|----------|
| 1 | [Obrazovka](#1-obrazovka) | před první směnou |
| 2 | [Příprava](#2-příprava) | každý start |
| 3 | [Jeden tag](#3-jeden-tag) | hlavní práce |
| 4 | [Výhybky a čipy](#4-výhybky-a-čipy) | špatný / jiný výběr |
| 5 | [Hranice TUDU](#5-hranice-tudu) | zápis na hranici úseku |
| 6 | [Kontrola](#6-kontrola) | ověření už zapsaného tagu |
| 7 | [Když něco nejde](#7-když-něco-nejde) | problém |
| 8 | [Slovníček](#8-slovníček) | pojmy |

---

## 1. Obrazovka

Aplikace podle GPS najde **úsek** a **výhybku**, zapíše tag a uloží záznam včetně polohy čtečky.

![Hlavní obrazovka](../fotky_pro_prirucku/hlavni_strana.png)

*Hlavní obrazovka – GPS, tři kroky, karta UDU · výhybka, nápověda čipu a tlačítko Kontrola.*

| Oblast | Co tam je |
|--------|-----------|
| Horní lišta | Logo, přepínač **Daleko** / **Blízko**, GPS souřadnice, čtyři tečky průběhu zápisu |
| Tři kroky | **UDU** → **Načtení** → **Hotovo** (šedá / modrá / zelená ✓ / oranžová / červená) |
| Karta UDU · výhybka | úsek, výhybka, čip; **Hranice TUDU**, **Načíst polohu** |
| Střed | nápověda (*Načtěte Čip …*), u 3částové výhybky **Jazyk** / **Rovně** / **Odbočka** |
| **Kontrola** | ověření už zapsaného tagu (nic se nezapisuje) |
| Poslední záznam | naposledy zapsané; **Smazat** vrátí předchozí stav |

---

## 2. Příprava

1. Zapněte aplikaci a počkejte na **GPS** (zelené souřadnice v horní liště).
2. Aplikace načte databázi a doplní **UDU** a **výhybku**.
3. Zkontrolujte, že náhled sedí s tím, kde stojíte.

> **Bez GPS?** Volné místo, nebo v kartě UDU **Testovací režim GPS** a poloha ze seznamu.

---

## 3. Jeden tag

Před spouští **vždy** zvolte výkon v horní liště (**Vyberte režim**):

| Tlačítko | Kdy |
|----------|-----|
| **Daleko** | Tag v koleji, čtečka dál |
| **Blízko** | Tag u antény v ruce |

Bez výběru spouště nefunguje (krok **Načtení** je oranžový).

1. Zkontrolujte výhybku v náhledu.
2. Zvolte **Daleko** nebo **Blízko**.
3. U 3částové výhybky zvolte **Jazyk**, **Rovně** nebo **Odbočka**.
4. Přiložte tag a stiskněte **spouště**.
5. Po dialogu **„Načetli jste“** → **Pokračovat** (další čip) nebo **Opakovat** (stejný čip).

![Úspěšné načtení](../fotky_pro_prirucku/uspesne_nacteni.png)

*Úspěch: všechny tři kroky zelené, dialog „Načetli jste“ s výhybkou a čipem.*

Zápis je hotový **jen** když se objeví tento dialog. Červený krok **Načtení** a hláška **Načtěte znovu** znamenají neúspěch – přiložte tag znovu, případně přepněte Daleko ↔ Blízko.

![Neúspěšné načtení](../fotky_pro_prirucku/neuspesne_nacteni.png)

*Neúspěch: krok Načtení červený, nahoře „Načtěte znovu“.*

---

## 4. Výhybky a čipy

- **GPS** sám nabídne nejbližší výhybku.
- **Ručně:** klepněte na náhled UDU nebo výhybky a vyberte ze seznamu.
- Po ruční změně se GPS nepřepíná, dokud nekliknete **Načíst polohu**.
- Mezi nedokončenými výhybkami můžete přepínat kdykoli – zapsané čipy si aplikace pamatuje.

![Výběr UDU](../fotky_pro_prirucku/vyber_udu.png)

*Seznam **Nejbližší UDU (10)** – řazeno podle vzdálenosti, výběr oranžovým kolečkem.*

![Výběr výhybky](../fotky_pro_prirucku/vyber_vyhybky.png)

*Seznam výhybek v UDU – vzdálenost a kolik čipů chybí; kompletní jsou zašedlé.*

| Typ | Čipy | Části |
|-----|------|-------|
| 3částová | 3 | Jazyk, Rovně, Odbočka (volba u každého) |
| 4částová | 4 | Podle databáze (CA/CB, CG/CH, …) |

Aplikace nastaví **první chybějící čip**; nápověda je uprostřed obrazovky.

---

## 5. Hranice TUDU

Speciální zápis **na hranici dvou úseků** (ne běžná výhybka s čipy 1–4).

1. V kartě UDU · výhybka → **Hranice TUDU**.
2. Vyplňte dialog a potvrďte **Použít**:

![Dialog Hranice TUDU](../fotky_pro_prirucku/hranice_tudu.png)

*Dialog Hranice TUDU – TUDU z okolí nebo ručně, Objekt, volitelně KM_EXT.*

| Pole | Co napsat |
|------|-----------|
| **TUDU** | Kód úseku – **Vybrat TUDU z okolí**, nebo ručně |
| **Objekt** | Číslo koleje nebo výhybky (např. `12`, `5A`) |
| **KM_EXT** | Volitelné – lze doplnit později |

3. Dál stejně jako u běžného tagu: **Daleko** / **Blízko** → spouště.
4. Po zápisu režim sám skončí.

**Režim poznáte** podle nápisu **Režim hranice TUDU**.  
**Ukončení dřív:** ruční změna úseku/výhybky, nebo **Načíst polohu**.

---

## 6. Kontrola

Tlačítko **Kontrola** na hlavní obrazovce **ověří už zapsaný tag** – nic se nezapisuje.

1. Zvolte **Daleko** nebo **Blízko**.
2. Přiložte tag a stiskněte spouště.
3. Zobrazí se údaje z tagu a z CSV (pokud tag v tabulce je).

![Obrazovka Kontrola](../fotky_pro_prirucku/kontrola.png)

*Kontrola – EPC, TID, TUDU, objekt, pozice čipu, poloha, RO_ID a KM_EXT.*

Tag mimo CSV → hláška **„Tag není v CSV“**. Více shod v CSV → listování šipkami.

---

## 7. Když něco nejde

| Problém | Co zkusit |
|---------|-----------|
| Spouště nic nedělá / Načtení oranžové | **Daleko** nebo **Blízko** |
| Načtení červené / „Načtěte znovu“ | Přiložit znovu, přepnout Daleko ↔ Blízko, jiná strana tagu |
| GPS nefunguje | Volné místo, nebo **Testovací režim GPS** |
| Špatná výhybka | Náhled → ruční výběr |
| Chyba po zápisu | **Smazat** u posledního záznamu → znovu |

---

## 8. Slovníček

| Pojem | Význam |
|-------|--------|
| **UDU / TUDU** | Kód úseku tratě |
| **Výhybka** | Číslo výhybky (např. 45) |
| **Čip** | Část výhybky – jazyk, rovně nebo odbočka |
| **Daleko / Blízko** | Výkon čtečky (tag v koleji / u antény) |
| **Hranice TUDU** | Zápis na hranici dvou úseků |
| **Kontrola** | Ověření tagu bez zápisu |
| **CSV** | Tabulka záznamů (Stažené soubory) |

---

*Příručka pro terén – RFID Go GPS verze 3.159. Kompletní dokumentace: `RFID_Go_GPS_prirucka.pdf`.*
