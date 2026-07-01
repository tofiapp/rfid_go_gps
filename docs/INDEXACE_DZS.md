# Indexace DZS databáze – výhybky a GPS

Tento dokument popisuje, jak aplikace **RFID Go GPS** indexuje tabulky `DZS_SUPER_RO_TPI` (výhybky / TUDU) a `DZS_SUPERTRA_GPS_KM` (GPS body), jak funguje **disková cache** a jak lze index **předpřipravit na PC** před nasazením na zařízení.

Implementace: `DzsDatabase.java`, `DzsIndexCache.java`, `VyhybkaGpsStore.java`.

---

## Přehled

Indexace **neprobíhá v SQLite** (`CREATE INDEX` na obsah se nepoužívá). Aplikace při otevření databáze sestaví **dva paměťové indexy** a uloží je do **cache** (gzip soubor `.idx`).

| Index | Zdrojová tabulka | Klíč | Hodnota |
|-------|------------------|------|---------|
| **RO index** (výhybky) | `DZS_SUPER_RO_TPI` | `SUPER_Z_ID\|SUPER_D_ID` | seznam `{ TUDU, výhybka, IOB, RO_ID, rozsah částí }` |
| **GPS index výhybek** | `DZS_SUPERTRA_GPS_KM` (při indexaci) | každá výhybka | `{ pairKey, TUDU, výhybka, lat, lon }` |

GPS souřadnice výhybky se párují přes **`RO_ID`** – stejný identifikátor v obou tabulkách. Za běhu se celá km tabulka neprochází.

Při běhu aplikace se z indexu výhybek sestaví **prostorová mřížka** (`SpatialGrid`) pro rychlé hledání nejbližší výhybky. Ta se **nepersistuje** – vytvoří se vždy z načtené cache.

---

## Jak to funguje za běhu

1. Najde se **nejbližší předpočítaná souřadnice výhybky** (prostorový index, řádově tisíce bodů).
2. Z výsledku se vezme `TUDU`, číslo výhybky a pár `SUPER_Z_ID` / `SUPER_D_ID`.

---

## Cache po restartu aplikace

Cache je uložena v:

```
{filesDir}/dzs/dzs_index/dzs_{sha256_obsahu_db}.idx
```

**Platnost indexu** = velikost souboru databáze + **SHA-256 celého obsahu** (ne `lastModified`).

Po první úspěšné indexaci:

1. Aplikace spočítá SHA-256 databáze (fáze „Kontrola databáze“).
2. Načte nebo vytvoří `.idx` podle tohoto otisku.
3. Při dalším spuštění stačí ověřit otisk + načíst cache (typicky desítky sekund).

---

## Předindexace na PC

Pro velké databáze (miliony GPS bodů) může první indexace na zařízení trvat minuty. Index lze připravit **na PC** a dodat spolu s databází.

### Sestavení nástroje

```bash
./gradlew :preindex:jar
```

Výstup: `preindex/build/libs/preindex-dzs-1.0.jar`

Na Windows lze použít `tools\preindex-dzs.bat` (po sestavení JAR).

### Spuštění

```bash
java -jar preindex/build/libs/preindex-dzs-1.0.jar DZS_PASPORT_TPI.sqlite --stats --verify
```

Volitelně `-o ./output` pro jiný výstupní adresář.

Nástroj vytvoří:

| Soubor | Popis |
|--------|-------|
| `dzs_<sha256>.idx` | Cache index (gzip, formát v17) |
| `DZS_PASPORT_TPI.sqlite.idx` | Sidecar kopie (stejný obsah) |

### Nasazení na zařízení

Zkopírujte **oba soubory do stejné složky** (typicky Stažené soubory):

```
DZS_PASPORT_TPI.sqlite
DZS_PASPORT_TPI.sqlite.idx
```

Alternativně lze použít přesný název `dzs_<sha256>.idx` (SHA-256 vypíše nástroj po dokončení).

Při prvním otevření databáze aplikace:

1. Spočítá SHA-256 obsahu DB.
2. Hledá `.idx` vedle databáze (sidecar nebo `dzs_<sha256>.idx`).
3. Zkopíruje platný index do interní cache (`files/dzs/dzs_index/`).
4. Načte index během desítek sekund – **bez skenování SQLite tabulek**.

> **Důležité:** Index musí odpovídat **přesnému obsahu** databáze. Jakákoli změna souboru `.sqlite` vyžaduje novou předindexaci.

---

## Postup indexace v aplikaci (krok za krokem)

### Fáze 0 – Otevření databáze

1. Zdrojový soubor se zkopíruje do cache aplikace, pokud není v zapisovatelném adresáři.
2. Nastaví se `PRAGMA cache_size = -64000`, `PRAGMA temp_store = MEMORY`.

### Fáze 1 – Kontrola cache

1. SHA-256 celého souboru databáze.
2. Import předpřipraveného `.idx` ze složky databáze (pokud existuje).
3. Hledání `dzs_{hash}.idx` v `dzs_index/`.
4. Pokud sedí → načtení RO indexu + souřadnic výhybek. Jinak plná indexace.

### Fáze 2A – RO index (výhybky)

Jeden SQL průchod tabulky `DZS_SUPER_RO_TPI`:

- `SUPER_Z_ID`, `SUPER_D_ID`, `TUDU`, `COBJEKT` (číslo výhybky), `RO_ID`
- volitelně `IOB`, `CAST_MIN` / `CAST_MAX`; pokud chybí `CAST_MAX`, odvodí se z prvního písmene `POLOHA` (**J** = 3 části, **C** = 4 části)
- řádky s prázdnou `POLOHA` nebo textem `NULL` se vyřazují
- **více výhybek na stejný pár ID** je povoleno (uloží se jako seznam)

### Fáze 2B – Souřadnice výhybek (RO_ID)

JOIN mezi RO indexem a GPS tabulkou přes `SUPER_Z_ID`, `SUPER_D_ID`, `RO_ID`:

- dočasná tabulka `_dzs_ro_gps_lookup` s páry z RO indexu
- jeden indexovaný dotaz na GPS tabulku
- záložní postup: dotaz `LIMIT 1` pro každou výhybku zvlášť

### Fáze 3 – Uložení cache

Index se zapíše do gzip souboru `.idx` (formát verze 17).

### Fáze 4 – Prostorová mřížka (pouze v paměti)

Buňka ~0,005° (~500 m), hledání rozšiřujícími prstenci nad souřadnicemi výhybek.

---

## Formát souboru `.idx` (verze 17)

Soubor je **gzip** komprimovaný binární stream ve formátu Java `DataOutputStream` (big-endian).

| Pořadí | Typ | Hodnota |
|--------|-----|---------|
| 1 | `int32` | Magic `0x445A5349` (`"DZSI"`) |
| 2 | `int32` | Verze `17` |
| 3 | `int64` | Velikost databáze v bajtech |
| 4 | `utf` | SHA-256 obsahu DB (64 hex znaků) |
| 5 | `int32` | Počet záznamů RO indexu (všechny výhybky) |
| 6… | opakování | `pairKey`, `tudu`, `vyhybka`, `iob`, `roId`, `castMin`, `castMax` |
| N | `int32` | Počet souřadnic výhybek |
| … | opakování | `pairKey`, `tudu`, `vyhybka`, `lat` float, `lon` float |

Název souboru: `dzs_{sha256}.idx`

Starší cache (verze 16 a níže bez `RO_ID`) se ignorují – proběhne plná indexace.

---

## Časté problémy

| Problém | Příčina | Řešení |
|---------|---------|--------|
| Indexace trvá minuty | Velká GPS tabulka, chybí cache | Předindexujte na PC, zkopírujte `.idx` |
| Cache se ignoruje | Jiný obsah DB než při indexaci | Znovu vygenerujte `.idx` pro aktuální soubor |
| Prázdný index výhybek | Žádné shody `RO_ID` mezi tabulkami | Zkontrolujte `SUPER_Z_ID` / `SUPER_D_ID` / `RO_ID` |
| Špatná výhybka | Chybí nebo nesedí `RO_ID` v GPS tabulce | Zkontrolujte párování v obou tabulkách |

---

## Související soubory

| Soubor | Popis |
|--------|-------|
| `app/src/main/java/com/rfidw/app/data/DzsDatabase.java` | Logika indexace a vyhledávání |
| `app/src/main/java/com/rfidw/app/data/DzsIndexCache.java` | Serializace / deserializace `.idx` |
| `app/src/main/java/com/rfidw/app/data/VyhybkaGpsStore.java` | Kompaktní úložiště souřadnic výhybek |
| `preindex/` | Gradle modul pro předindexaci na PC |
| `tools/preindex-dzs.bat` | Spouštěč pro Windows |
| `README.md` | Uživatelská dokumentace aplikace |
