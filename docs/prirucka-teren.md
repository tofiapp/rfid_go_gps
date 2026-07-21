# RFID Go GPS – Příručka pro terén

**Verze aplikace:** 3.155  
**Zařízení:** Chainway C5 (čtečka s GPS)

---

## Obsah

| # | Kapitola | Kdy číst |
|---|----------|----------|
| 0 | [Jak číst tuto příručku](#0-jak-číst-tuto-příručku) | vždy jako první |
| 1 | [Co aplikace dělá](#1-co-aplikace-dělá) | orientace |
| 2 | [Co uvidíte na obrazovce](#2-co-uvidíte-na-obrazovce) | před první směnou |
| 3 | [Příprava před načítáním](#3-příprava-před-načítáním) | každý start |
| 4 | [Standardní postup – jeden tag](#4-standardní-postup--jeden-tag) | hlavní práce v terénu |
| 5 | [Barvy a indikátory](#5-barvy-a-indikátory) | když něco bliká nebo blokuje |
| 6 | [Výhybky a čipy](#6-výhybky-a-čipy) | výběr, přepínání, typy |
| 7 | [Hranice TUDU](#7-hranice-tudu) | zápis na hranici úseku |
| 8 | [Když něco nejde](#8-když-něco-nejde) | problém v terénu |
| 9 | [Slovníček](#9-slovníček) | rychlá reference |

> Tato příručka popisuje **každodenní práci v terénu**. Technické detaily (CSV, Kontrola, Pokročilé) jsou v `RFID_Go_GPS_prirucka.pdf`.

---

## 0. Jak číst tuto příručku

### 0.1 Dvě úrovně

```
ÚROVEŇ 1 – Běžná směna
  └─ kapitoly 2 + 3 + 4

ÚROVEŇ 2 – Speciální situace a problémy
  └─ kapitoly 5–8 (podle potřeby) + slovníček
```

Nejdřív si projděte **obrazovku** a **standardní postup**. Barvy, výběr výhybky a hranice TUDU si nechte jako návratové body, až je budete potřebovat.

### 0.2 Kam jít podle otázky

| Ptám se… | Kapitola |
|----------|----------|
| Jak zapsat jeden tag od začátku do konce? | [4](#4-standardní-postup--jeden-tag) |
| Co znamenají barvy nahoře / tečky pod stavem? | [5](#5-barvy-a-indikátory) |
| Spouště nic nedělá? | [5](#5-barvy-a-indikátory) + [8](#8-když-něco-nejde) |
| Aplikace ukazuje špatnou výhybku? | [6.1](#61-výběr-výhybky) |
| Kolik čipů má výhybka a v jakém pořadí? | [6.2](#62-kolik-čipů-a-jaké-části) |
| Stojím na hranici dvou úseků? | [7](#7-hranice-tudu) |
| Tag se nenačte / zápis selhal? | [8](#8-když-něco-nejde) |

---

## 1. Co aplikace dělá

### 1.1 Jedna věta

Aplikace podle GPS najde **úsek tratě** a **výhybku**, na které stojíte, **zapíše UHF tag** a uloží záznam včetně polohy čtečky.

### 1.2 Co od ní čekat

- podle GPS doplní **UDU** a **výhybku**,
- u každého zápisu si pamatuje polohu čtečky,
- vede vás třemi kroky nahoře: **UDU → Načtení → Hotovo**.

---

## 2. Co uvidíte na obrazovce

| Oblast | Co tam je |
|--------|-----------|
| Horní lišta | GPS souřadnice, stav čtečky |
| Tři kroky nahoře | **UDU** → **Načtení** → **Hotovo** |
| Čtyři tečky pod stavem | průběh zápisu tagu |
| Karta UDU · výhybka | úsek, výhybka, tlačítka **V koleji** / **V ruce**, **Hranice TUDU** |
| Nápověda uprostřed | který čip právě načítáte (jazyk, rovně, odbočka) |
| Poslední záznam | co jste naposledy zapsali |

---

## 3. Příprava před načítáním

1. Zapněte aplikaci a počkejte na **GPS** – v horní liště uvidíte souřadnice.
2. Aplikace načte databázi a doplní **úsek (UDU)** a **výhybku** podle polohy.
3. Zkontrolujte, že údaje v náhledu sedí s tím, kde skutečně stojíte.

> **Bez GPS?** Vyjděte na volné místo, nebo v kartě UDU zapněte **Testovací režim GPS** a vyberte polohu ze seznamu.

Až sedí GPS i výhybka, pokračujte kapitolou [4](#4-standardní-postup--jeden-tag).

---

## 4. Standardní postup – jeden tag

Toto je **hlavní pracovní postup**. Detaily k barvám a výběru výhybky jsou v kapitolách 5 a 6.

### 4.1 Před stiskem spouště (povinné)

Před načtením tagu **vždy zvolte**:

| Tlačítko | Kdy použít |
|----------|------------|
| **V koleji** | Tag je v koleji na výhybce, čtečka je dál |
| **V ruce** | Tag držíte v ruce u antény |

Bez tohoto výběru spouště nefunguje. Krok **Načtení** zůstane oranžový – viz [5.2](#52-typické-situace).

U každého čipu **3částové** výhybky ještě zvolte **Jazyk**, **Rovně** nebo **Odbočka** (nápověda uprostřed obrazovky).

### 4.2 Postup krok za krokem

1. Počkejte na GPS a zkontrolujte výhybku v náhledu.
2. Zvolte **V koleji** nebo **V ruce**.
3. U každého čipu 3částové výhybky zvolte **Jazyk**, **Rovně** nebo **Odbočka**.
4. Přiložte tag a stiskněte **spouště**.
5. Po dialogu **„Načetli jste“** klepněte **Pokračovat** (nebo **Opakovat**).

### 4.3 Jak poznat, že je hotovo

Zápis proběhl **jen tehdy, když se objeví dialog „Načetli jste“**.

| Tlačítko v dialogu | Co udělá |
|--------------------|----------|
| **Pokračovat** | další čip (lze potvrdit i spouštěm) |
| **Opakovat** | stejný čip znovu |

Dialog se neobjevil? Podívejte se na červené tečky pod stavem čtečky a zkuste znovu. Pomůže přepnout **V koleji** ↔ **V ruce** nebo přiložit tag z jiné strany.

### 4.4 Poslední záznam

V kartě **Poslední záznam** uvidíte, co jste naposledy zapsali. Tlačítko **Smazat** zruší poslední zápis a vrátí vás na předchozí stav.

---

## 5. Barvy a indikátory

Sledujte tři kroky nahoře (**UDU → Načtení → Hotovo**) a čtyři tečky pod stavem čtečky. Barvy mají stejný význam.

### 5.1 Význam barev

| Barva | Co znamená |
|-------|------------|
| Šedá | Krok ještě neproběhl |
| Modrá | Právě probíhá |
| Zelená ✓ | Hotovo |
| Oranžová | Něco chybí – např. nevybrali jste **V koleji** nebo **V ruce** |
| Červená | Chyba – zkuste znovu |

Čtyři tečky = zápis tagu → uložení → heslo → zamčení.

### 5.2 Typické situace

| Co vidíte | Co to znamená | Co udělat |
|-----------|---------------|-----------|
| Krok **Načtení** oranžový | Chybí výkon | Zvolte **V koleji** nebo **V ruce** |
| Krok **Načtení** červený | Zápis selhal | Přiložte tag znovu |
| Krok **Hotovo** zelený ✓ | Úspěch | Objeví se dialog **„Načetli jste“** |

---

## 6. Výhybky a čipy

### 6.1 Výběr výhybky

**Automaticky:** Aplikace podle GPS najde nejbližší výhybku a zobrazí ji v náhledu.

**Ručně:** Klepněte na náhled výhybky a vyberte správnou ze seznamu. U každé uvidíte vzdálenost a kolik čipů ještě chybí.

> Po ruční změně se GPS sám nepřepíná – dokud nekliknete **Načíst polohu**.

Výhybky, které už máte kompletní, jsou v seznamu **zašedlé**.

### 6.2 Kolik čipů a jaké části

| Typ výhybky | Kolik čipů | Části |
|-------------|------------|-------|
| 3částová | 3 | Jazyk, Rovně, Odbočka (volba u každého čipu) |
| 4částová | 4 | Podle databáze (CA/CB, CG/CH, …) |

Aplikace sama nastaví **první chybějící čip**. Nad tlačítkem Kontrola uvidíte nápovědu, co právě načítáte.

### 6.3 Přepínání mezi výhybkami

Kdykoli můžete klepnout na výhybku v náhledu a vybrat jinou nedokončenou. Aplikace si pamatuje, co už je zapsané.

---

## 7. Hranice TUDU

### 7.1 K čemu to slouží

Někdy potřebujete zapsat tag **na hranici dvou úseků tratě** – tam, kde končí jeden úsek a začíná druhý. To není běžná výhybka s čipy 1–4, ale **speciální místo** označené jako **hranice TUDU**.

Použijte to, když:

- stojíte na hranici úseku a potřebujete tam zapsat tag,
- nechcete ručně přepínat běžný zápis výhybky.

### 7.2 Jak na to

**1. Otevřete formulář** – v kartě UDU · výhybka klepněte na **Hranice TUDU**.

**2. Vyplňte údaje:**

| Co vyplnit | Co tam napsat |
|------------|---------------|
| **TUDU** | Kód úseku. Ručně, nebo **Vybrat TUDU z okolí** (10 nejbližších podle GPS). |
| **Objekt** | Co tam je – číslo koleje nebo výhybky (např. `12` nebo `5A`). |
| Kilometrická poloha | Volitelné – můžete doplnit později. |

Potvrďte **Použít**.

**3. Zapište tag** – stejně jako u běžného čipu:

1. Zvolte **V koleji** nebo **V ruce**.
2. Přiložte tag a stiskněte **spouště**.

**4. Po zápisu** aplikace sama skončí režim hranice, načte GPS a najde **další výhybku**, na které můžete pokračovat.

### 7.3 Jak poznáte režim hranice

- Na obrazovce je **Režim hranice TUDU**.
- V náhledu se místo výhybky zobrazuje **objekt**.

### 7.4 Jak režim ukončit dřív

- Ručně změňte úsek nebo výhybku v náhledu,
- nebo klepněte **Načíst polohu**.

---

## 8. Když něco nejde

| Problém | Co zkusit |
|---------|-----------|
| Spouště nic nedělá | Zvolte **V koleji** nebo **V ruce** |
| GPS nefunguje | Vyjděte na volné místo, nebo **Testovací režim GPS** |
| Špatná výhybka | Klepněte na náhled a vyberte správnou ručně |
| Tag se nenačte | Přepněte V koleji ↔ V ruce, přiložte z jiné strany |
| Chyba při zápisu | V panelu **Pokročilé** smažte poslední záznam a zkuste znovu |

---

## 9. Slovníček

| Pojem | Význam |
|-------|--------|
| **UDU / TUDU** | Kód úseku tratě |
| **Výhybka** | Číslo výhybky (např. 10A) |
| **Čip** | Část výhybky – jazyk, rovně nebo odbočka |
| **Hranice TUDU** | Speciální zápis na hranici dvou úseků |
| **CSV** | Tabulka se všemi záznamy (soubor ve Stažených souborech) |

---

*Příručka pro terén – RFID Go GPS verze 3.155. Kompletní dokumentace: `RFID_Go_GPS_prirucka.pdf`.*
