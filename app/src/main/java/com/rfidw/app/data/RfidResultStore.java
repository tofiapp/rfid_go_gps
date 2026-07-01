package com.rfidw.app.data;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Výstupní SQLite databáze se záznamy zápisu tagů (varianta 2A – samostatný soubor).
 *
 * Tabulka {@link #TABLE_NAME}: jeden řádek na tag, klíč {@code ID_RFID}
 * (stejný princip jako CSV – při stejném ID se řádek přepíše).
 */
public class RfidResultStore {

    public static final String TABLE_NAME = "RFID_ZAPISY";

    public static class Row {
        public String idRfid;
        public String epc;
        public String tid;
        public String rok;
        public String tudu;
        public String vyhybka;
        public String cast;
        public String poloha;
        public String latitude;
        public String longitude;
        public String accuracyM;
        public String gpsTime;
        public String userId;
        public String superZId;
        public String superDId;
        public String roId;
        public String scannedAt;
    }

    private static final String UPSERT_SQL =
            "INSERT INTO " + TABLE_NAME + " ("
                    + "ID_RFID, EPC, TID, ROK, TUDU, VYHYBKA, CIP, POLOHA, "
                    + "LATITUDE, LONGITUDE, ACCURACY_M, GPS_TIME, USER_ID, "
                    + "SUPER_Z_ID, SUPER_D_ID, RO_ID, SCANNED_AT"
                    + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                    + " ON CONFLICT(ID_RFID) DO UPDATE SET "
                    + "EPC=excluded.EPC, TID=excluded.TID, ROK=excluded.ROK, "
                    + "TUDU=excluded.TUDU, VYHYBKA=excluded.VYHYBKA, CIP=excluded.CIP, "
                    + "POLOHA=excluded.POLOHA, LATITUDE=excluded.LATITUDE, "
                    + "LONGITUDE=excluded.LONGITUDE, ACCURACY_M=excluded.ACCURACY_M, "
                    + "GPS_TIME=excluded.GPS_TIME, USER_ID=excluded.USER_ID, "
                    + "SUPER_Z_ID=excluded.SUPER_Z_ID, SUPER_D_ID=excluded.SUPER_D_ID, "
                    + "RO_ID=excluded.RO_ID, SCANNED_AT=excluded.SCANNED_AT";

    private final File file;
    private final SQLiteDatabase db;
    private final SQLiteStatement upsertStmt;

    private RfidResultStore(File file, SQLiteDatabase db) {
        this.file = file;
        this.db = db;
        ensureSchema(db);
        this.upsertStmt = db.compileStatement(UPSERT_SQL);
    }

    public static RfidResultStore open(File file) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null);
        return new RfidResultStore(file, db);
    }

    public File getFile() {
        return file;
    }

    public synchronized int size() {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_NAME, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized long getMaxIdRfid() {
        try (Cursor c = db.rawQuery(
                "SELECT MAX(CAST(ID_RFID AS INTEGER)) FROM " + TABLE_NAME, null)) {
            if (!c.moveToFirst() || c.isNull(0)) return 0;
            return c.getLong(0);
        } catch (Exception e) {
            return 0;
        }
    }

    public synchronized Row getLastRow() {
        try (Cursor c = db.rawQuery(
                "SELECT * FROM " + TABLE_NAME + " ORDER BY _id DESC LIMIT 1", null)) {
            return c.moveToFirst() ? readRow(c) : null;
        }
    }

    public synchronized void upsert(Row row) {
        if (row == null || row.idRfid == null || row.idRfid.isEmpty()) return;
        if (row.scannedAt == null || row.scannedAt.isEmpty()) {
            row.scannedAt = nowIso();
        }
        bindRow(upsertStmt, row);
        upsertStmt.executeInsert();
    }

    /** Odstraní poslední chronologicky uložený řádek. Vrátí smazaný řádek nebo null. */
    public synchronized Row removeLast() {
        Row last = getLastRow();
        if (last == null) return null;
        db.delete(TABLE_NAME, "ID_RFID = ?", new String[]{last.idRfid});
        return last;
    }

    public synchronized void deleteByIdRfid(String idRfid) {
        if (idRfid == null || idRfid.isEmpty()) return;
        db.delete(TABLE_NAME, "ID_RFID = ?", new String[]{idRfid});
    }

    /** Jednorázový import z existujícího CSV (bez DZS klíčů). */
    public synchronized int importFromCsvRows(List<com.rfidw.app.csv.CsvStore.Row> rows) {
        if (rows == null || rows.isEmpty()) return 0;
        int imported = 0;
        db.beginTransaction();
        try {
            for (com.rfidw.app.csv.CsvStore.Row csv : rows) {
                if (csv.idRfid == null || csv.idRfid.isEmpty()) continue;
                Row row = fromCsvRow(csv);
                bindRow(upsertStmt, row);
                upsertStmt.executeInsert();
                imported++;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return imported;
    }

    public synchronized void close() {
        try {
            upsertStmt.close();
        } catch (Exception ignored) {
        }
        db.close();
    }

    private static void ensureSchema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "ID_RFID TEXT NOT NULL UNIQUE, "
                + "EPC TEXT NOT NULL, "
                + "TID TEXT, "
                + "ROK TEXT, "
                + "TUDU TEXT, "
                + "VYHYBKA TEXT, "
                + "CIP TEXT, "
                + "POLOHA TEXT, "
                + "LATITUDE TEXT, "
                + "LONGITUDE TEXT, "
                + "ACCURACY_M TEXT, "
                + "GPS_TIME TEXT, "
                + "USER_ID TEXT, "
                + "SUPER_Z_ID TEXT, "
                + "SUPER_D_ID TEXT, "
                + "RO_ID TEXT, "
                + "SCANNED_AT TEXT NOT NULL"
                + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rfid_zapisy_tudu_vyh ON "
                + TABLE_NAME + "(TUDU, VYHYBKA)");
    }

    private static Row fromCsvRow(com.rfidw.app.csv.CsvStore.Row csv) {
        Row row = new Row();
        row.idRfid = nullToEmpty(csv.idRfid);
        row.epc = nullToEmpty(csv.epc);
        row.tid = nullToEmpty(csv.tid);
        row.rok = nullToEmpty(csv.rok);
        row.tudu = nullToEmpty(csv.tudu);
        row.vyhybka = nullToEmpty(csv.vyhybka);
        row.cast = nullToEmpty(csv.cast);
        row.poloha = nullToEmpty(csv.poloha);
        row.latitude = nullToEmpty(csv.latitude);
        row.longitude = nullToEmpty(csv.longitude);
        row.accuracyM = nullToEmpty(csv.accuracyM);
        row.gpsTime = nullToEmpty(csv.gpsTime);
        row.userId = nullToEmpty(csv.userId);
        row.superZId = "";
        row.superDId = "";
        row.roId = "";
        row.scannedAt = nowIso();
        return row;
    }

    private static void bindRow(SQLiteStatement stmt, Row row) {
        stmt.clearBindings();
        stmt.bindString(1, nullToEmpty(row.idRfid));
        stmt.bindString(2, nullToEmpty(row.epc));
        stmt.bindString(3, nullToEmpty(row.tid));
        stmt.bindString(4, nullToEmpty(row.rok));
        stmt.bindString(5, nullToEmpty(row.tudu));
        stmt.bindString(6, nullToEmpty(row.vyhybka));
        stmt.bindString(7, nullToEmpty(row.cast));
        stmt.bindString(8, nullToEmpty(row.poloha));
        stmt.bindString(9, nullToEmpty(row.latitude));
        stmt.bindString(10, nullToEmpty(row.longitude));
        stmt.bindString(11, nullToEmpty(row.accuracyM));
        stmt.bindString(12, nullToEmpty(row.gpsTime));
        stmt.bindString(13, nullToEmpty(row.userId));
        stmt.bindString(14, nullToEmpty(row.superZId));
        stmt.bindString(15, nullToEmpty(row.superDId));
        stmt.bindString(16, nullToEmpty(row.roId));
        stmt.bindString(17, nullToEmpty(row.scannedAt));
    }

    private static Row readRow(Cursor c) {
        Row row = new Row();
        row.idRfid = col(c, "ID_RFID");
        row.epc = col(c, "EPC");
        row.tid = col(c, "TID");
        row.rok = col(c, "ROK");
        row.tudu = col(c, "TUDU");
        row.vyhybka = col(c, "VYHYBKA");
        row.cast = col(c, "CIP");
        row.poloha = col(c, "POLOHA");
        row.latitude = col(c, "LATITUDE");
        row.longitude = col(c, "LONGITUDE");
        row.accuracyM = col(c, "ACCURACY_M");
        row.gpsTime = col(c, "GPS_TIME");
        row.userId = col(c, "USER_ID");
        row.superZId = col(c, "SUPER_Z_ID");
        row.superDId = col(c, "SUPER_D_ID");
        row.roId = col(c, "RO_ID");
        row.scannedAt = col(c, "SCANNED_AT");
        return row;
    }

    private static String col(Cursor c, String name) {
        int idx = c.getColumnIndex(name);
        if (idx < 0 || c.isNull(idx)) return "";
        String v = c.getString(idx);
        return v != null ? v : "";
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    private static String nowIso() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
        fmt.setTimeZone(TimeZone.getDefault());
        return fmt.format(new Date());
    }
}
