package com.rfidw.app.data;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

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

/**
 * SQLite zdroj TUDU / výhybek z tabulek DZS_SUPERTRA_GPS_KM a DZS_SUPER_RO_TPI.
 *
 * Při otevření se indexuje jen tabulka výhybek (RO) – řádově sekundy.
 * GPS km body se neprocházejí: vyhledávání podle aktuální polohy proběhne až
 * při GPS dotazu (bounding box + index na lat/lon, nebo cílený dotaz na pár ID).
 */
public class DzsDatabase implements Closeable {

    public static final String TABLE_GPS_KM = "DZS_SUPERTRA_GPS_KM";
    public static final String TABLE_RO_TPI = "DZS_SUPER_RO_TPI";

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

    private static final class GpsKmPoint {
        final String superZId;
        final String superDId;
        final String pairKey;
        final double kmExt;
        final double latitude;
        final double longitude;

        GpsKmPoint(String superZId, String superDId, double kmExt, double latitude, double longitude) {
            this.superZId = superZId;
            this.superDId = superDId;
            this.pairKey = pairKey(superZId, superDId);
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
    private final Map<String, List<RoIndexEntry>> roByPairKey;
    /** Memoizace souřadnic výhybek (pairKey|tudu|vyhybka → [lat, lon]). */
    private final Map<String, double[]> coordMemo = new HashMap<>();
    private volatile boolean gpsLatLonIndexReady;

    private DzsDatabase(SQLiteDatabase db, GpsColumns gpsColumns, RoColumns roColumns,
                        Map<String, List<RoIndexEntry>> roByPairKey) {
        this.db = db;
        this.gpsColumns = gpsColumns;
        this.roColumns = roColumns;
        this.roByPairKey = roByPairKey;
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

            Map<String, List<RoIndexEntry>> roByPairKey;
            if (cached != null) {
                roByPairKey = convertRoIndex(cached.roByPairKey);
                report(listener, "Cache indexu načtena", 90);
            } else {
                report(listener, "Indexuji výhybky", 20);
                roByPairKey = buildRoIndex(db, roColumns);
                report(listener, "Ukládám cache", 85);
                if (cacheDir != null) {
                    if (contentHash == null) {
                        contentHash = DzsIndexCache.computeContentHash(sourceFile);
                    }
                    saveIndexCache(sourceFile, contentHash, cacheDir, roByPairKey, listener);
                }
            }
            DzsDatabase opened = new DzsDatabase(db, gpsColumns, roColumns, roByPairKey);
            if (cached != null && cached.vyhybkaGpsStore != null) {
                opened.preloadCoordsFromStore(cached.vyhybkaGpsStore);
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

    private void preloadCoordsFromStore(VyhybkaGpsStore store) {
        if (store == null || store.isEmpty()) return;
        for (int i = 0; i < store.size(); i++) {
            coordMemo.put(memoKey(store.pairKeyAt(i), store.tuduAt(i), store.vyhybkaAt(i)),
                    new double[]{store.latitudeAt(i), store.longitudeAt(i)});
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

    /** Zda je připraven index na sloupce LAT/LON (první GPS dotaz ho může vytvářet). */
    public boolean isGpsLatLonIndexReady() {
        return gpsLatLonIndexReady;
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
                                       OpenProgressListener listener) {
        DzsIndexCache.save(dbFile, contentHash, new File(cacheDir, "dzs_index"),
                toRoCache(roByPairKey),
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
     * Najde nejbližší výhybku podle aktuální GPS polohy.
     * Dotazuje jen km body v okolí – celá tabulka se při otevření neprochází.
     */
    public GpsMatch findNearest(double latitude, double longitude) {
        if (roByPairKey.isEmpty()) return null;
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
                + gpsColumns.kmExt + ", " + gpsColumns.latitude + ", " + gpsColumns.longitude
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
                Double kmExt = readDouble(c, 2);
                Double lat = readDouble(c, 3);
                Double lon = readDouble(c, 4);
                if (superZId == null || superDId == null || kmExt == null || lat == null || lon == null) {
                    continue;
                }
                consumer.accept(new GpsKmPoint(superZId, superDId, kmExt, lat, lon));
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Vrátí souřadnici výhybky přes indexovaný dotaz na pár ID + KM_EXT.
     * Nepotřebuje předchozí průchod celé GPS tabulky.
     */
    private double[] resolveVyhybkaCoord(String pairKey, RoIndexEntry ro) {
        String key = memoKey(pairKey, ro.tudu, ro.vyhybka);
        double[] cached = coordMemo.get(key);
        if (cached != null) return cached;

        String[] ids = splitPairKey(pairKey);
        String sql;
        String[] args;
        if (ro.midKm != null) {
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
        for (Map.Entry<String, List<RoIndexEntry>> e : roByPairKey.entrySet()) {
            for (RoIndexEntry ro : e.getValue()) {
                double[] coord = resolveVyhybkaCoord(e.getKey(), ro);
                if (coord == null) continue;
                String label = ro.tudu + " · výhybka " + ro.vyhybka;
                out.add(new GpsPoint(coord[0], coord[1], label));
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
