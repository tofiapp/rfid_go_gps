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
 * Platnost indexu je vázaná na velikost a SHA-256 obsahu databáze – přežije
 * kopírování souboru a restart aplikace (na rozdíl od lastModified).
 */
final class DzsIndexCache {

    private static final int MAGIC = 0x445A5349; // "DZSI"
    private static final int VERSION = 8;
    private static final int HASH_HEX_LEN = 64;

    static final class RoEntry {
        final String tudu;
        final int vyhybka;
        /** Střed OD a DO; {@link Double#NaN} pokud není k dispozici. */
        final double midKm;

        RoEntry(String tudu, int vyhybka, double midKm) {
            this.tudu = tudu;
            this.vyhybka = vyhybka;
            this.midKm = midKm;
        }
    }

    static final class LoadedIndex {
        final Map<String, List<RoEntry>> roByPairKey;
        final GpsPointStore gpsStore;

        LoadedIndex(Map<String, List<RoEntry>> roByPairKey, GpsPointStore gpsStore) {
            this.roByPairKey = roByPairKey;
            this.gpsStore = gpsStore;
        }
    }

    private DzsIndexCache() {
    }

    /** SHA-256 celého souboru databáze (hex, lowercase). */
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
     * Načte platný index z cache aplikace podle otisku obsahu databáze.
     */
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
    interface IndexBodyWriter {
        void write(DataOutputStream out) throws IOException;
    }

    @FunctionalInterface
    interface SaveProgressListener {
        void onGpsWritten(int written, int total);
    }

    static void save(File dbFile, String contentHash, File cacheDir,
                     Map<String, List<RoEntry>> roByPairKey, GpsPointStore gpsStore,
                     SaveProgressListener progress) {
        saveBody(dbFile, contentHash, cacheDir, out -> {
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
                    out.writeDouble(ro.midKm);
                }
            }
            writeGpsStore(out, gpsStore, progress);
        });
    }

    private static void writeGpsStore(DataOutputStream out, GpsPointStore store,
                                      SaveProgressListener progress) throws IOException {
        String[] pairKeys = store.pairKeyTable();
        out.writeInt(pairKeys.length);
        for (String key : pairKeys) {
            out.writeUTF(key);
        }
        int gpsTotal = store.size();
        out.writeInt(gpsTotal);
        for (int i = 0; i < gpsTotal; i++) {
            out.writeInt(store.pairIdAt(i));
            out.writeDouble(store.kmExtAt(i));
            out.writeFloat((float) store.latitudeAt(i));
            out.writeFloat((float) store.longitudeAt(i));
            if (progress != null && ((i + 1) % 100_000 == 0 || i + 1 == gpsTotal)) {
                progress.onGpsWritten(i + 1, gpsTotal);
            }
        }
    }

    /** Zápis indexu bez mezilehlých kopií v paměti (pouze stream). */
    static void saveBody(File dbFile, String contentHash, File cacheDir, IndexBodyWriter bodyWriter) {
        if (dbFile == null || cacheDir == null || !dbFile.isFile() || bodyWriter == null) return;
        if (contentHash == null || contentHash.length() != HASH_HEX_LEN) return;
        if (!cacheDir.exists() && !cacheDir.mkdirs()) return;
        File cacheFile = cacheFileFor(contentHash, cacheDir);
        File tmp = new File(cacheDir, cacheFile.getName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(tmp)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeLong(dbFile.length());
            out.writeUTF(contentHash);
            bodyWriter.write(out);
            out.flush();
        } catch (Exception ignored) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(cacheFile)) {
            tmp.delete();
        }
    }

    private static LoadedIndex readIndex(long dbSize, String contentHash, File indexFile) {
        if (indexFile == null || !indexFile.isFile()) return null;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(indexFile)))) {
            if (in.readInt() != MAGIC) return null;
            int version = in.readInt();
            if (version != VERSION && version != 7) return null;
            long cachedSize = in.readLong();
            String cachedHash = in.readUTF();
            if (cachedSize != dbSize || !contentHash.equals(cachedHash)) {
                return null;
            }
            int roCount = in.readInt();
            Map<String, List<RoEntry>> ro = new HashMap<>(Math.max(roCount / 2, 16));
            for (int i = 0; i < roCount; i++) {
                String pairKey = in.readUTF();
                RoEntry entry = new RoEntry(in.readUTF(), in.readInt(), in.readDouble());
                ro.computeIfAbsent(pairKey, k -> new ArrayList<>()).add(entry);
            }
            GpsPointStore gpsStore = version == VERSION
                    ? readGpsStoreV8(in)
                    : readGpsStoreV7(in);
            return new LoadedIndex(ro, gpsStore);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static GpsPointStore readGpsStoreV8(DataInputStream in) throws IOException {
        int pairCount = in.readInt();
        String[] pairKeys = new String[pairCount];
        for (int i = 0; i < pairCount; i++) {
            pairKeys[i] = in.readUTF();
        }
        int gpsCount = in.readInt();
        GpsPointStore.Builder builder = GpsPointStore.builder(Math.max(gpsCount, 256));
        for (int i = 0; i < gpsCount; i++) {
            int pairId = in.readInt();
            double km = in.readDouble();
            float lat = in.readFloat();
            float lon = in.readFloat();
            if (pairId < 0 || pairId >= pairCount) continue;
            String[] ids = splitPairKey(pairKeys[pairId]);
            builder.addPoint(ids[0], ids[1], km, lat, lon);
        }
        return builder.build();
    }

    /** v7: každý bod nese celý pairKey UTF – načte se přímo do kompaktního úložiště. */
    private static GpsPointStore readGpsStoreV7(DataInputStream in) throws IOException {
        int gpsCount = in.readInt();
        GpsPointStore.Builder builder = GpsPointStore.builder(Math.max(gpsCount, 256));
        for (int i = 0; i < gpsCount; i++) {
            String pairKey = in.readUTF();
            double km = in.readDouble();
            double lat = in.readDouble();
            double lon = in.readDouble();
            String[] ids = splitPairKey(pairKey);
            builder.addPoint(ids[0], ids[1], km, lat, lon);
        }
        return builder.build();
    }

    private static String[] splitPairKey(String pairKey) {
        int sep = pairKey.indexOf('|');
        if (sep < 0) return new String[]{pairKey, ""};
        return new String[]{pairKey.substring(0, sep), pairKey.substring(sep + 1)};
    }

    private static File cacheFileFor(String contentHash, File cacheDir) {
        return new File(cacheDir, "dzs_" + contentHash + ".idx");
    }
}
