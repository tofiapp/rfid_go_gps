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
     * Najde nejbližší bod v DZS_SUPERTRA_GPS_KM a dohledá TUDU / výhybku v DZS_SUPER_RO_TPI.
     */
    public GpsMatch findNearest(double latitude, double longitude) {
        String nearestSql = "SELECT " + gpsColumns.superZId + ", " + gpsColumns.superDId + ", "
                + gpsColumns.latitude + ", " + gpsColumns.longitude
                + " FROM " + TABLE_GPS_KM
                + " WHERE " + gpsColumns.latitude + " IS NOT NULL"
                + " AND " + gpsColumns.longitude + " IS NOT NULL"
                + " ORDER BY ((" + gpsColumns.latitude + " - ?) * (" + gpsColumns.latitude + " - ?)"
                + " + (" + gpsColumns.longitude + " - ?) * (" + gpsColumns.longitude + " - ?))"
                + " LIMIT 1";

        String[] args = {
                String.valueOf(latitude), String.valueOf(latitude),
                String.valueOf(longitude), String.valueOf(longitude)
        };

        String superZId = null;
        String superDId = null;
        double bestLat = 0;
        double bestLon = 0;

        try (Cursor c = db.rawQuery(nearestSql, args)) {
            if (!c.moveToFirst()) return null;
            superZId = c.getString(0);
            superDId = c.getString(1);
            bestLat = c.getDouble(2);
            bestLon = c.getDouble(3);
        }

        if (superZId == null || superDId == null) return null;

        String lookupSql = "SELECT " + roColumns.tudu + ", " + roColumns.vyhybka
                + " FROM " + TABLE_RO_TPI
                + " WHERE " + roColumns.superZId + " = ? AND " + roColumns.superDId + " = ?"
                + " LIMIT 1";

        try (Cursor c = db.rawQuery(lookupSql, new String[]{superZId, superDId})) {
            if (!c.moveToFirst()) return null;
            String tudu = c.getString(0);
            Integer vyhybka = readInt(c, 1);
            if (tudu == null || tudu.isEmpty() || vyhybka == null) return null;
            double dist = haversineM(latitude, longitude, bestLat, bestLon);
            return new GpsMatch(superZId, superDId, tudu, vyhybka, bestLat, bestLon, dist);
        }
    }

    /**
     * GPS bod pro konkrétní TUDU a výhybku – pro testovací režim bez fyzické polohy u koleje.
     */
    public GpsMatch findMatchForTuduVyhybka(String tudu, int vyhybka) {
        if (tudu == null || tudu.isEmpty()) return null;

        String lookupSql = "SELECT " + roColumns.superZId + ", " + roColumns.superDId
                + " FROM " + TABLE_RO_TPI
                + " WHERE " + roColumns.tudu + " = ? AND " + roColumns.vyhybka + " = ?"
                + " LIMIT 1";

        String superZId = null;
        String superDId = null;
        try (Cursor c = db.rawQuery(lookupSql, new String[]{tudu, String.valueOf(vyhybka)})) {
            if (!c.moveToFirst()) return null;
            superZId = c.getString(0);
            superDId = c.getString(1);
        }
        if (superZId == null || superDId == null) return null;

        String gpsSql = "SELECT " + gpsColumns.latitude + ", " + gpsColumns.longitude
                + " FROM " + TABLE_GPS_KM
                + " WHERE " + gpsColumns.superZId + " = ? AND " + gpsColumns.superDId + " = ?"
                + " AND " + gpsColumns.latitude + " IS NOT NULL"
                + " AND " + gpsColumns.longitude + " IS NOT NULL"
                + " LIMIT 1";

        double lat;
        double lon;
        try (Cursor c = db.rawQuery(gpsSql, new String[]{superZId, superDId})) {
            if (!c.moveToFirst()) return null;
            lat = c.getDouble(0);
            lon = c.getDouble(1);
        }

        return new GpsMatch(superZId, superDId, tudu, vyhybka, lat, lon, 0);
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
