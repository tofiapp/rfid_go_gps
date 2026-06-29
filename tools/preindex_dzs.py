#!/usr/bin/env python3
"""
Předindexace DZS SQLite databáze pro RFID Go GPS.

Sestaví soubor .idx kompatibilní s DzsIndexCache (verze 5) – stejný formát,
jaký aplikace ukládá po první indexaci. Index lze zkopírovat na zařízení do:

  /data/data/com.rfidw.app.gps/cache/dzs_index/

Použití:
  python3 tools/preindex_dzs.py DZS_PASPORT_TPI.sqlite
  python3 tools/preindex_dzs.py DZS_PASPORT_TPI.sqlite -o ./output --stats
"""

from __future__ import annotations

import argparse
import gzip
import os
import sqlite3
import struct
import sys
import time
from typing import Dict, List, Optional, Tuple

# (tudu, vyhybka, super_z_id, super_d_id, kmk_int)
RoVyhybkaRow = Tuple[str, int, str, str, Optional[int]]

MAGIC = 0x445A5349  # "DZSI"
VERSION = 5

TABLE_GPS = "DZS_SUPERTRA_GPS_KM"
TABLE_RO = "DZS_SUPER_RO_TPI"

GPS_LAT_CANDIDATES = ("LAT", "LAN", "LATITUDE", "GPS_LAT", "SIRKA", "GPS_SIRKA", "Y")
GPS_LON_CANDIDATES = ("LON", "LONGITUDE", "LNG", "GPS_LON", "DELKA", "GPS_DELKA", "X")
RO_TUDU_CANDIDATES = ("TUDU", "TUDU_KOD", "TUDU_CODE")
RO_VYHYBKA_CANDIDATES = (
    "COBJEKT", "VYHYBKA", "VYH_CISLO", "CISLO_VYHYBKY", "CIS_VYHYBKY", "VYHYBKA_CISLO",
)


def write_utf(s: str) -> bytes:
    data = s.encode("utf-8")
    if len(data) > 65535:
        raise ValueError(f"Řetězec příliš dlouhý pro Java writeUTF: {len(data)} B")
    return struct.pack(">H", len(data)) + data


def write_int(v: int) -> bytes:
    return struct.pack(">i", v)


def write_long(v: int) -> bytes:
    return struct.pack(">q", v)


def write_double(v: float) -> bytes:
    return struct.pack(">d", v)


def table_columns(conn: sqlite3.Connection, table: str) -> List[str]:
    cur = conn.execute(f"PRAGMA table_info({table})")
    return [row[1] for row in cur.fetchall()]


def find_column(cols: List[str], candidates: Tuple[str, ...], required: bool = True) -> Optional[str]:
    by_upper = {c.upper(): c for c in cols}
    for name in candidates:
        hit = by_upper.get(name.upper())
        if hit:
            return hit
    if required:
        raise ValueError(f"Chybí sloupec (kandidáti: {', '.join(candidates)}) v tabulce")
    return None


def normalize_id(value) -> Optional[str]:
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    try:
        numeric = float(text.replace(",", "."))
        if not (numeric != numeric or abs(numeric) == float("inf")):
            as_long = round(numeric)
            if abs(numeric - as_long) < 1e-6:
                return str(as_long)
    except ValueError:
        pass
    return text


def read_int(value) -> Optional[int]:
    if value is None:
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        return int(round(value))
    text = str(value).strip().replace(",", ".")
    if not text:
        return None
    try:
        return int(round(float(text)))
    except ValueError:
        return None


def read_double(value) -> Optional[float]:
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    text = str(value).strip().replace(",", ".")
    if not text:
        return None
    try:
        return float(text)
    except ValueError:
        return None


def pair_key(super_z_id: str, super_d_id: str) -> str:
    return f"{super_z_id}|{super_d_id}"


def split_pair_key(key: str) -> Tuple[str, str]:
    sep = key.find("|")
    if sep < 0:
        return key, ""
    return key[:sep], key[sep + 1 :]


def resolve_columns(conn: sqlite3.Connection) -> dict:
    gps_cols = table_columns(conn, TABLE_GPS)
    ro_cols = table_columns(conn, TABLE_RO)
    if not gps_cols:
        raise ValueError(f"Tabulka {TABLE_GPS} neexistuje")
    if not ro_cols:
        raise ValueError(f"Tabulka {TABLE_RO} neexistuje")

    vyhybka = find_column(ro_cols, RO_VYHYBKA_CANDIDATES)
    vyhybka_fallback = None
    if vyhybka.upper() != "VYHYBKA":
        vyhybka_fallback = find_column(ro_cols, RO_VYHYBKA_CANDIDATES[1:], required=False)

    return {
        "gps_super_z": find_column(gps_cols, ("SUPER_Z_ID",)),
        "gps_super_d": find_column(gps_cols, ("SUPER_D_ID",)),
        "gps_lat": find_column(gps_cols, GPS_LAT_CANDIDATES),
        "gps_lon": find_column(gps_cols, GPS_LON_CANDIDATES),
        "ro_super_z": find_column(ro_cols, ("SUPER_Z_ID",)),
        "ro_super_d": find_column(ro_cols, ("SUPER_D_ID",)),
        "ro_tudu": find_column(ro_cols, RO_TUDU_CANDIDATES),
        "ro_vyhybka": vyhybka,
        "ro_vyhybka_fallback": vyhybka_fallback,
        "ro_poloha": find_column(ro_cols, ("POLOHA",), required=False),
        "gps_km_int": find_column(gps_cols, ("KM_INT", "KM", "KILOMETR"), required=False),
        "ro_kmk_int": find_column(ro_cols, ("KMK_INT", "KM_INT", "KILOMETR"), required=False),
    }


def vyhybka_expr(cols: dict, alias: Optional[str] = None) -> str:
    prefix = f"{alias}." if alias else ""
    primary = f"NULLIF(TRIM(CAST({prefix}{cols['ro_vyhybka']} AS TEXT)), '')"
    fb = cols["ro_vyhybka_fallback"]
    if not fb:
        return primary
    fallback = f"NULLIF(TRIM(CAST({prefix}{fb} AS TEXT)), '')"
    return f"COALESCE({primary}, {fallback})"


def poloha_filter(cols: dict) -> str:
    poloha = cols["ro_poloha"]
    if not poloha:
        return ""
    expr = f"TRIM(CAST({poloha} AS TEXT))"
    return (
        f" AND {poloha} IS NOT NULL"
        f" AND {expr} <> ''"
        f" AND UPPER({expr}) <> 'NULL'"
    )


def build_ro_index_and_rows(conn: sqlite3.Connection, cols: dict) -> Tuple[Dict[str, Tuple[str, int]], List[RoVyhybkaRow]]:
    vyhybka = vyhybka_expr(cols)
    kmk_col = cols["ro_kmk_int"]
    select_cols = (
        f"{cols['ro_super_z']}, {cols['ro_super_d']}, {cols['ro_tudu']}, {vyhybka}"
        + (f", {kmk_col}" if kmk_col else "")
    )
    sql = f"""
        SELECT {select_cols}
        FROM {TABLE_RO}
        WHERE {cols['ro_tudu']} IS NOT NULL AND {cols['ro_tudu']} <> ''
          AND {vyhybka} IS NOT NULL
        {poloha_filter(cols)}
    """
    ro: Dict[str, Tuple[str, int]] = {}
    rows: List[RoVyhybkaRow] = []
    for row in conn.execute(sql):
        super_z = normalize_id(row[0])
        super_d = normalize_id(row[1])
        tudu = (row[2] or "").strip()
        vyhybka_num = read_int(row[3])
        if not super_z or not super_d or not tudu or vyhybka_num is None:
            continue
        ro[pair_key(super_z, super_d)] = (tudu, vyhybka_num)
        kmk = read_int(row[4]) if kmk_col else None
        rows.append((tudu, vyhybka_num, super_z, super_d, kmk))
    return ro, rows


def build_ro_index(conn: sqlite3.Connection, cols: dict) -> Dict[str, Tuple[str, int]]:
    ro, _ = build_ro_index_and_rows(conn, cols)
    return ro


def km_int_expr(alias: str, column: str) -> str:
    return f"CAST(REPLACE(TRIM(CAST({alias}.{column} AS TEXT)), ',', '.') AS REAL)"


def triple_key(super_z_id: str, super_d_id: str, km: int) -> str:
    return f"{super_z_id}|{super_d_id}|{km}"


def collect_triple_keys(ro_rows: List[RoVyhybkaRow]) -> set:
    keys = set()
    for _, _, super_z, super_d, kmk in ro_rows:
        if kmk is not None:
            keys.add(triple_key(super_z, super_d, kmk))
    return keys


def populate_km_triples_temp(conn: sqlite3.Connection, ro_rows: List[RoVyhybkaRow],
                             only_keys: Optional[set] = None) -> None:
    conn.execute("CREATE TEMP TABLE IF NOT EXISTS _dzs_ro_km_triples ("
                 "super_z_id TEXT NOT NULL, super_d_id TEXT NOT NULL, km_int INTEGER NOT NULL,"
                 "PRIMARY KEY (super_z_id, super_d_id, km_int))")
    conn.execute("DELETE FROM _dzs_ro_km_triples")
    triples = []
    for _, _, super_z, super_d, kmk in ro_rows:
        if kmk is None:
            continue
        key = triple_key(super_z, super_d, kmk)
        if only_keys is not None and key not in only_keys:
            continue
        triples.append((super_z, super_d, kmk))
    if triples:
        conn.executemany(
            "INSERT OR IGNORE INTO _dzs_ro_km_triples (super_z_id, super_d_id, km_int) VALUES (?, ?, ?)",
            triples,
        )


def query_gps_triple_join(conn: sqlite3.Connection, cols: dict,
                          rounded_km: bool) -> Dict[str, Tuple[float, float]]:
    km_col = cols["gps_km_int"]
    lat_expr = f"CAST(REPLACE(g.{cols['gps_lat']}, ',', '.') AS REAL)"
    lon_expr = f"CAST(REPLACE(g.{cols['gps_lon']}, ',', '.') AS REAL)"
    km_expr = km_int_expr("g", km_col)
    if rounded_km:
        km_join = f"({km_expr} >= (rt.km_int - 0.5) AND {km_expr} < (rt.km_int + 0.5))"
    else:
        km_join = f"{km_expr} = rt.km_int"
    sql = f"""
        SELECT g.{cols['gps_super_z']}, g.{cols['gps_super_d']}, rt.km_int, {lat_expr}, {lon_expr}
        FROM {TABLE_GPS} g
        INNER JOIN _dzs_ro_km_triples rt
          ON g.{cols['gps_super_z']} = rt.super_z_id
         AND g.{cols['gps_super_d']} = rt.super_d_id
         AND {km_join}
    """
    out: Dict[str, Tuple[float, float]] = {}
    try:
        for row in conn.execute(sql):
            super_z = normalize_id(row[0])
            super_d = normalize_id(row[1])
            km = read_int(row[2])
            lat = read_double(row[3])
            lon = read_double(row[4])
            if not super_z or not super_d or km is None or lat is None or lon is None:
                continue
            key = triple_key(super_z, super_d, km)
            out.setdefault(key, (lat, lon))
    except sqlite3.Error:
        return {}
    return out


def build_gps_by_triple_key(conn: sqlite3.Connection, cols: dict,
                            ro_rows: List[RoVyhybkaRow]) -> Dict[str, Tuple[float, float]]:
    km_col = cols["gps_km_int"]
    if not km_col or not ro_rows:
        return {}

    populate_km_triples_temp(conn, ro_rows, None)
    out = query_gps_triple_join(conn, cols, rounded_km=False)

    needed = collect_triple_keys(ro_rows)
    if len(out) >= len(needed):
        return out

    missing = needed - set(out.keys())
    if not missing:
        return out

    populate_km_triples_temp(conn, ro_rows, missing)
    rounded = query_gps_triple_join(conn, cols, rounded_km=True)
    for key, coords in rounded.items():
        out.setdefault(key, coords)
    return out


def build_vyhybka_gps_index(conn: sqlite3.Connection, cols: dict,
                            ro_rows: List[RoVyhybkaRow],
                            gps: List[Tuple[str, float, float]]) -> List[Tuple[str, int, float, float]]:
    gps_by_pair = {key: (lat, lon) for key, lat, lon in gps}
    gps_by_triple = build_gps_by_triple_key(conn, cols, ro_rows)

    seen: set = set()
    out: List[Tuple[str, int, float, float]] = []
    for tudu, vyhybka_num, super_z, super_d, kmk in ro_rows:
        lat = None
        lon = None
        if gps_by_triple and kmk is not None:
            coords = gps_by_triple.get(triple_key(super_z, super_d, kmk))
            if coords is not None:
                lat, lon = coords
        if lat is None or lon is None:
            coords = gps_by_pair.get(pair_key(super_z, super_d))
            if coords is None:
                continue
            lat, lon = coords

        dedupe = (tudu, vyhybka_num)
        if dedupe in seen:
            continue
        seen.add(dedupe)
        out.append((tudu, vyhybka_num, lat, lon))
    return out


def build_gps_index(conn: sqlite3.Connection, cols: dict, ro: Dict[str, Tuple[str, int]]) -> List[Tuple[str, float, float]]:
    if not ro:
        return []

    conn.execute("CREATE TEMP TABLE IF NOT EXISTS _dzs_ro_pairs ("
                 "super_z_id TEXT NOT NULL, super_d_id TEXT NOT NULL,"
                 "PRIMARY KEY (super_z_id, super_d_id))")
    conn.execute("DELETE FROM _dzs_ro_pairs")
    conn.executemany(
        "INSERT OR IGNORE INTO _dzs_ro_pairs (super_z_id, super_d_id) VALUES (?, ?)",
        [split_pair_key(k) for k in ro.keys()],
    )

    lat_expr = f"CAST(REPLACE(g.{cols['gps_lat']}, ',', '.') AS REAL)"
    lon_expr = f"CAST(REPLACE(g.{cols['gps_lon']}, ',', '.') AS REAL)"
    sql_cast = f"""
        SELECT g.{cols['gps_super_z']}, g.{cols['gps_super_d']}, {lat_expr}, {lon_expr}
        FROM {TABLE_GPS} g
        INNER JOIN (
          SELECT g2.{cols['gps_super_z']}, g2.{cols['gps_super_d']}, MIN(g2.rowid) AS rid
          FROM {TABLE_GPS} g2
          INNER JOIN _dzs_ro_pairs rp
            ON g2.{cols['gps_super_z']} = rp.super_z_id
           AND g2.{cols['gps_super_d']} = rp.super_d_id
          GROUP BY g2.{cols['gps_super_z']}, g2.{cols['gps_super_d']}
        ) agg ON g.rowid = agg.rid
    """

    gps: List[Tuple[str, float, float]] = []
    try:
        rows = conn.execute(sql_cast).fetchall()
    except sqlite3.Error:
        rows = []

    if not rows:
        sql_plain = f"""
            SELECT g.{cols['gps_super_z']}, g.{cols['gps_super_d']},
                   g.{cols['gps_lat']}, g.{cols['gps_lon']}
            FROM {TABLE_GPS} g
            INNER JOIN (
              SELECT g2.{cols['gps_super_z']}, g2.{cols['gps_super_d']}, MIN(g2.rowid) AS rid
              FROM {TABLE_GPS} g2
              INNER JOIN _dzs_ro_pairs rp
                ON g2.{cols['gps_super_z']} = rp.super_z_id
               AND g2.{cols['gps_super_d']} = rp.super_d_id
              GROUP BY g2.{cols['gps_super_z']}, g2.{cols['gps_super_d']}
            ) agg ON g.rowid = agg.rid
        """
        try:
            rows = conn.execute(sql_plain).fetchall()
        except sqlite3.Error:
            rows = []

    for row in rows:
        super_z = normalize_id(row[0])
        super_d = normalize_id(row[1])
        lat = read_double(row[2])
        lon = read_double(row[3])
        if not super_z or not super_d or lat is None or lon is None:
            continue
        gps.append((pair_key(super_z, super_d), lat, lon))

    if not gps:
        sql_one = (
            f"SELECT {cols['gps_lat']}, {cols['gps_lon']} FROM {TABLE_GPS}"
            f" WHERE {cols['gps_super_z']} = ? AND {cols['gps_super_d']} = ? LIMIT 1"
        )
        for key in ro:
            z, d = split_pair_key(key)
            try:
                row = conn.execute(sql_one, (z, d)).fetchone()
            except sqlite3.Error:
                continue
            if not row:
                continue
            lat = read_double(row[0])
            lon = read_double(row[1])
            if lat is not None and lon is not None:
                gps.append((key, lat, lon))

    return gps


def cache_filename(db_path: str) -> str:
    size = os.path.getsize(db_path)
    mtime_ms = int(os.path.getmtime(db_path) * 1000)
    return f"dzs_{size:x}_{mtime_ms:x}.idx"


def serialize_index(db_path: str, ro: Dict[str, Tuple[str, int]],
                    gps: List[Tuple[str, float, float]],
                    vyhybka_gps: List[Tuple[str, int, float, float]]) -> bytes:
    size = os.path.getsize(db_path)
    mtime_ms = int(os.path.getmtime(db_path) * 1000)
    parts = [write_int(MAGIC), write_int(VERSION), write_long(size), write_long(mtime_ms)]
    parts.append(write_int(len(ro)))
    for key, (tudu, vyhybka) in ro.items():
        parts.append(write_utf(key))
        parts.append(write_utf(tudu))
        parts.append(write_int(vyhybka))
    parts.append(write_int(len(gps)))
    for key, lat, lon in gps:
        parts.append(write_utf(key))
        parts.append(write_double(lat))
        parts.append(write_double(lon))
    parts.append(write_int(len(vyhybka_gps)))
    for tudu, vyhybka, lat, lon in vyhybka_gps:
        parts.append(write_utf(tudu))
        parts.append(write_int(vyhybka))
        parts.append(write_double(lat))
        parts.append(write_double(lon))
    return b"".join(parts)


def verify_index(db_path: str, idx_path: str) -> bool:
    size = os.path.getsize(db_path)
    mtime_ms = int(os.path.getmtime(db_path) * 1000)
    with gzip.open(idx_path, "rb") as f:
        def read_int() -> int:
            return struct.unpack(">i", f.read(4))[0]

        def read_long() -> int:
            return struct.unpack(">q", f.read(8))[0]

        def read_utf() -> str:
            length = struct.unpack(">H", f.read(2))[0]
            return f.read(length).decode("utf-8")

        if read_int() != MAGIC or read_int() != VERSION:
            print("CHYBA: magic/verze", file=sys.stderr)
            return False
        if read_long() != size or read_long() != mtime_ms:
            print("CHYBA: otisk DB nesedí", file=sys.stderr)
            return False
        ro_count = read_int()
        for _ in range(ro_count):
            read_utf()
            read_utf()
            read_int()
        gps_count = read_int()
        for _ in range(gps_count):
            read_utf()
            struct.unpack(">d", f.read(8))
            struct.unpack(">d", f.read(8))
        vyhybka_gps_count = read_int()
        for _ in range(vyhybka_gps_count):
            read_utf()
            read_int()
            struct.unpack(">d", f.read(8))
            struct.unpack(">d", f.read(8))
        extra = f.read(1)
        if extra:
            print("CHYBA: přebytečná data na konci", file=sys.stderr)
            return False
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description="Předindexace DZS databáze pro RFID Go GPS")
    parser.add_argument("database", help="Cesta k DZS_PASPORT_TPI.sqlite")
    parser.add_argument("-o", "--output", help="Výstupní adresář (výchozí: složka databáze)")
    parser.add_argument("--stats", action="store_true", help="Vypiš statistiky")
    parser.add_argument("--verify", action="store_true", help="Ověř vygenerovaný soubor")
    args = parser.parse_args()

    db_path = os.path.abspath(args.database)
    if not os.path.isfile(db_path):
        print(f"Soubor nenalezen: {db_path}", file=sys.stderr)
        return 1

    out_dir = os.path.abspath(args.output) if args.output else os.path.dirname(db_path)
    os.makedirs(out_dir, exist_ok=True)
    idx_name = cache_filename(db_path)
    idx_path = os.path.join(out_dir, idx_name)

    t0 = time.perf_counter()
    conn = sqlite3.connect(db_path)
    try:
        cols = resolve_columns(conn)
        if args.stats:
            print("Sloupce:", cols)
        ro, ro_rows = build_ro_index_and_rows(conn, cols)
        gps = build_gps_index(conn, cols, ro)
        vyhybka_gps = build_vyhybka_gps_index(conn, cols, ro_rows, gps)
    finally:
        conn.close()

    body = serialize_index(db_path, ro, gps, vyhybka_gps)
    with gzip.open(idx_path, "wb") as f:
        f.write(body)

    elapsed = time.perf_counter() - t0
    print(f"Hotovo: {idx_path}")
    print(f"  RO záznamů: {len(ro)}")
    print(f"  GPS bodů:   {len(gps)}")
    print(f"  Výhybky GPS: {len(vyhybka_gps)}")
    print(f"  Čas:        {elapsed:.1f} s")
    print(f"  Otisk:      velikost={os.path.getsize(db_path)}, mtime_ms={int(os.path.getmtime(db_path) * 1000)}")

    if args.verify:
        ok = verify_index(db_path, idx_path)
        print("Ověření:", "OK" if ok else "SELHALO")
        return 0 if ok else 2

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
