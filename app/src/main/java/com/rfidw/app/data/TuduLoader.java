package com.rfidw.app.data;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Načítání úseků TUDU a jejich výhybek ze souboru .CSV nebo .SQL.
 *
 * Očekávaný CSV formát (oddělovač , nebo ;), s hlavičkou nebo bez:
 *   TUDU;VYHYBKA;CAST_MIN;CAST_MAX
 *   1501J1;1;1;3
 *   1501J1;10;1;4
 *   1501A;5;1;3
 *
 * Stačí i jen dva sloupce (TUDU;VYHYBKA) – části se doplní default 1-3.
 * Pokud řádek obsahuje jen TUDU, založí se úsek bez výhybek.
 *
 * SQL formát: parsují se příkazy INSERT INTO ... VALUES (...),(...);
 * Sloupce se berou v pořadí TUDU, VYHYBKA, [CAST_MIN], [CAST_MAX].
 */
public class TuduLoader {

    /** Načte z libovolného streamu, typ se zvolí podle názvu souboru. */
    public static List<Tudu> load(InputStream in, String fileName) throws Exception {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String content = readAll(in);
        if (name.endsWith(".sql")) {
            return parseSql(content);
        }
        return parseCsv(content);
    }

    // ------------------------------------------------------------------ CSV

    public static List<Tudu> parseCsv(String content) {
        Map<String, Tudu> map = new LinkedHashMap<>();
        String[] lines = content.split("\\r?\\n");
        boolean firstChecked = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            String sep = line.contains(";") ? ";" : ",";
            String[] cols = line.split(sep, -1);
            for (int i = 0; i < cols.length; i++) cols[i] = unquote(cols[i].trim());

            // přeskočit hlavičku
            if (!firstChecked) {
                firstChecked = true;
                String c0 = cols[0].toUpperCase(Locale.ROOT);
                String c1 = cols.length > 1 ? cols[1].toUpperCase(Locale.ROOT) : "";
                if (c0.contains("TUDU") || c1.contains("VYHYB") || c1.contains("VÝHYB")) {
                    continue;
                }
            }

            addRow(map, cols);
        }
        return new ArrayList<>(map.values());
    }

    // ------------------------------------------------------------------ SQL

    private static final Pattern INSERT =
            Pattern.compile("insert\\s+into\\s+[^(]*?\\bvalues\\b(.*?);",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TUPLE = Pattern.compile("\\(([^)]*)\\)");

    public static List<Tudu> parseSql(String content) {
        Map<String, Tudu> map = new LinkedHashMap<>();
        Matcher mIns = INSERT.matcher(content);
        while (mIns.find()) {
            String valuesPart = mIns.group(1);
            Matcher mTup = TUPLE.matcher(valuesPart);
            while (mTup.find()) {
                String[] cols = splitSqlTuple(mTup.group(1));
                addRow(map, cols);
            }
        }
        return new ArrayList<>(map.values());
    }

    private static String[] splitSqlTuple(String tuple) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inStr = false;
        char q = 0;
        for (int i = 0; i < tuple.length(); i++) {
            char c = tuple.charAt(i);
            if (inStr) {
                if (c == q) inStr = false; else cur.append(c);
            } else {
                if (c == '\'' || c == '"') { inStr = true; q = c; }
                else if (c == ',') { out.add(cur.toString().trim()); cur.setLength(0); }
                else cur.append(c);
            }
        }
        out.add(cur.toString().trim());
        return out.toArray(new String[0]);
    }

    // ------------------------------------------------------------------ shared

    private static void addRow(Map<String, Tudu> map, String[] cols) {
        if (cols.length == 0 || cols[0].isEmpty()) return;
        String tuduCode = cols[0];
        Tudu tudu = map.get(tuduCode);
        if (tudu == null) {
            tudu = new Tudu(tuduCode);
            map.put(tuduCode, tudu);
        }
        if (cols.length >= 2 && !cols[1].isEmpty()) {
            Integer cislo = toInt(cols[1]);
            if (cislo != null) {
                Tudu.Vyhybka v = tudu.findOrCreate(cislo);
                if (cols.length >= 3) {
                    Integer cmin = toInt(cols[2]);
                    if (cmin != null) v.castMin = cmin;
                }
                if (cols.length >= 4) {
                    Integer cmax = toInt(cols[3]);
                    if (cmax != null) v.castMax = cmax;
                }
            }
        }
    }

    private static Integer toInt(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9-]", "")); }
        catch (Exception e) { return null; }
    }

    private static String unquote(String s) {
        if (s.length() >= 2 &&
                ((s.startsWith("\"") && s.endsWith("\"")) ||
                 (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }
}
