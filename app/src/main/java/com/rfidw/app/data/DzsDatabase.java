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
        String vyhybkaExpr = roColumns.vyhybkaSelectExpr(null);
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(roColumns.tudu).append(", ").append(vyhybkaExpr);
        if (roColumns.castMin != null) sql.append(", ").append(roColumns.castMin);
        if (roColumns.castMax != null) sql.append(", ").append(roColumns.castMax);
        sql.append(" FROM ").append(TABLE_RO_TPI)
                .append(" WHERE ").append(roColumns.tudu).append(" IS NOT NULL AND ")
                .append(roColumns.tudu).append(" <> ''")
                .append(" AND ").append(vyhybkaExpr).append(" IS NOT NULL")
                .append(" ORDER BY ").append(roColumns.tudu).append(", ").append(vyhybkaExpr);

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
     * Najde nejbližší bod v DZS_SUPERTRA_GPS_KM, který má odpovídající TUDU v DZS_SUPER_RO_TPI.
     * JOIN zajišťuje, že se nevybere GPS bod bez mapování (dřív lookup po nejbližším bodu často selhal).
     */
    public GpsMatch findNearest(double latitude, double longitude) {
        String gpsAlias = "gps";
        String roAlias = "ro";
        String vyhybkaExpr = roColumns.vyhybkaSelectExpr(roAlias);
        String joinOn = idJoinCondition(gpsAlias, roAlias);

        String nearestSql = "SELECT " + roAlias + "." + roColumns.tudu + ", " + vyhybkaExpr + ", "
                + gpsAlias + "." + gpsColumns.superZId + ", " + gpsAlias + "." + gpsColumns.superDId + ", "
                + gpsAlias + "." + gpsColumns.latitude + ", " + gpsAlias + "." + gpsColumns.longitude
                + " FROM " + TABLE_GPS_KM + " " + gpsAlias
                + " INNER JOIN " + TABLE_RO_TPI + " " + roAlias + " ON " + joinOn
                + " WHERE " + gpsAlias + "." + gpsColumns.latitude + " IS NOT NULL"
                + " AND " + gpsAlias + "." + gpsColumns.longitude + " IS NOT NULL"
                + " AND " + roAlias + "." + roColumns.tudu + " IS NOT NULL"
                + " AND " + roAlias + "." + roColumns.tudu + " <> ''"
                + " AND " + vyhybkaExpr + " IS NOT NULL"
                + " ORDER BY ((" + gpsAlias + "." + gpsColumns.latitude + " - ?) * ("
                + gpsAlias + "." + gpsColumns.latitude + " - ?)"
                + " + (" + gpsAlias + "." + gpsColumns.longitude + " - ?) * ("
                + gpsAlias + "." + gpsColumns.longitude + " - ?))"
                + " LIMIT 1";

        String[] args = {
                String.valueOf(latitude), String.valueOf(latitude),
                String.valueOf(longitude), String.valueOf(longitude)
        };

        try (Cursor c = db.rawQuery(nearestSql, args)) {
            if (!c.moveToFirst()) return null;
            String tudu = c.getString(0);
            Integer vyhybka = readInt(c, 1);
            String superZId = readId(c, 2);
            String superDId = readId(c, 3);
            double bestLat = c.getDouble(4);
            double bestLon = c.getDouble(5);
            if (tudu == null || tudu.isEmpty() || vyhybka == null
                    || superZId == null || superDId == null) {
                return null;
            }
            double dist = haversineM(latitude, longitude, bestLat, bestLon);
            return new GpsMatch(superZId, superDId, tudu, vyhybka, bestLat, bestLon, dist);
        }
    }

    private String idJoinCondition(String gpsAlias, String roAlias) {
        return idExpr(gpsAlias, gpsColumns.superZId) + " = " + idExpr(roAlias, roColumns.superZId)
                + " AND " + idExpr(gpsAlias, gpsColumns.superDId) + " = "
                + idExpr(roAlias, roColumns.superDId);
    }

    private static String idExpr(String tableAlias, String column) {
        return "TRIM(CAST(" + tableAlias + "." + column + " AS TEXT))";
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
        String vyhybkaExpr = roColumns.vyhybkaSelectExpr("ro");
        String lookupSql = "SELECT ro." + roColumns.tudu + ", " + vyhybkaExpr
                + " FROM " + TABLE_RO_TPI + " ro"
                + " WHERE " + idExpr("ro", roColumns.superZId) + " = ?"
                + " AND " + idExpr("ro", roColumns.superDId) + " = ?"
                + " AND ro." + roColumns.tudu + " IS NOT NULL AND ro." + roColumns.tudu + " <> ''"
                + " AND " + vyhybkaExpr + " IS NOT NULL"
                + " LIMIT 1";
        try (Cursor c = db.rawQuery(lookupSql, new String[]{
                normalizeId(superZId), normalizeId(superDId)})) {
            if (!c.moveToFirst()) return "";
            String tudu = c.getString(0);
            Integer vyhybka = readInt(c, 1);
            if (tudu == null || tudu.isEmpty() || vyhybka == null) return "";
            return tudu + " · výhybka " + vyhybka;
        }
    }

    @Override
    public void close() {
        db.close();
    }

    private static String readId(Cursor c, int index) {
        if (c.isNull(index)) return null;
        try {
            return normalizeId(c.getString(index));
        } catch (Exception e) {
            try {
                return normalizeId(String.valueOf(c.getLong(index)));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static String normalizeId(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
        final String vyhybkaFallback;
        final String castMin;
        final String castMax;

        RoColumns(String superZId, String superDId, String tudu, String vyhybka,
                  String vyhybkaFallback, String castMin, String castMax) {
            this.superZId = superZId;
            this.superDId = superDId;
            this.tudu = tudu;
            this.vyhybka = vyhybka;
            this.vyhybkaFallback = vyhybkaFallback;
            this.castMin = castMin;
            this.castMax = castMax;
        }

        String vyhybkaSelectExpr(String tableAlias) {
            String prefix = tableAlias == null || tableAlias.isEmpty()
                    ? "" : tableAlias + ".";
            if (vyhybkaFallback == null) {
                return prefix + vyhybka;
            }
            return "COALESCE(" + prefix + vyhybka + ", " + prefix + vyhybkaFallback + ")";
        }

        static RoColumns resolve(SQLiteDatabase db, String table) throws Exception {
            List<String> cols = tableColumns(db, table);
            if (cols.isEmpty()) {
                throw new Exception("Tabulka " + table + " neexistuje");
            }
            String vyhybka = findRequiredColumn(cols, "COBJEKT", "VYHYBKA", "VYH_CISLO", "CISLO_VYHYBKY",
                    "CIS_VYHYBKY", "VYHYBKA_CISLO");
            String vyhybkaFallback = null;
            if (!vyhybka.equalsIgnoreCase("VYHYBKA")) {
                vyhybkaFallback = findOptionalColumn(cols, "VYHYBKA", "VYH_CISLO", "CISLO_VYHYBKY",
                        "CIS_VYHYBKY", "VYHYBKA_CISLO");
            }
            return new RoColumns(
                    requireColumn(cols, "SUPER_Z_ID"),
                    requireColumn(cols, "SUPER_D_ID"),
                    findRequiredColumn(cols, "TUDU", "TUDU_KOD", "TUDU_CODE"),
                    vyhybka,
                    vyhybkaFallback,
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
