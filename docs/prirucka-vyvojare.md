# RFID Go GPS – Technická příručka pro vývojáře

**Verze aplikace:** 3.153  
**Package:** `com.rfidw.app` / `applicationId` `com.rfidw.app.gps`  
**Cílové zařízení:** Chainway C5 (UHF UART, RSCJA SDK)

---

Třetí dokument v sadě – **výhradně pro vývoj a údržbu kódu**. Začněte **kapitolou 1 (datové toky)**; detailní reference tříd je v kapitolách 2–10.

> **Tip:** Diagramy `mermaid` se nejlépe zobrazí na GitHubu / v IDE. V PDF je pod každým diagramem **tabulkový ekvivalent**.

---

## 1. Datové toky – vizuální přehled

### 1.1 Tři vrstvy aplikace

Aplikace má jednoduché rozložení: **UI orchestruje**, **doménové třídy počítají**, **hardware a soubory persistují**.

```mermaid
flowchart TB
    subgraph UI["① UI vrstva"]
        MA["MainActivity<br/><i>~5 600 řádků, jediná Activity</i>"]
    end

    subgraph LOGIC["② Doménová logika"]
        EPC["EpcModel"]
        TUDU["Tudu"]
        DZS["DzsDatabase"]
        CSV["CsvStore"]
        UHF["UhfManager"]
        LOC["LocationCache"]
    end

    subgraph IO["③ Hardware & úložiště"]
        READER["Chainway UHF UART"]
        GPSHW["GPS čip"]
        SQLITE[("DZS .sqlite")]
        FILE[("Download/*.csv")]
        CACHE[(".pidx cache")]
    end

    MA --> EPC & TUDU & DZS & CSV & UHF & LOC
    UHF --> READER
    LOC --> GPSHW
    DZS --> SQLITE & CACHE
    CSV --> FILE
```

| Vrstva | Komponenty | Odpovědnost |
|--------|------------|-------------|
| **① UI** | `MainActivity`, layout XML, `CsvAdapter` | workflow, indikátory, dialogy, spouště |
| **② Logika** | `EpcModel`, `Tudu`, `DzsDatabase`, `CsvStore`, `UhfManager`, `LocationCache` | výpočty, indexace, zápis tagu, CSV řádky |
| **③ I/O** | UHF SDK, GPS provider, SQLite, soubor CSV, `.pidx` | fyzický zápis, perzistence |

**Vlákna:** `io` (RFID + CSV), `gpsIo` (DB + GPS lookup), `ui` Handler (aktualizace obrazovky). RFID a SQLite **nikdy** na main thread.

---

### 1.2 Hlavní tok: zápis jednoho tagu

Nejčastější scénář – operátor stiskne spouště a proběhne celý řetězec.

```mermaid
sequenceDiagram
    autonumber
    actor Op as Operátor
    participant MA as MainActivity
    participant LOC as LocationCache
    participant UHF as UhfManager
    participant CSV as CsvStore

    Op->>MA: spouště (trigger key)
    MA->>MA: runTriggerAction() – validace
    MA->>LOC: getSnapshot() – GPS pro CSV
    MA->>UHF: writeEpcFromTid() nebo writeEpc()
    UHF-->>MA: WriteResult (EPC, TID)
    MA->>CSV: upsertAndPersist(řádek)
    MA->>UHF: writeAccessPassword()
    MA->>UHF: lockTag()
    MA->>Op: dialog „Načetli jste“
    Op->>MA: Pokračovat (spouště / tlačítko)
    MA->>MA: idRfid++, další čip/výhybka
```

| Krok | Kdo | Metoda | Výstup |
|:----:|-----|--------|--------|
| 1 | Operátor | spouště C5 | `onKeyDown()` |
| 2 | MainActivity | `runTriggerAction()` | validace výkonu, větve, EPC |
| 3 | LocationCache | `getSnapshot()` | LAT/LON/accuracy → CSV |
| 4 | UhfManager | `writeEpcFromTid()` * | nové EPC = TID (24 hex) |
| 5 | CsvStore | `upsertAndPersist()` | řádek v `rfid_go_gps_output.csv` |
| 6 | UhfManager | `writeAccessPassword()` | heslo `12345678` (výchozí) |
| 7 | UhfManager | `lockTag("008020")` | tag zamčen |
| 8 | MainActivity | dialog + `onScanDoneContinue()` | `idRfid++`, posun čipu |

\* Při `epcTemplateMode = true` krok 4 volá `writeEpc()` s `EpcModel.buildEpc()`.

**Stavové příznaky během řetězce:**

```
chainWorkflow = true
wfStepStates:  EPC → CSV → PWD → LOCK
               (každý: PENDING → ACTIVE → OK/FAIL)
scanDoneAwaitingConfirm = true   ← po úspěšném LOCK, čeká na potvrzení
```

---

### 1.3 Tok GPS → automatický výběr výhybky

```mermaid
flowchart TD
    A[GPS fix každých 500 ms] --> B{Pohyb &gt; 5 m<br/>a interval &gt; 1 s?}
    B -- ne --> Z[Bez změny]
    B -- ano --> C[ensureProximityLoaded]
    C --> D{Cache .pidx<br/>platná?}
    D -- ano --> E[Načti z disku]
    D -- ne --> F[SQL bbox ±4 km]
    F --> G[Ulož .pidx v21]
    E --> H[SpatialGrid]
    G --> H
    H --> I[findNearest / findNearestDistinctFullTudu]
    I --> J{gpsTuduLocked<br/>nebo gpsVyhybkaLocked?}
    J -- ano --> Z
    J -- ne --> K[applyGpsMatch]
    K --> L[Aktualizuj UI + EpcModel]
```

| Fáze | Třída | Co se děje |
|------|-------|------------|
| Sběr polohy | `LocationCache` | GPS preferován před sítí; stale po 30 s |
| Throttle | `MainActivity` | min. 5 m pohyb, 1 s mezi lookupy |
| Index okolí | `DzsDatabase` | bbox ±0,04° (~4 km); reload po 3 km |
| Cache | `DzsIndexCache` | `.pidx` gzip, verze 21, SHA-256 DB |
| Hledání | `SpatialGrid` | prstence 0,005° buňky, haversine |
| Aplikace | `applyGpsMatch()` | `currentTudu`, `currentVyhybka`, `epc.*` |

**Zámky:** ruční výběr TUDU → `gpsTuduLocked`; ruční výhybka → `gpsVyhybkaLocked`.

---

### 1.4 Tok DZS databáze (otevření → lookup)

```mermaid
flowchart LR
    subgraph VSTUP
        DBFILE["DZS_PASPORT_TPI.sqlite"]
        GPS["aktuální lat/lon"]
    end

    subgraph DzsDatabase
        COPY["kopie → files/dzs/"]
        HASH["SHA-256 obsahu"]
        PROX["proximity index<br/>bbox 4 km"]
        GRID["SpatialGrid"]
    end

    subgraph VYSTUP
        MATCH["GpsMatch<br/>tudu, výhybka, vzdálenost"]
        TUDULIST["List&lt;Tudu&gt; pro picker"]
    end

    DBFILE --> COPY --> HASH
    GPS --> PROX
    HASH --> PROX
    PROX --> GRID --> MATCH
    COPY --> TUDULIST
```

**SQLite tabulky:**

| Tabulka | Obsah |
|---------|-------|
| `DZS_SUPER_RO_TPI` | TUDU, výhybka, POLOHA, RO_ID, OD/DO |
| `DZS_SUPERTRA_GPS_KM` | GPS body, KM_EXT |

**Klíče v paměti:** `pairKey = SUPER_Z_ID|SUPER_D_ID`, `roKey = pairKey|RO_ID`.

---

### 1.5 Tok CSV (zápis a synchronizace)

```mermaid
flowchart TD
  subgraph ZÁPIS
    W1[MainActivity.buildCsvRow] --> W2[CsvRecordBuilder.build]
    W2 --> W3[CsvStore.upsert]
    W3 --> W4[CsvStorage.openOutputStream]
    W4 --> W5["Download/rfid_go_gps_output.csv"]
  end

  subgraph NAČTENÍ
    R1[USB upload z PC] --> R2[reloadIfChanged SHA-256]
    R2 --> R3[parse + castsByVyhybkaRo index]
    R3 --> R4[obnova idRfid, dokončené čipy]
  end
```

| Operace | Metoda | Poznámka |
|---------|--------|----------|
| Sestavení řádku | `CsvRecordBuilder.build(...)` | odděleno od EpcModel |
| Upsert | `CsvStore.upsertAndPersist()` | klíč = `ID_RFID` |
| Detekce změny | `reloadIfChanged()` | SHA-256 celého souboru |
| Index čipů | `castsByVyhybkaRo` | `TUDU\0výhybka\0roId` → Set čipů |
| Android 10+ | `CsvStorage` MediaStore | `IS_PENDING` → publish pro MTP |

---

### 1.6 Spouště – rozhodovací strom

```mermaid
flowchart TD
    T[onKeyDown trigger] --> K{kontrolaActive?}
    K -- ano --> KR[runKontrolaRead – jen čtení]
    K -- ne --> D{scanDoneAwaitingConfirm?}
    D -- ano --> C[onScanDoneContinue]
    D -- ne --> DEL{delete dialog?}
    DEL -- ano --> X[ignorovat]
    DEL -- ne --> W[runTriggerAction → řetězec zápisu]
```

| Kontext | Akce spouště |
|---------|--------------|
| Režim **Kontrola** | `readSingle()` → hledání v CSV |
| Dialog **„Načetli jste“** | potvrzení, posun na další čip |
| **Normální provoz** | EPC → CSV → heslo → lock |

Trigger key codes: `139, 280, 293, 311, 312, 522, 523, 0x3E8`.

---

### 1.7 Speciální režimy (odbočky od hlavního toku)

```mermaid
flowchart LR
    MAIN[Hlavní zápis tagu]

    MAIN --> KONTROLA["Kontrola<br/>read only"]
    MAIN --> HRANICE["Hranice TUDU<br/>čip 5"]
    MAIN --> RUCNE["Ruční výběr<br/>tuduModeGps=false"]
    MAIN --> SABLONA["Šablona EPC ON<br/>7 řádků"]

    KONTROLA --> K1[UhfManager.readSingle]
    KONTROLA --> K2[CsvStore.findAllRowsByTag]

    HRANICE --> H1[epc.cast = 5]
    HRANICE --> H2[bez POLOHA/RO_ID v CSV]

    RUCNE --> R1[showTuduPicker dialog]

    SABLONA --> S1[EpcModel.buildEpc]
```

---

### 1.8 EPC – vizuální layout (24 hex znaků)

```
┌────────┬────────┬────┬────┬─────┬───┬──────────┐
│  ROK   │ TUDU   │T5  │T6  │VÝH. │Č. │ ID_RFID  │
│ 4 zn.  │ 1–4    │2 zn│2 zn│3 zn│1  │ 8 zn.    │
├────────┼────────┼────┼────┼─────┼───┼──────────┤
│ 2026   │ 1501   │ 4A │ 01 │ 010 │ 1 │00030001  │
└────────┴────────┴────┴────┴─────┴───┴──────────┘
         └─ TUDU 1501J1 ─┘      výh.10  čip1
```

Implementace: `epc/EpcModel.java` – čistá Java, testovatelná na JVM.

---

### 1.9 Doménový model Tudu (hierarchie)

```
Tudu "1501J1"
 │
 └── Vyhybka cislo=10, iob="A"
      ├── castMin=1, castMax=3|4
      └── RoBranch[]
           ├── roId, poloha (JA / CA …)
           ├── kmExtChip1  ← čip 1
           └── kmExtOther  ← čipy 2+
```

| POLOHA | Typ výhybky | Čipy |
|--------|-------------|------|
| `J*` | 3částová | 1–3 (jazyk / rovně / odbočka) |
| `C*` | 4částová | 1–4 (CA/CB, CG/CH, …) |

---

## 2. Architektura – struktura balíčků

```
app/src/main/java/com/rfidw/app/
├── ui/MainActivity.java       orchestrátor
├── ui/CsvAdapter.java
├── epc/EpcModel.java
├── data/Tudu.java, DzsDatabase.java, DzsIndexCache.java, VyhybkaGpsStore.java
├── csv/CsvStore.java, CsvStorage.java, CsvRecordBuilder.java
├── rfid/UhfManager.java
├── location/LocationCache.java
└── kmext/KmExtResolver.java
```

### SharedPreferences (`rfidgogps`)

| Klíč | Význam |
|------|--------|
| `idRfid` | další pořadové č. tagu (min. 400) |
| `epcTemplateMode` | šablona EPC ON/OFF |
| `tuduModeGps` | GPS auto vs ruční |
| `gpsTestMode`, `testLat`, `testLon` | simulace GPS |
| `dbSourcePath`, `dbDisplayName`, `dbSourceUri` | poslední DB |

---

## 3. MainActivity – workflow detail

> Tok zápisu a GPS viz **kapitola 1.2–1.3**. Zde jen doplňující detaily.

### 3.1 Kroky UI (indikátor nahoře)

| Krok | Flag | Podmínka |
|------|------|----------|
| UDU | `step1Done` | TUDU + výhybka, nebo hranice TUDU |
| Načtení | `step2Done` | zvolen preset výkonu |
| Hotovo | `step3Done` | úspěšné zamčení |

### 3.2 Post-cyklus

`onTagCycleComplete()` → `idRfid++` → `advanceCastAndVyhybka()` → `firstMissingCast()` (hledá mezery v CSV).

### 3.3 Hranice TUDU (čip 5)

`CAST_TUDU_BOUNDARY = 5`. Dialog `showTuduBoundaryForm()` → `tuduBoundaryMode`, GPS vypnuto, CSV bez POLOHA/RO_ID.

---

## 4. EpcModel – reference

| Segment | Znaky | Kódování |
|---------|-------|----------|
| rok | 4 | literál |
| TUDU 1–4 | 4 | uppercase |
| TUDU 5 | 2 | ASCII hex (`J`→`4A`) |
| TUDU 6 | 2 | číslice `%02d`, jinak ASCII hex |
| výhybka | 3 | `%03d` mod 1000 |
| část | 1 | hex mod 16 |
| ID_RFID | 8 | `%08d` mod 10⁸ |

API: `buildEpc()`, `buildEpcPreview()`, `isValid()`, `decode(epc24)`.

---

## 5. DZS indexace – reference

Detailní popis (část zastaralá): `docs/INDEXACE_DZS.md`. Aktuální model od v3.141: **jen proximity okolí**, ne plná DB.

### SpatialGrid

- Buňka `0,005°` (~550 m), max 40 prstenců
- Early stop při dostatečné vzdálenosti

### Formát `.pidx` v21

```
MAGIC | VERSION | dbSize | SHA-256 | centerLat/Lon
→ roCount × (pairKey, tudu, výhybka, iob, roId, castMin/Max, poloha, kmExt…)
→ gpsCount × (pairKey, tudu, výhybka, lat, lon, roId, poloha)
```

Platnost: hash DB + střed cache ≤ 3 km od GPS.

---

## 6. CSV – reference

Hlavička: `ID_RFID;EPC;TID;TUDU;OBJEKT;POZICE;POLOHA;RO_ID_1;RO_ID_2;KM_EXT;LAT;LON;ACCURACY_M;GPS DATE`

Legacy formáty: auto-detekce z hlavičky (`LEGACY_WITH_ROK`, `LEGACY_NO_POLOHA`, …).

---

## 7. UhfManager – reference

| Banka | ptr | len | Účel |
|-------|-----|-----|------|
| EPC (1) | 2 | 6 wordů | 24 hex EPC |
| RESERVED (0) | 2 | 2 wordy | access heslo |

Preset hesla při selhání: `11223344`, `11112222`, `12345678`. Lock code: `008020`.

Výkon: **v koleji** 16 dBm, **v ruce** 1 dBm.

---

## 8. LocationCache & KmExtResolver

**LocationCache:** GPS 500 ms, síť 2000 ms, stale 30 s, recent 15 s.

**KmExtResolver:** `fromOdDoKmRef(od, do, kmRef)` → `chip1` = KM_REF, `other` = druhý konec OD/DO.

---

## 9. Build a nasazení

| Parametr | Hodnota |
|----------|---------|
| compileSdk / targetSdk | 34 |
| minSdk | 21 |
| Java | 17 |
| ABI | armeabi-v7a, arm64-v8a |

`./gradlew assembleRelease` → `rfid_go_gps_<verze>.apk`. CI: `.github/workflows/android.yml`.

Vývoj v Cursor Cloud: `AGENTS.md` (JDK 17, Android SDK).

---

## 10. Mapa metod MainActivity

| Oblast | Metody |
|--------|--------|
| Lifecycle | `onCreate`, `onResume`, `onPause`, `onDestroy` |
| DB | `loadDatabaseFromPath`, `tryAutoDiscoverDatabase` |
| GPS | `scheduleGpsTuduLookup`, `applyGpsMatch` |
| Zápis | `runTriggerAction`, `doWrite`, `doWritePassword`, `doLock` |
| Callbacks | `onWriteDone`, `onPwdWriteDone`, `onLockDone`, `onTagCycleComplete` |
| CSV | `saveRowToCsv`, `buildCsvRow`, `reloadCsvIfChanged` |
| Kontrola | `showKontrolaOverlay`, `runKontrolaRead` |
| Hranice | `showTuduBoundaryForm`, `finishTuduBoundaryWriteCycle` |
| Trigger | `onKeyDown` |

---

## 11. Známá omezení

| Položka | Stav |
|---------|------|
| Unit testy | chybí (`app/src/test`) |
| Hromadné smazání CSV | jen `removeLast()` |
| Export XLSX | ne |
| `INDEXACE_DZS.md` | popis plné `.idx` zastaralý |

---

## 12. Související dokumenty

| Dokument | Účel |
|----------|------|
| `prirucka-teren.md` | terén |
| `prirucka-uzivatele.md` | uživatel |
| `prirucka-vyvojare.md` | **tento dokument** |
| `INDEXACE_DZS.md` | detail DZS |

**PDF:** `python3 docs/generate_prirucka_vyvojare.py`

---

*Technická příručka – RFID Go GPS 3.153*
