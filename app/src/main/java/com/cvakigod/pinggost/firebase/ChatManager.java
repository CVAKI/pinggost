package com.cvakigod.pinggost.firebase;

import android.content.Context;
import android.provider.Settings;

import androidx.annotation.NonNull;

import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

/**
 * Handles the "Interact" feature: one-way messaging from the dashboard operator to the phone.
 *
 * Reads from:
 *   /devices/{deviceId}/chat/{pushId}        -> { type: "text"|"audio", text, audioBase64,
 *                                                  sender: "operator", timestamp }
 *   /devices/{deviceId}/control/endChat      -> one-shot: operator hit "End Chat" in the
 *                                                dashboard, so ChatActivity should glitch out
 *                                                and return to the radar screen
 *
 * The phone never writes back to /chat -- this is a receive-only "ghost line", matching the
 * one-way messaging design. New messages are picked up with onChildAdded so nothing already
 * in the log gets replayed when ChatActivity (re)attaches its listener mid-session.
 */
public class ChatManager {

    private static final String DB_URL = "https://pinggost-default-rtdb.asia-southeast1.firebasedatabase.app";

    public static class ChatMessage {
        public String type;         // "text" or "audio"
        public String text;         // present when type == "text"
        public String audioBase64;  // present when type == "audio"
        public long timestamp;
    }

    public interface Callback {
        void onMessageReceived(ChatMessage message);
        void onEndChatRequested();
    }

    private final DatabaseReference chatRef;
    private final DatabaseReference endChatRef;
    private ChildEventListener chatListener;
    private ValueEventListener endChatListener;
    private long attachedAtMillis;

    public ChatManager(Context context) {
        String deviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        DatabaseReference deviceRef = FirebaseDatabase.getInstance(DB_URL).getReference("devices").child(deviceId);
        this.chatRef = deviceRef.child("chat");
        this.endChatRef = deviceRef.child("control").child("endChat");
    }

    public void start(final Callback callback) {
        attachedAtMillis = System.currentTimeMillis();

        chatListener = new ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot snapshot, String previousChildName) {
                ChatMessage msg = new ChatMessage();
                msg.type = snapshot.child("type").getValue(String.class);
                msg.text = snapshot.child("text").getValue(String.class);
                msg.audioBase64 = snapshot.child("audioBase64").getValue(String.class);
                Long ts = snapshot.child("timestamp").getValue(Long.class);
                msg.timestamp = ts != null ? ts : 0L;

                // ignore stale history from before this screen opened, in case old messages
                // are still sitting in the database from a previous session
                if (msg.timestamp >= attachedAtMillis - 5000) {
                    callback.onMessageReceived(msg);
                }
            }

            @Override public void onChildChanged(@NonNull DataSnapshot snapshot, String previousChildName) { }
            @Override public void onChildRemoved(@NonNull DataSnapshot snapshot) { }
            @Override public void onChildMoved(@NonNull DataSnapshot snapshot, String previousChildName) { }
            @Override public void onCancelled(@NonNull DatabaseError error) { }
        };
        chatRef.addChildEventListener(chatListener);

        endChatListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean end = snapshot.getValue(Boolean.class);
                if (Boolean.TRUE.equals(end)) {
                    endChatRef.setValue(false); // consume the one-shot flag
                    callback.onEndChatRequested();
                }
            }

            @Override public void onCancelled(@NonNull DatabaseError error) { }
        };
        endChatRef.addValueEventListener(endChatListener);
    }

    public void stop() {
        if (chatListener != null) {
            chatRef.removeEventListener(chatListener);
            chatListener = null;
        }
        if (endChatListener != null) {
            endChatRef.removeEventListener(endChatListener);
            endChatListener = null;
        }
    }
}