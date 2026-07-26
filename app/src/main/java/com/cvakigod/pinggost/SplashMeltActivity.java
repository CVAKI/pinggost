package com.cvakigod.pinggost;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.cvakigod.pinggost.view.MeltView;

/**
 * Launcher activity. Every time the app opens it plays a short "melting" title animation
 * before handing off to MainActivity. If the PREVIOUS session ended in an anomaly
 * (flag set by ScaryActivity), the melt is slower, red-tinted, and glitchier -- as if
 * the app itself is still recovering from whatever just happened.
 */
public class SplashMeltActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        MeltView meltView = findViewById(R.id.meltView);

        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        boolean lastAnomaly = prefs.getBoolean(MainActivity.KEY_LAST_ANOMALY, false);
        meltView.setCorrupted(lastAnomaly);

        // consume the flag so the NEXT launch (after a normal exit) goes back to a calm melt
        prefs.edit().putBoolean(MainActivity.KEY_LAST_ANOMALY, false).apply();

        meltView.setOnMeltFinished(() -> {
            startActivity(new Intent(SplashMeltActivity.this, MainActivity.class));
            finish();
        });

        meltView.startMelt("PING GHOST");
    }
}
