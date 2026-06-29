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
 * 1) V DZS_SUPERTRA_GPS_KM najít GPS bod nejblíže aktuální poloze (všechny km body, ne jeden na pár).
 * 2) Z něj vzít SUPER_Z_ID, SUPER_D_ID a KM_EXT.
 * 3) V DZS_SUPER_RO_TPI podle těchto ID dohledat TUDU a výhybku (COBJEKT).
 *    Při více výhybkách na stejném páru ID rozlišit středem (OD+DO)/2 oproti KM_EXT.
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

    private static final class SpatialGrid {
        private static final double CELL_DEG = 0.005;
        private static final int MAX_RING = 40;

        private final GpsPointStore store;
        private final Map<Long, int[]> cells = new HashMap<>();
        private final int[] allIndices;

        SpatialGrid(GpsPointStore store) {
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
    private final GpsPointStore gpsStore;
    private volatile SpatialGrid spatialGrid;

    private DzsDatabase(SQLiteDatabase db, GpsColumns gpsColumns, RoColumns roColumns,
                        Map<String, List<RoIndexEntry>> roByPairKey, GpsPointStore gpsStore) {
        this.db = db;
        this.gpsColumns = gpsColumns;
        this.roColumns = roColumns;
        this.roByPairKey = roByPairKey;
        this.gpsStore = gpsStore;
    }

    private SpatialGrid spatialGrid() {
        SpatialGrid grid = spatialGrid;
        if (grid == null) {
            synchronized (this) {
                grid = spatialGrid;
                if (grid == null) {
                    grid = new SpatialGrid(gpsStore);
                    spatialGrid = grid;
                }
            }
        }
        return grid;
    }

    /** Sestaví prostorový index (volat z IO vlákna před návratem do UI). */
    void ensureSpatialIndex(OpenProgressListener listener) {
        if (gpsStore.isEmpty()) {
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

            GpsPointStore gpsStore;
            Map<String, List<RoIndexEntry>> roByPairKey;
            if (cached != null) {
                roByPairKey = convertRoIndex(cached.roByPairKey);
                gpsStore = cached.gpsStore;
                report(listener, "Cache indexu načtena", 90);
            } else {
                report(listener, "Indexuji výhybky", 20);
                roByPairKey = buildRoIndex(db, roColumns);
                report(listener, "Indexuji GPS body", 50);
                int expectedGpsRows = countGpsRowsForPairs(db, gpsColumns, roByPairKey.keySet());
                gpsStore = buildGpsIndex(db, gpsColumns, roByPairKey, expectedGpsRows, listener);
                report(listener, "Ukládám cache", 85);
                if (cacheDir != null) {
                    if (contentHash == null) {
                        contentHash = DzsIndexCache.computeContentHash(sourceFile);
                    }
                    saveIndexCache(sourceFile, contentHash, cacheDir, roByPairKey, gpsStore, listener);
                }
            }
            DzsDatabase opened = new DzsDatabase(db, gpsColumns, roColumns, roByPairKey, gpsStore);
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
                                       GpsPointStore gpsStore,
                                       OpenProgressListener listener) {
        DzsIndexCache.save(dbFile, contentHash, new File(cacheDir, "dzs_index"),
                toRoCache(roByPairKey), gpsStore,
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

    /**
     * Najde nejbližší výhybku: nejbližší GPS km bod → KM_EXT → výhybka přes (OD+DO)/2.
     */
    public GpsMatch findNearest(double latitude, double longitude) {
        if (gpsStore.isEmpty()) return null;

        final int[] bestIdx = {-1};
        final double[] bestDistSq = {Double.MAX_VALUE};
        double cosLat = Math.cos(Math.toRadians(latitude));

        spatialGrid().forEachNearest(latitude, longitude, idx -> {
            double dLat = gpsStore.latitudeAt(idx) - latitude;
            double dLon = (gpsStore.longitudeAt(idx) - longitude) * cosLat;
            double distSq = dLat * dLat + dLon * dLon;
            if (distSq < bestDistSq[0]) {
                bestDistSq[0] = distSq;
                bestIdx[0] = idx;
            }
        });

        if (bestIdx[0] < 0) return null;
        return gpsToMatch(bestIdx[0], latitude, longitude);
    }

    /**
     * Nejbližší body pro každý unikátní TUDU kód, seřazené podle vzdálenosti.
     */
    public List<GpsMatch> findNearestDistinctTudu(double latitude, double longitude, int limit) {
        if (limit <= 0) return Collections.emptyList();
        if (gpsStore.isEmpty()) return Collections.emptyList();

        Map<String, GpsMatch> bestByTudu = new HashMap<>();
        double cosLat = Math.cos(Math.toRadians(latitude));

        spatialGrid().forEachNearest(latitude, longitude, idx -> {
            String pairKey = gpsStore.pairKeyAt(idx);
            double kmExt = gpsStore.kmExtAt(idx);
            RoIndexEntry ro = resolveRoEntry(pairKey, kmExt);
            if (ro == null) return;

            double dLat = gpsStore.latitudeAt(idx) - latitude;
            double dLon = (gpsStore.longitudeAt(idx) - longitude) * cosLat;
            double distSq = dLat * dLat + dLon * dLon;

            GpsMatch existing = bestByTudu.get(ro.tudu);
            if (existing != null) {
                double existingDistSq = approximateDistSq(
                        latitude, longitude, existing.latitude, existing.longitude, cosLat);
                if (distSq >= existingDistSq) return;
            }

            String[] ids = splitPairKey(pairKey);
            double dist = haversineM(latitude, longitude, gpsStore.latitudeAt(idx), gpsStore.longitudeAt(idx));
            bestByTudu.put(ro.tudu, new GpsMatch(ids[0], ids[1], ro.tudu, ro.vyhybka,
                    gpsStore.latitudeAt(idx), gpsStore.longitudeAt(idx), dist));
        });

        List<GpsMatch> sorted = new ArrayList<>(bestByTudu.values());
        sorted.sort(Comparator.comparingDouble(m -> m.distanceM));
        if (sorted.size() <= limit) return sorted;
        return new ArrayList<>(sorted.subList(0, limit));
    }

    /**
     * Pro daný TUDU vrátí nejbližší vzdálenost (m) k jednotlivým výhybkám
     * podle GPS km bodů spárovaných přes (OD+DO)/2 ↔ KM_EXT.
     */
    public Map<Integer, Double> findVyhybkaDistancesForTudu(String tuduCode,
                                                              double latitude, double longitude) {
        if (tuduCode == null || tuduCode.isEmpty()) {
            return Collections.emptyMap();
        }
        String trimmedTudu = tuduCode.trim();
        if (trimmedTudu.isEmpty()) return Collections.emptyMap();

        Map<Integer, Double> bestByVyhybka = new HashMap<>();
        for (Map.Entry<String, List<RoIndexEntry>> pairEntry : roByPairKey.entrySet()) {
            for (RoIndexEntry ro : pairEntry.getValue()) {
                if (!trimmedTudu.equals(ro.tudu)) continue;
                int gpsIdx = findGpsIndexForMidKm(pairEntry.getKey(), ro.midKm);
                if (gpsIdx < 0) continue;
                recordVyhybkaDistance(bestByVyhybka, ro.vyhybka, latitude, longitude,
                        gpsStore.latitudeAt(gpsIdx), gpsStore.longitudeAt(gpsIdx));
            }
        }
        return bestByVyhybka;
    }

    private GpsMatch gpsToMatch(int gpsIdx, double userLat, double userLon) {
        String pairKey = gpsStore.pairKeyAt(gpsIdx);
        double kmExt = gpsStore.kmExtAt(gpsIdx);
        RoIndexEntry ro = resolveRoEntry(pairKey, kmExt);
        if (ro == null) return null;
        String[] ids = splitPairKey(pairKey);
        double lat = gpsStore.latitudeAt(gpsIdx);
        double lon = gpsStore.longitudeAt(gpsIdx);
        double dist = haversineM(userLat, userLon, lat, lon);
        return new GpsMatch(ids[0], ids[1], ro.tudu, ro.vyhybka, lat, lon, dist);
    }

    /** Vybere výhybku pro pár ID podle shody (OD+DO)/2 s KM_EXT. */
    private RoIndexEntry resolveRoEntry(String pairKey, double kmExt) {
        List<RoIndexEntry> entries = roByPairKey.get(pairKey);
        if (entries == null || entries.isEmpty()) return null;
        return matchRoByKm(entries, kmExt);
    }

    private int findGpsIndexForMidKm(String pairKey, Double midKm) {
        int[] indices = gpsStore.indicesForPair(pairKey);
        if (indices == null || indices.length == 0) return -1;
        if (midKm == null) return indices[0];
        return matchGpsByKm(gpsStore, indices, midKm);
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

    private static GpsPointStore buildGpsIndex(SQLiteDatabase db, GpsColumns gpsColumns,
                                               Map<String, List<RoIndexEntry>> roByPairKey,
                                               int expectedRows, OpenProgressListener listener) {
        if (roByPairKey.isEmpty()) return GpsPointStore.empty();
        Set<String> pairKeys = roByPairKey.keySet();
        GpsPointStore fromJoin = buildGpsIndexJoin(db, gpsColumns, pairKeys, expectedRows, listener);
        if (fromJoin != null) return fromJoin;
        int tableRows = countTableRows(db, TABLE_GPS_KM);
        return buildGpsIndexFullScan(db, gpsColumns, pairKeys, expectedRows, tableRows, listener);
    }

    /**
     * Načte všechny GPS km body pro páry z RO indexu (včetně KM_EXT).
     * Žádná deduplikace na jeden bod na pár – každý km bod je samostatný záznam.
     * Bez ORDER BY a CAST ve SQL – parsování probíhá v Javě (rychlejší na velkých tabulkách).
     */
    private static GpsPointStore buildGpsIndexJoin(SQLiteDatabase db, GpsColumns gpsColumns,
                                                   Set<String> pairKeys, int expectedRows,
                                                   OpenProgressListener listener) {
        if (!populateRoPairsTempTable(db, pairKeys)) return null;

        String sql = "SELECT g." + gpsColumns.superZId + ", g." + gpsColumns.superDId + ", "
                + gpsColumns.kmExt + ", " + gpsColumns.latitude + ", " + gpsColumns.longitude
                + " FROM " + TABLE_GPS_KM + " g"
                + " INNER JOIN " + TEMP_RO_PAIRS + " rp"
                + "   ON g." + gpsColumns.superZId + " = rp.super_z_id"
                + "   AND g." + gpsColumns.superDId + " = rp.super_d_id";

        return readGpsKmIndexCursor(db, sql, null, listener, null, expectedRows, -1);
    }

    /**
     * Záložní postup – jeden sekvenční průchod GPS tabulkou s filtrem párů v paměti.
     * Použije se jen když selže dočasná tabulka nebo JOIN dotaz; nikdy ne dotaz po jednom páru.
     */
    private static GpsPointStore buildGpsIndexFullScan(SQLiteDatabase db, GpsColumns gpsColumns,
                                                       Set<String> pairKeys, int expectedRows,
                                                       int tableRows, OpenProgressListener listener) {
        HashSet<String> pairFilter = new HashSet<>(pairKeys);
        String sql = "SELECT " + gpsColumns.superZId + ", " + gpsColumns.superDId + ", "
                + gpsColumns.kmExt + ", " + gpsColumns.latitude + ", " + gpsColumns.longitude
                + " FROM " + TABLE_GPS_KM;
        return readGpsKmIndexCursor(db, sql, null, listener, pairFilter, expectedRows, tableRows);
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

    private static GpsPointStore readGpsKmIndexCursor(SQLiteDatabase db, String sql, String[] args,
                                                        OpenProgressListener listener,
                                                        Set<String> pairFilter, int expectedRows,
                                                        int tableRows) {
        int capacity = expectedRows > 0 ? expectedRows : 65536;
        GpsPointStore.Builder builder = GpsPointStore.builder(capacity);
        try (Cursor c = db.rawQuery(sql, args)) {
            int processed = 0;
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
                processed++;
                if (processed % GPS_INDEX_PROGRESS_INTERVAL == 0
                        || (pairFilter != null && scanned % GPS_INDEX_PROGRESS_INTERVAL == 0)) {
                    reportGpsIndexProgress(listener, processed, expectedRows, tableRows, scanned, pairFilter != null);
                }
            }
            reportGpsIndexProgress(listener, processed, expectedRows, tableRows, scanned, pairFilter != null);
            if (listener != null && processed > 0) {
                report(listener, gpsProgressPhase(processed, pairFilter != null ? scanned : -1), 84);
            }
            return builder.build();
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void reportGpsIndexProgress(OpenProgressListener listener, int processed,
                                               int expectedRows, int tableRows, int scanned,
                                               boolean fullScan) {
        if (listener == null || processed <= 0) return;
        int pct;
        if (expectedRows > 0) {
            pct = 50 + (int) Math.min(34L, (processed * 34L) / expectedRows);
        } else if (fullScan && tableRows > 0) {
            pct = 50 + (int) Math.min(34L, (scanned * 34L) / tableRows);
        } else {
            pct = 50 + Math.min(34, processed / GPS_INDEX_PROGRESS_INTERVAL);
        }
        report(listener, gpsProgressPhase(processed, fullScan ? scanned : -1), pct);
    }

    private static String gpsProgressPhase(int processed, int scanned) {
        if (scanned >= 0) {
            return String.format(Locale.ROOT, "Indexuji GPS body (%s / %s řádků)",
                    formatCount(processed), formatCount(scanned));
        }
        return String.format(Locale.ROOT, "Indexuji GPS body (%s)", formatCount(processed));
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
     * Vybere výhybku podle shody středu OD/DO s KM_EXT.
     * Priorita: přesná shoda → okno ±0,5 → zaokrouhlení → nejbližší km.
     */
    private static RoIndexEntry matchRoByKm(List<RoIndexEntry> entries, double kmExt) {
        if (entries.size() == 1) return entries.get(0);

        RoIndexEntry exact = null;
        RoIndexEntry inRange = null;
        RoIndexEntry rounded = null;
        RoIndexEntry nearest = null;
        double nearestDiff = Double.MAX_VALUE;

        for (RoIndexEntry e : entries) {
            if (e.midKm == null) continue;
            double mid = e.midKm;
            if (mid == kmExt) {
                exact = e;
                break;
            }
            if (mid >= kmExt - 0.5 && mid < kmExt + 0.5) {
                inRange = e;
            }
            if (Math.round(mid) == Math.round(kmExt)) {
                rounded = e;
            }
            double diff = Math.abs(mid - kmExt);
            if (diff < nearestDiff) {
                nearestDiff = diff;
                nearest = e;
            }
        }

        if (exact != null) return exact;
        if (inRange != null) return inRange;
        if (rounded != null) return rounded;
        if (nearest != null) return nearest;
        return entries.get(0);
    }

    /** Stejná logika pro výběr GPS km bodu podle midKm. */
    private static int matchGpsByKm(GpsPointStore store, int[] indices, double midKm) {
        if (indices.length == 1) return indices[0];

        int exact = -1;
        int inRange = -1;
        int rounded = -1;
        int nearest = -1;
        double nearestDiff = Double.MAX_VALUE;

        for (int idx : indices) {
            double km = store.kmExtAt(idx);
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
        List<GpsPoint> out = new ArrayList<>(gpsStore.size());
        for (int i = 0; i < gpsStore.size(); i++) {
            String pairKey = gpsStore.pairKeyAt(i);
            double kmExt = gpsStore.kmExtAt(i);
            RoIndexEntry ro = resolveRoEntry(pairKey, kmExt);
            String label = ro == null ? "" : ro.tudu + " · výhybka " + ro.vyhybka;
            out.add(new GpsPoint(gpsStore.latitudeAt(i), gpsStore.longitudeAt(i), label));
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
            String lat = findRequiredColumn(cols, "LAT", "LAN", "LATITUDE", "GPS_LAT", "SIRKA", "GPS_SIRKA", "Y");
            String lon = findRequiredColumn(cols, "LON", "LONGITUDE", "LNG", "GPS_LON", "DELKA", "GPS_DELKA", "X");
            String kmExt = findRequiredColumn(cols, "KM_EXT", "KM_INT", "KM", "KILOMETR", "KMK");
            return new GpsColumns(superZId, superDId, lat, lon, kmExt);
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
