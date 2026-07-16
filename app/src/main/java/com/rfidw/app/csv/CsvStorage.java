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
 * Na Androidu 10+ nové soubory vznikají přes MediaStore (IS_PENDING → publikace pro MTP).
 * Přímý zápis na disk jen u již existujícího souboru (typicky nahraného z PC přes MTP).
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

    /** MediaStore URI výstupního CSV (Android 10+), nebo null. */
    public static Uri getMediaStoreUri(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        return findInMediaStore(context);
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
            return wrapPublishOnClose(context, file, new FileOutputStream(file, false), null);
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
                return wrapMediaStoreOutput(context, file, uri, out);
            }
        }
        if (canDirectWritePublicDownload()) {
            return wrapDirectOutput(context, file);
        }
        throw new IOException("CSV nelze uložit do Stažených souborů");
    }

    /**
     * Přímý zápis jen u existujícího souboru na disku (nahrání z PC přes MTP)
     * nebo při povolení „Přístup ke všem souborům“ (Android 11+).
     */
    private static boolean preferDirectFileIo(File file) {
        if (file == null || !file.isFile()) return false;
        return file.canWrite() || canDirectWritePublicDownload();
    }

    public static boolean canDirectWritePublicDownload() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        File parent = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        return parent != null && parent.isDirectory() && parent.canWrite();
    }

    private static OutputStream wrapDirectOutput(Context context, File file) throws IOException {
        return wrapPublishOnClose(context, file, new FileOutputStream(file, false), null);
    }

    private static OutputStream wrapMediaStoreOutput(
            Context context, File file, Uri uri, OutputStream out) {
        return wrapPublishOnClose(context, file, out, uri);
    }

    private static OutputStream wrapPublishOnClose(
            Context context, File file, OutputStream out, Uri mediaStoreUri) {
        return new FilterOutputStream(out) {
            private boolean closed;

            @Override
            public void close() throws IOException {
                if (closed) return;
                closed = true;
                IOException closeError = null;
                try {
                    super.close();
                } catch (IOException e) {
                    closeError = e;
                }
                try {
                    if (mediaStoreUri != null) {
                        markMediaStorePending(context, mediaStoreUri, false);
                    }
                    publishForMtp(context, file, mediaStoreUri);
                } catch (Exception e) {
                    Log.w(TAG, "Publikace CSV pro MTP selhala", e);
                }
                if (closeError != null) throw closeError;
            }
        };
    }

    /**
     * Zviditelní soubor pro MTP / Průzkumník Windows: zruší IS_PENDING,
     * spustí media scan a při povoleném přístupu zkopíruje obsah na veřejnou cestu Download/.
     */
    public static void publishForMtp(Context context, File file, Uri mediaStoreUri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        if (mediaStoreUri != null) {
            markMediaStorePending(context, mediaStoreUri, false);
        } else {
            Uri existing = findInMediaStore(context);
            if (existing != null) {
                markMediaStorePending(context, existing, false);
            }
        }
        if (file != null && file.isFile()) {
            scanIntoMediaStore(context, file);
            return;
        }
        if (canDirectWritePublicDownload()) {
            try {
                mirrorMediaStoreToPublicDownload(context, file);
            } catch (IOException e) {
                Log.w(TAG, "Zrcadlení CSV do Download/ selhalo", e);
            }
        }
    }

    private static void mirrorMediaStoreToPublicDownload(Context context, File target) throws IOException {
        if (target == null) return;
        Uri uri = findInMediaStore(context);
        if (uri == null) return;
        ensureParentExists(target);
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(target, false)) {
            if (in == null) throw new IOException("MediaStore CSV nelze otevřít");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        scanIntoMediaStore(context, target);
        Log.i(TAG, "CSV zrcadleno do " + target.getAbsolutePath());
    }

    private static void scanIntoMediaStore(Context context, File file) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || file == null || !file.isFile()) return;
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
                 OutputStream out = openOutputStream(context, target)) {
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
