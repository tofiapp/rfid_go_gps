package com.rfidw.app.data;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteStatement;
import android.util.Log;

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
 * Při otevření se indexuje jen okolí ~4 km kolem GPS (bbox ±0,04°). Plný index databáze
 * se staví na pozadí do souboru {@code dzs_{hash}.idx}; po dokončení jsou GPS/TUDU
 * dostupné kdekoli bez opakovaného SQL při pohybu.
 */
public class DzsDatabase implements Closeable {

    private static final String TAG = "DzsDatabase";
    /** Opakování při SQLITE_BUSY / database is locked během pozadí indexace. */
    private static final int DB_LOCK_RETRY_COUNT = 6;
    private static final long DB_LOCK_RETRY_MS = 250;

    public static final String TABLE_GPS_KM = "DZS_SUPERTRA_GPS_KM";
    public static final String TABLE_RO_TPI = "DZS_SUPER_RO_TPI";
    private static final String TEMP_RO_GPS_LOOKUP = "_dzs_ro_gps_lookup";
    /** Bbox ±0,04° kolem GPS (~4 km) – odpovídá SQL dotazu v dokumentaci. */
    private static final double PROXIMITY_BBOX_DEG = 0.04;
    /** Po přesunu o tuto vzdálenost se znovu načte okolí GPS (jen dokud není plný index). */
    private static final double PROXIMITY_RELOAD_MOVE_KM = 3.0;

    /** Průběh otevírání databáze (fáze + odhad procent 0–100, nebo -1). */
    public interface OpenProgressListener {
        void onProgress(String phase, int percent);
    }

    /** Průběh pozadí plné indexace (fáze + odhad procent 0–100, nebo -1 při chybě). */
    public interface IndexProgressListener {
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
        public final String poloha;
        public final String roId;

        public GpsMatch(String superZId, String superDId, String tudu, int vyhybka,
                        double latitude, double longitude, double distanceM) {
            this(superZId, superDId, tudu, vyhybka, latitude, longitude, distanceM, "", "");
        }

        public GpsMatch(String superZId, String superDId, String tudu, int vyhybka,
                        double latitude, double longitude, double distanceM, String poloha) {
            this(superZId, superDId, tudu, vyhybka, latitude, longitude, distanceM, poloha, "");
        }

        public GpsMatch(String superZId, String superDId, String tudu, int vyhybka,
                        double latitude, double longitude, double distanceM, String poloha,
                        String roId) {
            this.superZId = superZId;
            this.superDId = superDId;
            this.tudu = tudu;
            this.vyhybka = vyhybka;
            this.latitude = latitude;
            this.longitude = longitude;
            this.distanceM = distanceM;
            this.poloha = poloha != null ? poloha : "";
            this.roId = roId != null ? roId : "";
        }
    }

    private static final class RoIndexEntry {
        final String tudu;
        final int vyhybka;
        final String iob;
        final String roId;
        final Integer castMin;
        final Integer castMax;
        final String poloha;

        RoIndexEntry(String tudu, int vyhybka, String iob, String roId, Integer castMin,
                     Integer castMax, String poloha) {
            this.tudu = tudu;
            this.vyhybka = vyhybka;
            this.iob = iob != null ? iob : "";
            this.roId = roId;
            this.castMin = castMin;
            this.castMax = castMax;
            this.poloha = poloha != null ? poloha : "";
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

    private final SQLiteDatabase db;
    private final GpsColumns gpsColumns;
    private final RoColumns roColumns;
    private final Map<String, List<RoIndexEntry>> roByPairKey;
    private final Map<String, RoIndexEntry> roByRoKey;
    private VyhybkaGpsStore vyhybkaGpsStore;
    private final Map<String, double[]> coordMemo = new HashMap<>();
    private volatile boolean gpsRoLookupIndexReady;
    private volatile SpatialGrid spatialGrid;
    private volatile boolean proximityLoaded;
    private volatile Double proximityCenterLat;
    private volatile Double proximityCenterLon;
    private File sourceDbFile;
    private File storageCacheDir;
    private final Object dbQueryLock = new Object();
    private volatile int foregroundDbOps;
    private volatile boolean fullIndexReady;
    private volatile boolean backgroundIndexRunning;
    private volatile String indexProgressPhase = "";
    private volatile int indexProgressPercent = -1;
    private volatile IndexProgressListener indexProgressListener;

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
        return open(path, null, null, null, null);
    }

    public static DzsDatabase open(String path, File cacheDir, OpenProgressListener listener) throws Exception {
        return open(path, cacheDir, listener, null, null);
    }

    /**
     * Otevře databázi. Pokud jsou k dispozici souřadnice GPS, načte jen výhybky v okolí 4 km.
     * Zbytek se indexuje za běhu přes {@link #ensureProximityLoaded(double, double)}.
     */
    public static DzsDatabase open(String path, File cacheDir, OpenProgressListener listener,
                                   Double initialLatitude, Double initialLongitude) throws Exception {
        File sourceFile = new File(path);
        File dbFile = resolveDatabaseFile(sourceFile, cacheDir, listener);
        boolean writableCopy = cacheDir != null && !cacheDir.equals(sourceFile.getParentFile());
        SQLiteDatabase db = openSqliteDatabase(dbFile, writableCopy);
        try {
            applyReadPragmas(db);
            report(listener, "Kontrola schématu", 5);
            GpsColumns gpsColumns = GpsColumns.resolve(db, TABLE_GPS_KM);
            RoColumns roColumns = RoColumns.resolve(db, TABLE_RO_TPI);

            Map<String, List<RoIndexEntry>> roByPairKey = new HashMap<>();
            Map<String, RoIndexEntry> roByRoKey = new HashMap<>();
            VyhybkaGpsStore gpsStore = VyhybkaGpsStore.empty();
            DzsDatabase opened = new DzsDatabase(db, gpsColumns, roColumns, roByPairKey, roByRoKey,
                    gpsStore);
            opened.sourceDbFile = sourceFile;
            opened.storageCacheDir = cacheDir;

            if (initialLatitude != null && initialLongitude != null) {
                File indexCacheDir = cacheDir != null ? new File(cacheDir, "dzs_index") : null;
                DzsIndexCache.LoadedIndex proximityCached = null;
                if (indexCacheDir != null) {
                    report(listener, "Kontrola cache okolí", 15);
                    String cacheKey = DzsIndexCache.resolveCacheKey(sourceFile, indexCacheDir);
                    proximityCached = DzsIndexCache.tryLoadProximity(
                            sourceFile, cacheKey, indexCacheDir,
                            initialLatitude, initialLongitude,
                            PROXIMITY_RELOAD_MOVE_KM * 1000.0);
                    if (proximityCached == null) {
                        String fastKey = DzsIndexCache.fastDbKey(sourceFile);
                        if (!fastKey.equals(cacheKey)) {
                            proximityCached = DzsIndexCache.tryLoadProximity(
                                    sourceFile, fastKey, indexCacheDir,
                                    initialLatitude, initialLongitude,
                                    PROXIMITY_RELOAD_MOVE_KM * 1000.0);
                        }
                    }
                }
                if (proximityCached != null) {
                    opened.applyLoadedProximity(proximityCached, initialLatitude, initialLongitude);
                    report(listener, String.format(Locale.ROOT,
                            "Okolí GPS z cache (%d výhybek)", opened.vyhybkaGpsStore.size()), 90);
                } else {
                    report(listener, "Načítám okolí GPS (4 km)", 40);
                    int loaded = opened.loadProximityIndex(initialLatitude, initialLongitude, listener);
                    report(listener, String.format(Locale.ROOT,
                            "Okolí GPS načteno (%d výhybek)", loaded), 90);
                }
                opened.scheduleBackgroundProximityCacheSave(initialLatitude, initialLongitude);
                opened.ensureBackgroundFullIndex();
            } else {
                report(listener, "Čekám na GPS pro indexaci okolí", 40);
                opened.ensureBackgroundFullIndex();
            }

            opened.scheduleBackgroundContentHash();
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

    /**
     * Doplní index výhybek v okolí GPS, pokud ještě nebyl načten nebo se poloha výrazně změnila.
     */
    public void ensureProximityLoaded(double latitude, double longitude) {
        if (fullIndexReady) return;
        synchronized (this) {
            if (fullIndexReady) return;
            if (!proximityLoaded) {
                loadProximityIndex(latitude, longitude, null);
                ensureBackgroundFullIndex();
                return;
            }
            if (proximityCenterLat != null && proximityCenterLon != null) {
                double movedKm = haversineM(proximityCenterLat, proximityCenterLon,
                        latitude, longitude) / 1000.0;
                if (movedKm < PROXIMITY_RELOAD_MOVE_KM) {
                    return;
                }
            }
            loadProximityIndex(latitude, longitude, null);
            ensureBackgroundFullIndex();
        }
    }

    /** Plný index databáze je načten – GPS/TUDU fungují bez opakovaného SQL při pohybu. */
    public boolean isFullIndexReady() {
        return fullIndexReady;
    }

    public void setIndexProgressListener(IndexProgressListener listener) {
        indexProgressListener = listener;
        if (listener == null) return;
        if (fullIndexReady) {
            listener.onProgress("Indexace dokončena", 100);
        } else if (backgroundIndexRunning && indexProgressPercent >= 0) {
            listener.onProgress(indexProgressPhase, indexProgressPercent);
        }
    }

    /** Má načtené výhybky v okolí GPS – použitelné pro GPS režim bez čekání na SQL COUNT. */
    public boolean hasProximityData() {
        return proximityLoaded && !vyhybkaGpsStore.isEmpty();
    }

    /** Proběhla indexace okolí GPS (i když v bbox nebyla žádná výhybka). */
    public boolean isProximityIndexed() {
        return proximityLoaded;
    }

    private static File resolveDatabaseFile(File source, File cacheDir,
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
            byte[] buf = new byte[262_144];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static SQLiteDatabase openSqliteDatabase(File dbFile, boolean preferWritable) {
        if (preferWritable) {
            try {
                return SQLiteDatabase.openDatabase(
                        dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            } catch (SQLiteException e) {
                Log.w(TAG, "Kopie DB jen pro čtení – indexy GPS se vytvoří až při dalším otevření", e);
            }
        }
        return SQLiteDatabase.openDatabase(
                dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
    }

    private static void applyReadPragmas(SQLiteDatabase db) {
        try {
            db.execSQL("PRAGMA journal_mode = WAL");
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

    private void scheduleBackgroundProximityCacheSave(double centerLatitude, double centerLongitude) {
        if (sourceDbFile == null || storageCacheDir == null || !proximityLoaded) return;
        final File dbFile = sourceDbFile;
        final File cacheDir = storageCacheDir;
        Thread t = new Thread(() -> {
            synchronized (this) {
                if (!proximityLoaded || vyhybkaGpsStore.isEmpty()) return;
                File indexCacheDir = new File(cacheDir, "dzs_index");
                String key = DzsIndexCache.resolveCacheKey(dbFile, indexCacheDir);
                saveProximityCache(key, centerLatitude, centerLongitude);
            }
        }, "dzs-prox-cache");
        t.setDaemon(true);
        t.start();
    }

    private void ensureBackgroundFullIndex() {
        if (fullIndexReady || backgroundIndexRunning || sourceDbFile == null) return;
        synchronized (this) {
            if (fullIndexReady || backgroundIndexRunning) return;
            backgroundIndexRunning = true;
        }
        Thread t = new Thread(this::runBackgroundFullIndex, "dzs-full-index");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void runBackgroundFullIndex() {
        try {
            reportIndexProgress("Kontrola cache indexu", 0);
            waitForForegroundIdle();

            File indexCacheDir = storageCacheDir != null ? new File(storageCacheDir, "dzs_index") : null;
            DzsIndexCache.LoadedIndex cached = tryLoadFullIndexFromDisk(indexCacheDir);
            if (cached != null) {
                reportIndexProgress("Načítám cache indexu", 60);
                synchronized (this) {
                    if (!backgroundIndexRunning) return;
                    applyLoadedFullIndex(cached);
                }
                reportIndexProgress("Indexace dokončena", 100);
                return;
            }

            reportIndexProgress("Indexuji výhybky (RO)", 10);
            waitForForegroundIdle();
            RoIndexBuildResult built = runDbWithLockRetry(() -> buildRoIndex(db, roColumns));
            if (built == null) return;
            if (built.byRoKey.isEmpty()) {
                reportIndexProgress("Varování: prázdný RO index", -2);
                return;
            }
            reportIndexProgress("Indexuji výhybky (RO)", 40);

            reportIndexProgress("Indexuji GPS souřadnice výhybek", 45);
            waitForForegroundIdle();
            VyhybkaGpsStore gpsStore = runDbWithLockRetry(() -> {
                foregroundDbOps++;
                try {
                    return buildVyhybkaGpsStore(built.byRoKey, indexGpsProgressListener());
                } finally {
                    foregroundDbOps--;
                }
            });
            if (gpsStore == null) return;
            reportIndexProgress("Indexuji GPS souřadnice výhybek", 85);

            if (indexCacheDir != null && storageCacheDir != null) {
                reportIndexProgress("Ukládám cache indexu", 88);
                String contentHash = DzsIndexCache.fastDbKey(sourceDbFile);
                try {
                    contentHash = DzsIndexCache.resolveContentHash(sourceDbFile, indexCacheDir);
                } catch (Exception ignored) {
                }
                saveIndexCache(sourceDbFile, contentHash, storageCacheDir, built.byPairKey, gpsStore,
                        indexSaveProgressListener());
            }

            synchronized (this) {
                if (!backgroundIndexRunning) return;
                applyFullIndexInMemory(built.byPairKey, built.byRoKey, gpsStore);
            }
            reportIndexProgress("Indexace dokončena", 100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.w(TAG, "Plná indexace databáze selhala", e);
            reportIndexProgress("Indexace selhala", -1);
        } finally {
            backgroundIndexRunning = false;
        }
    }

    private DzsIndexCache.LoadedIndex tryLoadFullIndexFromDisk(File indexCacheDir) {
        if (sourceDbFile == null || indexCacheDir == null) return null;
        String fastKey = DzsIndexCache.fastDbKey(sourceDbFile);
        DzsIndexCache.LoadedIndex loaded = DzsIndexCache.tryLoad(sourceDbFile, fastKey, indexCacheDir);
        if (loaded != null) return loaded;
        String cacheKey = DzsIndexCache.resolveCacheKey(sourceDbFile, indexCacheDir);
        if (fastKey.equals(cacheKey)) return null;
        return DzsIndexCache.tryLoad(sourceDbFile, cacheKey, indexCacheDir);
    }

    private OpenProgressListener indexGpsProgressListener() {
        return (phase, percent) -> {
            if (percent < 0) {
                reportIndexProgress(phase, -1);
                return;
            }
            int mapped = 45;
            if (percent >= 50) {
                mapped = 45 + (int) ((percent - 50L) * 40L / 30L);
            }
            reportIndexProgress(phase, Math.min(mapped, 85));
        };
    }

    private OpenProgressListener indexSaveProgressListener() {
        return (phase, percent) -> {
            if (percent < 0) {
                reportIndexProgress(phase, -1);
                return;
            }
            int mapped = 88 + (percent - 85) / 3;
            reportIndexProgress(phase, Math.min(Math.max(mapped, 88), 98));
        };
    }

    private void applyLoadedFullIndex(DzsIndexCache.LoadedIndex cached) {
        roByPairKey.clear();
        roByRoKey.clear();
        roByPairKey.putAll(convertRoIndex(cached.roByPairKey));
        roByRoKey.putAll(buildRoByRoKey(roByPairKey));
        vyhybkaGpsStore = cached.vyhybkaGpsStore != null ? cached.vyhybkaGpsStore
                : VyhybkaGpsStore.empty();
        coordMemo.clear();
        preloadCoordsFromStore(vyhybkaGpsStore);
        spatialGrid = null;
        fullIndexReady = true;
        proximityLoaded = true;
    }

    private void applyFullIndexInMemory(Map<String, List<RoIndexEntry>> byPairKey,
                                        Map<String, RoIndexEntry> byRoKey,
                                        VyhybkaGpsStore gpsStore) {
        roByPairKey.clear();
        roByRoKey.clear();
        roByPairKey.putAll(byPairKey);
        roByRoKey.putAll(byRoKey);
        vyhybkaGpsStore = gpsStore != null ? gpsStore : VyhybkaGpsStore.empty();
        coordMemo.clear();
        preloadCoordsFromStore(vyhybkaGpsStore);
        spatialGrid = null;
        fullIndexReady = true;
        proximityLoaded = true;
    }

    private void preloadCoordsFromStore(VyhybkaGpsStore store) {
        if (store == null || store.isEmpty()) return;
        for (int i = 0; i < store.size(); i++) {
            coordMemo.put(memoKey(store.pairKeyAt(i), store.tuduAt(i), store.vyhybkaAt(i)),
                    new double[]{store.latitudeAt(i), store.longitudeAt(i)});
        }
    }

    private void reportIndexProgress(String phase, int percent) {
        if (percent >= 0) {
            if (indexProgressPercent >= 0 && percent < indexProgressPercent) {
                percent = indexProgressPercent;
            }
            indexProgressPhase = phase != null ? phase : "";
            indexProgressPercent = percent;
        } else {
            indexProgressPhase = phase != null ? phase : "";
            indexProgressPercent = percent;
        }
        IndexProgressListener listener = indexProgressListener;
        if (listener != null) {
            listener.onProgress(indexProgressPhase, indexProgressPercent);
        }
    }

    private void waitForForegroundIdle() throws InterruptedException {
        while (backgroundIndexRunning && foregroundDbOps > 0) {
            Thread.sleep(50);
        }
    }

    @FunctionalInterface
    private interface DbWork<T> {
        T run() throws Exception;
    }

  /**
     * Spustí DB operaci pod {@link #dbQueryLock} s opakováním při SQLITE_BUSY.
     * Vrátí {@code null}, pokud byla indexace mezitím zrušena ({@link #close()}).
     */
    private <T> T runDbWithLockRetry(DbWork<T> work) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < DB_LOCK_RETRY_COUNT; attempt++) {
            try {
                synchronized (dbQueryLock) {
                    waitForForegroundIdle();
                    if (!backgroundIndexRunning) return null;
                    return work.run();
                }
            } catch (InterruptedException e) {
                throw e;
            } catch (SQLiteException e) {
                last = e;
                if (!isTransientDbLock(e) || attempt + 1 >= DB_LOCK_RETRY_COUNT) {
                    throw e;
                }
                Log.w(TAG, "SQLite zaneprázdněna při indexaci, opakování " + (attempt + 2)
                        + "/" + DB_LOCK_RETRY_COUNT, e);
                Thread.sleep(DB_LOCK_RETRY_MS * (attempt + 1L));
            }
        }
        if (last != null) throw last;
        return null;
    }

    private static boolean isTransientDbLock(Exception e) {
        if (e == null) return false;
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("locked") || lower.contains("busy");
    }

    private DzsIndexCache.LoadedIndex tryLoadProximityFromDisk(double latitude, double longitude) {
        if (sourceDbFile == null || storageCacheDir == null) return null;
        File indexCacheDir = new File(storageCacheDir, "dzs_index");
        String key = DzsIndexCache.resolveCacheKey(sourceDbFile, indexCacheDir);
        double maxDistM = PROXIMITY_RELOAD_MOVE_KM * 1000.0;
        DzsIndexCache.LoadedIndex loaded = DzsIndexCache.tryLoadProximity(
                sourceDbFile, key, indexCacheDir, latitude, longitude, maxDistM);
        if (loaded != null) return loaded;
        String fastKey = DzsIndexCache.fastDbKey(sourceDbFile);
        if (fastKey.equals(key)) return null;
        return DzsIndexCache.tryLoadProximity(
                sourceDbFile, fastKey, indexCacheDir, latitude, longitude, maxDistM);
    }

    private int mergeProximityLoadedIndex(DzsIndexCache.LoadedIndex cached) {
        if (cached == null) return 0;
        int added = 0;
        Set<String> newlyAdded = new HashSet<>();
        for (Map.Entry<String, List<DzsIndexCache.RoEntry>> e : cached.roByPairKey.entrySet()) {
            String pairKey = e.getKey();
            String[] ids = splitPairKey(pairKey);
            for (DzsIndexCache.RoEntry ro : e.getValue()) {
                if (ro.roId == null || ro.roId.isEmpty()) continue;
                String entryRoKey = roKey(ids[0], ids[1], ro.roId);
                if (roByRoKey.containsKey(entryRoKey)) continue;
                Integer castMin = ro.castMin >= 0 ? ro.castMin : null;
                Integer castMax = ro.castMax >= 0 ? ro.castMax : null;
                RoIndexEntry entry = new RoIndexEntry(ro.tudu, ro.vyhybka, ro.iob, ro.roId,
                        castMin, castMax, ro.poloha);
                mergeRoEntry(ids[0], ids[1], entry);
                newlyAdded.add(entryRoKey);
                added++;
            }
        }
        VyhybkaGpsStore store = cached.vyhybkaGpsStore;
        if (store == null || store.isEmpty()) return added;
        if (vyhybkaGpsStore.isEmpty()) {
            vyhybkaGpsStore = store;
            return added;
        }
        if (newlyAdded.isEmpty()) return added;
        VyhybkaGpsStore.Builder gpsBuilder = VyhybkaGpsStore.builder();
        VyhybkaGpsStore.appendAll(gpsBuilder, vyhybkaGpsStore);
        for (int i = 0; i < store.size(); i++) {
            String pairKey = store.pairKeyAt(i);
            String[] ids = splitPairKey(pairKey);
            String entryRoKey = roKey(ids[0], ids[1], store.roIdAt(i));
            if (!newlyAdded.contains(entryRoKey)) continue;
            gpsBuilder.add(pairKey, store.tuduAt(i), store.vyhybkaAt(i), store.roIdAt(i),
                    store.polohaAt(i), store.latitudeAt(i), store.longitudeAt(i));
        }
        vyhybkaGpsStore = gpsBuilder.build();
        return added;
    }

    private void scheduleBackgroundContentHash() {
        if (sourceDbFile == null || storageCacheDir == null) return;
        final File dbFile = sourceDbFile;
        final File cacheDir = storageCacheDir;
        Thread t = new Thread(() -> {
            try {
                File indexCacheDir = new File(cacheDir, "dzs_index");
                String existing = DzsIndexCache.resolveCacheKey(dbFile, indexCacheDir);
                if (!existing.startsWith("f_")) return;
                String sha = DzsIndexCache.resolveContentHash(dbFile, indexCacheDir);
                synchronized (this) {
                    if (proximityLoaded && proximityCenterLat != null && proximityCenterLon != null
                            && !vyhybkaGpsStore.isEmpty()) {
                        saveProximityCache(sha, proximityCenterLat, proximityCenterLon);
                    }
                }
            } catch (Exception ignored) {
            }
        }, "dzs-db-hash");
        t.setDaemon(true);
        t.start();
    }

    private void applyLoadedProximity(DzsIndexCache.LoadedIndex cached,
                                      double latitude, double longitude) {
        roByPairKey.clear();
        roByRoKey.clear();
        roByPairKey.putAll(convertRoIndex(cached.roByPairKey));
        roByRoKey.putAll(buildRoByRoKey(roByPairKey));
        vyhybkaGpsStore = cached.vyhybkaGpsStore != null ? cached.vyhybkaGpsStore
                : VyhybkaGpsStore.empty();
        spatialGrid = null;
        proximityCenterLat = latitude;
        proximityCenterLon = longitude;
        proximityLoaded = true;
    }

    private void saveProximityCache(String contentHash, double centerLatitude, double centerLongitude) {
        if (sourceDbFile == null || storageCacheDir == null || contentHash == null) return;
        File indexCacheDir = new File(storageCacheDir, "dzs_index");
        DzsIndexCache.saveProximity(sourceDbFile, contentHash, indexCacheDir,
                centerLatitude, centerLongitude, toRoCache(roByPairKey), vyhybkaGpsStore);
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
                entries.add(new RoIndexEntry(ro.tudu, ro.vyhybka, ro.iob, ro.roId, castMin, castMax,
                        ro.poloha));
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
                entries.add(new DzsIndexCache.RoEntry(ro.tudu, ro.vyhybka, ro.iob, ro.roId, castMin,
                        castMax, ro.poloha));
            }
            map.put(e.getKey(), entries);
        }
        return map;
    }

    /** Počet UDU v načteném okolí GPS (bez SQL průchodu celé tabulky). */
    public int countDistinctTuduNearby() {
        Set<String> codes = new HashSet<>();
        for (List<RoIndexEntry> entries : roByPairKey.values()) {
            for (RoIndexEntry entry : entries) {
                codes.add(Tudu.uduCode(entry.tudu));
            }
        }
        return codes.size();
    }

    public int countDistinctTudu() {
        Integer fromSql = queryDistinctUduCount();
        if (fromSql != null) return fromSql;
        Set<String> codes = new HashSet<>();
        for (List<RoIndexEntry> entries : roByPairKey.values()) {
            for (RoIndexEntry entry : entries) {
                codes.add(Tudu.uduCode(entry.tudu));
            }
        }
        return codes.size();
    }

    private Integer queryDistinctUduCount() {
        String vyhybkaExpr = roColumns.vyhybkaSelectExpr(null);
        StringBuilder sql = new StringBuilder("SELECT COUNT(DISTINCT substr(")
                .append(roColumns.tudu).append(", 1, 5)) FROM ").append(TABLE_RO_TPI)
                .append(" WHERE ").append(roColumns.tudu).append(" IS NOT NULL AND ")
                .append(roColumns.tudu).append(" <> ''")
                .append(" AND ").append(vyhybkaExpr).append(" IS NOT NULL");
        roColumns.appendPolohaFilter(sql, null);
        try (Cursor c = db.rawQuery(sql.toString(), null)) {
            if (c.moveToFirst() && !c.isNull(0)) {
                return c.getInt(0);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public List<Tudu> loadAllTudu() {
        return queryTuduListFromSql(null, null);
    }

    /** Načte všechny podtypy TUDU pro danou stanici (UDU = prvních 5 znaků). */
    public List<Tudu> loadTuduForUdu(String udu) {
        if (udu == null || udu.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return queryTuduListFromSql(null, udu.trim());
    }

    public List<Tudu> loadTuduForCodes(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return loadAllTudu();
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
        if (coversAllCodes(fromMemory, wanted)) {
            return fromMemory;
        }
        return queryTuduListFromSql(wanted, null);
    }

    private static boolean coversAllCodes(List<Tudu> loaded, Set<String> wanted) {
        if (loaded.isEmpty()) return false;
        Set<String> found = new HashSet<>();
        for (Tudu t : loaded) found.add(t.code);
        return found.containsAll(wanted);
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
                Tudu.Vyhybka v = tudu.findOrCreate(ro.vyhybka, ro.iob);
                if (ro.castMin != null) v.castMin = ro.castMin;
                if (ro.castMax != null) v.castMax = ro.castMax;
                v.addRoBranch(ro.roId, ro.poloha);
            }
        }
        List<Tudu> out = new ArrayList<>(map.values());
        out.sort(Comparator.comparing(t -> t.code));
        return out;
    }

    private List<Tudu> queryTuduListFromSql(Set<String> fullCodes, String uduCode) {
        Map<String, Tudu> map = new LinkedHashMap<>();
        String vyhybkaExpr = roColumns.vyhybkaSelectExpr(null);
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(roColumns.tudu).append(", ").append(vyhybkaExpr);
        if (roColumns.castMin != null) sql.append(", ").append(roColumns.castMin);
        if (roColumns.castMax != null) sql.append(", ").append(roColumns.castMax);
        if (roColumns.poloha != null) sql.append(", ").append(roColumns.poloha);
        if (roColumns.iob != null) sql.append(", ").append(roColumns.iob);
        sql.append(", ").append(roColumns.roId);
        sql.append(" FROM ").append(TABLE_RO_TPI)
                .append(" WHERE ").append(roColumns.tudu).append(" IS NOT NULL AND ")
                .append(roColumns.tudu).append(" <> ''")
                .append(" AND ").append(vyhybkaExpr).append(" IS NOT NULL");
        roColumns.appendPolohaFilter(sql, null);

        List<String> args = new ArrayList<>();
        if (uduCode != null && !uduCode.isEmpty()) {
            roColumns.appendUduFilter(sql, null);
            args.add(uduCode);
        } else if (fullCodes != null && !fullCodes.isEmpty()) {
            StringBuilder placeholders = new StringBuilder();
            int i = 0;
            for (String code : fullCodes) {
                if (i++ > 0) placeholders.append(", ");
                placeholders.append('?');
                args.add(code);
            }
            sql.append(" AND ").append(roColumns.tudu).append(" IN (")
                    .append(placeholders).append(')');
        }
        sql.append(" ORDER BY ").append(roColumns.tudu).append(", ").append(vyhybkaExpr);
        if (roColumns.iob != null) sql.append(", ").append(roColumns.iob);

        try (Cursor c = db.rawQuery(sql.toString(), args.isEmpty() ? null : args.toArray(new String[0]))) {
            while (c.moveToNext()) {
                parseTuduRow(c, map);
            }
        }
        List<Tudu> out = new ArrayList<>(map.values());
        out.sort(Comparator.comparing(t -> t.code));
        return out;
    }

    private void parseTuduRow(Cursor c, Map<String, Tudu> map) {
        String tuduCode = c.getString(0);
        if (tuduCode == null || tuduCode.isEmpty()) return;
        Integer cislo = readInt(c, 1);
        if (cislo == null) return;

        Tudu tudu = map.get(tuduCode);
        if (tudu == null) {
            tudu = new Tudu(tuduCode);
            map.put(tuduCode, tudu);
        }
        int col = 2;
        Integer cmin = roColumns.castMin != null ? readInt(c, col++) : null;
        Integer cmax = roColumns.castMax != null ? readInt(c, col++) : null;
        String poloha = roColumns.poloha != null ? readTrimmedText(c, col++) : null;
        String iob = roColumns.iob != null ? readIobLetter(c, col++) : null;
        String roId = readId(c, col++);
        Tudu.Vyhybka v = tudu.findOrCreate(cislo, iob);
        CastRange cast = resolveCastRange(cmin, cmax, poloha);
        if (cast.castMin != null) v.castMin = cast.castMin;
        if (cast.castMax != null) v.castMax = cast.castMax;
        v.addRoBranch(roId, poloha);
    }

    /** Vrátí RO větve výhybky z indexu nebo SQL dotazu. */
    public List<Tudu.Vyhybka.RoBranch> findRoBranchesForVyhybka(String tuduCode, int cislo, String iob) {
        if (tuduCode == null || tuduCode.isEmpty() || cislo <= 0) {
            return Collections.emptyList();
        }
        String normIob = Tudu.Vyhybka.normalizeIob(iob);
        List<Tudu.Vyhybka.RoBranch> found = new ArrayList<>();
        for (List<RoIndexEntry> entries : roByPairKey.values()) {
            for (RoIndexEntry ro : entries) {
                if (!tuduCode.equals(ro.tudu) || ro.vyhybka != cislo) continue;
                if (!normIob.isEmpty() && !normIob.equals(ro.iob)) continue;
                found.add(new Tudu.Vyhybka.RoBranch(ro.roId, ro.poloha));
            }
        }
        if (!found.isEmpty()) return dedupeBranches(found);

        String vyhybkaExpr = roColumns.vyhybkaSelectExpr(null);
        StringBuilder sql = new StringBuilder("SELECT DISTINCT ")
                .append(roColumns.roId).append(", ");
        if (roColumns.poloha != null) sql.append(roColumns.poloha);
        else sql.append("''");
        sql.append(" FROM ").append(TABLE_RO_TPI)
                .append(" WHERE ").append(roColumns.tudu).append(" = ?")
                .append(" AND ").append(vyhybkaExpr).append(" = ?");
        roColumns.appendPolohaFilter(sql, null);
        List<String> args = new ArrayList<>();
        args.add(tuduCode);
        args.add(String.valueOf(cislo));
        if (roColumns.iob != null && !normIob.isEmpty()) {
            sql.append(" AND ").append(roColumns.iob).append(" = ?");
            args.add(normIob);
        }
        try (Cursor c = db.rawQuery(sql.toString(), args.toArray(new String[0]))) {
            while (c.moveToNext()) {
                String roId = readId(c, 0);
                String poloha = roColumns.poloha != null ? readTrimmedText(c, 1) : "";
                if (roId != null) found.add(new Tudu.Vyhybka.RoBranch(roId, poloha));
            }
        } catch (Exception ignored) {
        }
        return dedupeBranches(found);
    }

    private static List<Tudu.Vyhybka.RoBranch> dedupeBranches(List<Tudu.Vyhybka.RoBranch> branches) {
        Map<String, Tudu.Vyhybka.RoBranch> map = new LinkedHashMap<>();
        for (Tudu.Vyhybka.RoBranch b : branches) {
            if (!b.roId.isEmpty()) map.putIfAbsent(b.roId, b);
        }
        return new ArrayList<>(map.values());
    }

    /** Najde nejbližší výhybku podle předpočítaných souřadnic (RO_ID). */
    public GpsMatch findNearest(double latitude, double longitude) {
        ensureProximityLoaded(latitude, longitude);
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

    /** Nejbližší výhybka pro každý unikátní UDU (prvních 5 znaků TUDU), seřazené podle vzdálenosti. */
    public List<GpsMatch> findNearestDistinctTudu(double latitude, double longitude, int limit) {
        if (limit <= 0) return Collections.emptyList();
        ensureProximityLoaded(latitude, longitude);
        if (vyhybkaGpsStore.isEmpty()) return Collections.emptyList();
        Map<String, GpsMatch> bestByUdu = new HashMap<>();
        double cosLat = Math.cos(Math.toRadians(latitude));
        SpatialGrid grid = spatialGrid();
        for (int ring = 0; ring <= SpatialGrid.MAX_RING; ring++) {
            grid.forEachInRing(latitude, longitude, ring, idx -> {
                String udu = Tudu.uduCode(vyhybkaGpsStore.tuduAt(idx));
                double dLat = vyhybkaGpsStore.latitudeAt(idx) - latitude;
                double dLon = (vyhybkaGpsStore.longitudeAt(idx) - longitude) * cosLat;
                double distSq = dLat * dLat + dLon * dLon;
                GpsMatch existing = bestByUdu.get(udu);
                if (existing != null) {
                    double existingDistSq = approximateDistSq(
                            latitude, longitude, existing.latitude, existing.longitude, cosLat);
                    if (distSq >= existingDistSq) return;
                }
                bestByUdu.put(udu, vyhybkaToMatch(idx, latitude, longitude));
            });
            if (bestByUdu.size() >= limit) {
                List<GpsMatch> candidates = new ArrayList<>(bestByUdu.values());
                candidates.sort(Comparator.comparingDouble(m -> m.distanceM));
                GpsMatch nth = candidates.get(limit - 1);
                double nthDistSq = approximateDistSq(
                        latitude, longitude, nth.latitude, nth.longitude, cosLat);
                double ringBoundDeg = (ring + 1) * SpatialGrid.CELL_DEG;
                if (nthDistSq <= ringBoundDeg * ringBoundDeg) break;
            }
        }
        List<GpsMatch> sorted = new ArrayList<>(bestByUdu.values());
        sorted.sort(Comparator.comparingDouble(m -> m.distanceM));
        if (sorted.size() <= limit) return sorted;
        return new ArrayList<>(sorted.subList(0, limit));
    }

    /** Pro danou stanici (UDU) vrátí vzdálenosti k výhybkám všech podtypů TUDU. */
    public Map<String, Double> findVyhybkaDistancesForUdu(String uduCode,
                                                          double latitude, double longitude) {
        if (uduCode == null || uduCode.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Double> result = new HashMap<>();
        for (Tudu t : loadTuduForUdu(uduCode)) {
            Map<Integer, Double> perTudu = findVyhybkaDistancesForTudu(t.code, latitude, longitude);
            for (Tudu.Vyhybka v : t.vyhybky) {
                Double dist = perTudu.get(v.cislo);
                if (dist != null) {
                    result.put(t.code + "\0" + v.cislo + "\0" + v.iob, dist);
                }
            }
        }
        return result;
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
        String roIdExpr = "TRIM(CAST(g." + gpsColumns.roId + " AS TEXT))";
        String groupedGps = "(SELECT g." + gpsColumns.superZId + " AS super_z_id, g."
                + gpsColumns.superDId + " AS super_d_id, " + roIdExpr + " AS ro_id, MIN(g."
                + gpsColumns.latitude + ") AS lat, MIN(g." + gpsColumns.longitude + ") AS lon"
                + " FROM " + TABLE_GPS_KM + " g"
                + " INNER JOIN " + TEMP_RO_GPS_LOOKUP + " lk"
                + " ON g." + gpsColumns.superZId + " = lk.super_z_id"
                + " AND g." + gpsColumns.superDId + " = lk.super_d_id"
                + " AND " + roIdExpr + " = lk.ro_id"
                + " GROUP BY g." + gpsColumns.superZId + ", g." + gpsColumns.superDId + ", "
                + roIdExpr + ") gps";
        String sql = "SELECT ro.tudu, ro.vyhybka, gps.lat, gps.lon, ro.super_z_id, ro.super_d_id, ro.ro_id"
                + " FROM " + TEMP_RO_GPS_LOOKUP + " ro"
                + " INNER JOIN " + groupedGps + " gps"
                + " ON gps.super_z_id = ro.super_z_id"
                + " AND gps.super_d_id = ro.super_d_id"
                + " AND gps.ro_id = ro.ro_id";

        VyhybkaGpsStore.Builder builder = VyhybkaGpsStore.builder();
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
                RoIndexEntry ro = roByRoKey.get(roKey(superZId, superDId, roId));
                String poloha = ro != null ? ro.poloha : "";
                builder.add(pairKey(superZId, superDId), tudu, vyhybka, roId, poloha, lat, lon);
                matched++;
            }
        } catch (Exception e) {
            Log.w(TAG, "Hromadné párování GPS výhybek selhalo, použije se záložní postup", e);
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
                        builder.add(pairKey(ids[0], ids[1]), ro.tudu, ro.vyhybka, ro.roId,
                                ro.poloha, lat, lon);
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
                vyhybkaGpsStore.vyhybkaAt(idx), lat, lon, dist,
                vyhybkaGpsStore.polohaAt(idx), vyhybkaGpsStore.roIdAt(idx));
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

    private int loadProximityIndex(double latitude, double longitude, OpenProgressListener listener) {
        foregroundDbOps++;
        try {
            DzsIndexCache.LoadedIndex cached = tryLoadProximityFromDisk(latitude, longitude);
            if (cached != null) {
                int added = mergeProximityLoadedIndex(cached);
                spatialGrid = null;
                proximityCenterLat = latitude;
                proximityCenterLon = longitude;
                proximityLoaded = true;
                ensureBackgroundFullIndex();
                if (listener != null) {
                    report(listener, String.format(Locale.ROOT,
                            "Okolí GPS z cache (%d výhybek)", vyhybkaGpsStore.size()), 80);
                }
                return added;
            }

            DzsIndexCache.LoadedIndex queried = queryProximityBboxForeground(latitude, longitude);
            int added = mergeProximityLoadedIndex(queried);
            spatialGrid = null;
            proximityCenterLat = latitude;
            proximityCenterLon = longitude;
            proximityLoaded = true;
            scheduleBackgroundProximityCacheSave(latitude, longitude);
            ensureBackgroundFullIndex();
            if (listener != null) {
                report(listener, String.format(Locale.ROOT,
                        "Okolí GPS načteno (%d výhybek)", added), 80);
            }
            return added;
        } finally {
            foregroundDbOps--;
        }
    }

    private DzsIndexCache.LoadedIndex queryProximityBboxForeground(double latitude, double longitude) {
        synchronized (dbQueryLock) {
            return queryProximityBboxUnlocked(latitude, longitude);
        }
    }

    private DzsIndexCache.LoadedIndex queryProximityBboxUnlocked(double latitude, double longitude) {
        double minLat = latitude - PROXIMITY_BBOX_DEG;
        double maxLat = latitude + PROXIMITY_BBOX_DEG;
        double minLon = longitude - PROXIMITY_BBOX_DEG;
        double maxLon = longitude + PROXIMITY_BBOX_DEG;

        String gRoIdExpr = "TRIM(CAST(g." + gpsColumns.roId + " AS TEXT))";
        String groupedGps = "(SELECT g." + gpsColumns.superZId + " AS super_z_id, g."
                + gpsColumns.superDId + " AS super_d_id, " + gRoIdExpr + " AS ro_id, MIN(g."
                + gpsColumns.latitude + ") AS lat, MIN(g." + gpsColumns.longitude + ") AS lon"
                + " FROM " + TABLE_GPS_KM + " g"
                + " WHERE g." + gpsColumns.latitude + " BETWEEN ? AND ?"
                + " AND g." + gpsColumns.longitude + " BETWEEN ? AND ?"
                + " GROUP BY g." + gpsColumns.superZId + ", g." + gpsColumns.superDId + ", "
                + gRoIdExpr + ") g";

        String roRoIdExpr = "TRIM(CAST(ro." + roColumns.roId + " AS TEXT))";
        String vyhybkaExpr = roColumns.vyhybkaSelectExpr("ro");
        StringBuilder sql = new StringBuilder("SELECT ro.")
                .append(roColumns.superZId).append(", ro.").append(roColumns.superDId).append(", ro.")
                .append(roColumns.tudu).append(", ").append(vyhybkaExpr).append(", ro.")
                .append(roColumns.roId);
        if (roColumns.castMin != null) sql.append(", ro.").append(roColumns.castMin);
        if (roColumns.castMax != null) sql.append(", ro.").append(roColumns.castMax);
        if (roColumns.poloha != null) sql.append(", ro.").append(roColumns.poloha);
        if (roColumns.iob != null) sql.append(", ro.").append(roColumns.iob);
        sql.append(", g.lat, g.lon");
        sql.append(" FROM ").append(groupedGps);
        sql.append(" INNER JOIN ").append(TABLE_RO_TPI).append(" ro ON g.super_z_id = ro.")
                .append(roColumns.superZId);
        sql.append(" AND g.super_d_id = ro.").append(roColumns.superDId);
        sql.append(" AND g.ro_id = ").append(roRoIdExpr);
        sql.append(" WHERE ro.").append(roColumns.tudu).append(" IS NOT NULL AND ro.")
                .append(roColumns.tudu).append(" <> ''");
        sql.append(" AND ").append(vyhybkaExpr).append(" IS NOT NULL");
        sql.append(" AND ro.").append(roColumns.roId).append(" IS NOT NULL AND ")
                .append(roRoIdExpr).append(" <> ''");
        roColumns.appendPolohaFilter(sql, "ro");

        String[] args = {
                String.valueOf(minLat), String.valueOf(maxLat),
                String.valueOf(minLon), String.valueOf(maxLon)
        };

        Map<String, List<RoIndexEntry>> localRoByPair = new HashMap<>();
        VyhybkaGpsStore.Builder gpsBuilder = VyhybkaGpsStore.builder();
        Set<String> seenRoKeys = new HashSet<>();

        try (Cursor c = db.rawQuery(sql.toString(), args)) {
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
                String iob = roColumns.iob != null ? readIobLetter(c, col++) : null;
                Double lat = readDouble(c, col++);
                Double lon = readDouble(c, col++);
                if (lat == null || lon == null) continue;

                CastRange cast = resolveCastRange(castMin, castMax, poloha);
                RoIndexEntry entry = new RoIndexEntry(tudu, vyhybka, iob, roId, cast.castMin,
                        cast.castMax, poloha);
                String entryRoKey = roKey(superZId, superDId, roId);
                if (!seenRoKeys.add(entryRoKey)) continue;
                localRoByPair.computeIfAbsent(pairKey(superZId, superDId), k -> new ArrayList<>())
                        .add(entry);
                gpsBuilder.add(pairKey(superZId, superDId), tudu, vyhybka, roId, poloha, lat, lon);
            }
        } catch (Exception ignored) {
        }

        return new DzsIndexCache.LoadedIndex(toRoCache(localRoByPair), gpsBuilder.build());
    }

    private void mergeRoEntry(String superZId, String superDId, RoIndexEntry entry) {
        String key = pairKey(superZId, superDId);
        roByPairKey.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
        if (entry.roId != null) {
            roByRoKey.put(roKey(superZId, superDId, entry.roId), entry);
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
        if (roColumns.iob != null) sql.append(", ").append(roColumns.iob);
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
                String iob = roColumns.iob != null ? readIobLetter(c, col++) : null;
                CastRange cast = resolveCastRange(castMin, castMax, poloha);
                RoIndexEntry entry = new RoIndexEntry(tudu, vyhybka, iob, roId, cast.castMin,
                        cast.castMax, poloha);
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
        backgroundIndexRunning = false;
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

    private static String readIobLetter(Cursor c, int index) {
        String raw = readTrimmedText(c, index);
        return Tudu.Vyhybka.normalizeIob(raw);
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
        final String iob;
        final String roId;

        RoColumns(String superZId, String superDId, String tudu, String vyhybka,
                  String vyhybkaFallback, String castMin, String castMax, String poloha,
                  String iob, String roId) {
            this.superZId = superZId;
            this.superDId = superDId;
            this.tudu = tudu;
            this.vyhybka = vyhybka;
            this.vyhybkaFallback = vyhybkaFallback;
            this.castMin = castMin;
            this.castMax = castMax;
            this.poloha = poloha;
            this.iob = iob;
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

        void appendUduFilter(StringBuilder sql, String tableAlias) {
            String prefix = tableAlias == null || tableAlias.isEmpty()
                    ? "" : tableAlias + ".";
            sql.append(" AND substr(").append(prefix).append(tudu).append(", 1, 5) = ?");
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
                    findOptionalColumn(cols, "IOB"),
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
