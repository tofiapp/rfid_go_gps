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

            /** Druhé písmeno POLOHA u 4částové výhybky (C + písmeno). */
            public static Character fourPartSecondLetter(String poloha) {
                String code = normalizePoloha(poloha);
                if (code.length() < 2 || code.charAt(0) != 'C') return null;
                return code.charAt(1);
            }

            /** POLOHA CA/CG/CE – první pár 4částové výhybky (čipy 1–2). */
            public static boolean isCastPair1Poloha(String poloha) {
                Character c = fourPartSecondLetter(poloha);
                return c != null && (c == 'A' || c == 'G' || c == 'E');
            }

            /** POLOHA CB/CH/CF – druhý pár 4částové výhybky (čipy 3–4). */
            public static boolean isCastPair2Poloha(String poloha) {
                Character c = fourPartSecondLetter(poloha);
                return c != null && (c == 'B' || c == 'H' || c == 'F');
            }

            /** Sada CA/CB/CC/CD – čipy 1–2 = CA, čipy 3–4 = CB. */
            public static boolean isAbcdFourPartPoloha(String poloha) {
                Character c = fourPartSecondLetter(poloha);
                return c != null && c >= 'A' && c <= 'D';
            }

            /** Sada CE/CF/CG/CH – čipy 1–2 = CG, čipy 3–4 = CH. */
            public static boolean isEfghFourPartPoloha(String poloha) {
                Character c = fourPartSecondLetter(poloha);
                return c != null && c >= 'E' && c <= 'H';
            }

            public static String normalizePoloha(String poloha) {
                if (poloha == null) return "";
                return poloha.trim().toUpperCase(Locale.ROOT);
            }

            /** J = 3částová výhybka, C = 4částová – stejná logika jako v {@link DzsDatabase}. */
            public static Integer castMaxFromPoloha(String poloha) {
                String code = normalizePoloha(poloha);
                if (code.isEmpty()) return null;
                char first = code.charAt(0);
                if (first == 'J') return 3;
                if (first == 'C') return 4;
                return null;
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
                    applyCastRangeFromPoloha(mergedPoloha);
                }
                return;
            }
            roBranches.add(new RoBranch(id, pol, km1, km2));
            applyCastRangeFromPoloha(pol);
        }

        /** POLOHA / větve v DB – doplní castMax (J→3, C→4). */
        public void applyCastRangeFromPoloha(String poloha) {
            Integer fromPoloha = RoBranch.castMaxFromPoloha(poloha);
            if (fromPoloha != null) {
                castMax = Math.max(castMax, fromPoloha);
            }
        }

        /** Sloučí rozsah částí z větví a explicitního castMax z DB. */
        public void reconcileCastRangeFromBranches() {
            for (RoBranch branch : roBranches) {
                applyCastRangeFromPoloha(branch.poloha);
            }
            if (hasFourPartPolohaFamily(roBranches) || branchesCoverFourPartWriting(roBranches)) {
                castMax = Math.max(castMax, 4);
            }
        }

        /** Zajistí, že rozsah pojme daný čip (např. po obnově z CSV). */
        public void ensureCastAtLeast(int cast) {
            if (cast > castMax) {
                castMax = cast;
            }
        }

        /** Efektivní horní hranice – bere v úvahu DB, POLOHA i 4částové sady. */
        public int resolvedCastMax() {
            int max = castMax;
            for (RoBranch branch : roBranches) {
                Integer fromPoloha = RoBranch.castMaxFromPoloha(branch.poloha);
                if (fromPoloha != null) {
                    max = Math.max(max, fromPoloha);
                }
            }
            if (hasFourPartPolohaFamily(roBranches) || branchesCoverFourPartWriting(roBranches)) {
                max = Math.max(max, 4);
            }
            return max;
        }

        public int resolvedCastMin() {
            return Math.max(1, castMin);
        }

        public int resolvedCastCount() {
            return resolvedCastMax() - resolvedCastMin() + 1;
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
            return resolvedCastCount() == 4;
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
            FourPartFamily family = FourPartFamily.UNKNOWN;
            for (RoBranch b : branches) {
                if (RoBranch.isAbcdFourPartPoloha(b.poloha)) {
                    family = FourPartFamily.ABCD;
                    break;
                }
                if (RoBranch.isEfghFourPartPoloha(b.poloha)) {
                    family = FourPartFamily.EFGH;
                    break;
                }
            }
            if (family == FourPartFamily.ABCD) {
                return findBranchByWritingLetter(branches, 'A') != null
                        && findBranchByWritingLetter(branches, 'B') != null;
            }
            if (family == FourPartFamily.EFGH) {
                return findBranchByWritingLetter(branches, 'G') != null
                        && findBranchByWritingLetter(branches, 'H') != null;
            }
            return false;
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

        /** Zápisový kód POLOHA pro CSV (CA, CB, CG, CH). */
        public static String fourPartWritingCode(FourPartFamily family, int cast) {
            if (family == FourPartFamily.ABCD) {
                return cast <= 2 ? "CA" : "CB";
            }
            if (family == FourPartFamily.EFGH) {
                return cast <= 2 ? "CG" : "CH";
            }
            return null;
        }

        /** Druhé písmeno zápisového páru (A/B pro ABCD, G/H pro EFGH). */
        public static char fourPartWritingSecondLetter(FourPartFamily family, int cast) {
            if (family == FourPartFamily.ABCD) {
                return cast <= 2 ? 'A' : 'B';
            }
            if (family == FourPartFamily.EFGH) {
                return cast <= 2 ? 'G' : 'H';
            }
            return '\0';
        }

        private static RoBranch findBranchByWritingLetter(List<RoBranch> branches, char secondLetter) {
            if (branches == null || secondLetter == '\0') return null;
            RoBranch exact = null;
            RoBranch withSuffix = null;
            for (RoBranch b : branches) {
                String p = RoBranch.normalizePoloha(b.poloha);
                if (p.length() < 2 || p.charAt(0) != 'C' || p.charAt(1) != secondLetter) continue;
                if (p.length() == 2) {
                    exact = b;
                } else if (withSuffix == null) {
                    withSuffix = b;
                }
            }
            return exact != null ? exact : withSuffix;
        }

        private RoBranch findBranchByWritingLetter(char secondLetter) {
            return findBranchByWritingLetter(roBranches, secondLetter);
        }

        public RoBranch findCastPair1Branch() {
            FourPartFamily family = fourPartFamily();
            char letter = fourPartWritingSecondLetter(family, 1);
            if (letter != '\0') {
                RoBranch found = findBranchByWritingLetter(letter);
                if (found != null) return found;
            }
            return findBranchByWritingLetter('A');
        }

        public RoBranch findCastPair2Branch() {
            FourPartFamily family = fourPartFamily();
            char letter = fourPartWritingSecondLetter(family, 3);
            if (letter != '\0') {
                RoBranch found = findBranchByWritingLetter(letter);
                if (found != null) return found;
            }
            return findBranchByWritingLetter('B');
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
            FourPartFamily family = fourPartFamily();
            String code = fourPartWritingCode(family, cast);
            if (code != null) return code;
            RoBranch branch = resolveBranchForCastFourPart(cast);
            if (branch != null && branch.poloha != null && !branch.poloha.isEmpty()) {
                return RoBranch.normalizePoloha(branch.poloha);
            }
            return null;
        }

        /**
         * Větev pro zápis CSV – vždy vrátí POLOHA/RO_ID pokud je sada známa,
         * i když v indexu chybí přesný řádek CA/CB.
         */
        public RoBranch branchForFourPartCsv(int cast) {
            RoBranch branch = resolveBranchForCastFourPart(cast);
            if (branch != null) return branch;
            FourPartFamily family = fourPartFamily();
            String poloha = fourPartWritingCode(family, cast);
            if (poloha == null) return null;
            return new RoBranch("", poloha, "", "");
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
            return "Výhybka " + displayLabel() + " (čipy " + castMin + "-" + castMax + ")";
        }
    }
}
