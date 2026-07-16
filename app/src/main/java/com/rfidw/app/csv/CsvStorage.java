package com.rfidw.app.csv;

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
 * Výstupní CSV ve složce Stažené soubory (Download).
 * Z PC přes USB (MTP) lze soubor {@link #FILE_NAME} normálně nahrát a přepsat.
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
        if (file.isFile()) {
            return new FileInputStream(file);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri uri = findInMediaStore(context);
            if (uri != null) {
                InputStream in = context.getContentResolver().openInputStream(uri);
                if (in != null) return in;
            }
        }
        throw new IOException("CSV nenalezeno: " + file.getAbsolutePath());
    }

    static OutputStream openOutputStream(Context context, File file) throws IOException {
        ensureParentExists(file);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return new FileOutputStream(file, false);
        }
        Uri uri = findOrCreateMediaStoreUri(context);
        if (uri != null) {
            OutputStream out = context.getContentResolver().openOutputStream(uri, "wt");
            if (out != null) return out;
        }
        return new FileOutputStream(file, false);
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

    private static Uri findOrCreateMediaStoreUri(Context context) {
        Uri existing = findInMediaStore(context);
        if (existing != null) return existing;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME);
        values.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/");
        return context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
    }
}
