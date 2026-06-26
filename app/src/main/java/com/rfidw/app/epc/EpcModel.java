package com.rfidw.app.epc;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * Sestavení a rozklad EPC kódu podle šablony RFID Go.
 *
 * EPC = 24 hex znaků (6 wordů, bank EPC, ptr 2, Len 6):
 *  řádek 1 | 4 znaky | rok            (např. 2026)
 *  řádek 2 | 4 znaky | TUDU 1.-4.znak (první 4 znaky vybraného TUDU)
 *  řádek 3 | 2 znaky | TUDU 5.znak    -> ASCII hex (J = 4A)
 *  řádek 4 | 2 znaky | TUDU 6.znak    -> 2-místně (1 = 01)
 *  řádek 5 | 3 znaky | vyhybka        -> 3-místně dekadicky (10 = 010)
 *  řádek 6 | 1 znak  | cip            (1-4)
 *  řádek 7 | 8 znaků | ID_RFID        -> 8-místně dekadicky (30001 = 00030001)
 *
 *  4 + 4 + 2 + 2 + 3 + 1 + 8 = 24
 */
public class EpcModel {

    public static final int LENGTH = 24;

    // názvy kategorií (řádků šablony) – jdou přepsat v UI
    public String nameYear      = "rok";
    public String nameTudu14    = "TUDU (1.-4. znak)";
    public String nameTudu5     = "TUDU (5. znak / ASCII)";
    public String nameTudu6     = "TUDU (6. znak)";
    public String nameVyhybka   = "vyhybka";
    public String nameCast      = "cip";
    public String nameIdRfid    = "ID_RFID";

    // hodnoty
    public String year     = "2026";  // fixně, ale přepsatelné
    public String tudu     = "";      // celé TUDU, např. 1501J1
    public int    vyhybka  = 0;       // číslo výhybky
    public int    cast     = 1;       // část výhybky 1-4
    public long   idRfid   = 0;       // pořadové číslo tagu

    // ---- jednotlivé řádky šablony jako hex ----

    public String f1Year() {
        String y = safe(year);
        if (y.length() > 4) y = y.substring(0, 4);
        return padRight(y, 4, '0');
    }

    /** první 4 znaky TUDU */
    public String f2Tudu14() {
        String t = safe(tudu).toUpperCase(Locale.ROOT);
        String first = t.length() >= 4 ? t.substring(0, 4) : padRight(t, 4, '0');
        return first;
    }

    /** 5. znak TUDU -> ASCII hex (J = 4A) */
    public String f3Tudu5() {
        String t = safe(tudu);
        if (t.length() < 5) return "00";
        char c = t.charAt(4);
        return String.format(Locale.ROOT, "%02X", (int) c);
    }

    /** 6. znak TUDU -> 2 znaky (číslice 1 = 01, jinak ASCII hex) */
    public String f4Tudu6() {
        String t = safe(tudu);
        if (t.length() < 6) return "00";
        char c = t.charAt(5);
        if (Character.isDigit(c)) {
            return String.format(Locale.ROOT, "%02d", c - '0');
        }
        return String.format(Locale.ROOT, "%02X", (int) c);
    }

    /** výhybka 3-místně dekadicky (10 = 010) */
    public String f5Vyhybka() {
        return String.format(Locale.ROOT, "%03d", Math.max(0, vyhybka) % 1000);
    }

    /** část výhybky – 1 znak */
    public String f6Cast() {
        int v = Math.max(0, cast) % 16;
        return Integer.toHexString(v).toUpperCase(Locale.ROOT);
    }

    /** ID_RFID 8-místně dekadicky (30001 = 00030001) */
    public String f7IdRfid() {
        long v = Math.max(0, idRfid) % 100000000L;
        return String.format(Locale.ROOT, "%08d", v);
    }

    /** Celý EPC (24 hex znaků, bez pomlček) */
    public String buildEpc() {
        return (f1Year() + f2Tudu14() + f3Tudu5() + f4Tudu6()
                + f5Vyhybka() + f6Cast() + f7IdRfid()).toUpperCase(Locale.ROOT);
    }

    /** Náhled ve formátu xxxx-xxxx-xxxx-xxxx-xxxx-xxxx */
    public String buildEpcPreview() {
        String e = buildEpc();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < e.length(); i += 4) {
            if (i > 0) sb.append('-');
            sb.append(e, i, Math.min(i + 4, e.length()));
        }
        return sb.toString();
    }

    /** Je výsledný EPC validní (24 znaků, samé hex)? */
    public boolean isValid() {
        String e = buildEpc();
        if (e.length() != LENGTH) return false;
        for (int i = 0; i < e.length(); i++) {
            char c = Character.toUpperCase(e.charAt(i));
            boolean hex = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    // ----------------------------------------------------------------
    //  Rozklad EPC zpět na hodnoty (pro zápis do CSV)
    // ----------------------------------------------------------------

    public static class Decoded {
        public String idRfid;   // např. 30001 (bez vodících nul)
        public String epc;      // celých 24 znaků
        public String rok;      // 2026
        public String tudu;     // 1501J1
        public String vyhybka;  // 10
        public String cast;     // 1
    }

    /** Rozloží 24-znakový EPC na hodnoty podle šablony. */
    public static Decoded decode(@NonNull String epc24) {
        String e = epc24.trim().toUpperCase(Locale.ROOT);
        if (e.length() != LENGTH) {
            throw new IllegalArgumentException("EPC musí mít " + LENGTH + " znaků, má " + e.length());
        }
        Decoded d = new Decoded();
        d.epc = e;
        d.rok = e.substring(0, 4);

        String t14 = e.substring(4, 8);
        String t5hex = e.substring(8, 10);
        String t6 = e.substring(10, 12);

        StringBuilder tudu = new StringBuilder();
        tudu.append(t14);
        // 5. znak z ASCII hex
        int ascii5 = Integer.parseInt(t5hex, 16);
        if (ascii5 != 0) tudu.append((char) ascii5);
        // 6. znak: pokud "00" -> nic, pokud dekadicky 01..09 -> číslice, jinak ASCII
        if (!t6.equals("00")) {
            try {
                int dec = Integer.parseInt(t6, 10);
                if (dec >= 0 && dec <= 9) {
                    tudu.append((char) ('0' + dec));
                } else {
                    tudu.append((char) Integer.parseInt(t6, 16));
                }
            } catch (NumberFormatException ex) {
                tudu.append((char) Integer.parseInt(t6, 16));
            }
        }
        d.tudu = tudu.toString();

        d.vyhybka = String.valueOf(Integer.parseInt(e.substring(12, 15), 10));
        d.cast = String.valueOf(Integer.parseInt(e.substring(15, 16), 16));
        d.idRfid = String.valueOf(Long.parseLong(e.substring(16, 24), 10));
        return d;
    }

    // ---- pomocné ----

    private static String safe(String s) { return s == null ? "" : s.trim(); }

    private static String padRight(String s, int len, char pad) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < len) sb.append(pad);
        return sb.toString();
    }
}
