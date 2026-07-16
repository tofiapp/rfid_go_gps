package com.rfidw.app.csv;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
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
 *   ID_RFID ; EPC ; TID ; TUDU ; VYHYBKA ; CIP ; POLOHA ; RO_ID_1 ; RO_ID_2 ;
 *   KM_EXT ; LAT ; LON ; ACCURACY_M ; GPS_TIME
 *
 * Klíčem je ID_RFID – při zápisu stejného ID_RFID se daný řádek přepíše.
 */
public class CsvStore {

    public static final String[] HEADER = {
            "ID_RFID", "EPC", "TID", "TUDU", "VYHYBKA", "CIP", "POLOHA",
            "RO_ID_1", "RO_ID_2", "KM_EXT",
            "LAT", "LON", "ACCURACY_M", "GPS_TIME"
    };
    private static final String SEP = ";";

    public static class Row {
        public String idRfid;
        public String epc;
        public String tid;
        public String tudu;
        public String vyhybka;
        public String cast;
        public String poloha;
        public String roId1;
        public String roId2;
        public String kmExt;
        public String latitude;
        public String longitude;
        public String accuracyM;
        public String gpsTime;

        public String[] toArray() {
            return new String[]{
                    idRfid, epc, tid, tudu, vyhybka, cast, poloha,
                    roId1, roId2, kmExt,
                    latitude, longitude, accuracyM, gpsTime
            };
        }
    }

    private final Context appContext;
    private final File file;
    private final Map<String, Row> rows = new LinkedHashMap<>();
    /** TUDU|výhybka|RO_ID → množina zapsaných částí */
    private final Map<String, Set<Integer>> castsByVyhybkaRo = new HashMap<>();
    /** Otisk obsahu po poslední synchronizaci – spolehlivá detekce nahraného souboru z PC. */
    private String lastSyncedHash = "";

    public CsvStore(Context context, File file) {
        this.appContext = context != null ? context.getApplicationContext() : null;
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

    /** Najde řádek pro danou výhybku a část (první shoda v pořadí zápisu). */
    public synchronized Row findRowForCast(String tuduCode, int vyhybkaCislo, int cast) {
        if (tuduCode == null || tuduCode.isEmpty()) return null;
        for (Row r : rows.values()) {
            if (!tuduCode.equals(r.tudu)) continue;
            if (parseInt(r.vyhybka, -1) != vyhybkaCislo) continue;
            if (parseInt(r.cast, -1) != cast) continue;
            return r;
        }
        return null;
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

    /** Upsert a zápis na disk – před zápisem načte soubor z disku, pokud byl změněn zvenku. */
    public synchronized void upsertAndPersist(Row row) {
        reloadIfChanged();
        upsert(row);
        save();
    }

    /**
     * Znovu načte CSV z disku, pokud se obsah změnil (např. nahrání přes USB z PC).
     *
     * @return true pokud došlo ke změně dat v paměti
     */
    public synchronized boolean reloadIfChanged() {
        if (file == null || appContext == null) return false;
        if (!file.isFile()) {
            if (rows.isEmpty()) {
                lastSyncedHash = "";
                return false;
            }
            rows.clear();
            castsByVyhybkaRo.clear();
            lastSyncedHash = "";
            return true;
        }
        String hash = computeFileHash();
        if (hash.equals(lastSyncedHash)) return false;
        load();
        return true;
    }

    private void load() {
        rows.clear();
        castsByVyhybkaRo.clear();
        if (file == null || appContext == null) return;
        if (!file.isFile()) {
            lastSyncedHash = "";
            return;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                CsvStorage.openInputStream(appContext, file), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            CsvFormat format = CsvFormat.CURRENT;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] c = line.split(SEP, -1);
                if (first) {
                    first = false;
                    if (c.length > 0 && c[0].trim().equalsIgnoreCase("ID_RFID")) {
                        format = detectFormat(c);
                        continue;
                    }
                }
                Row r = parseDataRow(c, format);
                if (r.idRfid != null && !r.idRfid.isEmpty()) {
                    rows.put(r.idRfid, r);
                    addToCastIndex(r);
                }
            }
        } catch (Exception e) {
            rows.clear();
            castsByVyhybkaRo.clear();
        }
        lastSyncedHash = computeFileHash();
    }

    private static Row parseDataRow(String[] c, CsvFormat format) {
        Row r = new Row();
        r.idRfid = get(c, 0);
        r.epc = get(c, 1);
        r.tid = get(c, 2);
        switch (format) {
            case LEGACY_WITH_ROK:
                r.tudu = get(c, 4);
                r.vyhybka = get(c, 5);
                r.cast = get(c, 6);
                r.poloha = get(c, 7);
                migrateLegacyRoId(r, get(c, 8));
                migrateLegacyKmExt(r, get(c, 9));
                r.latitude = get(c, 10);
                r.longitude = get(c, 11);
                r.accuracyM = get(c, 12);
                r.gpsTime = get(c, 13);
                break;
            case LEGACY_NO_POLOHA:
                r.tudu = get(c, 3);
                r.vyhybka = get(c, 4);
                r.cast = get(c, 5);
                r.poloha = "";
                r.roId1 = "";
                r.roId2 = "";
                r.kmExt = "";
                r.latitude = get(c, 6);
                r.longitude = get(c, 7);
                r.accuracyM = get(c, 8);
                r.gpsTime = get(c, 9);
                break;
            case LEGACY_NO_RO_KM:
                r.tudu = get(c, 3);
                r.vyhybka = get(c, 4);
                r.cast = get(c, 5);
                r.poloha = get(c, 6);
                r.roId1 = "";
                r.roId2 = "";
                r.kmExt = "";
                r.latitude = get(c, 7);
                r.longitude = get(c, 8);
                r.accuracyM = get(c, 9);
                r.gpsTime = get(c, 10);
                break;
            case LEGACY_SINGLE_RO:
                r.tudu = get(c, 3);
                r.vyhybka = get(c, 4);
                r.cast = get(c, 5);
                r.poloha = get(c, 6);
                migrateLegacyRoId(r, get(c, 7));
                migrateLegacyKmExt(r, get(c, 8));
                r.latitude = get(c, 9);
                r.longitude = get(c, 10);
                r.accuracyM = get(c, 11);
                r.gpsTime = get(c, 12);
                break;
            case CURRENT_KM_EXT_SPLIT:
                r.tudu = get(c, 3);
                r.vyhybka = get(c, 4);
                r.cast = get(c, 5);
                r.poloha = get(c, 6);
                r.roId1 = get(c, 7);
                r.roId2 = get(c, 8);
                migrateSplitKmExt(r, get(c, 9), get(c, 10));
                r.latitude = get(c, 11);
                r.longitude = get(c, 12);
                r.accuracyM = get(c, 13);
                r.gpsTime = get(c, 14);
                break;
            case CURRENT:
            default:
                r.tudu = get(c, 3);
                r.vyhybka = get(c, 4);
                r.cast = get(c, 5);
                r.poloha = get(c, 6);
                r.roId1 = get(c, 7);
                r.roId2 = get(c, 8);
                r.kmExt = get(c, 9);
                r.latitude = get(c, 10);
                r.longitude = get(c, 11);
                r.accuracyM = get(c, 12);
                r.gpsTime = get(c, 13);
                break;
        }
        return r;
    }

    private static void migrateLegacyRoId(Row r, String roIdField) {
        List<String> roIds = parseRoIds(roIdField);
        if (roIds.isEmpty() || (roIds.size() == 1 && roIds.get(0).isEmpty())) {
            r.roId1 = "";
            r.roId2 = "";
            return;
        }
        r.roId1 = roIds.get(0);
        r.roId2 = roIds.size() > 1 ? roIds.get(1) : "";
    }

    private static void migrateLegacyKmExt(Row r, String kmExtField) {
        r.kmExt = kmExtField != null ? kmExtField.trim() : "";
    }

    private static void migrateSplitKmExt(Row r, String kmExt1, String kmExt2) {
        String first = kmExt1 != null ? kmExt1.trim() : "";
        String second = kmExt2 != null ? kmExt2.trim() : "";
        if (first.isEmpty()) {
            r.kmExt = second;
            return;
        }
        if (second.isEmpty()) {
            r.kmExt = first;
            return;
        }
        r.kmExt = first + ", " + second;
    }

    private static CsvFormat detectFormat(String[] header) {
        boolean hasRok = false;
        boolean hasPoloha = false;
        boolean hasRoId1 = false;
        boolean hasRoId = false;
        boolean hasKmExt1 = false;
        boolean hasKmExt = false;
        for (String col : header) {
            String name = col.trim();
            if ("rok".equalsIgnoreCase(name)) hasRok = true;
            if ("POLOHA".equalsIgnoreCase(name)) hasPoloha = true;
            if ("RO_ID_1".equalsIgnoreCase(name)) hasRoId1 = true;
            if ("RO_ID".equalsIgnoreCase(name)) hasRoId = true;
            if ("KM_EXT_1".equalsIgnoreCase(name)) hasKmExt1 = true;
            if ("KM_EXT".equalsIgnoreCase(name)) hasKmExt = true;
        }
        if (hasRoId1 && hasKmExt1) return CsvFormat.CURRENT_KM_EXT_SPLIT;
        if (hasRoId1) return CsvFormat.CURRENT;
        if (hasRok) return CsvFormat.LEGACY_WITH_ROK;
        if (hasPoloha && hasRoId) return CsvFormat.LEGACY_SINGLE_RO;
        if (hasPoloha) return CsvFormat.LEGACY_NO_RO_KM;
        return CsvFormat.LEGACY_NO_POLOHA;
    }

    private enum CsvFormat {
        CURRENT,
        CURRENT_KM_EXT_SPLIT,
        LEGACY_WITH_ROK,
        LEGACY_SINGLE_RO,
        LEGACY_NO_RO_KM,
        LEGACY_NO_POLOHA
    }

    private void save() {
        try {
            try (OutputStream out = CsvStorage.openOutputStream(appContext, file);
                 Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                w.write(join(HEADER));
                w.write("\n");
                for (Row r : rows.values()) {
                    w.write(join(r.toArray()));
                    w.write("\n");
                }
            }
            lastSyncedHash = computeRowsHash();
        } catch (Exception e) {
            throw new RuntimeException("Nepodařilo se uložit CSV: " + e.getMessage(), e);
        }
    }

    private String computeRowsHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            sb.append(join(HEADER)).append('\n');
            for (Row r : rows.values()) {
                sb.append(join(r.toArray())).append('\n');
            }
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String computeFileHash() {
        if (file == null || !file.isFile() || appContext == null) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            try (InputStream in = CsvStorage.openInputStream(appContext, file)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    digest.update(buf, 0, n);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
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
        for (String roId : rowRoIds(row)) {
            castsByVyhybkaRo
                    .computeIfAbsent(vyhybkaRoKey(row.tudu, vyhybka, roId), k -> new HashSet<>())
                    .add(cast);
        }
    }

    private void removeFromCastIndex(Row row) {
        int cast = parseInt(row.cast, -1);
        int vyhybka = parseInt(row.vyhybka, -1);
        if (row.tudu == null || row.tudu.isEmpty() || vyhybka < 0 || cast < 0) return;
        for (String roId : rowRoIds(row)) {
            Set<Integer> casts = castsByVyhybkaRo.get(vyhybkaRoKey(row.tudu, vyhybka, roId));
            if (casts == null) continue;
            casts.remove(cast);
            if (casts.isEmpty()) {
                castsByVyhybkaRo.remove(vyhybkaRoKey(row.tudu, vyhybka, roId));
            }
        }
    }

    /** Všechna neprázdná RO_ID z řádku (RO_ID_1, RO_ID_2). */
    public static List<String> rowRoIds(Row row) {
        if (row == null) return Collections.singletonList("");
        List<String> result = new ArrayList<>(2);
        if (row.roId1 != null && !row.roId1.trim().isEmpty()) result.add(row.roId1.trim());
        if (row.roId2 != null && !row.roId2.trim().isEmpty()) result.add(row.roId2.trim());
        return result.isEmpty() ? Collections.singletonList("") : result;
    }

    /** Jedno RO_ID nebo více hodnot oddělených „, “ (zpětná kompatibilita starého CSV). */
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
