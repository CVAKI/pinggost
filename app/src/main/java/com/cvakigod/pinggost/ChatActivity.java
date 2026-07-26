package com.cvakigod.pinggost;

import android.graphics.Color;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.cvakigod.pinggost.firebase.ChatManager;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;

/**
 * Receive-only "ghost line" chat screen. Shown instead of the normal app-close step at the end
 * of ScaryActivity's sequence, when the dashboard operator has Interact mode switched on.
 *
 * The phone never types back -- this is intentionally one-directional. Text messages appear
 * with a typewriter reveal for effect; audio messages are decoded from base64 (already
 * distorted client-side by the dashboard's Web Audio pipeline) and played back with an
 * additional on-device pitch/speed wobble via PlaybackParams for extra "ghost voice" character.
 *
 * When the operator hits "End Chat" on the dashboard, ChatManager fires onEndChatRequested(),
 * which plays a short exit-glitch and finishes this Activity -- MainActivity (the radar screen)
 * was never killed, just paused underneath, so the user lands right back on it.
 */
public class ChatActivity extends AppCompatActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random rng = new Random();

    private View chatRoot;
    private View glitchOverlay;
    private TextView statusText;
    private android.widget.LinearLayout logContainer;
    private ScrollView scrollView;

    private ChatManager chatManager;
    private MediaPlayer audioPlayer;

    // messages are queued and revealed one at a time so overlapping text/audio doesn't collide
    private final Queue<ChatManager.ChatMessage> messageQueue = new LinkedList<>();
    private boolean processingQueue = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        chatRoot = findViewById(R.id.chatRoot);
        glitchOverlay = findViewById(R.id.chatGlitchOverlay);
        statusText = findViewById(R.id.chatStatusText);
        logContainer = findViewById(R.id.chatLogContainer);
        scrollView = findViewById(R.id.chatScrollView);

        chatManager = new ChatManager(this);

        playEntranceGlitch(() -> {
            statusText.setText("waiting for transmission...");
            chatManager.start(new ChatManager.Callback() {
                @Override public void onMessageReceived(ChatManager.ChatMessage message) {
                    runOnUiThread(() -> {
                        messageQueue.add(message);
                        processQueue();
                    });
                }

                @Override public void onEndChatRequested() {
                    runOnUiThread(ChatActivity.this::playExitGlitchAndFinish);
                }
            });
        });
    }

    // ---- entrance: quick flicker-in, like a channel locking onto a signal ----
    private void playEntranceGlitch(Runnable onDone) {
        glitchOverlay.setVisibility(View.VISIBLE);
        final int[] flickers = {80, 140, 60, 200, 90, 50};
        final int[] index = {0};
        Runnable step = new Runnable() {
            @Override public void run() {
                if (index[0] >= flickers.length) {
                    glitchOverlay.setVisibility(View.GONE);
                    onDone.run();
                    return;
                }
                glitchOverlay.setVisibility(
                        glitchOverlay.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                handler.postDelayed(this, flickers[index[0]]);
                index[0]++;
            }
        };
        handler.post(step);
    }

    // ---- exit: same idea, then close this Activity (MainActivity resumes underneath) ----
    private void playExitGlitchAndFinish() {
        statusText.setText("connection terminated");
        final int[] flickers = {60, 100, 50, 160, 70, 220};
        final int[] index = {0};
        Runnable step = new Runnable() {
            @Override public void run() {
                if (index[0] >= flickers.length) {
                    finish();
                    return;
                }
                glitchOverlay.setVisibility(
                        glitchOverlay.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                handler.postDelayed(this, flickers[index[0]]);
                index[0]++;
            }
        };
        handler.post(step);
    }

    // ---- message queue: reveal one message at a time so text/audio don't overlap ----
    private void processQueue() {
        if (processingQueue) return;
        ChatManager.ChatMessage next = messageQueue.poll();
        if (next == null) return;
        processingQueue = true;

        if ("audio".equals(next.type) && next.audioBase64 != null) {
            appendLogLine("[ incoming transmission... ]", true);
            playGhostAudio(next.audioBase64, () -> {
                processingQueue = false;
                processQueue();
            });
        } else {
            String text = next.text != null ? next.text : "...";
            typewriterReveal(text, () -> {
                processingQueue = false;
                processQueue();
            });
        }
    }

    private void appendLogLine(String text, boolean dim) {
        TextView line = new TextView(this);
        line.setText(text);
        line.setTextColor(Color.parseColor(dim ? "#556655" : "#00FF41"));
        line.setTextSize(14f);
        line.setTypeface(android.graphics.Typeface.MONOSPACE);
        line.setPadding(0, 6, 0, 6);
        logContainer.addView(line);
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void typewriterReveal(String fullText, Runnable onDone) {
        TextView line = new TextView(this);
        line.setTextColor(Color.parseColor("#00FF41"));
        line.setTextSize(15f);
        line.setTypeface(android.graphics.Typeface.MONOSPACE);
        line.setPadding(0, 6, 0, 6);
        logContainer.addView(line);

        final int[] pos = {0};
        Runnable typeStep = new Runnable() {
            @Override public void run() {
                if (pos[0] > fullText.length()) {
                    onDone.run();
                    return;
                }
                line.setText(fullText.substring(0, pos[0]));
                scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
                pos[0]++;
                // occasional stutter for a creepier typewriter feel
                int delay = 22 + (rng.nextInt(10) == 0 ? 180 : 0);
                handler.postDelayed(this, delay);
            }
        };
        handler.post(typeStep);
    }

    // ---- audio: decode base64 (already ghost-distorted by the dashboard), play with an
    // extra on-device pitch wobble layered on top for additional character ----
    private void playGhostAudio(String base64, Runnable onDone) {
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            File tempFile = new File(getCacheDir(), "ghost_msg_" + System.currentTimeMillis() + ".webm");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(bytes);
            }

            audioPlayer = new MediaPlayer();
            audioPlayer.setDataSource(tempFile.getAbsolutePath());
            audioPlayer.setOnPreparedListener(mp -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        float pitch = 0.75f + rng.nextFloat() * 0.2f; // 0.75x - 0.95x, deeper + warbly
                        PlaybackParams params = mp.getPlaybackParams();
                        params.setPitch(pitch);
                        params.setSpeed(pitch);
                        mp.setPlaybackParams(params);
                    } catch (Exception ignored) {
                        // if the OEM doesn't support pitch shifting, it still plays back normally
                    }
                }
                mp.start();
            });
            audioPlayer.setOnCompletionListener(mp -> {
                mp.release();
                tempFile.delete();
                onDone.run();
            });
            audioPlayer.setOnErrorListener((mp, what, extra) -> {
                appendLogLine("[ transmission corrupted -- signal lost ]", true);
                mp.release();
                tempFile.delete();
                onDone.run();
                return true;
            });
            audioPlayer.prepareAsync();
        } catch (Exception e) {
            appendLogLine("[ could not decode transmission ]", true);
            onDone.run();
        }
    }

    @Override
    public void onBackPressed() {
        // block back-button escape -- exit only via the dashboard's "End Chat" button
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (chatManager != null) chatManager.stop();
        if (audioPlayer != null) {
            try { audioPlayer.release(); } catch (Exception ignored) { }
        }
    }
}