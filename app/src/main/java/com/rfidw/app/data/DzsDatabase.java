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
 * Při otevření se indexuje tabulka výhybek (RO) a předpočítají se GPS souřadnice
 * výhybek (jeden indexovaný dotaz na RO_ID). Výsledek se ukládá do diskové cache v16.
 * Vyhledávání TUDU podle GPS pak probíhá nad tisíci předpočítaných bodů v paměti,
 * ne nad celou km tabulkou za běhu.
 */
public class DzsDatabase implements Closeable {

    public static final String TABLE_GPS_KM = "DZS_SUPERTRA_GPS_KM";
    public static final String TABLE_RO_TPI = "DZS_SUPER_RO_TPI";
    private static final String TEMP_RO_GPS_LOOKUP = "_dzs_ro_gps_lookup";

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
        final Integer castMin;
        final Integer castMax;

        RoIndexEntry(String tudu, int vyhybka, String roId, Integer castMin, Integer castMax) {
            this.tudu = tudu;
            this.vyhybka = vyhybka;
            this.roId = roId;
            this.castMin = castMin;
            this.castMax = castMax;
        }
    }

    private static final class CastRange {
        final Integer castMin;
        final Integer castMax;

        CastRange(Integer castMin, Integer castMax) {
            this.castMin = castMin;
            this.castMax = castMax;
        }
    }

    /**
     * Určí rozsah částí výhybky. Explicitní CAST_MIN/CAST_MAX z DB má přednost;
     * jinak podle prvního písmene POLOHA: J = 3 části, C = 4 části.
     */
    static CastRange resolveCastRange(Integer castMin, Integer castMax, String poloha) {
        if (castMax != null) {
            int min = castMin != null ? castMin : 1;
            return new CastRange(min, castMax);
        }
        Integer fromPoloha = castMaxFromPoloha(poloha);
        if (fromPoloha != null) {
            return new CastRange(castMin != null ? castMin : 1, fromPoloha);
        }
        if (castMin != null) {
            return new CastRange(castMin, null);
        }
        return new CastRange(null, null);
    }

    /** J = 3částová výhybka (2 řádky v DB), C = 4částová (4 řádky). */
    static Integer castMaxFromPoloha(String poloha) {
        if (poloha == null || poloha.isEmpty()) return null;
        char first = Character.toUpperCase(poloha.trim().charAt(0));
        if (first == 'J') return 3;
        if (first == 'C') return 4;
        return null;
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
            double bestDistSq = Double.MAX_VALUE;

            for (int ring = 0; ring <= MAX_RING; ring++) {
                bestDistSq = Math.min(bestDistSq,
                        forEachInRing(latitude, longitude, ring, consumer));
                if (bestDistSq < Double.MAX_VALUE) {
                    double ringBoundDeg = (ring + 1) * CELL_DEG;
                    if (bestDistSq < ringBoundDeg * ringBoundDeg) return;
                }
            }
            if (bestDistSq < Double.MAX_VALUE) return;
            for (int idx : allIndices) consumer.accept(idx);
        }

        double forEachInRing(double latitude, double longitude, int ring, IntConsumer consumer) {
            if (store.isEmpty()) return Double.MAX_VALUE;
            int latCell = (int) Math.floor(latitude / CELL_DEG);
            int lonCell = (int) Math.floor(longitude / CELL_DEG);
            double cosLat = Math.cos(Math.toRadians(latitude));
            double bestDistSq = Double.MAX_VALUE;
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
            return bestDistSq;
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
        final String roId;
        final double latitude;
        final double longitude;

        GpsKmPoint(String superZId, String superDId, String roId, double latitude, double longitude) {
            this.superZId = superZId;
            this.superDId = superDId;
            this.roId = roId;
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
    private final Map<String, List<RoIndexEntry>> roByPairKey;
    private final Map<String, RoIndexEntry> roByRoKey;
    private final VyhybkaGpsStore vyhybkaGpsStore;
    private final Map<String, double[]> coordMemo = new HashMap<>();
    private volatile boolean gpsRoLookupIndexReady;
    private volatile boolean gpsLatLonIndexReady;
    private volatile SpatialGrid spatialGrid;

    private DzsDatabase(SQLiteDatabase db, GpsColumns gpsColumns, RoColumns roColumns,
                        Map<String, List<RoIndexEntry>> roByPairKey,
                        Map<String, RoIndexEntry> roByRoKey,
                        VyhybkaGpsStore vyhybkaGpsStore) {
        this.db = db;
        this.gpsColumns = gpsColumns;
        this.roColumns = roColumns;
        this.roByPairKey = roByPairKey;
        this.roByRoKey = roByRoKey;
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
            Map<String, RoIndexEntry> roByRoKey;
            VyhybkaGpsStore gpsStore = VyhybkaGpsStore.empty();
            if (cached != null) {
                roByPairKey = convertRoIndex(cached.roByPairKey);
                roByRoKey = buildRoByRoKey(roByPairKey);
                if (cached.vyhybkaGpsStore != null && !cached.vyhybkaGpsStore.isEmpty()) {
                    gpsStore = cached.vyhybkaGpsStore;
                }
                report(listener, "Cache indexu načtena", 75);
            } else {
                report(listener, "Indexuji výhybky", 20);
                RoIndexBuildResult built = buildRoIndex(db, roColumns);
                roByPairKey = built.byPairKey;
                roByRoKey = built.byRoKey;
                if (cacheDir != null) {
                    if (contentHash == null) {
                        contentHash = DzsIndexCache.resolveContentHash(sourceFile, indexCacheDir);
                    }
                    report(listener, "Ukládám cache (výhybky)", 40);
                    saveIndexCache(sourceFile, contentHash, cacheDir, roByPairKey,
                            VyhybkaGpsStore.empty(), listener);
                }
            }

            if (roByRoKey.isEmpty()) {
                throw new Exception("V databázi chybí platné páry RO_ID mezi tabulkami výhybek a GPS");
            }

            DzsDatabase opened = new DzsDatabase(db, gpsColumns, roColumns, roByPairKey, roByRoKey, gpsStore);
            boolean needsFullSave = false;

            if (gpsStore.isEmpty()) {
                report(listener, "Indexuji souřadnice výhybek", 50);
                gpsStore = opened.buildVyhybkaGpsStore(roByRoKey, listener);
                opened = new DzsDatabase(db, gpsColumns, roColumns, roByPairKey, roByRoKey, gpsStore);
                needsFullSave = cacheDir != null && !gpsStore.isEmpty();
            }

            if (needsFullSave && cacheDir != null) {
                if (contentHash == null) {
                    contentHash = DzsIndexCache.resolveContentHash(sourceFile, indexCacheDir);
                }
                report(listener, "Ukládám cache", 85);
                if (!saveIndexCache(sourceFile, contentHash, cacheDir, roByPairKey, gpsStore, listener)) {
                    report(listener, "Varování: cache indexu se nepodařilo uložit", -1);
                }
            }

            report(listener, "Hotovo", 100);
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
            db.execSQL("PRAGMA mmap_size = 268435456");
        } catch (Exception ignored) {
        }
    }

    private void ensureGpsRoLookupIndex() {
        if (gpsRoLookupIndexReady) return;
        synchronized (this) {
            if (gpsRoLookupIndexReady) return;
            try {
                db.execSQL("CREATE INDEX IF NOT EXISTS _dzs_gps_zd ON " + TABLE_GPS_KM
                        + " (" + gpsColumns.superZId + ", " + gpsColumns.superDId + ")");
                db.execSQL("CREATE INDEX IF NOT EXISTS _dzs_gps_ro ON " + TABLE_GPS_KM
                        + " (" + gpsColumns.superZId + ", " + gpsColumns.superDId
                        + ", " + gpsColumns.roId + ")");
                gpsRoLookupIndexReady = true;
            } catch (Exception ignored) {
            }
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
                Integer castMin = ro.castMin >= 0 ? ro.castMin : null;
                Integer castMax = ro.castMax >= 0 ? ro.castMax : null;
                entries.add(new RoIndexEntry(ro.tudu, ro.vyhybka, ro.roId, castMin, castMax));
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

    private static boolean saveIndexCache(File dbFile, String contentHash, File cacheDir,
                                          Map<String, List<RoIndexEntry>> roByPairKey,
                                          VyhybkaGpsStore vyhybkaGpsStore,
                                          OpenProgressListener listener) {
        return DzsIndexCache.save(dbFile, contentHash, new File(cacheDir, "dzs_index"),
                toRoCache(roByPairKey), vyhybkaGpsStore,
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
                int castMin = ro.castMin != null ? ro.castMin : DzsIndexCache.CAST_UNSPECIFIED;
                int castMax = ro.castMax != null ? ro.castMax : DzsIndexCache.CAST_UNSPECIFIED;
                entries.add(new DzsIndexCache.RoEntry(ro.tudu, ro.vyhybka, ro.roId, castMin, castMax));
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
        if (roColumns.poloha != null) sql.append(", ").append(roColumns.poloha);
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
                Integer cmin = roColumns.castMin != null ? readInt(c, col++) : null;
                Integer cmax = roColumns.castMax != null ? readInt(c, col++) : null;
                String poloha = roColumns.poloha != null ? readTrimmedText(c, col++) : null;
                CastRange cast = resolveCastRange(cmin, cmax, poloha);
                if (cast.castMin != null) v.castMin = cast.castMin;
                if (cast.castMax != null) v.castMax = cast.castMax;
            }
        }
        return new ArrayList<>(map.values());
    }

    /** Najde nejbližší výhybku podle předpočítaných souřadnic (RO_ID). */
    public GpsMatch findNearest(double latitude, double longitude) {
        if (roByRoKey.isEmpty()) return null;
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

    /** Nejbližší výhybka pro každý unikátní TUDU kód, seřazené podle vzdálenosti. */
    public List<GpsMatch> findNearestDistinctTudu(double latitude, double longitude, int limit) {
        if (limit <= 0) return Collections.emptyList();
        if (roByRoKey.isEmpty()) return Collections.emptyList();
        if (!vyhybkaGpsStore.isEmpty()) {
            Map<String, GpsMatch> bestByTudu = new HashMap<>();
            double cosLat = Math.cos(Math.toRadians(latitude));
            SpatialGrid grid = spatialGrid();
            for (int ring = 0; ring <= SpatialGrid.MAX_RING; ring++) {
                grid.forEachInRing(latitude, longitude, ring, idx -> {
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
                if (bestByTudu.size() >= limit) {
                    List<GpsMatch> candidates = new ArrayList<>(bestByTudu.values());
                    candidates.sort(Comparator.comparingDouble(m -> m.distanceM));
                    GpsMatch nth = candidates.get(limit - 1);
                    double nthDistSq = approximateDistSq(
                            latitude, longitude, nth.latitude, nth.longitude, cosLat);
                    double ringBoundDeg = (ring + 1) * SpatialGrid.CELL_DEG;
                    if (nthDistSq <= ringBoundDeg * ringBoundDeg) break;
                }
            }
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

    private void queryGpsPointsInBox(double latitude, double longitude, double deltaDeg,
                                     GpsKmPointConsumer consumer) {
        String sql = "SELECT " + gpsColumns.superZId + ", " + gpsColumns.superDId + ", "
                + gpsColumns.roId + ", " + gpsColumns.latitude + ", " + gpsColumns.longitude
                + " FROM " + TABLE_GPS_KM
                + " WHERE " + gpsColumns.latitude + " BETWEEN ? AND ?"
                + " AND " + gpsColumns.longitude + " BETWEEN ? AND ?";
        String[] args = {
                String.valueOf(latitude - deltaDeg),
                String.valueOf(latitude + deltaDeg),
                String.valueOf(longitude - deltaDeg),
                String.valueOf(longitude + deltaDeg)
        };
        try (Cursor c = db.rawQuery(sql, args)) {
            while (c.moveToNext()) {
                String superZId = readId(c, 0);
                String superDId = readId(c, 1);
                String roId = readId(c, 2);
                Double lat = readDouble(c, 3);
                Double lon = readDouble(c, 4);
                if (superZId == null || superDId == null || roId == null || lat == null || lon == null) {
                    continue;
                }
                consumer.accept(new GpsKmPoint(superZId, superDId, roId, lat, lon));
            }
        } catch (Exception ignored) {
        }
    }

    private double[] resolveVyhybkaCoord(String pairKey, RoIndexEntry ro) {
        if (ro.roId == null) return null;
        ensureGpsRoLookupIndex();
        String key = memoKey(pairKey, ro.tudu, ro.vyhybka);
        double[] cached = coordMemo.get(key);
        if (cached != null) return cached;

        String[] ids = splitPairKey(pairKey);
        String sql = "SELECT " + gpsColumns.latitude + ", " + gpsColumns.longitude
                + " FROM " + TABLE_GPS_KM
                + " WHERE " + gpsColumns.superZId + " = ? AND " + gpsColumns.superDId + " = ?"
                + " AND " + gpsColumns.roId + " = ?"
                + " LIMIT 1";
        String[] args = new String[]{ids[0], ids[1], ro.roId};
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

    private VyhybkaGpsStore buildVyhybkaGpsStore(Map<String, RoIndexEntry> roByRoKey,
                                                 OpenProgressListener listener) {
        ensureGpsRoLookupIndex();
        VyhybkaGpsStore batch = buildVyhybkaGpsStoreBatch(roByRoKey, listener);
        if (!batch.isEmpty()) {
            return batch;
        }
        return buildVyhybkaGpsStorePerEntry(roByRoKey, listener);
    }

    private boolean populateRoGpsLookupTempTable(Map<String, RoIndexEntry> roByRoKey) {
        try {
            db.execSQL("CREATE TEMP TABLE IF NOT EXISTS " + TEMP_RO_GPS_LOOKUP
                    + " (super_z_id TEXT NOT NULL, super_d_id TEXT NOT NULL, ro_id TEXT NOT NULL,"
                    + " tudu TEXT NOT NULL, vyhybka INTEGER NOT NULL,"
                    + " PRIMARY KEY (super_z_id, super_d_id, ro_id))");
            db.execSQL("DELETE FROM " + TEMP_RO_GPS_LOOKUP);
        } catch (Exception ignored) {
            return false;
        }

        SQLiteStatement insert = db.compileStatement(
                "INSERT OR IGNORE INTO " + TEMP_RO_GPS_LOOKUP
                        + " (super_z_id, super_d_id, ro_id, tudu, vyhybka) VALUES (?, ?, ?, ?, ?)");
        db.beginTransaction();
        try {
            for (Map.Entry<String, RoIndexEntry> e : roByRoKey.entrySet()) {
                String[] ids = splitRoKey(e.getKey());
                RoIndexEntry ro = e.getValue();
                if (ro.roId == null) continue;
                insert.clearBindings();
                insert.bindString(1, ids[0]);
                insert.bindString(2, ids[1]);
                insert.bindString(3, ro.roId);
                insert.bindString(4, ro.tudu);
                insert.bindLong(5, ro.vyhybka);
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

    private VyhybkaGpsStore buildVyhybkaGpsStoreBatch(Map<String, RoIndexEntry> roByRoKey,
                                                      OpenProgressListener listener) {
        if (!populateRoGpsLookupTempTable(roByRoKey)) {
            return VyhybkaGpsStore.empty();
        }
        String roIdExpr = "TRIM(CAST(" + gpsColumns.roId + " AS TEXT))";
        String sql = "SELECT ro.tudu, ro.vyhybka, gps." + gpsColumns.latitude + ", gps."
                + gpsColumns.longitude + ", ro.super_z_id, ro.super_d_id, ro.ro_id"
                + " FROM " + TEMP_RO_GPS_LOOKUP + " ro"
                + " INNER JOIN " + TABLE_GPS_KM + " gps"
                + " ON gps." + gpsColumns.superZId + " = ro.super_z_id"
                + " AND gps." + gpsColumns.superDId + " = ro.super_d_id"
                + " AND " + roIdExpr + " = ro.ro_id";

        VyhybkaGpsStore.Builder builder = VyhybkaGpsStore.builder();
        HashSet<String> seen = new HashSet<>();
        int matched = 0;
        try (Cursor c = db.rawQuery(sql, null)) {
            while (c.moveToNext()) {
                String tudu = c.getString(0);
                Integer vyhybka = readInt(c, 1);
                Double lat = readDouble(c, 2);
                Double lon = readDouble(c, 3);
                String superZId = readId(c, 4);
                String superDId = readId(c, 5);
                String roId = readId(c, 6);
                if (tudu == null || vyhybka == null || lat == null || lon == null) continue;
                if (superZId == null || superDId == null || roId == null) continue;
                if (!seen.add(roKey(superZId, superDId, roId))) continue;
                builder.add(pairKey(superZId, superDId), tudu, vyhybka, lat, lon);
                matched++;
            }
        } catch (Exception ignored) {
            return VyhybkaGpsStore.empty();
        }
        if (matched == 0) {
            report(listener, "Varování: žádné GPS souřadnice výhybek", 80);
        } else {
            report(listener, String.format(Locale.ROOT,
                    "Indexuji souřadnice výhybek (%d/%d)", matched, roByRoKey.size()), 80);
        }
        return builder.build();
    }

    private VyhybkaGpsStore buildVyhybkaGpsStorePerEntry(Map<String, RoIndexEntry> roByRoKey,
                                                        OpenProgressListener listener) {
        VyhybkaGpsStore.Builder builder = VyhybkaGpsStore.builder();
        String sql = "SELECT " + gpsColumns.latitude + ", " + gpsColumns.longitude
                + " FROM " + TABLE_GPS_KM
                + " WHERE " + gpsColumns.superZId + " = ? AND " + gpsColumns.superDId + " = ?"
                + " AND " + gpsColumns.roId + " = ? LIMIT 1";
        int total = roByRoKey.size();
        int done = 0;
        int matched = 0;
        for (Map.Entry<String, RoIndexEntry> e : roByRoKey.entrySet()) {
            String[] ids = splitRoKey(e.getKey());
            RoIndexEntry ro = e.getValue();
            String[] args = new String[]{ids[0], ids[1], ro.roId};
            try (Cursor c = db.rawQuery(sql, args)) {
                if (c.moveToFirst()) {
                    Double lat = readDouble(c, 0);
                    Double lon = readDouble(c, 1);
                    if (lat != null && lon != null) {
                        builder.add(pairKey(ids[0], ids[1]), ro.tudu, ro.vyhybka, lat, lon);
                        matched++;
                    }
                }
            } catch (Exception ignored) {
            }
            done++;
            if (listener != null && (done % 500 == 0 || done == total)) {
                int pct = 50 + (int) ((done * 30L) / Math.max(total, 1));
                report(listener, String.format(Locale.ROOT,
                        "Indexuji souřadnice výhybek (%d/%d)", done, total),
                        Math.min(pct, 80));
            }
        }
        if (matched == 0) {
            report(listener, "Varování: žádné GPS souřadnice výhybek", 80);
        }
        return builder.build();
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

    private static void recordVyhybkaDistance(Map<Integer, Double> bestByVyhybka, int vyhybka,
                                              double latitude, double longitude,
                                              double targetLat, double targetLon) {
        double dist = haversineM(latitude, longitude, targetLat, targetLon);
        Double existing = bestByVyhybka.get(vyhybka);
        if (existing == null || dist < existing) {
            bestByVyhybka.put(vyhybka, dist);
        }
    }

    private static RoIndexBuildResult buildRoIndex(SQLiteDatabase db, RoColumns roColumns) {
        Map<String, List<RoIndexEntry>> byPairKey = new HashMap<>();
        Map<String, RoIndexEntry> byRoKey = new HashMap<>();
        String vyhybkaExpr = roColumns.vyhybkaSelectExpr(null);
        String roIdExpr = "TRIM(CAST(" + roColumns.roId + " AS TEXT))";
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(roColumns.superZId).append(", ").append(roColumns.superDId).append(", ")
                .append(roColumns.tudu).append(", ").append(vyhybkaExpr).append(", ")
                .append(roColumns.roId);
        if (roColumns.castMin != null) sql.append(", ").append(roColumns.castMin);
        if (roColumns.castMax != null) sql.append(", ").append(roColumns.castMax);
        if (roColumns.poloha != null) sql.append(", ").append(roColumns.poloha);
        sql.append(" FROM ").append(TABLE_RO_TPI)
                .append(" WHERE ").append(roColumns.tudu).append(" IS NOT NULL AND ")
                .append(roColumns.tudu).append(" <> ''")
                .append(" AND ").append(vyhybkaExpr).append(" IS NOT NULL")
                .append(" AND ").append(roColumns.roId).append(" IS NOT NULL")
                .append(" AND ").append(roIdExpr).append(" <> ''");
        roColumns.appendPolohaFilter(sql, null);

        try (Cursor c = db.rawQuery(sql.toString(), null)) {
            while (c.moveToNext()) {
                String superZId = readId(c, 0);
                String superDId = readId(c, 1);
                String tudu = readTrimmedText(c, 2);
                Integer vyhybka = readInt(c, 3);
                String roId = readId(c, 4);
                if (superZId == null || superDId == null || tudu == null
                        || vyhybka == null || roId == null) {
                    continue;
                }
                int col = 5;
                Integer castMin = roColumns.castMin != null ? readInt(c, col++) : null;
                Integer castMax = roColumns.castMax != null ? readInt(c, col++) : null;
                String poloha = roColumns.poloha != null ? readTrimmedText(c, col++) : null;
                CastRange cast = resolveCastRange(castMin, castMax, poloha);
                RoIndexEntry entry = new RoIndexEntry(tudu, vyhybka, roId, cast.castMin, cast.castMax);
                String key = pairKey(superZId, superDId);
                byPairKey.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
                byRoKey.put(roKey(superZId, superDId, roId), entry);
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

    private static String[] splitRoKey(String roKeyStr) {
        int lastSep = roKeyStr.lastIndexOf('|');
        if (lastSep < 0) return new String[]{"", "", roKeyStr};
        int pairSep = roKeyStr.indexOf('|');
        if (pairSep < 0 || pairSep == lastSep) return new String[]{"", "", roKeyStr};
        return new String[]{
                roKeyStr.substring(0, pairSep),
                roKeyStr.substring(pairSep + 1, lastSep),
                roKeyStr.substring(lastSep + 1)
        };
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
        final String roId;

        GpsColumns(String superZId, String superDId, String latitude, String longitude, String roId) {
            this.superZId = superZId;
            this.superDId = superDId;
            this.latitude = latitude;
            this.longitude = longitude;
            this.roId = roId;
        }

        static GpsColumns resolve(SQLiteDatabase db, String table) throws Exception {
            List<String> cols = tableColumns(db, table);
            if (cols.isEmpty()) {
                throw new Exception("Tabulka " + table + " neexistuje");
            }
            return new GpsColumns(
                    requireColumn(cols, "SUPER_Z_ID"),
                    requireColumn(cols, "SUPER_D_ID"),
                    findRequiredColumn(cols, "LAT", "LAN", "LATITUDE", "GPS_LAT", "SIRKA", "GPS_SIRKA", "Y"),
                    findRequiredColumn(cols, "LON", "LONGITUDE", "LNG", "GPS_LON", "DELKA", "GPS_DELKA", "X"),
                    requireColumn(cols, "RO_ID")
            );
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

        RoColumns(String superZId, String superDId, String tudu, String vyhybka,
                  String vyhybkaFallback, String castMin, String castMax, String poloha,
                  String roId) {
            this.superZId = superZId;
            this.superDId = superDId;
            this.tudu = tudu;
            this.vyhybka = vyhybka;
            this.vyhybkaFallback = vyhybkaFallback;
            this.castMin = castMin;
            this.castMax = castMax;
            this.poloha = poloha;
            this.roId = roId;
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
                    requireColumn(cols, "RO_ID")
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
