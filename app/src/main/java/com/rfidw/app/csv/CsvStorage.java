package com.rfidw.app.csv;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Výstupní CSV ve složce Stažené soubory (Download).
 * Z PC přes USB (MTP) lze soubor {@link #FILE_NAME} normálně nahrát a přepsat.
 *
 * Na Androidu 10+ se při zápisu preferuje přímý soubor na disku (viditelný přes MTP),
 * MediaStore slouží jako záloha a pro čtení, když scoped storage skryje cestu.
 */
public final class CsvStorage {

    private static final String TAG = "CsvStorage";
    public static final String FILE_NAME = "rfid_go_gps_output.csv";

    private CsvStorage() {}

    public static File resolveFile(Context context) {
        migrateLegacyIfNeeded(context);
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (dir != null && !dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return new File(dir, FILE_NAME);
    }

    public static String displayPath() {
        return Environment.DIRECTORY_DOWNLOADS + "/" + FILE_NAME;
    }

    /** Soubor existuje na disku nebo v MediaStore (Stažené soubory). */
    public static boolean isPresent(Context context, File file) {
        if (file != null && file.isFile()) return true;
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && findInMediaStore(context) != null;
    }

    public static void copyTo(Context context, InputStream in) throws IOException {
        File dest = resolveFile(context);
        try (OutputStream out = openOutputStream(context, dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    static InputStream openInputStream(Context context, File file) throws IOException {
        if (file != null && file.isFile() && file.canRead()) {
            return new FileInputStream(file);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri uri = findInMediaStore(context);
            if (uri != null) {
                InputStream in = context.getContentResolver().openInputStream(uri);
                if (in != null) return in;
            }
        }
        if (file != null && file.isFile()) {
            return new FileInputStream(file);
        }
        throw new IOException("CSV nenalezeno: " + (file != null ? file.getAbsolutePath() : FILE_NAME));
    }

    static OutputStream openOutputStream(Context context, File file) throws IOException {
        ensureParentExists(file);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return new FileOutputStream(file, false);
        }
        if (preferDirectFileIo(file)) {
            try {
                return wrapDirectOutput(context, file);
            } catch (IOException e) {
                Log.w(TAG, "Přímý zápis CSV selhal, použiji MediaStore", e);
            }
        }
        Uri uri = findOrCreateMediaStoreUri(context, file);
        if (uri != null) {
            markMediaStorePending(context, uri, true);
            OutputStream out = context.getContentResolver().openOutputStream(uri, "wt");
            if (out != null) {
                return wrapMediaStoreOutput(context, uri, out);
            }
        }
        return wrapDirectOutput(context, file);
    }

    private static boolean preferDirectFileIo(File file) {
        if (file == null) return false;
        if (file.isFile() && file.canWrite()) return true;
        File parent = file.getParentFile();
        return parent != null && parent.isDirectory() && parent.canWrite();
    }

    private static OutputStream wrapDirectOutput(Context context, File file) throws IOException {
        return new FilterOutputStream(new FileOutputStream(file, false)) {
            private boolean closed;

            @Override
            public void close() throws IOException {
                if (closed) return;
                closed = true;
                super.close();
                scanIntoMediaStore(context, file);
            }
        };
    }

    private static OutputStream wrapMediaStoreOutput(Context context, Uri uri, OutputStream out) {
        return new FilterOutputStream(out) {
            private boolean closed;

            @Override
            public void close() throws IOException {
                if (closed) return;
                closed = true;
                super.close();
                markMediaStorePending(context, uri, false);
            }
        };
    }

    private static void scanIntoMediaStore(Context context, File file) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        MediaScannerConnection.scanFile(
                context,
                new String[]{file.getAbsolutePath()},
                new String[]{"text/csv"},
                null);
    }

    private static void markMediaStorePending(Context context, Uri uri, boolean pending) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || uri == null) return;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.IS_PENDING, pending ? 1 : 0);
            context.getContentResolver().update(uri, values, null, null);
        } catch (Exception e) {
            Log.w(TAG, "MediaStore IS_PENDING update selhala", e);
        }
    }

    private static void migrateLegacyIfNeeded(Context context) {
        File target = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                FILE_NAME);
        if (target.isFile()) return;

        File legacy = new File(context.getExternalFilesDir(null), FILE_NAME);
        if (!legacy.isFile()) return;
        try {
            ensureParentExists(target);
            try (InputStream in = new FileInputStream(legacy);
                 OutputStream out = new FileOutputStream(target, false)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            scanIntoMediaStore(context, target);
            Log.i(TAG, "CSV migrováno do Download: " + target.getAbsolutePath());
        } catch (IOException e) {
            Log.w(TAG, "Migrace CSV do Download selhala", e);
        }
    }

    private static void ensureParentExists(File file) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
    }

    private static Uri findInMediaStore(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.Downloads._ID};
        String selection = MediaStore.Downloads.DISPLAY_NAME + " = ?";
        try (Cursor c = context.getContentResolver().query(
                collection, projection, selection, new String[]{FILE_NAME},
                MediaStore.Downloads.DATE_MODIFIED + " DESC")) {
            if (c != null && c.moveToFirst()) {
                return ContentUris.withAppendedId(collection, c.getLong(0));
            }
        } catch (Exception e) {
            Log.w(TAG, "MediaStore dotaz na CSV selhal", e);
        }
        return null;
    }

    /**
     * MediaStore URI jen když na disku soubor ještě není – jinak přímý zápis (MTP z PC).
     */
    private static Uri findOrCreateMediaStoreUri(Context context, File file) throws IOException {
        if (file != null && file.isFile() && file.canRead()) {
            return null;
        }
        Uri existing = findInMediaStore(context);
        if (existing != null) return existing;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME);
        values.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri created = context.getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (created == null) {
            throw new IOException("MediaStore: nelze vytvořit " + FILE_NAME);
        }
        return created;
    }
}
