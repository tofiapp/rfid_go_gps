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
            /** KM_REF (shoduje se s OD nebo DO) – hodnota pro čip 1. */
            public final String kmExtChip1;
            /** Druhá z hodnot OD/DO – hodnota pro čipy 2 a 3. */
            public final String kmExtOther;

            public RoBranch(String roId, String poloha) {
                this(roId, poloha, "", "");
            }

            public RoBranch(String roId, String poloha, String kmExtChip1, String kmExtOther) {
                this.roId = roId != null ? roId : "";
                this.poloha = poloha != null ? poloha : "";
                this.kmExtChip1 = kmExtChip1 != null ? kmExtChip1 : "";
                this.kmExtOther = kmExtOther != null ? kmExtOther : "";
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

            /** POLOHA CA/CG – první pár 4částové výhybky (čipy 1–2). */
            public static boolean isCastPair1Poloha(String poloha) {
                if (poloha == null || poloha.length() < 2) return false;
                char c = Character.toUpperCase(poloha.trim().charAt(1));
                return c == 'A' || c == 'G';
            }

            /** POLOHA CB/CH – druhý pár 4částové výhybky (čipy 3–4). */
            public static boolean isCastPair2Poloha(String poloha) {
                if (poloha == null || poloha.length() < 2) return false;
                char c = Character.toUpperCase(poloha.trim().charAt(1));
                return c == 'B' || c == 'H';
            }

            public boolean isCastPair1() {
                return isCastPair1Poloha(poloha);
            }

            public boolean isCastPair2() {
                return isCastPair2Poloha(poloha);
            }
        }

        public List<RoBranch> getRoBranches() {
            return roBranches;
        }

        public void addRoBranch(String roId, String poloha) {
            addRoBranch(roId, poloha, "", "");
        }

        public void addRoBranch(String roId, String poloha, String kmExtChip1, String kmExtOther) {
            if (roId == null || roId.trim().isEmpty()) return;
            String id = roId.trim();
            String pol = poloha != null ? poloha : "";
            String km1 = kmExtChip1 != null ? kmExtChip1 : "";
            String km2 = kmExtOther != null ? kmExtOther : "";
            for (int i = 0; i < roBranches.size(); i++) {
                RoBranch existing = roBranches.get(i);
                if (!existing.roId.equals(id)) continue;
                String mergedPoloha = existing.poloha.isEmpty() ? pol : existing.poloha;
                String mergedKm1 = existing.kmExtChip1.isEmpty() ? km1 : existing.kmExtChip1;
                String mergedKm2 = existing.kmExtOther.isEmpty() ? km2 : existing.kmExtOther;
                if (!mergedPoloha.equals(existing.poloha)
                        || !mergedKm1.equals(existing.kmExtChip1)
                        || !mergedKm2.equals(existing.kmExtOther)) {
                    roBranches.set(i, new RoBranch(id, mergedPoloha, mergedKm1, mergedKm2));
                }
                return;
            }
            roBranches.add(new RoBranch(id, pol, km1, km2));
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

        /** 4částová výhybka (C-type): čipy 1–4, páry POLOHA CA/CG a CB/CH. */
        public boolean isFourPart() {
            return castMax - castMin + 1 == 4;
        }

        public RoBranch findCastPair1Branch() {
            for (RoBranch b : roBranches) {
                if (b.isCastPair1()) return b;
            }
            return null;
        }

        public RoBranch findCastPair2Branch() {
            for (RoBranch b : roBranches) {
                if (b.isCastPair2()) return b;
            }
            return null;
        }

        /** Větev pro čip 4částové výhybky: čipy 1–2 = CA/CG, čipy 3–4 = CB/CH. */
        public RoBranch resolveBranchForCastFourPart(int cast) {
            if (cast <= 2) return findCastPair1Branch();
            if (cast <= 4) return findCastPair2Branch();
            return null;
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
