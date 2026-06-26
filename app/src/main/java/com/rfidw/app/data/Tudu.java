package com.rfidw.app.data;

import java.util.ArrayList;
import java.util.List;

/** Jeden úsek TUDU se seznamem výhybek. */
public class Tudu {
    public final String code;                 // např. 1501J1
    public final List<Vyhybka> vyhybky = new ArrayList<>();

    public Tudu(String code) {
        this.code = code;
    }

    public Vyhybka findOrCreate(int cislo) {
        for (Vyhybka v : vyhybky) {
            if (v.cislo == cislo) return v;
        }
        Vyhybka v = new Vyhybka(cislo);
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
        public int castMin = 1;       // nejmenší část (obvykle 1)
        public int castMax = 3;       // největší část (obvykle 3, někdy 4)

        public Vyhybka(int cislo) {
            this.cislo = cislo;
        }

        @Override
        public String toString() {
            return "výhybka " + cislo + " (čipy " + castMin + "-" + castMax + ")";
        }
    }
}
