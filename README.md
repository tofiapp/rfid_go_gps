# RFID Go GPS

Android aplikace (verze **3.99**) pro čtečku **Chainway C5** (vestavěný UHF UART modul, RSCJA/Chainway SDK).

Slouží k **terénnímu načítání UHF tagů** na železničních výhybkách: určení polohy a kontextu z **GPS** a **SQLite databáze DZS**, zápis tagu (EPC, heslo, zamčení), export do **CSV** a záznam GPS polohy čtečky u každého tagu.

> **Souběžná instalace:** `applicationId` je `com.rfidw.app.gps` – aplikaci lze mít nainstalovanou vedle původní **RFID Go** (`com.rfidw.app`) bez vzájemného přepisování dat.

---

## Přehled obrazovky

Aplikace má **jednu hlavní obrazovku** (`MainActivity`) a vyjížděcí panel **Pokročilé**.

| Oblast | Obsah |
|--------|-------|
| Horní lišta | Logo, výkon čtečky, stav operace, GPS souřadnice |
| Indikátor 3 kroků | **UDU** → **Načtení** → **Hotovo** |
| Pod-kroky zápisu | přepis EPC → zápis do CSV → zápis hesla → zamčení |
| Karta UDU · výhybka | databáze, režim GPS/ručně, náhled výběru, výkon |
| Nápověda čipu | jazyk / levé rameno / pravé rameno; větev hlavní/vedlejší |
| Poslední záznam | náhled posledního řádku CSV |
| Panel Pokročilé | CSV, EPC šablona, heslo, zamčení, indexace DB |

---

## Co aplikace umí dnes (běžný provoz)

Toto je aktuální způsob práce v terénu – funkce, na které aplikace staví každodenní workflow.

### Typický postup operátora

1. Aplikace automaticky načte databázi **`DZS_PASPORT_TPI.sqlite`** ze složky **Documents/RFID Go GPS** (nebo ji vyberete ručně).
2. **GPS** (nebo testovací režim) určí **UDU** a **výhybku**; podle existujícího CSV se doplní **první chybějící čip**.
3. Operátor zvolí výkon: **v koleji** (16 dBm) nebo **v ruce** (1 dBm).
4. U dvojvětvých 3částových výhybek vybere u čipů 2–3 větev **hlavní / vedlejší**.
5. **Spouště čtečky** spustí celý řetězec jedním stiskem:
   - zápis EPC (výchozí režim **TID → EPC**) → zápis do CSV → zápis access hesla → zamčení tagu
6. Dialog **„Načetli jste“** nabídne **Pokračovat** (posun na další čip) nebo **Opakovat** (stejný čip znovu). **Pokračovat** lze potvrdit i spouštěm.

### 1. SQLite databáze DZS a výběr UDU / výhybky / čipu

- Zdroj dat je **SQLite** (`.db` / `.sqlite`) s tabulkami `DZS_SUPERTRA_GPS_KM` a `DZS_SUPER_RO_TPI`.
- Po spuštění aplikace automaticky hledá **`DZS_PASPORT_TPI.sqlite`** ve složce **Documents/RFID Go GPS** (společně s CSV; funguje i po přeinstalaci). Starší umístění ve Stažených souborech je stále podporováno. Podporovány jsou i soubory s `DZS` / `PASPORT` v názvu.
- Ruční výběr databáze zůstává k dispozici.
- Podle **aktuální GPS polohy** se najde nejbližší výhybka a příslušný **UDU** (stanice = prvních 5 znaků TUDU). Do EPC a CSV se zapisuje **plný TUDU** včetně 6. znaku (podtyp).
- Hodnoty se zobrazí v **náhledovém panelu** nahoře (UDU / výhybka / čip).
- **Režim GPS** (výchozí) vs **Ručně** – přepínač v kartě UDU; volba se ukládá.
- UDU a výhybku lze kdykoli **ručně změnit** klepnutím na náhled – v GPS režimu se tím vypne automatická aktualizace, dokud nekliknete **Načíst polohu**.
- Výběr výhybky zohledňuje **již zapsané čipy v CSV** – dokončené výhybky jsou v seznamu zašedlé a nevybíratelné.
- Při výběru výhybky se automaticky nastaví **první chybějící čip**.
- **Testovací režim GPS**: simuluje polohu podle souřadnic z databáze – vhodné bez signálu (např. v budově) nebo pro testování. Do CSV se zapíší simulované souřadnice (řádek GPS času začíná `TEST`).

**Očekávané sloupce**

`DZS_SUPERTRA_GPS_KM`: `SUPER_Z_ID`, `SUPER_D_ID`, `KM_EXT`, souřadnice (`LAT`/`LON` primárně, alternativně `LATITUDE`/`LONGITUDE`, …)

`DZS_SUPER_RO_TPI`: `SUPER_Z_ID`, `SUPER_D_ID`, `TUDU`, `COBJEKT` (číslo výhybky; alternativně `VYHYBKA`, …), `OD`, `DO` (volitelně `CAST_MIN`, `CAST_MAX`, `POLOHA`, `RO_ID`). Řádky s prázdnou `POLOHA` nebo textem `NULL` se při indexaci vyřazují. Pokud chybí `CAST_MAX`, aplikace ho odvodí z prvního písmene `POLOHA`: **J** = 3částová výhybka, **C** = 4částová.

Vzorová data (legacy formát CSV/SQL, viz níže) jsou ve složce [`sample_data/`](sample_data).

**Indexace a cache:** Při otevření databáze se nejdřív indexuje okolí GPS (~4 km), poté běží **plná indexace na pozadí** s diskovou cache podle otisku obsahu souboru. Po restartu aplikace se index znovu načte během desítek sekund. Podrobnosti: [`docs/INDEXACE_DZS.md`](docs/INDEXACE_DZS.md).

**Nápověda k čipu** – u 3částových výhybek:
- čip 1 → *jazyk*
- čip 2 → *levé rameno*
- čip 3 → *pravé rameno*

U dvojvětvých výhybek (více `RO_ID` v DB) se u čipů 2–3 vybírá větev **hlavní / vedlejší**.

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
| VYHYBKA | číslo výhybky |
| CIP | část výhybky (1–4) |
| POLOHA | kód polohy z DB (např. JA, JB) |
| RO_ID_1 | první RO_ID větve |
| RO_ID_2 | druhá RO_ID větve (pokud existuje) |
| KM_EXT | kilometrový bod odvozený z OD/DO/KM_REF |
| LAT | GPS šířka čtečky |
| LON | GPS délka čtečky |
| ACCURACY_M | přesnost GPS v metrech |
| GPS_TIME | čas měření (`yyyy-MM-dd HH:mm:ss`; v testovacím režimu prefix `TEST`) |

Při zápisu stejného `ID_RFID` se daný řádek **přepíše**.
Tabulku lze **sdílet / exportovat** nebo **vymazat poslední záznam** (obnoví se předchozí stav šablony a výběru).
Nad spodním panelem se zobrazuje náhled **posledních 5 řádků** a box **poslední záznam** na hlavní obrazovce.

Soubor: **`Documents/RFID Go GPS/rfid_go_gps_output.csv`** – stejná složka jako databáze DZS.

**Nahrání z PC přes USB:** Průzkumník → čtečka → **Documents** → **RFID Go GPS** → přepište `rfid_go_gps_output.csv`. Aplikace při běhu zapisuje do interní kopie; soubor ve složce Documents aktualizuje až při minimalizaci. Po nahrání z PC otevřete aplikaci (nebo panel Pokročilé). Bez USB: tlačítko **Načíst CSV** v aplikaci.

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

Ruční nastavení výkonu v dBm je k dispozici v panelu Pokročilé (viz níže).

---

## Co aplikace umí, ale běžně nepoužíváme

Tyto funkce jsou **plně implementované** a dostupné v UI, ale v současném provozu jsou vedlejší, skryté v panelu **Pokročilé**, nebo výchozí vypnuté. Zůstávají v kódu záměrně – lze je kdykoli zapnout bez úprav aplikace.

### Šablona EPC (7 řádků) – výchozí OFF

Kompletní sestavení EPC podle šablony je připravené, ale **v terénu se dnes nepoužívá**. Přepínač **Šablona EPC** v panelu Pokročilé:

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

### Přepis názvů kategorií šablony EPC

Řádky šablony mají editovatelné popisky (rok, TUDU, výhybka, …). Nemění logiku zápisu, jen zobrazení v UI – praktický dopad je minimální.

### Karta indexace databáze

V Pokročilých je viditelný průběh **plné indexace na pozadí** (procenta, fáze). Indexace sama probíhá automaticky při otevření DB; karta slouží hlavně pro přehled a diagnostiku.

### Preset access hesla

Pole `PRESET_ACCESS_PASSWORDS` obsahuje známá hesla (`11223344`, `11112222`, `12345678`) zkoušená při selhání zápisu s uživatelským heslem.

---

## Připravené v kódu, ale nezapojené

Funkce nebo knihovny, které v repozitáři existují, ale aplikace je v provozu **nevolá**.

| Položka | Stav |
|---------|------|
| **`TuduLoader.java`** | Legacy načítání TUDU z `.csv` / `.sql` – nahrazeno `DzsDatabase`; třída zůstává v kódu, vzorová data v [`sample_data/`](sample_data) |
| **Apache POI, jxl, xUtils** | Knihovny v `app/libs/` pro budoucí **export do XLSX** – žádný import v Java kódu |
| **`UhfManager.readSingle()`** | Veřejná metoda pro čtení tagu bez zápisu – není napojená na UI |
| **Hromadné vymazání CSV** | V UI jen smazání **posledního** záznamu |
| **Samostatná obrazovka Nastavení** | Neexistuje; perzistentní jsou jen vybrané preference (viz níže) |
| **Import CSV z externího souboru** | Ne – načítá se pouze vlastní výstupní soubor aplikace |
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

Knihovny v `app/libs/`:
- `DeviceAPI_ver20251103_release.aar` – Chainway/RSCJA UHF SDK (nativní `.so`),
- `poi-*`, `jxl.jar`, `xUtils-*` – připraveno pro budoucí XLSX export.

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
│  ├─ Tudu.java                – model TUDU, výhybka, RO větve, UDU kód
│  ├─ DzsDatabase.java         – SQLite: GPS lookup, indexace, spatial grid
│  ├─ DzsIndexCache.java       – disková cache indexů (gzip .idx)
│  ├─ VyhybkaGpsStore.java     – kompaktní GPS souřadnice výhybek
│  └─ TuduLoader.java          – (legacy, nepoužíváno) CSV/SQL loader
├─ csv/
│  ├─ CsvStore.java            – načtení/zápis CSV, index zapsaných čipů
│  └─ CsvRecordBuilder.java   – sestavení řádku CSV z provozního stavu
├─ kmext/KmExtResolver.java    – KM_EXT z OD/DO/KM_REF
├─ location/LocationCache.java – cache GPS polohy (500 ms)
├─ rfid/UhfManager.java        – obal nad RFIDWithUHFUART (EPC, heslo, zamčení)
└─ ui/
   ├─ MainActivity.java        – hlavní obrazovka, workflow, propojení všeho
   └─ CsvAdapter.java          – tabulka CSV v RecyclerView

app/src/main/res/layout/
├─ activity_main.xml           – hlavní obrazovka
├─ bottom_sheet_workflow.xml   – panel Pokročilé
├─ dialog_tudu_picker.xml      – dialog výběru UDU
└─ row_*.xml                   – řádky šablony EPC a CSV

docs/
└─ INDEXACE_DZS.md              – indexace a cache DZS databáze
```

---

## Možné další kroky

- Export do `.xlsx` (knihovny POI/jxl jsou přibalené).
- Perzistentní nastavení (access pwd, výchozí rok, výkon).
- Hromadné vymazání celé CSV tabulky.
- Doplnění třetího preset access hesla.
- Zapojení `TuduLoader` nebo jeho odstranění po úplném přechodu na DZS SQLite.
