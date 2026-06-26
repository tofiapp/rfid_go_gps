package com.rfidw.app.rfid;

import android.content.Context;

import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.deviceapi.entity.UHFTAGInfo;

/**
 * Obal nad Chainway / RSCJA UHF čtečkou (vestavěný UART modul – platí pro C5).
 *
 * Banky paměti UHF tagu (Gen2):
 *   0 = RESERVED (kill/access pwd), 1 = EPC, 2 = TID, 3 = USER
 */
public class UhfManager {

    public static final int BANK_RESERVED = 0;
    public static final int BANK_EPC      = 1;
    public static final int BANK_TID      = 2;
    public static final int BANK_USER     = 3;

    // EPC: ptr=2 (přeskočí CRC+PC), Len=6 wordů = 96 bitů = 24 hex znaků
    public static final int EPC_PTR = 2;
    public static final int EPC_LEN = 6;

    // RESERVED: ptr=2 = access password (2 wordy = 8 hex znaků)
    public static final int ACCESS_PWD_PTR = 2;
    public static final int ACCESS_PWD_LEN = 2;

    public static final String DEFAULT_LOCK_CODE = "008020";
    public static final String DEFAULT_ACCESS_PASSWORD = "00000000";

    /** @deprecated použijte {@link #PRESET_ACCESS_PASSWORDS} */
    @Deprecated
    public static final String PRESET_ACCESS_PASSWORD = "11223344";

    /**
     * Preset access hesla zkoušená při selhání zápisu s uživatelským heslem.
     * Třetí položka je zatím prázdná – doplní se později.
     */
    public static final String[] PRESET_ACCESS_PASSWORDS = {
            "11223344",
            "11112222",
            null,
    };

    private RFIDWithUHFUART reader;
    private boolean ready = false;

    public boolean isReady() { return ready; }

    /** Inicializace čtečky. Volat z UI vlákna při startu. */
    public synchronized boolean init(Context ctx) {
        try {
            if (reader == null) {
                reader = RFIDWithUHFUART.getInstance();
            }
            ready = reader.init(ctx.getApplicationContext());
            return ready;
        } catch (Exception e) {
            ready = false;
            return false;
        }
    }

    public synchronized void free() {
        try {
            if (reader != null) reader.free();
        } catch (Exception ignored) {
        } finally {
            ready = false;
        }
    }

    public int getPower() {
        try { return reader != null ? reader.getPower() : -1; }
        catch (Exception e) { return -1; }
    }

    public boolean setPower(int dbm) {
        try { return reader != null && reader.setPower(dbm); }
        catch (Exception e) { return false; }
    }

    /** Výsledek operace zápisu. */
    public static class WriteResult {
        public boolean success;
        public String oldEpc;     // EPC před přepisem (pokud byl tag přečten)
        public String tid;        // TID přečteného tagu
        public String message;
        public boolean usedPresetPassword;
        /** Preset heslo, které zápis uspělo; null pokud stačilo uživatelské heslo. */
        public String presetPasswordUsed;
    }

    /**
     * Přečte tag v dosahu (EPC + TID) a přepíše jeho EPC na nový.
     *
     * @param accessPwd 8 hex znaků (default "00000000")
     * @param newEpc    nový EPC, 24 hex znaků
     */
    public synchronized WriteResult writeEpc(String accessPwd, String newEpc) {
        WriteResult r = new WriteResult();
        if (!ready || reader == null) {
            r.message = "Čtečka není připravena.";
            return r;
        }
        accessPwd = normalizePwd(accessPwd);
        if (newEpc == null || newEpc.length() != 24) {
            r.message = "EPC musí mít 24 znaků.";
            return r;
        }

        try {
            UHFTAGInfo info = reader.inventorySingleTag();
            if (info != null) {
                r.oldEpc = info.getEPC();
                r.tid = info.getTid();
            }
            if (r.tid == null || r.tid.isEmpty()) {
                r.tid = readTidWithPresetFallback(accessPwd);
            }

            PwdWriteAttempt attempt = writeDataWithPresetFallback(
                    accessPwd, BANK_EPC, EPC_PTR, EPC_LEN, newEpc);
            r.success = attempt.success;
            r.usedPresetPassword = attempt.usedPresetPassword;
            r.presetPasswordUsed = attempt.presetPasswordUsed;
            r.message = attempt.success
                    ? (attempt.usedPresetPassword ? "EPC zapsáno (preset heslo)." : "EPC zapsáno.")
                    : "Zápis EPC se nezdařil (tag mimo dosah / špatné heslo).";
            return r;
        } catch (Exception e) {
            r.success = false;
            r.message = "Chyba zápisu: " + e.getMessage();
            return r;
        }
    }

    /**
     * Zapíše nové access heslo do RESERVED banky (ptr 2, len 2).
     *
     * @param accessPwd aktuální access heslo (8 hex)
     * @param newPwd    nové access heslo (8 hex)
     */
    public synchronized WriteResult writeAccessPassword(String accessPwd, String newPwd) {
        WriteResult r = new WriteResult();
        if (!ready || reader == null) {
            r.message = "Čtečka není připravena.";
            return r;
        }
        accessPwd = normalizePwd(accessPwd);
        newPwd = normalizePwd(newPwd);
        if (!isValidHexPwd(newPwd)) {
            r.message = "Nové heslo musí mít 8 hex znaků.";
            return r;
        }

        try {
            UHFTAGInfo info = reader.inventorySingleTag();
            if (info != null) {
                r.oldEpc = info.getEPC();
                r.tid = info.getTid();
            }
            if (info == null) {
                r.message = "Tag nenalezen v dosahu.";
                return r;
            }

            PwdWriteAttempt attempt = writeDataWithPresetFallback(
                    accessPwd, BANK_RESERVED, ACCESS_PWD_PTR, ACCESS_PWD_LEN, newPwd);
            r.success = attempt.success;
            r.usedPresetPassword = attempt.usedPresetPassword;
            r.presetPasswordUsed = attempt.presetPasswordUsed;
            r.message = attempt.success
                    ? (attempt.usedPresetPassword ? "Access heslo zapsáno (preset heslo)." : "Access heslo zapsáno.")
                    : "Zápis hesla se nezdařil (špatné heslo / tag zamčen).";
            return r;
        } catch (Exception e) {
            r.success = false;
            r.message = "Chyba zápisu hesla: " + e.getMessage();
            return r;
        }
    }

    /**
     * Zamkne paměť tagu podle lock kódu (např. 008020).
     *
     * @param accessPwd aktuální access heslo (8 hex)
     * @param lockCode  lock payload (6 hex znaků)
     */
    public synchronized WriteResult lockTag(String accessPwd, String lockCode) {
        WriteResult r = new WriteResult();
        if (!ready || reader == null) {
            r.message = "Čtečka není připravena.";
            return r;
        }
        accessPwd = normalizePwd(accessPwd);
        if (lockCode == null || lockCode.isEmpty()) lockCode = DEFAULT_LOCK_CODE;

        try {
            UHFTAGInfo info = reader.inventorySingleTag();
            if (info != null) {
                r.oldEpc = info.getEPC();
                r.tid = info.getTid();
            }
            if (info == null) {
                r.message = "Tag nenalezen v dosahu.";
                return r;
            }

            boolean ok = reader.lockMem(accessPwd, lockCode);
            r.success = ok;
            r.message = ok ? "Tag zamčen." : "Zamčení se nezdařilo (špatné heslo / tag již zamčen).";
            return r;
        } catch (Exception e) {
            r.success = false;
            r.message = "Chyba zamčení: " + e.getMessage();
            return r;
        }
    }

    /** Jednorázové přečtení tagu (EPC + TID), např. pro kontrolu. */
    public synchronized UHFTAGInfo readSingle() {
        try {
            return reader != null ? reader.inventorySingleTag() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static class PwdWriteAttempt {
        boolean success;
        boolean usedPresetPassword;
        String presetPasswordUsed;
    }

    private PwdWriteAttempt writeDataWithPresetFallback(
            String accessPwd, int bank, int ptr, int len, String data) {
        PwdWriteAttempt attempt = new PwdWriteAttempt();
        String primary = normalizePwd(accessPwd);

        try {
            if (reader.writeData(primary, bank, ptr, len, data)) {
                attempt.success = true;
                return attempt;
            }
            for (String preset : PRESET_ACCESS_PASSWORDS) {
                if (preset == null || preset.isEmpty()) continue;
                String normalized = normalizePwd(preset);
                if (normalized.equals(primary)) continue;
                if (reader.writeData(normalized, bank, ptr, len, data)) {
                    attempt.success = true;
                    attempt.usedPresetPassword = true;
                    attempt.presetPasswordUsed = normalized;
                    return attempt;
                }
            }
        } catch (Exception ignored) {
        }
        return attempt;
    }

    private String readTidWithPresetFallback(String accessPwd) {
        String primary = normalizePwd(accessPwd);
        try {
            String tid = reader.readData(primary, BANK_TID, 0, EPC_LEN);
            if (tid != null && !tid.isEmpty()) return tid;
        } catch (Exception ignored) { }
        for (String preset : PRESET_ACCESS_PASSWORDS) {
            if (preset == null || preset.isEmpty()) continue;
            String normalized = normalizePwd(preset);
            if (normalized.equals(primary)) continue;
            try {
                String tid = reader.readData(normalized, BANK_TID, 0, EPC_LEN);
                if (tid != null && !tid.isEmpty()) return tid;
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static String normalizePwd(String pwd) {
        if (pwd == null || pwd.isEmpty()) return DEFAULT_ACCESS_PASSWORD;
        String s = pwd.trim().toUpperCase();
        return s.length() > 8 ? s.substring(0, 8) : s;
    }

    private static boolean isValidHexPwd(String pwd) {
        return pwd != null && pwd.matches("[0-9A-Fa-f]{8}");
    }
}
