package com.rfidw.app.storage;

import android.os.Environment;

import java.io.File;

/**
 * Veřejná pracovní složka na čtečce – přístupná z PC přes USB (MTP).
 * CSV a DZS databáze leží spolu v {@code Download/RFID Go GPS/}.
 */
public final class RfidPublicStorage {

    public static final String WORK_DIR = "RFID Go GPS";
    public static final String CSV_FILE_NAME = "rfid_go_gps_output.csv";
    public static final String DB_FILE_NAME = "DZS_PASPORT_TPI.sqlite";

    private RfidPublicStorage() {}

    /** Relativní cesta pro UI a MediaStore, např. {@code Download/RFID Go GPS}. */
    public static String relativeWorkDir() {
        return Environment.DIRECTORY_DOWNLOADS + "/" + WORK_DIR;
    }

    /** Relativní cesta s koncovým lomítkem pro MediaStore RELATIVE_PATH. */
    public static String mediaStoreRelativePath() {
        return relativeWorkDir() + "/";
    }

    /** Starší umístění z verze 3.113 – pro jednorázovou migraci. */
    public static String legacyDocumentsRelativeWorkDir() {
        return Environment.DIRECTORY_DOCUMENTS + "/" + WORK_DIR;
    }

    public static File workDir() {
        File base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(base, WORK_DIR);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    public static File legacyDocumentsWorkDir() {
        File base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        return new File(base, WORK_DIR);
    }

    public static File csvFile() {
        return new File(workDir(), CSV_FILE_NAME);
    }

    public static File dbFile() {
        return new File(workDir(), DB_FILE_NAME);
    }
}
