package com.rfidw.app.data;

import java.util.HashMap;
import java.util.Map;

/**
 * Krajní GPS body každého RO_ID (první a poslední bod úseku podle KM_EXT).
 * Slouží k určení POLOHA podle bližšího konce úseku.
 */
final class RoGpsEndpoints {

    static final class Endpoint {
        final double firstLatitude;
        final double firstLongitude;
        final double lastLatitude;
        final double lastLongitude;

        Endpoint(double firstLatitude, double firstLongitude,
                 double lastLatitude, double lastLongitude) {
            this.firstLatitude = firstLatitude;
            this.firstLongitude = firstLongitude;
            this.lastLatitude = lastLatitude;
            this.lastLongitude = lastLongitude;
        }
    }

    static final class Builder {
        private static final class ExtremesTracker {
            double firstLatitude;
            double firstLongitude;
            double lastLatitude;
            double lastLongitude;
            double minKey = Double.POSITIVE_INFINITY;
            double maxKey = Double.NEGATIVE_INFINITY;
            boolean hasPoint;

            void consider(double latitude, double longitude, double sortKey) {
                if (!hasPoint) {
                    hasPoint = true;
                    minKey = maxKey = sortKey;
                    firstLatitude = lastLatitude = latitude;
                    firstLongitude = lastLongitude = longitude;
                    return;
                }
                if (sortKey < minKey) {
                    minKey = sortKey;
                    firstLatitude = latitude;
                    firstLongitude = longitude;
                }
                if (sortKey > maxKey) {
                    maxKey = sortKey;
                    lastLatitude = latitude;
                    lastLongitude = longitude;
                }
            }
        }

        private final Map<String, ExtremesTracker> trackers = new HashMap<>();

        void addPoint(String roKey, double latitude, double longitude, double sortKey) {
            if (roKey == null || roKey.isEmpty()) return;
            trackers.computeIfAbsent(roKey, k -> new ExtremesTracker())
                    .consider(latitude, longitude, sortKey);
        }

        RoGpsEndpoints build() {
            if (trackers.isEmpty()) {
                return empty();
            }
            Map<String, Endpoint> out = new HashMap<>(trackers.size());
            for (Map.Entry<String, ExtremesTracker> e : trackers.entrySet()) {
                ExtremesTracker t = e.getValue();
                if (!t.hasPoint) continue;
                out.put(e.getKey(), new Endpoint(
                        t.firstLatitude, t.firstLongitude, t.lastLatitude, t.lastLongitude));
            }
            return new RoGpsEndpoints(out);
        }
    }

    private final Map<String, Endpoint> byRoKey;

    private RoGpsEndpoints(Map<String, Endpoint> byRoKey) {
        this.byRoKey = byRoKey;
    }

    static Builder builder() {
        return new Builder();
    }

    static RoGpsEndpoints empty() {
        return new RoGpsEndpoints(new HashMap<>());
    }

    static RoGpsEndpoints fromEntries(Map<String, Endpoint> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) {
            return empty();
        }
        return new RoGpsEndpoints(new HashMap<>(endpoints));
    }

    boolean isEmpty() {
        return byRoKey.isEmpty();
    }

    int size() {
        return byRoKey.size();
    }

    Endpoint endpointFor(String roKey) {
        return roKey != null ? byRoKey.get(roKey) : null;
    }

    Iterable<Map.Entry<String, Endpoint>> entries() {
        return byRoKey.entrySet();
    }
}
