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
import java.util.function.IntConsumer;

/**
 * SQLite zdroj TUDU / výhybek z tabulek DZS_SUPERTRA_GPS_KM a DZS_SUPER_RO_TPI.
 *
 * Postup:
 * 1) Při indexaci pro každou výhybku najít km bod, jehož KM_EXT nejlépe sedí na (OD+DO)/2 v rámci páru ID.
 * 2) Za běhu najít nejbližší předpočítanou souřadnici výhybky.
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
        /** Střed sloupců OD a DO; null pokud nelze spočítat. */
        final Double midKm;

        RoIndexEntry(String tudu, int vyhybka, Double midKm) {
            this.tudu = tudu;
            this.vyhybka = vyhybka;
            this.midKm = midKm;
        }
    }

    /** Kilometrický bod GPS tabulky – dočasně při indexaci, neukládá se do cache. */
    private static final class KmGpsPoint {
        final double km;
        final double latitude;
        final double longitude;

        KmGpsPoint(double km, double latitude, double longitude) {
            this.km = km;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    private static final class SpatialGrid {
        private static final double CELL_DEG = 0.005;
        private static final int MAX_RING = 40;

        private final VyhybkaGpsStore store;
        private final Map<Long, int[]> cells = new HashMap<>();
        private final int[] allIndices;

        SpatialGrid(VyhybkaGpsStore store) {
            this.store = store;
            this.allIndices = range(store.size());
            Map<Long, List<Integer>> buckets = new HashMap<>();
            for (int i = 0; i < store.size(); i++) {
                buckets.computeIfAbsent(cellKey(store.latitudeAt(i), store.longitudeAt(i)),
                        k -> new ArrayList<>()).add(i);
            }
            for (Map.Entry<Long, List<Integer>> e : buckets.entrySet()) {
                int[] indices = new int[e.getValue().size()];
                for (int i = 0; i < indices.length; i++) {
                    indices[i] = e.getValue().get(i);
                }
                cells.put(e.getKey(), indices);
            }
        }

        void forEachNearest(double latitude, double longitude, IntConsumer consumer) {
            if (store.isEmpty()) return;
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
                        int[] bucket = cells.get(key);
                        if (bucket == null) continue;
                        for (int idx : bucket) {
                            double dLatM = store.latitudeAt(idx) - latitude;
                            double dLonM = (store.longitudeAt(idx) - longitude) * cosLat;
                            double distSq = dLatM * dLatM + dLonM * dLonM;
                            if (distSq < bestDistSq) bestDistSq = distSq;
                            consumer.accept(idx);
                        }
                    }
                }
                if (bestDistSq < Double.MAX_VALUE) {
                    double ringBoundDeg = (ring + 1) * CELL_DEG;
                    if (bestDistSq < ringBoundDeg * ringBoundDeg) return;
                }
            }
            if (bestDistSq < Double.MAX_VALUE) return;
            for (int idx : allIndices) consumer.accept(idx);
        }

        private static int[] range(int size) {
            int[] out = new int[size];
            for (int i = 0; i < size; i++) out[i] = i;
            return out;
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
    private final Map<String, List<RoIndexEntry>> roByPairKey;
    private final VyhybkaGpsStore vyhybkaGpsStore;
    private volatile SpatialGrid spatialGrid;

    private DzsDatabase(SQLiteDatabase db, GpsColumns gpsColumns, RoColumns roColumns,
                        Map<String, List<RoIndexEntry>> roByPairKey, VyhybkaGpsStore vyhybkaGpsStore) {
        this.db = db;
        this.gpsColumns = gpsColumns;
        this.roColumns = roColumns;
        this.roByPairKey = roByPairKey;
        this.vyhybkaGpsStore = vyhybkaGpsStore;
    }

    private SpatialGrid spatialGrid() {
        SpatialGrid grid = spatialGrid;
        if (grid == null) {
            synchronized (this) {
                grid = spatialGrid;
                if (grid == null) {
                    grid = new SpatialGrid(vyhybkaGpsStore);
                    spatialGrid = grid;
                }
            }
        }
        return grid;
    }

    /** Sestaví prostorový index (volat z IO vlákna před návratem do UI). */
    void ensureSpatialIndex(OpenProgressListener listener) {
        if (vyhybkaGpsStore.isEmpty()) {
            report(listener, "Hotovo", 100);
            return;
        }
        report(listener, "Připravuji vyhledávání", 93);
        spatialGrid();
        report(listener, "Hotovo", 100);
    }

    public static DzsDatabase open(String path) throws Exception {
        return open(path, null, null);
    }

    public static DzsDatabase open(String path, File cacheDir, OpenProgressListener listener) throws Exception {
        File sourceFile = new File(path);
        File dbFile = resolveWritableDatabaseFile(sourceFile, cacheDir, listener);
        SQLiteDatabase db = SQLiteDatabase.openDatabase(
                dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        try {
            applyReadPragmas(db);
            report(listener, "Kontrola schématu", 5);
            GpsColumns gpsColumns = GpsColumns.resolve(db, TABLE_GPS_KM);
            RoColumns roColumns = RoColumns.resolve(db, TABLE_RO_TPI);
            ensureGpsPairIndex(db, gpsColumns);

            String contentHash = null;
            DzsIndexCache.LoadedIndex cached = null;
            if (cacheDir != null) {
                report(listener, "Kontrola databáze", 8);
                contentHash = DzsIndexCache.computeContentHash(sourceFile);
                report(listener, "Načítám cache indexu", 12);
                cached = DzsIndexCache.tryLoad(sourceFile, contentHash, new File(cacheDir, "dzs_index"));
            }

            VyhybkaGpsStore vyhybkaGpsStore;
            Map<String, List<RoIndexEntry>> roByPairKey;
            if (cached != null) {
                roByPairKey = convertRoIndex(cached.roByPairKey);
                vyhybkaGpsStore = cached.vyhybkaGpsStore;
                report(listener, "Cache indexu načtena", 90);
            } else {
                report(listener, "Indexuji výhybky", 20);
                roByPairKey = buildRoIndex(db, roColumns);
                report(listener, "Indexuji souřadnice výhybek", 50);
                int expectedGpsRows = countGpsRowsForPairs(db, gpsColumns, roByPairKey.keySet());
                vyhybkaGpsStore = buildVyhybkaGpsIndex(
                        db, gpsColumns, roByPairKey, expectedGpsRows, listener);
                report(listener, "Ukládám cache", 85);
                if (cacheDir != null) {
                    if (contentHash == null) {
                        contentHash = DzsIndexCache.computeContentHash(sourceFile);
                    }
                    saveIndexCache(sourceFile, contentHash, cacheDir, roByPairKey, vyhybkaGpsStore, listener);
                }
            }
            DzsDatabase opened = new DzsDatabase(db, gpsColumns, roColumns, roByPairKey, vyhybkaGpsStore);
            opened.ensureSpatialIndex(listener);
            return opened;
        } catch (OutOfMemoryError e) {
            db.close();
            throw e;
        } catch (Exception e) {
            db.close();
            throw e;
        }
    }

    private static File resolveWritableDatabaseFile(File source, File cacheDir,
                                                    OpenProgressListener listener) throws Exception {
        if (!source.isFile()) {
            throw new Exception("Databáze nenalezena: " + source.getAbsolutePath());
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
        if (!cached.isFile() || cached.length() != source.length()) {
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
            db.execSQL("PRAGMA mmap_size = 268435456");
        } catch (Exception ignored) {
        }
    }

    private static void ensureGpsPairIndex(SQLiteDatabase db, GpsColumns gpsColumns) {
        try {
            db.execSQL("CREATE INDEX IF NOT EXISTS _dzs_gps_zd ON " + TABLE_GPS_KM
                    + " (" + gpsColumns.superZId + ", " + gpsColumns.superDId + ")");
        } catch (Exception ignored) {
        }
    }

    private static void report(OpenProgressListener listener, String phase, int percent) {
        if (listener != null) listener.onProgress(phase, percent);
    }

    private static Map<String, List<RoIndexEntry>> convertRoIndex(
            Map<String, List<DzsIndexCache.RoEntry>> cached) {
        Map<String, List<RoIndexEntry>> map = new HashMap<>(cached.size());
        for (Map.Entry<String, List<DzsIndexCache.RoEntry>> e : cached.entrySet()) {
            List<RoIndexEntry> entries = new ArrayList<>(e.getValue().size());
            for (DzsIndexCache.RoEntry ro : e.getValue()) {
                Double midKm = Double.isNaN(ro.midKm) ? null : ro.midKm;
                entries.add(new RoIndexEntry(ro.tudu, ro.vyhybka, midKm));
            }
            map.put(e.getKey(), entries);
        }
        return map;
    }

    private static void saveIndexCache(File dbFile, String contentHash, File cacheDir,
                                       Map<String, List<RoIndexEntry>> roByPairKey,
                                       VyhybkaGpsStore vyhybkaGpsStore,
                                       OpenProgressListener listener) {
        DzsIndexCache.save(dbFile, contentHash, new File(cacheDir, "dzs_index"),
                toRoCache(roByPairKey), vyhybkaGpsStore,
                (written, total) -> {
                    int pct = 85 + (int) ((written * 7L) / Math.max(total, 1));
                    report(listener, cacheProgressPhase(written), Math.min(pct, 92));
                });
    }

    private static Map<String, List<DzsIndexCache.RoEntry>> toRoCache(
            Map<String, List<RoIndexEntry>> roByPairKey) {
        Map<String, List<DzsIndexCache.RoEntry>> map = new HashMap<>(roByPairKey.size());
        for (Map.Entry<String, List<RoIndexEntry>> e : roByPairKey.entrySet()) {
            List<DzsIndexCache.RoEntry> entries = new ArrayList<>(e.getValue().size());
            for (RoIndexEntry ro : e.getValue()) {
                double midKm = ro.midKm != null ? ro.midKm : Double.NaN;
                entries.add(new DzsIndexCache.RoEntry(ro.tudu, ro.vyhybka, midKm));
            }
            map.put(e.getKey(), entries);
        }
        return map;
    }

    public int countDistinctTudu() {
        Set<String> codes = new HashSet<>();
        for (List<RoIndexEntry> entries : roByPairKey.values()) {
            for (RoIndexEntry entry : entries) {
                codes.add(entry.tudu);
            }
        }
        return codes.size();
    }

    public List<Tudu> loadAllTudu() {
        return loadTuduForCodes(null);
    }

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

    /** Najde nejbližší výhybku podle předpočítaných souřadnic (KM_EXT ↔ (OD+DO)/2). */
    public GpsMatch findNearest(double latitude, double longitude) {
        if (vyhybkaGpsStore.isEmpty()) return null;

        final int[] bestIdx = {-1};
        final double[] bestDistSq = {Double.MAX_VALUE};
        double cosLat = Math.cos(Math.toRadians(latitude));

        spatialGrid().forEachNearest(latitude, longitude, idx -> {
            double dLat = vyhybkaGpsStore.latitudeAt(idx) - latitude;
            double dLon = (vyhybkaGpsStore.longitudeAt(idx) - longitude) * cosLat;
            double distSq = dLat * dLat + dLon * dLon;
            if (distSq < bestDistSq[0]) {
                bestDistSq[0] = distSq;
                bestIdx[0] = idx;
            }
        });

        if (bestIdx[0] < 0) return null;
        return vyhybkaToMatch(bestIdx[0], latitude, longitude);
    }

    /** Nejbližší výhybka pro každý unikátní TUDU kód, seřazené podle vzdálenosti. */
    public List<GpsMatch> findNearestDistinctTudu(double latitude, double longitude, int limit) {
        if (limit <= 0) return Collections.emptyList();
        if (vyhybkaGpsStore.isEmpty()) return Collections.emptyList();

        Map<String, GpsMatch> bestByTudu = new HashMap<>();
        double cosLat = Math.cos(Math.toRadians(latitude));

        spatialGrid().forEachNearest(latitude, longitude, idx -> {
            String tudu = vyhybkaGpsStore.tuduAt(idx);
            double dLat = vyhybkaGpsStore.latitudeAt(idx) - latitude;
            double dLon = (vyhybkaGpsStore.longitudeAt(idx) - longitude) * cosLat;
            double distSq = dLat * dLat + dLon * dLon;

            GpsMatch existing = bestByTudu.get(tudu);
            if (existing != null) {
                double existingDistSq = approximateDistSq(
                        latitude, longitude, existing.latitude, existing.longitude, cosLat);
                if (distSq >= existingDistSq) return;
            }

            bestByTudu.put(tudu, vyhybkaToMatch(idx, latitude, longitude));
        });

        List<GpsMatch> sorted = new ArrayList<>(bestByTudu.values());
        sorted.sort(Comparator.comparingDouble(m -> m.distanceM));
        if (sorted.size() <= limit) return sorted;
        return new ArrayList<>(sorted.subList(0, limit));
    }

    /** Pro daný TUDU vrátí nejbližší vzdálenost (m) k jednotlivým výhybkám. */
    public Map<Integer, Double> findVyhybkaDistancesForTudu(String tuduCode,
                                                              double latitude, double longitude) {
        if (tuduCode == null || tuduCode.isEmpty()) {
            return Collections.emptyMap();
        }
        String trimmedTudu = tuduCode.trim();
        if (trimmedTudu.isEmpty()) return Collections.emptyMap();

        Map<Integer, Double> bestByVyhybka = new HashMap<>();
        for (int i = 0; i < vyhybkaGpsStore.size(); i++) {
            if (!trimmedTudu.equals(vyhybkaGpsStore.tuduAt(i))) continue;
            recordVyhybkaDistance(bestByVyhybka, vyhybkaGpsStore.vyhybkaAt(i),
                    latitude, longitude,
                    vyhybkaGpsStore.latitudeAt(i), vyhybkaGpsStore.longitudeAt(i));
        }
        return bestByVyhybka;
    }

    private GpsMatch vyhybkaToMatch(int idx, double userLat, double userLon) {
        String pairKey = vyhybkaGpsStore.pairKeyAt(idx);
        String[] ids = splitPairKey(pairKey);
        double lat = vyhybkaGpsStore.latitudeAt(idx);
        double lon = vyhybkaGpsStore.longitudeAt(idx);
        double dist = haversineM(userLat, userLon, lat, lon);
        return new GpsMatch(ids[0], ids[1], vyhybkaGpsStore.tuduAt(idx),
                vyhybkaGpsStore.vyhybkaAt(idx), lat, lon, dist);
    }

    private static void recordVyhybkaDistance(Map<Integer, Double> bestByVyhybka, int vyhybka,
                                              double latitude, double longitude,
                                              double targetLat, double targetLon) {
        double dist = haversineM(latitude, longitude, targetLat, targetLon);
        Double existing = bestByVyhybka.get(vyhybka);
        if (existing == null || dist < existing) {
            bestByVyhybka.put(vyhybka, dist);
        }
    }

    private static double approximateDistSq(double lat, double lon, double targetLat, double targetLon,
                                            double cosLat) {
        double dLat = targetLat - lat;
        double dLon = (targetLon - lon) * cosLat;
        return dLat * dLat + dLon * dLon;
    }

    /** Jeden průchod RO tabulkou – více výhybek na stejný pár ID je povoleno. */
    private static Map<String, List<RoIndexEntry>> buildRoIndex(SQLiteDatabase db, RoColumns roColumns) {
        Map<String, List<RoIndexEntry>> byPairKey = new HashMap<>();
        String vyhybkaExpr = roColumns.vyhybkaSelectExpr(null);
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(roColumns.superZId).append(", ").append(roColumns.superDId).append(", ")
                .append(roColumns.tudu).append(", ").append(vyhybkaExpr);
        if (roColumns.od != null) sql.append(", ").append(roColumns.od);
        if (roColumns.doCol != null) sql.append(", ").append(roColumns.doCol);
        sql.append(" FROM ").append(TABLE_RO_TPI)
                .append(" WHERE ").append(roColumns.tudu).append(" IS NOT NULL AND ")
                .append(roColumns.tudu).append(" <> ''")
                .append(" AND ").append(vyhybkaExpr).append(" IS NOT NULL");
        roColumns.appendPolohaFilter(sql, null);

        try (Cursor c = db.rawQuery(sql.toString(), null)) {
            while (c.moveToNext()) {
                String superZId = readId(c, 0);
                String superDId = readId(c, 1);
                String tudu = readTrimmedText(c, 2);
                Integer vyhybka = readInt(c, 3);
                if (superZId == null || superDId == null || tudu == null || vyhybka == null) {
                    continue;
                }
                int col = 4;
                Double midKm = null;
                if (roColumns.od != null && roColumns.doCol != null) {
                    Double od = readDouble(c, col++);
                    Double doVal = readDouble(c, col);
                    if (od != null && doVal != null) {
                        midKm = (od + doVal) / 2.0;
                    }
                }
                String key = pairKey(superZId, superDId);
                byPairKey.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new RoIndexEntry(tudu, vyhybka, midKm));
            }
        }
        return byPairKey;
    }

    private static final int GPS_INDEX_PROGRESS_INTERVAL = 50_000;

    /**
     * Předpočítá souřadnici každé výhybky: pro pár ID najde km bod, jehož KM_EXT
     * nejlépe sedí na (OD+DO)/2. GPS tabulka se projde jednou; do cache jde jen výsledek.
     */
    private static VyhybkaGpsStore buildVyhybkaGpsIndex(SQLiteDatabase db, GpsColumns gpsColumns,
                                                        Map<String, List<RoIndexEntry>> roByPairKey,
                                                        int expectedRows, OpenProgressListener listener) {
        if (roByPairKey.isEmpty()) return VyhybkaGpsStore.empty();
        Set<String> pairKeys = roByPairKey.keySet();
        VyhybkaGpsStore fromJoin = buildVyhybkaGpsIndexJoin(
                db, gpsColumns, roByPairKey, pairKeys, expectedRows, listener);
        if (fromJoin != null) return fromJoin;
        int tableRows = countTableRows(db, TABLE_GPS_KM);
        return buildVyhybkaGpsIndexFullScan(
                db, gpsColumns, roByPairKey, pairKeys, expectedRows, tableRows, listener);
    }

    private static VyhybkaGpsStore buildVyhybkaGpsIndexJoin(SQLiteDatabase db, GpsColumns gpsColumns,
                                                            Map<String, List<RoIndexEntry>> roByPairKey,
                                                            Set<String> pairKeys, int expectedRows,
                                                            OpenProgressListener listener) {
        if (!populateRoPairsTempTable(db, pairKeys)) return null;

        String sql = "SELECT g." + gpsColumns.superZId + ", g." + gpsColumns.superDId + ", "
                + gpsColumns.kmExt + ", " + gpsColumns.latitude + ", " + gpsColumns.longitude
                + " FROM " + TABLE_GPS_KM + " g"
                + " INNER JOIN " + TEMP_RO_PAIRS + " rp"
                + "   ON g." + gpsColumns.superZId + " = rp.super_z_id"
                + "   AND g." + gpsColumns.superDId + " = rp.super_d_id"
                + " ORDER BY g." + gpsColumns.superZId + ", g." + gpsColumns.superDId;

        return streamGpsKmForVyhybkaIndex(db, sql, null, roByPairKey, listener,
                null, expectedRows, -1);
    }

    private static VyhybkaGpsStore buildVyhybkaGpsIndexFullScan(SQLiteDatabase db, GpsColumns gpsColumns,
                                                                Map<String, List<RoIndexEntry>> roByPairKey,
                                                                Set<String> pairKeys, int expectedRows,
                                                                int tableRows, OpenProgressListener listener) {
        HashSet<String> pairFilter = new HashSet<>(pairKeys);
        String sql = "SELECT " + gpsColumns.superZId + ", " + gpsColumns.superDId + ", "
                + gpsColumns.kmExt + ", " + gpsColumns.latitude + ", " + gpsColumns.longitude
                + " FROM " + TABLE_GPS_KM
                + " ORDER BY " + gpsColumns.superZId + ", " + gpsColumns.superDId;
        return streamGpsKmForVyhybkaIndex(db, sql, null, roByPairKey, listener,
                pairFilter, expectedRows, tableRows);
    }

    private static int countGpsRowsForPairs(SQLiteDatabase db, GpsColumns gpsColumns, Set<String> pairKeys) {
        if (pairKeys.isEmpty() || !populateRoPairsTempTable(db, pairKeys)) return -1;
        String sql = "SELECT COUNT(*) FROM " + TABLE_GPS_KM + " g"
                + " INNER JOIN " + TEMP_RO_PAIRS + " rp"
                + "   ON g." + gpsColumns.superZId + " = rp.super_z_id"
                + "   AND g." + gpsColumns.superDId + " = rp.super_d_id";
        return readCount(db, sql);
    }

    private static int countTableRows(SQLiteDatabase db, String table) {
        return readCount(db, "SELECT COUNT(*) FROM " + table);
    }

    private static int readCount(SQLiteDatabase db, String sql) {
        try (Cursor c = db.rawQuery(sql, null)) {
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Exception ignored) {
        }
        return -1;
    }

    /**
     * Streamuje km body seřazené podle páru ID; po dokončení každého páru
     * doplní souřadnice výhybek a uvolní body z paměti.
     */
    private static VyhybkaGpsStore streamGpsKmForVyhybkaIndex(
            SQLiteDatabase db, String sql, String[] args,
            Map<String, List<RoIndexEntry>> roByPairKey, OpenProgressListener listener,
            Set<String> pairFilter, int expectedRows, int tableRows) {
        VyhybkaGpsStore.Builder builder = VyhybkaGpsStore.builder();
        try (Cursor c = db.rawQuery(sql, args)) {
            String currentPair = null;
            List<KmGpsPoint> currentPoints = new ArrayList<>();
            int scanned = 0;
            int matchedVyhybky = 0;

            while (c.moveToNext()) {
                scanned++;
                String superZId = readId(c, 0);
                String superDId = readId(c, 1);
                if (superZId == null || superDId == null) continue;
                String key = pairKey(superZId, superDId);
                if (pairFilter != null && !pairFilter.contains(key)) continue;

                Double kmExt = readDouble(c, 2);
                Double lat = readDouble(c, 3);
                Double lon = readDouble(c, 4);
                if (kmExt == null || lat == null || lon == null) continue;

                if (!key.equals(currentPair)) {
                    if (currentPair != null) {
                        matchedVyhybky += flushPairVyhybkaCoords(
                                currentPair, currentPoints, roByPairKey.get(currentPair), builder);
                        currentPoints.clear();
                    }
                    currentPair = key;
                }
                currentPoints.add(new KmGpsPoint(kmExt, lat, lon));

                if (scanned % GPS_INDEX_PROGRESS_INTERVAL == 0) {
                    reportVyhybkaGpsProgress(listener, matchedVyhybky, expectedRows, tableRows, scanned,
                            pairFilter != null);
                }
            }
            if (currentPair != null) {
                matchedVyhybky += flushPairVyhybkaCoords(
                        currentPair, currentPoints, roByPairKey.get(currentPair), builder);
            }
            reportVyhybkaGpsProgress(listener, matchedVyhybky, expectedRows, tableRows, scanned,
                    pairFilter != null);
            if (listener != null && matchedVyhybky > 0) {
                report(listener, vyhybkaGpsProgressPhase(matchedVyhybky, pairFilter != null ? scanned : -1), 84);
            }
            return builder.build();
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int flushPairVyhybkaCoords(String pairKey, List<KmGpsPoint> points,
                                              List<RoIndexEntry> entries,
                                              VyhybkaGpsStore.Builder builder) {
        if (entries == null || entries.isEmpty() || points.isEmpty()) return 0;
        int added = 0;
        for (RoIndexEntry ro : entries) {
            KmGpsPoint hit = matchKmPoint(points, ro.midKm);
            if (hit == null) continue;
            builder.add(pairKey, ro.tudu, ro.vyhybka, hit.latitude, hit.longitude);
            added++;
        }
        return added;
    }

    private static void reportVyhybkaGpsProgress(OpenProgressListener listener, int matchedVyhybky,
                                                 int expectedRows, int tableRows, int scanned,
                                                 boolean fullScan) {
        if (listener == null || scanned <= 0) return;
        int pct;
        if (expectedRows > 0) {
            pct = 50 + (int) Math.min(34L, (scanned * 34L) / expectedRows);
        } else if (fullScan && tableRows > 0) {
            pct = 50 + (int) Math.min(34L, (scanned * 34L) / tableRows);
        } else {
            pct = 50 + Math.min(34, scanned / GPS_INDEX_PROGRESS_INTERVAL);
        }
        report(listener, vyhybkaGpsProgressPhase(matchedVyhybky, fullScan ? scanned : -1), pct);
    }

    private static String vyhybkaGpsProgressPhase(int matched, int scanned) {
        if (scanned >= 0) {
            return String.format(Locale.ROOT, "Indexuji souřadnice výhybek (%s / %s řádků)",
                    formatCount(matched), formatCount(scanned));
        }
        return String.format(Locale.ROOT, "Indexuji souřadnice výhybek (%s)", formatCount(matched));
    }

    private static String cacheProgressPhase(int written) {
        return String.format(Locale.ROOT, "Ukládám cache (%s)", formatCount(written));
    }

    private static String formatCount(int count) {
        if (count >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1f mil.", count / 1_000_000.0);
        }
        if (count >= 1_000) {
            return String.format(Locale.ROOT, "%d tis.", count / 1_000);
        }
        return String.valueOf(count);
    }

    private static boolean populateRoPairsTempTable(SQLiteDatabase db, Set<String> pairKeys) {
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
            for (String key : pairKeys) {
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

    /**
     * Vybere km bod podle shody KM_EXT se středem OD/DO.
     * Priorita: přesná shoda → okno ±0,5 → zaokrouhlení → nejbližší km.
     */
    private static KmGpsPoint matchKmPoint(List<KmGpsPoint> points, Double midKm) {
        if (points.isEmpty()) return null;
        if (midKm == null) return points.get(0);
        if (points.size() == 1) return points.get(0);

        KmGpsPoint exact = null;
        KmGpsPoint inRange = null;
        KmGpsPoint rounded = null;
        KmGpsPoint nearest = null;
        double nearestDiff = Double.MAX_VALUE;

        for (KmGpsPoint p : points) {
            if (p.km == midKm) {
                exact = p;
                break;
            }
            if (p.km >= midKm - 0.5 && p.km < midKm + 0.5) {
                inRange = p;
            }
            if (Math.round(p.km) == Math.round(midKm)) {
                rounded = p;
            }
            double diff = Math.abs(p.km - midKm);
            if (diff < nearestDiff) {
                nearestDiff = diff;
                nearest = p;
            }
        }

        if (exact != null) return exact;
        if (inRange != null) return inRange;
        if (rounded != null) return rounded;
        if (nearest != null) return nearest;
        return points.get(0);
    }

    private static String pairKey(String superZId, String superDId) {
        return superZId + "|" + superDId;
    }

    private static String[] splitPairKey(String pairKey) {
        int sep = pairKey.indexOf('|');
        if (sep < 0) return new String[]{pairKey, ""};
        return new String[]{pairKey.substring(0, sep), pairKey.substring(sep + 1)};
    }

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
        List<GpsPoint> out = new ArrayList<>(vyhybkaGpsStore.size());
        for (int i = 0; i < vyhybkaGpsStore.size(); i++) {
            String label = vyhybkaGpsStore.tuduAt(i) + " · výhybka " + vyhybkaGpsStore.vyhybkaAt(i);
            out.add(new GpsPoint(vyhybkaGpsStore.latitudeAt(i), vyhybkaGpsStore.longitudeAt(i), label));
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
        final String kmExt;

        GpsColumns(String superZId, String superDId, String latitude, String longitude, String kmExt) {
            this.superZId = superZId;
            this.superDId = superDId;
            this.latitude = latitude;
            this.longitude = longitude;
            this.kmExt = kmExt;
        }

        static GpsColumns resolve(SQLiteDatabase db, String table) throws Exception {
            List<String> cols = tableColumns(db, table);
            if (cols.isEmpty()) {
                throw new Exception("Tabulka " + table + " neexistuje");
            }
            String superZId = requireColumn(cols, "SUPER_Z_ID");
            String superDId = requireColumn(cols, "SUPER_D_ID");
            String lat = findRequiredColumn(cols, "LAT", "LAN", "LATITUDE", "GPS_LAT", "SIRKA", "GPS_SIRKA", "Y");
            String lon = findRequiredColumn(cols, "LON", "LONGITUDE", "LNG", "GPS_LON", "DELKA", "GPS_DELKA", "X");
            String kmExt = findRequiredColumn(cols, "KM_EXT", "KM_INT", "KM", "KILOMETR", "KMK");
            return new GpsColumns(superZId, superDId, lat, lon, kmExt);
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
        final String poloha;
        final String od;
        final String doCol;

        RoColumns(String superZId, String superDId, String tudu, String vyhybka,
                  String vyhybkaFallback, String castMin, String castMax, String poloha,
                  String od, String doCol) {
            this.superZId = superZId;
            this.superDId = superDId;
            this.tudu = tudu;
            this.vyhybka = vyhybka;
            this.vyhybkaFallback = vyhybkaFallback;
            this.castMin = castMin;
            this.castMax = castMax;
            this.poloha = poloha;
            this.od = od;
            this.doCol = doCol;
        }

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
                    findOptionalColumn(cols, "POLOHA"),
                    findRequiredColumn(cols, "OD"),
                    findRequiredColumn(cols, "DO")
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
