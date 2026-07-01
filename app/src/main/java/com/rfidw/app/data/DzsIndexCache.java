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
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Disková cache paměťových indexů DZS databáze.
 * Platnost indexu je vázaná na velikost a SHA-256 obsahu databáze.
 *
 * Verze 20 kompaktně ukládá GPS body (index RO_ID + souřadnice) a hlásí průběh zápisu.
 * Verze 19 ukládá všechny GPS body výhybek a krajní body RO_ID (první/poslední km bod).
 * Verze 18 přidává POLOHA k RO indexu a k předpočítaným GPS souřadnicím výhybek.
 * Verze 17 přidává volitelné IOB písmeno k číslu výhybky (COBJEKT).
 * Verze 16 ukládá RO index (RO_ID) včetně rozsahu částí odvozeného z POLOHA
 * (J = 3, C = 4) a předpočítané GPS souřadnice výhybek.
 */
final class DzsIndexCache {

    private static final int MAGIC = 0x445A5349; // "DZSI"
    private static final int VERSION = 20;
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
        final RoGpsEndpoints roGpsEndpoints;

        LoadedIndex(Map<String, List<RoEntry>> roByPairKey, VyhybkaGpsStore vyhybkaGpsStore,
                    RoGpsEndpoints roGpsEndpoints) {
            this.roByPairKey = roByPairKey;
            this.vyhybkaGpsStore = vyhybkaGpsStore != null ? vyhybkaGpsStore : VyhybkaGpsStore.empty();
            this.roGpsEndpoints = roGpsEndpoints != null ? roGpsEndpoints : RoGpsEndpoints.empty();
        }
    }

    private DzsIndexCache() {
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
        void onProgress(String phase, int written, int total);
    }

    private static final int GPS_WRITE_PROGRESS_STEP = 50_000;

    static boolean save(File dbFile, String contentHash, File cacheDir,
                     Map<String, List<RoEntry>> roByPairKey,
                     VyhybkaGpsStore vyhybkaGpsStore,
                     RoGpsEndpoints roGpsEndpoints,
                     SaveProgressListener progress) {
        if (dbFile == null || cacheDir == null || !dbFile.isFile()) return false;
        if (contentHash == null || contentHash.length() != HASH_HEX_LEN) return false;
        if (!cacheDir.exists() && !cacheDir.mkdirs()) return false;
        File cacheFile = cacheFileFor(contentHash, cacheDir);
        File tmp = new File(cacheDir, cacheFile.getName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(
                new FileOutputStream(tmp), new Deflater(Deflater.BEST_SPEED, true)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeLong(dbFile.length());
            out.writeUTF(contentHash);
            int roCount = 0;
            for (List<RoEntry> entries : roByPairKey.values()) {
                roCount += entries.size();
            }
            int gpsCount = vyhybkaGpsStore != null ? vyhybkaGpsStore.size() : 0;
            int endpointCount = roGpsEndpoints != null ? roGpsEndpoints.size() : 0;
            int totalWork = roCount + gpsCount + endpointCount;
            int written = 0;

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
                    written++;
                    if (progress != null && (written % 10_000 == 0 || written == roCount)) {
                        progress.onProgress("výhybky", written, totalWork);
                    }
                }
            }
            written = writeVyhybkaGpsStoreCompact(out, vyhybkaGpsStore, written, totalWork, progress);
            written = writeRoGpsEndpoints(out, roGpsEndpoints, written, totalWork, progress);
            if (progress != null) {
                progress.onProgress("dokončuji", totalWork, totalWork);
            }
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

            Map<String, RoEntry> roByRoKey = new HashMap<>(Math.max(roCount, 16));
            Map<String, String> pairKeyByRoKey = new HashMap<>(Math.max(roCount, 16));
            for (Map.Entry<String, List<RoEntry>> e : ro.entrySet()) {
                for (RoEntry roEntry : e.getValue()) {
                    String roKey = roKey(e.getKey(), roEntry.roId);
                    roByRoKey.put(roKey, roEntry);
                    pairKeyByRoKey.put(roKey, e.getKey());
                }
            }

            VyhybkaGpsStore vyhybkaGpsStore = readVyhybkaGpsStoreCompact(in, roByRoKey, pairKeyByRoKey);
            RoGpsEndpoints roGpsEndpoints = readRoGpsEndpoints(in);
            return new LoadedIndex(ro, vyhybkaGpsStore, roGpsEndpoints);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int writeVyhybkaGpsStoreCompact(DataOutputStream out, VyhybkaGpsStore store,
                                                 int writtenSoFar, int totalWork,
                                                 SaveProgressListener progress) throws IOException {
        if (store == null || store.isEmpty()) {
            out.writeInt(0);
            out.writeInt(0);
            return writtenSoFar;
        }
        Map<String, Integer> roKeyToIndex = new HashMap<>();
        List<String> roKeyTable = new ArrayList<>();
        for (int i = 0; i < store.size(); i++) {
            String roKey = store.roKeyAt(i);
            if (!roKeyToIndex.containsKey(roKey)) {
                roKeyToIndex.put(roKey, roKeyTable.size());
                roKeyTable.add(roKey);
            }
        }
        out.writeInt(store.size());
        out.writeInt(roKeyTable.size());
        for (String roKey : roKeyTable) {
            out.writeUTF(roKey);
        }
        int written = writtenSoFar;
        for (int i = 0; i < store.size(); i++) {
            int roKeyIndex = roKeyToIndex.get(store.roKeyAt(i));
            if (roKeyIndex > 0xFFFF) {
                throw new IOException("Příliš mnoho RO_ID v GPS indexu");
            }
            out.writeShort(roKeyIndex);
            out.writeFloat((float) store.latitudeAt(i));
            out.writeFloat((float) store.longitudeAt(i));
            written++;
            if (progress != null && (i % GPS_WRITE_PROGRESS_STEP == 0 || i + 1 == store.size())) {
                progress.onProgress("GPS body", written, totalWork);
            }
        }
        return written;
    }

    private static VyhybkaGpsStore readVyhybkaGpsStoreCompact(DataInputStream in,
                                                              Map<String, RoEntry> roByRoKey,
                                                              Map<String, String> pairKeyByRoKey)
            throws IOException {
        int count = in.readInt();
        if (count <= 0) {
            in.readInt(); // prázdná tabulka roKey
            return VyhybkaGpsStore.empty();
        }
        int roKeyCount = in.readInt();
        if (roKeyCount <= 0) return VyhybkaGpsStore.empty();
        String[] roKeys = new String[roKeyCount];
        for (int i = 0; i < roKeyCount; i++) {
            roKeys[i] = in.readUTF();
        }
        VyhybkaGpsStore.Builder builder = VyhybkaGpsStore.builder();
        for (int i = 0; i < count; i++) {
            int roKeyIndex = in.readShort() & 0xFFFF;
            float lat = in.readFloat();
            float lon = in.readFloat();
            if (roKeyIndex < 0 || roKeyIndex >= roKeys.length) continue;
            String roKey = roKeys[roKeyIndex];
            RoEntry ro = roByRoKey.get(roKey);
            String pairKey = pairKeyByRoKey.get(roKey);
            if (ro == null || pairKey == null) continue;
            builder.add(pairKey, ro.tudu, ro.vyhybka, ro.roId, ro.poloha, lat, lon);
        }
        return builder.build();
    }

    private static int writeRoGpsEndpoints(DataOutputStream out, RoGpsEndpoints endpoints,
                                           int writtenSoFar, int totalWork,
                                           SaveProgressListener progress) throws IOException {
        if (endpoints == null || endpoints.isEmpty()) {
            out.writeInt(0);
            return writtenSoFar;
        }
        int count = endpoints.size();
        out.writeInt(count);
        int written = writtenSoFar;
        for (Map.Entry<String, RoGpsEndpoints.Endpoint> e : endpoints.entries()) {
            RoGpsEndpoints.Endpoint ep = e.getValue();
            out.writeUTF(e.getKey());
            out.writeFloat((float) ep.firstLatitude);
            out.writeFloat((float) ep.firstLongitude);
            out.writeFloat((float) ep.lastLatitude);
            out.writeFloat((float) ep.lastLongitude);
            written++;
            if (progress != null) {
                progress.onProgress("konce úseků", written, totalWork);
            }
        }
        return written;
    }

    private static String roKey(String pairKey, String roId) {
        return pairKey + "|" + (roId != null ? roId : "");
    }

    private static RoGpsEndpoints readRoGpsEndpoints(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count <= 0) return RoGpsEndpoints.empty();
        Map<String, RoGpsEndpoints.Endpoint> map = new HashMap<>(count);
        for (int i = 0; i < count; i++) {
            String roKey = in.readUTF();
            double firstLat = in.readFloat();
            double firstLon = in.readFloat();
            double lastLat = in.readFloat();
            double lastLon = in.readFloat();
            map.put(roKey, new RoGpsEndpoints.Endpoint(
                    firstLat, firstLon, lastLat, lastLon));
        }
        return RoGpsEndpoints.fromEntries(map);
    }

    private static File cacheFileFor(String contentHash, File cacheDir) {
        return new File(cacheDir, "dzs_" + contentHash + ".idx");
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
