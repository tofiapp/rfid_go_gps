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
import java.util.Map;
import java.util.Set;

/**
 * Výstupní tabulka .CSV.
 *
 * Sloupce:
 *   ID_RFID ; EPC ; TID ; rok ; TUDU ; vyhybka ; cip ; POLOHA ; RO_ID ; KM_EXT ;
 *   LAT ; LON ; accuracy_m ; gps_time
 *
 * Klíčem je ID_RFID – při zápisu stejného ID_RFID se daný řádek přepíše.
 */
public class CsvStore {

    public static final String[] HEADER = {
            "ID_RFID", "EPC", "TID", "rok", "TUDU", "vyhybka", "cip", "POLOHA", "RO_ID", "KM_EXT",
            "LAT", "LON", "accuracy_m", "gps_time"
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
        public String roId;
        /** logika KM_EXT – nejbližší km bod k GPS při zápisu (viz {@code KmExtLogic}). */
        public String kmExt;
        public String latitude;
        public String longitude;
        public String accuracyM;
        public String gpsTime;

        public String[] toArray() {
            return new String[]{
                    idRfid, epc, tid, rok, tudu, vyhybka, cast, poloha, roId, kmExt,
                    latitude, longitude, accuracyM, gpsTime
            };
        }
    }

    private final File file;
    private final Map<String, Row> rows = new LinkedHashMap<>();
    /** TUDU|výhybka|RO_ID → množina zapsaných částí */
    private final Map<String, Set<Integer>> castsByVyhybkaRo = new HashMap<>();

    public CsvStore(File file) {
        this.file = file;
        load();
    }

    public File getFile() { return file; }

    public List<Row> getRows() {
        return new ArrayList<>(rows.values());
    }

    public int size() { return rows.size(); }

    public Row getLastRow() {
        if (rows.isEmpty()) return null;
        List<Row> list = getRows();
        return list.get(list.size() - 1);
    }

    public synchronized long getMaxIdRfid() {
        long max = 0;
        for (Row r : rows.values()) {
            long id = parseLong(r.idRfid, 0);
            if (id > max) max = id;
        }
        return max;
    }

    public synchronized void upsert(Row row) {
        Row previous = rows.get(row.idRfid);
        if (previous != null) removeFromCastIndex(previous);
        rows.put(row.idRfid, row);
        addToCastIndex(row);
    }

    public synchronized void clear() {
        rows.clear();
        castsByVyhybkaRo.clear();
    }

    public List<Row> getLastRows(int max) {
        List<Row> all = getRows();
        if (max <= 0 || all.isEmpty()) return new ArrayList<>();
        int from = Math.max(0, all.size() - max);
        return new ArrayList<>(all.subList(from, all.size()));
    }

    public synchronized Row removeLast() {
        if (rows.isEmpty()) return null;
        List<Row> list = getRows();
        Row last = list.get(list.size() - 1);
        rows.remove(last.idRfid);
        removeFromCastIndex(last);
        return last;
    }

    /** Souhrn částí výhybky napříč všemi RO_ID (zpětná kompatibilita). */
    public synchronized Set<Integer> getWrittenCasts(String tuduCode, int vyhybkaCislo) {
        Set<Integer> merged = new HashSet<>();
        String prefix = tuduCode + "\0" + vyhybkaCislo + "\0";
        for (Map.Entry<String, Set<Integer>> e : castsByVyhybkaRo.entrySet()) {
            if (e.getKey().startsWith(prefix)) merged.addAll(e.getValue());
        }
        return merged.isEmpty() ? Collections.emptySet() : merged;
    }

    public synchronized Set<Integer> getWrittenCasts(String tuduCode, int vyhybkaCislo, String roId) {
        Set<Integer> casts = castsByVyhybkaRo.get(vyhybkaRoKey(tuduCode, vyhybkaCislo, roId));
        if (casts == null || casts.isEmpty()) return Collections.emptySet();
        return new HashSet<>(casts);
    }

    public synchronized boolean hasWrittenCast(String tuduCode, int vyhybkaCislo, String roId, int cast) {
        return getWrittenCasts(tuduCode, vyhybkaCislo, roId).contains(cast);
    }

    public synchronized void persist() {
        save();
    }

    private void load() {
        rows.clear();
        castsByVyhybkaRo.clear();
        if (file == null || !file.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            boolean first = true;
            boolean hasPolohaColumn = false;
            boolean hasRoIdColumn = false;
            boolean hasKmExtColumn = false;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] c = line.split(SEP, -1);
                if (first) {
                    first = false;
                    if (c.length > 0 && c[0].trim().equalsIgnoreCase("ID_RFID")) {
                        for (String col : c) {
                            String name = col.trim();
                            if ("POLOHA".equalsIgnoreCase(name)) hasPolohaColumn = true;
                            if ("RO_ID".equalsIgnoreCase(name)) hasRoIdColumn = true;
                            // logika KM_EXT
                            if ("KM_EXT".equalsIgnoreCase(name)) hasKmExtColumn = true;
                        }
                        continue;
                    }
                }
                Row r = new Row();
                r.idRfid = get(c, 0);
                r.epc = get(c, 1);
                r.tid = get(c, 2);
                r.rok = get(c, 3);
                r.tudu = get(c, 4);
                r.vyhybka = get(c, 5);
                r.cast = get(c, 6);
                if (hasPolohaColumn && hasRoIdColumn && hasKmExtColumn) {
                    r.poloha = get(c, 7);
                    r.roId = get(c, 8);
                    r.kmExt = get(c, 9);
                    r.latitude = get(c, 10);
                    r.longitude = get(c, 11);
                    r.accuracyM = get(c, 12);
                    r.gpsTime = get(c, 13);
                } else if (hasPolohaColumn && hasRoIdColumn) {
                    r.poloha = get(c, 7);
                    r.roId = get(c, 8);
                    r.kmExt = "";
                    r.latitude = get(c, 9);
                    r.longitude = get(c, 10);
                    r.accuracyM = get(c, 11);
                    r.gpsTime = get(c, 12);
                } else if (hasPolohaColumn) {
                    r.poloha = get(c, 7);
                    r.roId = "";
                    r.kmExt = "";
                    r.latitude = get(c, 8);
                    r.longitude = get(c, 9);
                    r.accuracyM = get(c, 10);
                    r.gpsTime = get(c, 11);
                } else {
                    r.poloha = "";
                    r.roId = "";
                    r.kmExt = "";
                    r.latitude = get(c, 7);
                    r.longitude = get(c, 8);
                    r.accuracyM = get(c, 9);
                    r.gpsTime = get(c, 10);
                }
                if (r.idRfid != null && !r.idRfid.isEmpty()) {
                    rows.put(r.idRfid, r);
                    addToCastIndex(r);
                }
            }
        } catch (Exception e) {
            rows.clear();
            castsByVyhybkaRo.clear();
        }
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

    private static String vyhybkaRoKey(String tuduCode, int vyhybkaCislo, String roId) {
        String ro = roId != null ? roId.trim() : "";
        return tuduCode + "\0" + vyhybkaCislo + "\0" + ro;
    }

    private void addToCastIndex(Row row) {
        int cast = parseInt(row.cast, -1);
        int vyhybka = parseInt(row.vyhybka, -1);
        if (row.tudu == null || row.tudu.isEmpty() || vyhybka < 0 || cast < 0) return;
        for (String roId : parseRoIds(row.roId)) {
            castsByVyhybkaRo
                    .computeIfAbsent(vyhybkaRoKey(row.tudu, vyhybka, roId), k -> new HashSet<>())
                    .add(cast);
        }
    }

    private void removeFromCastIndex(Row row) {
        int cast = parseInt(row.cast, -1);
        int vyhybka = parseInt(row.vyhybka, -1);
        if (row.tudu == null || row.tudu.isEmpty() || vyhybka < 0 || cast < 0) return;
        for (String roId : parseRoIds(row.roId)) {
            Set<Integer> casts = castsByVyhybkaRo.get(vyhybkaRoKey(row.tudu, vyhybka, roId));
            if (casts == null) continue;
            casts.remove(cast);
            if (casts.isEmpty()) {
                castsByVyhybkaRo.remove(vyhybkaRoKey(row.tudu, vyhybka, roId));
            }
        }
    }

    /** Jedno RO_ID nebo více hodnot oddělených „, “ (čip 1 u dvojvětvé výhybky). */
    public static List<String> parseRoIds(String roIdField) {
        if (roIdField == null || roIdField.trim().isEmpty()) {
            return Collections.singletonList("");
        }
        String trimmed = roIdField.trim();
        String[] parts = trimmed.contains(",")
                ? trimmed.split("\\s*,\\s*")
                : trimmed.split("\\s+");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (!part.isEmpty()) result.add(part);
        }
        return result.isEmpty() ? Collections.singletonList("") : result;
    }

    private static String get(String[] arr, int i) {
        return i < arr.length ? arr[i].trim() : "";
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
