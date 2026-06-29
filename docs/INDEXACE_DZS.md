# Indexace DZS databáze – výhybky a GPS

Tento dokument popisuje, jak aplikace **RFID Go GPS** indexuje tabulky `DZS_SUPER_RO_TPI` (výhybky / TUDU) a `DZS_SUPERTRA_GPS_KM` (GPS body), a jak funguje **disková cache** pro rychlý start po restartu aplikace.

Implementace: `DzsDatabase.java`, `DzsIndexCache.java`.

---

## Přehled

Indexace **neprobíhá v SQLite** (`CREATE INDEX` na obsah se nepoužívá). Aplikace při otevření databáze sestaví **dva paměťové indexy** a uloží je do **cache** (gzip soubor `.idx`).

| Index | Zdrojová tabulka | Klíč | Hodnota |
|-------|------------------|------|---------|
| **RO index** (výhybky) | `DZS_SUPER_RO_TPI` | `SUPER_Z_ID\|SUPER_D_ID` | seznam `{ TUDU, výhybka, střed OD/DO }` |
| **GPS index** | `DZS_SUPERTRA_GPS_KM` | každý km bod | `{ SUPER_Z_ID\|SUPER_D_ID, KM_EXT, lat, lon }` |

Při běhu aplikace se z GPS indexu sestaví **prostorová mřížka** (`SpatialGrid`) pro rychlé hledání nejbližšího km bodu. Ta se **nepersistuje** – vytvoří se vždy z GPS indexu.

**Žádný samostatný index souřadnic výhybek** – výhybka se určí až za běhu podle nejbližšího GPS km bodu a shody `KM_EXT` ↔ `(OD+DO)/2`.

---

## Jak to funguje za běhu

1. Najde se **nejbližší GPS km bod** v `DZS_SUPERTRA_GPS_KM` (všechny body, ne jeden na pár ID).
2. Z bodu se vezme `SUPER_Z_ID`, `SUPER_D_ID` a `KM_EXT`.
3. V RO indexu se najdou výhybky pro daný pár ID.
4. Pokud je výhybek více, vybere se ta, jejíž střed `(OD+DO)/2` nejlépe odpovídá `KM_EXT`.

---

## Cache po restartu aplikace

Cache je uložena v:

```
{cacheDir}/dzs_index/dzs_{sha256_obsahu_db}.idx
```

**Platnost indexu** = velikost souboru databáze + **SHA-256 celého obsahu** (ne `lastModified`).

Po první úspěšné indexaci:

1. Aplikace spočítá SHA-256 databáze (fáze „Kontrola databáze“).
2. Načte nebo vytvoří `.idx` podle tohoto otisku.
3. Při dalším spuštění stačí ověřit otisk + načíst cache (typicky desítky sekund).

---

## Postup indexace (krok za krokem)

### Fáze 0 – Otevření databáze

1. Zdrojový soubor se zkopíruje do cache aplikace, pokud není v zapisovatelném adresáři (jen při změně velikosti).
2. Nastaví se `PRAGMA cache_size = -64000`, `PRAGMA temp_store = MEMORY`.

### Fáze 1 – Kontrola cache

1. SHA-256 celého souboru databáze.
2. Hledání `dzs_{hash}.idx` v `dzs_index/`.
3. Pokud sedí → načtení RO + GPS indexu. Jinak plná indexace.

### Fáze 2A – RO index (výhybky)

Jeden SQL průchod tabulky `DZS_SUPER_RO_TPI`:

- `SUPER_Z_ID`, `SUPER_D_ID`, `TUDU`, `COBJEKT` (číslo výhybky)
- střed `(OD + DO) / 2` jako kilometrický rozlišovač
- **více výhybek na stejný pár ID** je povoleno (uloží se jako seznam)

### Fáze 2B – GPS index

Všechny GPS km body pro páry ID z RO indexu (dočasná tabulka `_dzs_ro_pairs`):

- `SUPER_Z_ID`, `SUPER_D_ID`, `KM_EXT`, souřadnice
- **žádná deduplikace** – každý km bod je samostatný záznam

### Fáze 3 – Uložení cache

Index se zapíše do gzip souboru `.idx` (formát verze 7).

### Fáze 4 – Prostorová mřížka (pouze v paměti)

Buňka ~0,005° (~500 m), hledání rozšiřujícími prstenci.

---

## Formát souboru `.idx` (verze 7)

Soubor je **gzip** komprimovaný binární stream ve formátu Java `DataOutputStream` (big-endian).

| Pořadí | Typ | Hodnota |
|--------|-----|---------|
| 1 | `int32` | Magic `0x445A5349` (`"DZSI"`) |
| 2 | `int32` | Verze `7` |
| 3 | `int64` | Velikost databáze v bajtech |
| 4 | `utf` | SHA-256 obsahu DB (64 hex znaků) |
| 5 | `int32` | Počet záznamů RO indexu (všechny výhybky) |
| 6… | opakování | `pairKey`, `tudu`, `vyhybka`, `midKm` |
| N | `int32` | Počet GPS záznamů |
| … | opakování | `pairKey`, `kmExt`, `latitude`, `longitude` |

Název souboru: `dzs_{sha256}.idx`

Starší cache (verze 6 a níže) se ignorují – při prvním spuštění po aktualizaci proběhne jednorázová plná indexace.

---

## Časté problémy

| Problém | Příčina | Řešení |
|---------|---------|--------|
| Indexace trvá minuty | Velká databáze, první otevření | Po dokončení se uloží cache; další start je rychlejší |
| Cache se po restartu nenačte | Stará verze cache nebo jiný obsah DB | Aktualizujte aplikaci; cache v7 používá hash obsahu |
| Prázdný GPS index | Žádné shody ID mezi tabulkami | Zkontrolujte `SUPER_Z_ID` / `SUPER_D_ID` |
| Špatná výhybka při více na stejném páru | Chybí nebo nesedí `OD`/`DO` nebo `KM_EXT` | Zkontrolujte sloupce a hodnoty kilometrického středu |
| OOM při indexaci | Příliš velká DB pro RAM zařízení | Menší DB nebo zařízení s více RAM |

---

## Související soubory

| Soubor | Popis |
|--------|-------|
| `app/src/main/java/com/rfidw/app/data/DzsDatabase.java` | Logika indexace a vyhledávání |
| `app/src/main/java/com/rfidw/app/data/DzsIndexCache.java` | Serializace / deserializace `.idx` |
| `README.md` | Uživatelská dokumentace aplikace |
