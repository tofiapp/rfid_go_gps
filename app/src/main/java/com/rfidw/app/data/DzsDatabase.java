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
 * Pokud obě tabulky mají sloupec RO_ID, výhybka se spáruje přímo přes
 * SUPER_Z_ID + SUPER_D_ID + RO_ID a GPS se hledá lazy podle nejbližšího LAT/LON.
 * Jinak se při indexaci použije KM_EXT ↔ (OD+DO)/2 a předpočítané souřadnice výhybek.
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
        final String roId;
        /** Střed sloupců OD a DO; null pokud nelze spočítat. */
        final Double midKm;
        final Integer castMin;
        final Integer castMax;

        RoIndexEntry(String tudu, int vyhybka, String roId, Double midKm) {
            this(tudu, vyhybka, roId, midKm, null, null);
        }

        RoIndexEntry(String tudu, int vyhybka, String roId, Double midKm,
                     Integer castMin, Integer castMax) {
            this.tudu = tudu;
            this.vyhybka = vyhybka;
            this.roId = roId;
            this.midKm = midKm;
            this.castMin = castMin;
            this.castMax = castMax;
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

    private static final class GpsKmPoint {
        final String superZId;
        final String superDId;
        final String pairKey;
        final String roId;
        final double kmExt;
        final double latitude;
        final double longitude;

        GpsKmPoint(String superZId, String superDId, String roId, double kmExt,
                   double latitude, double longitude) {
            this.superZId = superZId;
            this.superDId = superDId;
            this.pairKey = pairKey(superZId, superDId);
            this.roId = roId;
            this.kmExt = kmExt;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    @FunctionalInterface
    private interface GpsKmPointConsumer {
        void accept(GpsKmPoint point);
    }

    private static final double BBOX_CELL_DEG = 0.005;
    private static final int BBOX_MAX_RING = 40;

    private final SQLiteDatabase db;
    private final GpsColumns gpsColumns;
    private final RoColumns roColumns;
    private final boolean roIdMode;
    private final Map<String, List<RoIndexEntry>> roByPairKey;
    private final Map<String, RoIndexEntry> roByRoKey;
    private final VyhybkaGpsStore vyhybkaGpsStore;
    /** Memoizace pro záložní režim bez předpočítaného store. */
    private final Map<String, double[]> coordMemo = new HashMap<>();
    private volatile boolean gpsLatLonIndexReady;
    private volatile SpatialGrid spatialGrid;

    private DzsDatabase(SQLiteDatabase db, GpsColumns gpsColumns, RoColumns roColumns,
                        boolean roIdMode,
                        Map<String, List<RoIndexEntry>> roByPairKey,
                        Map<String, RoIndexEntry> roByRoKey,
                        VyhybkaGpsStore vyhybkaGpsStore) {
        this.db = db;
        this.gpsColumns = gpsColumns;
        this.roColumns = roColumns;
        this.roIdMode = roIdMode;
        this.roByPairKey = roByPairKey;
        this.roByRoKey = roByRoKey != null ? roByRoKey : Collections.emptyMap();
        this.vyhybkaGpsStore = vyhybkaGpsStore != null ? vyhybkaGpsStore : VyhybkaGpsStore.empty();
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

    private void ensureSpatialIndex(OpenProgressListener listener) {
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
            boolean roIdColumnsPresent = gpsColumns.roId != null && roColumns.roId != null;
            ensureGpsPairIndex(db, gpsColumns);
            if (roIdColumnsPresent) {
                ensureGpsRoIdIndex(db, gpsColumns);
            }

            String contentHash = null;
            DzsIndexCache.LoadedIndex cached = null;
            File indexCacheDir = cacheDir != null ? new File(cacheDir, "dzs_index") : null;
            if (cacheDir != null) {
                report(listener, "Kontrola databáze", 8);
                contentHash = DzsIndexCache.resolveContentHash(sourceFile, indexCacheDir);
                report(listener, "Načítám cache indexu", 12);
                cached = DzsIndexCache.tryLoad(sourceFile, contentHash, indexCacheDir);
            }

            Map<String, List<RoIndexEntry>> roByPairKey;
            Map<String, RoIndexEntry> roByRoKey = Collections.emptyMap();
            VyhybkaGpsStore gpsStore = VyhybkaGpsStore.empty();
            if (cached != null) {
                roByPairKey = convertRoIndex(cached.roByPairKey);
                if (roIdColumnsPresent) {
                    roByRoKey = buildRoByRoKey(roByPairKey);
                    if (roByRoKey.isEmpty()) {
                        cached = null;
                    }
                } else if (cached.vyhybkaGpsStore != null && !cached.vyhybkaGpsStore.isEmpty()) {
                    gpsStore = cached.vyhybkaGpsStore;
                }
            }
            boolean roIdMode = roIdColumnsPresent && !roByRoKey.isEmpty();
            if (cached != null) {
                report(listener, "Cache indexu načtena", roIdMode ? 95 : 75);
            } else {
                report(listener, "Indexuji výhybky", 20);
                RoIndexBuildResult roIndex = buildRoIndex(db, roColumns);
                roByPairKey = roIndex.byPairKey;
                roByRoKey = roIndex.byRoKey;
                roIdMode = roIdColumnsPresent && !roByRoKey.isEmpty();
            }

            DzsDatabase opened = new DzsDatabase(db, gpsColumns, roColumns, roIdMode,
                    roByPairKey, roByRoKey, gpsStore);
            if (!roIdMode && gpsStore.isEmpty() && !roByPairKey.isEmpty()) {
                report(listener, "Indexuji souřadnice výhybek", 50);
                gpsStore = buildVyhybkaGpsIndex(db, gpsColumns, roByPairKey, listener);
                opened = new DzsDatabase(db, gpsColumns, roColumns, false,
                        roByPairKey, Collections.emptyMap(), gpsStore);
                if (cacheDir != null) {
                    if (contentHash == null) {
                        contentHash = DzsIndexCache.resolveContentHash(sourceFile, indexCacheDir);
                    }
                    report(listener, "Ukládám cache", 85);
                    saveIndexCache(sourceFile, contentHash, cacheDir, roByPairKey, gpsStore, listener);
                }
            } else if (cached == null && cacheDir != null) {
                if (contentHash == null) {
                    contentHash = DzsIndexCache.resolveContentHash(sourceFile, indexCacheDir);
                }
                report(listener, "Ukládám cache", 85);
                saveIndexCache(sourceFile, contentHash, cacheDir, roByPairKey,
                        VyhybkaGpsStore.empty(), listener);
            }
            if (roIdMode) {
                report(listener, "Hotovo", 100);
            } else {
                opened.ensureSpatialIndex(listener);
            }
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

    private static void ensureGpsRoIdIndex(SQLiteDatabase db, GpsColumns gpsColumns) {
        try {
            db.execSQL("CREATE INDEX IF NOT EXISTS _dzs_gps_ro ON " + TABLE_GPS_KM
                    + " (" + gpsColumns.superZId + ", " + gpsColumns.superDId
                    + ", " + gpsColumns.roId + ")");
        } catch (Exception ignored) {
        }
    }

    private void ensureGpsLatLonIndex() {
        if (gpsLatLonIndexReady) return;
        synchronized (this) {
            if (gpsLatLonIndexReady) return;
            try {
                db.execSQL("CREATE INDEX IF NOT EXISTS _dzs_gps_latlon ON " + TABLE_GPS_KM
                        + " (" + gpsColumns.latitude + ", " + gpsColumns.longitude + ")");
                gpsLatLonIndexReady = true;
            } catch (Exception ignored) {
            }
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
                Integer castMin = ro.castMin >= 0 ? ro.castMin : null;
                Integer castMax = ro.castMax >= 0 ? ro.castMax : null;
                entries.add(new RoIndexEntry(ro.tudu, ro.vyhybka, ro.roId, midKm, castMin, castMax));
            }
            map.put(e.getKey(), entries);
        }
        return map;
    }

    private static Map<String, RoIndexEntry> buildRoByRoKey(
            Map<String, List<RoIndexEntry>> roByPairKey) {
        Map<String, RoIndexEntry> byRoKey = new HashMap<>();
        for (Map.Entry<String, List<RoIndexEntry>> e : roByPairKey.entrySet()) {
            String[] ids = splitPairKey(e.getKey());
            for (RoIndexEntry ro : e.getValue()) {
                if (ro.roId == null) continue;
                byRoKey.put(roKey(ids[0], ids[1], ro.roId), ro);
            }
        }
        return byRoKey;
    }

    private static final class RoIndexBuildResult {
        final Map<String, List<RoIndexEntry>> byPairKey;
        final Map<String, RoIndexEntry> byRoKey;

        RoIndexBuildResult(Map<String, List<RoIndexEntry>> byPairKey,
                           Map<String, RoIndexEntry> byRoKey) {
            this.byPairKey = byPairKey;
            this.byRoKey = byRoKey;
        }
    }

    private static void saveIndexCache(File dbFile, String contentHash, File cacheDir,
                                       Map<String, List<RoIndexEntry>> roByPairKey,
                                       VyhybkaGpsStore vyhybkaGpsStore,
                                       OpenProgressListener listener) {
        DzsIndexCache.save(dbFile, contentHash, new File(cacheDir, "dzs_index"),
                toRoCache(roByPairKey),
                vyhybkaGpsStore,
                (written, total) -> {
                    int pct = 85 + (int) ((written * 10L) / Math.max(total, 1));
                    report(listener, cacheProgressPhase(written), Math.min(pct, 95));
                });
    }

    private static Map<String, List<DzsIndexCache.RoEntry>> toRoCache(
            Map<String, List<RoIndexEntry>> roByPairKey) {
        Map<String, List<DzsIndexCache.RoEntry>> map = new HashMap<>(roByPairKey.size());
        for (Map.Entry<String, List<RoIndexEntry>> e : roByPairKey.entrySet()) {
            List<DzsIndexCache.RoEntry> entries = new ArrayList<>(e.getValue().size());
            for (RoIndexEntry ro : e.getValue()) {
                double midKm = ro.midKm != null ? ro.midKm : Double.NaN;
                int castMin = ro.castMin != null ? ro.castMin : DzsIndexCache.CAST_UNSPECIFIED;
                int castMax = ro.castMax != null ? ro.castMax : DzsIndexCache.CAST_UNSPECIFIED;
                entries.add(new DzsIndexCache.RoEntry(ro.tudu, ro.vyhybka, ro.roId, midKm, castMin, castMax));
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
        if (codes == null || codes.isEmpty()) {
            return buildTuduListFromRoIndex(null);
        }
        Set<String> wanted = new HashSet<>();
        for (String code : codes) {
            if (code != null && !code.trim().isEmpty()) {
                wanted.add(code.trim());
            }
        }
        if (wanted.isEmpty()) {
            return Collections.emptyList();
        }
        List<Tudu> fromMemory = buildTuduListFromRoIndex(wanted);
        if (!fromMemory.isEmpty()) {
            return fromMemory;
        }
        return loadTuduForCodesFromSql(wanted);
    }

    private List<Tudu> buildTuduListFromRoIndex(Set<String> codes) {
        Map<String, Tudu> map = new LinkedHashMap<>();
        for (List<RoIndexEntry> entries : roByPairKey.values()) {
            for (RoIndexEntry ro : entries) {
                if (codes != null && !codes.contains(ro.tudu)) continue;
                Tudu tudu = map.get(ro.tudu);
                if (tudu == null) {
                    tudu = new Tudu(ro.tudu);
                    map.put(ro.tudu, tudu);
                }
                Tudu.Vyhybka v = tudu.findOrCreate(ro.vyhybka);
                if (ro.castMin != null) v.castMin = ro.castMin;
                if (ro.castMax != null) v.castMax = ro.castMax;
            }
        }
        List<Tudu> out = new ArrayList<>(map.values());
        out.sort(Comparator.comparing(t -> t.code));
        return out;
    }

    private List<Tudu> loadTuduForCodesFromSql(Set<String> codes) {
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
        String[] args = new String[codes.size()];
        int i = 0;
        StringBuilder placeholders = new StringBuilder();
        for (String code : codes) {
            if (i > 0) placeholders.append(", ");
            placeholders.append('?');
            args[i++] = code;
        }
        sql.append(" AND ").append(roColumns.tudu).append(" IN (").append(placeholders).append(')');
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
     * Najde nejbližší výhybku – lazy GPS podle LAT/LON + RO_ID, nebo předpočítané souřadnice.
     */
    public GpsMatch findNearest(double latitude, double longitude) {
        if (roByPairKey.isEmpty()) return null;
        if (roIdMode) {
            return findNearestByRoId(latitude, longitude);
        }
        if (!vyhybkaGpsStore.isEmpty()) {
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
        ensureGpsLatLonIndex();

        final GpsMatch[] best = {null};
        final double[] bestDistM = {Double.MAX_VALUE};

        for (int ring = 0; ring <= BBOX_MAX_RING; ring++) {
            double delta = BBOX_CELL_DEG * (ring + 1);
            queryGpsPointsInBox(latitude, longitude, delta, point -> {
                List<RoIndexEntry> entries = roByPairKey.get(point.pairKey);
                if (entries == null || entries.isEmpty()) return;
                RoIndexEntry ro = pickVyhybkaForKmExt(entries, point.kmExt);
                if (ro == null) return;
                double dist = haversineM(latitude, longitude, point.latitude, point.longitude);
                if (dist < bestDistM[0]) {
                    bestDistM[0] = dist;
                    best[0] = new GpsMatch(point.superZId, point.superDId, ro.tudu, ro.vyhybka,
                            point.latitude, point.longitude, dist);
                }
            });

            if (best[0] != null) {
                double ringBoundM = delta * 111_000.0;
                if (bestDistM[0] <= ringBoundM) {
                    return best[0];
                }
            }
        }
        return best[0];
    }

    /** Nejbližší výhybka pro každý unikátní TUDU kód, seřazené podle vzdálenosti. */
    public List<GpsMatch> findNearestDistinctTudu(double latitude, double longitude, int limit) {
        if (limit <= 0) return Collections.emptyList();
        if (roByPairKey.isEmpty()) return Collections.emptyList();
        if (roIdMode) {
            return findNearestDistinctTuduByRoId(latitude, longitude, limit);
        }
        if (!vyhybkaGpsStore.isEmpty()) {
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
        ensureGpsLatLonIndex();

        Map<String, GpsMatch> bestByTudu = new HashMap<>();

        for (int ring = 0; ring <= BBOX_MAX_RING; ring++) {
            double delta = BBOX_CELL_DEG * (ring + 1);
            queryGpsPointsInBox(latitude, longitude, delta, point -> {
                List<RoIndexEntry> entries = roByPairKey.get(point.pairKey);
                if (entries == null || entries.isEmpty()) return;
                RoIndexEntry ro = pickVyhybkaForKmExt(entries, point.kmExt);
                if (ro == null) return;
                double dist = haversineM(latitude, longitude, point.latitude, point.longitude);

                GpsMatch existing = bestByTudu.get(ro.tudu);
                if (existing != null && dist >= existing.distanceM) return;

                bestByTudu.put(ro.tudu, new GpsMatch(point.superZId, point.superDId, ro.tudu,
                        ro.vyhybka, point.latitude, point.longitude, dist));
            });

            if (bestByTudu.size() >= limit) {
                double ringBoundM = delta * 111_000.0;
                double worstDist = bestByTudu.values().stream()
                        .mapToDouble(m -> m.distanceM)
                        .max().orElse(Double.MAX_VALUE);
                if (worstDist <= ringBoundM) break;
            }
        }

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
        if (!vyhybkaGpsStore.isEmpty()) {
            for (int i = 0; i < vyhybkaGpsStore.size(); i++) {
                if (!trimmedTudu.equals(vyhybkaGpsStore.tuduAt(i))) continue;
                recordVyhybkaDistance(bestByVyhybka, vyhybkaGpsStore.vyhybkaAt(i),
                        latitude, longitude,
                        vyhybkaGpsStore.latitudeAt(i), vyhybkaGpsStore.longitudeAt(i));
            }
            return bestByVyhybka;
        }
        for (Map.Entry<String, List<RoIndexEntry>> e : roByPairKey.entrySet()) {
            for (RoIndexEntry ro : e.getValue()) {
                if (!trimmedTudu.equals(ro.tudu)) continue;
                double[] coord = resolveVyhybkaCoord(e.getKey(), ro);
                if (coord == null) continue;
                recordVyhybkaDistance(bestByVyhybka, ro.vyhybka,
                        latitude, longitude, coord[0], coord[1]);
            }
        }
        return bestByVyhybka;
    }

    private GpsMatch findNearestByRoId(double latitude, double longitude) {
        ensureGpsLatLonIndex();

        final GpsMatch[] best = {null};
        final double[] bestDistM = {Double.MAX_VALUE};

        for (int ring = 0; ring <= BBOX_MAX_RING; ring++) {
            double delta = BBOX_CELL_DEG * (ring + 1);
            queryGpsPointsInBox(latitude, longitude, delta, point -> {
                if (point.roId == null) return;
                RoIndexEntry ro = roByRoKey.get(roKey(point.superZId, point.superDId, point.roId));
                if (ro == null) return;
                double dist = haversineM(latitude, longitude, point.latitude, point.longitude);
                if (dist < bestDistM[0]) {
                    bestDistM[0] = dist;
                    best[0] = new GpsMatch(point.superZId, point.superDId, ro.tudu, ro.vyhybka,
                            point.latitude, point.longitude, dist);
                }
            });

            if (best[0] != null) {
                double ringBoundM = delta * 111_000.0;
                if (bestDistM[0] <= ringBoundM) {
                    return best[0];
                }
            }
        }
        return best[0];
    }

    private List<GpsMatch> findNearestDistinctTuduByRoId(double latitude, double longitude, int limit) {
        ensureGpsLatLonIndex();

        Map<String, GpsMatch> bestByTudu = new HashMap<>();

        for (int ring = 0; ring <= BBOX_MAX_RING; ring++) {
            double delta = BBOX_CELL_DEG * (ring + 1);
            queryGpsPointsInBox(latitude, longitude, delta, point -> {
                if (point.roId == null) return;
                RoIndexEntry ro = roByRoKey.get(roKey(point.superZId, point.superDId, point.roId));
                if (ro == null) return;
                double dist = haversineM(latitude, longitude, point.latitude, point.longitude);

                GpsMatch existing = bestByTudu.get(ro.tudu);
                if (existing != null && dist >= existing.distanceM) return;

                bestByTudu.put(ro.tudu, new GpsMatch(point.superZId, point.superDId, ro.tudu,
                        ro.vyhybka, point.latitude, point.longitude, dist));
            });

            if (bestByTudu.size() >= limit) {
                double ringBoundM = delta * 111_000.0;
                double worstDist = bestByTudu.values().stream()
                        .mapToDouble(m -> m.distanceM)
                        .max().orElse(Double.MAX_VALUE);
                if (worstDist <= ringBoundM) break;
            }
        }

        List<GpsMatch> sorted = new ArrayList<>(bestByTudu.values());
        sorted.sort(Comparator.comparingDouble(m -> m.distanceM));
        if (sorted.size() <= limit) return sorted;
        return new ArrayList<>(sorted.subList(0, limit));
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

    private static double approximateDistSq(double lat, double lon, double targetLat, double targetLon,
                                            double cosLat) {
        double dLat = targetLat - lat;
        double dLon = (targetLon - lon) * cosLat;
        return dLat * dLat + dLon * dLon;
    }

    private void queryGpsPointsInBox(double latitude, double longitude, double deltaDeg,
                                     GpsKmPointConsumer consumer) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(gpsColumns.superZId).append(", ").append(gpsColumns.superDId).append(", ");
        if (roIdMode) {
            sql.append(gpsColumns.roId);
        } else {
            sql.append(gpsColumns.kmExt);
        }
        sql.append(", ").append(gpsColumns.latitude).append(", ").append(gpsColumns.longitude)
                .append(" FROM ").append(TABLE_GPS_KM)
                .append(" WHERE ").append(gpsColumns.latitude).append(" BETWEEN ? AND ?")
                .append(" AND ").append(gpsColumns.longitude).append(" BETWEEN ? AND ?");
        String[] args = {
                String.valueOf(latitude - deltaDeg),
                String.valueOf(latitude + deltaDeg),
                String.valueOf(longitude - deltaDeg),
                String.valueOf(longitude + deltaDeg)
        };
        try (Cursor c = db.rawQuery(sql.toString(), args)) {
            while (c.moveToNext()) {
                String superZId = readId(c, 0);
                String superDId = readId(c, 1);
                Double lat = readDouble(c, 3);
                Double lon = readDouble(c, 4);
                if (superZId == null || superDId == null || lat == null || lon == null) {
                    continue;
                }
                String roId = null;
                double kmExt = Double.NaN;
                if (roIdMode) {
                    roId = readId(c, 2);
                    if (roId == null) continue;
                } else {
                    Double km = readDouble(c, 2);
                    if (km == null) continue;
                    kmExt = km;
                }
                consumer.accept(new GpsKmPoint(superZId, superDId, roId, kmExt, lat, lon));
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Vrátí souřadnici výhybky – přes RO_ID nebo záložně KM_EXT.
     */
    private double[] resolveVyhybkaCoord(String pairKey, RoIndexEntry ro) {
        String key = memoKey(pairKey, ro.tudu, ro.vyhybka);
        double[] cached = coordMemo.get(key);
        if (cached != null) return cached;

        String[] ids = splitPairKey(pairKey);
        String sql;
        String[] args;
        if (ro.roId != null && gpsColumns.roId != null) {
            sql = "SELECT " + gpsColumns.latitude + ", " + gpsColumns.longitude
                    + " FROM " + TABLE_GPS_KM
                    + " WHERE " + gpsColumns.superZId + " = ? AND " + gpsColumns.superDId + " = ?"
                    + " AND " + gpsColumns.roId + " = ?"
                    + " LIMIT 1";
            args = new String[]{ids[0], ids[1], ro.roId};
        } else if (ro.midKm != null && gpsColumns.kmExt != null) {
            double mid = ro.midKm;
            sql = "SELECT " + gpsColumns.latitude + ", " + gpsColumns.longitude
                    + " FROM " + TABLE_GPS_KM
                    + " WHERE " + gpsColumns.superZId + " = ? AND " + gpsColumns.superDId + " = ?"
                    + " AND " + gpsColumns.kmExt + " >= ? AND " + gpsColumns.kmExt + " < ?"
                    + " ORDER BY ABS(" + gpsColumns.kmExt + " - ?) LIMIT 1";
            args = new String[]{ids[0], ids[1],
                    String.valueOf(mid - 0.5), String.valueOf(mid + 0.5),
                    String.valueOf(mid)};
        } else {
            sql = "SELECT " + gpsColumns.latitude + ", " + gpsColumns.longitude
                    + " FROM " + TABLE_GPS_KM
                    + " WHERE " + gpsColumns.superZId + " = ? AND " + gpsColumns.superDId + " = ?"
                    + " LIMIT 1";
            args = new String[]{ids[0], ids[1]};
        }
        try (Cursor c = db.rawQuery(sql, args)) {
            if (c.moveToFirst()) {
                Double lat = readDouble(c, 0);
                Double lon = readDouble(c, 1);
                if (lat != null && lon != null) {
                    double[] coord = new double[]{lat, lon};
                    coordMemo.put(key, coord);
                    return coord;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static RoIndexEntry pickVyhybkaForKmExt(List<RoIndexEntry> entries, double kmExt) {
        if (entries == null || entries.isEmpty()) return null;
        if (entries.size() == 1) return entries.get(0);

        RoIndexEntry exact = null;
        RoIndexEntry inRange = null;
        RoIndexEntry rounded = null;
        RoIndexEntry nearest = null;
        double nearestDiff = Double.MAX_VALUE;

        for (RoIndexEntry ro : entries) {
            if (ro.midKm == null) continue;
            double mid = ro.midKm;
            if (mid == kmExt) {
                exact = ro;
                break;
            }
            if (kmExt >= mid - 0.5 && kmExt < mid + 0.5) {
                inRange = ro;
            }
            if (Math.round(kmExt) == Math.round(mid)) {
                rounded = ro;
            }
            double diff = Math.abs(kmExt - mid);
            if (diff < nearestDiff) {
                nearestDiff = diff;
                nearest = ro;
            }
        }

        if (exact != null) return exact;
        if (inRange != null) return inRange;
        if (rounded != null) return rounded;
        if (nearest != null) return nearest;
        return entries.get(0);
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

    private static final int GPS_INDEX_PROGRESS_INTERVAL = 50_000;

    /**
     * Předpočítá souřadnici každé výhybky jedním průchodem GPS tabulky (bez ORDER BY).
     * Párování KM_EXT ↔ (OD+DO)/2 proběhne v paměti – více výhybek na stejný pár ID.
     */
    private static VyhybkaGpsStore buildVyhybkaGpsIndex(SQLiteDatabase db, GpsColumns gpsColumns,
                                                        Map<String, List<RoIndexEntry>> roByPairKey,
                                                        OpenProgressListener listener) {
        if (roByPairKey.isEmpty()) return VyhybkaGpsStore.empty();
        Set<String> pairKeys = roByPairKey.keySet();

        int expectedRows = -1;
        GpsPointStore kmBuffer = null;
        if (populateRoPairsTempTable(db, pairKeys)) {
            expectedRows = countGpsRowsWithTempTable(db, gpsColumns);
            kmBuffer = readGpsKmBufferJoin(db, gpsColumns, listener, null, expectedRows, -1);
        }
        if (kmBuffer == null) {
            int tableRows = countTableRows(db, TABLE_GPS_KM);
            HashSet<String> pairFilter = new HashSet<>(pairKeys);
            kmBuffer = readGpsKmBufferFullScan(db, gpsColumns, listener, pairFilter, expectedRows, tableRows);
        }
        if (kmBuffer == null) {
            kmBuffer = GpsPointStore.empty();
        }

        report(listener, "Páruji souřadnice výhybek", 82);
        return convertKmBufferToVyhybkaGps(kmBuffer, roByPairKey, listener);
    }

    private static GpsPointStore readGpsKmBufferJoin(SQLiteDatabase db, GpsColumns gpsColumns,
                                                     OpenProgressListener listener,
                                                     Set<String> pairFilter, int expectedRows,
                                                     int tableRows) {
        String sql = "SELECT g." + gpsColumns.superZId + ", g." + gpsColumns.superDId + ", "
                + gpsColumns.kmExt + ", " + gpsColumns.latitude + ", " + gpsColumns.longitude
                + " FROM " + TABLE_GPS_KM + " g"
                + " INNER JOIN " + TEMP_RO_PAIRS + " rp"
                + "   ON g." + gpsColumns.superZId + " = rp.super_z_id"
                + "   AND g." + gpsColumns.superDId + " = rp.super_d_id";
        return readGpsKmBufferCursor(db, sql, null, listener, pairFilter, expectedRows, tableRows);
    }

    private static GpsPointStore readGpsKmBufferFullScan(SQLiteDatabase db, GpsColumns gpsColumns,
                                                         OpenProgressListener listener,
                                                         Set<String> pairFilter, int expectedRows,
                                                         int tableRows) {
        String sql = "SELECT " + gpsColumns.superZId + ", " + gpsColumns.superDId + ", "
                + gpsColumns.kmExt + ", " + gpsColumns.latitude + ", " + gpsColumns.longitude
                + " FROM " + TABLE_GPS_KM;
        return readGpsKmBufferCursor(db, sql, null, listener, pairFilter, expectedRows, tableRows);
    }

    private static int countGpsRowsWithTempTable(SQLiteDatabase db, GpsColumns gpsColumns) {
        String sql = "SELECT COUNT(*) FROM " + TABLE_GPS_KM + " g"
                + " INNER JOIN " + TEMP_RO_PAIRS + " rp"
                + "   ON g." + gpsColumns.superZId + " = rp.super_z_id"
                + "   AND g." + gpsColumns.superDId + " = rp.super_d_id";
        return readCount(db, sql);
    }

    private static GpsPointStore readGpsKmBufferCursor(SQLiteDatabase db, String sql, String[] args,
                                                       OpenProgressListener listener,
                                                       Set<String> pairFilter, int expectedRows,
                                                       int tableRows) {
        int capacity = expectedRows > 0 ? expectedRows : 65536;
        GpsPointStore.Builder builder = GpsPointStore.builder(capacity);
        try (Cursor c = db.rawQuery(sql, args)) {
            int scanned = 0;
            while (c.moveToNext()) {
                scanned++;
                String superZId = readId(c, 0);
                String superDId = readId(c, 1);
                if (superZId == null || superDId == null) continue;
                if (pairFilter != null) {
                    String key = pairKey(superZId, superDId);
                    if (!pairFilter.contains(key)) continue;
                }
                Double kmExt = readDouble(c, 2);
                Double lat = readDouble(c, 3);
                Double lon = readDouble(c, 4);
                if (kmExt == null || lat == null || lon == null) continue;
                builder.addPoint(superZId, superDId, kmExt, lat, lon);

                if (scanned % GPS_INDEX_PROGRESS_INTERVAL == 0) {
                    reportGpsReadProgress(listener, scanned, expectedRows, tableRows, pairFilter != null);
                }
            }
            reportGpsReadProgress(listener, scanned, expectedRows, tableRows, pairFilter != null);
            return builder.build();
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static VyhybkaGpsStore convertKmBufferToVyhybkaGps(
            GpsPointStore kmBuffer, Map<String, List<RoIndexEntry>> roByPairKey,
            OpenProgressListener listener) {
        VyhybkaGpsStore.Builder builder = VyhybkaGpsStore.builder();
        int matched = 0;
        int pairs = roByPairKey.size();
        int processedPairs = 0;
        for (Map.Entry<String, List<RoIndexEntry>> e : roByPairKey.entrySet()) {
            matched += flushPairVyhybkaCoordsFromBuffer(
                    e.getKey(), kmBuffer, e.getValue(), builder);
            processedPairs++;
            if (listener != null && pairs > 0 && processedPairs % 500 == 0) {
                int pct = 82 + (int) Math.min(2L, (processedPairs * 2L) / pairs);
                report(listener, vyhybkaGpsProgressPhase(matched), Math.min(pct, 84));
            }
        }
        if (listener != null && matched > 0) {
            report(listener, vyhybkaGpsProgressPhase(matched), 84);
        }
        return builder.build();
    }

    private static int flushPairVyhybkaCoordsFromBuffer(String pairKey, GpsPointStore kmBuffer,
                                                      List<RoIndexEntry> entries,
                                                      VyhybkaGpsStore.Builder builder) {
        if (entries == null || entries.isEmpty()) return 0;
        int[] indices = kmBuffer.indicesForPair(pairKey);
        if (indices == null || indices.length == 0) return 0;
        int added = 0;
        for (RoIndexEntry ro : entries) {
            int hitIdx = matchKmPointInBuffer(kmBuffer, indices, ro.midKm);
            if (hitIdx < 0) continue;
            builder.add(pairKey, ro.tudu, ro.vyhybka,
                    kmBuffer.latitudeAt(hitIdx), kmBuffer.longitudeAt(hitIdx));
            added++;
        }
        return added;
    }

    private static void reportGpsReadProgress(OpenProgressListener listener, int scanned,
                                              int expectedRows, int tableRows, boolean fullScan) {
        if (listener == null || scanned <= 0) return;
        int pct;
        if (expectedRows > 0) {
            pct = 50 + (int) Math.min(30L, (scanned * 30L) / expectedRows);
        } else if (fullScan && tableRows > 0) {
            pct = 50 + (int) Math.min(30L, (scanned * 30L) / tableRows);
        } else {
            pct = 50 + Math.min(30, scanned / GPS_INDEX_PROGRESS_INTERVAL);
        }
        report(listener, gpsReadProgressPhase(scanned), pct);
    }

    private static String gpsReadProgressPhase(int scanned) {
        return String.format(Locale.ROOT, "Načítám GPS km body (%s řádků)", formatCount(scanned));
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
     * Vybere km bod podle shody KM_EXT se středem OD/DO.
     * Priorita: přesná shoda → okno ±0,5 → zaokrouhlení → nejbližší km.
     */
    private static int matchKmPointInBuffer(GpsPointStore buffer, int[] indices, Double midKm) {
        if (indices.length == 0) return -1;
        if (midKm == null) return indices[0];
        if (indices.length == 1) return indices[0];

        int exact = -1;
        int inRange = -1;
        int rounded = -1;
        int nearest = -1;
        double nearestDiff = Double.MAX_VALUE;

        for (int idx : indices) {
            double km = buffer.kmExtAt(idx);
            if (km == midKm) {
                exact = idx;
                break;
            }
            if (km >= midKm - 0.5 && km < midKm + 0.5) {
                inRange = idx;
            }
            if (Math.round(km) == Math.round(midKm)) {
                rounded = idx;
            }
            double diff = Math.abs(km - midKm);
            if (diff < nearestDiff) {
                nearestDiff = diff;
                nearest = idx;
            }
        }

        if (exact >= 0) return exact;
        if (inRange >= 0) return inRange;
        if (rounded >= 0) return rounded;
        if (nearest >= 0) return nearest;
        return indices[0];
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

    private static String vyhybkaGpsProgressPhase(int matched) {
        return String.format(Locale.ROOT, "Indexuji souřadnice výhybek (%s)", formatCount(matched));
    }

    /** Jeden průchod RO tabulkou – více výhybek na stejný pár ID je povoleno. */
    private static RoIndexBuildResult buildRoIndex(SQLiteDatabase db, RoColumns roColumns) {
        Map<String, List<RoIndexEntry>> byPairKey = new HashMap<>();
        Map<String, RoIndexEntry> byRoKey = new HashMap<>();
        String vyhybkaExpr = roColumns.vyhybkaSelectExpr(null);
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(roColumns.superZId).append(", ").append(roColumns.superDId).append(", ")
                .append(roColumns.tudu).append(", ").append(vyhybkaExpr);
        if (roColumns.roId != null) sql.append(", ").append(roColumns.roId);
        if (roColumns.od != null) sql.append(", ").append(roColumns.od);
        if (roColumns.doCol != null) sql.append(", ").append(roColumns.doCol);
        if (roColumns.castMin != null) sql.append(", ").append(roColumns.castMin);
        if (roColumns.castMax != null) sql.append(", ").append(roColumns.castMax);
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
                String roId = roColumns.roId != null ? readId(c, col++) : null;
                Double midKm = null;
                if (roColumns.od != null && roColumns.doCol != null) {
                    Double od = readDouble(c, col++);
                    Double doVal = readDouble(c, col++);
                    if (od != null && doVal != null) {
                        midKm = (od + doVal) / 2.0;
                    }
                }
                Integer castMin = roColumns.castMin != null ? readInt(c, col++) : null;
                Integer castMax = roColumns.castMax != null ? readInt(c, col) : null;
                RoIndexEntry entry = new RoIndexEntry(tudu, vyhybka, roId, midKm, castMin, castMax);
                String key = pairKey(superZId, superDId);
                byPairKey.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
                if (roId != null) {
                    byRoKey.put(roKey(superZId, superDId, roId), entry);
                }
            }
        }
        return new RoIndexBuildResult(byPairKey, byRoKey);
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

    private static String memoKey(String pairKey, String tudu, int vyhybka) {
        return pairKey + "|" + tudu + "|" + vyhybka;
    }

    private static String pairKey(String superZId, String superDId) {
        return superZId + "|" + superDId;
    }

    private static String roKey(String superZId, String superDId, String roId) {
        return pairKey(superZId, superDId) + "|" + roId;
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
        List<GpsPoint> out = new ArrayList<>();
        if (!vyhybkaGpsStore.isEmpty()) {
            for (int i = 0; i < vyhybkaGpsStore.size(); i++) {
                String label = vyhybkaGpsStore.tuduAt(i) + " · výhybka " + vyhybkaGpsStore.vyhybkaAt(i);
                out.add(new GpsPoint(vyhybkaGpsStore.latitudeAt(i), vyhybkaGpsStore.longitudeAt(i), label));
            }
        } else {
            for (Map.Entry<String, List<RoIndexEntry>> e : roByPairKey.entrySet()) {
                for (RoIndexEntry ro : e.getValue()) {
                    double[] coord = resolveVyhybkaCoord(e.getKey(), ro);
                    if (coord == null) continue;
                    String label = ro.tudu + " · výhybka " + ro.vyhybka;
                    out.add(new GpsPoint(coord[0], coord[1], label));
                }
            }
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
        final String roId;

        GpsColumns(String superZId, String superDId, String latitude, String longitude,
                   String kmExt, String roId) {
            this.superZId = superZId;
            this.superDId = superDId;
            this.latitude = latitude;
            this.longitude = longitude;
            this.kmExt = kmExt;
            this.roId = roId;
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
            String roId = findOptionalColumn(cols, "RO_ID");
            String kmExt = roId != null
                    ? findOptionalColumn(cols, "KM_EXT", "KM_INT", "KM", "KILOMETR", "KMK")
                    : findRequiredColumn(cols, "KM_EXT", "KM_INT", "KM", "KILOMETR", "KMK");
            return new GpsColumns(superZId, superDId, lat, lon, kmExt, roId);
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
        final String roId;
        final String od;
        final String doCol;

        RoColumns(String superZId, String superDId, String tudu, String vyhybka,
                  String vyhybkaFallback, String castMin, String castMax, String poloha,
                  String roId, String od, String doCol) {
            this.superZId = superZId;
            this.superDId = superDId;
            this.tudu = tudu;
            this.vyhybka = vyhybka;
            this.vyhybkaFallback = vyhybkaFallback;
            this.castMin = castMin;
            this.castMax = castMax;
            this.poloha = poloha;
            this.roId = roId;
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
            String roId = findOptionalColumn(cols, "RO_ID");
            String od = roId != null
                    ? findOptionalColumn(cols, "OD")
                    : findRequiredColumn(cols, "OD");
            String doCol = roId != null
                    ? findOptionalColumn(cols, "DO")
                    : findRequiredColumn(cols, "DO");
            return new RoColumns(
                    requireColumn(cols, "SUPER_Z_ID"),
                    requireColumn(cols, "SUPER_D_ID"),
                    findRequiredColumn(cols, "TUDU", "TUDU_KOD", "TUDU_CODE"),
                    vyhybka,
                    vyhybkaFallback,
                    findOptionalColumn(cols, "CAST_MIN", "CASTMIN"),
                    findOptionalColumn(cols, "CAST_MAX", "CASTMAX"),
                    findOptionalColumn(cols, "POLOHA"),
                    roId,
                    od,
                    doCol
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
