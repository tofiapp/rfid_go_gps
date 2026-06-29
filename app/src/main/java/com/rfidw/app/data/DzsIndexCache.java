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
    private static final int VERSION = 6;
    private static final int HASH_HEX_LEN = 64;

    static final class RoEntry {
        final String tudu;
        final int vyhybka;

        RoEntry(String tudu, int vyhybka) {
            this.tudu = tudu;
            this.vyhybka = vyhybka;
        }
    }

    static final class GpsEntry {
        final String pairKey;
        final double latitude;
        final double longitude;

        GpsEntry(String pairKey, double latitude, double longitude) {
            this.pairKey = pairKey;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    /** Souřadnice výhybky v rámci TUDU (předpočítáno při indexaci). */
    static final class VyhybkaGpsEntry {
        final String tudu;
        final int vyhybka;
        final double latitude;
        final double longitude;

        VyhybkaGpsEntry(String tudu, int vyhybka, double latitude, double longitude) {
            this.tudu = tudu;
            this.vyhybka = vyhybka;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    static final class LoadedIndex {
        final Map<String, RoEntry> roByPairKey;
        final List<GpsEntry> gpsIndex;
        final List<VyhybkaGpsEntry> vyhybkaGpsIndex;

        LoadedIndex(Map<String, RoEntry> roByPairKey, List<GpsEntry> gpsIndex,
                    List<VyhybkaGpsEntry> vyhybkaGpsIndex) {
            this.roByPairKey = roByPairKey;
            this.gpsIndex = gpsIndex;
            this.vyhybkaGpsIndex = vyhybkaGpsIndex;
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

    static void save(File dbFile, String contentHash, File cacheDir, Map<String, RoEntry> roByPairKey,
                     List<GpsEntry> gpsIndex, List<VyhybkaGpsEntry> vyhybkaGpsIndex) {
        saveBody(dbFile, contentHash, cacheDir, out -> {
            out.writeInt(roByPairKey.size());
            for (Map.Entry<String, RoEntry> e : roByPairKey.entrySet()) {
                out.writeUTF(e.getKey());
                out.writeUTF(e.getValue().tudu);
                out.writeInt(e.getValue().vyhybka);
            }
            out.writeInt(gpsIndex.size());
            for (GpsEntry gps : gpsIndex) {
                out.writeUTF(gps.pairKey);
                out.writeDouble(gps.latitude);
                out.writeDouble(gps.longitude);
            }
            out.writeInt(vyhybkaGpsIndex.size());
            for (VyhybkaGpsEntry entry : vyhybkaGpsIndex) {
                out.writeUTF(entry.tudu);
                out.writeInt(entry.vyhybka);
                out.writeDouble(entry.latitude);
                out.writeDouble(entry.longitude);
            }
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
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return null;
            long cachedSize = in.readLong();
            String cachedHash = in.readUTF();
            if (cachedSize != dbSize || !contentHash.equals(cachedHash)) {
                return null;
            }
            int roCount = in.readInt();
            Map<String, RoEntry> ro = new HashMap<>(Math.max(roCount, 16));
            for (int i = 0; i < roCount; i++) {
                String pairKey = in.readUTF();
                ro.put(pairKey, new RoEntry(in.readUTF(), in.readInt()));
            }
            int gpsCount = in.readInt();
            List<GpsEntry> gps = new ArrayList<>(gpsCount);
            for (int i = 0; i < gpsCount; i++) {
                gps.add(new GpsEntry(in.readUTF(), in.readDouble(), in.readDouble()));
            }
            int vyhybkaGpsCount = in.readInt();
            List<VyhybkaGpsEntry> vyhybkaGps = new ArrayList<>(vyhybkaGpsCount);
            for (int i = 0; i < vyhybkaGpsCount; i++) {
                vyhybkaGps.add(new VyhybkaGpsEntry(
                        in.readUTF(), in.readInt(), in.readDouble(), in.readDouble()));
            }
            return new LoadedIndex(ro, gps, vyhybkaGps);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static File cacheFileFor(String contentHash, File cacheDir) {
        return new File(cacheDir, "dzs_" + contentHash + ".idx");
    }
}
