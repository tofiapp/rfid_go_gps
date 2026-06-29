package com.rfidw.app.data;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    private static final class RoIndexEntry {
        final String tudu;
        final int vyhybka;

        RoIndexEntry(String tudu, int vyhybka) {
            this.tudu = tudu;
            this.vyhybka = vyhybka;
        }
    }

    private static final class GpsIndexEntry {
        final String pairKey;
        final double latitude;
        final double longitude;

        GpsIndexEntry(String pairKey, double latitude, double longitude) {
            this.pairKey = pairKey;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    private final SQLiteDatabase db;
    private final GpsColumns gpsColumns;
    private final RoColumns roColumns;
    private final Map<String, RoIndexEntry> roByPairKey;
    private final List<GpsIndexEntry> gpsIndex;

    private DzsDatabase(SQLiteDatabase db, GpsColumns gpsColumns, RoColumns roColumns,
                        Map<String, RoIndexEntry> roByPairKey, List<GpsIndexEntry> gpsIndex) {
        this.db = db;
        this.gpsColumns = gpsColumns;
        this.roColumns = roColumns;
        this.roByPairKey = roByPairKey;
        this.gpsIndex = gpsIndex;
    }

    public static DzsDatabase open(String path) throws Exception {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(
                path, null, SQLiteDatabase.OPEN_READONLY);
        try {
            GpsColumns gpsColumns = GpsColumns.resolve(db, TABLE_GPS_KM);
            RoColumns roColumns = RoColumns.resolve(db, TABLE_RO_TPI);
            Map<String, RoIndexEntry> roByPairKey = buildRoIndex(db, roColumns);
            List<GpsIndexEntry> gpsIndex = buildGpsIndex(db, gpsColumns, roByPairKey);
            return new DzsDatabase(db, gpsColumns, roColumns, roByPairKey, gpsIndex);
        } catch (Exception e) {
            db.close();
            throw e;
        }
    }

    /** Počet unikátních TUDU kódů v indexu (bez plného načtení výhybek). */
    public int countDistinctTudu() {
        Set<String> codes = new HashSet<>();
        for (RoIndexEntry entry : roByPairKey.values()) {
            codes.add(entry.tudu);
        }
        return codes.size();
    }

    /** Všechny TUDU a výhybky z DZS_SUPER_RO_TPI (pro ruční výběr). */
    public List<Tudu> loadAllTudu() {
        return loadTuduForCodes(null);
    }

    /** TUDU a výhybky jen pro zadané kódy; {@code null} = všechny. */
    public List<Tudu> loadTuduForCodes(Collection<String> codes) {
        Map<String, Tudu> map = new LinkedHashMap<>();
        String vyhybkaExpr = roColumns.vyhybkaSelectExpr(null);
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(roColumns.tudu).append(", ").append(vyhybkaExpr);
        if (roColumns.castMin != null) sql.append(", ").append(roColumns.castMin);
        if (roColumns.castMax != null) sql.append(", ").append(roColumns.castMax);
        sql.append(" FROM ").append(TABLE_RO_TPI)
                .append(" WHERE ").append(roColumns.tudu).append(" IS NOT NULL AND ")
                .append(roColumns.tudu).append(" <> ''")
                .append(" AND ").append(vyhybkaExpr).append(" IS NOT NULL");
        String[] args = null;
        if (codes != null && !codes.isEmpty()) {
            Set<String> unique = new HashSet<>(codes);
            StringBuilder placeholders = new StringBuilder();
            args = new String[unique.size()];
            int i = 0;
            for (String code : unique) {
                if (i > 0) placeholders.append(", ");
                placeholders.append('?');
                args[i++] = code;
            }
            sql.append(" AND ").append(roColumns.tudu).append(" IN (").append(placeholders).append(')');
        }
        sql.append(" ORDER BY ").append(roColumns.tudu).append(", ").append(vyhybkaExpr);

        try (Cursor c = db.rawQuery(sql.toString(), args)) {
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
     * Najde nejbližší bod v paměťovém indexu GPS bodů, který má odpovídající záznam
     * v DZS_SUPER_RO_TPI (SUPER_Z_ID + SUPER_D_ID). Index se sestaví při otevření DB.
     */
    public GpsMatch findNearest(double latitude, double longitude) {
        if (gpsIndex.isEmpty()) return null;

        GpsIndexEntry bestGps = null;
        RoIndexEntry bestRo = null;
        double bestDistSq = Double.MAX_VALUE;
        double cosLat = Math.cos(Math.toRadians(latitude));

        for (GpsIndexEntry gps : gpsIndex) {
            RoIndexEntry ro = roByPairKey.get(gps.pairKey);
            if (ro == null) continue;
            double dLat = gps.latitude - latitude;
            double dLon = (gps.longitude - longitude) * cosLat;
            double distSq = dLat * dLat + dLon * dLon;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestGps = gps;
                bestRo = ro;
            }
        }
        if (bestGps == null || bestRo == null) return null;

        String[] ids = splitPairKey(bestGps.pairKey);
        double dist = haversineM(latitude, longitude, bestGps.latitude, bestGps.longitude);
        return new GpsMatch(ids[0], ids[1], bestRo.tudu, bestRo.vyhybka,
                bestGps.latitude, bestGps.longitude, dist);
    }

    /**
     * Nejbližší body pro každý unikátní TUDU kód, seřazené podle vzdálenosti.
     * Vrací nejvýše {@code limit} záznamů (např. 10 nejbližších TUDU).
     */
    public List<GpsMatch> findNearestDistinctTudu(double latitude, double longitude, int limit) {
        if (gpsIndex.isEmpty() || limit <= 0) return Collections.emptyList();

        Map<String, GpsMatch> bestByTudu = new HashMap<>();
        double cosLat = Math.cos(Math.toRadians(latitude));

        for (GpsIndexEntry gps : gpsIndex) {
            RoIndexEntry ro = roByPairKey.get(gps.pairKey);
            if (ro == null) continue;
            double dLat = gps.latitude - latitude;
            double dLon = (gps.longitude - longitude) * cosLat;
            double distSq = dLat * dLat + dLon * dLon;

            GpsMatch existing = bestByTudu.get(ro.tudu);
            if (existing != null) {
                double existingDistSq = approximateDistSq(
                        latitude, longitude, existing.latitude, existing.longitude, cosLat);
                if (distSq >= existingDistSq) continue;
            }

            String[] ids = splitPairKey(gps.pairKey);
            double dist = haversineM(latitude, longitude, gps.latitude, gps.longitude);
            bestByTudu.put(ro.tudu, new GpsMatch(ids[0], ids[1], ro.tudu, ro.vyhybka,
                    gps.latitude, gps.longitude, dist));
        }

        List<GpsMatch> sorted = new ArrayList<>(bestByTudu.values());
        sorted.sort(Comparator.comparingDouble(m -> m.distanceM));
        if (sorted.size() <= limit) return sorted;
        return new ArrayList<>(sorted.subList(0, limit));
    }

    private static double approximateDistSq(double lat, double lon, double targetLat, double targetLon,
                                            double cosLat) {
        double dLat = targetLat - lat;
        double dLon = (targetLon - lon) * cosLat;
        return dLat * dLat + dLon * dLon;
    }

    private static Map<String, RoIndexEntry> buildRoIndex(SQLiteDatabase db, RoColumns roColumns) {
        Map<String, RoIndexEntry> map = new HashMap<>();
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(roColumns.superZId).append(", ").append(roColumns.superDId).append(", ")
                .append(roColumns.tudu).append(", ").append(roColumns.vyhybka);
        if (roColumns.vyhybkaFallback != null) {
            sql.append(", ").append(roColumns.vyhybkaFallback);
        }
        sql.append(" FROM ").append(TABLE_RO_TPI);

        try (Cursor c = db.rawQuery(sql.toString(), null)) {
            while (c.moveToNext()) {
                String superZId = readId(c, 0);
                String superDId = readId(c, 1);
                String tudu = readTrimmedText(c, 2);
                Integer vyhybka = readVyhybka(c, 3, roColumns.vyhybkaFallback != null ? 4 : -1);
                if (superZId == null || superDId == null || tudu == null || vyhybka == null) {
                    continue;
                }
                map.put(pairKey(superZId, superDId), new RoIndexEntry(tudu, vyhybka));
            }
        }
        return map;
    }

    private static List<GpsIndexEntry> buildGpsIndex(SQLiteDatabase db, GpsColumns gpsColumns,
                                                     Map<String, RoIndexEntry> roByPairKey) {
        // Jeden reprezentativní bod na SUPER_Z_ID|SUPER_D_ID – GPS tabulka má mnoho km bodů na výhybku.
        Map<String, GpsIndexEntry> byPairKey = new HashMap<>();
        String sql = "SELECT " + gpsColumns.superZId + ", " + gpsColumns.superDId + ", "
                + gpsColumns.latitude + ", " + gpsColumns.longitude
                + " FROM " + TABLE_GPS_KM;

        try (Cursor c = db.rawQuery(sql, null)) {
            while (c.moveToNext()) {
                String superZId = readId(c, 0);
                String superDId = readId(c, 1);
                Double lat = readDouble(c, 2);
                Double lon = readDouble(c, 3);
                if (superZId == null || superDId == null || lat == null || lon == null) {
                    continue;
                }
                String key = pairKey(superZId, superDId);
                if (!roByPairKey.containsKey(key)) continue;
                byPairKey.putIfAbsent(key, new GpsIndexEntry(key, lat, lon));
            }
        }
        return new ArrayList<>(byPairKey.values());
    }

    private static String pairKey(String superZId, String superDId) {
        return superZId + "|" + superDId;
    }

    private static String[] splitPairKey(String pairKey) {
        int sep = pairKey.indexOf('|');
        if (sep < 0) return new String[]{pairKey, ""};
        return new String[]{pairKey.substring(0, sep), pairKey.substring(sep + 1)};
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
        List<GpsPoint> out = new ArrayList<>(gpsIndex.size());
        for (GpsIndexEntry gps : gpsIndex) {
            RoIndexEntry ro = roByPairKey.get(gps.pairKey);
            String label = ro == null ? "" : ro.tudu + " · výhybka " + ro.vyhybka;
            out.add(new GpsPoint(gps.latitude, gps.longitude, label));
        }
        out.sort((a, b) -> {
            int latCmp = Double.compare(a.latitude, b.latitude);
            return latCmp != 0 ? latCmp : Double.compare(a.longitude, b.longitude);
        });
        return out;
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
        if (trimmed.isEmpty()) return null;
        try {
            double numeric = Double.parseDouble(trimmed.replace(',', '.'));
            if (!Double.isNaN(numeric) && !Double.isInfinite(numeric)) {
                long asLong = Math.round(numeric);
                if (Math.abs(numeric - asLong) < 1e-6) {
                    return String.valueOf(asLong);
                }
            }
        } catch (NumberFormatException ignored) {
        }
        return trimmed;
    }

    private static String readTrimmedText(Cursor c, int index) {
        if (c.isNull(index)) return null;
        String raw = c.getString(index);
        if (raw == null) return null;
        raw = raw.trim();
        return raw.isEmpty() ? null : raw;
    }

    private static Integer readVyhybka(Cursor c, int primaryIndex, int fallbackIndex) {
        Integer primary = readInt(c, primaryIndex);
        if (primary != null) return primary;
        if (fallbackIndex < 0) return null;
        return readInt(c, fallbackIndex);
    }

    private static Integer readInt(Cursor c, int index) {
        if (c.isNull(index)) return null;
        try {
            return c.getInt(index);
        } catch (Exception e) {
            try {
                return (int) Math.round(c.getDouble(index));
            } catch (Exception ignored) {
            }
            try {
                String raw = c.getString(index);
                if (raw == null) return null;
                raw = raw.trim().replace(',', '.');
                if (raw.isEmpty()) return null;
                return (int) Math.round(Double.parseDouble(raw));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static Double readDouble(Cursor c, int index) {
        if (c.isNull(index)) return null;
        try {
            return c.getDouble(index);
        } catch (Exception e) {
            try {
                String raw = c.getString(index);
                if (raw == null) return null;
                raw = raw.trim().replace(',', '.');
                if (raw.isEmpty()) return null;
                return Double.parseDouble(raw);
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

        /** Číselný výraz pro šířku – CAST + desetinná čárka, aby fungoval text i REAL. */
        String latitudeExpr(String tableAlias) {
            return coordExpr(tableAlias, latitude);
        }

        String longitudeExpr(String tableAlias) {
            return coordExpr(tableAlias, longitude);
        }

        static GpsColumns resolve(SQLiteDatabase db, String table) throws Exception {
            List<String> cols = tableColumns(db, table);
            if (cols.isEmpty()) {
                throw new Exception("Tabulka " + table + " neexistuje");
            }
            String superZId = requireColumn(cols, "SUPER_Z_ID");
            String superDId = requireColumn(cols, "SUPER_D_ID");
            // DZS_SUPERTRA_GPS_KM používá primárně LAT / LON (někdy LAN místo LAT)
            String lat = findRequiredColumn(cols, "LAT", "LAN", "LATITUDE", "GPS_LAT", "SIRKA", "GPS_SIRKA", "Y");
            String lon = findRequiredColumn(cols, "LON", "LONGITUDE", "LNG", "GPS_LON", "DELKA", "GPS_DELKA", "X");
            return new GpsColumns(superZId, superDId, lat, lon);
        }
    }

    private static String coordExpr(String tableAlias, String column) {
        String qualified = tableAlias == null || tableAlias.isEmpty()
                ? column : tableAlias + "." + column;
        return "CAST(REPLACE(" + qualified + ", ',', '.') AS REAL)";
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
            String primary = "NULLIF(TRIM(CAST(" + prefix + vyhybka + " AS TEXT)), '')";
            if (vyhybkaFallback == null) {
                return primary;
            }
            String fallback = "NULLIF(TRIM(CAST(" + prefix + vyhybkaFallback + " AS TEXT)), '')";
            return "COALESCE(" + primary + ", " + fallback + ")";
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
