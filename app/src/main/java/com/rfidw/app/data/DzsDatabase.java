package com.rfidw.app.data;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SQLite zdroj TUDU / výhybek z tabulek DZS_SUPERTRA_GPS_KM a DZS_SUPER_RO_TPI.
 *
 * Postup:
 * 1) V DZS_SUPERTRA_GPS_KM najít bod nejblíže aktuální GPS poloze.
 * 2) Z něj vzít SUPER_Z_ID a SUPER_D_ID.
 * 3) V DZS_SUPER_RO_TPI podle těchto ID dohledat TUDU a číslo výhybky (sloupec COBJEKT).
 */
public class DzsDatabase implements Closeable {

    public static final String TABLE_GPS_KM = "DZS_SUPERTRA_GPS_KM";
    public static final String TABLE_RO_TPI = "DZS_SUPER_RO_TPI";

    public static class GpsMatch {
        public final String superZId;
        public final String superDId;
        public final String tudu;
        public final int vyhybka;
        public final double latitude;
        public final double longitude;
        public final double distanceM;

        public GpsMatch(String superZId, String superDId, String tudu, int vyhybka,
                        double latitude, double longitude, double distanceM) {
            this.superZId = superZId;
            this.superDId = superDId;
            this.tudu = tudu;
            this.vyhybka = vyhybka;
            this.latitude = latitude;
            this.longitude = longitude;
            this.distanceM = distanceM;
        }
    }

    private final SQLiteDatabase db;
    private final GpsColumns gpsColumns;
    private final RoColumns roColumns;

    private DzsDatabase(SQLiteDatabase db, GpsColumns gpsColumns, RoColumns roColumns) {
        this.db = db;
        this.gpsColumns = gpsColumns;
        this.roColumns = roColumns;
    }

    public static DzsDatabase open(String path) throws Exception {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(
                path, null, SQLiteDatabase.OPEN_READONLY);
        try {
            GpsColumns gpsColumns = GpsColumns.resolve(db, TABLE_GPS_KM);
            RoColumns roColumns = RoColumns.resolve(db, TABLE_RO_TPI);
            return new DzsDatabase(db, gpsColumns, roColumns);
        } catch (Exception e) {
            db.close();
            throw e;
        }
    }

    /** Všechny TUDU a výhybky z DZS_SUPER_RO_TPI (pro ruční výběr). */
    public List<Tudu> loadAllTudu() {
        Map<String, Tudu> map = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(roColumns.tudu).append(", ").append(roColumns.vyhybka);
        if (roColumns.castMin != null) sql.append(", ").append(roColumns.castMin);
        if (roColumns.castMax != null) sql.append(", ").append(roColumns.castMax);
        sql.append(" FROM ").append(TABLE_RO_TPI)
                .append(" WHERE ").append(roColumns.tudu).append(" IS NOT NULL AND ")
                .append(roColumns.tudu).append(" <> ''")
                .append(" AND ").append(roColumns.vyhybka).append(" IS NOT NULL")
                .append(" ORDER BY ").append(roColumns.tudu).append(", ").append(roColumns.vyhybka);

        try (Cursor c = db.rawQuery(sql.toString(), null)) {
            while (c.moveToNext()) {
                String tuduCode = c.getString(0);
                if (tuduCode == null || tuduCode.isEmpty()) continue;
                Integer cislo = readInt(c, 1);
                if (cislo == null) continue;

                Tudu tudu = map.get(tuduCode);
                if (tudu == null) {
                    tudu = new Tudu(tuduCode);
                    map.put(tuduCode, tudu);
                }
                Tudu.Vyhybka v = tudu.findOrCreate(cislo);
                int col = 2;
                if (roColumns.castMin != null) {
                    Integer cmin = readInt(c, col++);
                    if (cmin != null) v.castMin = cmin;
                }
                if (roColumns.castMax != null) {
                    Integer cmax = readInt(c, col);
                    if (cmax != null) v.castMax = cmax;
                }
            }
        }
        return new ArrayList<>(map.values());
    }

    /**
     * Najde nejbližší bod v DZS_SUPERTRA_GPS_KM, který má záznam v DZS_SUPER_RO_TPI.
     */
    public GpsMatch findNearest(double latitude, double longitude) {
        GpsMatch joined = findNearestJoined(latitude, longitude);
        if (joined != null) return joined;
        return findNearestLegacy(latitude, longitude);
    }

    private GpsMatch findNearestJoined(double latitude, double longitude) {
        String joinOn = idJoinCondition("g", gpsColumns.superZId, gpsColumns.superDId, "ro", roColumns.superZId, roColumns.superDId);
        String sql = "SELECT ro." + roColumns.tudu + ", ro." + roColumns.vyhybka + ", "
                + "g." + gpsColumns.latitude + ", g." + gpsColumns.longitude + ", "
                + "g." + gpsColumns.superZId + ", g." + gpsColumns.superDId
                + " FROM " + TABLE_GPS_KM + " g"
                + " INNER JOIN " + TABLE_RO_TPI + " ro ON " + joinOn
                + " WHERE g." + gpsColumns.latitude + " IS NOT NULL"
                + " AND g." + gpsColumns.longitude + " IS NOT NULL"
                + " AND TRIM(CAST(ro." + roColumns.tudu + " AS TEXT)) <> ''"
                + " AND ro." + roColumns.vyhybka + " IS NOT NULL"
                + " ORDER BY ((g." + gpsColumns.latitude + " - ?) * (g." + gpsColumns.latitude + " - ?)"
                + " + (g." + gpsColumns.longitude + " - ?) * (g." + gpsColumns.longitude + " - ?))"
                + " LIMIT 1";

        String[] args = {
                String.valueOf(latitude), String.valueOf(latitude),
                String.valueOf(longitude), String.valueOf(longitude)
        };

        try (Cursor c = db.rawQuery(sql, args)) {
            if (!c.moveToFirst()) return null;
            String tudu = trimToNull(c.getString(0));
            Integer vyhybka = readInt(c, 1);
            double bestLat = c.getDouble(2);
            double bestLon = c.getDouble(3);
            String superZId = c.getString(4);
            String superDId = c.getString(5);
            if (tudu == null || vyhybka == null) return null;
            double dist = haversineM(latitude, longitude, bestLat, bestLon);
            return new GpsMatch(superZId, superDId, tudu, vyhybka, bestLat, bestLon, dist);
        }
    }

    /** Záloha: nejbližší GPS bod a ruční dohledání TUDU / výhybky. */
    private GpsMatch findNearestLegacy(double latitude, double longitude) {
        String nearestSql = "SELECT " + gpsColumns.superZId + ", " + gpsColumns.superDId + ", "
                + gpsColumns.latitude + ", " + gpsColumns.longitude
                + " FROM " + TABLE_GPS_KM
                + " WHERE " + gpsColumns.latitude + " IS NOT NULL"
                + " AND " + gpsColumns.longitude + " IS NOT NULL"
                + " ORDER BY ((" + gpsColumns.latitude + " - ?) * (" + gpsColumns.latitude + " - ?)"
                + " + (" + gpsColumns.longitude + " - ?) * (" + gpsColumns.longitude + " - ?))"
                + " LIMIT 20";

        String[] args = {
                String.valueOf(latitude), String.valueOf(latitude),
                String.valueOf(longitude), String.valueOf(longitude)
        };

        try (Cursor c = db.rawQuery(nearestSql, args)) {
            while (c.moveToNext()) {
                String superZId = c.getString(0);
                String superDId = c.getString(1);
                double bestLat = c.getDouble(2);
                double bestLon = c.getDouble(3);
                if (superZId == null || superDId == null) continue;
                GpsMatch match = lookupRoEntry(superZId, superDId, latitude, longitude, bestLat, bestLon);
                if (match != null) return match;
            }
        }
        return null;
    }

    private GpsMatch lookupRoEntry(String superZId, String superDId,
                                   double queryLat, double queryLon, double bestLat, double bestLon) {
        String lookupSql = "SELECT " + roColumns.tudu + ", " + roColumns.vyhybka
                + " FROM " + TABLE_RO_TPI
                + " WHERE TRIM(CAST(" + roColumns.superZId + " AS TEXT)) = ?"
                + " AND TRIM(CAST(" + roColumns.superDId + " AS TEXT)) = ?"
                + " LIMIT 1";

        for (String zKey : idLookupKeys(superZId)) {
            for (String dKey : idLookupKeys(superDId)) {
                try (Cursor c = db.rawQuery(lookupSql, new String[]{zKey, dKey})) {
                    if (!c.moveToFirst()) continue;
                    String tudu = trimToNull(c.getString(0));
                    Integer vyhybka = readInt(c, 1);
                    if (tudu == null || vyhybka == null) continue;
                    double dist = haversineM(queryLat, queryLon, bestLat, bestLon);
                    return new GpsMatch(superZId, superDId, tudu, vyhybka, bestLat, bestLon, dist);
                }
            }
        }
        return null;
    }

    private static String idJoinCondition(String gpsAlias, String gpsZ, String gpsD,
                                          String roAlias, String roZ, String roD) {
        return "TRIM(CAST(" + gpsAlias + "." + gpsZ + " AS TEXT)) = TRIM(CAST(" + roAlias + "." + roZ + " AS TEXT))"
                + " AND TRIM(CAST(" + gpsAlias + "." + gpsD + " AS TEXT)) = TRIM(CAST(" + roAlias + "." + roD + " AS TEXT))";
    }

    private static List<String> idLookupKeys(String raw) {
        List<String> keys = new ArrayList<>();
        if (raw == null) return keys;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return keys;
        keys.add(trimmed);
        String normalized = normalizeId(trimmed);
        if (!keys.contains(normalized)) keys.add(normalized);
        try {
            String numeric = trimmed.replace(',', '.');
            if (numeric.matches("-?\\d+(\\.\\d+)?")) {
                long asLong = (long) Double.parseDouble(numeric);
                String asStr = String.valueOf(asLong);
                if (!keys.contains(asStr)) keys.add(asStr);
            }
        } catch (Exception ignored) { }
        return keys;
    }

    private static String normalizeId(String id) {
        if (id == null) return "";
        String s = id.trim();
        int dot = s.indexOf('.');
        if (dot > 0 && s.substring(dot).matches("\\.0+")) {
            return s.substring(0, dot);
        }
        return s;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Všechny GPS body z DZS_SUPERTRA_GPS_KM – pro výběr simulované polohy v test módu.
     * Popisek (TUDU · výhybka) je jen nápověda; výběr probíhá podle souřadnic.
     */
    public static class GpsPoint {
        public final double latitude;
        public final double longitude;
        public final String label;

        public GpsPoint(double latitude, double longitude, String label) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.label = label != null ? label : "";
        }
    }

    public List<GpsPoint> listGpsPoints() {
        List<GpsPoint> out = new ArrayList<>();
        String sql = "SELECT " + gpsColumns.superZId + ", " + gpsColumns.superDId + ", "
                + gpsColumns.latitude + ", " + gpsColumns.longitude
                + " FROM " + TABLE_GPS_KM
                + " WHERE " + gpsColumns.latitude + " IS NOT NULL"
                + " AND " + gpsColumns.longitude + " IS NOT NULL"
                + " ORDER BY " + gpsColumns.latitude + ", " + gpsColumns.longitude;

        try (Cursor c = db.rawQuery(sql, null)) {
            while (c.moveToNext()) {
                String superZId = c.getString(0);
                String superDId = c.getString(1);
                double lat = c.getDouble(2);
                double lon = c.getDouble(3);
                out.add(new GpsPoint(lat, lon, lookupTuduVyhybkaLabel(superZId, superDId)));
            }
        }
        return out;
    }

    private String lookupTuduVyhybkaLabel(String superZId, String superDId) {
        GpsMatch match = lookupRoEntry(superZId, superDId, 0, 0, 0, 0);
        if (match == null) return "";
        return match.tudu + " · výhybka " + match.vyhybka;
    }

    @Override
    public void close() {
        db.close();
    }

    private static Integer readInt(Cursor c, int index) {
        if (c.isNull(index)) return null;
        try {
            return c.getInt(index);
        } catch (Exception e) {
            try {
                return Integer.parseInt(c.getString(index).replaceAll("[^0-9-]", ""));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static double haversineM(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    private static final class GpsColumns {
        final String superZId;
        final String superDId;
        final String latitude;
        final String longitude;

        GpsColumns(String superZId, String superDId, String latitude, String longitude) {
            this.superZId = superZId;
            this.superDId = superDId;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        static GpsColumns resolve(SQLiteDatabase db, String table) throws Exception {
            List<String> cols = tableColumns(db, table);
            if (cols.isEmpty()) {
                throw new Exception("Tabulka " + table + " neexistuje");
            }
            String superZId = requireColumn(cols, "SUPER_Z_ID");
            String superDId = requireColumn(cols, "SUPER_D_ID");
            String lat = findRequiredColumn(cols, "LATITUDE", "LAT", "GPS_LAT", "SIRKA", "GPS_SIRKA", "Y");
            String lon = findRequiredColumn(cols, "LONGITUDE", "LON", "LNG", "GPS_LON", "DELKA", "GPS_DELKA", "X");
            return new GpsColumns(superZId, superDId, lat, lon);
        }
    }

    private static final class RoColumns {
        final String superZId;
        final String superDId;
        final String tudu;
        final String vyhybka;
        final String castMin;
        final String castMax;

        RoColumns(String superZId, String superDId, String tudu, String vyhybka,
                  String castMin, String castMax) {
            this.superZId = superZId;
            this.superDId = superDId;
            this.tudu = tudu;
            this.vyhybka = vyhybka;
            this.castMin = castMin;
            this.castMax = castMax;
        }

        static RoColumns resolve(SQLiteDatabase db, String table) throws Exception {
            List<String> cols = tableColumns(db, table);
            if (cols.isEmpty()) {
                throw new Exception("Tabulka " + table + " neexistuje");
            }
            String vyhybka = findRequiredColumn(cols, "COBJEKT", "VYHYBKA", "VYH_CISLO", "CISLO_VYHYBKY",
                    "CIS_VYHYBKY", "VYHYBKA_CISLO");
            return new RoColumns(
                    requireColumn(cols, "SUPER_Z_ID"),
                    requireColumn(cols, "SUPER_D_ID"),
                    findRequiredColumn(cols, "TUDU", "TUDU_KOD", "TUDU_CODE"),
                    vyhybka,
                    findOptionalColumn(cols, "CAST_MIN", "CASTMIN"),
                    findOptionalColumn(cols, "CAST_MAX", "CASTMAX")
            );
        }
    }

    private static List<String> tableColumns(SQLiteDatabase db, String table) {
        List<String> out = new ArrayList<>();
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (c.moveToNext()) {
                out.add(c.getString(1));
            }
        }
        return out;
    }

    private static String requireColumn(List<String> cols, String name) throws Exception {
        return findRequiredColumn(cols, name);
    }

    private static String findRequiredColumn(List<String> cols, String... candidates) throws Exception {
        String hit = findOptionalColumn(cols, candidates);
        if (hit == null) {
            throw new Exception("Chybí sloupec " + candidates[0]);
        }
        return hit;
    }

    private static String findOptionalColumn(List<String> cols, String... candidates) {
        Map<String, String> byUpper = new HashMap<>();
        for (String col : cols) {
            byUpper.put(col.toUpperCase(Locale.ROOT), col);
        }
        for (String candidate : candidates) {
            String hit = byUpper.get(candidate.toUpperCase(Locale.ROOT));
            if (hit != null) return hit;
        }
        return null;
    }
}
