package com.cvakigod.pinggost;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.Random;

/**
 * Full-screen "something is wrong" sequence, triggered when all three channels
 * (alpha/beta/gamma) read high at once. Sequence:
 *   1. Screen flicker / color glitch for a few seconds
 *   2. Creepy audio cue (res/raw/hrr.mp3 or .ogg - you supply the file), forced to max volume
 *   3. A few random full-black "screen off" flashes (real screen-off isn't accessible
 *      to a normal app, so this fakes it convincingly with a full-black overlay + min brightness)
 *   4. Either: fully closes the app (default), OR -- if the dashboard's Interact mode is
 *      switched on for this device -- hands off to ChatActivity instead of closing.
 *
 * On the next launch (after a normal close), SplashMeltActivity checks the
 * "last_session_anomaly" flag and plays a more corrupted melt animation as a callback to
 * what just happened.
 */
public class ScaryActivity extends AppCompatActivity {

    private static final String TAG = "PingGhost";
    private static final String DB_URL = "https://pinggost-default-rtdb.asia-southeast1.firebasedatabase.app";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random rng = new Random();
    private MediaPlayer creepyPlayer;
    private View root;
    private View blackout;
    private TextView warningText;
    private boolean sequenceRunning = true;

    // remembers the phone's original volume so it can be restored after the scare
    private AudioManager audioManager;
    private int originalMusicVolume = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_scary);

        root = findViewById(R.id.scaryRoot);
        blackout = findViewById(R.id.blackoutView);
        warningText = findViewById(R.id.warningText);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        forceMaxVolume();
        playCreepySound();
        startGlitchPhase();
    }

    /** Cranks the media volume to max so the sound cue always lands, regardless of current setting. */
    private void forceMaxVolume() {
        if (audioManager == null) return;
        try {
            originalMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0);
        } catch (SecurityException ignored) {
            // some OEMs restrict this without a permission the app doesn't request; sequence
            // still runs fine at whatever volume was already set
        }
    }

    private void restoreOriginalVolume() {
        if (audioManager != null && originalMusicVolume >= 0) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalMusicVolume, 0);
            } catch (SecurityException ignored) { }
        }
    }

    private void playCreepySound() {
        try {
            // Add a file named hrr.mp3 (or hrr.ogg) to app/src/main/res/raw/
            // e.g. a low drone, static burst, or distorted whisper. Keep it short (5-10s) and loud.
            int resId = getResources().getIdentifier("hrr", "raw", getPackageName());
            if (resId != 0) {
                creepyPlayer = MediaPlayer.create(this, resId);
                if (creepyPlayer != null) {
                    creepyPlayer.setLooping(false);
                    creepyPlayer.setVolume(1.0f, 1.0f); // max the MediaPlayer's own gain too
                    creepyPlayer.start();
                }
            }
        } catch (Exception ignored) {
            // if no asset is supplied yet, the visual sequence still runs fine without audio
        }
    }

    // ---- Phase 1: rapid color/text glitch, ~3.5s ----
    private void startGlitchPhase() {
        final long glitchDurationMs = 3500;
        final long start = System.currentTimeMillis();
        final int[] glitchColors = {
                Color.parseColor("#FF1744"), Color.parseColor("#000000"),
                Color.parseColor("#FFFFFF"), Color.parseColor("#00FF41"),
                Color.parseColor("#000000")
        };

        Runnable glitchTick = new Runnable() {
            @Override public void run() {
                if (!sequenceRunning) return;
                long elapsed = System.currentTimeMillis() - start;
                if (elapsed >= glitchDurationMs) {
                    root.setBackgroundColor(Color.BLACK);
                    startBlackoutPhase();
                    return;
                }
                root.setBackgroundColor(glitchColors[rng.nextInt(glitchColors.length)]);
                warningText.setTranslationX((rng.nextFloat() - 0.5f) * 40f);
                warningText.setTranslationY((rng.nextFloat() - 0.5f) * 40f);
                warningText.setVisibility(rng.nextBoolean() ? View.VISIBLE : View.INVISIBLE);
                handler.postDelayed(this, 60 + rng.nextInt(90)); // irregular flicker timing
            }
        };
        handler.post(glitchTick);
    }

    // ---- Phase 2: a few random fake "screen off" blackouts ----
    private void startBlackoutPhase() {
        setBrightness(0f); // dim as far as the app is allowed to
        int flashCount = 3 + rng.nextInt(3);
        scheduleBlackoutFlash(flashCount);
    }

    private void scheduleBlackoutFlash(final int remaining) {
        if (!sequenceRunning) return;
        if (remaining <= 0) {
            handler.postDelayed(this::decideEnding, 500);
            return;
        }
        blackout.setVisibility(View.VISIBLE);
        handler.postDelayed(() -> {
            blackout.setVisibility(View.INVISIBLE);
            handler.postDelayed(() -> scheduleBlackoutFlash(remaining - 1), 200 + rng.nextInt(400));
        }, 400 + rng.nextInt(700));
    }

    private void setBrightness(float value) {
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = value; // 0f = dimmest the app can force, not a true power-off
        getWindow().setAttributes(params);
    }

    // ---- Phase 3: check Interact mode, then either open the chat or close the app ----
    private void decideEnding() {
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        DatabaseReference interactRef = FirebaseDatabase.getInstance(DB_URL)
                .getReference("devices").child(deviceId).child("control").child("interactEnabled");

        interactRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean interact = snapshot.getValue(Boolean.class);
                if (Boolean.TRUE.equals(interact)) {
                    Log.d(TAG, "Interact mode is on -- opening chat instead of closing");
                    openChat();
                } else {
                    closeApp();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Could not check interactEnabled, defaulting to close", error.toException());
                closeApp();
            }
        });
    }

    private void openChat() {
        sequenceRunning = false;
        if (creepyPlayer != null) {
            try { creepyPlayer.stop(); creepyPlayer.release(); } catch (Exception ignored) { }
        }
        restoreOriginalVolume();
        setBrightness(-1f); // reset to system default brightness before handing off
        startActivity(new Intent(this, ChatActivity.class));
        finish(); // only this Activity -- MainActivity stays alive underneath, paused
    }

    // ---- Phase 3 (default path): fully close the app ----
    private void closeApp() {
        sequenceRunning = false;
        if (creepyPlayer != null) {
            try { creepyPlayer.stop(); creepyPlayer.release(); } catch (Exception ignored) { }
        }
        restoreOriginalVolume();
        finishAffinity(); // closes every activity in the task, app disappears from the user's view
        // Process.killProcess ensures nothing lingers in the background so the NEXT launch
        // is a clean cold-start -> the melt animation plays properly.
        handler.postDelayed(() -> Process.killProcess(Process.myPid()), 150);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sequenceRunning = false;
        handler.removeCallbacksAndMessages(null);
        if (creepyPlayer != null) {
            try { creepyPlayer.release(); } catch (Exception ignored) { }
        }
        restoreOriginalVolume();
    }

    @Override
    public void onBackPressed() {
        // block back-button escape during the sequence for effect
    }
}