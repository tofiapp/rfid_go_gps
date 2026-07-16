package com.rfidw.app.csv;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Umístění výstupního CSV – veřejná složka Stažené soubory (Download),
 * aby šel soubor nahrát / přepsat z PC přes USB (MTP).
 *
 * Stará cesta {@code Android/data/.../files/} je z MTP zapisovatelná jen výjimečně.
 */
public final class CsvStorage {

    private static final String TAG = "CsvStorage";
    public static final String FILE_NAME = "rfid_go_gps_output.csv";

    private CsvStorage() {}

    /** Cílový soubor ve složce Download (přístupné z Průzkumníka při USB). */
    public static File resolveFile(Context context) {
        migrateLegacyIfNeeded(context);
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (dir != null && !dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return new File(dir, FILE_NAME);
    }

    /** Krátká cesta pro UI – kam kopírovat z PC. */
    public static String displayPath() {
        return Environment.DIRECTORY_DOWNLOADS + "/" + FILE_NAME;
    }

    static InputStream openInputStream(Context context, File file) throws IOException {
        if (file.isFile() && file.canRead()) {
            return new FileInputStream(file);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri uri = findInMediaStore(context);
            if (uri != null) {
                InputStream in = context.getContentResolver().openInputStream(uri);
                if (in != null) return in;
            }
        }
        if (file.isFile()) {
            return new FileInputStream(file);
        }
        throw new IOException("CSV nenalezeno: " + file.getAbsolutePath());
    }

    static OutputStream openOutputStream(Context context, File file) throws IOException {
        ensureParentExists(file);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return new FileOutputStream(file, false);
        }
        Uri uri = findOrCreateMediaStoreUri(context, file);
        if (uri != null) {
            OutputStream out = context.getContentResolver().openOutputStream(uri, "wt");
            if (out != null) return out;
        }
        return new FileOutputStream(file, false);
    }

    static FileMeta queryFileMeta(Context context, File file) {
        long size = file.isFile() ? file.length() : -1;
        long mtime = file.isFile() ? file.lastModified() : 0;
        if (size >= 0 && mtime > 0) {
            return new FileMeta(size, mtime);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            FileMeta fromStore = queryMediaStoreMeta(context);
            if (fromStore != null) return fromStore;
        }
        if (file.isFile()) {
            return new FileMeta(file.length(), file.lastModified());
        }
        return null;
    }

    static final class FileMeta {
        final long size;
        final long mtime;

        FileMeta(long size, long mtime) {
            this.size = size;
            this.mtime = mtime;
        }
    }

    private static void migrateLegacyIfNeeded(Context context) {
        File legacy = new File(context.getExternalFilesDir(null), FILE_NAME);
        if (!legacy.isFile()) return;
        File target = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                FILE_NAME);
        if (target.isFile() && target.length() >= legacy.length()) {
            //noinspection ResultOfMethodCallIgnored
            legacy.delete();
            return;
        }
        try {
            ensureParentExists(target);
            copyFile(legacy, target);
            Log.i(TAG, "CSV migrováno do Download: " + target.getAbsolutePath());
        } catch (IOException e) {
            Log.w(TAG, "Migrace CSV do Download selhala", e);
        }
    }

    private static void copyFile(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to, false)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
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
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.Downloads._ID};
        String selection = MediaStore.Downloads.DISPLAY_NAME + " = ?";
        String[] args = {FILE_NAME};
        try (Cursor c = resolver.query(collection, projection, selection, args,
                MediaStore.Downloads.DATE_MODIFIED + " DESC")) {
            if (c != null && c.moveToFirst()) {
                long id = c.getLong(0);
                return ContentUris.withAppendedId(collection, id);
            }
        } catch (Exception e) {
            Log.w(TAG, "MediaStore dotaz na CSV selhal", e);
        }
        return null;
    }

    private static FileMeta queryMediaStoreMeta(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_MODIFIED
        };
        String selection = MediaStore.Downloads.DISPLAY_NAME + " = ?";
        try (Cursor c = resolver.query(collection, projection, selection,
                new String[]{FILE_NAME}, MediaStore.Downloads.DATE_MODIFIED + " DESC")) {
            if (c != null && c.moveToFirst()) {
                long size = c.isNull(0) ? -1 : c.getLong(0);
                long modified = c.isNull(1) ? 0 : c.getLong(1) * 1000L;
                return new FileMeta(size, modified);
            }
        } catch (Exception e) {
            Log.w(TAG, "MediaStore meta CSV selhala", e);
        }
        return null;
    }

    private static Uri findOrCreateMediaStoreUri(Context context, File file) throws IOException {
        Uri existing = findInMediaStore(context);
        if (existing != null) return existing;
        if (file.isFile() && file.canRead()) {
            return null;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME);
        values.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/");
        Uri created = context.getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (created == null) {
            throw new IOException("MediaStore: nelze vytvořit " + FILE_NAME);
        }
        return created;
    }
}
