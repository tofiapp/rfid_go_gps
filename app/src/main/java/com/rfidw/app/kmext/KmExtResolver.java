package com.rfidw.app.kmext;

import java.util.Locale;

/**
 * KM_EXT z tabulky {@code DZS_SUPER_RO_TPI}: sloupce OD, DO, KM_REF.
 * KM_REF se shoduje s OD nebo DO – tato hodnota je pro čip 1, druhá pro čipy 2 a 3.
 */
public final class KmExtResolver {

    public static final class Values {
        public final String chip1;
        public final String other;

        public Values(String chip1, String other) {
            this.chip1 = chip1 != null ? chip1 : "";
            this.other = other != null ? other : "";
        }

        public static Values empty() {
            return new Values("", "");
        }
    }

    private static final double KM_EPS = 1e-4;

    private KmExtResolver() {}

    public static Values fromOdDoKmRef(Double od, Double doVal, Double kmRef) {
        if (od == null || doVal == null || kmRef == null) {
            return Values.empty();
        }
        String chip1 = formatKm(kmRef);
        String other;
        if (nearlyEqual(kmRef, od)) {
            other = formatKm(doVal);
        } else if (nearlyEqual(kmRef, doVal)) {
            other = formatKm(od);
        } else {
            // KM_REF nemusí sedět na konec větve (např. střed OD/DO pro GPS) – vezmeme vzdálenější konec
            other = Math.abs(kmRef - od) >= Math.abs(kmRef - doVal)
                    ? formatKm(od) : formatKm(doVal);
        }
        return new Values(chip1, other);
    }

    public static String formatKm(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "";
        if (Math.rint(value) == value) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static boolean nearlyEqual(double a, double b) {
        return Math.abs(a - b) < KM_EPS;
    }
}
