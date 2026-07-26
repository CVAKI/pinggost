package com.cvakigod.pinggost.firebase;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class RemoteControlManager {

    private static final String TAG = "PingGhost";
    private static final String DB_URL = "https://pinggost-default-rtdb.asia-southeast1.firebasedatabase.app";

    public interface Callback {
        void onRemoteAnomalyTriggered();
        void onRemoteKillRequested();
        void onOverrideChanged(boolean enabled, float alpha, float beta, float gamma);
    }

    private final DatabaseReference controlRef;
    private final String deviceId;
    private ValueEventListener listener;

    public RemoteControlManager(Context context) {
        this.deviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        this.controlRef = FirebaseDatabase.getInstance(DB_URL)
                .getReference("devices").child(deviceId).child("control");
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void start(final Callback callback) {
        listener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean anomaly = snapshot.child("anomaly").getValue(Boolean.class);
                Boolean killApp = snapshot.child("killApp").getValue(Boolean.class);

                if (Boolean.TRUE.equals(anomaly)) {
                    controlRef.child("anomaly").setValue(false);
                    callback.onRemoteAnomalyTriggered();
                }
                if (Boolean.TRUE.equals(killApp)) {
                    controlRef.child("killApp").setValue(false);
                    callback.onRemoteKillRequested();
                }

                DataSnapshot overrideSnap = snapshot.child("override");
                Boolean enabled = overrideSnap.child("enabled").getValue(Boolean.class);
                Double alpha = overrideSnap.child("alpha").getValue(Double.class);
                Double beta = overrideSnap.child("beta").getValue(Double.class);
                Double gamma = overrideSnap.child("gamma").getValue(Double.class);

                callback.onOverrideChanged(
                        Boolean.TRUE.equals(enabled),
                        alpha != null ? alpha.floatValue() : 0f,
                        beta != null ? beta.floatValue() : 0f,
                        gamma != null ? gamma.floatValue() : 0f
                );
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Control listener cancelled: " + error.getMessage(), error.toException());
            }
        };
        controlRef.addValueEventListener(listener);
    }

    public void stop() {
        if (listener != null) {
            controlRef.removeEventListener(listener);
            listener = null;
        }
    }
}