package com.rfidw.app.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Jeden úsek TUDU se seznamem výhybek. */
public class Tudu {
    public final String code;                 // např. 1501J1
    public final List<Vyhybka> vyhybky = new ArrayList<>();

    public Tudu(String code) {
        this.code = code;
    }

    /**
     * UDU – prvních 5 znaků TUDU (např. 1501J). V náhledu reprezentuje celou stanici;
     * do EPC a CSV se zapisuje plný kód včetně 6. znaku (podtypu).
     */
    public static String uduCode(String fullCode) {
        if (fullCode == null) return "";
        String trimmed = fullCode.trim();
        if (trimmed.isEmpty()) return "";
        return trimmed.length() <= 5 ? trimmed : trimmed.substring(0, 5);
    }

    public String uduCode() {
        return uduCode(code);
    }

    public Vyhybka findOrCreate(int cislo) {
        for (Vyhybka v : vyhybky) {
            if (v.cislo == cislo && v.iob.isEmpty()) return v;
        }
        for (Vyhybka v : vyhybky) {
            if (v.cislo == cislo) return v;
        }
        return findOrCreate(cislo, null);
    }

    public Vyhybka findOrCreate(int cislo, String iob) {
        String normIob = Vyhybka.normalizeIob(iob);
        for (Vyhybka v : vyhybky) {
            if (v.cislo == cislo && v.iob.equals(normIob)) return v;
        }
        Vyhybka v = new Vyhybka(cislo, normIob);
        vyhybky.add(v);
        return v;
    }

    @Override
    public String toString() {
        return code;
    }

    /** Jedna výhybka v rámci TUDU. */
    public static class Vyhybka {
        public final int cislo;       // číslo výhybky (např. 10)
        public final String iob;      // volitelné písmeno z DB (např. A)
        public int castMin = 1;       // nejmenší část (obvykle 1)
        public int castMax = 3;       // největší část (obvykle 3, někdy 4)
        private final List<RoBranch> roBranches = new ArrayList<>();

        public Vyhybka(int cislo) {
            this(cislo, "");
        }

        public Vyhybka(int cislo, String iob) {
            this.cislo = cislo;
            this.iob = normalizeIob(iob);
        }

        /** Jeden řádek výhybky v DB – párování GPS přes RO_ID, větev z POLOHA. */
        public static final class RoBranch {
            public final String roId;
            public final String poloha;

            public RoBranch(String roId, String poloha) {
                this.roId = roId != null ? roId : "";
                this.poloha = poloha != null ? poloha : "";
            }

            /** POLOHA JAx / JCx – hlavní větev. */
            public static boolean isHlavniPoloha(String poloha) {
                if (poloha == null || poloha.length() < 2) return false;
                char c = Character.toUpperCase(poloha.trim().charAt(1));
                return c == 'A' || c == 'C';
            }

            /** POLOHA JBx / JDx – vedlejší větev. */
            public static boolean isVedlejsiPoloha(String poloha) {
                if (poloha == null || poloha.length() < 2) return false;
                char c = Character.toUpperCase(poloha.trim().charAt(1));
                return c == 'B' || c == 'D';
            }

            public boolean isHlavni() {
                return isHlavniPoloha(poloha);
            }

            public boolean isVedlejsi() {
                return isVedlejsiPoloha(poloha);
            }
        }

        public List<RoBranch> getRoBranches() {
            return roBranches;
        }

        public void addRoBranch(String roId, String poloha) {
            if (roId == null || roId.trim().isEmpty()) return;
            String id = roId.trim();
            for (RoBranch existing : roBranches) {
                if (existing.roId.equals(id)) return;
            }
            roBranches.add(new RoBranch(id, poloha));
        }

        public RoBranch findHlavniBranch() {
            for (RoBranch b : roBranches) {
                if (b.isHlavni()) return b;
            }
            return null;
        }

        public RoBranch findVedlejsiBranch() {
            for (RoBranch b : roBranches) {
                if (b.isVedlejsi()) return b;
            }
            return null;
        }

        public boolean hasDualRoBranches() {
            return findHlavniBranch() != null && findVedlejsiBranch() != null;
        }

        /** Číslo výhybky s volitelným IOB pro náhled a CSV (např. 10A). */
        public String displayLabel() {
            return formatDisplay(cislo, iob);
        }

        public static String formatDisplay(int cislo, String iob) {
            if (cislo <= 0) return "";
            String letter = normalizeIob(iob);
            return letter.isEmpty() ? String.valueOf(cislo) : cislo + letter;
        }

        static String normalizeIob(String iob) {
            if (iob == null) return "";
            String trimmed = iob.trim().toUpperCase(Locale.ROOT);
            if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) return "";
            return trimmed.substring(0, 1);
        }

        @Override
        public String toString() {
            return "výhybka " + displayLabel() + " (čipy " + castMin + "-" + castMax + ")";
        }
    }
}
