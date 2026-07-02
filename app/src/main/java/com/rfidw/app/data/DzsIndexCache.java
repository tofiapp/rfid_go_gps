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
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    /** Cache indexu okolí GPS (jen výhybky v bbox ±0,05°). */
    private static final int PROXIMITY_VERSION = 19;
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
