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

            /** POLOHA CA/CG/CE – první pár 4částové výhybky (čipy 1–2). */
            public static boolean isCastPair1Poloha(String poloha) {
                String code = normalizePoloha(poloha);
                return "CA".equals(code) || "CG".equals(code) || "CE".equals(code);
            }

            /** POLOHA CB/CH/CF – druhý pár 4částové výhybky (čipy 3–4). */
            public static boolean isCastPair2Poloha(String poloha) {
                String code = normalizePoloha(poloha);
                return "CB".equals(code) || "CH".equals(code) || "CF".equals(code);
            }

            /** Sada CA/CB/CC/CD – čipy 1–2 = CA, čipy 3–4 = CB. */
            public static boolean isAbcdFourPartPoloha(String poloha) {
                String code = normalizePoloha(poloha);
                return "CA".equals(code) || "CB".equals(code)
                        || "CC".equals(code) || "CD".equals(code);
            }

            /** Sada CE/CF/CG/CH – čipy 1–2 = CG, čipy 3–4 = CH. */
            public static boolean isEfghFourPartPoloha(String poloha) {
                String code = normalizePoloha(poloha);
                return "CE".equals(code) || "CF".equals(code)
                        || "CG".equals(code) || "CH".equals(code);
            }

            public static String normalizePoloha(String poloha) {
                if (poloha == null) return "";
                return poloha.trim().toUpperCase(Locale.ROOT);
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

        /** 4částová výhybka (C-type): čipy 1–4. */
        public boolean isFourPart() {
            return castMax - castMin + 1 == 4;
        }

        /** V DB je některá POLOHA ze sady CA/CB/CC/CD nebo CE/CF/CG/CH. */
        public static boolean hasFourPartPolohaFamily(List<RoBranch> branches) {
            if (branches == null) return false;
            for (RoBranch b : branches) {
                if (RoBranch.isAbcdFourPartPoloha(b.poloha) || RoBranch.isEfghFourPartPoloha(b.poloha)) {
                    return true;
                }
            }
            return false;
        }

        /** Má oba zápisové páry RO větví (CA+CB nebo CG+CH). */
        public boolean hasFourPartRoBranches() {
            return findCastPair1Branch() != null && findCastPair2Branch() != null;
        }

        /** Index/SQL obsahuje oba zápisové páry pro 4částovou výhybku. */
        public static boolean branchesCoverFourPartWriting(List<RoBranch> branches) {
            if (branches == null || branches.isEmpty()) return false;
            boolean abcd = false;
            boolean efgh = false;
            for (RoBranch b : branches) {
                if (RoBranch.isAbcdFourPartPoloha(b.poloha)) abcd = true;
                if (RoBranch.isEfghFourPartPoloha(b.poloha)) efgh = true;
            }
            if (abcd) {
                return findBranchByPoloha(branches, "CA") != null
                        && findBranchByPoloha(branches, "CB") != null;
            }
            if (efgh) {
                return findBranchByPoloha(branches, "CG") != null
                        && findBranchByPoloha(branches, "CH") != null;
            }
            return false;
        }

        private static RoBranch findBranchByPoloha(List<RoBranch> branches, String... codes) {
            if (branches == null || codes == null) return null;
            for (String code : codes) {
                String wanted = RoBranch.normalizePoloha(code);
                for (RoBranch b : branches) {
                    if (wanted.equals(RoBranch.normalizePoloha(b.poloha))) {
                        return b;
                    }
                }
            }
            return null;
        }

        private RoBranch findBranchByPoloha(String... codes) {
            return findBranchByPoloha(roBranches, codes);
        }

        /** Sada 4částové výhybky podle POLOHA v DB. */
        public FourPartFamily fourPartFamily() {
            for (RoBranch b : roBranches) {
                if (RoBranch.isAbcdFourPartPoloha(b.poloha)) return FourPartFamily.ABCD;
                if (RoBranch.isEfghFourPartPoloha(b.poloha)) return FourPartFamily.EFGH;
            }
            return FourPartFamily.UNKNOWN;
        }

        public enum FourPartFamily {
            ABCD, EFGH, UNKNOWN
        }

        public RoBranch findCastPair1Branch() {
            FourPartFamily family = fourPartFamily();
            if (family == FourPartFamily.ABCD) {
                return findBranchByPoloha("CA");
            }
            if (family == FourPartFamily.EFGH) {
                return findBranchByPoloha("CG");
            }
            return findBranchByPoloha("CA", "CG", "CE");
        }

        public RoBranch findCastPair2Branch() {
            FourPartFamily family = fourPartFamily();
            if (family == FourPartFamily.ABCD) {
                return findBranchByPoloha("CB");
            }
            if (family == FourPartFamily.EFGH) {
                return findBranchByPoloha("CH");
            }
            return findBranchByPoloha("CB", "CH", "CF");
        }

        /** Větev pro čip 4částové výhybky: čipy 1–2 = CA/CG, čipy 3–4 = CB/CH. */
        public RoBranch resolveBranchForCastFourPart(int cast) {
            if (cast <= 2) return findCastPair1Branch();
            if (cast <= 4) return findCastPair2Branch();
            return null;
        }

        /** Popisek části 4částové výhybky pro nápovědu – vždy konkrétní kód z DB sady. */
        public String castFourPartLabel(int cast) {
            if (cast <= 0 || cast > 4) return null;
            RoBranch branch = resolveBranchForCastFourPart(cast);
            if (branch != null && branch.poloha != null && !branch.poloha.isEmpty()) {
                return RoBranch.normalizePoloha(branch.poloha);
            }
            FourPartFamily family = fourPartFamily();
            if (family == FourPartFamily.ABCD) {
                return cast <= 2 ? "CA" : "CB";
            }
            if (family == FourPartFamily.EFGH) {
                return cast <= 2 ? "CG" : "CH";
            }
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
