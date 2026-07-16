package com.rfidw.app.storage;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * DZS databáze ve složce {@link RfidPublicStorage#relativeWorkDir()} –
 * přežije přeinstalaci aplikace a je viditelná ve Stažených / z PC přes USB.
 */
public final class DbStorage {

    private static final String TAG = "DbStorage";
    public static final String FILE_NAME = RfidPublicStorage.DB_FILE_NAME;

    private DbStorage() {}

    public static File resolveFile(Context context) {
        migrateLegacyIfNeeded(context);
        return RfidPublicStorage.dbFile();
    }

    public static String displayPath() {
        return RfidPublicStorage.relativeWorkDir() + "/" + FILE_NAME;
    }

    /**
     * Zkopíruje databázi do veřejné složky Stažených, pokud tam ještě není
     * nebo je zdroj novější / větší.
     *
     * @return cesta ke kanonické kopii ve Stažených, nebo {@code null} při selhání
     */
    public static File ensureCanonicalCopy(Context context, File source) {
        if (source == null || !source.isFile() || !source.canRead()) return null;
        File target = resolveFile(context);
        if (target.isFile() && target.getAbsolutePath().equals(source.getAbsolutePath())) {
            return target;
        }
        if (target.isFile() && target.length() == source.length()
                && target.lastModified() >= source.lastModified()) {
            return target;
        }
        try {
            writeFile(context, target, source);
            Log.i(TAG, "DZS databáze uložena do " + target.getAbsolutePath());
            return target;
        } catch (IOException e) {
            Log.w(TAG, "Nelze uložit DZS databázi do Stažených", e);
            return null;
        }
    }

    private static void migrateLegacyIfNeeded(Context context) {
        File target = RfidPublicStorage.dbFile();
        if (target.isFile()) return;

        File[] sources = {
                new File(RfidPublicStorage.legacyDocumentsWorkDir(), FILE_NAME),
                new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), FILE_NAME)
        };
        for (File source : sources) {
            if (!source.isFile()) continue;
            try {
                writeFile(context, target, source);
                Log.i(TAG, "DZS databáze migrována do " + target.getAbsolutePath());
                return;
            } catch (IOException e) {
                Log.w(TAG, "Migrace DZS z " + source + " selhala", e);
            }
        }
    }

    private static void writeFile(Context context, File target, File source) throws IOException {
        ensureParentExists(target);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            copyFile(source, target);
            return;
        }
        Uri uri = findOrCreateMediaStoreUri(context, target);
        if (uri != null) {
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("MediaStore: nelze otevřít výstup");
                copyStream(in, out);
                return;
            }
        }
        copyFile(source, target);
    }

    private static void copyFile(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to, false)) {
            copyStream(in, out);
        }
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[262_144];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    private static void ensureParentExists(File file) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
    }

    private static Uri filesCollection() {
        return MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
    }

    private static Uri findInMediaStore(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        ContentResolver resolver = context.getContentResolver();
        String[] projection = {MediaStore.MediaColumns._ID};
        String selection = MediaStore.MediaColumns.RELATIVE_PATH + " = ? AND "
                + MediaStore.MediaColumns.DISPLAY_NAME + " = ?";
        String[] args = {RfidPublicStorage.mediaStoreRelativePath(), FILE_NAME};
        try (Cursor c = resolver.query(filesCollection(), projection, selection, args,
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (c != null && c.moveToFirst()) {
                return ContentUris.withAppendedId(filesCollection(), c.getLong(0));
            }
        } catch (Exception e) {
            Log.w(TAG, "MediaStore dotaz na DZS selhal", e);
        }
        return null;
    }

    private static Uri findOrCreateMediaStoreUri(Context context, File file) throws IOException {
        Uri existing = findInMediaStore(context);
        if (existing != null) return existing;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/x-sqlite3");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, RfidPublicStorage.mediaStoreRelativePath());
        Uri created = context.getContentResolver().insert(filesCollection(), values);
        if (created == null) {
            throw new IOException("MediaStore: nelze vytvořit " + FILE_NAME);
        }
        return created;
    }
}
