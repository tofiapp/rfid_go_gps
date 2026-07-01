package com.rfidw.app.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Kompaktní index všech km bodů pro určení POLOHA (všechna RO_ID dané výhybky).
 * Reprezentační body pro findNearest jsou v {@link VyhybkaGpsStore}.
 */
final class RoGpsPolohaIndex {

    static final class Builder {
        private final ArrayList<String> roKeyTable = new ArrayList<>();
        private final HashMap<String, Integer> roKeyToIndex = new HashMap<>();
        private final ArrayList<Integer> roKeyIndices = new ArrayList<>();
        private final ArrayList<Float> latitudes = new ArrayList<>();
        private final ArrayList<Float> longitudes = new ArrayList<>();

        void addPoint(String roKey, double latitude, double longitude) {
            if (roKey == null || roKey.isEmpty()) return;
            Integer index = roKeyToIndex.get(roKey);
            if (index == null) {
                index = roKeyTable.size();
                roKeyToIndex.put(roKey, index);
                roKeyTable.add(roKey);
            }
            roKeyIndices.add(index);
            latitudes.add((float) latitude);
            longitudes.add((float) longitude);
        }

        int size() {
            return roKeyIndices.size();
        }

        RoGpsPolohaIndex build() {
            if (roKeyIndices.isEmpty()) {
                return empty();
            }
            return new RoGpsPolohaIndex(
                    roKeyTable.toArray(new String[0]),
                    toIntArray(roKeyIndices),
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
