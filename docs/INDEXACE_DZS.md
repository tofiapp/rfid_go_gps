# Indexace DZS databáze – výhybky a GPS

Tento dokument popisuje, jak aplikace **RFID Go GPS** indexuje tabulky `DZS_SUPER_RO_TPI` (výhybky / TUDU) a `DZS_SUPERTRA_GPS_KM` (GPS body), a jak funguje **disková cache** pro rychlý start po restartu aplikace.

Implementace: `DzsDatabase.java`, `DzsIndexCache.java`, `VyhybkaGpsStore.java`.

---

## Přehled

Indexace **neprobíhá v SQLite** (`CREATE INDEX` na obsah se nepoužívá). Aplikace při otevření databáze sestaví **dva paměťové indexy** a uloží je do **cache** (gzip soubor `.idx`).

| Index | Zdrojová tabulka | Klíč | Hodnota |
|-------|------------------|------|---------|
| **RO index** (výhybky) | `DZS_SUPER_RO_TPI` | `SUPER_Z_ID\|SUPER_D_ID` | seznam `{ TUDU, výhybka, střed OD/DO }` |
| **GPS index výhybek** | `DZS_SUPERTRA_GPS_KM` (při indexaci) | každá výhybka | `{ pairKey, TUDU, výhybka, lat, lon }` |

GPS tabulka se při indexaci projde **jednou**, ale do cache se ukládá jen **jedna souřadnice na výhybku** – km bod, jehož `KM_EXT` nejlépe sedí na `(OD+DO)/2` v rámci páru ID.

Při běhu aplikace se z indexu výhybek sestaví **prostorová mřížka** (`SpatialGrid`) pro rychlé hledání nejbližší výhybky. Ta se **nepersistuje** – vytvoří se vždy z načtené cache.

---

## Jak to funguje za běhu

1. Najde se **nejbližší předpočítaná souřadnice výhybky** (malý prostorový index, řádově tisíce bodů).
2. Z výsledku se vezme `TUDU`, číslo výhybky a pár `SUPER_Z_ID` / `SUPER_D_ID`.

Párování `KM_EXT` ↔ `(OD+DO)/2` proběhlo už při indexaci – za běhu se celá km tabulka neprochází.

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
3. Pokud sedí → načtení RO indexu + souřadnic výhybek. Jinak plná indexace.

### Fáze 2A – RO index (výhybky)

Jeden SQL průchod tabulky `DZS_SUPER_RO_TPI`:

- `SUPER_Z_ID`, `SUPER_D_ID`, `TUDU`, `COBJEKT` (číslo výhybky)
- střed `(OD + DO) / 2` jako kilometrický rozlišovač
- **více výhybek na stejný pár ID** je povoleno (uloží se jako seznam)

### Fáze 2B – Souřadnice výhybek (KM_EXT)

Jeden SQL průchod GPS km bodů pro páry z RO indexu (dočasná tabulka `_dzs_ro_pairs`):

- **bez ORDER BY** – sekvenční čtení; SQLite nemusí řadit miliony řádků před odesláním kurzoru
- data se načtou do dočasného kompaktního bufferu, párování KM_EXT ↔ (OD+DO)/2 proběhne v paměti
- do výsledku jde **jen souřadnice výhybek**, ne celá km tabulka
- před načtením se spočítá `COUNT(*)` pro reálný průběh 50–80 % (načítání), 82–84 % (párování)
- záložní postup: sekvenční průchod GPS tabulkou s filtrem párů v paměti

### Fáze 3 – Uložení cache

Index se zapíše do gzip souboru `.idx` (formát verze 9).

### Fáze 4 – Prostorová mřížka (pouze v paměti)

Buňka ~0,005° (~500 m), hledání rozšiřujícími prstenci nad souřadnicemi výhybek.

---

## Formát souboru `.idx` (verze 9)

Soubor je **gzip** komprimovaný binární stream ve formátu Java `DataOutputStream` (big-endian).

| Pořadí | Typ | Hodnota |
|--------|-----|---------|
| 1 | `int32` | Magic `0x445A5349` (`"DZSI"`) |
| 2 | `int32` | Verze `9` |
| 3 | `int64` | Velikost databáze v bajtech |
| 4 | `utf` | SHA-256 obsahu DB (64 hex znaků) |
| 5 | `int32` | Počet záznamů RO indexu (všechny výhybky) |
| 6… | opakování | `pairKey`, `tudu`, `vyhybka`, `midKm` |
| N | `int32` | Počet souřadnic výhybek |
| … | opakování | `pairKey`, `tudu`, `vyhybka`, `lat` float, `lon` float |

Název souboru: `dzs_{sha256}.idx`

Starší cache (verze 8 a níže) se ignorují – při prvním spuštění po aktualizaci proběhne jednorázová plná indexace.

---

## Časté problémy

| Problém | Příčina | Řešení |
|---------|---------|--------|
| Indexace trvá minuty | Velká GPS tabulka, první otevření | Po dokončení se uloží cache; další start je rychlejší |
| Cache se po restartu nenačte | Stará verze cache nebo jiný obsah DB | Aktualizujte aplikaci; cache v9 používá hash obsahu |
| Prázdný index výhybek | Žádné shody ID mezi tabulkami | Zkontrolujte `SUPER_Z_ID` / `SUPER_D_ID` |
| Špatná výhybka při více na stejném páru | Chybí nebo nesedí `OD`/`DO` nebo `KM_EXT` | Zkontrolujte sloupce a hodnoty kilometrického středu |

---

## Související soubory

| Soubor | Popis |
|--------|-------|
| `app/src/main/java/com/rfidw/app/data/DzsDatabase.java` | Logika indexace a vyhledávání |
| `app/src/main/java/com/rfidw/app/data/DzsIndexCache.java` | Serializace / deserializace `.idx` |
| `app/src/main/java/com/rfidw/app/data/VyhybkaGpsStore.java` | Kompaktní úložiště souřadnic výhybek |
| `README.md` | Uživatelská dokumentace aplikace |
