package com.rfidw.app.csv;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import com.rfidw.app.storage.RfidPublicStorage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Aplikace pracuje s interním CSV (nikdy ho neblokuje MTP).
 * Soubor v Documents/RFID Go GPS/ je jen kopie pro PC – zapisuje se jen při
 * minimalizaci aplikace, takže ho jde z Windows přepsat kdykoliv.
 */
public final class CsvStorage {

    private static final String TAG = "CsvStorage";
    public static final String FILE_NAME = RfidPublicStorage.CSV_FILE_NAME;

    private static long lastPublicSize = -1;
    private static long lastPublicMtime;

    private CsvStorage() {}

    /** Interní pracovní soubor – sem zapisuje aplikace. */
    public static File resolveWorkingFile(Context context) {
        File working = workingFile(context);
        if (!working.isFile()) {
            migrateToWorking(context, working);
        }
        return working;
    }

    /** Cesta pro USB / Průzkumník Windows. */
    public static File publicFile() {
        return RfidPublicStorage.csvFile();
    }

    public static String displayPath() {
        return RfidPublicStorage.relativeWorkDir() + "/" + FILE_NAME;
    }

    public static void importFromInputStream(Context context, InputStream in) throws IOException {
        File working = resolveWorkingFile(context);
        try (OutputStream out = new FileOutputStream(working, false)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        mirrorWorkingToPublic(context);
    }

    /** Načte CSV nahrané z PC, pokud se veřejná kopie změnila. */
    public static boolean pullFromPublicIfChanged(Context context) throws IOException {
        File pub = publicFile();
        if (!pub.isFile()) return false;
        long size = pub.length();
        long mtime = pub.lastModified();
        if (size == lastPublicSize && mtime == lastPublicMtime) {
            return false;
        }
        File working = workingFile(context);
        copyFile(pub, working);
        lastPublicSize = size;
        lastPublicMtime = mtime;
        Log.i(TAG, "CSV načteno z USB: " + pub.getAbsolutePath());
        return true;
    }

    /** Zkopíruje interní CSV do Documents/RFID Go GPS/ (volat při onPause). */
    public static void mirrorWorkingToPublic(Context context) throws IOException {
        File working = workingFile(context);
        if (!working.isFile()) return;

        File pub = publicFile();
        deleteLegacyMediaStoreEntry(context);
        ensureDir(pub.getParentFile());

        File tmp = new File(pub.getParentFile(), pub.getName() + ".tmp");
        copyFile(working, tmp);
        if (pub.exists() && !pub.delete()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Nelze přepsat " + FILE_NAME);
        }
        if (!tmp.renameTo(pub)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Nelze uložit " + FILE_NAME);
        }
        lastPublicSize = pub.length();
        lastPublicMtime = pub.lastModified();
    }

    /** Smaže starý MediaStore záznam z doby, kdy aplikace zapisovala přímo do Documents. */
    public static void deleteLegacyMediaStoreEntry(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        Uri uri = findInMediaStore(context);
        if (uri == null) return;
        try {
            context.getContentResolver().delete(uri, null, null);
        } catch (Exception ignored) {
        }
    }

    private static File workingFile(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = context.getFilesDir();
        return new File(dir, FILE_NAME);
    }

    private static void migrateToWorking(Context context, File working) {
        File[] sources = {
                publicFile(),
                new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), FILE_NAME)
        };
        for (File source : sources) {
            if (!source.isFile()) continue;
            try {
                copyFile(source, working);
                lastPublicSize = source.length();
                lastPublicMtime = source.lastModified();
                deleteLegacyMediaStoreEntry(context);
                Log.i(TAG, "CSV migrováno do interní kopie");
                return;
            } catch (IOException e) {
                Log.w(TAG, "Migrace CSV selhala: " + source, e);
            }
        }
    }

    private static void copyFile(File from, File to) throws IOException {
        ensureDir(to.getParentFile());
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to, false)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static void ensureDir(File dir) {
        if (dir != null && !dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
    }

    private static Uri findInMediaStore(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        String selection = MediaStore.MediaColumns.RELATIVE_PATH + " = ? AND "
                + MediaStore.MediaColumns.DISPLAY_NAME + " = ?";
        try (Cursor c = resolver.query(collection,
                new String[]{MediaStore.MediaColumns._ID}, selection,
                new String[]{RfidPublicStorage.mediaStoreRelativePath(), FILE_NAME},
                null)) {
            if (c != null && c.moveToFirst()) {
                return ContentUris.withAppendedId(collection, c.getLong(0));
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
