package com.rfidw.app.export;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Kopie souborů do veřejné složky Stažené soubory / RFID Go GPS –
 * viditelné v aplikaci Soubory a přes USB z PC.
 */
public final class PublicFileExport {

    public static final String DOWNLOAD_SUBDIR = "RFID Go GPS";

    private PublicFileExport() {
    }

    public static String copyToDownloads(Context context, File source, String fileName,
                                         String mimeType) throws IOException {
        if (source == null || !source.isFile() || !source.canRead()) {
            throw new IOException("Zdrojový soubor nelze přečíst: "
                    + (source != null ? source.getAbsolutePath() : "null"));
        }
        if (fileName == null || fileName.trim().isEmpty()) {
            fileName = source.getName();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return copyToDownloadsMediaStore(context, source, fileName, mimeType);
        }
        return copyToDownloadsLegacy(source, fileName);
    }

    public static String downloadsDisplayPath(String fileName) {
        return Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_SUBDIR + "/" + fileName;
    }

    private static String copyToDownloadsMediaStore(Context context, File source, String fileName,
                                                    String mimeType) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_SUBDIR + "/";
        Uri existing = findDownloadUri(resolver, fileName, relativePath);
        Uri target;
        if (existing != null) {
            target = existing;
        } else {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType != null ? mimeType : "application/octet-stream");
            values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);
            target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (target == null) {
                throw new IOException("Nelze vytvořit soubor ve Stažených");
            }
        }
        try (InputStream in = new FileInputStream(source);
             OutputStream out = resolver.openOutputStream(target, "wt")) {
            if (out == null) {
                throw new IOException("Nelze zapsat do Stažených");
            }
            copyStream(in, out);
        }
        return downloadsDisplayPath(fileName);
    }

    private static Uri findDownloadUri(ContentResolver resolver, String fileName, String relativePath) {
        String selection = MediaStore.Downloads.DISPLAY_NAME + "=? AND "
                + MediaStore.Downloads.RELATIVE_PATH + "=?";
        String[] args = {fileName, relativePath};
        try (Cursor c = resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Downloads._ID},
                selection, args, null)) {
            if (c != null && c.moveToFirst()) {
                return ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0));
            }
        }
        return null;
    }

    private static String copyToDownloadsLegacy(File source, String fileName) throws IOException {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_SUBDIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Nelze vytvořit složku: " + dir.getAbsolutePath());
        }
        File dest = new File(dir, fileName);
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new java.io.FileOutputStream(dest, false)) {
            copyStream(in, out);
        }
        return dest.getAbsolutePath();
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }
}
