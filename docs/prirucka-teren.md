# RFID Go GPS – Příručka pro terén

**Verze aplikace:** 3.159  
**Zařízení:** Chainway C5 (čtečka s GPS)

Každodenní práce v terénu – od spuštění po zápis tagu. Technické detaily (CSV, Pokročilé): `RFID_Go_GPS_prirucka.pdf`.

![Ikona aplikace RFID Go GPS](../fotky_pro_prirucku/ikona.png)

*Aplikaci spustíte ikonou **RFID Go GPS** na ploše čtečky.*

---

## Obsah

| # | Kapitola | Kdy číst |
|---|----------|----------|
| 1 | [Obrazovka](#1-obrazovka) | před první směnou |
| 2 | [Příprava](#2-příprava) | každý start |
| 3 | [Zápis tagu](#3-zápis-tagu) | hlavní práce |
| 4 | [Výběr UDU a výhybky](#4-výběr-udu-a-výhybky) | špatný / jiný výběr |
| 5 | [Hranice TUDU](#5-hranice-tudu) | zápis na hranici úseku |
| 6 | [Kontrola](#6-kontrola) | ověření už zapsaného tagu |
| 7 | [Když něco nejde](#7-když-něco-nejde) | problém |
| 8 | [Slovníček](#8-slovníček) | pojmy |

---

## 1. Obrazovka

Jedna hlavní obrazovka: GPS najde **úsek** a **výhybku**, vy zapíšete tag, aplikace uloží záznam včetně polohy čtečky.

| Oblast | Co tam je |
|--------|-----------|
| Horní lišta | Logo, **Daleko** / **Blízko**, GPS, čtyři tečky průběhu zápisu |
| Tři kroky | **UDU** → **Načtení** → **Hotovo** |
| Barvy kroků | šedá = ještě ne · modrá = probíhá · zelená ✓ = hotovo · oranžová = chybí režim · červená = chyba |
| Karta UDU · výhybka | úsek, výhybka, čip; **Hranice TUDU**, **Načíst polohu** |
| Střed | nápověda čipu; u 3částové výhybky **Jazyk** / **Rovně** / **Odbočka** |
| **Kontrola** | ověření už zapsaného tagu (bez zápisu) |
| Poslední záznam | naposledy zapsané; **Smazat** vrátí předchozí stav |

![Hlavní obrazovka](../fotky_pro_prirucku/hlavni_strana.png)

*Ukázka před zápisem: GPS je zelená, doplněné jsou UDU **1302F**, výhybka **45** a čip **1/3**. Oranžový krok **Načtení** a nápis **Vyberte režim** znamenají, že ještě není zvoleno **Daleko** / **Blízko**. Uprostřed je nápověda „Načtěte Čip 1“ a volba **Jazyk** / **Rovně** / **Odbočka**.*

---

## 2. Příprava

1. Zapněte aplikaci a počkejte na **GPS** (zelené souřadnice nahoře).
2. Počkejte, až se načte databáze a doplní **UDU** s **výhybkou**.
3. Ověřte, že náhled sedí s místem, kde stojíte.

> **Bez GPS?** Vyjděte na volné místo, nebo v kartě UDU zapněte **Testovací režim GPS** a vyberte polohu ze seznamu.

---

## 3. Zápis tagu

### Výkon čtečky

Před každou spouští zvolte v horní liště režim (**Vyberte režim**):

| Tlačítko | Kdy použít |
|----------|------------|
| **Daleko** | Tag je v koleji, čtečka dál |
| **Blízko** | Tag držíte u antény |

Bez výběru spouště nefunguje – krok **Načtení** zůstane oranžový.

### Postup

1. Zkontrolujte výhybku a čip v náhledu.
2. Zvolte **Daleko** nebo **Blízko**.
3. U 3částové výhybky zvolte **Jazyk**, **Rovně** nebo **Odbočka**.
4. Přiložte tag a stiskněte **spoušť**.
5. V dialogu **„Načetli jste“** zvolte **Pokračovat** (další čip) nebo **Opakovat** (stejný čip).

### Úspěch

Zápis platí **jen** když se objeví dialog „Načetli jste“.

![Úspěšné načtení](../fotky_pro_prirucku/uspesne_nacteni.png)

*Úspěšný zápis: všechny tři kroky mají zelenou fajfku, nahoře čtyři zelené tečky. Dialog ukazuje výhybku a čip – **Pokračovat** jde dál, **Opakovat** znovu zapíše stejný čip.*

### Neúspěch

![Neúspěšné načtení](../fotky_pro_prirucku/neuspesne_nacteni.png)

*Načtení selhalo: krok **Načtení** je červený a nahoře svítí **Načtěte znovu**. Tag znovu přiložte, případně přepněte **Daleko** ↔ **Blízko** nebo otočte tag.*

---

## 4. Výběr UDU a výhybky

- **GPS** sám nabídne nejbližší výhybku.
- **Ručně:** klepněte v náhledu na UDU nebo na výhybku.
- Po ruční změně se GPS nepřepíná, dokud neklepnete **Načíst polohu**.
- Mezi nedokončenými výhybkami můžete přepínat kdykoli – zapsané čipy zůstanou.

### Ruční výběr UDU

![Výběr UDU](../fotky_pro_prirucku/vyber_udu.png)

*Seznam **Nejbližší UDU (10)** po klepnutí na kód úseku. Úseky jsou seřazené podle vzdálenosti; vybraný má oranžové kolečko. Zrušit zavře seznam beze změny.*

### Ruční výběr výhybky

![Výběr výhybky](../fotky_pro_prirucku/vyber_vyhybky.png)

*Seznam výhybek v aktuálním UDU (hledání nahoře). U každé je vzdálenost; u nedokončených oranžově kolik čipů chybí (např. **Chybí 2 čipy**). Kompletní výhybky jsou zašedlé a nejdou vybrat.*

### Typy výhybek

| Typ | Čipy | Co volíte |
|-----|------|-----------|
| 3částová | 3 | **Jazyk**, **Rovně**, **Odbočka** u každého čipu |
| 4částová | 4 | Části podle databáze (CA/CB, CG/CH, …) |

Aplikace vždy nastaví **první chybějící čip**; nápověda je uprostřed obrazovky.

---

## 5. Hranice TUDU

Zápis **na hranici dvou úseků** – ne běžná výhybka s čipy 1–4.

1. V kartě UDU · výhybka klepněte na **Hranice TUDU**.
2. Vyplňte dialog a potvrďte **Použít**.

![Dialog Hranice TUDU](../fotky_pro_prirucku/hranice_tudu.png)

*Dialog hranice: **Vybrat TUDU z okolí** nabídne nejbližší úseky, nebo kód napíšete ručně. Do **Objekt** patří číslo koleje či výhybky. **KM_EXT** je volitelné a lze doplnit později.*

| Pole | Co vyplnit |
|------|------------|
| **TUDU** | Kód úseku – z okolí, nebo ručně |
| **Objekt** | Číslo koleje nebo výhybky (např. `12`, `5A`) |
| **KM_EXT** | Volitelné |

3. Dál jako u běžného tagu: **Daleko** / **Blízko** → spoušť.
4. Po zápisu režim sám skončí.

Režim poznáte podle nápisu **Režim hranice TUDU**.  
Ukončení dřív: ruční změna úseku/výhybky, nebo **Načíst polohu**.

---

## 6. Kontrola

Tlačítko **Kontrola** ověří **už zapsaný** tag – **nic se nezapisuje**.

1. Zvolte **Daleko** nebo **Blízko**.
2. Přiložte tag a stiskněte spoušť.
3. Zobrazí se údaje z tagu a z CSV (pokud tag v tabulce je).

![Obrazovka Kontrola](../fotky_pro_prirucku/kontrola.png)

*Po načtení tagu vidíte **EPC** a **TID** z čipu a uložené údaje z CSV (**TUDU**, **OBJEKT**, pozice čipu, poloha, RO_ID, KM_EXT). Nahoře lze přepnout **Daleko** / **Blízko**. Zavřete křížkem.*

- Tag mimo CSV → **„Tag není v CSV“**.
- Více shod v CSV → listování šipkami.

---

## 7. Když něco nejde

| Problém | Co zkusit |
|---------|-----------|
| Spoušť nic nedělá / Načtení oranžové | Zvolte **Daleko** nebo **Blízko** |
| Načtení červené / **Načtěte znovu** | Přiložit znovu, přepnout Daleko ↔ Blízko, jiná strana tagu |
| GPS nefunguje | Volné místo, nebo **Testovací režim GPS** |
| Špatná výhybka | Klepněte na náhled → ruční výběr |
| Chyba po zápisu | **Smazat** u posledního záznamu → znovu |

---

## 8. Slovníček

| Pojem | Význam |
|-------|--------|
| **UDU / TUDU** | Kód úseku tratě |
| **Výhybka** | Číslo výhybky (např. 45) |
| **Čip** | Část výhybky (jazyk, rovně, odbočka, …) |
| **Daleko / Blízko** | Výkon čtečky – tag v koleji / u antény |
| **Hranice TUDU** | Zápis na hranici dvou úseků |
| **Kontrola** | Ověření tagu bez zápisu |
| **CSV** | Tabulka záznamů ve Stažených souborech |

---

*Příručka pro terén – RFID Go GPS verze 3.159. Kompletní dokumentace: `RFID_Go_GPS_prirucka.pdf`.*
