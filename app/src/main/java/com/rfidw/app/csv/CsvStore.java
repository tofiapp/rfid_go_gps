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
 *   ID_RFID ; EPC ; TID ; rok ; TUDU ; vyhybka ; cip
 *
 * Klíčem je ID_RFID – při zápisu stejného ID_RFID se daný řádek přepíše.
 */
public class CsvStore {

    public static final String[] HEADER = {
            "ID_RFID", "EPC", "TID", "rok", "TUDU", "vyhybka", "cip"
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

        public String[] toArray() {
            return new String[]{ idRfid, epc, tid, rok, tudu, vyhybka, cast };
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
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] c = line.split(SEP, -1);
                if (first) {
                    first = false;
                    if (c.length > 0 && c[0].trim().equalsIgnoreCase("ID_RFID")) {
                        continue; // hlavička
                    }
                }
                Row r = new Row();
                r.idRfid  = get(c, 0);
                r.epc     = get(c, 1);
                r.tid     = get(c, 2);
                r.rok     = get(c, 3);
                r.tudu    = get(c, 4);
                r.vyhybka = get(c, 5);
                r.cast    = get(c, 6);
                if (r.idRfid != null && !r.idRfid.isEmpty()) {
                    rows.put(r.idRfid, r);
                    addToCastIndex(r);
                }
            }
        } catch (Exception e) {
            // poškozený soubor – začneme s prázdnou tabulkou
            rows.clear();
            castsByVyhybka.clear();
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
        // nahradíme oddělovač/nové řádky, ať se tabulka nerozbije
        return s.replace(SEP, " ").replace("\n", " ").replace("\r", " ");
    }
}
