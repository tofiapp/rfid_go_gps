package com.rfidw.app.csv;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Výstupní tabulka .CSV.
 *
 * Sloupce:
 *   ID_RFID ; EPC ; TID ; rok ; TUDU ; vyhybka ; cip ; POLOHA ;
 *   poloha_latitude ; poloha_longitude ;
 *   latitude ; longitude ; accuracy_m ; gps_time
 *
 * Klíčem je ID_RFID – při zápisu stejného ID_RFID se daný řádek přepíše.
 */
public class CsvStore {

    public static final String[] HEADER = {
            "ID_RFID", "EPC", "TID", "rok", "TUDU", "vyhybka", "cip", "POLOHA",
            "poloha_latitude", "poloha_longitude",
            "latitude", "longitude", "accuracy_m", "gps_time"
    };
    private static final String SEP = ";";

    public static class Row {
        public String idRfid;
        public String epc;
        public String tid;
        public String rok;
        public String tudu;
        public String vyhybka;
        public String cast;
        public String poloha;
        public String polohaLatitude;
        public String polohaLongitude;
        public String latitude;
        public String longitude;
        public String accuracyM;
        public String gpsTime;

        public String[] toArray() {
            return new String[]{
                    idRfid, epc, tid, rok, tudu, vyhybka, cast, poloha,
                    polohaLatitude, polohaLongitude,
                    latitude, longitude, accuracyM, gpsTime
            };
        }
    }

    private final File file;
    // zachovává pořadí vložení, klíč = ID_RFID
    private final Map<String, Row> rows = new LinkedHashMap<>();
    // rychlý index: TUDU|výhybka → množina zapsaných částí
    private final Map<String, Set<Integer>> castsByVyhybka = new HashMap<>();

    public CsvStore(File file) {
        this.file = file;
        load();
    }

    public File getFile() { return file; }

    public List<Row> getRows() {
        return new ArrayList<>(rows.values());
    }

    public int size() { return rows.size(); }

    /** Vrátí poslední vložený řádek nebo null, pokud je tabulka prázdná. */
    public Row getLastRow() {
        if (rows.isEmpty()) return null;
        List<Row> list = getRows();
        return list.get(list.size() - 1);
    }

    /** Vrátí nejvyšší hodnotu ID_RFID v tabulce, nebo 0 pokud je tabulka prázdná. */
    public synchronized long getMaxIdRfid() {
        long max = 0;
        for (Row r : rows.values()) {
            long id = parseLong(r.idRfid, 0);
            if (id > max) max = id;
        }
        return max;
    }

    /** Vloží nebo přepíše řádek podle ID_RFID (jen v paměti). */
    public synchronized void upsert(Row row) {
        Row previous = rows.get(row.idRfid);
        if (previous != null) removeFromCastIndex(previous);
        rows.put(row.idRfid, row);
        addToCastIndex(row);
    }

    public synchronized void clear() {
        rows.clear();
        castsByVyhybka.clear();
    }

    /** Vrátí posledních {@code max} vložených řádků (chronologicky od nejstaršího). */
    public List<Row> getLastRows(int max) {
        List<Row> all = getRows();
        if (max <= 0 || all.isEmpty()) return new ArrayList<>();
        int from = Math.max(0, all.size() - max);
        return new ArrayList<>(all.subList(from, all.size()));
    }

    /** Odstraní poslední vložený řádek (jen v paměti). Vrátí smazaný řádek nebo null. */
    public synchronized Row removeLast() {
        if (rows.isEmpty()) return null;
        List<Row> list = getRows();
        Row last = list.get(list.size() - 1);
        rows.remove(last.idRfid);
        removeFromCastIndex(last);
        return last;
    }

    /** Vrátí množinu částí výhybky, které jsou v CSV pro dané TUDU. */
    public synchronized Set<Integer> getWrittenCasts(String tuduCode, int vyhybkaCislo) {
        Set<Integer> casts = castsByVyhybka.get(vyhybkaKey(tuduCode, vyhybkaCislo));
        if (casts == null || casts.isEmpty()) return Collections.emptySet();
        return new HashSet<>(casts);
    }

    /** Uloží aktuální stav na disk. Volat mimo UI vlákno. */
    public synchronized void persist() {
        save();
    }

    // ----------------------------------------------------------- IO

    private void load() {
        rows.clear();
        castsByVyhybka.clear();
        if (file == null || !file.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            boolean first = true;
            Map<String, Integer> colIndex = null;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] c = line.split(SEP, -1);
                if (first) {
                    first = false;
                    if (c.length > 0 && c[0].trim().equalsIgnoreCase("ID_RFID")) {
                        colIndex = parseHeader(c);
                        continue;
                    }
                }
                Row r = new Row();
                if (colIndex != null) {
                    r.idRfid = get(c, colIndex, "ID_RFID", 0);
                    r.epc = get(c, colIndex, "EPC", 1);
                    r.tid = get(c, colIndex, "TID", 2);
                    r.rok = get(c, colIndex, "rok", 3);
                    r.tudu = get(c, colIndex, "TUDU", 4);
                    r.vyhybka = get(c, colIndex, "vyhybka", 5);
                    r.cast = get(c, colIndex, "cip", 6);
                    r.poloha = get(c, colIndex, "POLOHA", -1);
                    r.polohaLatitude = get(c, colIndex, "poloha_latitude", -1);
                    r.polohaLongitude = get(c, colIndex, "poloha_longitude", -1);
                    r.latitude = get(c, colIndex, "latitude", -1);
                    r.longitude = get(c, colIndex, "longitude", -1);
                    r.accuracyM = get(c, colIndex, "accuracy_m", -1);
                    r.gpsTime = get(c, colIndex, "gps_time", -1);
                } else {
                    r.idRfid = get(c, 0);
                    r.epc = get(c, 1);
                    r.tid = get(c, 2);
                    r.rok = get(c, 3);
                    r.tudu = get(c, 4);
                    r.vyhybka = get(c, 5);
                    r.cast = get(c, 6);
                    r.poloha = c.length > 11 ? get(c, 7) : "";
                    r.polohaLatitude = c.length > 13 ? get(c, 8) : "";
                    r.polohaLongitude = c.length > 13 ? get(c, 9) : "";
                    if (c.length > 13) {
                        r.latitude = get(c, 10);
                        r.longitude = get(c, 11);
                        r.accuracyM = get(c, 12);
                        r.gpsTime = get(c, 13);
                    } else if (c.length > 11) {
                        r.latitude = get(c, 8);
                        r.longitude = get(c, 9);
                        r.accuracyM = get(c, 10);
                        r.gpsTime = get(c, 11);
                    } else {
                        r.latitude = get(c, 7);
                        r.longitude = get(c, 8);
                        r.accuracyM = get(c, 9);
                        r.gpsTime = get(c, 10);
                    }
                }
                if (r.idRfid != null && !r.idRfid.isEmpty()) {
                    rows.put(r.idRfid, r);
                    addToCastIndex(r);
                }
            }
        } catch (Exception e) {
            rows.clear();
            castsByVyhybka.clear();
        }
    }

    private static Map<String, Integer> parseHeader(String[] cols) {
        Map<String, Integer> out = new HashMap<>();
        for (int i = 0; i < cols.length; i++) {
            out.put(cols[i].trim().toLowerCase(Locale.ROOT), i);
        }
        return out;
    }

    private void save() {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (Writer w = new OutputStreamWriter(
                    new FileOutputStream(file, false), StandardCharsets.UTF_8)) {
                w.write(join(HEADER));
                w.write("\n");
                for (Row r : rows.values()) {
                    w.write(join(r.toArray()));
                    w.write("\n");
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Nepodařilo se uložit CSV: " + e.getMessage(), e);
        }
    }

    private static String vyhybkaKey(String tuduCode, int vyhybkaCislo) {
        return tuduCode + "\0" + vyhybkaCislo;
    }

    private void addToCastIndex(Row row) {
        int cast = parseInt(row.cast, -1);
        int vyhybka = parseInt(row.vyhybka, -1);
        if (row.tudu == null || row.tudu.isEmpty() || vyhybka < 0 || cast < 0) return;
        castsByVyhybka
                .computeIfAbsent(vyhybkaKey(row.tudu, vyhybka), k -> new HashSet<>())
                .add(cast);
    }

    private void removeFromCastIndex(Row row) {
        int cast = parseInt(row.cast, -1);
        int vyhybka = parseInt(row.vyhybka, -1);
        if (row.tudu == null || row.tudu.isEmpty() || vyhybka < 0 || cast < 0) return;
        Set<Integer> casts = castsByVyhybka.get(vyhybkaKey(row.tudu, vyhybka));
        if (casts == null) return;
        casts.remove(cast);
        if (casts.isEmpty()) castsByVyhybka.remove(vyhybkaKey(row.tudu, vyhybka));
    }

    private static String get(String[] arr, int i) {
        return i < arr.length ? arr[i].trim() : "";
    }

    private static String get(String[] arr, Map<String, Integer> colIndex, String name, int fallback) {
        Integer idx = colIndex.get(name.toLowerCase(Locale.ROOT));
        if (idx != null && idx >= 0) return get(arr, idx);
        if (fallback >= 0) return get(arr, fallback);
        return "";
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9-]", ""));
        } catch (Exception e) {
            return def;
        }
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s.replaceAll("[^0-9-]", ""));
        } catch (Exception e) {
            return def;
        }
    }

    private static String join(String[] cols) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) sb.append(SEP);
            sb.append(escape(cols[i]));
        }
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace(SEP, " ").replace("\n", " ").replace("\r", " ");
    }
}
