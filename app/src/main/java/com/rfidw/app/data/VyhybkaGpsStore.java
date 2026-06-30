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
        private final ArrayList<Float> latitudes = new ArrayList<>();
        private final ArrayList<Float> longitudes = new ArrayList<>();

        void add(String pairKey, String tudu, int vyhybka, double latitude, double longitude) {
            pairKeys.add(pairKey);
            tuduCodes.add(tudu);
            vyhybkaNumbers.add(vyhybka);
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
    private final float[] latitudes;
    private final float[] longitudes;

    private VyhybkaGpsStore(String[] pairKeys, String[] tuduCodes, int[] vyhybkaNumbers,
                              float[] latitudes, float[] longitudes) {
        this.pairKeys = pairKeys;
        this.tuduCodes = tuduCodes;
        this.vyhybkaNumbers = vyhybkaNumbers;
        this.latitudes = latitudes;
        this.longitudes = longitudes;
    }

    static Builder builder() {
        return new Builder();
    }

    static VyhybkaGpsStore empty() {
        return new VyhybkaGpsStore(new String[0], new String[0], new int[0],
                new float[0], new float[0]);
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

    double latitudeAt(int index) {
        return latitudes[index];
    }

    double longitudeAt(int index) {
        return longitudes[index];
    }
}
