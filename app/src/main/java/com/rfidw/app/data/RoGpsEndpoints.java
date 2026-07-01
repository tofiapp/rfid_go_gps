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
        private static final class Tracker {
            double firstLatitude;
            double firstLongitude;
            double lastLatitude;
            double lastLongitude;
            boolean hasPoint;

            void add(double latitude, double longitude) {
                if (!hasPoint) {
                    firstLatitude = lastLatitude = latitude;
                    firstLongitude = lastLongitude = longitude;
                    hasPoint = true;
                } else {
                    lastLatitude = latitude;
                    lastLongitude = longitude;
                }
            }
        }

        private final Map<String, Tracker> trackers = new HashMap<>();

        void addPoint(String roKey, double latitude, double longitude) {
            if (roKey == null || roKey.isEmpty()) return;
            trackers.computeIfAbsent(roKey, k -> new Tracker()).add(latitude, longitude);
        }

        RoGpsEndpoints build() {
            if (trackers.isEmpty()) {
                return empty();
            }
            Map<String, Endpoint> out = new HashMap<>(trackers.size());
            for (Map.Entry<String, Tracker> e : trackers.entrySet()) {
                Tracker t = e.getValue();
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
