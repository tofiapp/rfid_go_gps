package com.rfidw.app.data;

import java.util.ArrayList;

/**
 * Kompaktní úložiště předpočítaných GPS souřadnic výhybek.
 * Každá výhybka má jednu souřadnici z GPS tabulky (párování přes RO_ID).
 */
final class VyhybkaGpsStore {

    static final class Builder {
        private final ArrayList<String> pairKeys = new ArrayList<>();
        private final ArrayList<String> tuduCodes = new ArrayList<>();
        private final ArrayList<Integer> vyhybkaNumbers = new ArrayList<>();
        private final ArrayList<String> roIds = new ArrayList<>();
        private final ArrayList<String> polohas = new ArrayList<>();
        private final ArrayList<Float> latitudes = new ArrayList<>();
        private final ArrayList<Float> longitudes = new ArrayList<>();

        void add(String pairKey, String tudu, int vyhybka, String roId, String poloha,
                 double latitude, double longitude) {
            pairKeys.add(pairKey);
            tuduCodes.add(tudu);
            vyhybkaNumbers.add(vyhybka);
            roIds.add(roId != null ? roId : "");
            polohas.add(poloha != null ? poloha : "");
            latitudes.add((float) latitude);
            longitudes.add((float) longitude);
        }

        int size() {
            return pairKeys.size();
        }

        VyhybkaGpsStore build() {
            if (pairKeys.isEmpty()) {
                return empty();
            }
            return new VyhybkaGpsStore(
                    pairKeys.toArray(new String[0]),
                    tuduCodes.toArray(new String[0]),
                    toIntArray(vyhybkaNumbers),
                    roIds.toArray(new String[0]),
                    polohas.toArray(new String[0]),
                    toFloatArray(latitudes),
                    toFloatArray(longitudes));
        }

        private static int[] toIntArray(ArrayList<Integer> list) {
            int[] out = new int[list.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = list.get(i);
            }
            return out;
        }

        private static float[] toFloatArray(ArrayList<Float> list) {
            float[] out = new float[list.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = list.get(i);
            }
            return out;
        }
    }

    private final String[] pairKeys;
    private final String[] tuduCodes;
    private final int[] vyhybkaNumbers;
    private final String[] roIds;
    private final String[] polohas;
    private final float[] latitudes;
    private final float[] longitudes;

    private VyhybkaGpsStore(String[] pairKeys, String[] tuduCodes, int[] vyhybkaNumbers,
                              String[] roIds, String[] polohas,
                              float[] latitudes, float[] longitudes) {
        this.pairKeys = pairKeys;
        this.tuduCodes = tuduCodes;
        this.vyhybkaNumbers = vyhybkaNumbers;
        this.roIds = roIds;
        this.polohas = polohas;
        this.latitudes = latitudes;
        this.longitudes = longitudes;
    }

    static Builder builder() {
        return new Builder();
    }

    static VyhybkaGpsStore empty() {
        return new VyhybkaGpsStore(new String[0], new String[0], new int[0],
                new String[0], new String[0], new float[0], new float[0]);
    }

    /** Sloučí dva úložiště; duplicitní RO_ID (pairKey+roId) se přeskočí. */
    static VyhybkaGpsStore merge(VyhybkaGpsStore primary, VyhybkaGpsStore secondary) {
        if (primary == null || primary.isEmpty()) {
            return secondary != null ? secondary : empty();
        }
        if (secondary == null || secondary.isEmpty()) {
            return primary;
        }
        Builder builder = builder();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        appendUnique(builder, seen, primary);
        appendUnique(builder, seen, secondary);
        return builder.build();
    }

    private static void appendUnique(Builder builder, java.util.HashSet<String> seen,
                                     VyhybkaGpsStore store) {
        for (int i = 0; i < store.size(); i++) {
            String key = store.pairKeyAt(i) + "|" + store.roIdAt(i);
            if (!seen.add(key)) continue;
            builder.add(store.pairKeyAt(i), store.tuduAt(i), store.vyhybkaAt(i),
                    store.roIdAt(i), store.polohaAt(i),
                    store.latitudeAt(i), store.longitudeAt(i));
        }
    }

    int size() {
        return pairKeys.length;
    }

    boolean isEmpty() {
        return pairKeys.length == 0;
    }

    String pairKeyAt(int index) {
        return pairKeys[index];
    }

    String tuduAt(int index) {
        return tuduCodes[index];
    }

    int vyhybkaAt(int index) {
        return vyhybkaNumbers[index];
    }

    String roIdAt(int index) {
        return roIds[index];
    }

    String polohaAt(int index) {
        return polohas[index];
    }

    double latitudeAt(int index) {
        return latitudes[index];
    }

    double longitudeAt(int index) {
        return longitudes[index];
    }
}
