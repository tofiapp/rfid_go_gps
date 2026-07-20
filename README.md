# RFID Go GPS

Android aplikace (verze **3.142**) pro čtečku **Chainway C5** (vestavěný UHF UART modul, RSCJA/Chainway SDK).

Slouží k **terénnímu načítání UHF tagů** na železničních výhybkách: určení polohy a kontextu z **GPS** a **SQLite databáze DZS**, zápis tagu (EPC, heslo, zamčení), export do **CSV** a záznam GPS polohy čtečky u každého tagu.

> **Souběžná instalace:** `applicationId` je `com.rfidw.app.gps` – aplikaci lze mít nainstalovanou vedle původní **RFID Go** (`com.rfidw.app`) bez vzájemného přepisování dat.

---

## Obsah

- [Přehled obrazovky](#přehled-obrazovky)
- [Rychlý start v terénu](#rychlý-start-v-terénu)
- [Běžný provoz](#běžný-provoz)
  - [SQLite databáze DZS](#1-sqlite-databáze-dzs-a-výběr-udu--výhybky--čipu)
  - [Zápis EPC (TID → EPC)](#2-zápis-epc--režim-tid--epc-výchozí)
  - [Tabulka CSV](#3-tabulka-csv)
  - [GPS poloha čtečky](#4-gps-poloha-čtečky)
  - [Zaheslování a zamčení](#5-zaheslování-a-zamčení-tagu)
  - [Výkon čtečky](#6-výkon-čtečky)
  - [Kontrola načteného tagu](#7-kontrola-načteného-tagu)
  - [Hranice TUDU (čip 5)](#8-hranice-tudu-čip-5)
- [Pokročilé funkce](#pokročilé-funkce-panel-pokročilé)
- [Připravené v kódu, ale nezapojené](#připravené-v-kódu-ale-nezapojené)
- [Sestavení](#sestavení)
- [Struktura kódu](#struktura-kódu)
- [Dokumentace](#dokumentace)
- [Audit a optimalizace (v3.141)](#audit-a-optimalizace-v3141)
- [Možné další kroky](#možné-další-kroky)

---

## Přehled obrazovky

Aplikace má **jednu hlavní obrazovku** (`MainActivity`) a vyjížděcí panel **Pokročilé**.

| Oblast | Obsah |
|--------|-------|
| Horní lišta | Logo, výkon čtečky, stav operace, GPS souřadnice |
| Indikátor 3 kroků | **UDU** → **Načtení** → **Hotovo** |
| Pod-kroky zápisu | přepis EPC → zápis do CSV → zápis hesla → zamčení |
| Karta UDU · výhybka | databáze, režim GPS/ručně, náhled výběru, výkon, tlačítko **Hranice TUDU** |
| Nápověda čipu | jazyk / levé rameno / pravé rameno; u dvojvětvých výhybek **Rovně / Odbočka**; u 4částových konkrétní kódy z DB (CA/CB, CG/CH, …) |
| Tlačítko **Kontrola** | celoobrazovkový režim ověření tagu proti CSV |
| Poslední záznam | náhled posledního řádku CSV |
| Panel Pokročilé | CSV, EPC šablona, heslo, zamčení |

---

## Rychlý start v terénu

1. Uložte databázi **`DZS_PASPORT_TPI.sqlite`** do složky **Stažené soubory** (Download) na čtečce – aplikace ji při startu najde automaticky.
2. Povolte **polohu** (GPS) a případně **přístup ke všem souborům**, aby byl CSV viditelný z PC přes USB.
3. Po načtení databáze vyčkejte na GPS fix – aplikace doplní **UDU**, **výhybku** a **první chybějící čip** podle existujícího CSV.
4. Zvolte výkon **v koleji** (16 dBm) nebo **v ruce** (1 dBm).
5. U dvojvětvých 3částových výhybek u čipů 2–3 zvolte **Rovně** nebo **Odbočka**.
6. Stiskněte **spouště čtečky** – proběhne TID→EPC, zápis CSV, heslo a zamčení.
7. V dialogu **„Načetli jste“** zvolte **Pokračovat** (další čip) nebo **Opakovat**. **Pokračovat** lze potvrdit i spouštěm.

---

## Běžný provoz

Toto je aktuální způsob práce v terénu – funkce, na které aplikace staví každodenní workflow.

### 1. SQLite databáze DZS a výběr UDU / výhybky / čipu

- Zdroj dat je **SQLite** (`.db` / `.sqlite`) s tabulkami `DZS_SUPERTRA_GPS_KM` a `DZS_SUPER_RO_TPI`.
- Po spuštění aplikace automaticky hledá **`DZS_PASPORT_TPI.sqlite`** ve složce Stažené soubory (funguje i po přeinstalaci – data aplikace se smažou, soubor ve Stažených zůstane). Podporovány jsou i soubory s `DZS` / `PASPORT` v názvu.
- Ruční výběr databáze zůstává k dispozici.
- Podle **aktuální GPS polohy** se najde nejbližší výhybka a příslušný **UDU** (stanice = prvních 5 znaků TUDU). Do EPC a CSV se zapisuje **plný TUDU** včetně 6. znaku (podtyp).
- Hodnoty se zobrazí v **náhledovém panelu** nahoře (UDU / výhybka / čip).
- **Režim GPS** (výchozí) vs **Ručně** – přepínač v kartě UDU; volba se ukládá.
- UDU a výhybku lze kdykoli **ručně změnit** klepnutím na náhled – v GPS režimu se tím vypne automatická aktualizace, dokud nekliknete **Načíst polohu**.
- Výběr výhybky zohledňuje **již zapsané čipy v CSV** – dokončené výhybky jsou v seznamu zašedlé a nevybíratelné.
- Při výběru výhybky se automaticky nastaví **první chybějící čip**.
- **Testovací režim GPS**: simuluje polohu podle souřadnic z databáze – vhodné bez signálu (např. v budově) nebo pro testování. Do CSV se zapíší simulované souřadnice (řádek GPS času začíná `TEST`).

**Typy výhybek**

| Typ | Rozpoznání v DB | Čipy | Nápověda v UI |
|-----|-----------------|------|---------------|
| 3částová | `POLOHA` začíná **J** | 1–3 | jazyk, levé rameno, pravé rameno |
| 3částová dvojvětvá | více `RO_ID` | 2–3 | navíc volba **Rovně / Odbočka** |
| 4částová | `POLOHA` začíná **C** | 1–4 | konkrétní kódy sady (CA/CB, CG/CH, CE/CF, …) |

Číslo výhybky může obsahovat volitelné **IOB písmeno** z databáze (např. `10A`) – zobrazuje se v UI i ukládá do sloupce `OBJEKT` v CSV.

**Očekávané sloupce**

`DZS_SUPERTRA_GPS_KM`: `SUPER_Z_ID`, `SUPER_D_ID`, `KM_EXT`, souřadnice (`LAT`/`LON` primárně, alternativně `LATITUDE`/`LONGITUDE`, …)

`DZS_SUPER_RO_TPI`: `SUPER_Z_ID`, `SUPER_D_ID`, `TUDU`, `COBJEKT` (číslo výhybky; alternativně `VYHYBKA`, …), volitelně `IOB`, `OD`, `DO` (volitelně `CAST_MIN`, `CAST_MAX`, `POLOHA`, `RO_ID`). Řádky s prázdnou `POLOHA` nebo textem `NULL` se při indexaci vyřazují. Pokud chybí `CAST_MAX`, aplikace ho odvodí z prvního písmene `POLOHA`: **J** = 3částová výhybka, **C** = 4částová.

**Indexace a cache:** Při otevření databáze se indexuje jen **okolí aktuální GPS polohy** (bbox ~4 km). Výsledek se ukládá do diskové cache **`.pidx`** podle otisku obsahu DB a GPS buňky (formát **v21**). Při posunu o více než ~3 km se okolí doindexuje znovu; při návratu do stejné oblasti se cache načte během sekund. Staré soubory plné indexace `dzs_*.idx` se při otevření DB automaticky smažou (viz [audit v3.141](#audit-a-optimalizace-v3141)). Technické detaily: [`docs/INDEXACE_DZS.md`](docs/INDEXACE_DZS.md) *(část popisu plné indexace je zastaralá)*.

### 2. Zápis EPC – režim TID → EPC (výchozí)

V běžném provozu se **nepoužívá 7řádková šablona EPC**. Výchozí režim (**Šablona EPC = OFF**):

- Čtečka načte tag v dosahu, přečte jeho **TID** a zapíše ho jako nové **EPC** (24 hex znaků, bank EPC, ptr 2, Len 6).
- Jeden krok = načtení i přepis; operátor nemusí nic nastavovat v panelu Pokročilé.

Po úspěšném zápisu se zobrazí původní EPC a TID tagu. Při selhání zápisu s uživatelským heslem se automaticky zkusí **preset hesla** `11223344`, `11112222`, `12345678`.

Po dokončení celého cyklu (spouště nebo ruční potvrzení) se automaticky:
- zvýší `ID_RFID` o 1 (uloženo v aplikaci, minimum 400),
- posune **čip** o 1; po dokončení výhybky přejde na **další nedokončenou výhybku** v rámci UDU.

### 3. Tabulka CSV

Po každém zápisu EPC (lze vypnout zaškrtávátkem, výchozí zapnuto) se uloží řádek do `rfid_go_gps_output.csv`:

| Sloupec | Popis |
|---------|-------|
| ID_RFID | pořadové číslo tagu |
| EPC | celých 24 hex znaků |
| TID | přečtený z tagu |
| TUDU | plný kód úseku |
| OBJEKT | číslo výhybky (včetně IOB písmene, např. `10A`) |
| POZICE | část výhybky (1–4; u hranice TUDU = 5) |
| POLOHA | kód polohy z DB (např. JA, JB, CA, CB) |
| RO_ID_1 | první RO_ID větve |
| RO_ID_2 | druhá RO_ID větve (pokud existuje) |
| KM_EXT | kilometrový bod odvozený z OD/DO/KM_REF |
| LAT | GPS šířka čtečky |
| LON | GPS délka čtečky |
| ACCURACY_M | přesnost GPS v metrech |
| GPS DATE | datum měření (`yyyy-MM-dd`) |

Při zápisu stejného `ID_RFID` se daný řádek **přepíše**.
Tabulku lze **sdílet / exportovat** nebo **vymazat poslední záznam** (obnoví se předchozí stav šablony a výběru).
Nad spodním panelem se zobrazuje náhled **posledních 5 řádků** a box **poslední záznam** na hlavní obrazovce.

**Umístění souboru:** `Download/rfid_go_gps_output.csv` – přímo ve složce Stažené soubory.

Na **Androidu 10+** nový soubor vzniká přes **MediaStore** (`IS_PENDING` → publikace pro MTP). Přímý zápis na disk probíhá u již existujícího souboru (typicky nahraného z PC). Logika je v `CsvStorage.java`.

**Nahrání z PC přes USB:** V Průzkumníku Windows otevřete čtečku → vnitřní úložiště → **Download** (Stažené soubory) a soubor `rfid_go_gps_output.csv` tam zkopírujte nebo přepište. Pokud soubor v MTP nevidíte, povolte u aplikace **„Přístup ke všem souborům“** (dialog při prvním zápisu nebo v systémovém nastavení). Po nahrání se vraťte do aplikace – CSV se automaticky načte (detekce podle otisku obsahu). Alternativa: tlačítko **Načíst CSV** v panelu Pokročilé.

Aplikace při startu **automaticky načte** existující CSV a podle něj určí dokončené čipy a obnoví `ID_RFID`. Starší formáty CSV (včetně sloupce `rok` a bez GPS) jsou zpětně kompatibilní.

### 4. GPS poloha čtečky

- Po spuštění žádá o oprávnění k poloze a **aktualizuje GPS cache každých 500 ms** (satelitní fix má přednost před síťovou polohou).
- Ve stavu **připraveno** se poloha zobrazuje mezi horním řádkem a indikátorem kroků, např. `49.1951° 16.6084° ±6m`.
- Při zápisu tagu se do CSV uloží nejlepší známá poloha (bez čekání na nový fix).
- Pokud GPS není dostupná, tag se uloží bez souřadnic a operátor dostane jednorázové upozornění.
- Během zápisu zůstává v horním řádku text průběhu operace a řádek GPS je skrytý.

### 5. Zaheslování a zamčení tagu

Spouště po zápisu EPC a CSV automaticky:

1. **Zápis access hesla** – bank RESERVED, ptr 2, len 2 (8 hex znaků). Výchozí nové heslo: `12345678`.
2. **Zamčení tagu** – lock code `008020` (pevná hodnota).

Stejný fallback na preset hesla jako u zápisu EPC.

### 6. Výkon čtečky

Před zápisem je nutné zvolit preset:
- **v koleji** – 16 dBm
- **v ruce** – 1 dBm

Ruční nastavení výkonu v dBm je k dispozici v panelu Pokročilé (viz níže). Stejné presety má i režim **Kontrola**.

### 7. Kontrola načteného tagu

Tlačítko **Kontrola** na hlavní obrazovce otevře celoobrazovkový režim pro **ověření již zapsaného tagu** proti tabulce CSV:

1. Zvolte výkon (v koleji / v ruce).
2. Načtěte tag spouštěm – aplikace přečte **EPC** a **TID**.
3. Pokud tag existuje v CSV, zobrazí se uložené údaje: EPC, TID, TUDU, OBJEKT, POZICE, POLOHA, RO_ID, KM_EXT.
4. Pokud stejný tag odpovídá **více řádkům** CSV, lze mezi nimi přepínat šipkami (např. `2 / 5`).
5. Tag mimo CSV → hláška **„Tag není v CSV“**.

Režim Kontrola **nezapisuje** do tagu ani do CSV – slouží jen ke kontrole v terénu.

### 8. Hranice TUDU (čip 5)

Speciální režim pro zápis tagů na **hranici TUDU** mimo běžný cyklus výhybky (čip **5**):

1. Klepněte na **Hranice TUDU** v kartě UDU.
2. Vyplňte **TUDU** (ručně nebo výběrem z 10 nejbližších podle GPS), **objekt** (kolej / výhybka) a volitelně **KM_EXT**.
3. Po potvrzení aplikace přepne do režimu **čip 5** – v náhledu se místo „výhybka“ zobrazuje **objekt**.
4. Spouště provede stejný řetězec jako u běžného zápisu (EPC → CSV → heslo → zamčení).
5. Do CSV se uloží `POZICE = 5`, prázdná `POLOHA` a zadaný objekt v `OBJEKT`.

Režim se ukončí při ruční změně UDU/výhybky, načtení polohy z GPS nebo při obnovení stavu z CSV řádku s jiným čipem.

---

## Pokročilé funkce (panel Pokročilé)

Tyto funkce jsou plně implementované, ale v současném provozu jsou vedlejší, skryté v panelu **Pokročilé**, nebo výchozí vypnuté.

### Šablona EPC (7 řádků) – výchozí OFF

Kompletní sestavení EPC podle šablony je připravené, ale **v terénu se dnes nepoužívá**. Přepínač **Šablona EPC**:

| Režim | Chování |
|-------|---------|
| **OFF** (výchozí) | EPC = TID načteného tagu |
| **ON** | EPC se sestaví ze šablony níže |

EPC = **24 hex znaků** (bank EPC, ptr 2, Len 6). Šablona má 7 řádků:

| # | Délka | Kategorie | Pravidlo | Příklad |
|---|-------|-----------|----------|---------|
| 1 | 4 | Rok | fixně, lze přepsat | `2026` |
| 2 | 4 | TUDU 1.–4. znak | první 4 znaky TUDU | `1501` |
| 3 | 2 | TUDU 5. znak | ASCII hex | `J` → `4A` |
| 4 | 2 | TUDU 6. znak | 2-místně | `1` → `01` |
| 5 | 3 | Výhybka | 3-místně dekadicky | `10` → `010` |
| 6 | 1 | Čip | 1 znak (1–4) | `1` |
| 7 | 8 | ID_RFID | 8-místně dekadicky | `30001` → `00030001` |

Příklad: TUDU `1501J1`, výhybka `10`, čip `1`, ID `30001` →
`202615014A01010100030001`.

- Názvy kategorií i hodnoty Rok / Čip / ID_RFID lze ručně přepsat.
- Náhled EPC a validace (24 hex znaků) fungují i v režimu OFF.
- Po přepnutí na ON se při zápisu použije `EpcModel.buildEpc()` místo TID→EPC.
- Logika je v `epc/EpcModel.java` – jádro aplikace, vhodné i pro testování mimo zařízení.

### Ruční jednotlivé kroky (mimo spouště)

V panelu Pokročilé lze spustit zvlášť:
- **ZAPSAT EPC** – jen přepis EPC (v režimu OFF rovnou TID→EPC)
- **ZAPSAT HESLO** – jen access heslo
- **ZAMKNOUT** – jen zamčení

V terénu se používá spouště (celý řetězec), ale ruční tlačítka jsou užitečná při ladění nebo opravě jednoho kroku.

### Ruční výkon čtečky (dBm)

Kromě presetů **v koleji / v ruce** lze v Pokročilých zadat konkrétní hodnotu v dBm a tlačítkem **Nastavit** ji aplikovat na čtečku.

### Preset access hesla

Pole `PRESET_ACCESS_PASSWORDS` obsahuje známá hesla (`11223344`, `11112222`, `12345678`) zkoušená při selhání zápisu s uživatelským heslem.

---

## Připravené v kódu, ale nezapojené

Funkce nebo rozhraní, která v repozitáři existují, ale aplikace je v provozu **nevolá** nebo nemají UI.

| Položka | Stav |
|---------|------|
| **`UhfManager.readSingle()`** | Veřejná metoda pro čtení tagu bez zápisu – není napojená na UI (režim Kontrola používá vlastní čtení) |
| **Hromadné vymazání CSV** | V UI jen smazání **posledního** záznamu |
| **Samostatná obrazovka Nastavení** | Neexistuje; perzistentní jsou jen vybrané preference (viz níže) |
| **Import libovolného CSV** | Ne – načítá se pouze vlastní výstupní soubor aplikace |
| **Export do XLSX** | Není implementován |
| **Unit / instrumentační testy** | Složky `app/src/test` a `androidTest` chybí |

### Uložená nastavení (`SharedPreferences`)

| Klíč | Obsah |
|------|-------|
| `idRfid` | pořadové číslo tagu |
| `epcTemplateMode` | ON/OFF šablony EPC |
| `tuduModeGps` | GPS vs ruční režim UDU |
| `gpsTestMode` | testovací GPS |
| `testLat` / `testLon` | simulovaná poloha |
| `dbSourcePath` / `dbDisplayName` / `dbSourceUri` | poslední databáze |

Access heslo, výkon a rok v šabloně se v UI mění, ale **neukládají se** mezi spuštěními (kromě výše uvedených prefs).

---

## Sestavení

Projekt je standardní Android (Gradle). Otevřete v **Android Studiu** nebo přes Cursor:

```bash
./gradlew assembleRelease
```

APK: `app/build/outputs/apk/release/rfid_go_gps_<verze>.apk`

Pro lokální vývoj lze použít `./gradlew assembleDebug` – oba buildy používají stejný podpis z `keystore.properties`, takže nové APK lze nainstalovat přes starší verzi bez odinstalace.

| Parametr | Hodnota |
|----------|---------|
| `compileSdk` / `targetSdk` | 34 |
| `minSdk` | 21 |
| Java | 17 |
| UI | Material 3 |
| Android Gradle Plugin | 8.5.2 |
| Gradle | 8.7 |
| Cílové zařízení | Chainway **C5** (UHF UART) |
| ABI | `armeabi-v7a`, `arm64-v8a` (bez x86 – neběží na běžném emulátoru) |

Knihovna v `app/libs/`:
- `DeviceAPI_ver20251103_release.aar` – Chainway/RSCJA UHF SDK (nativní `.so`)

> Při prvním otevření Android Studio vygeneruje `local.properties` s cestou k SDK.

### Aktualizace na zařízení

Všechna APK (CI i lokální build) jsou podepsána stejným klíčem v `app/keystore/rfid_go_gps_upload.jks` (viz `keystore.properties`). Stačí nainstalovat novější verzi přes stávající – Android ji aktualizuje bez ztráty dat aplikace.

> **Jednorázově:** Pokud máte na čtečce starší APK podepsané jiným (debug) klíčem, je nutné ho jednou odinstalovat a nainstalovat nové release APK.

### CI (GitHub Actions)

Po mergi PR do `main` workflow [`.github/workflows/android.yml`](.github/workflows/android.yml):

1. zvýší verzi v `version.properties`,
2. sestaví release APK,
3. nahraje artefakt v Actions,
4. vytvoří **GitHub Release** s APK ke stažení.

U otevřeného PR se APK sestaví bez zvýšení verze.

---

## Struktura kódu

```
app/src/main/java/com/rfidw/app/
├─ epc/EpcModel.java           – sestavení a rozklad EPC (šablona; jádro logiky)
├─ data/
│  ├─ Tudu.java                – model TUDU, výhybka, RO větve, 4částové sady, UDU kód
│  ├─ DzsDatabase.java         – SQLite: GPS lookup, indexace, spatial grid
│  ├─ DzsIndexCache.java       – disková cache okolí GPS (gzip .pidx, verze 21)
│  └─ VyhybkaGpsStore.java     – kompaktní GPS souřadnice výhybek
├─ csv/
│  ├─ CsvStore.java            – načtení/zápis CSV, index zapsaných čipů
│  ├─ CsvStorage.java           – cesta k souboru, MediaStore (Android 10+), migrace
│  └─ CsvRecordBuilder.java    – sestavení řádku CSV z provozního stavu
├─ kmext/KmExtResolver.java    – KM_EXT z OD/DO/KM_REF
├─ location/LocationCache.java – cache GPS polohy (500 ms)
├─ rfid/UhfManager.java        – obal nad RFIDWithUHFUART (EPC, heslo, zamčení)
└─ ui/
   ├─ MainActivity.java        – hlavní obrazovka, workflow, Kontrola, hranice TUDU
   └─ CsvAdapter.java          – tabulka CSV v RecyclerView

app/src/main/res/layout/
├─ activity_main.xml           – hlavní obrazovka + overlay Kontrola
├─ bottom_sheet_workflow.xml   – panel Pokročilé
├─ dialog_tudu_picker.xml      – dialog výběru UDU
├─ dialog_tudu_boundary.xml    – formulář hranice TUDU
├─ item_kontrola_field*.xml    – buňky režimu Kontrola
└─ row_*.xml                   – řádky šablony EPC a CSV

docs/
└─ INDEXACE_DZS.md              – indexace a cache DZS databáze
```

---

## Dokumentace

| Dokument | Obsah |
|----------|-------|
| [`docs/RFID_Go_GPS_prirucka.pdf`](docs/RFID_Go_GPS_prirucka.pdf) | **PDF příručka pro běžné užívání** v terénu (tisk / sdílení) |
| [`docs/prirucka-uzivatele.md`](docs/prirucka-uzivatele.md) | Zdroj příručky (Markdown); PDF: `python3 docs/generate_manual.py` |
| [`README.md`](README.md) | Uživatelská dokumentace a přehled projektu (tento soubor) |
| [`docs/INDEXACE_DZS.md`](docs/INDEXACE_DZS.md) | Technický popis indexace DZS *(část o plné indexaci je zastaralá – viz audit)* |
| [`AGENTS.md`](AGENTS.md) | Poznámky pro vývoj v Cursor Cloud VM (JDK, SDK, build) |

---

## Audit a optimalizace (v3.141)

V červenci 2026 proběhl **audit kódu a závislostí** ([PR #150](https://github.com/tofiapp/rfid_go_gps/pull/150), verze **3.141**). Cílem bylo zjednodušit aplikaci, zmenšit APK a odstranit mrtvý kód, aniž by se změnil běžný terénní workflow.

| Oblast | Před auditem | Po auditu |
|--------|--------------|-----------|
| Indexace DZS | plná indexace celé DB na pozadí + index okolí GPS | jen **index okolí GPS** (~4 km) |
| Cache | soubory `dzs_*.idx` (celá DB) | soubory `dzs_*_p_*.pidx` (okolí GPS) |
| UI | karta průběhu plné indexace v Pokročilých | odstraněna – průběh jen při načítání DB na hlavní obrazovce |
| Závislosti | Apache POI, jxl, xUtils v `app/libs/` | odstraněny (nepoužívané) |
| Mrtvý kód | `TuduLoader`, vzorová `sample_data/`, nepoužívané drawable/resources | smazáno |
| Velikost APK | ~13,6 MB | ~7,0 MB |

**Co se pro operátora nemění:** GPS výběr UDU/výhybky, zápis tagů, CSV, Kontrola, hranice TUDU a ruční výběr z databáze fungují stejně. Indexace okolí probíhá automaticky po získání GPS; při přesunu do nové oblasti se okolí doplní znovu.

---

## Možné další kroky

- Export do `.xlsx`.
- Perzistentní nastavení (access pwd, výchozí rok, výkon).
- Hromadné vymazání celé CSV tabulky.
- Aktualizace [`docs/INDEXACE_DZS.md`](docs/INDEXACE_DZS.md) podle nového modelu `.pidx` (po auditu v3.141).
- Unit testy pro `EpcModel`, `CsvStore`, `KmExtResolver` a indexaci DZS.
- Automatické testy na zařízení (instrumentation) pro UHF workflow.
