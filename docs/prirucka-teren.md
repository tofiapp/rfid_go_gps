# RFID Go GPS – Příručka pro terén

**Verze aplikace:** 3.160  
**Zařízení:** Chainway C5 (čtečka s GPS)

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

Aplikace podle GPS najde **úsek** a **výhybku**, zapíše tag a uloží záznam včetně polohy čtečky. Celá práce probíhá na **jedné** obrazovce.

| Co potřebujete | Kde to je |
|----------------|-----------|
| Výkon čtečky | **Daleko** / **Blízko** v horní liště – bez volby spouště nefunguje |
| Úsek / výhybka / čip | Karta **UDU · výhybka** (klepnutím změníte ručně) |
| Speciální zápis | **Hranice TUDU** v kartě |
| Obnovit GPS výběr | **Načíst polohu** |
| Ověřit tag bez zápisu | **Kontrola** |
| Vrátit omyl | **Smazat** u posledního záznamu |

Barvy tří kroků: šedá = ještě ne · modrá = probíhá · zelená ✓ = hotovo · oranžová = něco chybí · červená = chyba.

![Hlavní obrazovka](../fotky_pro_prirucku/hlavni_strana.png)

*Na snímku typický stav před zápisem: **Blízko** zvoleno, krok **Načtení** oranžový (čeká na stisk spouště), karta UDU **1302F** · výhybka **45** · čip **1/3**, uprostřed nápověda a **Jazyk / Rovně / Odbočka**, dole **Kontrola** a **Poslední záznam**. Horní čtyři tečky = průběh zápisu (tag → CSV → heslo → zamčení).*

---

## 2. Příprava

1. Zapněte aplikaci a počkejte na **GPS** (zelené souřadnice v horní liště).
2. Aplikace načte databázi a doplní **UDU** a **výhybku**.
3. Zkontrolujte, že náhled sedí s tím, kde stojíte.

> **Bez GPS?** Volné místo, nebo v kartě UDU **Testovací režim GPS** a poloha ze seznamu.

---

## 3. Jeden tag

Před spouští **vždy** zvolte výkon (**Vyberte režim** v horní liště):

| Tlačítko | Kdy |
|----------|-----|
| **Daleko** | Tag v koleji, čtečka dál |
| **Blízko** | Tag u antény v ruce |

1. Zkontrolujte výhybku v náhledu.
2. Zvolte **Daleko** nebo **Blízko**.
3. U 3částové výhybky zvolte **Jazyk**, **Rovně** nebo **Odbočka**.
4. Přiložte tag a stiskněte **spouště**.
5. Po dialogu **„Načetli jste"**:

![Úspěšné načtení](../fotky_pro_prirucku/uspesne_nacteni.png)

*Zápis je hotový **jen** když se objeví tento dialog (všechny tři kroky zelené). **Pokračovat** = další čip · **Opakovat** = stejný čip znovu. Pokračovat lze potvrdit i spouští.*

Když zápis selže:

![Neúspěšné načtení](../fotky_pro_prirucku/neuspesne_nacteni.png)

*Červené **Načtení** a nápis **Načtěte znovu** = tag se nezapsal. Přiložte znovu, případně přepněte Daleko ↔ Blízko nebo otočte tag.*

---

## 4. Výhybky a čipy

- **GPS** sám nabídne nejbližší výhybku.
- **Ručně:** klepněte na náhled UDU nebo výhybky.
- Po ruční změně se GPS nepřepíná, dokud nekliknete **Načíst polohu**.
- Mezi nedokončenými výhybkami můžete přepínat kdykoli – zapsané čipy si aplikace pamatuje.

Klepnutí na UDU otevře seznam podle vzdálenosti:

![Výběr UDU](../fotky_pro_prirucku/vyber_udu.png)

*Nejbližší je nahoře. Oranžové kolečko = vybraný úsek.*

Klepnutí na výhybku:

![Výběr výhybky](../fotky_pro_prirucku/vyber_vyhybky.png)

*Řazeno podle vzdálenosti. Oranžové **Chybí X čipů** = výhybka ještě není hotová (na snímku výhybka 45). Hotové výhybky jsou v seznamu **zašedlé** a nevybíratelné. Hledání nahoře zúží dlouhý seznam.*

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

| Pole | Co napsat |
|------|-----------|
| **TUDU** | **Vybrat TUDU z okolí**, nebo ručně (na snímku `1302FA`) |
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
3. Porovnejte údaje na obrazovce s tím, co očekáváte u výhybky:

![Obrazovka Kontrola](../fotky_pro_prirucku/kontrola.png)

*V terénu hlavně zkontrolujte **TUDU**, **OBJEKT** (výhybka) a **POZICE** (číslo čipu). Oranžové řádky (EPC, OBJEKT) jen zvýrazňují klíčová pole – zápis se nemění. Zavřete křížkem vpravo nahoře.*

Tag mimo CSV → hláška **„Tag není v CSV“**. Více shod v CSV → listování šipkami.

---

## 7. Když něco nejde

| Problém | Co zkusit |
|---------|-----------|
| Spouště nic nedělá / Načtení oranžové | **Daleko** nebo **Blízko** |
| Načtení červené / „Načtěte znovu" | Přiložit znovu, přepnout Daleko ↔ Blízko, jiná strana tagu |
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

*Příručka pro terén – RFID Go GPS verze 3.160. Kompletní dokumentace: `RFID_Go_GPS_prirucka.pdf`.*
