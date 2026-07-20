# RFID Go GPS – Jednoduchá příručka pro terén

**Verze aplikace:** 3.147  
**Zařízení:** Chainway C5 (čtečka s GPS)

---

## 1. Co aplikace dělá

RFID Go GPS slouží k **zápisu tagů na výhybkách** v terénu. Aplikace:

- podle GPS najde **úsek tratě** a **výhybku**, na které stojíte,
- zapíše tag a uloží záznam do tabulky,
- u každého zápisu si pamatuje polohu čtečky.

Tato příručka popisuje **každodenní práci v terénu** – načítání tagů, barvy na obrazovce a zápis na hranici úseku. Technické detaily jsou v hlavní příručce `RFID_Go_GPS_prirucka.pdf`.

---

## 2. Co uvidíte na obrazovce

| Oblast | Co tam je |
|--------|-----------|
| Horní lišta | GPS souřadnice, stav čtečky |
| Tři kroky nahoře | **UDU** → **Načtení** → **Hotovo** |
| Čtyři tečky pod stavem | průběh zápisu tagu |
| Karta UDU · výhybka | úsek, výhybka, tlačítka **V koleji** / **V ruce**, **Hranice TUDU** |
| Nápověda uprostřed | který čip právě načítáte (jazyk, rameno, …) |
| Poslední záznam | co jste naposledy zapsali |

---

## 3. Příprava před načítáním

1. Zapněte aplikaci a počkejte na **GPS** – v horní liště uvidíte souřadnice.
2. Aplikace načte databázi a doplní **úsek (UDU)** a **výhybku** podle polohy.
3. Zkontrolujte, že údaje v náhledu sedí s tím, kde skutečně stojíte.

> **Bez GPS?** Vyjděte na volné místo, nebo v kartě UDU zapněte **Testovací režim GPS** a vyberte polohu ze seznamu.

---

## 4. Načítání tagů – krok za krokem

### 4.1 Barvy indikátorů

Sledujte tři kroky nahoře (**UDU → Načtení → Hotovo**):

| Barva | Co znamená |
|-------|-------------|
| Šedá | Krok ještě neproběhl |
| Modrá | Právě probíhá |
| Zelená ✓ | Hotovo |
| Oranžová | Něco chybí – např. nevybrali jste **V koleji** nebo **V ruce** |
| Červená | Chyba – zkuste znovu |

Pod stavem čtečky jsou **čtyři tečky** (zápis tagu → uložení → heslo → zamčení). Stejné barvy: šedá, modrá, zelená, červená.

**Typické situace:**

- Krok **Načtení oranžový** → nejdřív zvolte **V koleji** nebo **V ruce**.
- Krok **Načtení červený** → zápis selhal, přiložte tag znovu.
- Krok **Hotovo zelený** ✓ → objevil se dialog **„Načetli jste“** – zápis proběhl.

### 4.2 V koleji nebo v ruce (povinné)

Před načtením tagu **vždy zvolte**:

| Tlačítko | Kdy použít |
|----------|------------|
| **V koleji** | Tag je v koleji na výhybce, čtečka je dál |
| **V ruce** | Tag držíte v ruce u antény |

Bez tohoto výběru spouště nefunguje.

### 4.3 Výběr výhybky

**Automaticky:** Aplikace podle GPS najde nejbližší výhybku a zobrazí ji v náhledu.

**Ručně:** Klepněte na náhled výhybky a vyberte správnou ze seznamu. U každé uvidíte vzdálenost a kolik čipů ještě chybí. Po ruční změně se GPS sám nepřepíná – dokud nekliknete **Načíst polohu**.

Výhybky, které už máte kompletní, jsou v seznamu **zašedlé**.

### 4.4 Kolik čipů a jaké části

| Typ výhybky | Kolik čipů | Části |
|-------------|------------|-------|
| Klasická 3částová | 3 | Jazyk, Levé rameno, Pravé rameno |
| Dvojvětvá | 3 | U čipů 2–3 volba **Rovně** nebo **Odbočka** |
| 4částová | 4 | Podle databáze (CA/CB, CG/CH, …) |

Aplikace sama nastaví **první chybějící čip**. Nad tlačítkem Kontrola uvidíte nápovědu, co právě načítáte.

### 4.5 Přepínání mezi výhybkami

Kdykoli můžete klepnout na výhybku v náhledu a vybrat jinou nedokončenou. Aplikace si pamatuje, co už je zapsané.

### 4.6 Jak poznat, že je hotovo

Zápis proběhl **jen tehdy, když se objeví dialog „Načetli jste“**.

- **Pokračovat** – další čip (lze potvrdit i spouštěm),
- **Opakovat** – stejný čip znovu.

Dialog se neobjevil? Podívejte se na červené tečky pod stavem čtečky a zkuste znovu. Pomůže přepnout **V koleji** ↔ **V ruce** nebo přiložit tag z jiné strany.

### 4.7 Poslední záznam

V kartě **Poslední záznam** uvidíte, co jste naposledy zapsali. Tlačítko **Smazat** zruší poslední zápis a vrátí vás na předchozí stav.

### 4.8 Shrnutí – jeden tag

1. Počkejte na GPS a zkontrolujte výhybku v náhledu.
2. Zvolte **V koleji** nebo **V ruce**.
3. U dvojvětvé výhybky zvolte **Rovně** nebo **Odbočka** (pokud je potřeba).
4. Přiložte tag a stiskněte **spouště**.
5. Po dialogu **„Načetli jste“** klepněte **Pokračovat**.

---

## 5. Hranice TUDU

### K čemu to slouží

Někdy potřebujete zapsat tag **na hranici dvou úseků tratě** – tam, kde končí jeden úsek a začíná druhý. To není běžná výhybka s čipy 1–4, ale **speciální místo** označené jako **hranice TUDU**.

Použijte to, když:

- stojíte na hranici úseku a potřebujete tam zapsat tag,
- nechcete ručně přepínat běžný zápis výhybky.

### Jak na to

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

### Jak poznáte režim hranice

- Na obrazovce je **Režim hranice TUDU**.
- V náhledu se místo výhybky zobrazuje **objekt**.

### Jak režim ukončit dřív

- Ručně změňte úsek nebo výhybku v náhledu,
- nebo klepněte **Načíst polohu**.

---

## 6. Když něco nejde

| Problém | Co zkusit |
|---------|-----------|
| Spouště nic nedělá | Zvolte **V koleji** nebo **V ruce** |
| GPS nefunguje | Vyjděte na volné místo, nebo **Testovací režim GPS** |
| Špatná výhybka | Klepněte na náhled a vyberte správnou ručně |
| Tag se nenačte | Přepněte V koleji ↔ V ruce, přiložte z jiné strany |
| Chyba při zápisu | V panelu **Pokročilé** smažte poslední záznam a zkuste znovu |

---

## 7. Krátký slovníček

| Pojem | Význam |
|-------|--------|
| **UDU / TUDU** | Kód úseku tratě |
| **Výhybka** | Číslo výhybky (např. 10A) |
| **Čip** | Část výhybky – jazyk, rameno, … |
| **Hranice TUDU** | Speciální zápis na hranici dvou úseků |
| **CSV** | Tabulka se všemi záznamy (soubor ve Stažených souborech) |

---

*Jednoduchá příručka pro terén – RFID Go GPS verze 3.147. Kompletní dokumentace: `RFID_Go_GPS_prirucka.pdf`.*
