package com.rfidw.app.data;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Disková cache paměťových indexů DZS databáze.
 * Platnost indexu je vázaná na velikost a SHA-256 obsahu databáze.
 *
 * Verze 18 přidává POLOHA k RO indexu a k předpočítaným GPS souřadnicím výhybek.
 * Verze 17 přidává volitelné IOB písmeno k číslu výhybky (COBJEKT).
 * Verze 16 ukládá RO index (RO_ID) včetně rozsahu částí odvozeného z POLOHA
 * (J = 3, C = 4) a předpočítané GPS souřadnice výhybek.
 */
final class DzsIndexCache {

    private static final int MAGIC = 0x445A5349; // "DZSI"
    private static final int VERSION = 18;
    /** Cache indexu okolí GPS (bbox ±0,04° ~4 km). */
    private static final int PROXIMITY_VERSION = 19;
    /** Per-cell cache výhybek (~1 km mřížka) – bez SQLite indexů na zdrojové DB. */
    private static final int CELL_VERSION = 20;
    private static final int VERSION_LEGACY_V16 = 16;
    private static final int VERSION_LEGACY_V14 = 14;
    private static final int VERSION_LEGACY_V13 = 13;
    private static final int HASH_HEX_LEN = 64;
    /** {@link RoEntry#castMin} / {@link RoEntry#castMax} pokud sloupec v DB chybí. */
    static final int CAST_UNSPECIFIED = -1;

    static final class RoEntry {
        final String tudu;
        final int vyhybka;
        final String iob;
        final String roId;
        final int castMin;
        final int castMax;
        final String poloha;

        RoEntry(String tudu, int vyhybka, String iob, String roId, int castMin, int castMax,
                String poloha) {
            this.tudu = tudu;
            this.vyhybka = vyhybka;
            this.iob = iob != null ? iob : "";
            this.roId = roId;
            this.castMin = castMin;
            this.castMax = castMax;
            this.poloha = poloha != null ? poloha : "";
        }
    }

    static final class LoadedIndex {
        final Map<String, List<RoEntry>> roByPairKey;
        final VyhybkaGpsStore vyhybkaGpsStore;

        LoadedIndex(Map<String, List<RoEntry>> roByPairKey, VyhybkaGpsStore vyhybkaGpsStore) {
            this.roByPairKey = roByPairKey;
            this.vyhybkaGpsStore = vyhybkaGpsStore != null ? vyhybkaGpsStore : VyhybkaGpsStore.empty();
        }
    }

    private DzsIndexCache() {
    }

    /**
     * Rychlý klíč cache bez čtení celého souboru (velikost + lastModified).
     * Použít na kritické cestě otevírání; plný SHA-256 jen na pozadí.
     */
    static String fastDbKey(File dbFile) {
        if (dbFile == null || !dbFile.isFile()) return "";
        return String.format(Locale.ROOT, "f_%016x_%016x",
                dbFile.length(), dbFile.lastModified());
    }

    /** Vrátí uložený SHA-256 nebo {@link #fastDbKey} – nikdy nespočítá hash synchronně. */
    static String resolveCacheKey(File dbFile, File cacheDir) {
        if (dbFile == null || !dbFile.isFile()) return "";
        if (cacheDir != null) {
            String cached = readStoredHash(dbFile, cacheDir);
            if (cached != null) return cached;
        }
        return fastDbKey(dbFile);
    }

    static String resolveContentHash(File dbFile, File cacheDir) throws IOException {
        if (dbFile == null || !dbFile.isFile()) {
            throw new IOException("Databáze nenalezena");
        }
        if (cacheDir != null) {
            String cached = readStoredHash(dbFile, cacheDir);
            if (cached != null) return cached;
        }
        String hash = computeContentHash(dbFile);
        if (cacheDir != null) {
            storeHash(dbFile, hash, cacheDir);
        }
        return hash;
    }

    static String computeContentHash(File dbFile) throws IOException {
        if (dbFile == null || !dbFile.isFile()) {
            throw new IOException("Databáze nenalezena");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            try (InputStream in = new FileInputStream(dbFile)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    digest.update(buf, 0, n);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(HASH_HEX_LEN);
            for (byte b : hash) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Nelze spočítat otisk databáze", e);
        }
    }

    /**
     * Rozsah buněk mřížky (~1 km) pokrývající bbox ±{@code bboxDeg} kolem polohy.
     * Vrací {@code [minLatCell, maxLatCell, minLonCell, maxLonCell]}.
     */
    static int[] bboxCellRange(double latitude, double longitude, double bboxDeg) {
        return new int[]{
                (int) Math.floor((latitude - bboxDeg) * 100.0),
                (int) Math.ceil((latitude + bboxDeg) * 100.0),
                (int) Math.floor((longitude - bboxDeg) * 100.0),
                (int) Math.ceil((longitude + bboxDeg) * 100.0)
        };
    }

    /** Všechny buňky v bbox mají soubor {@code .cidx}? */
    static boolean allBBoxCellsCached(String contentHash, File cacheDir,
                                      double latitude, double longitude, double bboxDeg) {
        if (contentHash == null || contentHash.isEmpty() || cacheDir == null) return false;
        int[] range = bboxCellRange(latitude, longitude, bboxDeg);
        for (int latCell = range[0]; latCell <= range[1]; latCell++) {
            for (int lonCell = range[2]; lonCell <= range[3]; lonCell++) {
                if (!cellCacheFileFor(contentHash, cacheDir, latCell, lonCell).isFile()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Načte a sloučí všechny dostupné {@code .cidx} buňky v bbox okolí GPS.
     * Vrátí {@code null}, pokud žádná buňka neexistuje.
     */
    static LoadedIndex tryLoadProximityCells(File dbFile, String contentHash, File cacheDir,
                                             double latitude, double longitude, double bboxDeg) {
        if (dbFile == null || !dbFile.isFile() || contentHash == null || contentHash.isEmpty()) {
            return null;
        }
        if (cacheDir == null) return null;
        int[] range = bboxCellRange(latitude, longitude, bboxDeg);
        Map<String, List<RoEntry>> ro = new HashMap<>();
        VyhybkaGpsStore.Builder gpsBuilder = VyhybkaGpsStore.builder();
        Set<String> seenRoKeys = new HashSet<>();
        Set<String> seenGpsKeys = new HashSet<>();
        boolean any = false;
        for (int latCell = range[0]; latCell <= range[1]; latCell++) {
            for (int lonCell = range[2]; lonCell <= range[3]; lonCell++) {
                File cellFile = cellCacheFileFor(contentHash, cacheDir, latCell, lonCell);
                if (!cellFile.isFile()) continue;
                LoadedIndex part = readCellIndex(dbFile.length(), contentHash, cellFile);
                if (part == null) continue;
                any = true;
                mergeInto(ro, gpsBuilder, seenRoKeys, seenGpsKeys, part);
            }
        }
        if (!any) return null;
        return new LoadedIndex(ro, gpsBuilder.build());
    }

    /** Uloží výhybky z paměti do per-cell {@code .cidx} souborů (mřížka ~1 km). */
    static void saveProximityCells(File dbFile, String contentHash, File cacheDir,
                                   Map<String, List<RoEntry>> roByPairKey,
                                   VyhybkaGpsStore vyhybkaGpsStore) {
        if (dbFile == null || cacheDir == null || !dbFile.isFile()) return;
        if (contentHash == null || contentHash.isEmpty()) return;
        if (vyhybkaGpsStore == null || vyhybkaGpsStore.isEmpty()) return;
        if (!cacheDir.exists() && !cacheDir.mkdirs()) return;

        Map<Long, CellBucket> buckets = new HashMap<>();
        for (int i = 0; i < vyhybkaGpsStore.size(); i++) {
            int latCell = (int) Math.round(vyhybkaGpsStore.latitudeAt(i) * 100.0);
            int lonCell = (int) Math.round(vyhybkaGpsStore.longitudeAt(i) * 100.0);
            long cellKey = packCell(latCell, lonCell);
            CellBucket bucket = buckets.get(cellKey);
            if (bucket == null) {
                bucket = new CellBucket(latCell, lonCell);
                buckets.put(cellKey, bucket);
            }
            bucket.gpsIndices.add(i);
            String pairKey = vyhybkaGpsStore.pairKeyAt(i);
            String roId = vyhybkaGpsStore.roIdAt(i);
            if (roId == null || roId.isEmpty()) continue;
            String roLookup = pairKey + "|" + roId;
            if (bucket.roKeys.add(roLookup)) {
                RoEntry ro = findRoEntry(roByPairKey, pairKey, roId);
                if (ro != null) {
                    bucket.roEntries.computeIfAbsent(pairKey, k -> new ArrayList<>()).add(ro);
                }
            }
        }

        for (CellBucket bucket : buckets.values()) {
            saveCellBucket(dbFile, contentHash, cacheDir, bucket, vyhybkaGpsStore);
        }
    }

    /**
     * Po SQL průchodu označí všechny zbývající buňky v bbox jako prázdné,
     * aby další načtení stejné oblasti nemuselo znovu skenovat databázi.
     */
    static void finalizeProximityCellCache(File dbFile, String contentHash, File cacheDir,
                                           double latitude, double longitude, double bboxDeg) {
        if (dbFile == null || cacheDir == null || !dbFile.isFile()) return;
        if (contentHash == null || contentHash.isEmpty()) return;
        if (!cacheDir.exists() && !cacheDir.mkdirs()) return;
        int[] range = bboxCellRange(latitude, longitude, bboxDeg);
        for (int latCell = range[0]; latCell <= range[1]; latCell++) {
            for (int lonCell = range[2]; lonCell <= range[3]; lonCell++) {
                File cellFile = cellCacheFileFor(contentHash, cacheDir, latCell, lonCell);
                if (!cellFile.isFile()) {
                    saveEmptyCellBucket(dbFile, contentHash, cacheDir, latCell, lonCell);
                }
            }
        }
    }

  /**
     * Načte cache okolí GPS, pokud sedí otisk DB a střed cache je blízko požadované polohy.
     *
     * @param maxCenterDistM maximální vzdálenost středů (m); typicky {@code PROXIMITY_RELOAD_MOVE_KM * 1000}
     */
    static LoadedIndex tryLoadProximity(File dbFile, String contentHash, File cacheDir,
                                        double latitude, double longitude, double maxCenterDistM) {
        if (dbFile == null || !dbFile.isFile() || contentHash == null || contentHash.isEmpty()) {
            return null;
        }
        if (cacheDir == null) return null;
        File cacheFile = proximityCacheFileFor(contentHash, cacheDir, latitude, longitude);
        if (!cacheFile.isFile()) return null;
        return readProximityIndex(dbFile.length(), contentHash, cacheFile, latitude, longitude,
                maxCenterDistM);
    }

    static boolean saveProximity(File dbFile, String contentHash, File cacheDir,
                                 double centerLatitude, double centerLongitude,
                                 Map<String, List<RoEntry>> roByPairKey,
                                 VyhybkaGpsStore vyhybkaGpsStore) {
        if (dbFile == null || cacheDir == null || !dbFile.isFile()) return false;
        if (contentHash == null || contentHash.isEmpty()) return false;
        if (!cacheDir.exists() && !cacheDir.mkdirs()) return false;
        File cacheFile = proximityCacheFileFor(contentHash, cacheDir, centerLatitude, centerLongitude);
        File tmp = new File(cacheDir, cacheFile.getName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(
                new GZIPOutputStream(new FileOutputStream(tmp)))) {
            out.writeInt(MAGIC);
            out.writeInt(PROXIMITY_VERSION);
            out.writeLong(dbFile.length());
            out.writeUTF(contentHash);
            out.writeDouble(centerLatitude);
            out.writeDouble(centerLongitude);
            int roCount = 0;
            for (List<RoEntry> entries : roByPairKey.values()) {
                roCount += entries.size();
            }
            out.writeInt(roCount);
            for (Map.Entry<String, List<RoEntry>> e : roByPairKey.entrySet()) {
                for (RoEntry ro : e.getValue()) {
                    out.writeUTF(e.getKey());
                    out.writeUTF(ro.tudu);
                    out.writeInt(ro.vyhybka);
                    out.writeUTF(ro.iob);
                    out.writeUTF(ro.roId);
                    out.writeInt(ro.castMin);
                    out.writeInt(ro.castMax);
                    out.writeUTF(ro.poloha);
                }
            }
            writeVyhybkaGpsStore(out, vyhybkaGpsStore, true);
            out.flush();
        } catch (Exception ignored) {
            tmp.delete();
            return false;
        }
        if (!tmp.renameTo(cacheFile)) {
            tmp.delete();
            return false;
        }
        return true;
    }

    static LoadedIndex tryLoad(File dbFile, String contentHash, File cacheDir) {
        if (dbFile == null || !dbFile.isFile() || contentHash == null || contentHash.isEmpty()) {
            return null;
        }
        if (cacheDir == null) return null;

        File cacheFile = cacheFileFor(contentHash, cacheDir);
        if (!cacheFile.isFile()) return null;
        return readIndex(dbFile.length(), contentHash, cacheFile);
    }

    @FunctionalInterface
    interface SaveProgressListener {
        void onWritten(int written, int total);
    }

    static boolean save(File dbFile, String contentHash, File cacheDir,
                     Map<String, List<RoEntry>> roByPairKey,
                     VyhybkaGpsStore vyhybkaGpsStore,
                     SaveProgressListener progress) {
        if (dbFile == null || cacheDir == null || !dbFile.isFile()) return false;
        if (contentHash == null || contentHash.length() != HASH_HEX_LEN) return false;
        if (!cacheDir.exists() && !cacheDir.mkdirs()) return false;
        File cacheFile = cacheFileFor(contentHash, cacheDir);
        File tmp = new File(cacheDir, cacheFile.getName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(tmp)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeLong(dbFile.length());
            out.writeUTF(contentHash);
            int roCount = 0;
            for (List<RoEntry> entries : roByPairKey.values()) {
                roCount += entries.size();
            }
            out.writeInt(roCount);
            int written = 0;
            for (Map.Entry<String, List<RoEntry>> e : roByPairKey.entrySet()) {
                for (RoEntry ro : e.getValue()) {
                    out.writeUTF(e.getKey());
                    out.writeUTF(ro.tudu);
                    out.writeInt(ro.vyhybka);
                    out.writeUTF(ro.iob);
                    out.writeUTF(ro.roId);
                    out.writeInt(ro.castMin);
                    out.writeInt(ro.castMax);
                    out.writeUTF(ro.poloha);
                    written++;
                    if (progress != null && (written % 10_000 == 0 || written == roCount)) {
                        progress.onWritten(written, roCount);
                    }
                }
            }
            writeVyhybkaGpsStore(out, vyhybkaGpsStore, true);
            out.flush();
        } catch (Exception ignored) {
            tmp.delete();
            return false;
        }
        if (!tmp.renameTo(cacheFile)) {
            tmp.delete();
            return false;
        }
        return true;
    }

    private static LoadedIndex readIndex(long dbSize, String contentHash, File indexFile) {
        if (indexFile == null || !indexFile.isFile()) return null;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(indexFile)))) {
            if (in.readInt() != MAGIC) return null;
            int version = in.readInt();
            if (version != VERSION) {
                return null;
            }
            long cachedSize = in.readLong();
            String cachedHash = in.readUTF();
            if (cachedSize != dbSize || !contentHash.equals(cachedHash)) {
                return null;
            }
            int roCount = in.readInt();
            Map<String, List<RoEntry>> ro = new HashMap<>(Math.max(roCount / 2, 16));
            boolean hasRoId = false;
            for (int i = 0; i < roCount; i++) {
                String pairKey = in.readUTF();
                String tudu = in.readUTF();
                int vyhybka = in.readInt();
                String iob = in.readUTF();
                String roId = in.readUTF();
                int castMin = in.readInt();
                int castMax = in.readInt();
                String poloha = in.readUTF();
                if (roId == null || roId.isEmpty()) continue;
                hasRoId = true;
                RoEntry entry = new RoEntry(tudu, vyhybka, iob, roId, castMin, castMax, poloha);
                ro.computeIfAbsent(pairKey, k -> new ArrayList<>()).add(entry);
            }
            if (!hasRoId) return null;

            VyhybkaGpsStore vyhybkaGpsStore = readVyhybkaGpsStore(in, true);
            return new LoadedIndex(ro, vyhybkaGpsStore);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writeVyhybkaGpsStore(DataOutputStream out, VyhybkaGpsStore store,
                                             boolean withPoloha) throws IOException {
        if (store == null || store.isEmpty()) {
            out.writeInt(0);
            return;
        }
        out.writeInt(store.size());
        for (int i = 0; i < store.size(); i++) {
            out.writeUTF(store.pairKeyAt(i));
            out.writeUTF(store.tuduAt(i));
            out.writeInt(store.vyhybkaAt(i));
            out.writeFloat((float) store.latitudeAt(i));
            out.writeFloat((float) store.longitudeAt(i));
            if (withPoloha) {
                out.writeUTF(store.roIdAt(i));
                out.writeUTF(store.polohaAt(i));
            }
        }
    }

    private static VyhybkaGpsStore readVyhybkaGpsStore(DataInputStream in, boolean withPoloha)
            throws IOException {
        int count = in.readInt();
        if (count <= 0) return VyhybkaGpsStore.empty();
        VyhybkaGpsStore.Builder builder = VyhybkaGpsStore.builder();
        for (int i = 0; i < count; i++) {
            String pairKey = in.readUTF();
            String tudu = in.readUTF();
            int vyhybka = in.readInt();
            float lat = in.readFloat();
            float lon = in.readFloat();
            String roId = "";
            String poloha = "";
            if (withPoloha) {
                roId = in.readUTF();
                poloha = in.readUTF();
            }
            builder.add(pairKey, tudu, vyhybka, roId, poloha, lat, lon);
        }
        return builder.build();
    }

    private static LoadedIndex readProximityIndex(long dbSize, String contentHash, File indexFile,
                                                    double latitude, double longitude,
                                                    double maxCenterDistM) {
        if (indexFile == null || !indexFile.isFile()) return null;
        try (DataInputStream in = new DataInputStream(
                new GZIPInputStream(new FileInputStream(indexFile)))) {
            if (in.readInt() != MAGIC) return null;
            if (in.readInt() != PROXIMITY_VERSION) return null;
            long cachedSize = in.readLong();
            String cachedHash = in.readUTF();
            if (cachedSize != dbSize || !contentHash.equals(cachedHash)) {
                return null;
            }
            double centerLat = in.readDouble();
            double centerLon = in.readDouble();
            if (haversineM(centerLat, centerLon, latitude, longitude) > maxCenterDistM) {
                return null;
            }
            int roCount = in.readInt();
            Map<String, List<RoEntry>> ro = new HashMap<>(Math.max(roCount / 2, 16));
            boolean hasRoId = false;
            for (int i = 0; i < roCount; i++) {
                String pairKey = in.readUTF();
                String tudu = in.readUTF();
                int vyhybka = in.readInt();
                String iob = in.readUTF();
                String roId = in.readUTF();
                int castMin = in.readInt();
                int castMax = in.readInt();
                String poloha = in.readUTF();
                if (roId == null || roId.isEmpty()) continue;
                hasRoId = true;
                RoEntry entry = new RoEntry(tudu, vyhybka, iob, roId, castMin, castMax, poloha);
                ro.computeIfAbsent(pairKey, k -> new ArrayList<>()).add(entry);
            }
            if (!hasRoId) return null;
            VyhybkaGpsStore vyhybkaGpsStore = readVyhybkaGpsStore(in, true);
            return new LoadedIndex(ro, vyhybkaGpsStore);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Exception ignored) {
            return null;
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

    private static File cacheFileFor(String contentHash, File cacheDir) {
        return new File(cacheDir, "dzs_" + contentHash + ".idx");
    }

    /** Buňka ~0,01° (~1 km) pro název souboru cache okolí. */
    private static File proximityCacheFileFor(String contentHash, File cacheDir,
                                              double latitude, double longitude) {
        int latCell = (int) Math.round(latitude * 100.0);
        int lonCell = (int) Math.round(longitude * 100.0);
        return new File(cacheDir, "dzs_" + contentHash + "_p_" + latCell + "_" + lonCell + ".pidx");
    }

    private static File cellCacheFileFor(String contentHash, File cacheDir,
                                         int latCell, int lonCell) {
        return new File(cacheDir, "dzs_" + contentHash + "_cell_" + latCell + "_" + lonCell + ".cidx");
    }

    private static long packCell(int latCell, int lonCell) {
        return ((long) latCell << 32) | (lonCell & 0xFFFFFFFFL);
    }

    private static final class CellBucket {
        final int latCell;
        final int lonCell;
        final Set<String> roKeys = new HashSet<>();
        final Map<String, List<RoEntry>> roEntries = new HashMap<>();
        final List<Integer> gpsIndices = new ArrayList<>();

        CellBucket(int latCell, int lonCell) {
            this.latCell = latCell;
            this.lonCell = lonCell;
        }
    }

    private static RoEntry findRoEntry(Map<String, List<RoEntry>> roByPairKey,
                                       String pairKey, String roId) {
        List<RoEntry> entries = roByPairKey.get(pairKey);
        if (entries == null) return null;
        for (RoEntry ro : entries) {
            if (roId.equals(ro.roId)) return ro;
        }
        return null;
    }

    private static void mergeInto(Map<String, List<RoEntry>> ro,
                                  VyhybkaGpsStore.Builder gpsBuilder,
                                  Set<String> seenRoKeys,
                                  Set<String> seenGpsKeys,
                                  LoadedIndex part) {
        for (Map.Entry<String, List<RoEntry>> e : part.roByPairKey.entrySet()) {
            String pairKey = e.getKey();
            for (RoEntry entry : e.getValue()) {
                if (entry.roId == null || entry.roId.isEmpty()) continue;
                String roLookup = pairKey + "|" + entry.roId;
                if (!seenRoKeys.add(roLookup)) continue;
                ro.computeIfAbsent(pairKey, k -> new ArrayList<>()).add(entry);
            }
        }
        VyhybkaGpsStore store = part.vyhybkaGpsStore;
        for (int i = 0; i < store.size(); i++) {
            String pairKey = store.pairKeyAt(i);
            String roId = store.roIdAt(i);
            if (roId == null || roId.isEmpty()) continue;
            String roLookup = pairKey + "|" + roId;
            if (!seenGpsKeys.add(roLookup)) continue;
            gpsBuilder.add(pairKey, store.tuduAt(i), store.vyhybkaAt(i),
                    roId, store.polohaAt(i), store.latitudeAt(i), store.longitudeAt(i));
        }
    }

    private static LoadedIndex readCellIndex(long dbSize, String contentHash, File indexFile) {
        if (indexFile == null || !indexFile.isFile()) return null;
        try (DataInputStream in = new DataInputStream(
                new GZIPInputStream(new FileInputStream(indexFile)))) {
            if (in.readInt() != MAGIC) return null;
            if (in.readInt() != CELL_VERSION) return null;
            long cachedSize = in.readLong();
            String cachedHash = in.readUTF();
            if (cachedSize != dbSize || !contentHash.equals(cachedHash)) {
                return null;
            }
            in.readInt(); // latCell
            in.readInt(); // lonCell
            int roCount = in.readInt();
            Map<String, List<RoEntry>> ro = new HashMap<>(Math.max(roCount / 2, 8));
            if (roCount == 0) {
                VyhybkaGpsStore emptyGps = readVyhybkaGpsStore(in, true);
                if (!emptyGps.isEmpty()) return null;
                return new LoadedIndex(ro, VyhybkaGpsStore.empty());
            }
            boolean hasRoId = false;
            for (int i = 0; i < roCount; i++) {
                String pairKey = in.readUTF();
                String tudu = in.readUTF();
                int vyhybka = in.readInt();
                String iob = in.readUTF();
                String roId = in.readUTF();
                int castMin = in.readInt();
                int castMax = in.readInt();
                String poloha = in.readUTF();
                if (roId == null || roId.isEmpty()) continue;
                hasRoId = true;
                RoEntry entry = new RoEntry(tudu, vyhybka, iob, roId, castMin, castMax, poloha);
                ro.computeIfAbsent(pairKey, k -> new ArrayList<>()).add(entry);
            }
            if (!hasRoId) return null;
            VyhybkaGpsStore vyhybkaGpsStore = readVyhybkaGpsStore(in, true);
            return new LoadedIndex(ro, vyhybkaGpsStore);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void saveCellBucket(File dbFile, String contentHash, File cacheDir,
                                       CellBucket bucket, VyhybkaGpsStore store) {
        File cacheFile = cellCacheFileFor(contentHash, cacheDir, bucket.latCell, bucket.lonCell);
        File tmp = new File(cacheDir, cacheFile.getName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(
                new GZIPOutputStream(new FileOutputStream(tmp)))) {
            out.writeInt(MAGIC);
            out.writeInt(CELL_VERSION);
            out.writeLong(dbFile.length());
            out.writeUTF(contentHash);
            out.writeInt(bucket.latCell);
            out.writeInt(bucket.lonCell);
            int roCount = 0;
            for (List<RoEntry> entries : bucket.roEntries.values()) {
                roCount += entries.size();
            }
            out.writeInt(roCount);
            for (Map.Entry<String, List<RoEntry>> e : bucket.roEntries.entrySet()) {
                for (RoEntry ro : e.getValue()) {
                    out.writeUTF(e.getKey());
                    out.writeUTF(ro.tudu);
                    out.writeInt(ro.vyhybka);
                    out.writeUTF(ro.iob);
                    out.writeUTF(ro.roId);
                    out.writeInt(ro.castMin);
                    out.writeInt(ro.castMax);
                    out.writeUTF(ro.poloha);
                }
            }
            VyhybkaGpsStore.Builder cellGps = VyhybkaGpsStore.builder();
            for (int idx : bucket.gpsIndices) {
                cellGps.add(store.pairKeyAt(idx), store.tuduAt(idx), store.vyhybkaAt(idx),
                        store.roIdAt(idx), store.polohaAt(idx),
                        store.latitudeAt(idx), store.longitudeAt(idx));
            }
            writeVyhybkaGpsStore(out, cellGps.build(), true);
            out.flush();
        } catch (Exception ignored) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(cacheFile)) {
            tmp.delete();
        }
    }

    private static void saveEmptyCellBucket(File dbFile, String contentHash, File cacheDir,
                                            int latCell, int lonCell) {
        File cacheFile = cellCacheFileFor(contentHash, cacheDir, latCell, lonCell);
        File tmp = new File(cacheDir, cacheFile.getName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(
                new GZIPOutputStream(new FileOutputStream(tmp)))) {
            out.writeInt(MAGIC);
            out.writeInt(CELL_VERSION);
            out.writeLong(dbFile.length());
            out.writeUTF(contentHash);
            out.writeInt(latCell);
            out.writeInt(lonCell);
            out.writeInt(0);
            writeVyhybkaGpsStore(out, VyhybkaGpsStore.empty(), true);
            out.flush();
        } catch (Exception ignored) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(cacheFile)) {
            tmp.delete();
        }
    }

    private static File hashSidecarFile(File dbFile, File cacheDir) {
        String key = Long.toHexString(dbFile.length()) + "_"
                + Long.toHexString(dbFile.lastModified()) + "_"
                + Integer.toHexString(dbFile.getAbsolutePath().hashCode());
        return new File(cacheDir, "hash2_" + key + ".txt");
    }

    private static String readStoredHash(File dbFile, File cacheDir) {
        if (cacheDir == null) return null;
        File sidecar = hashSidecarFile(dbFile, cacheDir);
        if (!sidecar.isFile()) return null;
        try (DataInputStream in = new DataInputStream(new FileInputStream(sidecar))) {
            long storedSize = in.readLong();
            long storedModified = in.readLong();
            String hash = in.readUTF();
            if (storedSize != dbFile.length()
                    || storedModified != dbFile.lastModified()
                    || hash.length() != HASH_HEX_LEN) {
                return null;
            }
            return hash;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void storeHash(File dbFile, String hash, File cacheDir) {
        if (cacheDir == null || hash == null || hash.length() != HASH_HEX_LEN) return;
        if (!cacheDir.exists() && !cacheDir.mkdirs()) return;
        File sidecar = hashSidecarFile(dbFile, cacheDir);
        File tmp = new File(cacheDir, sidecar.getName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(tmp))) {
            out.writeLong(dbFile.length());
            out.writeLong(dbFile.lastModified());
            out.writeUTF(hash);
            out.flush();
        } catch (Exception ignored) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(sidecar)) {
            tmp.delete();
        }
    }
}
