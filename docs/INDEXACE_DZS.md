# Indexace DZS databáze – výhybky a GPS

Tento dokument popisuje, jak aplikace **RFID Go GPS** indexuje tabulky `DZS_SUPER_RO_TPI` (výhybky / TUDU) a `DZS_SUPERTRA_GPS_KM` (GPS body), a jak funguje **disková cache** pro rychlý start po restartu aplikace.

Implementace: `DzsDatabase.java`, `DzsIndexCache.java`.

---

## Přehled

Indexace **neprobíhá v SQLite** (`CREATE INDEX` na obsah se nepoužívá). Aplikace při otevření databáze sestaví **tři paměťové indexy** a uloží je do **cache** (gzip soubor `.idx`).

| Index | Zdrojová tabulka | Klíč | Hodnota |
|-------|------------------|------|---------|
| **RO index** (výhybky) | `DZS_SUPER_RO_TPI` | `SUPER_Z_ID\|SUPER_D_ID` | TUDU kód + číslo výhybky |
| **GPS index** | `DZS_SUPERTRA_GPS_KM` | `SUPER_Z_ID\|SUPER_D_ID` | zeměpisná šířka + délka |
| **Výhybka GPS index** | RO + GPS (při indexaci) | `TUDU` → číslo výhybky | souřadnice výhybky |

Při běhu aplikace se z GPS indexu ještě sestaví **prostorová mřížka** (`SpatialGrid`) pro rychlé hledání nejbližšího bodu. Ta se **nepersistuje** – vytvoří se vždy z GPS indexu (~1 s).

---

## Cache po restartu aplikace

Cache je uložena v:

```
{cacheDir}/dzs_index/dzs_{sha256_obsahu_db}.idx
```

**Platnost indexu** = velikost souboru databáze + **SHA-256 celého obsahu** (ne `lastModified`).

Důvod: databáze se často kopíruje ze Stažených souborů do cache aplikace. Při každém kopírování se mění čas změny souboru, takže starý formát cache (verze 5, size + mtime) se po restartu neaplikoval a indexace běžela znovu (~10 minut).

Po první úspěšné indexaci:

1. Aplikace spočítá SHA-256 databáze (fáze „Kontrola databáze“).
2. Načte nebo vytvoří `.idx` podle tohoto otisku.
3. Při dalším spuštění stačí ověřit otisk + načíst cache (typicky desítky sekund).

Kopie databáze se znovu nestahuje, pokud soubor v cache aplikace má **stejnou velikost** jako zdroj.

---

## Postup indexace (krok za krokem)

### Fáze 0 – Otevření databáze

1. Zdrojový soubor se zkopíruje do cache aplikace, pokud není v zapisovatelném adresáři (jen při změně velikosti).
2. Nastaví se `PRAGMA cache_size = -64000`, `PRAGMA temp_store = MEMORY`.

### Fáze 1 – Kontrola cache

1. SHA-256 celého souboru databáze.
2. Hledání `dzs_{hash}.idx` v `dzs_index/`.
3. Pokud sedí → načtení RO + GPS + výhybka GPS indexu. Jinak plná indexace.

### Fáze 2A – RO index (výhybky)

Jeden SQL průchod tabulky `DZS_SUPER_RO_TPI` – mapa `SUPER_Z_ID|SUPER_D_ID → { tudu, vyhybka }`.

### Fáze 2B – GPS index

GPS body jen pro páry ID z RO indexu (dočasná tabulka `_dzs_ro_pairs` + deduplikace přes `MIN(rowid)`).

### Fáze 2C – Výhybka GPS index

1. Jeden SQL průchod GPS tabulky pro relevantní páry ID (km body seřazené podle km).
2. Párování `KMK_INT` / `KM_INT` **v paměti** (místo dotazu na každou trojici zvlášť).
3. Mapa `TUDU → výhybka → souřadnice`.

### Fáze 3 – Uložení cache

Index se zapíše do gzip souboru `.idx` (formát verze 6).

### Fáze 4 – Prostorová mřížka (pouze v paměti)

Buňka ~0,005° (~500 m), hledání rozšiřujícími prstenci.

---

## Formát souboru `.idx` (verze 6)

Soubor je **gzip** komprimovaný binární stream ve formátu Java `DataOutputStream` (big-endian).

| Pořadí | Typ | Hodnota |
|--------|-----|---------|
| 1 | `int32` | Magic `0x445A5349` (`"DZSI"`) |
| 2 | `int32` | Verze `6` |
| 3 | `int64` | Velikost databáze v bajtech |
| 4 | `utf` | SHA-256 obsahu DB (64 hex znaků) |
| 5 | `int32` | Počet záznamů RO indexu |
| 6… | opakování | `pairKey`, `tudu`, `vyhybka` |
| N | `int32` | Počet GPS záznamů |
| … | opakování | `pairKey`, `latitude`, `longitude` |
| M | `int32` | Počet záznamů výhybka GPS indexu |
| … | opakování | `tudu`, `vyhybka`, `latitude`, `longitude` |

Název souboru: `dzs_{sha256}.idx`

Starší cache (verze 5 a níže) se ignorují – při prvním spuštění po aktualizaci proběhne jednorázová plná indexace.

---

## Časté problémy

| Problém | Příčina | Řešení |
|---------|---------|--------|
| Indexace trvá minuty | Velká databáze, první otevření | Po dokončení se uloží cache; další start je rychlejší |
| Cache se po restartu nenačte | Stará verze cache (mtime) nebo jiný obsah DB | Aktualizujte aplikaci; cache v6 používá hash obsahu |
| Prázdný GPS index | Žádné shody ID mezi tabulkami | Zkontrolujte `SUPER_Z_ID` / `SUPER_D_ID` |
| OOM při indexaci | Příliš velká DB pro RAM zařízení | Menší DB nebo zařízení s více RAM |

---

## Související soubory

| Soubor | Popis |
|--------|-------|
| `app/src/main/java/com/rfidw/app/data/DzsDatabase.java` | Logika indexace a vyhledávání |
| `app/src/main/java/com/rfidw/app/data/DzsIndexCache.java` | Serializace / deserializace `.idx` |
| `README.md` | Uživatelská dokumentace aplikace |
