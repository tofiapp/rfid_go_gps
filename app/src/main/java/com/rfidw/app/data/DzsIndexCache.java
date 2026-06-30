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
 *
 * Verze 13 ukládá RO index včetně RO_ID pro přímé párování výhybek s GPS body.
 * Verze 12 ukládá RO index (výhybky včetně částí) a předpočítané GPS souřadnice
 * výhybek (správné párování KM_EXT ↔ střed OD/DO pro stejný pár ID).
 */
final class DzsIndexCache {

    private static final int MAGIC = 0x445A5349; // "DZSI"
    private static final int VERSION = 13;
    private static final int VERSION_LEGACY_V12 = 12;
    private static final int VERSION_LEGACY_V11 = 11;
    private static final int VERSION_LEGACY_V10 = 10;
    private static final int VERSION_LEGACY_V9 = 9;
    private static final int HASH_HEX_LEN = 64;
    /** {@link RoEntry#castMin} / {@link RoEntry#castMax} pokud sloupec v DB chybí. */
    static final int CAST_UNSPECIFIED = -1;

    static final class RoEntry {
        final String tudu;
        final int vyhybka;
        /** Identifikátor výhybky společný s GPS tabulkou; null pokud chybí. */
        final String roId;
        /** Střed OD a DO; {@link Double#NaN} pokud není k dispozici. */
        final double midKm;
        final int castMin;
        final int castMax;

        RoEntry(String tudu, int vyhybka, double midKm) {
            this(tudu, vyhybka, null, midKm, CAST_UNSPECIFIED, CAST_UNSPECIFIED);
        }

        RoEntry(String tudu, int vyhybka, double midKm, int castMin, int castMax) {
            this(tudu, vyhybka, null, midKm, castMin, castMax);
        }

        RoEntry(String tudu, int vyhybka, String roId, double midKm, int castMin, int castMax) {
            this.tudu = tudu;
            this.vyhybka = vyhybka;
            this.roId = roId;
            this.midKm = midKm;
            this.castMin = castMin;
            this.castMax = castMax;
        }
    }

    static final class LoadedIndex {
        final Map<String, List<RoEntry>> roByPairKey;
        /** Neprázdné jen u starší cache v9 – lze přednačíst do memoizace. */
        final VyhybkaGpsStore vyhybkaGpsStore;

        LoadedIndex(Map<String, List<RoEntry>> roByPairKey, VyhybkaGpsStore vyhybkaGpsStore) {
            this.roByPairKey = roByPairKey;
            this.vyhybkaGpsStore = vyhybkaGpsStore;
        }
    }

    private DzsIndexCache() {
    }

    /**
     * Vrátí SHA-256 databáze – použije uložený otisk, pokud sedí velikost souboru;
     * jinak přepočítá a uloží pro další start.
     */
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
        void onWritten(int written, int total);
    }

    static void save(File dbFile, String contentHash, File cacheDir,
                     Map<String, List<RoEntry>> roByPairKey,
                     VyhybkaGpsStore vyhybkaGpsStore,
                     SaveProgressListener progress) {
        saveBody(dbFile, contentHash, cacheDir, out -> {
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
                    out.writeUTF(ro.roId != null ? ro.roId : "");
                    out.writeDouble(ro.midKm);
                    out.writeInt(ro.castMin);
                    out.writeInt(ro.castMax);
                    written++;
                    if (progress != null && (written % 10_000 == 0 || written == roCount)) {
                        progress.onWritten(written, roCount);
                    }
                }
            }
            writeVyhybkaGpsStore(out, vyhybkaGpsStore);
        });
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
            if (version != VERSION && version != VERSION_LEGACY_V9
                    && version != VERSION_LEGACY_V10 && version != VERSION_LEGACY_V11
                    && version != VERSION_LEGACY_V12) {
                return null;
            }
            long cachedSize = in.readLong();
            String cachedHash = in.readUTF();
            if (cachedSize != dbSize || !contentHash.equals(cachedHash)) {
                return null;
            }
            int roCount = in.readInt();
            Map<String, List<RoEntry>> ro = new HashMap<>(Math.max(roCount / 2, 16));
            for (int i = 0; i < roCount; i++) {
                String pairKey = in.readUTF();
                String tudu = in.readUTF();
                int vyhybka = in.readInt();
                String roId = null;
                double midKm;
                int castMin = CAST_UNSPECIFIED;
                int castMax = CAST_UNSPECIFIED;
                if (version >= VERSION) {
                    String roIdRaw = in.readUTF();
                    roId = roIdRaw.isEmpty() ? null : roIdRaw;
                    midKm = in.readDouble();
                    castMin = in.readInt();
                    castMax = in.readInt();
                } else {
                    midKm = in.readDouble();
                    if (version >= VERSION_LEGACY_V11) {
                        castMin = in.readInt();
                        castMax = in.readInt();
                    }
                }
                RoEntry entry = new RoEntry(tudu, vyhybka, roId, midKm, castMin, castMax);
                ro.computeIfAbsent(pairKey, k -> new ArrayList<>()).add(entry);
            }
            VyhybkaGpsStore vyhybkaGpsStore = VyhybkaGpsStore.empty();
            if (version == VERSION_LEGACY_V9 || version >= VERSION_LEGACY_V11) {
                vyhybkaGpsStore = readVyhybkaGpsStore(in);
            }
            return new LoadedIndex(ro, vyhybkaGpsStore);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writeVyhybkaGpsStore(DataOutputStream out, VyhybkaGpsStore store) throws IOException {
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
        }
    }

    private static VyhybkaGpsStore readVyhybkaGpsStore(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count <= 0) return VyhybkaGpsStore.empty();
        VyhybkaGpsStore.Builder builder = VyhybkaGpsStore.builder();
        for (int i = 0; i < count; i++) {
            String pairKey = in.readUTF();
            String tudu = in.readUTF();
            int vyhybka = in.readInt();
            float lat = in.readFloat();
            float lon = in.readFloat();
            builder.add(pairKey, tudu, vyhybka, lat, lon);
        }
        return builder.build();
    }

    private static File cacheFileFor(String contentHash, File cacheDir) {
        return new File(cacheDir, "dzs_" + contentHash + ".idx");
    }

    private static File hashSidecarFile(File dbFile, File cacheDir) {
        String key = Long.toHexString(dbFile.length()) + "_"
                + Integer.toHexString(dbFile.getAbsolutePath().hashCode());
        return new File(cacheDir, "hash_" + key + ".txt");
    }

    private static String readStoredHash(File dbFile, File cacheDir) {
        if (cacheDir == null) return null;
        File sidecar = hashSidecarFile(dbFile, cacheDir);
        if (!sidecar.isFile()) return null;
        try (DataInputStream in = new DataInputStream(new FileInputStream(sidecar))) {
            long storedSize = in.readLong();
            String hash = in.readUTF();
            if (storedSize != dbFile.length() || hash.length() != HASH_HEX_LEN) {
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
