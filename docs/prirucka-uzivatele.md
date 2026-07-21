# RFID Go GPS – Příručka pro uživatele

**Verze aplikace:** 3.147  
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
| Nápověda čipu | u 3částových výhybek **Jazyk / Rovně / Odbočka** |
| Tlačítko **Kontrola** | ověření již zapsaného tagu |
| Poslední záznam | náhled posledního řádku CSV (s možností smazat) |
| Panel **Pokročilé** | tabulka CSV (5 posledních záznamů), ruční kroky, volitelná šablona EPC |

> Podrobný popis načítání tagů, barev indikátorů a hranice TUDU je v **příručce pro terén**: `RFID_Go_GPS_prirucka_teren.pdf`

---

## 5. Příručka pro terén

Kompletní průvodce načítáním tagů (indikátory kroků, barvy, výběr výhybky, přepínání mezi výhybkami) a zápisem na **hranici TUDU** je v samostatném dokumentu určeném pro terén:

- **PDF:** `RFID_Go_GPS_prirucka_teren.pdf`
- **Zdroj:** `docs/prirucka-teren.md` → `python3 docs/generate_prirucka_teren.py`

Tato hlavní příručka doplňuje technické detaily (CSV, GPS, Kontrola, Pokročilé). Pro každodenní práci v terénu použijte příručku pro terén (začněte kapitolou 1 – běžný zápis).

---

## 6. Rychlý start v terénu

Zkrácený postup pro běžný zápis jednoho tagu (podrobnosti viz **příručka pro terén**, kapitola 1):

1. Ověřte, že je načtena databáze a máte GPS fix.
2. Zkontrolujte **UDU**, **výhybku** a **čip** v náhledovém panelu.
3. Zvolte výkon **V koleji** (z dálky) nebo **V ruce** (z blízka).
4. U každého čipu 3částové výhybky zvolte **Jazyk**, **Rovně** nebo **Odbočka**.
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

> Kompletní průvodce načítáním je v **příručce pro terén** (`RFID_Go_GPS_prirucka_teren.pdf`). Níže stručný technický přehled.

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
| 3částová | 1–3 | Jazyk, Rovně, Odbočka (volba u každého čipu) |
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

## 11. Hranice TUDU

Postup zápisu tagu na hranici dvou úseků tratě je v **příručce pro terén** – kapitola 3 (`RFID_Go_GPS_prirucka_teren.pdf`).

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

*Dokument vytvořen pro RFID Go GPS verze 3.147. Technické detaily vývoje a sestavení viz README.md v repozitáři projektu.*
