package com.cvakigod.pinggost.firebase;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.Map;

/**
 * Streams this device's GPS location into Realtime Database at:
 *   /devices/{deviceId}/location  -> { lat, lng, accuracy, timestamp }
 *   /devices/{deviceId}/control/lastSeen -> server timestamp (heartbeat)
 *
 * IMPORTANT: DB_URL must match the one used in PresenceManager and RemoteControlManager --
 * previously this class called the no-argument FirebaseDatabase.getInstance(), which does not
 * work correctly if google-services.json is missing a databaseURL entry, so location writes
 * were silently going nowhere the dashboard could see. Now hardcoded to match.
 */
public class FirebaseLocationManager implements LocationListener {

    private static final String TAG = "PingGhost";
    private static final String DB_URL = "https://pinggost-default-rtdb.asia-southeast1.firebasedatabase.app";
    private static final long HEARTBEAT_INTERVAL_MS = 15000L; // re-push even with zero movement

    private final Context context;
    private final LocationManager locationManager;
    private final DatabaseReference deviceRef;
    private final String deviceId;
    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private volatile Location lastKnown;
    private boolean running = false;

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (lastKnown != null) {
                pushLocation(lastKnown);
            } else {
                Location fresh = getBestLastKnown();
                if (fresh != null) pushLocation(fresh);
            }
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    public FirebaseLocationManager(Context context) {
        this.context = context.getApplicationContext();
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        this.deviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        this.deviceRef = FirebaseDatabase.getInstance(DB_URL).getReference("devices").child(deviceId);
        Log.d(TAG, "Device ID: " + deviceId);
    }

    public String getDeviceId() {
        return deviceId;
    }

    /** Human-readable status for on-screen debugging -- no Logcat needed. */
    public String getDiagnostics() {
        boolean hasFine = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean gpsOn, networkOn;
        try {
            gpsOn = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            networkOn = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            gpsOn = false;
            networkOn = false;
        }
        String fix = lastKnown != null
                ? String.format("%.5f, %.5f", lastKnown.getLatitude(), lastKnown.getLongitude())
                : "no fix yet";

        return "PERM:" + (hasFine || hasCoarse ? "OK" : "DENIED")
                + "  GPS:" + (gpsOn ? "ON" : "OFF")
                + "  NET:" + (networkOn ? "ON" : "OFF")
                + "  FIX:" + fix;
    }

    public void start() {
        boolean hasFine = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "Location permission not granted -- location will not update. Grant it and call start() again.");
            return;
        }

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 8000L, 5f, this);
                Log.d(TAG, "Subscribed to GPS_PROVIDER updates");
            } else {
                Log.w(TAG, "GPS_PROVIDER is disabled on this device -- enable Location in system settings");
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 8000L, 5f, this);
                Log.d(TAG, "Subscribed to NETWORK_PROVIDER updates");
            }

            Location last = getBestLastKnown();
            if (last != null) {
                lastKnown = last;
                pushLocation(last);
            } else {
                Log.w(TAG, "No last-known location available yet -- waiting for first GPS/network fix");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException requesting location updates", e);
        }

        running = true;
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS);
    }

    public void stop() {
        running = false;
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        try {
            locationManager.removeUpdates(this);
        } catch (SecurityException ignored) { }
    }

    private Location getBestLastKnown() {
        Location best = null;
        try {
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (!locationManager.isProviderEnabled(provider)) continue;
                Location loc = locationManager.getLastKnownLocation(provider);
                if (loc != null && (best == null || loc.getAccuracy() < best.getAccuracy())) {
                    best = loc;
                }
            }
        } catch (SecurityException ignored) { }
        return best;
    }

    private void pushLocation(Location location) {
        Map<String, Object> update = new HashMap<>();
        update.put("lat", location.getLatitude());
        update.put("lng", location.getLongitude());
        update.put("accuracy", location.getAccuracy());
        update.put("timestamp", ServerValue.TIMESTAMP);

        deviceRef.child("location").setValue(update)
                .addOnSuccessListener(v -> Log.d(TAG, "Location write OK: "
                        + location.getLatitude() + ", " + location.getLongitude()))
                .addOnFailureListener(e -> Log.e(TAG, "Location write FAILED -- check database rules", e));

        deviceRef.child("control").child("lastSeen").setValue(ServerValue.TIMESTAMP);
    }

    @Override
    public void onLocationChanged(Location location) {
        lastKnown = location;
        pushLocation(location);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) { }

    @Override
    public void onProviderEnabled(String provider) {
        Log.d(TAG, "Provider enabled: " + provider);
    }

    @Override
    public void onProviderDisabled(String provider) {
        Log.w(TAG, "Provider disabled: " + provider);
    }
}