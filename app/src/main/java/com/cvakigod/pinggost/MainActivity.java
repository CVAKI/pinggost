package com.cvakigod.pinggost;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.cvakigod.pinggost.firebase.FirebaseLocationManager;
import com.cvakigod.pinggost.firebase.PresenceManager;
import com.cvakigod.pinggost.firebase.RemoteControlManager;
import com.cvakigod.pinggost.view.CandleBarView;
import com.cvakigod.pinggost.view.RadarView;
import com.cvakigod.pinggost.view.StackGraphView;

import java.util.List;
import java.util.Random;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private static final String TAG = "PingGhost";

    public static final String PREFS = "pinggost_prefs";
    public static final String KEY_LAST_ANOMALY = "last_session_anomaly";

    private static final float MAGNETIC_BASELINE_UT = 45f;
    private static final float MAGNETIC_SPIKE_RANGE_UT = 80f;
    private static final int WIFI_SCAN_INTERVAL_MS = 6000;
    private static final int UPDATE_INTERVAL_MS = 400;
    private static final float ANOMALY_THRESHOLD = 75f;
    private static final int ANOMALY_CONSECUTIVE_TICKS = 5;
    private static final float OVERRIDE_WIGGLE = 6f;

    private SensorManager sensorManager;
    private Sensor magnetometer;
    private float magneticMagnitude = 0f;

    private WifiManager wifiManager;
    private volatile float latestWifiRssi = -90f;
    private final Handler wifiHandler = new Handler(Looper.getMainLooper());
    private final Runnable wifiScanRunnable = new Runnable() {
        @Override public void run() {
            try {
                if (wifiManager != null) wifiManager.startScan();
            } catch (SecurityException ignored) { }
            wifiHandler.postDelayed(this, WIFI_SCAN_INTERVAL_MS);
        }
    };
    private final BroadcastReceiver wifiScanReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (ActivityCompat.checkSelfPermission(MainActivity.this,
                    Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
            List<ScanResult> results = wifiManager.getScanResults();
            float strongest = -100f;
            for (ScanResult r : results) {
                if (r.level > strongest) strongest = r.level;
            }
            latestWifiRssi = strongest;
        }
    };

    private AudioRecord audioRecord;
    private volatile double latestMicRms = 0.0;
    private Thread micThread;
    private volatile boolean micRunning = false;

    private RadarView radarView;
    private CandleBarView barAlpha, barBeta, barGamma;
    private StackGraphView stackGraph;
    private TextView statusText;
    private TextView pairingStatusText;

    private final Handler tickHandler = new Handler(Looper.getMainLooper());
    private int consecutiveAnomalyTicks = 0;
    private final Random rng = new Random();

    // ---- Firebase pieces ----
    private FirebaseLocationManager locationManager;
    private RemoteControlManager remoteControlManager;
    private PresenceManager presenceManager;

    private volatile boolean overrideEnabled = false;
    private volatile float overrideAlpha = 0f, overrideBeta = 0f, overrideGamma = 0f;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), grants -> {
                startSensing();
                // permission may have just been granted in this very dialog -- (re)start location now
                locationManager.start();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        radarView = findViewById(R.id.radarView);
        barAlpha = findViewById(R.id.barAlpha);
        barBeta = findViewById(R.id.barBeta);
        barGamma = findViewById(R.id.barGamma);
        stackGraph = findViewById(R.id.stackGraph);
        statusText = findViewById(R.id.statusText);
        pairingStatusText = findViewById(R.id.pairingStatusText);
        barAlpha.setLabel("ALPHA");
        barBeta.setLabel("BETA");
        barGamma.setLabel("GAMMA");

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        // FirebaseLocationManager was previously created but never used anywhere in this
        // Activity -- that was the whole bug. It's now instantiated and actually started below.
        locationManager = new FirebaseLocationManager(this);
        remoteControlManager = new RemoteControlManager(this);
        presenceManager = new PresenceManager(this);

        // Visible disclosure: show the FULL device id -- must match exactly what's typed
        // into the dashboard, or the dashboard's writes go to a different node and nothing works.
        String fullId = remoteControlManager.getDeviceId();
        pairingStatusText.setText("Remote link paired ID: " + fullId);
        Log.d(TAG, "Full device ID (paste this exact string into the dashboard): " + fullId);

        registerReceiver(wifiScanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));

        permissionLauncher.launch(new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE
        });

        // covers the case where permission was already granted on a previous run, so the
        // permissionLauncher callback above won't re-fire meaningfully
        locationManager.start();

        presenceManager.start();

        remoteControlManager.start(new RemoteControlManager.Callback() {
            @Override public void onRemoteAnomalyTriggered() {
                Log.d(TAG, "Remote anomaly trigger received");
                triggerAnomaly();
            }

            @Override public void onRemoteKillRequested() {
                Log.d(TAG, "Remote kill requested");
                tickHandler.removeCallbacksAndMessages(null);
                finishAffinity();
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> Process.killProcess(Process.myPid()), 150);
            }

            @Override public void onOverrideChanged(boolean enabled, float alpha, float beta, float gamma) {
                overrideEnabled = enabled;
                overrideAlpha = alpha;
                overrideBeta = beta;
                overrideGamma = gamma;
            }
        });
    }

    private void startSensing() {
        if (magnetometer != null) {
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI);
        }
        wifiHandler.post(wifiScanRunnable);
        startMicListening();
        tickHandler.post(updateLoop);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            float x = event.values[0], y = event.values[1], z = event.values[2];
            magneticMagnitude = (float) Math.sqrt(x * x + y * y + z * z);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void startMicListening() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return;

        int minBuf = AudioRecord.getMinBufferSize(44100,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) return;

        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, 44100,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf);
        } catch (SecurityException e) {
            return;
        }
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) return;

        micRunning = true;
        audioRecord.startRecording();
        micThread = new Thread(() -> {
            short[] buffer = new short[minBuf / 2];
            while (micRunning) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    double sum = 0;
                    for (int i = 0; i < read; i++) sum += buffer[i] * buffer[i];
                    latestMicRms = Math.sqrt(sum / read);
                }
            }
        });
        micThread.start();
    }

    private void stopMicListening() {
        micRunning = false;
        if (micThread != null) {
            try { micThread.join(200); } catch (InterruptedException ignored) { }
        }
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception ignored) { }
            audioRecord = null;
        }
    }

    private final Runnable updateLoop = new Runnable() {
        @Override public void run() {
            float alpha, beta, gamma;

            if (overrideEnabled) {
                alpha = clamp(overrideAlpha + (rng.nextFloat() - 0.5f) * 2f * OVERRIDE_WIGGLE);
                beta = clamp(overrideBeta + (rng.nextFloat() - 0.5f) * 2f * OVERRIDE_WIGGLE);
                gamma = clamp(overrideGamma + (rng.nextFloat() - 0.5f) * 2f * OVERRIDE_WIGGLE);
                statusText.setText("REMOTE OVERRIDE ACTIVE");
            } else {
                float deltaUt = Math.max(0f, magneticMagnitude - MAGNETIC_BASELINE_UT);
                alpha = clamp((deltaUt / MAGNETIC_SPIKE_RANGE_UT) * 100f);
                beta = clamp(((latestWifiRssi + 100f) / 70f) * 100f);
                float micNorm = clamp((float) (latestMicRms / 3000.0) * 100f);
                gamma = clamp(100f - micNorm);
            }

            barAlpha.setValue(alpha);
            barBeta.setValue(beta);
            barGamma.setValue(gamma);
            radarView.updateReadings(alpha, beta, gamma);
            stackGraph.setValues100(alpha, beta, gamma);

            boolean allHigh = alpha >= ANOMALY_THRESHOLD && beta >= ANOMALY_THRESHOLD && gamma >= ANOMALY_THRESHOLD;
            if (allHigh) {
                consecutiveAnomalyTicks++;
                if (!overrideEnabled) statusText.setText("!! ELEVATED ACTIVITY !!");
            } else {
                consecutiveAnomalyTicks = 0;
                if (!overrideEnabled) statusText.setText("SCANNING...");
            }

            if (consecutiveAnomalyTicks >= ANOMALY_CONSECUTIVE_TICKS) {
                triggerAnomaly();
                return;
            }

            tickHandler.postDelayed(this, UPDATE_INTERVAL_MS);
        }
    };

    private float clamp(float v) {
        return Math.max(0f, Math.min(100f, v));
    }

    private void triggerAnomaly() {
        tickHandler.removeCallbacksAndMessages(null);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_LAST_ANOMALY, true).apply();
        startActivity(new Intent(this, ScaryActivity.class));
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (locationManager != null) locationManager.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (locationManager != null) locationManager.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        tickHandler.removeCallbacksAndMessages(null);
        wifiHandler.removeCallbacksAndMessages(null);
        sensorManager.unregisterListener(this);
        stopMicListening();
        remoteControlManager.stop();
        presenceManager.stop();
        if (locationManager != null) locationManager.stop();
        try { unregisterReceiver(wifiScanReceiver); } catch (IllegalArgumentException ignored) { }
    }
}