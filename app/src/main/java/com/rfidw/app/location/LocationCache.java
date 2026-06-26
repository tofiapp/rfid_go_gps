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
 * Cache poslední známé GPS polohy – aktualizace cca každou sekundu na pozadí.
 */
public class LocationCache implements LocationListener {

    public static final long UPDATE_INTERVAL_MS = 1000;
    public static final long STALE_AFTER_MS = 30_000;

    public static class Snapshot {
        public final double latitude;
        public final double longitude;
        public final float accuracyM;
        public final long gpsTimeMs;
        public final boolean valid;

        private Snapshot(double latitude, double longitude, float accuracyM, long gpsTimeMs, boolean valid) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.accuracyM = accuracyM;
            this.gpsTimeMs = gpsTimeMs;
            this.valid = valid;
        }

        public static Snapshot empty() {
            return new Snapshot(0, 0, 0, 0, false);
        }
    }

    public interface Listener {
        void onLocationUpdated();
    }

    private final LocationManager locationManager;
    private Listener listener;
    private Snapshot snapshot = Snapshot.empty();
    private boolean running;

    public LocationCache(Context context) {
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public synchronized Snapshot getSnapshot() {
        return snapshot;
    }

    public boolean hasFix() {
        return snapshot.valid;
    }

    public boolean isStale() {
        if (!snapshot.valid) return false;
        return System.currentTimeMillis() - snapshot.gpsTimeMs > STALE_AFTER_MS;
    }

    public void start(Context context) {
        if (running) return;
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        running = true;
        seedLastKnown(context);
        requestUpdates(LocationManager.GPS_PROVIDER);
        requestUpdates(LocationManager.NETWORK_PROVIDER);
    }

    public void stop() {
        if (!running) return;
        running = false;
        try {
            locationManager.removeUpdates(this);
        } catch (SecurityException ignored) {
        }
    }

    private void requestUpdates(String provider) {
        try {
            if (!locationManager.isProviderEnabled(provider)) return;
            locationManager.requestLocationUpdates(
                    provider, UPDATE_INTERVAL_MS, 0, this, Looper.getMainLooper());
        } catch (SecurityException ignored) {
        }
    }

    private void seedLastKnown(Context context) {
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
                if (best == null || location.getTime() > best.getTime()) best = location;
            } catch (SecurityException ignored) {
            }
        }
        if (best != null) updateFrom(best, false);
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) return;
        updateFrom(location, true);
    }

    private synchronized void updateFrom(Location location, boolean notify) {
        snapshot = new Snapshot(
                location.getLatitude(),
                location.getLongitude(),
                location.getAccuracy(),
                location.getTime(),
                true);
        if (notify && listener != null) listener.onLocationUpdated();
    }

    public String formatStatusSuffix() {
        Snapshot s = getSnapshot();
        if (!s.valid) return "GPS čekám…";
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
