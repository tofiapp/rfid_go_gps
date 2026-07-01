package com.rfidw.app.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Kompaktní index všech km bodů pro určení POLOHA (všechna RO_ID dané výhybky).
 * Reprezentační body pro findNearest jsou v {@link VyhybkaGpsStore}.
 */
final class RoGpsPolohaIndex {

    static final class Builder {
        private static final int INITIAL_CAPACITY = 4096;

        private final ArrayList<String> roKeyTable = new ArrayList<>();
        private final HashMap<String, Integer> roKeyToIndex = new HashMap<>();
        private int[] roKeyIndices = new int[INITIAL_CAPACITY];
        private float[] latitudes = new float[INITIAL_CAPACITY];
        private float[] longitudes = new float[INITIAL_CAPACITY];
        private int size;

        void addPoint(String roKey, double latitude, double longitude) {
            if (roKey == null || roKey.isEmpty()) return;
            Integer index = roKeyToIndex.get(roKey);
            if (index == null) {
                index = roKeyTable.size();
                roKeyToIndex.put(roKey, index);
                roKeyTable.add(roKey);
            }
            if (size == roKeyIndices.length) {
                int next = size * 2;
                roKeyIndices = Arrays.copyOf(roKeyIndices, next);
                latitudes = Arrays.copyOf(latitudes, next);
                longitudes = Arrays.copyOf(longitudes, next);
            }
            roKeyIndices[size] = index;
            latitudes[size] = (float) latitude;
            longitudes[size] = (float) longitude;
            size++;
        }

        int size() {
            return size;
        }

        RoGpsPolohaIndex build() {
            if (size == 0) {
                return empty();
            }
            return new RoGpsPolohaIndex(
                    roKeyTable.toArray(new String[0]),
                    Arrays.copyOf(roKeyIndices, size),
                    Arrays.copyOf(latitudes, size),
                    Arrays.copyOf(longitudes, size));
        }
    }

    private final String[] roKeys;
    private final int[] roKeyIndices;
    private final float[] latitudes;
    private final float[] longitudes;

    private RoGpsPolohaIndex(String[] roKeys, int[] roKeyIndices,
                             float[] latitudes, float[] longitudes) {
        this.roKeys = roKeys;
        this.roKeyIndices = roKeyIndices;
        this.latitudes = latitudes;
        this.longitudes = longitudes;
    }

    static Builder builder() {
        return new Builder();
    }

    static RoGpsPolohaIndex empty() {
        return new RoGpsPolohaIndex(new String[0], new int[0], new float[0], new float[0]);
    }

    int size() {
        return roKeyIndices.length;
    }

    boolean isEmpty() {
        return roKeyIndices.length == 0;
    }

    String roKeyAt(int pointIndex) {
        return roKeys[roKeyIndices[pointIndex]];
    }

    int roKeyIndexAt(int pointIndex) {
        return roKeyIndices[pointIndex];
    }

    double latitudeAt(int pointIndex) {
        return latitudes[pointIndex];
    }

    double longitudeAt(int pointIndex) {
        return longitudes[pointIndex];
    }

    Iterable<Map.Entry<String, Integer>> roKeyTableEntries() {
        ArrayList<Map.Entry<String, Integer>> out = new ArrayList<>(roKeys.length);
        for (int i = 0; i < roKeys.length; i++) {
            out.add(new HashMap.SimpleEntry<>(roKeys[i], i));
        }
        return out;
    }

    String[] roKeyTable() {
        return roKeys;
    }
}
