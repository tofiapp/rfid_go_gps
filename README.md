# RFID Go GPS

Android aplikace (verze **3.2**) pro čtečku **Chainway C5** (vestavěný UHF UART modul, RSCJA/Chainway SDK).
Slouží k **přepisu EPC** UHF tagů podle definované šablony, **zaheslování** a **zamčení** tagů, k **zápisu údajů o tagu do tabulky CSV** a k **záznamu GPS polohy čtečky** při každém tagu.

---

## Co aplikace umí

Hlavní obrazovka vede operátora třemi kroky (**TUDU → Načtení → Hotovo**). Výběr TUDU a výhybky je vždy nahoře; pokročilé funkce (EPC šablona, CSV, heslo, zamčení) jsou ve vyjížděcím panelu **Pokročilé**.

### Průběh práce (3 kroky)

| Krok | Název | Popis |
|------|-------|-------|
| 1 | TUDU | Vybrána databáze SQLite, TUDU a výhybka (z GPS nebo ručně) |
| 2 | Načtení | Probíhá zápis EPC, hesla a zamčení tagu |
| 3 | Hotovo | Tag úspěšně zpracován – potvrzení nebo opakování |

### 1. Zdroj dat – SQLite databáze a GPS
- Načte databázi **SQLite** (`.db` / `.sqlite`) s tabulkami `DZS_SUPERTRA_GPS_KM` a `DZS_SUPER_RO_TPI`.
- Po spuštění automaticky hledá a načte soubor **`DZS_PASPORT_TPI.sqlite`** (kořen úložiště, Stažené soubory, složka aplikace). Ruční výběr zůstává k dispozici.
- Podle **aktuální GPS polohy** najde nejbližší bod v `DZS_SUPERTRA_GPS_KM`, z něj vezme `SUPER_Z_ID` a `SUPER_D_ID` a v `DZS_SUPER_RO_TPI` dohledá **TUDU** a **číslo výhybky**.
- Hodnoty se automaticky doplní do **náhledového panelu** nahoře (TUDU / Výhybka / čip).
- TUDU a výhybku lze kdykoli **ručně změnit** klepnutím na náhledový panel – tím se vypne automatická aktualizace z GPS.
- Výběr výhybky zohledňuje **již zapsané části v CSV** – dokončené výhybky jsou v seznamu zašedlé a nevybíratelné.
- Při výběru výhybky se automaticky nastaví **první chybějící část** podle CSV.
- **Testovací režim GPS** (zaškrtávátko v kartě TUDU): simuluje polohu podle souřadnic z databáze – vhodné bez signálu GPS (např. v budově) nebo pro testování z libovolné vzdálenosti. Vyberte bod v dialogu *Simulovaná poloha*; **TUDU a výhybka se doplní automaticky** stejným postupem jako u skutečné GPS (nejblížší výhybka podle předpočítaných souřadnic). Do CSV se zapíší simulované souřadnice (řádek GPS stavu začíná `TEST`).

**Očekávané sloupce**

`DZS_SUPERTRA_GPS_KM`: `SUPER_Z_ID`, `SUPER_D_ID`, souřadnice (`LAT`/`LON` primárně, alternativně `LATITUDE`/`LONGITUDE`, …)

`DZS_SUPER_RO_TPI`: `SUPER_Z_ID`, `SUPER_D_ID`, `TUDU`, `COBJEKT` (číslo výhybky; alternativně `VYHYBKA`, …) (volitelně `CAST_MIN`, `CAST_MAX`, `POLOHA`, `KMK_INT`). Řádky s prázdnou `POLOHA` nebo textem `NULL` se při indexaci vyřazují.

`DZS_SUPERTRA_GPS_KM`: volitelně `KM_INT` – pro přesnou vzdálenost výhybky se páruje s `KMK_INT` v RO tabulce (výhybky se stejným `SUPER_Z_ID`/`SUPER_D_ID` mají jiný kilometrický bod).

Vzorová databáze je ve složce [`sample_data/`](sample_data).

**Předindexace (rychlejší start):** Index lze připravit na PC **bez Pythonu** – stačí Java 17+ a `preindex-dzs.jar` (viz [`docs/INDEXACE_DZS.md`](docs/INDEXACE_DZS.md)). Alternativně [`tools/preindex_dzs.py`](tools/preindex_dzs.py) s Pythonem 3.

**Nápověda k části výhybky** – u výhybek se třemi částmi (1–3) se pod výběrem zobrazí textová nápověda:
- část 1 → *jazyk*
- část 2 → *levé rameno*
- část 3 → *pravé rameno*

### 2. Šablona EPC a zápis
EPC = **24 hex znaků** (bank EPC, ptr 2, Len 6). Skládá se podle 7 řádků šablony:

| # | Délka | Kategorie | Pravidlo | Příklad |
|---|-------|-----------|----------|---------|
| 1 | 4 | Rok | fixně, lze přepsat | `2026` |
| 2 | 4 | TUDU 1.–4. znak | první 4 znaky TUDU | `1501` |
| 3 | 2 | TUDU 5. znak | ASCII hex | `J` → `4A` |
| 4 | 2 | TUDU 6. znak | 2-místně | `1` → `01` |
| 5 | 3 | Výhybka | 3-místně dekadicky | `10` → `010` |
| 6 | 1 | Část výhybky | 1 znak (1–4) | `1` |
| 7 | 8 | ID_RFID | 8-místně dekadicky, +1 | `30001` → `00030001` |

Příklad: `1501J1`, výhybka `10`, část `1`, ID `30001` →
`2026 1501 4A 01 010 1 00030001` = `202615014A01010100030001`.

- **Názvy kategorií** i hodnoty Rok / Část / ID_RFID lze ručně přepsat.
- Náhled EPC a validace (24 hex znaků) jsou v panelu **Pokročilé**.
- Pod šablonou je rozhraní zápisu: `bank EPC`, `ptr 2`, `Len 6`, **Access pwd** (default `00000000`) a výkon v dBm.
- Tlačítko **ZAPSAT EPC** přepíše EPC tagu v dosahu.
- Po úspěšném zápisu se zobrazí původní EPC a TID tagu.
- Při selhání zápisu s uživatelským heslem se automaticky zkusí **preset hesla** `11223344`, `11112222` (třetí doplníte později).
- Po dokončení celého cyklu (viz níže) se automaticky:
  - `ID_RFID += 1` (hodnota se ukládá do aplikace),
  - posune **část výhybky** o 1; po překročení maxima se přepne na **další nedokončenou výhybku** v pořadí daného TUDU.

### 3. Tabulka CSV
Po každém zápisu EPC (lze vypnout zaškrtávátkem) se uloží řádek do `rfid_go_gps_output.csv`:

| Sloupec | Zdroj |
|---------|-------|
| ID_RFID | řádek 7 bez vodících nul (`00030001` → `30001`) |
| EPC | celých 24 znaků |
| TID | přečtený z tagu |
| Rok | řádek 1 |
| TUDU | řádek 2 + dekódovaný 3 (`4A`→`J`) + dekódovaný 4 (`01`→`1`) |
| Vyhybka | řádek 5 (`010` → `10`) |
| CastVyhybky | řádek 6 |
| latitude | GPS šířka čtečky v okamžiku zápisu |
| longitude | GPS délka čtečky v okamžiku zápisu |
| accuracy_m | přesnost GPS v metrech |
| gps_time | čas měření GPS (`yyyy-MM-dd HH:mm:ss`) |

Při zápisu stejného `ID_RFID` se daný řádek **přepíše**.
Tabulku lze sdílet tlačítkem **Sdílet / Export** nebo **vymazat poslední záznam** (obnoví se předchozí stav šablony).
Nad spodním panelem se zobrazuje náhled **posledního záznamu** (výhybka a část).

Soubor je uložen v `Android/data/com.rfidw.app.gps/files/rfid_go_gps_output.csv`.

> **Souběžná instalace:** GPS verze má `applicationId` `com.rfidw.app.gps`, takže ji lze mít nainstalovanou vedle původní **RFID Go** (`com.rfidw.app`) bez vzájemného přepisování.

Starší CSV soubory bez GPS sloupců lze načíst – GPS pole zůstanou prázdná.

### 4. GPS poloha čtečky
- Aplikace po spuštění žádá o oprávnění k poloze a **aktualizuje GPS cache každých 500 ms** (satelitní fix má přednost před síťovou polohou).
- Ve stavu **připraveno** se poloha zobrazuje **mezi horním řádkem (logo, režim, stav) a indikátorem 3 kroků**, např. `49.1951° 16.6084° ±6m`. Horní řádek zobrazuje pouze `připraveno`.
- Při zápisu tagu se do CSV uloží nejlepší známá poloha (bez čekání na nový fix).
- Pokud GPS není dostupná, tag se uloží bez souřadnic a operátor dostane jednorázové upozornění.
- Během zápisu EPC / hesla / zamčení zůstává v horním řádku text průběhu operace a řádek GPS je skrytý.

### 5. Zaheslování – zápis access hesla
- **bank RESERVED**, `ptr 2`, `len 2` (access password, 8 hex znaků)
- Pole **ACCESS PWD** – aktuální heslo tagu (default `00000000`)
- Pole **NEW PWD** – nové heslo (8 hex znaků)
- Tlačítko **ZAPSAT HESLO** zapíše nové access heslo na tag v dosahu
- Stejný fallback na preset hesla jako u zápisu EPC

### 6. Zamčení tagu
- Pole **NEW ACCESS PWD** – heslo pro zamčení (po zápisu hesla se doplní automaticky)
- **Lock code** – pevná hodnota `008020`
- Tlačítko **ZAMKNOUT** zamkne tag v dosahu

### Spouště čtečky
Fyzické tlačítko (spouště) čtečky spouští **celý řetězec** v jednom kroku:

1. zápis EPC → 2. zápis access hesla → 3. zamčení tagu

Po úspěšném dokončení se zobrazí přehled **Načetli jste** (výhybka + část) s volbami:
- **Pokračovat** – posune část/výhybku a připraví další tag (stejně jako po ručním dokončení cyklu),
- **Opakovat** – zůstane na stejné části pro nový pokus.

Během zobrazení tohoto dialogu lze **Pokračovat** potvrdit i fyzickým tlačítkem čtečky.

Jednotlivé akce (jen EPC, jen heslo, jen zamčení) lze spustit i ručně tlačítky v panelu **Pokročilé**.

---

## Sestavení

Projekt je standardní Android (Gradle). Otevřete v **Android Studiu** nebo přes Cursor a:

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/rfid_go_gps.apk`

- `compileSdk 34`, `minSdk 21`, `targetSdk 34`, Java 17, Material 3
- Android Gradle Plugin **8.5.2**, Gradle **8.7**
- Knihovny čtečky a Excelu jsou v `app/libs/`:
  - `DeviceAPI_ver20251103_release.aar` – Chainway/RSCJA UHF SDK (obsahuje i nativní `.so`),
  - `poi-*`, `jxl.jar`, `xUtils-*` – ponechány pro budoucí export do XLSX.

> Pozn.: Při prvním otevření Android Studio vygeneruje `local.properties` s cestou k SDK.

### CI (GitHub Actions)

Po mergi PR do `main` workflow [`.github/workflows/android.yml`](.github/workflows/android.yml):

1. zvýší verzi v `version.properties`,
2. sestaví debug APK (`rfid_go_gps_<verze>.apk`),
3. nahraje artefakt v Actions,
4. vytvoří **GitHub Release** s APK ke stažení (záložka *Releases*).

U otevřeného PR se APK sestaví bez zvýšení verze (artefakt u daného běhu workflow).

---

## Struktura kódu

```
app/src/main/java/com/rfidw/app/
├─ epc/EpcModel.java       – sestavení a rozklad EPC (jádro logiky)
├─ data/Tudu.java          – model TUDU + výhybky
├─ data/DzsDatabase.java   – SQLite: GPS → TUDU / výhybka
├─ data/TuduLoader.java    – (legacy) načítání z .csv / .sql
├─ csv/CsvStore.java       – výstupní CSV s přepisem podle ID_RFID
├─ location/LocationCache.java – cache GPS polohy (satelitní fix, aktualizace 500 ms)
├─ rfid/UhfManager.java    – obal nad RFIDWithUHFUART (EPC, heslo, zamčení)
└─ ui/
   ├─ MainActivity.java    – obrazovka, workflow a propojení všeho
   └─ CsvAdapter.java      – zobrazení tabulky CSV v RecyclerView

app/src/main/res/layout/
├─ activity_main.xml           – hlavní obrazovka (krok 1, indikátor, spodní panel)
├─ bottom_sheet_workflow.xml   – panel Pokročilé (EPC, karty 2–5)
├─ dialog_tudu_picker.xml      – dialog výběru TUDU s vyhledáváním
└─ row_*.xml                   – řádky šablony EPC a CSV
```

## Možné další kroky
- Export do `.xlsx` (knihovny POI/jxl jsou už přibalené).
- Nastavení (uložení access pwd, výchozí rok, výkon).
- Hromadné vymazání celé CSV tabulky.
