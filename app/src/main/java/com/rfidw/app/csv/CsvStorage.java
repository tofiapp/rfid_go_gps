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

import com.rfidw.app.storage.RfidPublicStorage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Výstupní CSV ve složce {@link RfidPublicStorage#relativeWorkDir()} –
 * společně s DZS databází, zapisovatelné z PC přes USB (MTP).
 *
 * Zápis vždy preferuje přímý soubor na disku (ne MediaStore ContentResolver).
 * MediaStore záznam blokuje přepis souboru z Průzkumníku Windows – proto se po
 * vytvoření souboru na disku z MediaStore maže a dál se používá jen cesta File.
 */
public final class CsvStorage {

    private static final String TAG = "CsvStorage";
    public static final String FILE_NAME = RfidPublicStorage.CSV_FILE_NAME;

    private CsvStorage() {}

    public static File resolveFile(Context context) {
        migrateLegacyIfNeeded(context);
        return RfidPublicStorage.csvFile();
    }

    public static String displayPath() {
        return RfidPublicStorage.relativeWorkDir() + "/" + FILE_NAME;
    }

    public static void importFromInputStream(Context context, InputStream in) throws IOException {
        File dest = resolveFile(context);
        releaseMediaStoreEntry(context);
        try (OutputStream out = openOutputStream(context, dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    /**
     * Uvolní MediaStore zámek nad CSV – volat při přechodu do pozadí,
     * aby šel soubor z PC přes USB (MTP) přepsat i při běžící aplikaci.
     */
    public static void releaseMediaStoreEntry(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        Uri uri = findInMediaStore(context);
        if (uri == null) return;
        try {
            context.getContentResolver().delete(uri, null, null);
            Log.d(TAG, "MediaStore záznam CSV uvolněn pro MTP");
        } catch (Exception e) {
            Log.w(TAG, "MediaStore záznam CSV nelze smazat", e);
        }
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
        if (canWriteDirect(file)) {
            releaseMediaStoreEntry(context);
            return new FileOutputStream(file, false);
        }
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
        if (file.isFile()) {
            long size = file.length();
            long mtime = file.lastModified();
            if (size >= 0 && mtime > 0) {
                return new FileMeta(size, mtime);
            }
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

    private static boolean canWriteDirect(File file) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        if (file.isFile()) {
            return file.canWrite();
        }
        try {
            return file.createNewFile() && file.canWrite();
        } catch (IOException e) {
            return false;
        }
    }

    private static void migrateLegacyIfNeeded(Context context) {
        File target = RfidPublicStorage.csvFile();
        if (target.isFile()) return;

        File[] sources = {
                new File(context.getExternalFilesDir(null), FILE_NAME),
                new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), FILE_NAME)
        };
        for (File source : sources) {
            if (!source.isFile()) continue;
            try {
                ensureParentExists(target);
                copyFile(source, target);
                releaseMediaStoreEntry(context);
                Log.i(TAG, "CSV migrováno do " + target.getAbsolutePath());
                return;
            } catch (IOException e) {
                Log.w(TAG, "Migrace CSV z " + source + " selhala", e);
            }
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
            Log.w(TAG, "MediaStore dotaz na CSV selhal", e);
        }
        return null;
    }

    private static FileMeta queryMediaStoreMeta(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        ContentResolver resolver = context.getContentResolver();
        String[] projection = {
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED
        };
        String selection = MediaStore.MediaColumns.RELATIVE_PATH + " = ? AND "
                + MediaStore.MediaColumns.DISPLAY_NAME + " = ?";
        try (Cursor c = resolver.query(filesCollection(), projection, selection,
                new String[]{RfidPublicStorage.mediaStoreRelativePath(), FILE_NAME},
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
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
        if (file.isFile() && file.canWrite()) {
            return null;
        }
        Uri existing = findInMediaStore(context);
        if (existing != null) return existing;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, RfidPublicStorage.mediaStoreRelativePath());
        Uri created = context.getContentResolver().insert(filesCollection(), values);
        if (created == null) {
            throw new IOException("MediaStore: nelze vytvořit " + FILE_NAME);
        }
        return created;
    }
}
