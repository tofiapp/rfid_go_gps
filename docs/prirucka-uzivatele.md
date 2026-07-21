# RFID Go GPS – Příručka pro uživatele

**Verze aplikace:** 3.158  
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
| Karta UDU · výhybka | databáze, režim GPS/ručně, náhled výběru, výkon (**Daleko** / **Blízko**), tlačítko **Hranice TUDU** |
| Nápověda čipu | u 3částových výhybek **Jazyk / Rovně / Odbočka** |
| Tlačítko **Kontrola** | ověření již zapsaného tagu |
| Poslední záznam | náhled posledního řádku CSV (s možností smazat) |
| Panel **Pokročilé** | tabulka CSV (5 posledních záznamů), ruční kroky, volitelná šablona EPC |

> Podrobný popis načítání tagů, barev indikátorů a hranice TUDU je v **příručce pro terén**: `RFID_Go_GPS_prirucka_teren.pdf`

---

## 5. Příručka pro terén

Postup načítání tagů, barvy indikátorů, výběr výhybky a **hranice TUDU** jsou v:

- **PDF:** `RFID_Go_GPS_prirucka_teren.pdf`
- **Zdroj:** `docs/prirucka-teren.md`

Tato příručka doplňuje technické detaily (CSV, GPS, Kontrola, Pokročilé).

---

## 6. Zápis tagu – co se děje po spuštění

Po stisku spouště (nejprve **Daleko** / **Blízko**, viz příručka pro terén) proběhne:

1. **Přepis EPC** – čtečka načte TID tagu a zapíše ho jako nové EPC.
2. **Zápis do CSV** – uloží řádek s EPC, TID, UDU, výhybkou, čipem, GPS a dalšími údaji.
3. **Zápis hesla** – nastaví access heslo (výchozí `12345678`).
4. **Zamčení tagu** – tag se uzamkne proti dalším změnám.

Indikátor pod-kroků na obrazovce ukazuje průběh. Po úspěchu se automaticky:

- zvýší pořadové číslo **ID_RFID**,
- posune **čip** o 1; po dokončení výhybky přejde na **další nedokončenou výhybku** v rámci UDU.

---

## 7. Výběr UDU, výhybky a čipu

> Průvodce pro terén: `RFID_Go_GPS_prirucka_teren.pdf`. Níže stručný technický přehled.

### Režim GPS (výchozí)

- Aplikace podle polohy najde nejbližší výhybku a příslušný UDU.
- Klepnutím na náhled můžete UDU nebo výhybku **ručně změnit** – automatická aktualizace se vypne, dokud nekliknete **Načíst polohu**.

### Režim Ručně

- Přepínač v kartě UDU · výhybka – UDU a výhybku vyberete ze seznamu v databázi.

### Dokončené výhybky

- Výhybky se všemi čipy v CSV jsou v seznamu **zašedlé**.
- Při výběru se nastaví **první chybějící čip**.

### Typy výhybek

| Typ | Čipy | Nápověda v aplikaci |
|-----|------|---------------------|
| 3částová | 1–3 | Jazyk, Rovně, Odbočka |
| 4částová | 1–4 | Konkrétní kódy (CA/CB, CG/CH, …) |

Číslo výhybky může obsahovat písmeno IOB z databáze (např. `10A`).

---

## 8. GPS poloha

- Souřadnice se zobrazují ve stavu **Připraveno**, např. `49.1951° 16.6084° ±6m`.
- Při zápisu tagu se do CSV uloží nejlepší známá poloha.
- Pokud GPS není dostupná, tag se uloží **bez souřadnic** a zobrazí se upozornění.

### Testovací režim GPS

- Zapněte **Testovací režim GPS** v kartě UDU a vyberte simulovanou polohu.
- UDU a výhybka se doplní stejně jako u skutečné GPS.
- V CSV bude u GPS času příznak `TEST`.

---

## 9. Kontrola načteného tagu

Postup a screenshot: **příručka pro terén**, kapitola 7. Tag mimo CSV → hláška **„Tag není v CSV“**. Při více shodách přepínejte šipkami (`2 / 5`).

---

## 10. Hranice TUDU

Postup: **příručka pro terén**, kapitola 6.

---

## 11. Práce s CSV souborem

### Co se ukládá

Po každém zápisu se přidá (nebo přepíše) řádek v `rfid_go_gps_output.csv` ve **Stažených souborech**.

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

## 12. Časté situace a řešení

| Situace | Co dělat |
|---------|----------|
| Databáze se nenačte | Soubor ve Stažených souborech; případně **Vybrat databázi SQLite** v Pokročilých |
| GPS nefunguje | Volné místo; nebo **Testovací režim GPS** |
| „U aktuální GPS polohy nelze určit UDU“ | Režim **Ručně** a výběr UDU ze seznamu |
| Spouště nic nedělá | Nejprve **Daleko** nebo **Blízko** |
| Zápis selže (heslo) | Aplikace zkusí známá hesla; tag může být zamčený jiným heslem |
| CSV není vidět z PC | Povolte **Přístup ke všem souborům** |
| Po přeinstalaci chybí nastavení | CSV a databáze ve Stažených souborech zůstávají |

---

## 13. Panel Pokročilé (volitelné)

Panel **Pokročilé** otevřete tahem zdola. Pro běžný terénní provoz ho **nemusíte** používat.

| Funkce | Kdy použít |
|--------|------------|
| Tabulka CSV | Přehled a export záznamů |
| Šablona EPC (ON/OFF) | Výchozí je **OFF** (EPC = TID). ON = 7řádková šablona |
| Ruční tlačítka ZAPSAT EPC / HESLO / ZAMKNOUT | Oprava jednoho kroku bez celého cyklu |
| Ruční výkon v dBm | Jemné doladění místo presetů Daleko / Blízko |

---

## 14. Slovníček

| Pojem | Význam |
|-------|--------|
| **UDU** | Úsek dráhy – kód stanice (TUDU) |
| **TUDU** | Plný kód úseku včetně 6 znaků |
| **Výhybka / OBJEKT** | Číslo výhybky na úseku (např. 10A) |
| **Čip / POZICE** | Část výhybky (1–4, u hranice TUDU = 5) |
| **Daleko / Blízko** | Výkon čtečky (v koleji / v ruce) |
| **EPC** | Identifikátor zapsaný v tagu (24 hex znaků) |
| **TID** | Tovární identifikátor čipu |
| **CSV** | Tabulka se všemi záznamy zápisů |
| **DZS** | Databáze s polohami výhybek a úseků |

---

*Dokument vytvořen pro RFID Go GPS verze 3.158. Technické detaily vývoje a sestavení viz README.md v repozitáři projektu.*
