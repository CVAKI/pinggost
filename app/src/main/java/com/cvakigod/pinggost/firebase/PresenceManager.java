package com.cvakigod.pinggost.firebase;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

public class PresenceManager {

    private static final String TAG = "PingGhost";
    private static final String DB_URL = "https://pinggost-default-rtdb.asia-southeast1.firebasedatabase.app";

    private final DatabaseReference controlRef;
    private final String deviceId;
    private ValueEventListener connectedListener;

    public PresenceManager(Context context) {
        this.deviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        this.controlRef = FirebaseDatabase.getInstance(DB_URL)
                .getReference("devices").child(deviceId).child("control");
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void start() {
        DatabaseReference connectedRef = FirebaseDatabase.getInstance(DB_URL).getReference(".info/connected");
        connectedListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean connected = snapshot.getValue(Boolean.class);
                Log.d(TAG, "Firebase connected: " + connected);
                if (Boolean.TRUE.equals(connected)) {
                    controlRef.child("online").onDisconnect().setValue(false);
                    controlRef.child("lastSeen").onDisconnect().setValue(ServerValue.TIMESTAMP);

                    Map<String, Object> update = new HashMap<>();
                    update.put("online", true);
                    update.put("lastSeen", ServerValue.TIMESTAMP);
                    controlRef.updateChildren(update)
                            .addOnSuccessListener(v -> Log.d(TAG, "Presence write OK"))
                            .addOnFailureListener(e -> Log.e(TAG, "Presence write FAILED", e));
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Presence listener cancelled: " + error.getMessage(), error.toException());
            }
        };
        connectedRef.addValueEventListener(connectedListener);
    }

    public void stop() {
        if (connectedListener != null) {
            FirebaseDatabase.getInstance(DB_URL).getReference(".info/connected").removeEventListener(connectedListener);
            connectedListener = null;
        }
        controlRef.child("online").setValue(false);
    }
}