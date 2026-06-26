package com.rfidw.app.location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Cache nejlepší známé GPS polohy – preferuje satelitní fix, aktualizace cca každých 500 ms.
 */
public class LocationCache implements LocationListener {

    public static final long GPS_UPDATE_INTERVAL_MS = 500;
    public static final long NETWORK_UPDATE_INTERVAL_MS = 2000;
    public static final long STALE_AFTER_MS = 30_000;
    /** Fix starší než toto se při výběru nepreferuje oproti čerstvějšímu. */
    private static final long RECENT_FIX_MS = 15_000;

    public static class Snapshot {
        public final double latitude;
        public final double longitude;
        public final float accuracyM;
        public final long gpsTimeMs;
        public final boolean valid;
        public final String provider;

        private Snapshot(double latitude, double longitude, float accuracyM, long gpsTimeMs,
                         boolean valid, String provider) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.accuracyM = accuracyM;
            this.gpsTimeMs = gpsTimeMs;
            this.valid = valid;
            this.provider = provider != null ? provider : "";
        }

        public static Snapshot empty() {
            return new Snapshot(0, 0, 0, 0, false, "");
        }

        public boolean isGpsProvider() {
            return LocationManager.GPS_PROVIDER.equals(provider);
        }
    }

    public interface Listener {
        void onLocationUpdated();
    }

    private final LocationManager locationManager;
    private Listener listener;
    private Snapshot snapshot = Snapshot.empty();
    private Snapshot testOverride = Snapshot.empty();
    private boolean running;

    public LocationCache(Context context) {
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public synchronized Snapshot getSnapshot() {
        if (testOverride.valid) return testOverride;
        return snapshot;
    }

    public boolean hasFix() {
        return testOverride.valid || snapshot.valid;
    }

    public boolean hasTestOverride() {
        return testOverride.valid;
    }

    public void setTestOverride(double latitude, double longitude) {
        synchronized (this) {
            testOverride = new Snapshot(
                    latitude, longitude, 5f, System.currentTimeMillis(), true, "test");
        }
        if (listener != null) listener.onLocationUpdated();
    }

    public void clearTestOverride() {
        boolean hadOverride;
        synchronized (this) {
            hadOverride = testOverride.valid;
            testOverride = Snapshot.empty();
        }
        if (hadOverride && listener != null) listener.onLocationUpdated();
    }

    public boolean isStale() {
        Snapshot active = getSnapshot();
        if (!active.valid) return false;
        if (testOverride.valid) return false;
        return System.currentTimeMillis() - active.gpsTimeMs > STALE_AFTER_MS;
    }

    public void start(Context context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        running = true;
        refresh(context);
    }

    /** Znovu načte poslední polohu a obnoví odběr – nutné po návratu z výběru souboru / dialogu oprávnění. */
    public void refresh(Context context) {
        if (!running) return;
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            locationManager.removeUpdates(this);
        } catch (SecurityException ignored) {
        }
        seedLastKnown(context, true);
        requestUpdates(LocationManager.GPS_PROVIDER, GPS_UPDATE_INTERVAL_MS);
        requestUpdates(LocationManager.NETWORK_PROVIDER, NETWORK_UPDATE_INTERVAL_MS);
    }

    public void stop() {
        if (!running) return;
        running = false;
        try {
            locationManager.removeUpdates(this);
        } catch (SecurityException ignored) {
        }
    }

    private void requestUpdates(String provider, long intervalMs) {
        try {
            if (!locationManager.isProviderEnabled(provider)) return;
            locationManager.requestLocationUpdates(
                    provider, intervalMs, 0, this, Looper.getMainLooper());
        } catch (SecurityException ignored) {
        }
    }

    private void seedLastKnown(Context context, boolean notify) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Location best = null;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                if (!locationManager.isProviderEnabled(provider)) continue;
                Location location = locationManager.getLastKnownLocation(provider);
                if (location == null) continue;
                best = pickBetterLocation(best, location);
            } catch (SecurityException ignored) {
            }
        }
        if (best != null) updateFrom(best, notify);
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) return;
        updateFrom(location, true);
    }

    private synchronized void updateFrom(Location location, boolean notify) {
        if (snapshot.valid && !shouldReplace(snapshot, location)) return;

        float accuracy = location.hasAccuracy() ? location.getAccuracy() : 100f;
        long timeMs = location.getTime() > 0 ? location.getTime() : System.currentTimeMillis();
        snapshot = new Snapshot(
                location.getLatitude(),
                location.getLongitude(),
                accuracy,
                timeMs,
                true,
                location.getProvider());
        if (notify && listener != null) listener.onLocationUpdated();
    }

    /**
     * Vybere lepší fix: satelitní má přednost, pak nižší přesnost (accuracy), pak čerstvější čas.
     */
    static Location pickBetterLocation(Location current, Location candidate) {
        if (current == null) return candidate;
        if (candidate == null) return current;

        boolean currentGps = LocationManager.GPS_PROVIDER.equals(current.getProvider());
        boolean candidateGps = LocationManager.GPS_PROVIDER.equals(candidate.getProvider());
        long now = System.currentTimeMillis();
        boolean currentRecent = now - current.getTime() <= RECENT_FIX_MS;
        boolean candidateRecent = now - candidate.getTime() <= RECENT_FIX_MS;

        if (candidateGps && !currentGps && candidateRecent) return candidate;
        if (currentGps && !candidateGps && currentRecent) return current;

        if (currentGps == candidateGps) {
            float accuracyDiff = candidate.getAccuracy() - current.getAccuracy();
            if (accuracyDiff < -3f) return candidate;
            if (accuracyDiff > 3f) return current;
            return candidate.getTime() >= current.getTime() ? candidate : current;
        }

        if (candidateGps) return candidate;
        if (currentGps) return current;
        return candidate.getAccuracy() < current.getAccuracy() ? candidate : current;
    }

    static boolean shouldReplace(Snapshot current, Location candidate) {
        if (!current.valid) return true;

        long ageMs = System.currentTimeMillis() - current.gpsTimeMs;
        boolean currentStale = ageMs > STALE_AFTER_MS;
        if (currentStale) return true;

        boolean candidateGps = LocationManager.GPS_PROVIDER.equals(candidate.getProvider());
        if (candidateGps && !current.isGpsProvider()) return true;

        if (current.isGpsProvider() && !candidateGps) {
            return ageMs > RECENT_FIX_MS;
        }

        float accuracyGain = current.accuracyM - candidate.getAccuracy();
        if (accuracyGain >= 3f) return true;
        if (accuracyGain <= -5f) return false;

        return candidate.getTime() > current.gpsTimeMs + 1000;
    }

    public String formatStatusText() {
        Snapshot s = getSnapshot();
        if (!s.valid) return "GPS čekám…";
        if (testOverride.valid) {
            return String.format(Locale.getDefault(), "TEST %.4f° %.4f°", s.latitude, s.longitude);
        }
        if (isStale()) {
            return String.format(Locale.getDefault(), "GPS ⚠ %.4f° %.4f°", s.latitude, s.longitude);
        }
        int accuracy = Math.round(s.accuracyM);
        return String.format(Locale.getDefault(), "%.4f° %.4f° ±%dm", s.latitude, s.longitude, accuracy);
    }

    public static String formatLatitude(double latitude) {
        return String.format(Locale.US, "%.6f", latitude);
    }

    public static String formatLongitude(double longitude) {
        return String.format(Locale.US, "%.6f", longitude);
    }

    public static String formatAccuracyM(float accuracyM) {
        return String.valueOf(Math.round(accuracyM));
    }

    public static String formatGpsTime(long gpsTimeMs) {
        if (gpsTimeMs <= 0) return "";
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date(gpsTimeMs));
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) { }

    @Override
    public void onProviderEnabled(String provider) { }

    @Override
    public void onProviderDisabled(String provider) { }
}
