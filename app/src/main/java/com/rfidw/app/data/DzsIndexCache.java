package com.rfidw.app.data;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Disková cache paměťových indexů DZS databáze.
 * Klíč platnosti: velikost a čas změny <b>zdrojového</b> SQLite souboru – při stejném
 * obsahu se přeskočí pomalé skenování tabulek. Umožňuje předpřipravit .idx na PC
 * (viz docs/INDEXACE_DZS.md a tools/preindex_dzs.py).
 */
final class DzsIndexCache {

    private static final int MAGIC = 0x445A5349; // "DZSI"
    private static final int VERSION = 4;

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

    static final class LoadedIndex {
        final Map<String, RoEntry> roByPairKey;
        final List<GpsEntry> gpsIndex;

        LoadedIndex(Map<String, RoEntry> roByPairKey, List<GpsEntry> gpsIndex) {
            this.roByPairKey = roByPairKey;
            this.gpsIndex = gpsIndex;
        }
    }

    private DzsIndexCache() {
    }

    static LoadedIndex tryLoad(File dbFile, File cacheDir) {
        if (dbFile == null || cacheDir == null || !dbFile.isFile()) return null;
        File cacheFile = cacheFileFor(dbFile, cacheDir);
        if (!cacheFile.isFile()) return null;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(cacheFile)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return null;
            long cachedSize = in.readLong();
            long cachedMtime = in.readLong();
            if (cachedSize != dbFile.length() || cachedMtime != dbFile.lastModified()) {
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
            return new LoadedIndex(ro, gps);
        } catch (Exception ignored) {
            cacheFile.delete();
            return null;
        }
    }

    @FunctionalInterface
    interface IndexBodyWriter {
        void write(DataOutputStream out) throws IOException;
    }

    static void save(File dbFile, File cacheDir, Map<String, RoEntry> roByPairKey,
                     List<GpsEntry> gpsIndex) {
        saveBody(dbFile, cacheDir, out -> {
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
        });
    }

    /** Zápis indexu bez mezilehlých kopií v paměti (pouze stream). */
    static void saveBody(File dbFile, File cacheDir, IndexBodyWriter bodyWriter) {
        if (dbFile == null || cacheDir == null || !dbFile.isFile() || bodyWriter == null) return;
        if (!cacheDir.exists() && !cacheDir.mkdirs()) return;
        File cacheFile = cacheFileFor(dbFile, cacheDir);
        File tmp = new File(cacheDir, cacheFile.getName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(tmp)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeLong(dbFile.length());
            out.writeLong(dbFile.lastModified());
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

    private static File cacheFileFor(File dbFile, File cacheDir) {
        String name = "dzs_" + Long.toHexString(dbFile.length())
                + "_" + Long.toHexString(dbFile.lastModified()) + ".idx";
        return new File(cacheDir, name);
    }
}
