# AUDIT — RFID Go GPS (verze 3.141)

Datum: 2026-07-17 · Základ: `origin/main` @ `4bf6b57` · 102 souborů dle `git ls-files`.
Fáze 0 + 1 (analýza, žádné změny kódu). Ověřovací prostředí: Cursor Cloud VM, JDK 17,
Android SDK 34, Android emulátor API 25 x86 (software, bez KVM) pro dynamickou diagnostiku.

## 1. Strom repozitáře (do 3 úrovní)

```
.
├── .github/workflows/     – CI: build release APK, bump verze, GitHub Release
├── app/                   – jediný Gradle modul (Android aplikace)
│   ├── keystore/          – upload keystore pro podpis APK (jks, tracked)
│   ├── libs/              – binární knihovny: Chainway SDK (.aar), POI/jxl/xUtils (.jar)
│   └── src/main/          – zdrojáky (java/, res/, AndroidManifest.xml)
├── docs/                  – INDEXACE_DZS.md (technická dokumentace indexace)
├── gradle/wrapper/        – Gradle wrapper 8.7
└── sample_data/           – vzorová DZS SQLite DB + legacy CSV/SQL vzory
```

## 2. Skupiny souborů a stav auditu

### A. `data/` — DZS databáze a indexace — **DONE**
`DzsDatabase.java` (2121 ř.), `DzsIndexCache.java` (486 ř.), `Tudu.java` (445 ř.),
`TuduLoader.java` (161 ř.), `VyhybkaGpsStore.java` (148 ř.)

Shrnutí: 3 na sobě naskládané chyby v plné indexaci na pozadí (viz §4 — READONLY otevření,
duplicitní SQL alias `gps gps`, poškození pracovní kopie kvůli stale `-wal`). Mrtvý kód:
`TuduLoader` (celá třída), `VyhybkaGpsStore.merge()`, `DzsIndexCache.hasProximityCache()`,
konstanty `VERSION_LEGACY_V13/V14/V16`, `DzsDatabase.isFullIndexReady()` (public, bez volajícího).
NewApi lint chyby (computeIfAbsent apod., minSdk 21 vs API 24).

### B. `csv/` — výstupní CSV — **DONE**
`CsvStore.java` (595 ř.), `CsvStorage.java` (307 ř.), `CsvRecordBuilder.java` (44 ř.)

Shrnutí: kód čistý, dobře komentovaný. Každý zápis tagu = SHA-256 souboru + přepis celého CSV
(pro terénní stovky řádků zanedbatelné). 6 zpětně kompatibilních formátů hlavičky = kandidát
na budoucí konsolidaci, dnes funkční. NewApi: `List#sort`, `computeIfAbsent`.

### C. `epc/`, `kmext/`, `location/`, `rfid/` — jádro logiky — **DONE**
`EpcModel.java` (183 ř.), `KmExtResolver.java` (58 ř.), `LocationCache.java` (281 ř.),
`UhfManager.java` (363 ř.)

Shrnutí: čisté, malé třídy; `EpcModel`, `KmExtResolver` a parsování `CsvStore` jsou testovatelné
na JVM — testy neexistují. Mrtvý kód: deprecated `UhfManager.PRESET_ACCESS_PASSWORD` (bez čtenáře).
Pozor: `UhfManager.readSingle()` **není** mrtvý (volá ho režim Kontrola, MainActivity:5460) —
README tvrdí opak, je zastaralé.

### D. `ui/` — MainActivity, adaptéry — **DONE** (metodicky: celé čteno strukturálně,
detailně ~70 %; sekce pickerů výhybek a Kontrola overlay jen zběžně — přiznaná neúplnost)
`MainActivity.java` (5653 ř.), `CsvAdapter.java` (79 ř.)

Shrnutí: god-class (64 % Java kódu aplikace). Duplicitní import `android.os.Environment`
(ř. 12–13). Jediný single-thread executor `io` sdílí otevírání DB (blokuje až 20 s čekáním
na GPS) s inicializací čtečky a zápisy tagů. `CsvAdapter` používá `notifyDataSetChanged()`
(lint), pro 5řádkový náhled bez dopadu.

### E. `res/` — layouty, drawable, hodnoty — **DONE**
10 layoutů (activity_main 1170 ř., bottom_sheet_workflow 526 ř.), 26 drawable, 6 color
selectorů, values, mipmapy, `AndroidManifest.xml`

Shrnutí: lint `UnusedResources` hlásí 20 položek; ručně ověřeno `rg` (bez shody mimo definici):
`btn_write.xml`, `hint_bg.xml`, `step_circle_done_glow.xml`, `tudu_boundary_chip_badge.xml`,
`tudu_boundary_step_badge.xml`, barvy `vyhybka_accent_container`, `ok`, `epc_highlight`,
`epc_highlight_label`, 2 dimens, 8 stringů. `ic_launcher_round` je definovaný, ale manifest
nemá `android:roundIcon`. Manifest jinak minimální a v pořádku.

### F. build + CI + libs — **DONE**
`build.gradle`, `app/build.gradle`, `settings.gradle`, `gradle.properties`,
`version.properties`, `keystore.properties`, proguard, wrapper, `.github/workflows/android.yml`,
`app/libs/*` (DeviceAPI 3,1 MB; poi 6,2 MB; poi-ooxml-schemas 5,5 MB; jxl 0,7 MB; xUtils 0,3 MB)

Shrnutí: build funguje (assembleRelease 1 m 15 s v VM, APK 13,60 MB). POI/jxl/xUtils bez
jediného importu = 6,3 MB APK (−46 %, změřeno). `minifyEnabled false`. CI workflow logicky
v pořádku; keystore + hesla v repu jsou vědomé rozhodnutí (README).
Lint: 26 errors (vše NewApi), 163 warnings.

### G. Dokumentace a vzorky — **DONE**
`README.md`, `AGENTS.md`, `docs/INDEXACE_DZS.md`, `sample_data/*`

Shrnutí: README rozsáhlé a převážně aktuální; drobnosti: hlavička uvádí verzi 3.99 (skutečná
3.141), tvrzení o nevyužitém `readSingle()` neplatí. `tudu_vzor.csv/.sql` slouží jen legacy
`TuduLoaderu`. `INDEXACE_DZS.md` popisuje formát v9, kód má verzi 21 — zastaralé číslo.

### §4. Samostatný úkol: plná indexace na pozadí — **DONE** (viz report; reprodukováno na
emulátoru se syntetickou DB 1 M GPS řádků, zachyceny 3 skutečné stack trace)

## 3. Vynechané soubory (a proč)

| Soubor | Důvod |
|--------|-------|
| `app/libs/*.aar`, `*.jar` | binární vendor knihovny — audituje se jen jejich použití |
| `app/keystore/rfid_go_gps_upload.jks` | binární keystore |
| `gradle/wrapper/gradle-wrapper.jar` | standardní Gradle wrapper binárka |
| `app/src/main/res/mipmap-*/**`, `drawable-nodpi/*.png` | generované ikony launcheru |
| `gradlew`, `gradlew.bat` | generované wrapper skripty |
| `sample_data/dzs_vzor.db` | binární vzorová data (schema zkontrolováno přes sqlite3) |

Žádné netracked buildy/vendor složky (`.gitignore` kryje `**/build/`, `.gradle/`, `local.properties`).

## 4. Přiznaná neúplnost

- `MainActivity.java`: detailně přečteno ~70 % (onCreate + DB discovery/load, workflow zápisu,
  GPS lookup, boundary režim, spouště); pickery výhybek (ř. ~1250–1960) a Kontrola overlay
  (ř. ~5400–5650) jen zběžně.
- Výkon na reálném Chainway C5 neměřen (VM nemá hardware); měření pocházejí z x86 emulátoru
  (software) a z rozdílových buildů APK — absolutní časy nejsou přenositelné, nálezy typu
  „blokování vlákna“ a „syntax error“ ano.
- Obsah `DeviceAPI_*.aar` (Chainway SDK) neauditován.
