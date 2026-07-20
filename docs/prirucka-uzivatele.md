# RFID Go GPS – Příručka pro uživatele

**Verze aplikace:** 3.144  
**Zařízení:** Chainway C5 (UHF čtečka s GPS)

---

## 1. K čemu aplikace slouží

RFID Go GPS je terénní aplikace pro **zápis UHF RFID tagů** na železničních výhybkách. Aplikace:

- určí **UDU**, **výhybku** a **čip** podle GPS polohy a databáze DZS,
- zapíše do tagu jeho **EPC** (z TID), **heslo** a **zamkne** tag,
- uloží záznam do tabulky **CSV** včetně GPS souřadnic čtečky.

Aplikaci lze mít nainstalovanou vedle původní aplikace RFID Go – data se navzájem nepřepisují.

---

## 2. Co budete potřebovat

| Položka | Popis |
|---------|-------|
| Čtečka Chainway C5 | S vestavěným UHF modulem a GPS |
| Databáze DZS | Soubor `DZS_PASPORT_TPI.sqlite` (nebo jiný `.db` / `.sqlite` s DZS daty) |
| UHF tagy | Prázdné nebo k přepisu |
| Povolení v telefonu | **Poloha (GPS)** a doporučeně **Přístup ke všem souborům** (kvůli viditelnosti CSV z PC) |

### Umístění souborů na čtečce

- **Databáze:** složka **Stažené soubory** (Download) – aplikace ji při startu najde automaticky.
- **Výstupní CSV:** `Download/rfid_go_gps_output.csv` – lze kopírovat na PC přes USB.

---

## 3. První spuštění

1. Zkopírujte databázi `DZS_PASPORT_TPI.sqlite` do složky **Stažené soubory**.
2. Spusťte aplikaci **RFID Go GPS**.
3. Povolte přístup k **poloze** (GPS).
4. Pokud aplikace nabídne **Přístup ke všem souborům**, povolte ho – usnadní to práci s CSV z počítače.
5. Počkejte, až se databáze načte a získáte **GPS fix** (souřadnice v horní části obrazovky).
6. Aplikace doplní **UDU**, **výhybku** a **první chybějící čip** podle existujícího CSV (pokud už nějaké záznamy máte).

> **Tip:** Po přeinstalaci aplikace zůstává databáze ve Stažených souborech – stačí ji znovu najít automaticky. Data aplikace (nastavení) se smažou, ale CSV ve Stažených souborech zůstane.

---

## 4. Přehled obrazovky

Aplikace má **jednu hlavní obrazovku** a panel **Pokročilé** (vyjíždí zdola).

| Oblast | Co zobrazuje |
|--------|----------------|
| Horní lišta | Logo, výkon čtečky, stav operace, GPS souřadnice |
| Indikátor 3 kroků | **UDU** → **Načtení** → **Hotovo** (barvy: šedá / modrá / zelená ✓ / oranžová upozornění / červená chyba) |
| Pod-kroky zápisu | přepis EPC → zápis do CSV → zápis hesla → zamčení (tečky pod stavem čtečky) |
| Karta UDU · výhybka | databáze, režim GPS/ručně, náhled výběru, výkon (**V koleji** / **V ruce**), tlačítko **Hranice TUDU** |
| Nápověda čipu | jazyk / levé rameno / pravé rameno; u dvojvětvých výhybek **Rovně / Odbočka** |
| Tlačítko **Kontrola** | ověření již zapsaného tagu |
| Poslední záznam | náhled posledního řádku CSV (s možností smazat) |
| Panel **Pokročilé** | tabulka CSV (5 posledních záznamů), ruční kroky, volitelná šablona EPC |

> Podrobný popis celého procesu načítání včetně barev indikátorů viz **kapitola 5**.

---

## 5. Načítání tagů – průvodce krok za krokem

Tato kapitola popisuje celý proces **načítání (zápisu) tagu** v terénu – od zapnutí čtečky až po ověření záznamu. Průběh provází **indikátory kroků** v horní části obrazovky; barvy řádků režimu a postupu ukazují, co je hotové, co čeká a kde nastala chyba.

### 5.1 Zapnutí a příprava (ne první spuštění)

Pokud už máte databázi ve Stažených souborech a aplikaci jste dříve používali, při běžném startu **nečekáte na výběr databáze** – ta se načte automaticky. Po zapnutí:

1. Počkejte na **GPS fix** (souřadnice v horní liště, např. `49.1951° 16.6084° ±6m`).
2. Aplikace podle polohy **indexuje okolí 4 km** v databázi DZS (průběh uvidíte v kartě UDU · výhybka).
3. Jakmile je index hotový, GPS automaticky doplní **UDU** a **nejbližší výhybku** v náhledovém panelu.

> **Tip:** Bez GPS fixu se okolí 4 km neindexuje a UDU/výhybku nelze určit automaticky – vyjděte na volné místo nebo použijte **Testovací režim GPS** (viz kapitola 9).

### 5.2 Indikátory kroků a barev

Na obrazovce sledujte **dva související indikátory**:

#### Hlavní kroky (řádek UDU → Načtení → Hotovo)

| Krok | Název | Význam |
|------|-------|--------|
| **1** | **UDU** | Je vybrán UDU a výhybka (nebo objekt u hranice TUDU) |
| **2** | **Načtení** | Probíhá nebo proběhlo načtení tagu spouštěm |
| **3** | **Hotovo** | Zápis byl úspěšný – zobrazil se dialog **„Načetli jste“** |

**Barvy kruhů a popisků:**

| Barva | Význam |
|-------|--------|
| **Šedá** | Krok ještě neproběhl |
| **Modrá** | Krok právě probíhá |
| **Zelená** ✓ | Krok úspěšně dokončen |
| **Oranžová** | Upozornění – něco chybí (např. není vybrán režim čtení) |
| **Červená** | Chyba při načítání – zkuste znovu |

Typické stavy:

- Krok **1 oranžový** → čeká se na GPS a doplnění UDU/výhybky, nebo je potřeba výběr ručně.
- Krok **2 oranžový** → UDU je v pořádku, ale **nevybrali jste režim čtení** (viz 5.3) – bez něj spouště nefunguje.
- Krok **2 červený** → zápis selhal (např. chyba EPC, hesla nebo zamčení).
- Krok **3 zelený** ✓ → zobrazil se dialog **„Načetli jste“** s číslem výhybky a části čipu.

#### Pod-kroky zápisu (čtyři tečky pod stavem čtečky)

Po výběru režimu čtení se při načítání zobrazí čtyři pod-kroky:

**Přepis EPC** → **Zápis do CSV** → **Zápis hesla** → **Zamčení**

| Barva tečky | Význam |
|-------------|--------|
| Šedá | Čeká na provedení |
| Modrá | Právě probíhá |
| Zelená | Úspěšně dokončeno |
| Červená | Selhalo – stav se střídá s chybovou hláškou (např. *„Načtěte znovu“*, *„Chyba hesla“*) |

### 5.3 Výběr režimu čtení (povinný před načtením)

Před prvním načtením tagu **musíte zvolit režim výkonu** v kartě UDU · výhybka:

| Tlačítko v aplikaci | Význam | Kdy použít |
|---------------------|--------|------------|
| **🛤 V koleji** (16 dBm) | Čtení **z dálky** – tag je v koleji, čtečka je dál | Tag je připevněný na výhybce v koleji |
| **✋ V ruce** (1 dBm) | Čtení **z blízka** – držíte tag v ruce u antény | Tag držíte v ruce při zápisu |

> **Poznámka:** Názvy tlačítek se mohou v budoucí verzi změnit na **Z dálky** / **Z blízka** – význam zůstává stejný.

**Bez výběru režimu nelze načítat** – krok 2 zůstane oranžový a při stisku spouště se zobrazí upozornění.

### 5.4 Určení UDU a výhybky

#### Automaticky z GPS (výchozí)

- Po získání polohy aplikace najde **nejbližší výhybku** a příslušný **UDU**.
- Hodnoty se zobrazí v **náhledovém panelu** (klepnutím na UDU nebo výhybku je lze změnit).

#### Ruční oprava výběru

- **Výhybka není přesná** → klepněte na náhled **výhybky** (nebo rozbalte kartu UDU · výhybka) a vyberte správnou výhybku ze seznamu. U každé položky uvidíte vzdálenost od GPS; u nedokončených výhybek i **kolik čipů ještě chybí** (např. *„Chybí 2 čipy“*).
- **Výhybka bez GPS v databázi** → v seznamu je označena jako **„Bez GPS“**; vyhledejte ji ručně (pole hledání nahoře v dialogu).
- Po ruční změně výhybky se **automatická aktualizace z GPS vypne**, dokud nekliknete **Načíst polohu** (nebo dokud nedokončíte všechny čipy u aktuální výhybky).

#### Režim Ručně

Přepínač **GPS / Ručně** v kartě UDU · výhybka – UDU i výhybku vyberete vždy ze seznamu (vhodné bez signálu GPS).

### 5.5 Kolik čipů načítat

| Typ výhybky | Počet čipů | Části |
|-------------|------------|-------|
| **Klasická 3částová** | 3 | **Jazyk**, **Levé rameno**, **Pravé rameno** |
| **3částová dvojvětvá** | 3 | U čipů 2–3 navíc volba **Rovně** / **Odbočka** |
| **Křižovatková 4částová** | 4 | Konkrétní kódy podle databáze (CA/CB, CG/CH, …) |

- Při výběru výhybky se automaticky nastaví **první chybějící čip** (podle existujícího CSV).
- Výhybky, u kterých jsou **všechny čipy zapsané**, jsou v seznamu **zašedlé** a nejdou vybrat.

### 5.6 Výběr části při načítání

Nad tlačítkem Kontrola se zobrazí **nápověda k aktuálnímu čipu**:

- U **klasické výhybky** uvidíte text typu *„Načtěte čip 2, výhybky 10“* a pod ním název části (**Jazyk**, **Levé rameno**, …).
- U **dvojvětvé výhybky** u čipů 2–3 **vyberte před načtením** tlačítkem **Jazyk**, **Rovně** nebo **Odbočka**. Přeškrtnuté možnosti jsou již zapsané u jiného čipu.
- U **4částové výhybky** se název části doplní automaticky podle čísla čipu.

### 5.7 Přeskakování mezi výhybkami

Během načítání můžete **kdykoli přepnout na jinou výhybku** – klepněte na náhled výhybky a vyberte jinou z nedokončených. V seznamu u každé výhybky uvidíte, **kolik čipů ještě zbývá**. Aplikace si pamatuje, které čipy už jsou v CSV zapsané.

### 5.8 Hranice TUDU (čip 5)

Pro tag na **hranici TUDU** (mimo běžný cyklus výhybky):

1. Klepněte na **Hranice TUDU** v kartě UDU · výhybka.
2. Vyplňte **TUDU** – ručně, nebo tlačítkem **Vybrat TUDU z okolí** (10 nejbližších podle GPS).
3. **Povinně vyplňte číslo objektu** (kolej / výhybka), kde se čip nachází.
4. Volitelně doplňte **KM_EXT**.
5. Po potvrzení aplikace přepne do režimu **čip 5** – v náhledu se místo výhybky zobrazuje **objekt**.
6. Zvolte režim čtení a načtěte tag spouštěm stejně jako u běžného čipu.

Režim hranice TUDU skončí při ruční změně UDU/výhybky nebo po kliknutí **Načíst polohu**.

### 5.9 Jak poznat úspěšný zápis

**Záznam byl úspěšný teprve tehdy, když se zobrazí dialog „Načetli jste“** s číslem výhybky (nebo objektu) a číslem části čipu.

- **Pokračovat** – přejde na další chybějící čip (lze potvrdit i spouštěm).
- **Opakovat** – zopakuje zápis stejného čipu (např. když byl tag špatně přiložen).

Pokud se dialog **neobjevil**, zápis neproběhl – podívejte se na barvy pod-kroků (červená = chyba) a zkuste znovu.

> **Tip:** Když se tag nedaří načíst, zkoušejte ho přiložit **z různých stran** a případně přepněte režim **V koleji** ↔ **V ruce**. U již zamčeného tagu s jiným heslem aplikace zkusí známá preset hesla automaticky.

### 5.10 Poslední záznam a historie

| Kde | Co uvidíte |
|-----|------------|
| **Karta Poslední záznam** (nad panelem Pokročilé) | Náhled posledního zápisu – výhybka a čip. Tlačítko **Smazat** zruší poslední řádek CSV a vrátí stav předchozího zápisu. |
| **Pokročilé → karta 3. Tabulka CSV** | Až **5 posledních záznamů** se všemi sloupci (EPC, TID, TUDU, OBJEKT, POZICE, POLOHA, KM_EXT, GPS, …). Úplný soubor je v `Download/rfid_go_gps_output.csv`. |

### 5.11 Shrnutí – rychlý postup jednoho tagu

1. Zapněte aplikaci → počkejte na **GPS** a **index okolí 4 km**.
2. Zkontrolujte **UDU** a **výhybku** (případně opravte ručně).
3. Zvolte **V koleji** nebo **V ruce**.
4. U dvojvětvé výhybky zvolte **Jazyk / Rovně / Odbočka** (pokud je potřeba).
5. Přiložte tag a stiskněte **spouště**.
6. Po dialogu **„Načetli jste“** zvolte **Pokračovat** a přejděte na další čip.

---

## 6. Rychlý start v terénu

Zkrácený postup pro běžný zápis jednoho tagu (podrobnosti viz kapitola 5):

1. Ověřte, že je načtena databáze a máte GPS fix.
2. Zkontrolujte **UDU**, **výhybku** a **čip** v náhledovém panelu.
3. Zvolte výkon **V koleji** (z dálky) nebo **V ruce** (z blízka).
4. U dvojvětvých 3částových výhybek u čipů 2–3 zvolte **Rovně** nebo **Odbočka**.
5. Přiložte tag ke čtečce a stiskněte **spouště čtečky**.
6. Po dokončení se zobrazí dialog **„Načetli jste“** – zvolte **Pokračovat** nebo **Opakovat**.

---

## 7. Zápis tagu – co se děje po spuštění

Po stisku spouště proběhne automaticky tento řetězec:

1. **Přepis EPC** – čtečka načte TID tagu a zapíše ho jako nové EPC.
2. **Zápis do CSV** – uloží řádek s EPC, TID, UDU, výhybkou, čipem, GPS a dalšími údaji.
3. **Zápis hesla** – nastaví access heslo (výchozí `12345678`).
4. **Zamčení tagu** – tag se uzamkne proti dalším změnám.

Indikátor pod-kroků na obrazovce ukazuje průběh. Po úspěchu se automaticky:

- zvýší pořadové číslo **ID_RFID**,
- posune **čip** o 1; po dokončení výhybky přejde na **další nedokončenou výhybku** v rámci UDU.

---

## 8. Výběr UDU, výhybky a čipu

> Kompletní průvodce načítáním včetně GPS, přeskakování mezi výhybkami a výběru části čipu je v **kapitole 5**. Níže stručný technický přehled.

### Režim GPS (výchozí)

- Aplikace podle polohy najde nejbližší výhybku a příslušný UDU.
- Výběr se zobrazí v náhledovém panelu nahoře.
- Klepnutím na náhled můžete UDU nebo výhybku **ručně změnit** – tím se vypne automatická aktualizace, dokud nekliknete **Načíst polohu**.

### Režim Ručně

- Přepínač v kartě UDU · výhybka.
- UDU a výhybku vyberete ze seznamu v databázi.

### Dokončené výhybky

- Výhybky, u kterých jsou všechny čipy zapsané v CSV, jsou v seznamu **zašedlé** a nejdou vybrat.
- Při výběru výhybky se automaticky nastaví **první chybějící čip**.

### Typy výhybek

| Typ | Čipy | Nápověda v aplikaci |
|-----|------|---------------------|
| 3částová | 1–3 | Jazyk, Levé rameno, Pravé rameno |
| 3částová dvojvětvá | 2–3 | Navíc volba **Rovně** / **Odbočka** |
| 4částová | 1–4 | Konkrétní kódy (CA/CB, CG/CH, …) |

Číslo výhybky může obsahovat písmeno IOB z databáze (např. `10A`).

---

## 9. GPS poloha

- Souřadnice se zobrazují ve stavu **Připraveno**, např. `49.1951° 16.6084° ±6m`.
- Při zápisu tagu se do CSV uloží nejlepší známá poloha.
- Pokud GPS není dostupná, tag se uloží **bez souřadnic** a zobrazí se upozornění.

### Testovací režim GPS

Vhodný bez signálu (např. v budově) nebo pro zkoušení:

- Zapněte **Testovací režim GPS** v kartě UDU.
- Vyberte simulovanou polohu ze seznamu (souřadnice z databáze).
- UDU a výhybka se doplní stejně jako u skutečné GPS.
- V CSV bude u GPS času příznak `TEST`.

---

## 10. Kontrola načteného tagu

Tlačítko **Kontrola** slouží k **ověření již zapsaného tagu** – nic se nezapisuje.

1. Zvolte výkon (v koleji / v ruce).
2. Načtěte tag spouštěm – aplikace přečte EPC a TID.
3. Pokud tag je v CSV, zobrazí uložené údaje (TUDU, výhybka, čip, poloha, KM_EXT, …).
4. Pokud tag odpovídá více řádkům CSV, přepínejte šipkami (`2 / 5`).
5. Tag mimo CSV → hláška **„Tag není v CSV“**.

---

## 11. Hranice TUDU (čip 5)

> Podrobný postup zápisu hranice TUDU je v **kapitole 5.8**. Stručný přehled:

Speciální režim pro tagy na **hranici TUDU** mimo běžný cyklus výhybky:

1. Klepněte na **Hranice TUDU** v kartě UDU.
2. Vyplňte **TUDU** (ručně nebo výběrem z 10 nejbližších podle GPS), **objekt** (kolej / výhybka) a volitelně **KM_EXT**.
3. Po potvrzení aplikace přepne do režimu **čip 5**.
4. Spouště provede stejný zápis jako u běžného čipu.
5. Režim skončí při ruční změně UDU/výhybky nebo načtení polohy z GPS.

---

## 12. Práce s CSV souborem

### Co se ukládá

Po každém zápisu se přidá (nebo přepíše) řádek v souboru `rfid_go_gps_output.csv` ve složce **Stažené soubory**.

Hlavní sloupce: ID_RFID, EPC, TID, TUDU, OBJEKT (výhybka), POZICE (čip), POLOHA, KM_EXT, LAT, LON, přesnost GPS, datum.

### Nahrání z PC přes USB

1. Připojte čtečku k PC.
2. V Průzkumníku Windows otevřete **Vnitřní úložiště → Download**.
3. Zkopírujte nebo přepište soubor `rfid_go_gps_output.csv`.
4. Vraťte se do aplikace – CSV se načte automaticky.

Pokud soubor v MTP nevidíte, povolte u aplikace **Přístup ke všem souborům**.

### V aplikaci (panel Pokročilé)

- **Načíst CSV** – ruční obnovení ze souboru na disku.
- **Vymazat poslední záznam** – smaže poslední řádek a vrátí stav předchozího zápisu.
- **Sdílet / exportovat** – odeslání tabulky jinou aplikací.

---

## 13. Časté situace a řešení

| Situace | Co dělat |
|---------|----------|
| Databáze se nenačte | Zkontrolujte, že soubor je ve Stažených souborech; případně **Vybrat databázi SQLite** v Pokročilých |
| GPS nefunguje | Vyjděte na volné místo; nebo zapněte **Testovací režim GPS** |
| „U aktuální GPS polohy nelze určit UDU“ | Přepněte na režim **Ručně** a vyberte UDU ze seznamu |
| Spouště nic nedělá | Nejprve zvolte výkon **V koleji** nebo **V ruce** |
| Zápis selže (heslo) | Aplikace automaticky zkusí známá preset hesla; tag může být již zamčený jiným heslem |
| CSV není vidět z PC | Povolte **Přístup ke všem souborům** u aplikace |
| Po přeinstalaci chybí nastavení | CSV a databáze ve Stažených souborech zůstávají; aplikace je znovu načte |

---

## 14. Panel Pokročilé (volitelné)

Panel **Pokročilé** otevřete tahem zdola. Pro běžný terénní provoz ho **nemusíte** používat.

| Funkce | Kdy použít |
|--------|------------|
| Tabulka CSV | Přehled a export záznamů |
| Šablona EPC (ON/OFF) | Výchozí je **OFF** (EPC = TID). Zapnutí ON používá 7řádkovou šablonu – jen pokud to vyžaduje váš postup |
| Ruční tlačítka ZAPSAT EPC / HESLO / ZAMKNOUT | Oprava jednoho kroku bez celého cyklu |
| Ruční výkon v dBm | Jemné doladění místo presetů v koleji / v ruce |

---

## 15. Slovníček

| Pojem | Význam |
|-------|--------|
| **UDU** | Úsek dráhy – kód stanice (TUDU) |
| **TUDU** | Plný kód úseku včetně 6 znaků |
| **Výhybka / OBJEKT** | Číslo výhybky na úseku (např. 10A) |
| **Čip / POZICE** | Část výhybky (1–4, u hranice TUDU = 5) |
| **EPC** | Identifikátor zapsaný v tagu (24 hex znaků) |
| **TID** | Tovární identifikátor čipu |
| **CSV** | Tabulka se všemi záznamy zápisů |
| **DZS** | Databáze s polohami výhybek a úseků |

---

*Dokument vytvořen pro RFID Go GPS verze 3.144. Technické detaily vývoje a sestavení viz README.md v repozitáři projektu.*
