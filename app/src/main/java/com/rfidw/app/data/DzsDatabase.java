package com.rfidw.app.data;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.function.Consumer;

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
    private static final String TEMP_RO_PAIRS = "_dzs_ro_pairs";

    /** Průběh otevírání databáze (fáze + odhad procent 0–100, nebo -1). */
    public interface OpenProgressListener {
        void onProgress(String phase, int percent);
    }

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

    /**
     * Prostorová mřížka pro rychlé vyhledání nejbližšího bodu bez O(n) průchodu celým seznamem.
     * Velikost buňky ~0,005° (~500 m) – při hledání se postupně rozšiřuje okolí.
     */
    private static final class SpatialGrid {
        private static final double CELL_DEG = 0.005;
        private static final int MAX_RING = 40;

        private final Map<Long, List<GpsIndexEntry>> cells = new HashMap<>();
        private final List<GpsIndexEntry> allPoints;

        SpatialGrid(List<GpsIndexEntry> points) {
            this.allPoints = points;
            for (GpsIndexEntry gps : points) {
                cells.computeIfAbsent(cellKey(gps.latitude, gps.longitude), k -> new ArrayList<>())
                        .add(gps);
            }
        }

        void forEachNearest(double latitude, double longitude, Consumer<GpsIndexEntry> consumer) {
            if (allPoints.isEmpty()) return;
            int latCell = (int) Math.floor(latitude / CELL_DEG);
            int lonCell = (int) Math.floor(longitude / CELL_DEG);
            double bestDistSq = Double.MAX_VALUE;
            double cosLat = Math.cos(Math.toRadians(latitude));

            for (int ring = 0; ring <= MAX_RING; ring++) {
                for (int dLat = -ring; dLat <= ring; dLat++) {
                    for (int dLon = -ring; dLon <= ring; dLon++) {
                        if (ring > 0 && Math.abs(dLat) < ring && Math.abs(dLon) < ring) {
                            continue;
                        }
                        long key = packCell(latCell + dLat, lonCell + dLon);
                        List<GpsIndexEntry> bucket = cells.get(key);
                        if (bucket == null) continue;
                        for (GpsIndexEntry gps : bucket) {
                            double dLatM = gps.latitude - latitude;
                            double dLonM = (gps.longitude - longitude) * cosLat;
                            double distSq = dLatM * dLatM + dLonM * dLonM;
                            if (distSq < bestDistSq) bestDistSq = distSq;
                            consumer.accept(gps);
                        }
                    }
                }
                if (bestDistSq < Double.MAX_VALUE) {
                    double ringBoundDeg = (ring + 1) * CELL_DEG;
                    if (bestDistSq < ringBoundDeg * ringBoundDeg) return;
                }
            }
            if (bestDistSq < Double.MAX_VALUE) return;
            for (GpsIndexEntry gps : allPoints) consumer.accept(gps);
        }

        private static long cellKey(double latitude, double longitude) {
            return packCell((int) Math.floor(latitude / CELL_DEG),
                    (int) Math.floor(longitude / CELL_DEG));
        }

        private static long packCell(int latCell, int lonCell) {
            return ((long) latCell << 32) | (lonCell & 0xFFFFFFFFL);
        }
    }

    private final SQLiteDatabase db;
    private final GpsColumns gpsColumns;
    private final RoColumns roColumns;
    private final Map<String, RoIndexEntry> roByPairKey;
    private final List<GpsIndexEntry> gpsIndex;
    private final SpatialGrid spatialGrid;

    private DzsDatabase(SQLiteDatabase db, GpsColumns gpsColumns, RoColumns roColumns,
                        Map<String, RoIndexEntry> roByPairKey, List<GpsIndexEntry> gpsIndex) {
        this.db = db;
        this.gpsColumns = gpsColumns;
        this.roColumns = roColumns;
        this.roByPairKey = roByPairKey;
        this.gpsIndex = gpsIndex;
        this.spatialGrid = new SpatialGrid(gpsIndex);
    }

    public static DzsDatabase open(String path) throws Exception {
        return open(path, null, null);
    }

    public static DzsDatabase open(String path, File cacheDir, OpenProgressListener listener) throws Exception {
        File dbFile = resolveWritableDatabaseFile(path, cacheDir, listener);
        SQLiteDatabase db = SQLiteDatabase.openDatabase(
                dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        try {
            applyReadPragmas(db);
            report(listener, "Kontrola schématu", 5);
            GpsColumns gpsColumns = GpsColumns.resolve(db, TABLE_GPS_KM);
            RoColumns roColumns = RoColumns.resolve(db, TABLE_RO_TPI);

            DzsIndexCache.LoadedIndex cached = null;
            if (cacheDir != null) {
                report(listener, "Načítám cache indexu", 10);
                cached = DzsIndexCache.tryLoad(dbFile, new File(cacheDir, "dzs_index"));
            }

            Map<String, RoIndexEntry> roByPairKey;
            List<GpsIndexEntry> gpsIndex;
            if (cached != null) {
                roByPairKey = convertRoIndex(cached.roByPairKey);
                gpsIndex = convertGpsIndex(cached.gpsIndex);
                report(listener, "Cache indexu načtena", 90);
            } else {
                report(listener, "Indexuji výhybky", 20);
                roByPairKey = buildRoIndex(db, roColumns);
                report(listener, "Indexuji GPS body", 45);
                gpsIndex = buildGpsIndex(db, gpsColumns, roByPairKey);
                report(listener, "Ukládám cache", 85);
                if (cacheDir != null) {
                    saveIndexCache(dbFile, cacheDir, roByPairKey, gpsIndex);
                }
            }
            report(listener, "Hotovo", 100);
            return new DzsDatabase(db, gpsColumns, roColumns, roByPairKey, gpsIndex);
        } catch (Exception e) {
            db.close();
            throw e;
        }
    }

    /**
     * SQLite potřebuje zapisovatelný adresář pro journal/WAL a dočasné tabulky při indexaci.
     * Soubor ze Stažených nebo jiného sdíleného úložiště proto zkopírujeme do cache aplikace.
     */
    private static File resolveWritableDatabaseFile(String path, File cacheDir,
                                                    OpenProgressListener listener) throws Exception {
        File source = new File(path);
        if (!source.isFile()) {
            throw new Exception("Databáze nenalezena: " + path);
        }
        if (cacheDir == null || cacheDir.equals(source.getParentFile())) {
            return source;
        }
        report(listener, "Kopíruji databázi do cache", 2);
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new Exception("Nelze vytvořit cache pro databázi");
        }
        File cached = new File(cacheDir, "sqlite_"
                + Long.toHexString(source.length()) + "_"
                + Long.toHexString(source.lastModified()) + ".db");
        if (!cached.isFile() || cached.length() != source.length()
                || cached.lastModified() < source.lastModified()) {
            copyFile(source, cached);
        }
        return cached;
    }

    private static void copyFile(File source, File dest) throws IOException {
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static void applyReadPragmas(SQLiteDatabase db) {
        try {
            db.execSQL("PRAGMA cache_size = -64000");
            db.execSQL("PRAGMA temp_store = MEMORY");
        } catch (Exception ignored) {
        }
    }

    private static void report(OpenProgressListener listener, String phase, int percent) {
        if (listener != null) listener.onProgress(phase, percent);
    }

    private static Map<String, RoIndexEntry> convertRoIndex(Map<String, DzsIndexCache.RoEntry> cached) {
        Map<String, RoIndexEntry> map = new HashMap<>(cached.size());
        for (Map.Entry<String, DzsIndexCache.RoEntry> e : cached.entrySet()) {
            map.put(e.getKey(), new RoIndexEntry(e.getValue().tudu, e.getValue().vyhybka));
        }
        return map;
    }

    private static List<GpsIndexEntry> convertGpsIndex(List<DzsIndexCache.GpsEntry> cached) {
        List<GpsIndexEntry> list = new ArrayList<>(cached.size());
        for (DzsIndexCache.GpsEntry e : cached) {
            list.add(new GpsIndexEntry(e.pairKey, e.latitude, e.longitude));
        }
        return list;
    }

    private static void saveIndexCache(File dbFile, File cacheDir,
                                       Map<String, RoIndexEntry> roByPairKey,
                                       List<GpsIndexEntry> gpsIndex) {
        Map<String, DzsIndexCache.RoEntry> roOut = new HashMap<>(roByPairKey.size());
        for (Map.Entry<String, RoIndexEntry> e : roByPairKey.entrySet()) {
            RoIndexEntry ro = e.getValue();
            roOut.put(e.getKey(), new DzsIndexCache.RoEntry(ro.tudu, ro.vyhybka));
        }
        List<DzsIndexCache.GpsEntry> gpsOut = new ArrayList<>(gpsIndex.size());
        for (GpsIndexEntry gps : gpsIndex) {
            gpsOut.add(new DzsIndexCache.GpsEntry(gps.pairKey, gps.latitude, gps.longitude));
        }
        DzsIndexCache.save(dbFile, new File(cacheDir, "dzs_index"), roOut, gpsOut);
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
        roColumns.appendPolohaFilter(sql, null);
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

        final GpsIndexEntry[] bestGps = {null};
        final RoIndexEntry[] bestRo = {null};
        final double[] bestDistSq = {Double.MAX_VALUE};
        double cosLat = Math.cos(Math.toRadians(latitude));

        spatialGrid.forEachNearest(latitude, longitude, gps -> {
            RoIndexEntry ro = roByPairKey.get(gps.pairKey);
            if (ro == null) return;
            double dLat = gps.latitude - latitude;
            double dLon = (gps.longitude - longitude) * cosLat;
            double distSq = dLat * dLat + dLon * dLon;
            if (distSq < bestDistSq[0]) {
                bestDistSq[0] = distSq;
                bestGps[0] = gps;
                bestRo[0] = ro;
            }
        });

        if (bestGps[0] == null || bestRo[0] == null) return null;

        String[] ids = splitPairKey(bestGps[0].pairKey);
        double dist = haversineM(latitude, longitude, bestGps[0].latitude, bestGps[0].longitude);
        return new GpsMatch(ids[0], ids[1], bestRo[0].tudu, bestRo[0].vyhybka,
                bestGps[0].latitude, bestGps[0].longitude, dist);
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
        sql.append(" FROM ").append(TABLE_RO_TPI)
                .append(" WHERE 1=1");
        roColumns.appendPolohaFilter(sql, null);

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
        if (roByPairKey.isEmpty()) return new ArrayList<>();
        List<GpsIndexEntry> fromSql = buildGpsIndexSql(db, gpsColumns, roByPairKey);
        if (fromSql != null) return fromSql;
        return buildGpsIndexScan(db, gpsColumns, roByPairKey);
    }

    /**
     * SQL deduplikace: jeden bod na SUPER_Z_ID|SUPER_D_ID, jen páry z indexu výhybek.
     * Filtrování probíhá před GROUP BY, aby se nezpracovávaly miliony irelevantních GPS řádků.
     */
    private static List<GpsIndexEntry> buildGpsIndexSql(SQLiteDatabase db, GpsColumns gpsColumns,
                                                        Map<String, RoIndexEntry> roByPairKey) {
        if (!populateRoPairsTempTable(db, roByPairKey)) return null;

        String latExpr = gpsColumns.latitudeExpr("g");
        String lonExpr = gpsColumns.longitudeExpr("g");
        String sql = "SELECT g." + gpsColumns.superZId + ", g." + gpsColumns.superDId + ", "
                + latExpr + ", " + lonExpr
                + " FROM " + TABLE_GPS_KM + " g"
                + " INNER JOIN ("
                + "   SELECT g2." + gpsColumns.superZId + ", g2." + gpsColumns.superDId
                + ", MIN(g2.rowid) AS rid"
                + "   FROM " + TABLE_GPS_KM + " g2"
                + "   INNER JOIN " + TEMP_RO_PAIRS + " rp"
                + "     ON g2." + gpsColumns.superZId + " = rp.super_z_id"
                + "     AND g2." + gpsColumns.superDId + " = rp.super_d_id"
                + "   GROUP BY g2." + gpsColumns.superZId + ", g2." + gpsColumns.superDId
                + " ) agg ON g.rowid = agg.rid";

        return readGpsIndexCursor(db, sql, null);
    }

    /** Záložní postup – stejný filtr přes dočasnou tabulku, bez CAST výrazů pro souřadnice. */
    private static List<GpsIndexEntry> buildGpsIndexScan(SQLiteDatabase db, GpsColumns gpsColumns,
                                                       Map<String, RoIndexEntry> roByPairKey) {
        if (!populateRoPairsTempTable(db, roByPairKey)) {
            return buildGpsIndexScanByKey(db, gpsColumns, roByPairKey);
        }

        String sql = "SELECT g." + gpsColumns.superZId + ", g." + gpsColumns.superDId + ", "
                + gpsColumns.latitude + ", " + gpsColumns.longitude
                + " FROM " + TABLE_GPS_KM + " g"
                + " INNER JOIN ("
                + "   SELECT g2." + gpsColumns.superZId + ", g2." + gpsColumns.superDId
                + ", MIN(g2.rowid) AS rid"
                + "   FROM " + TABLE_GPS_KM + " g2"
                + "   INNER JOIN " + TEMP_RO_PAIRS + " rp"
                + "     ON g2." + gpsColumns.superZId + " = rp.super_z_id"
                + "     AND g2." + gpsColumns.superDId + " = rp.super_d_id"
                + "   GROUP BY g2." + gpsColumns.superZId + ", g2." + gpsColumns.superDId
                + " ) agg ON g.rowid = agg.rid";

        List<GpsIndexEntry> fromSql = readGpsIndexCursor(db, sql, null);
        return fromSql != null ? fromSql : buildGpsIndexScanByKey(db, gpsColumns, roByPairKey);
    }

    private static List<GpsIndexEntry> readGpsIndexCursor(SQLiteDatabase db, String sql, String[] args) {
        List<GpsIndexEntry> out = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, args)) {
            while (c.moveToNext()) {
                String superZId = readId(c, 0);
                String superDId = readId(c, 1);
                Double lat = readDouble(c, 2);
                Double lon = readDouble(c, 3);
                if (superZId == null || superDId == null || lat == null || lon == null) {
                    continue;
                }
                out.add(new GpsIndexEntry(pairKey(superZId, superDId), lat, lon));
            }
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean populateRoPairsTempTable(SQLiteDatabase db,
                                                  Map<String, RoIndexEntry> roByPairKey) {
        try {
            db.execSQL("CREATE TEMP TABLE IF NOT EXISTS " + TEMP_RO_PAIRS
                    + " (super_z_id TEXT NOT NULL, super_d_id TEXT NOT NULL,"
                    + " PRIMARY KEY (super_z_id, super_d_id))");
            db.execSQL("DELETE FROM " + TEMP_RO_PAIRS);
        } catch (Exception ignored) {
            return false;
        }

        SQLiteStatement insert = db.compileStatement(
                "INSERT OR IGNORE INTO " + TEMP_RO_PAIRS + " (super_z_id, super_d_id) VALUES (?, ?)");
        db.beginTransaction();
        try {
            for (String key : roByPairKey.keySet()) {
                String[] ids = splitPairKey(key);
                insert.clearBindings();
                insert.bindString(1, ids[0]);
                insert.bindString(2, ids[1]);
                insert.executeInsert();
            }
            db.setTransactionSuccessful();
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            db.endTransaction();
        }
    }

    /** Poslední záložní postup – dotaz po jednom páru ID (pomalé, ale bez plného skenu GPS tabulky). */
    private static List<GpsIndexEntry> buildGpsIndexScanByKey(SQLiteDatabase db, GpsColumns gpsColumns,
                                                            Map<String, RoIndexEntry> roByPairKey) {
        List<GpsIndexEntry> out = new ArrayList<>();
        String sql = "SELECT " + gpsColumns.latitude + ", " + gpsColumns.longitude
                + " FROM " + TABLE_GPS_KM
                + " WHERE " + gpsColumns.superZId + " = ? AND " + gpsColumns.superDId + " = ?"
                + " LIMIT 1";

        for (String key : roByPairKey.keySet()) {
            String[] ids = splitPairKey(key);
            try (Cursor c = db.rawQuery(sql, new String[]{ids[0], ids[1]})) {
                if (!c.moveToFirst()) continue;
                Double lat = readDouble(c, 0);
                Double lon = readDouble(c, 1);
                if (lat == null || lon == null) continue;
                out.add(new GpsIndexEntry(key, lat, lon));
            } catch (Exception ignored) {
            }
        }
        return out;
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
        final String poloha;

        RoColumns(String superZId, String superDId, String tudu, String vyhybka,
                  String vyhybkaFallback, String castMin, String castMax, String poloha) {
            this.superZId = superZId;
            this.superDId = superDId;
            this.tudu = tudu;
            this.vyhybka = vyhybka;
            this.vyhybkaFallback = vyhybkaFallback;
            this.castMin = castMin;
            this.castMax = castMax;
            this.poloha = poloha;
        }

        /** Vyřadí řádky s prázdnou POLOHOU nebo textem „NULL“. */
        void appendPolohaFilter(StringBuilder sql, String tableAlias) {
            if (poloha == null) return;
            String prefix = tableAlias == null || tableAlias.isEmpty()
                    ? "" : tableAlias + ".";
            String expr = "TRIM(CAST(" + prefix + poloha + " AS TEXT))";
            sql.append(" AND ").append(prefix).append(poloha).append(" IS NOT NULL")
                    .append(" AND ").append(expr).append(" <> ''")
                    .append(" AND UPPER(").append(expr).append(") <> 'NULL'");
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
                    findOptionalColumn(cols, "CAST_MAX", "CASTMAX"),
                    findOptionalColumn(cols, "POLOHA")
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
