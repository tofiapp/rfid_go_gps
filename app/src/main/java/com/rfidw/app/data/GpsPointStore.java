package com.rfidw.app.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Kompaktní úložiště GPS km bodů v primitivních polích (~4× méně RAM než seznam objektů).
 * Každý pár SUPER_Z_ID|SUPER_D_ID je uložen jednou v tabulce řetězců, body odkazují int pairId.
 */
final class GpsPointStore {

    static final class Builder {
        private final HashMap<String, Integer> pairKeyToId = new HashMap<>();
        private final ArrayList<String> pairKeys = new ArrayList<>();
        private int[] pairId;
        private double[] kmExt;
        private float[] latitude;
        private float[] longitude;
        private int size;

        Builder(int capacity) {
            int cap = Math.max(capacity, 256);
            pairId = new int[cap];
            kmExt = new double[cap];
            latitude = new float[cap];
            longitude = new float[cap];
        }

        int size() {
            return size;
        }

        void addPoint(String superZId, String superDId, double km, double lat, double lon) {
            if (size >= pairId.length) {
                grow();
            }
            String key = pairKey(superZId, superDId);
            Integer id = pairKeyToId.get(key);
            if (id == null) {
                id = pairKeys.size();
                pairKeyToId.put(key, id);
                pairKeys.add(key);
            }
            pairId[size] = id;
            kmExt[size] = km;
            latitude[size] = (float) lat;
            longitude[size] = (float) lon;
            size++;
        }

        GpsPointStore build() {
            if (size == 0) {
                return empty();
            }
            trim();
            String[] pairKeyTable = pairKeys.toArray(new String[0]);
            int[][] pointIndicesByPair = buildPointIndicesByPair(pairKeyTable.length);
            return new GpsPointStore(pairKeyTable, pairKeyToId, pairId, kmExt, latitude, longitude,
                    pointIndicesByPair);
        }

        private void grow() {
            int newCap = pairId.length + (pairId.length >> 1) + 1;
            pairId = copyOf(pairId, newCap);
            kmExt = copyOf(kmExt, newCap);
            latitude = copyOf(latitude, newCap);
            longitude = copyOf(longitude, newCap);
        }

        private void trim() {
            if (size == pairId.length) return;
            pairId = copyOf(pairId, size);
            kmExt = copyOf(kmExt, size);
            latitude = copyOf(latitude, size);
            longitude = copyOf(longitude, size);
        }

        private int[][] buildPointIndicesByPair(int pairCount) {
            int[] counts = new int[pairCount];
            for (int i = 0; i < size; i++) {
                counts[pairId[i]]++;
            }
            int[][] indices = new int[pairCount][];
            for (int p = 0; p < pairCount; p++) {
                indices[p] = new int[counts[p]];
            }
            int[] next = new int[pairCount];
            for (int i = 0; i < size; i++) {
                int p = pairId[i];
                indices[p][next[p]++] = i;
            }
            return indices;
        }

        private static int[] copyOf(int[] src, int len) {
            int[] out = new int[len];
            System.arraycopy(src, 0, out, 0, Math.min(src.length, len));
            return out;
        }

        private static double[] copyOf(double[] src, int len) {
            double[] out = new double[len];
            System.arraycopy(src, 0, out, 0, Math.min(src.length, len));
            return out;
        }

        private static float[] copyOf(float[] src, int len) {
            float[] out = new float[len];
            System.arraycopy(src, 0, out, 0, Math.min(src.length, len));
            return out;
        }

        private static String pairKey(String superZId, String superDId) {
            return superZId + "|" + superDId;
        }
    }

    private final String[] pairKeyTable;
    private final HashMap<String, Integer> pairKeyToId;
    private final int[] pairId;
    private final double[] kmExt;
    private final float[] latitude;
    private final float[] longitude;
    private final int[][] pointIndicesByPair;

    private GpsPointStore(String[] pairKeyTable, HashMap<String, Integer> pairKeyToId,
                          int[] pairId, double[] kmExt, float[] latitude, float[] longitude,
                          int[][] pointIndicesByPair) {
        this.pairKeyTable = pairKeyTable;
        this.pairKeyToId = pairKeyToId;
        this.pairId = pairId;
        this.kmExt = kmExt;
        this.latitude = latitude;
        this.longitude = longitude;
        this.pointIndicesByPair = pointIndicesByPair;
    }

    static Builder builder(int capacity) {
        return new Builder(capacity);
    }

    static GpsPointStore empty() {
        return new GpsPointStore(new String[0], new HashMap<>(), new int[0], new double[0],
                new float[0], new float[0], new int[0][]);
    }

    int size() {
        return pairId.length;
    }

    boolean isEmpty() {
        return pairId.length == 0;
    }

    String pairKeyAt(int index) {
        return pairKeyTable[pairId[index]];
    }

    int pairIdForKey(String pairKey) {
        Integer id = pairKeyToId.get(pairKey);
        return id == null ? -1 : id;
    }

    int[] indicesForPair(String pairKey) {
        int id = pairIdForKey(pairKey);
        if (id < 0) return null;
        return pointIndicesByPair[id];
    }

    double kmExtAt(int index) {
        return kmExt[index];
    }

    double latitudeAt(int index) {
        return latitude[index];
    }

    double longitudeAt(int index) {
        return longitude[index];
    }

    String[] pairKeyTable() {
        return pairKeyTable;
    }

    int pairIdAt(int index) {
        return pairId[index];
    }
}
